// The launch plan (migration 024): focus mode, coverage, visit codes, diary and /best.
// Its own database, so it can run alongside the other suites.
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
const dbName = `worthabite_founder_${process.pid}`;

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

  mediaDir = await fs.mkdtemp(path.join(os.tmpdir(), 'worthabite-founder-media-'));
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
  const handle = `founder_${userCounter}`;
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

const rebuild = () => db.query('select refresh_dish_stats(); select refresh_people_pick();');

describe('focus mode and signature dishes', () => {
  it('starts on, hides the extras, and an admin can turn it off and edit the list', async () => {
    const c = (await app.inject('/app/config')).json();
    expect(c.focusMode).toBe(true);
    expect(c.hidden).toContain('stories');
    expect(c.signatureDishes).toContain('Misal Pav');
    const admin = await adminAuth();
    const u = await signUp();
    expect((await app.inject({ method: 'PUT', url: '/admin/flags/focus_mode', headers: u.auth, payload: { value: 'off' } })).statusCode).toBe(403);
    expect((await app.inject({ method: 'PUT', url: '/admin/flags/focus_mode', headers: admin, payload: { value: 'maybe' } })).statusCode).toBe(400);
    expect((await app.inject({ method: 'PUT', url: '/admin/flags/focus_mode', headers: admin, payload: { value: 'off' } })).statusCode).toBe(200);
    const off = (await app.inject('/app/config')).json();
    expect(off.focusMode).toBe(false);
    expect(off.hidden).toEqual([]);
    await app.inject({ method: 'PUT', url: '/admin/flags/focus_mode', headers: admin, payload: { value: 'on' } });
    const list = await app.inject({ method: 'PUT', url: '/admin/flags/signature_dishes', headers: admin, payload: { value: 'Founder Poha\n founder  poha2 ,Founder Poha\n\nx' } });
    expect(list.statusCode).toBe(200);
    expect((await app.inject('/app/config')).json().signatureDishes).toEqual(['Founder Poha', 'founder poha2']);
  });

  it('counts coverage: places with a real photo, and places with enough shown votes', async () => {
    const admin = await adminAuth();
    await app.inject({ method: 'PUT', url: '/admin/flags/signature_dishes', headers: admin, payload: { value: 'Coverage Misal\nNobody Dish' } });
    const poster = await signUp({ established: true });
    const places = await Promise.all([1, 2, 3].map((i) => testPlace(`Coverage Place ${i}`)));
    const dishes = [];
    for (const pl of places) dishes.push(await mkDish(pl, 'Coverage Misal', 30, poster.id));
    // A seed photo is not a real one.
    await db.query("update posts set source = 'seed' where dish_id = $1", [dishes[2]]);
    for (let i = 0; i < 10; i++) {
      const v = await signUp({ established: true });
      await vote(v.id, dishes[0]!, 'recommend', 30 + i * 30);
    }
    await rebuild();
    const c = (await app.inject({ url: `/admin/coverage?city=${cityId}`, headers: admin })).json();
    const misal = c.dishes.find((d: { dish: string }) => d.dish === 'Coverage Misal');
    expect(misal).toMatchObject({ placesWithPhotos: 2, placesWithVotes: 1, placesCovered: 1, ready: false });
    expect(c.dishes.find((d: { dish: string }) => d.dish === 'Nobody Dish')).toMatchObject({ placesWithPhotos: 0, votes: 0, ready: false });
    expect(c.bar).toEqual({ places: 5, votes: 10 });
  });
});

describe('visit codes', () => {
  it('a diner gets a code, the owner confirms a new customer, and only that is billed', async () => {
    const admin = await adminAuth();
    const place = await testPlace('Pilot Misal House');
    const diner = await signUp({ established: true });
    // Not on the pilot yet.
    expect((await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: diner.auth })).statusCode).toBe(404);
    const bad = await app.inject({ method: 'PUT', url: `/admin/pilots/${place}`, headers: admin, payload: { feeInr: 10, quietHours: 'whenever' } });
    expect(bad.statusCode).toBe(400);
    const set = await app.inject({ method: 'PUT', url: `/admin/pilots/${place}`, headers: admin, payload: { feeInr: 10, offer: 'Free chai with misal' } });
    expect(set.statusCode).toBe(200);
    const link: string = set.json().ownerLink;
    const token = link.split('/r/')[1]!;
    expect((await app.inject(`/restaurants/${place}`)).json().pilot).toEqual({ offer: 'Free chai with misal', quietHours: null });

    const got = await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: diner.auth });
    expect(got.statusCode).toBe(200);
    const code: string = got.json().code;
    expect(code).toMatch(/^[A-HJ-KM-NP-Z2-9]{6}$/);
    // Asking again gives the same code.
    expect((await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: diner.auth })).json().code).toBe(code);

    expect((await app.inject(`/r/${token}`)).body).toContain('Pilot Misal House');
    expect((await app.inject('/r/not-a-real-token-at-all')).statusCode).toBe(404);
    const form = (body: string) =>
      app.inject({ method: 'POST', url: `/r/${token}`, headers: { 'content-type': 'application/x-www-form-urlencoded' }, payload: body });
    const wrong = await form('code=ZZZZZZ');
    expect(wrong.statusCode).toBe(409);
    expect(wrong.body).toContain('not for this place');
    const ok = await form(`code=${code.toLowerCase()}&new_customer=yes`);
    expect(ok.statusCode).toBe(200);
    expect(ok.body).toContain('New customer');
    const again = await form(`code=${code}&new_customer=yes`);
    expect(again.body).toContain('already used');
    // Used today: no second code here today.
    expect((await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: diner.auth })).statusCode).toBe(409);

    // A regular: the owner leaves "new customer" unticked, nothing to pay.
    const regular = await signUp({ established: true });
    const c2 = (await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: regular.auth })).json().code;
    expect((await form(`code=${c2}`)).body).toContain('nothing to pay');
    // An expired code is refused.
    const late = await signUp({ established: true });
    const c3 = (await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: late.auth })).json().code;
    await db.query("update visit_codes set expires_at = now() - interval '1 minute' where code = $1", [c3]);
    expect((await form(`code=${c3}&new_customer=yes`)).body).toContain('expired');

    const pilots = (await app.inject({ url: '/admin/pilots', headers: admin })).json().pilots;
    const mine = pilots.find((p: { restaurantId: string }) => p.restaurantId === place);
    expect(mine.months[0]).toMatchObject({ issued: 3, redeemed: 2, newCustomers: 1, amountInr: 10 });
    expect((await app.inject(`/r/${token}`)).body).toContain('₹10');
  });

  it('quiet hours, the place adder, and the daily limit', async () => {
    const admin = await adminAuth();
    const adder = await signUp({ established: true });
    const place = await testPlace('Pilot Quiet Cafe', adder.id);
    await app.inject({ method: 'PUT', url: `/admin/pilots/${place}`, headers: admin, payload: { feeInr: 8, quietHours: 'Mo-Su 00:00-00:01' } });
    const diner = await signUp({ established: true });
    const closed = await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: diner.auth });
    // 00:00-00:01 is open one minute a day; nearly always "not now".
    if (closed.statusCode !== 200) expect(closed.json().error).toBe('not_now');
    await app.inject({ method: 'PUT', url: `/admin/pilots/${place}`, headers: admin, payload: { feeInr: 8, quietHours: '24/7' } });
    expect((await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: adder.auth })).statusCode).toBe(403);
    // Three codes a day across places.
    const hungry = await signUp({ established: true });
    for (let i = 0; i < 3; i++) {
      const pl = await testPlace(`Pilot Limit ${i}`);
      await app.inject({ method: 'PUT', url: `/admin/pilots/${pl}`, headers: admin, payload: { feeInr: 5 } });
      expect((await app.inject({ method: 'POST', url: `/restaurants/${pl}/visit-code`, headers: hungry.auth })).statusCode).toBe(200);
    }
    expect((await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: hungry.auth })).statusCode).toBe(429);
    // A paused pilot gives no codes.
    await app.inject({ method: 'PUT', url: `/admin/pilots/${place}`, headers: admin, payload: { feeInr: 8, active: false } });
    expect((await app.inject({ method: 'POST', url: `/restaurants/${place}/visit-code`, headers: diner.auth })).statusCode).toBe(404);
  });
});

describe('my food diary', () => {
  it('lists what I posted and voted on, with what I paid, newest first', async () => {
    const me = await signUp({ established: true });
    expect((await app.inject('/me/diary')).statusCode).toBe(401);
    const place = await testPlace('Diary Dhaba');
    const posted = await post(me.auth, { restaurantId: place, dishName: 'Diary Thalipeeth', verdict: 'loved', priceInr: '90' });
    expect(posted.statusCode).toBe(201);
    const other = await mkDish(place, 'Diary Kanda Bhaji', 5);
    expect((await put(me, other, 'pass')).statusCode).toBe(200);
    const d = (await app.inject({ url: '/me/diary', headers: me.auth })).json();
    expect(d.summary).toMatchObject({ dishes: 2, places: 1, loved: 1, spentInr: 90, photos: 1 });
    expect(d.entries.map((e: { dish: string }) => e.dish)).toEqual(['Diary Kanda Bhaji', 'Diary Thalipeeth']);
    expect(d.entries[0]).toMatchObject({ verdict: 'not_for_me', pricePaid: null, photos: 0 });
    expect(d.entries[1]).toMatchObject({ verdict: 'loved', pricePaid: 90, photos: 1 });
    expect(d.entries[1].photoUrl).toBeTruthy();
    expect(d.nextOffset).toBeNull();
  });
});

describe('/best share page', () => {
  it('shows a craving top list with "% loved", and an empty city shows a nudge', async () => {
    const place = await testPlace('Best Bhaji Corner');
    const dish = await mkDish(place, 'Bestshare Pav Bhaji', 10);
    for (let i = 0; i < 3; i++) await vote((await signUp({ established: true })).id, dish, 'recommend', 48);
    await rebuild();
    const res = await app.inject('/best?city=nashik&q=bestshare%20pav%20bhaji');
    expect(res.statusCode).toBe(200);
    expect(res.body).toContain('Best Bhaji Corner');
    expect(res.body).toContain('100% loved · 3 votes');
    expect(res.body).toContain('og:title');
    const none = await app.inject('/best?city=nashik&q=zzqx%20nothing');
    expect(none.body).toContain('Nobody has posted');
    expect((await app.inject('/best?city=nashik')).body).toContain('/best?city=nashik&amp;q=');
    expect((await app.inject('/best?city=atlantis&q=misal')).statusCode).toBe(404);
  });
});
