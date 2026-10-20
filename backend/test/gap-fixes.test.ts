// The algorithm audit's gaps (migration 020), each tried the way a person would hit it.
// Its own database, so it can run alongside api.test.ts.
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import pg from 'pg';
import sharp from 'sharp';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Config } from '../src/config.js';
import { createPool, migrate, type Db } from '../src/db.js';
import { ensureCities } from '../src/geo.js';
import { seedCatalog, syncVocabulary } from '../src/seed.js';

const adminUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5433/postgres';
const dbName = `worthabite_gaps_${process.pid}`;

let app: FastifyInstance;
let db: Db;
let mediaDir: string;
let cityId: number;

function testUrl(name: string) {
  const u = new URL(adminUrl);
  u.pathname = `/${name}`;
  return u.toString();
}

beforeAll(async () => {
  const admin = new pg.Client({ connectionString: adminUrl });
  await admin.connect();
  await admin.query(`drop database if exists ${dbName}`);
  await admin.query(`create database ${dbName}`);
  await admin.end();

  mediaDir = await fs.mkdtemp(path.join(os.tmpdir(), 'worthabite-gaps-media-'));
  db = createPool(testUrl(dbName));
  const config: Config = {
    port: 0,
    databaseUrl: testUrl(dbName),
    jwtSecret: 'test-secret-that-is-long-enough-123456',
    mediaDir,
    publicUrl: 'http://test.local',
    adminEmails: ['admin@example.com'],
    rankRefreshSeconds: 0,
    authRateLimitPerMinute: 10_000,
    seedOnStart: false,
    demoActivity: false,
    appDownloadUrl: 'https://example.com/app',
    contactEmail: 'help@example.com',
    logLevel: 'silent',
    mapillaryToken: '',
    googlePlacesApiKey: '',
    googlePhotosDailyLimit: 30,
    routingUrl: '',
  };
  await migrate(db);
  await seedCatalog(db, mediaDir);
  await syncVocabulary(db);
  await ensureCities(db);
  app = await buildApp(config, db, { logger: false });
  cityId = (await db.query("select id from cities where slug = 'nashik'")).rows[0].id;
}, 120_000);

afterAll(async () => {
  await app?.close();
  await db?.end();
  const admin = new pg.Client({ connectionString: adminUrl });
  await admin.connect();
  await admin.query(`drop database if exists ${dbName} with (force)`);
  await admin.end();
  await fs.rm(mediaDir, { recursive: true, force: true });
});

let userCounter = 0;
async function signUp(opts: { email?: string; established?: boolean; days?: number } = {}) {
  userCounter++;
  const handle = `gapper_${userCounter}`;
  const res = await app.inject({
    method: 'POST',
    url: '/auth/register',
    payload: { email: opts.email ?? `${handle}@example.com`, password: 'correct horse', handle, acceptTerms: true },
  });
  expect(res.statusCode).toBe(201);
  const body = res.json();
  const days = opts.days ?? (opts.established ? 10 : 0);
  if (days > 0) {
    await db.query('update profiles set created_at = now() - make_interval(days => $2) where id = $1', [body.user.id, days]);
  }
  return { token: body.token as string, id: body.user.id as string, handle, auth: { authorization: `Bearer ${body.token}` } };
}

function multipart(auth: Record<string, string>, fields: Record<string, string>, image?: Buffer) {
  const boundary = '----worthabitetest' + Math.random().toString(16).slice(2);
  const parts: Buffer[] = [];
  for (const [k, v] of Object.entries(fields)) {
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`));
  }
  if (image) {
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="dish.jpg"\r\nContent-Type: image/jpeg\r\n\r\n`));
    parts.push(image, Buffer.from('\r\n'));
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`));
  return { payload: Buffer.concat(parts), headers: { ...auth, 'content-type': `multipart/form-data; boundary=${boundary}` } };
}

/** A different photo every time (a soft blur of random colours), as real photos are. */
async function photo(): Promise<Buffer> {
  const raw = Buffer.from(Array.from({ length: 16 * 12 * 3 }, () => Math.floor(Math.random() * 256)));
  return sharp(raw, { raw: { width: 16, height: 12, channels: 3 } }).resize(1600, 1200).jpeg().toBuffer();
}

async function adminAuth() {
  const login = () => app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'admin@example.com', password: 'correct horse' } });
  if ((await login()).statusCode !== 200) await signUp({ email: 'admin@example.com' });
  // A listed email may need to be verified before it is admin; signing in then makes it admin.
  await db.query("update profiles set email_verified_at = coalesce(email_verified_at, now()) where email = 'admin@example.com'");
  return { authorization: `Bearer ${(await login()).json().token}` };
}

/** A place of our own in the test city, so these tests don't lean on the demo data. */
async function testPlace(name: string, createdBy: string | null = null) {
  const { rows } = await db.query<{ id: string }>(
    `insert into restaurants (city_id, name, name_norm, geo, status, created_by)
     values ($1, $2, norm_name($2), st_setsrid(st_makepoint(73.79, 20.0), 4326)::geography, 'active', $3) returning id`,
    [cityId, name, createdBy],
  );
  return rows[0]!.id;
}

async function post(auth: Record<string, string>, fields: Record<string, string>, image?: Buffer) {
  return app.inject({
    method: 'POST', url: '/posts',
    ...multipart(auth, { idempotencyKey: `k-${Math.random().toString(36).slice(2, 12)}`, ...fields }, image ?? (await photo())),
  });
}

/** A dish made directly in the database, with one photo, [daysOld] days old. */
async function mkDish(place: string, name: string, daysOld = 60, author: string | null = null): Promise<string> {
  const d = await db.query(
    `insert into dishes (restaurant_id, city_id, name, name_norm, created_at)
     values ($1, $2, $3, norm_name($3), now() - make_interval(days => $4)) returning id`,
    [place, cityId, name, daysOld],
  );
  await db.query(
    `insert into posts (dish_id, author_id, image_path, thumb_path, created_at)
     values ($1, $2, '/media/x.webp', '/media/x.webp', now() - make_interval(days => $3))`,
    [d.rows[0].id, author, daysOld],
  );
  return d.rows[0].id;
}

const put = (u: { auth: Record<string, string> }, dish: string, kind: string) =>
  app.inject({ method: 'PUT', url: `/dishes/${dish}/engagements/${kind}`, headers: u.auth });

async function vote(userId: string, dish: string, kind: string, hoursAgo: number) {
  await db.query(
    'insert into engagements (user_id, dish_id, kind, created_at) values ($1, $2, $3, now() - make_interval(hours => $4))',
    [userId, dish, kind, hoursAgo],
  );
}

/**
 * A full rebuild that certainly runs: a full rebuild skips itself when another one is
 * running (say, the trust refresh after a burst of votes), so wait for that one first.
 * Advisory locks are re-entrant in one session, so the rebuild's own try-lock succeeds.
 */
async function rebuild() {
  await db.query(
    `select pg_advisory_lock(727001); select refresh_dish_stats(); select pg_advisory_unlock(727001);
     select pg_advisory_lock(727002); select refresh_people_pick(); select pg_advisory_unlock(727002);`,
  );
}

async function wholeFeed(tab = 'top') {
  const items: { id: string; lane: string; name: string }[] = [];
  for (let offset: number | null = 0; offset !== null; ) {
    const page = (await app.inject(`/feed?city=${cityId}&tab=${tab}&limit=50&offset=${offset}`)).json();
    items.push(...page.items);
    offset = page.nextOffset;
  }
  return items;
}

const setting = (key: string, value: number) => db.query('update ranking_config set value = $2 where key = $1', [key, value]);
const ratingOf = async (dish: string) => (await db.query('select * from dish_rating where dish_id = $1', [dish])).rows[0];
const trustOf = async (user: string) => (await db.query('select * from taster_stats where user_id = $1 and category_id = 0', [user])).rows[0];

describe('a city just starting out', () => {
  it('fills Top, Trending and Hidden gems from a few early loves, and stops once the city is busy', async () => {
    const place = await testPlace('Young Town Cafe');
    const dish = await mkDish(place, 'Young Town Sabudana Vada', 2);
    // Four two-day-old accounts: about 1.1 full-standing people, under Top's 3.
    for (let i = 0; i < 4; i++) expect((await put(await signUp({ days: 2 }), dish, 'recommend')).statusCode).toBe(200);
    expect((await db.query('select city_is_young($1) as y', [cityId])).rows[0].y).toBe(true);
    expect((await wholeFeed()).find((i) => i.id === dish)?.lane).toBe('top');
    expect((await wholeFeed('trending')).some((i) => i.id === dish)).toBe(true);
    // A dish loved only by accounts made a moment ago stays out, young city or not.
    const rush = await mkDish(place, 'Young Town Rushed Roll', 2);
    for (let i = 0; i < 3; i++) await put(await signUp(), rush, 'recommend');
    expect((await wholeFeed()).some((i) => i.id === rush && i.lane === 'top')).toBe(false);
    // Busy city: the usual minimum is back.
    await setting('young_city_votes', 0);
    expect((await wholeFeed()).some((i) => i.id === dish && i.lane === 'top')).toBe(false);
    await setting('young_city_votes', 200);
  });

  it('lists places nobody posted from yet in Explore while the city is young', async () => {
    const empty = await testPlace('Young Empty Kitchen');
    const all: string[] = [];
    for (let offset: number | null = 0; offset !== null; ) {
      const page = (await app.inject(`/places?city=${cityId}&sort=dishes&limit=50&offset=${offset}`)).json();
      all.push(...page.items.map((i: { id: string }) => i.id));
      offset = page.nextOffset;
    }
    expect(all).toContain(empty);
    const busy = (await app.inject(`/places?city=${cityId}&all=false&limit=50`)).json().items.map((i: { id: string }) => i.id);
    expect(busy).not.toContain(empty);
  });

  it('finds the places for a craving even when nobody has posted the dish there', async () => {
    await testPlace('Slice Of Pizza Corner');
    const res = (await app.inject(`/craving?q=pizza&city=${cityId}`)).json();
    expect(res.places.map((p: { name: string }) => p.name)).toContain('Slice Of Pizza Corner');
  });
});

describe('new places', () => {
  it('lets a second diner post at a place still waiting for approval, and finds it in the place picker', async () => {
    const a = await signUp({ established: true });
    const first = await post(a.auth, { dishName: 'Pending Thecha', newPlaceName: 'Waiting Room Dhaba', newPlaceLat: '20.01', newPlaceLng: '73.80' });
    expect(first.statusCode).toBe(201);
    expect(first.json().restaurantStatus).toBe('pending');
    const place = first.json().restaurantId;
    const near = (await app.inject(`/restaurants/nearby?lat=20.01&lng=73.80&q=waiting`)).json();
    expect(near.find((p: { id: string }) => p.id === place)?.pending).toBe(true);
    const b = await signUp({ established: true });
    const second = await post(b.auth, { dishName: 'Pending Bhakri', restaurantId: place });
    expect(second.statusCode).toBe(201);
    // Two established people posted there: the timer approves it.
    expect((await db.query('select auto_approve_places() as n')).rows[0].n).toBeGreaterThanOrEqual(1);
    expect((await db.query('select status from restaurants where id = $1', [place])).rows[0].status).toBe('active');
  });

  it('approves a lone place after a few days, but never one that looks like a place already live', async () => {
    const a = await signUp({ established: true });
    const lone = (await post(a.auth, { dishName: 'Lone Poha', newPlaceName: 'Far Field Canteen', newPlaceLat: '20.05', newPlaceLng: '73.85' })).json().restaurantId;
    await testPlace('Copy Cat Cafe');
    const copy = (await post(a.auth, { dishName: 'Copy Coffee', newPlaceName: 'Copy Cat Cafe Nashik', newPlaceLat: '20.0', newPlaceLng: '73.79' })).json().restaurantId;
    await db.query("update restaurants set created_at = now() - interval '4 days' where id = any($1)", [[lone, copy]]);
    await db.query('select auto_approve_places()');
    const status = async (id: string) => (await db.query('select status from restaurants where id = $1', [id])).rows[0].status;
    expect(await status(lone)).toBe('active');
    expect(await status(copy)).toBe('pending');
    // Two throwaway accounts can't approve a place either.
    const fake = (await post((await signUp()).auth, { dishName: 'Fake Vada', newPlaceName: 'Ghost Kitchen', newPlaceLat: '20.07', newPlaceLng: '73.70' })).json().restaurantId;
    await post((await signUp()).auth, { dishName: 'Fake Pav', restaurantId: fake });
    await db.query('select auto_approve_places()');
    expect(await status(fake)).toBe('pending');
  });

  it('warns about a similar place either way round ("Sai" vs "Hotel Sai Palace Family Restaurant")', async () => {
    await testPlace('Sai');
    const res = (await app.inject(`/restaurants/similar?name=${encodeURIComponent('Hotel Sai Palace Family Restaurant')}&lat=20.0&lng=73.79`)).json();
    expect(res.map((r: { name: string }) => r.name)).toContain('Sai');
  });
});

describe('diet', () => {
  it('keeps meat away from vegetarians and egg-eaters, and lets an egg-eater have eggs', async () => {
    const fits = async (diet: string, name: string, isVeg: boolean | null = null, cat: string | null = null) =>
      (await db.query('select dish_fits_diet($1, $2, (select id from dish_categories where slug = $4), norm_name($3)) as ok',
        [diet, isVeg, name, cat])).rows[0].ok;
    expect(await fits('veg', 'Chicken Biryani')).toBe(false);
    expect(await fits('egg', 'Chicken Biryani')).toBe(false);
    expect(await fits('egg', 'Mutton Seekh Kebab')).toBe(false);
    expect(await fits('veg', 'Veg Biryani')).toBe(true);
    expect(await fits('veg', 'Paneer Tikka')).toBe(true);
    expect(await fits('veg', 'Egg Bhurji')).toBe(false);
    expect(await fits('egg', 'Egg Bhurji')).toBe(true);
    expect(await fits('veg', 'Biryani', null, 'biryani')).toBe(false); // can't tell: not for a vegetarian
    expect(await fits('veg', 'Misal Pav')).toBe(true);
    expect(await fits('nonveg', 'Chicken Biryani')).toBe(true);
    expect(await fits('veg', 'Hakka Noodles', true)).toBe(true); // marked veg is veg
  });

  it("never shows a vegetarian meat in For You", async () => {
    const place = await testPlace('Diet Darbar');
    const meat = await mkDish(place, 'Diet Darbar Chicken Tikka', 3);
    const veg = await mkDish(place, 'Diet Darbar Paneer Tikka', 3);
    for (let i = 0; i < 3; i++) {
      const u = await signUp({ established: true });
      await put(u, meat, 'recommend');
      await put(u, veg, 'recommend');
    }
    const me = await signUp();
    await app.inject({ method: 'PUT', url: '/me/taste', headers: me.auth, payload: { categories: [], diet: 'veg', spice: 3 } });
    await rebuild();
    const ids = (await app.inject({ url: `/feed?city=${cityId}&tab=foryou&limit=50`, headers: me.auth })).json().items.map((i: { id: string }) => i.id);
    expect(ids).not.toContain(meat);
    expect(ids).toContain(veg); // the city's best still fill For You for someone new
  });
});

describe('For You and Taste Match', () => {
  it('names only someone who loved the dish, and never someone who blocked you', async () => {
    const place = await testPlace('Because Bistro');
    const dishes = [];
    for (let i = 0; i < 4; i++) dishes.push(await mkDish(place, `Because Dish ${i}`, 10));
    const me = await signUp({ established: true });
    const twin = await signUp({ established: true });
    const passer = await signUp({ established: true });
    // twin and me agree on three dishes (days apart, so not lockstep)...
    for (let i = 0; i < 3; i++) {
      await vote(me.id, dishes[i]!, 'recommend', 24 * (30 + i * 3));
      await vote(twin.id, dishes[i]!, 'recommend', 24 * (20 + i * 3));
      await vote(passer.id, dishes[i]!, 'recommend', 24 * (10 + i * 3));
    }
    // ...the passer said "not for me" to the fourth; twin loved it.
    await vote(passer.id, dishes[3]!, 'pass', 24 * 5);
    await vote(twin.id, dishes[3]!, 'recommend', 24 * 4);
    await rebuild();
    const items = (await app.inject({ url: `/feed?city=${cityId}&tab=foryou&limit=50`, headers: me.auth })).json().items;
    const card = items.find((i: { id: string }) => i.id === dishes[3]);
    expect(card).toBeDefined();
    expect([twin.handle, null]).toContain(card.because.handle);
    expect(card.because.handle).not.toBe(passer.handle);
    // twin blocks me: their name goes from my For You.
    await app.inject({ method: 'POST', url: '/blocks', headers: twin.auth, payload: { handle: me.handle } });
    const after = (await app.inject({ url: `/feed?city=${cityId}&tab=foryou&limit=50`, headers: me.auth })).json().items;
    expect(after.some((i: { because: { handle: string | null } }) => i.because.handle === twin.handle)).toBe(false);
  });

  it('lets answers alone say at most 75%: people who voted like you come first', async () => {
    const cats = (await db.query("select id from dish_categories where slug in ('misal', 'pizza-pasta') order by id")).rows.map((r) => r.id);
    const me = await signUp({ established: true });
    const stranger = await signUp({ established: true });
    for (const u of [me, stranger]) {
      await app.inject({ method: 'PUT', url: '/me/taste', headers: u.auth, payload: { categories: cats, diet: 'veg', spice: 3 } });
    }
    const m = (await db.query('select match from taste_matches($1, $2)', [me.id, stranger.id])).rows[0];
    expect(m.match).toBeLessThanOrEqual(0.75);
  });
});

describe('cheating', () => {
  it("flags a copycat who votes right after an expert, without lowering the expert's standing", async () => {
    const place = await testPlace('Copycat Corner');
    const expert = await signUp({ days: 200 });
    const copycat = await signUp({ days: 200 });
    for (let i = 0; i < 10; i++) {
      const d = await mkDish(place, `Copycat Dish ${i}`, 60);
      await vote(expert.id, d, 'recommend', 24 * (40 - i * 3));
      // The copycat follows on three of them, an hour later each time.
      if (i < 3) await vote(copycat.id, d, 'recommend', 24 * (40 - i * 3) - 1);
    }
    await rebuild();
    const twins = async (id: string) => (await db.query('select twins from voter_independence where user_id = $1', [id])).rows[0]?.twins ?? 0;
    expect(await twins(copycat.id)).toBe(1);
    expect(await twins(expert.id)).toBe(0);
  });

  it('keeps a taken-back vote in your record, and a re-vote keeps its first date', async () => {
    const place = await testPlace('Undo Udupi');
    const dish = await mkDish(place, 'Undo Dosa', 30);
    const u = await signUp({ established: true });
    await vote(u.id, dish, 'recommend', 24 * 10);
    const first = (await db.query("select created_at from engagements where user_id = $1 and dish_id = $2 and kind = 'recommend'", [u.id, dish])).rows[0].created_at;
    expect((await app.inject({ method: 'DELETE', url: `/dishes/${dish}/engagements/recommend`, headers: u.auth })).statusCode).toBe(200);
    expect((await db.query('select kind from pick_history where user_id = $1 and dish_id = $2', [u.id, dish])).rows).toEqual([{ kind: 'recommend' }]);
    expect((await put(u, dish, 'recommend')).statusCode).toBe(200);
    const again = (await db.query("select created_at from engagements where user_id = $1 and dish_id = $2 and kind = 'recommend'", [u.id, dish])).rows[0].created_at;
    expect(again.getTime()).toBe(first.getTime());
    expect((await db.query('select count(*)::int as n from pick_history where user_id = $1', [u.id])).rows[0].n).toBe(0);
  });

  it('counts a wrong call that was flipped afterwards against your trust', async () => {
    const place = await testPlace('Flip Flop Foods');
    const dish = await mkDish(place, 'Flip Bhel', 30);
    const flipper = await signUp({ days: 100 });
    await vote(flipper.id, dish, 'recommend', 24 * 20);
    for (let i = 0; i < 4; i++) await vote((await signUp({ days: 100 })).id, dish, 'pass', 24 * (15 - i * 2));
    // Seeing everyone pass, they flip their love to "not for me".
    expect((await put(flipper, dish, 'pass')).statusCode).toBe(200);
    await rebuild();
    const t = await trustOf(flipper.id);
    // The love still counts as their pick (and a wrong one), the flip earns nothing.
    expect(t).toBeDefined();
    expect(t.agreed).toBeLessThanOrEqual(0);
  });

  it('keeps one verdict per person and dish, even when love and "not for me" are tapped at once', async () => {
    const place = await testPlace('Double Tap Dhaba');
    const dish = await mkDish(place, 'Double Tap Thali', 10);
    const u = await signUp({ established: true });
    const res = await Promise.all([put(u, dish, 'recommend'), put(u, dish, 'pass'), put(u, dish, 'recommend'), put(u, dish, 'pass')]);
    for (const r of res) expect(r.statusCode).toBe(200);
    const kinds = (await db.query("select kind from engagements where user_id = $1 and dish_id = $2 and kind in ('recommend', 'pass')", [u.id, dish])).rows;
    expect(kinds.length).toBe(1);
  });

  it("doesn't count votes at a place still waiting for approval", async () => {
    const a = await signUp({ established: true });
    const r = (await post(a.auth, { dishName: 'Unapproved Usal', newPlaceName: 'Not Yet Mess', newPlaceLat: '20.09', newPlaceLng: '73.71' })).json();
    for (let i = 0; i < 3; i++) await put(await signUp({ established: true }), r.dishId, 'recommend');
    await rebuild();
    expect((await ratingOf(r.dishId)).w_loves).toBe(0);
  });

  it("doesn't let whoever added a place rate it after it's merged into another listing", async () => {
    const owner = await signUp({ established: true });
    const from = await testPlace('Owner Merge One', owner.id);
    const into = await testPlace('Owner Merge Two');
    const dish = await mkDish(into, 'Owner Merge Kulfi', 10);
    const admin = await adminAuth();
    expect((await app.inject({ method: 'POST', url: `/admin/restaurants/${from}/merge`, headers: admin, payload: { into } })).statusCode).toBe(200);
    expect((await put(owner, dish, 'recommend')).statusCode).toBe(200);
    expect((await ratingOf(dish)).loves).toBe(0);
  });

  it('weighs reports by standing, and never hides a whole dish on reports alone', async () => {
    const place = await testPlace('Report Rasoi');
    const dish = await mkDish(place, 'Report Pithla', 10);
    const postId = (await db.query('select id from posts where dish_id = $1', [dish])).rows[0].id;
    // Three accounts two days old: 3 x 2/7 of a person, not enough to hide a post.
    for (let i = 0; i < 3; i++) {
      const r = await app.inject({ method: 'POST', url: '/reports', headers: (await signUp({ days: 2 })).auth, payload: { targetType: 'post', targetId: postId, reason: 'spam' } });
      expect(r.json().hidden).toBe(false);
    }
    for (let i = 0; i < 4; i++) {
      const r = await app.inject({ method: 'POST', url: '/reports', headers: (await signUp({ established: true })).auth, payload: { targetType: 'dish', targetId: dish, reason: 'spam' } });
      expect(r.json().hidden).toBe(false);
    }
    expect((await db.query('select status from dishes where id = $1', [dish])).rows[0].status).toBe('live');
  });
});

describe('polish', () => {
  it("matches a cuisine by whole words: Thai isn't Thali", async () => {
    const thali = await testPlace('Whole Word Thali House');
    await db.query("update restaurants set cuisine = 'thali' where id = $1", [thali]);
    const thai = await testPlace('Whole Word Bangkok');
    await db.query("update restaurants set cuisine = 'thai' where id = $1", [thai]);
    const ids = (await app.inject(`/places?city=${cityId}&cuisine=thai&all=true&limit=50`)).json().items.map((i: { id: string }) => i.id);
    expect(ids).toContain(thai);
    expect(ids).not.toContain(thali);
  });

  it('keeps the feed order while you scroll, even as votes come in', async () => {
    const seed = 'scroll-1';
    const page = async (offset: number) =>
      (await app.inject(`/feed?city=${cityId}&tab=top&limit=5&offset=${offset}&seed=${seed}`)).json().items.map((i: { id: string }) => i.id);
    const first = await page(0);
    // Someone loves a dish from further down a lot: it would jump to the top.
    const rest = await wholeFeed();
    const last = rest[rest.length - 1]!.id;
    for (let i = 0; i < 5; i++) await put(await signUp({ established: true }), last, 'recommend');
    const second = await page(5);
    expect(second.filter((id: string) => first.includes(id))).toEqual([]);
  });

  it('shows the latest loves of people you follow first on Home', async () => {
    const place = await testPlace('Follow Feed Kitchen');
    const me = await signUp({ established: true });
    const friend = await signUp({ established: true });
    await app.inject({ method: 'PUT', url: `/users/${friend.handle}/follow`, headers: me.auth });
    const dishes = [];
    for (let i = 0; i < 12; i++) dishes.push(await mkDish(place, `Follow Dish ${i}`, 20));
    for (let i = 0; i < 12; i++) await vote(friend.id, dishes[i]!, 'recommend', 24 * (12 - i)); // dish 11 is the latest
    const home = (await app.inject({ url: `/home?city=${cityId}`, headers: me.auth })).json();
    const shown = home.following.map((f: { card: { id: string } }) => f.card.id);
    expect(shown[0]).toBe(dishes[11]);
    expect(shown).not.toContain(dishes[0]);
  });

  it('suggests people by full name with spaces, never yourself', async () => {
    const me = await signUp({ established: true });
    await db.query("update profiles set display_name = 'Ravi Kulkarni' where id = $1", [me.id]);
    const other = await signUp({ established: true });
    await db.query("update profiles set display_name = 'Ravi Kumar' where id = $1", [other.id]);
    const res = (await app.inject({ url: `/search/suggest?q=${encodeURIComponent('ravi ku')}&city=${cityId}`, headers: me.auth })).json();
    const people = res.items.filter((s: { type: string }) => s.type === 'person').map((s: { id: string }) => s.id);
    expect(people).toContain(other.handle);
    expect(people).not.toContain(me.handle);
  });

  it('re-picks the cover and re-rates the dish when its photo is deleted', async () => {
    const place = await testPlace('Cover Cafe');
    const a = await signUp({ established: true });
    const b = await signUp({ established: true });
    const p1 = (await post(a.auth, { dishName: 'Cover Cutlet', restaurantId: place })).json();
    const p2 = (await post(b.auth, { dishName: 'Cover Cutlet', restaurantId: place })).json();
    expect(p2.dishId).toBe(p1.dishId);
    expect((await app.inject({ method: 'DELETE', url: `/posts/${p1.id}`, headers: a.auth })).json()).toEqual({ ok: true });
    expect((await db.query('select cover_post_id from dishes where id = $1', [p1.dishId])).rows[0].cover_post_id).toBe(p2.id);
  });

  it('cleans up reports and chats left behind by deleted accounts', async () => {
    const u = await signUp({ established: true });
    await db.query("insert into reports (reporter_id, target_type, target_id, reason) values ($1, 'post', gen_random_uuid(), 'spam')", [u.id]);
    await db.query('select clean_orphans()');
    expect((await db.query('select count(*)::int as n from reports where reporter_id = $1', [u.id])).rows[0].n).toBe(0);
  });

  it('accepts the new settings in the dashboard', async () => {
    const admin = await adminAuth();
    for (const [key, value] of [['young_city_votes', 150], ['pick_full_backers', 3], ['revote_keep_days', 30], ['place_auto_approve_posters', 2], ['place_auto_approve_days', 3]] as const) {
      const r = await app.inject({ method: 'PUT', url: `/admin/ranking-config/${key}`, headers: admin, payload: { value } });
      expect(r.statusCode).toBe(200);
    }
    await setting('young_city_votes', 200);
  });
});
