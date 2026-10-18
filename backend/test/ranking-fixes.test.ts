// Ranking and posting fixes (migration 015), against a real Postgres + PostGIS database.
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
import { phashBands } from '../src/routes/posts.js';
import { seedCatalog, syncVocabulary } from '../src/seed.js';

const adminUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5433/postgres';
const dbName = `worthabite_rank_${process.pid}`;

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

  mediaDir = await fs.mkdtemp(path.join(os.tmpdir(), 'worthabite-rank-media-'));
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
  const handle = `ranker_${userCounter}`;
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

describe('posting lands on the right dish', () => {
  it('posts a merged-away name to the dish it became, and refuses a dish an admin hid', async () => {
    const place = await testPlace('Merge Chai Point');
    const u = await signUp({ established: true });
    const into = (await post(u.auth, { dishName: 'Masala Chai', restaurantId: place })).json().dishId;
    const from = await mkDish(place, 'Kadak Cutting', 1);
    const admin = await adminAuth();
    expect((await app.inject({ method: 'POST', url: `/admin/dishes/${from}/merge`, headers: admin, payload: { into } })).statusCode).toBe(200);

    // Someone posting the old name lands on the merged dish, never the hidden one.
    const v = await signUp({ established: true });
    const res = await post(v.auth, { dishName: 'Kadak Cutting', restaurantId: place });
    expect(res.statusCode).toBe(201);
    expect(res.json()).toMatchObject({ dishId: into, dishName: 'Masala Chai', status: 'live' });
    expect((await db.query('select status from dishes where id = $1', [from])).rows[0].status).toBe('hidden');

    // The daily limit follows the dish it lands on: the old name doesn't give a second photo.
    const again = await post(u.auth, { dishName: 'Kadak Cutting', restaurantId: place });
    expect(again.statusCode).toBe(429);
    expect(again.json().error).toBe('dish_daily_limit');

    // A dish an admin hid stays hidden.
    const hidden = await mkDish(place, 'Banned Bun', 1);
    await db.query("update dishes set status = 'hidden' where id = $1", [hidden]);
    const blocked = await post(v.auth, { dishName: 'Banned Bun', restaurantId: place });
    expect(blocked.statusCode).toBe(409);
    expect(blocked.json().error).toBe('dish_unavailable');
    expect((await db.query('select status from dishes where id = $1', [hidden])).rows[0].status).toBe('hidden');
  });

  it('joins a typo to the dish already there, but keeps a longer name its own dish', async () => {
    // Trigram similarity alone can't separate the two: the longer name is closer than the typo.
    const { rows } = await db.query(
      `select similarity('paneer tikka', 'paneer tikka masala') as longer, similarity('butter chicken', 'buter chiken') as typo,
              levenshtein('paneer tikka', 'paneer tikka masala') as longer_edits, levenshtein('butter chicken', 'buter chiken') as typo_edits`,
    );
    expect(rows[0].longer).toBeCloseTo(0.65, 2);
    expect(rows[0].typo).toBeLessThan(rows[0].longer);
    expect(rows[0]).toMatchObject({ longer_edits: 7, typo_edits: 2 });

    const place = await testPlace('Typo Tandoor');
    const a = await signUp({ established: true });
    const b = await signUp({ established: true });
    const butter = (await post(a.auth, { dishName: 'Butter Chicken', restaurantId: place })).json().dishId;
    const typo = await post(b.auth, { dishName: 'Buter Chiken', restaurantId: place, verdict: 'loved' });
    expect(typo.statusCode).toBe(201);
    expect(typo.json()).toMatchObject({ dishId: butter, dishName: 'Butter Chicken' });
    expect((await db.query('select count(*)::int as n from dishes where restaurant_id = $1', [place])).rows[0].n).toBe(1);
    expect((await db.query('select loves from dish_rating where dish_id = $1', [butter])).rows[0].loves).toBe(1);

    const tikka = (await post(a.auth, { dishName: 'Paneer Tikka', restaurantId: place })).json().dishId;
    const masala = (await post(a.auth, { dishName: 'Paneer Tikka Masala', restaurantId: place })).json();
    expect(masala.dishName).toBe('Paneer Tikka Masala');
    expect(masala.dishId).not.toBe(tikka);
  });
});

describe('daily limits and Fresh', () => {
  it('still counts a deleted post toward the per-dish daily limit', async () => {
    const place = await testPlace('Repost Vada');
    const u = await signUp({ established: true });
    const first = await post(u.auth, { dishName: 'Vada Pav', restaurantId: place });
    expect(first.statusCode).toBe(201);
    const del = await app.inject({ method: 'DELETE', url: `/posts/${first.json().id}`, headers: u.auth });
    expect(del.json()).toEqual({ ok: true });
    const again = await post(u.auth, { dishName: 'Vada Pav', restaurantId: place });
    expect(again.statusCode).toBe(429);
    expect(again.json().error).toBe('dish_daily_limit');
    const n = (await db.query('select count(*)::int as n from post_uploads where author_id = $1', [u.id])).rows[0].n;
    expect(n).toBe(1);
  });

  it("keeps the place's own re-posts out of Fresh; a diner's photo still freshens a dish", async () => {
    const owner = await signUp({ established: true });
    const place = await testPlace('Owner Fresh Cafe', owner.id);
    const dish = await mkDish(place, 'Owner Old Sandwich', 60, owner.id);
    await db.query(`insert into posts (dish_id, author_id, image_path, thumb_path) values ($1, $2, '/media/y.webp', '/media/y.webp')`, [dish, owner.id]);
    expect((await wholeFeed()).find((i) => i.id === dish)).toBeUndefined();
    const diner = await signUp({ established: true });
    await db.query(`insert into posts (dish_id, author_id, image_path, thumb_path) values ($1, $2, '/media/z.webp', '/media/z.webp')`, [dish, diner.id]);
    expect((await wholeFeed()).find((i) => i.id === dish)?.lane).toBe('fresh');
  });
});

describe('genuine photos', () => {
  it('gives a held copy no vote, and finds a resized copy through the fingerprint bands', async () => {
    const place = await testPlace('Band Bistro');
    const a = await signUp({ established: true });
    const img = await photo();
    const first = await post(a.auth, { dishName: 'Misal Special', restaurantId: place }, img);
    expect(first.statusCode).toBe(201);
    const { rows } = await db.query('select phash::text as phash, ph_bands from posts where id = $1', [first.json().id]);
    expect(rows[0].ph_bands).toEqual(phashBands(rows[0].phash));

    // The TypeScript bands match the database's, negative fingerprints included.
    for (const h of ['0', '-1', '-9223372036854775808', '9223372036854775807', '-6148914691236517206', '1234567890123']) {
      const sql = (await db.query('select phash_bands($1::bigint) as b', [h])).rows[0].b;
      expect(phashBands(h)).toEqual(sql);
    }

    // A smaller, recompressed copy by someone else is found (it shares bands) and held.
    const smaller = await sharp(img).resize(800).jpeg({ quality: 60 }).toBuffer();
    const b = await signUp({ established: true });
    const copy = await post(b.auth, { dishName: 'Misal Special', restaurantId: place, verdict: 'loved' }, smaller);
    expect(copy.statusCode).toBe(201);
    expect(copy.json()).toMatchObject({ status: 'pending', held: true });
    const copyRow = (await db.query('select ph_bands, copy_of from posts where id = $1', [copy.json().id])).rows[0];
    expect(copyRow.copy_of).toBe(first.json().id);
    const shared = (copyRow.ph_bands as number[]).filter((x) => (rows[0].ph_bands as number[]).includes(x));
    expect(shared.length).toBeGreaterThanOrEqual(1);
    // ...and its "Loved it" isn't counted.
    const dish = first.json().dishId;
    expect((await db.query('select loves from dish_rating where dish_id = $1', [dish])).rows[0].loves).toBe(0);
    expect((await db.query('select count(*)::int as n from engagements where user_id = $1', [b.id])).rows[0].n).toBe(0);
  });
});

describe('fair ranking', () => {
  it('keeps a dish with only saves and shares out of Top', async () => {
    const place = await testPlace('Top Gate Dhaba');
    const saved = await mkDish(place, 'Saved Only Thali');
    const loved = await mkDish(place, 'Loved Thali');
    for (let i = 0; i < 4; i++) {
      const u = await signUp({ established: true });
      expect((await put(u, saved, 'save')).statusCode).toBe(200);
      await app.inject({ method: 'POST', url: `/dishes/${saved}/share`, headers: u.auth });
    }
    for (let i = 0; i < 3; i++) expect((await put(await signUp({ established: true }), loved, 'recommend')).statusCode).toBe(200);
    const st = (await db.query('select w_endorsers from dish_stats where dish_id = $1', [saved])).rows[0];
    expect(st.w_endorsers).toBeGreaterThanOrEqual(3);
    const dr = async (id: string) => (await db.query('select w_voters from dish_rating where dish_id = $1', [id])).rows[0].w_voters;
    expect(await dr(saved)).toBe(0);
    expect(await dr(loved)).toBeCloseTo(3, 5);
    const feed = await wholeFeed();
    expect(feed.find((i) => i.id === loved)?.lane).toBe('top');
    expect(feed.find((i) => i.id === saved && i.lane === 'top')).toBeUndefined();
  });

  it("gives the place's owner no trust from its dishes, and doesn't count them as agreeing", async () => {
    const owner = await signUp({ days: 100 });
    const place = await testPlace('Owner Trust Mess', owner.id);
    const dish = await mkDish(place, 'Owner Trust Bhaji');
    // The owner loves it first, and four diners agree later: no credit for the owner.
    await vote(owner.id, dish, 'recommend', 24 * 10);
    for (let i = 0; i < 4; i++) await vote((await signUp({ days: 100 })).id, dish, 'recommend', 24 * 5 - i);
    // A diner's pick that only the owner followed earns nothing either.
    const early = await signUp({ days: 100 });
    const other = await mkDish(place, 'Owner Trust Usal');
    await vote(early.id, other, 'pass', 24 * 10);
    await vote(owner.id, other, 'pass', 24 * 5);
    await rebuild();
    const stats = async (id: string) => (await db.query('select * from taster_stats where user_id = $1', [id])).rows;
    expect(await stats(owner.id)).toEqual([]);
    expect(await stats(early.id)).toEqual([]);
    // The owner's vote doesn't rate the dish either (as before).
    expect((await db.query('select loves, w_voters from dish_rating where dish_id = $1', [dish])).rows[0]).toMatchObject({ loves: 4, w_voters: 4 });
  });

  it('lets a verified visit add weight to a vote, but not make a low-standing account a champion', async () => {
    const place = await testPlace('Visit Champ Corner');
    // Three days old: standing 3/7 (0.43), under the 0.5 a champion needs; x1.25 would be 0.54.
    const u = await signUp({ days: 3 });
    const res = await post(u.auth, { dishName: 'Visit Kachori', restaurantId: place, lat: '20.0001', lng: '73.7901', capture: 'camera', verdict: 'loved' });
    expect(res.json().visit).toBe('verified');
    const r = (await db.query('select loves, w_loves, w_voters, champion_id from dish_rating where dish_id = $1', [res.json().dishId])).rows[0];
    expect(r.loves).toBe(1);
    expect(r.champion_id).toBeNull();
    // The vote itself still counts the visit: trust (0.6) x standing x 1.25.
    expect(r.w_loves).toBeCloseTo(0.6 * r.w_voters * 1.25, 2);
  });

  it('sorts places by how much their dishes are loved, not by how many accounts clicked', async () => {
    const crowd = await testPlace('Clicked Canteen');
    const real = await testPlace('Loved Lunch Home');
    const poster = await signUp({ established: true });
    const c = (await post(poster.auth, { dishName: 'Clicked Poha', restaurantId: crowd })).json().dishId;
    const r = (await post(poster.auth, { dishName: 'Loved Poha', restaurantId: real })).json().dishId;
    // Five accounts made a moment ago against one established diner.
    for (let i = 0; i < 5; i++) await put(await signUp(), c, 'recommend');
    await put(await signUp({ established: true }), r, 'recommend');
    // A hidden dish's loves don't count for its place.
    const hiddenPlace = await testPlace('Hidden Dish House');
    const h = (await post(poster.auth, { dishName: 'Hidden Poha', restaurantId: hiddenPlace })).json().dishId;
    await post(poster.auth, { dishName: 'Shown Upma', restaurantId: hiddenPlace });
    for (let i = 0; i < 3; i++) await put(await signUp({ established: true }), h, 'recommend');
    await db.query("update dishes set status = 'hidden' where id = $1", [h]);
    await db.query('select refresh_people_pick($1)', [h]);

    const { items } = (await app.inject(`/places?city=${cityId}&sort=loved&limit=50`)).json();
    const names = items.map((i: { name: string }) => i.name);
    expect(names).toContain('Clicked Canteen');
    expect(names.indexOf('Loved Lunch Home')).toBeGreaterThanOrEqual(0);
    expect(names.indexOf('Loved Lunch Home')).toBeLessThan(names.indexOf('Clicked Canteen'));
    expect(items.find((i: { name: string }) => i.name === 'Hidden Dish House').lovers).toBe(0);
  });
});

describe('cards', () => {
  it("lists only people who loved a dish, never the owner or someone you blocked", async () => {
    const owner = await signUp({ established: true });
    const place = await testPlace('Card Corner', owner.id);
    const dish = await mkDish(place, 'Card Dabeli', 1);
    const fan = await signUp({ established: true });
    const blocked = await signUp({ established: true });
    const taster = await signUp({ established: true });
    await put(owner, dish, 'recommend');
    await put(fan, dish, 'recommend');
    await put(blocked, dish, 'recommend');
    await put(taster, dish, 'tried');
    const viewer = await signUp();
    await app.inject({ method: 'POST', url: '/blocks', headers: viewer.auth, payload: { handle: blocked.handle } });
    const page = (await app.inject({ url: `/dishes/${dish}`, headers: viewer.auth })).json();
    expect(page.recentRecommenders.map((x: { handle: string }) => x.handle)).toEqual([fan.handle]);
    expect(page.recommenders.map((x: { handle: string }) => x.handle)).toEqual([fan.handle]);
  });
});

describe('refreshes', () => {
  it('re-rates single dishes alongside a full rebuild without clashing, and snapshots Top once a day', async () => {
    const place = await testPlace('Busy Bakery');
    const dishes = [];
    for (let i = 0; i < 4; i++) dishes.push(await mkDish(place, `Busy Bun ${i}`, 1));
    const u = await signUp({ established: true });
    for (const d of dishes) await vote(u.id, d, 'recommend', 1);
    await Promise.all([
      db.query('select refresh_people_pick()'),
      db.query('select refresh_dish_stats()'),
      ...dishes.flatMap((d) => [db.query('select refresh_people_pick($1)', [d]), db.query('select refresh_dish_stats($1)', [d])]),
    ]);
    const rated = (await db.query('select count(*)::int as n from dish_rating where dish_id = any($1) and loves = 1', [dishes])).rows[0].n;
    expect(rated).toBe(4);

    await db.query('delete from top_snapshots where day = current_date');
    await db.query('select snapshot_top()');
    const snap = (await db.query('select dish_ids from top_snapshots where city_id = $1 and day = current_date', [cityId])).rows[0];
    expect(snap.dish_ids.length).toBeLessThanOrEqual(20);
    expect(snap.dish_ids.length).toBeGreaterThan(0);
    // Already kept today: a second call changes nothing.
    await db.query('update top_snapshots set dish_ids = $2 where city_id = $1 and day = current_date', [cityId, []]);
    await db.query('select snapshot_top()');
    expect((await db.query('select dish_ids from top_snapshots where city_id = $1 and day = current_date', [cityId])).rows[0].dish_ids).toEqual([]);
  });
});
