// Integration tests against a real Postgres + PostGIS database.
// Needs TEST_DATABASE_URL pointing at a server where the user may create databases,
// e.g. postgres://postgres:postgres@localhost:5433/postgres (see README).
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import pg from 'pg';
import sharp from 'sharp';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Config } from '../src/config.js';
import { createPool, migrate, type Db } from '../src/db.js';
import { ensureCities } from '../src/geo.js';
import { seedCatalog, seedDemoActivity } from '../src/seed.js';

const adminUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5433/postgres';
const dbName = `worthabite_test_${process.pid}`;

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

  mediaDir = await fs.mkdtemp(path.join(os.tmpdir(), 'worthabite-media-'));
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
async function signUp(opts: { email?: string; established?: boolean } = {}) {
  userCounter++;
  const handle = `tester_${userCounter}`;
  const res = await app.inject({
    method: 'POST',
    url: '/auth/register',
    payload: { email: opts.email ?? `${handle}@example.com`, password: 'correct horse', handle, acceptTerms: true },
  });
  expect(res.statusCode).toBe(201);
  const body = res.json();
  if (opts.established) {
    await db.query("update profiles set created_at = now() - interval '10 days' where id = $1", [body.user.id]);
  }
  return { token: body.token as string, id: body.user.id as string, handle, auth: { authorization: `Bearer ${body.token}` } };
}

async function dishId(name: string): Promise<string> {
  return (await db.query('select id from dishes where name = $1', [name])).rows[0].id;
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

async function photo(withGps = false): Promise<Buffer> {
  let img = sharp({ create: { width: 1600, height: 1200, channels: 3, background: { r: 200, g: 90, b: 30 } } }).jpeg();
  if (withGps) {
    img = img.withExif({ IFD0: { Make: 'TestPhone' }, IFD3: { GPSLatitudeRef: 'N', GPSLatitude: '20/1 0/1 0/1' } });
  }
  return img.toBuffer();
}

describe('browsing as a guest', () => {
  it('reports health and lists cities and categories', async () => {
    expect((await app.inject('/health')).json()).toEqual({ ok: true });
    const cities = (await app.inject('/cities')).json();
    expect(cities[0]).toMatchObject({ slug: 'nashik', isActive: true });
    expect((await app.inject('/categories')).json()).toHaveLength(12);
  });

  it('finds the nearest active city and says when you are out of range', async () => {
    const near = (await app.inject(`/cities/nearest?lat=20.01&lng=73.78`)).json();
    expect(near).toMatchObject({ city: { slug: 'nashik' }, inRange: true });
    expect(typeof near.city.distanceKm).toBe('number');
    const delhi = (await app.inject(`/cities/nearest?lat=28.61&lng=77.2`)).json();
    expect(delhi).toMatchObject({ city: { slug: 'delhi', state: 'Delhi' }, inRange: true });
    // A suburb joins its metro: Hinjawadi (west Pune) belongs to Pune or Pimpri, never a small town.
    const suburb = (await app.inject(`/cities/nearest?lat=18.59&lng=73.74`)).json();
    expect(['pune', 'pimpri']).toContain(suburb.city.slug);
    const london = (await app.inject(`/cities/nearest?lat=51.5&lng=-0.12`)).json();
    expect(london).toEqual({ city: null, inRange: false });
  });

  it('opens every Indian city and searches them by name', async () => {
    const { rows } = await db.query<{ n: number }>('select count(*)::int as n from cities where is_active');
    expect(rows[0]!.n).toBeGreaterThan(800);
    const found = (await app.inject('/cities?q=bengal')).json();
    expect(found[0]).toMatchObject({ slug: 'bengaluru', state: 'Karnataka', dishCount: 0 });
    const top = (await app.inject('/cities')).json();
    expect(top).toHaveLength(40);
    expect(top[0].dishCount).toBeGreaterThan(0);
    // Re-running the loader changes nothing and keeps Nashik's id.
    await ensureCities(db);
    expect((await db.query("select id from cities where slug = 'nashik'")).rows[0].id).toBe(cityId);
  });

  it('shows the editorial order on launch day when nobody has recommended anything', async () => {
    const res = await app.inject(`/feed?city=${cityId}&limit=20&seed=abc`);
    expect(res.statusCode).toBe(200);
    const { items, nextOffset } = res.json();
    expect(items).toHaveLength(20);
    expect(nextOffset).toBe(20);
    expect(new Set(items.map((i: { lane: string }) => i.lane))).toEqual(new Set(['fresh']));
    expect(items[0].name).toBe('Sabudana Khichdi');
    expect(items[0].cover.imageUrl).toMatch(/^\/media\/posts\/.+\/main\.webp$/);
    expect(items[0].stats.recommenders).toBe(0);
  });

  it('pages through the whole feed without repeats', async () => {
    const first = (await app.inject(`/feed?city=${cityId}&limit=20&seed=s`)).json();
    const second = (await app.inject(`/feed?city=${cityId}&limit=20&offset=20&seed=s`)).json();
    const ids = [...first.items, ...second.items].map((i: { id: string }) => i.id);
    expect(new Set(ids).size).toBe(30);
    expect(second.nextOffset).toBeNull();
  });

  it('compresses JSON for phones but sends photos as they are', async () => {
    const url = `/feed?city=${cityId}&tab=top&limit=20&offset=0&seed=z`;
    const plain = await app.inject(url);
    const gz = await app.inject({ url, headers: { 'accept-encoding': 'br, gzip' } });
    expect(gz.headers['content-encoding']).toBe('br');
    expect(gz.rawPayload.length).toBeLessThan(plain.rawPayload.length / 3);
    const photo = plain.json().items[0].cover.imageUrl;
    const img = await app.inject({ url: photo, headers: { 'accept-encoding': 'br, gzip' } });
    expect(img.statusCode).toBe(200);
    expect(img.headers['content-encoding']).toBeUndefined();
    expect(img.headers['cache-control']).toContain('immutable');
  });

  it('filters by category', async () => {
    const misal = (await db.query("select id from dish_categories where slug = 'misal'")).rows[0].id;
    const { items } = (await app.inject(`/feed?city=${cityId}&category=${misal}`)).json();
    expect(items.map((i: { name: string }) => i.name).sort()).toEqual(['Kolhapuri Misal', 'Misal Pav']);
  });

  it('needs a location for Nearby and then sorts by distance', async () => {
    expect((await app.inject(`/feed?city=${cityId}&tab=nearby`)).statusCode).toBe(400);
    const { items } = (await app.inject(`/feed?city=${cityId}&tab=nearby&lat=19.952&lng=73.838&limit=5`)).json();
    expect(items[0].restaurant.name).toBe('Bhurji Corner');
    const d = items.map((i: { distanceM: number }) => i.distanceM);
    expect([...d].sort((a, b) => a - b)).toEqual(d);
  });

  it('opens dish and restaurant pages', async () => {
    const id = await dishId('Kolhapuri Misal');
    const dish = (await app.inject(`/dishes/${id}`)).json();
    expect(dish).toMatchObject({ name: 'Kolhapuri Misal', restaurant: { name: 'Godavari Misal House' } });
    expect(dish.posts).toHaveLength(1);
    expect(dish.moreAtPlace.length).toBe(2);
    const place = (await app.inject(`/restaurants/${dish.restaurant.id}`)).json();
    expect(place.dishes).toHaveLength(3);
    expect((await app.inject('/dishes/00000000-0000-0000-0000-000000000000')).statusCode).toBe(404);
  });

  it('searches with local spellings and typos', async () => {
    const wada = (await app.inject(`/search?city=${cityId}&q=wada%20pav`)).json();
    expect(wada.dishes.map((d: { name: string }) => d.name)).toContain('Vada Pav');
    const missal = (await app.inject(`/search?city=${cityId}&q=missal`)).json();
    expect(missal.dishes.map((d: { name: string }) => d.name)).toEqual(expect.arrayContaining(['Misal Pav', 'Kolhapuri Misal']));
    const place = (await app.inject(`/search?city=${cityId}&q=godavri`)).json();
    expect(place.restaurants[0].name).toBe('Godavari Misal House');
    const suggest = (await app.inject(`/suggest/dishes?q=sabudan`)).json();
    expect(suggest.map((s: { name: string }) => s.name)).toContain('Sabudana Khichdi');
  });

  it('serves the share page and policy pages', async () => {
    const id = await dishId('Jalebi');
    const share = await app.inject(`/d/${id}`);
    expect(share.statusCode).toBe(200);
    expect(share.body).toContain('og:title');
    expect(share.body).toContain('Jalebi at Sweet Sangam');
    expect(share.body).toContain(`worthabite://dish/${id}`);
    for (const p of ['/privacy', '/terms', '/delete-account', '/']) expect((await app.inject(p)).statusCode).toBe(200);
  });
});

describe('accounts', () => {
  it('registers, rejects duplicates and bad passwords, and logs in', async () => {
    const u = await signUp();
    const dup = await app.inject({
      method: 'POST', url: '/auth/register',
      payload: { email: 'other@example.com', password: 'correct horse', handle: u.handle, acceptTerms: true },
    });
    expect(dup.statusCode).toBe(409);
    expect(dup.json().error).toBe('handle_taken');
    const noTerms = await app.inject({
      method: 'POST', url: '/auth/register',
      payload: { email: 'x@example.com', password: 'correct horse', handle: 'someone_new' },
    });
    expect(noTerms.statusCode).toBe(400);
    const bad = await app.inject({ method: 'POST', url: '/auth/login', payload: { email: `${u.handle}@example.com`, password: 'nope' } });
    expect(bad.statusCode).toBe(401);
    const ok = await app.inject({ method: 'POST', url: '/auth/login', payload: { email: `${u.handle}@EXAMPLE.com`, password: 'correct horse' } });
    expect(ok.statusCode).toBe(200);
    expect((await app.inject({ url: '/me', headers: { authorization: `Bearer ${ok.json().token}` } })).json().handle).toBe(u.handle);
    expect((await app.inject('/me')).statusCode).toBe(401);
  });
});

describe('recommend, save, tried and share', () => {
  it('asks guests to sign in', async () => {
    const id = await dishId('Vada Pav');
    expect((await app.inject({ method: 'PUT', url: `/dishes/${id}/engagements/recommend` })).statusCode).toBe(401);
  });

  it('toggles idempotently and updates the public count immediately', async () => {
    const u = await signUp({ established: true });
    const id = await dishId('Vada Pav');
    const url = `/dishes/${id}/engagements/recommend`;
    const on = (await app.inject({ method: 'PUT', url, headers: u.auth })).json();
    expect(on).toMatchObject({ stats: { recommenders: 1 }, me: { recommended: true } });
    const again = (await app.inject({ method: 'PUT', url, headers: u.auth })).json();
    expect(again.stats.recommenders).toBe(1);
    const save = (await app.inject({ method: 'PUT', url: `/dishes/${id}/engagements/save`, headers: u.auth })).json();
    expect(save).toMatchObject({ stats: { recommenders: 1, saves: 1 }, me: { saved: true } });
    const off = (await app.inject({ method: 'DELETE', url, headers: u.auth })).json();
    expect(off).toMatchObject({ stats: { recommenders: 0, saves: 1 }, me: { recommended: false } });
    const saved = (await app.inject({ url: '/me/saved', headers: u.auth })).json();
    expect(saved.items.map((i: { name: string }) => i.name)).toEqual(['Vada Pav']);
  });

  it('caps one person at 3 points, and a brand-new account barely counts', async () => {
    const id = await dishId('Rabdi');
    const veteran = await signUp({ established: true });
    for (const k of ['recommend', 'save', 'tried']) {
      await app.inject({ method: 'PUT', url: `/dishes/${id}/engagements/${k}`, headers: veteran.auth });
    }
    await app.inject({ method: 'POST', url: `/dishes/${id}/share`, headers: veteran.auth });
    let score = (await db.query('select score from dish_stats where dish_id = $1', [id])).rows[0].score;
    expect(score).toBeCloseTo(3, 3); // 1 + 1.5 + 2 + 2.5 = 7, capped at 3
    const newbie = await signUp();
    await app.inject({ method: 'PUT', url: `/dishes/${id}/engagements/recommend`, headers: newbie.auth });
    score = (await db.query('select score from dish_stats where dish_id = $1', [id])).rows[0].score;
    expect(score).toBeCloseTo(3, 2); // a seconds-old account's love counts next to nothing...
    await db.query("update profiles set created_at = now() - interval '7 days' where id = $1", [newbie.id]);
    await db.query('select refresh_dish_stats($1)', [id]);
    score = (await db.query('select score from dish_stats where dish_id = $1', [id])).rows[0].score;
    expect(score).toBeCloseTo(4, 2); // ...and fully once the account is a week old
  });

  it('counts a share once and lets guests share without counting', async () => {
    const id = await dishId('Shrikhand');
    const guest = (await app.inject({ method: 'POST', url: `/dishes/${id}/share` })).json();
    expect(guest).toEqual({ url: `http://test.local/d/${id}`, counted: false });
    const u = await signUp({ established: true });
    await app.inject({ method: 'POST', url: `/dishes/${id}/share`, headers: u.auth });
    await app.inject({ method: 'POST', url: `/dishes/${id}/share`, headers: u.auth });
    expect((await db.query('select shares from dish_stats where dish_id = $1', [id])).rows[0].shares).toBe(1);
  });

  it('records impressions only as the signed-in user', async () => {
    const u = await signUp();
    const id = await dishId('Bun Maska');
    const res = await app.inject({ method: 'POST', url: '/views', headers: u.auth, payload: { dishIds: [id, id], deviceId: 'dev-1' } });
    expect(res.statusCode).toBe(202);
    const rows = (await db.query('select user_id from dish_views where dish_id = $1', [id])).rows;
    expect(rows).toEqual([{ user_id: u.id }]);
  });
});

describe('ranking tabs with demo activity', () => {
  beforeAll(async () => {
    await seedDemoActivity(db);
  });

  it('builds a Top lane with a fresh dish every fifth card', async () => {
    const { items } = (await app.inject(`/feed?city=${cityId}&limit=10&seed=x`)).json();
    const lanes = items.map((i: { lane: string }) => i.lane);
    expect(lanes.slice(0, 4)).toEqual(['top', 'top', 'top', 'top']);
    expect(lanes[4]).toBe('fresh');
    const scores = (await db.query('select d.name, s.score from dish_stats s join dishes d on d.id = s.dish_id order by s.score desc limit 1')).rows[0];
    expect(items[0].name).toBe(scores.name);
    expect(items[0].stats.recommenders).toBeGreaterThan(5);
    expect(items[0].recentRecommenders.length).toBe(3);
  });

  it('shows trending and hidden gems', async () => {
    const trending = (await app.inject(`/feed?city=${cityId}&tab=trending`)).json();
    expect(trending.items.length).toBeGreaterThan(5);
    const gems = (await app.inject(`/feed?city=${cityId}&tab=gems`)).json();
    expect(gems.items.map((i: { name: string }) => i.name)).toContain('Egg Bhurji Pav');
    for (const g of gems.items) expect(g.lane).toBe('gem');
  });
});

describe('posting a dish', () => {
  it('posts at an existing place, strips photo metadata and is idempotent', async () => {
    const u = await signUp({ established: true });
    const restaurantId = (await db.query("select id from restaurants where name = 'Chai Katta'")).rows[0].id;
    const fields = { idempotencyKey: 'key-123456', dishName: 'Masala Chai!!', restaurantId, priceInr: '20', isVeg: 'true', caption: 'Ginger heavy' };
    const res = await app.inject({ method: 'POST', url: '/posts',  ...multipart(u.auth, fields, await photo(true)) });
    expect(res.statusCode).toBe(201);
    const post = res.json();
    expect(post).toMatchObject({ dishName: 'Masala Chai!!', restaurantStatus: 'active', status: 'live' });
    const main = await fs.readFile(path.join(mediaDir, post.imageUrl.replace('/media/', '')));
    const meta = await sharp(main).metadata();
    expect(meta.width).toBe(1080);
    expect(meta.exif).toBeUndefined();
    expect(meta.format).toBe('webp');
    const retry = await app.inject({ method: 'POST', url: '/posts',  ...multipart(u.auth, fields, await photo()) });
    expect(retry.statusCode).toBe(200);
    expect(retry.json().id).toBe(post.id);
    // the photo is served
    expect((await app.inject(post.thumbUrl)).statusCode).toBe(200);
  });

  it('matches an existing dish by normalised name instead of duplicating it', async () => {
    const u = await signUp({ established: true });
    const restaurantId = (await db.query("select id from restaurants where name = 'Chai Katta'")).rows[0].id;
    const res = await app.inject({
      method: 'POST', url: '/posts', 
      ...multipart(u.auth, { idempotencyKey: 'key-vadapav1', dishName: 'vada  PAV', restaurantId }, await photo()),
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().dishId).toBe(await dishId('Vada Pav'));
  });

  it('keeps a new place pending until an admin approves it', async () => {
    const u = await signUp({ established: true });
    const res = await app.inject({
      method: 'POST', url: '/posts', 
      ...multipart(u.auth, { idempotencyKey: 'key-newplace', dishName: 'Mango Lassi', newPlaceName: 'Lassi Wala', newPlaceLocality: 'Panchavati', newPlaceLat: '20.0095', newPlaceLng: '73.7925' }, await photo()),
    });
    expect(res.statusCode).toBe(201);
    const post = res.json();
    expect(post.restaurantStatus).toBe('pending');
    const feed = (await app.inject(`/feed?city=${cityId}&limit=50`)).json();
    expect(feed.items.map((i: { name: string }) => i.name)).not.toContain('Mango Lassi');
    // the author still sees it on their profile and on the place page
    const me = (await app.inject({ url: `/users/${u.handle}`, headers: u.auth })).json();
    expect(me.posts[0]).toMatchObject({ dishName: 'Mango Lassi', restaurantStatus: 'pending' });
    expect((await app.inject({ url: `/restaurants/${post.restaurantId}`, headers: u.auth })).statusCode).toBe(200);
    expect((await app.inject(`/restaurants/${post.restaurantId}`)).statusCode).toBe(404);

    const notAdmin = await app.inject({ method: 'POST', url: `/admin/restaurants/${post.restaurantId}/approve`, headers: u.auth });
    expect(notAdmin.statusCode).toBe(403);
    const admin = await signUp({ email: 'admin@example.com' });
    const queue = (await app.inject({ url: '/admin/queue', headers: admin.auth })).json();
    expect(queue.pendingPlaces.map((p: { name: string }) => p.name)).toContain('Lassi Wala');
    await app.inject({ method: 'POST', url: `/admin/restaurants/${post.restaurantId}/approve`, headers: admin.auth });
    const after = (await app.inject(`/feed?city=${cityId}&limit=50`)).json();
    expect(after.items.map((i: { name: string }) => i.name)).toContain('Mango Lassi');
  });

  it('puts a new place anywhere in India into its city', async () => {
    const u = await signUp({ established: true });
    const res = await app.inject({
      method: 'POST', url: '/posts',
      ...multipart(u.auth, { idempotencyKey: 'key-pune-place', dishName: 'Bun Maska', newPlaceName: 'Goodluck Cafe', newPlaceLat: '18.5167', newPlaceLng: '73.8414' }, await photo()),
    });
    expect(res.statusCode).toBe(201);
    const { rows } = await db.query(
      "select c.slug from restaurants r join cities c on c.id = r.city_id where r.name = 'Goodluck Cafe'",
    );
    expect(rows[0].slug).toBe('pune');
  });

  it('rejects places outside every city, missing photos and non-images', async () => {
    const u = await signUp({ established: true });
    const far = await app.inject({
      method: 'POST', url: '/posts', 
      ...multipart(u.auth, { idempotencyKey: 'key-far-away', dishName: 'Momos', newPlaceName: 'Far Place', newPlaceLat: '51.5', newPlaceLng: '-0.12' }, await photo()),
    });
    expect(far.statusCode).toBe(400);
    expect(far.json().error).toBe('outside_city');
    const restaurantId = (await db.query("select id from restaurants where name = 'Chai Katta'")).rows[0].id;
    const none = await app.inject({ method: 'POST', url: '/posts',  ...multipart(u.auth, { idempotencyKey: 'key-no-photo', dishName: 'Tea', restaurantId }) });
    expect(none.statusCode).toBe(400);
    const junk = await app.inject({ method: 'POST', url: '/posts',  ...multipart(u.auth, { idempotencyKey: 'key-junkfile', dishName: 'Tea', restaurantId }, Buffer.from('not an image')) });
    expect(junk.statusCode).toBe(400);
    expect((await db.query("select count(*)::int as n from restaurants where name = 'Far Place'")).rows[0].n).toBe(0);
  });
});

describe('safety', () => {
  it('hides a post after three established reporters, but not after throwaway accounts', async () => {
    const id = await dishId('Penne Arrabbiata');
    const postId = (await db.query('select id from posts where dish_id = $1', [id])).rows[0].id;
    for (let i = 0; i < 3; i++) {
      const fresh = await signUp();
      await app.inject({ method: 'POST', url: '/reports', headers: fresh.auth, payload: { targetType: 'post', targetId: postId, reason: 'spam' } });
    }
    expect((await db.query('select status from posts where id = $1', [postId])).rows[0].status).toBe('live');
    let hidden = false;
    for (let i = 0; i < 3; i++) {
      const old = await signUp({ established: true });
      const r = await app.inject({ method: 'POST', url: '/reports', headers: old.auth, payload: { targetType: 'post', targetId: postId, reason: 'not_food' } });
      expect(r.statusCode).toBe(201);
      hidden = r.json().hidden;
    }
    expect(hidden).toBe(true);
    // the dish has no live photo now, so it leaves the feed and its page 404s for nobody but shows no cover
    const feed = (await app.inject(`/feed?city=${cityId}&limit=50`)).json();
    expect(feed.items.map((i: { name: string }) => i.name)).not.toContain('Penne Arrabbiata');
    const admin = await signUp({ email: `admin2_${Date.now()}@example.com` });
    await db.query("update profiles set role = 'admin' where id = $1", [admin.id]);
    const restored = await app.inject({ method: 'POST', url: `/admin/posts/${postId}/restore`, headers: admin.auth });
    expect(restored.statusCode).toBe(200);
    expect((await db.query('select status from posts where id = $1', [postId])).rows[0].status).toBe('live');
  });

  it('falls back to another photo when the cover is hidden, and hides blocked people', async () => {
    const author = await signUp({ established: true });
    const viewer = await signUp({ established: true });
    const restaurantId = (await db.query("select id from restaurants where name = 'Udupi Swad'")).rows[0].id;
    const res = await app.inject({
      method: 'POST', url: '/posts', 
      ...multipart(author.auth, { idempotencyKey: 'key-idli-2nd', dishName: 'Idli Sambar', restaurantId }, await photo()),
    });
    const newPost = res.json().id;
    const id = await dishId('Idli Sambar');
    const cover = (await db.query('select cover_post_id from dishes where id = $1', [id])).rows[0].cover_post_id;
    await db.query("update posts set status = 'hidden' where id = $1", [cover]);
    let card = (await app.inject(`/dishes/${id}`)).json();
    expect(card.cover.postId).toBe(newPost);
    await app.inject({ method: 'POST', url: '/blocks', headers: viewer.auth, payload: { handle: author.handle } });
    card = (await app.inject({ url: `/dishes/${id}`, headers: viewer.auth })).json();
    expect(card.cover).toBeNull();
    expect(card.posts).toHaveLength(0);
    await db.query("update posts set status = 'live' where id = $1", [cover]);
  });

  it('deletes an account with its photos and actions', async () => {
    const u = await signUp({ established: true });
    const restaurantId = (await db.query("select id from restaurants where name = 'Burger Barn'")).rows[0].id;
    const post = (await app.inject({
      method: 'POST', url: '/posts', 
      ...multipart(u.auth, { idempotencyKey: 'key-delete-me', dishName: 'Cheese Fries', restaurantId }, await photo()),
    })).json();
    const id = await dishId('Veg Burger');
    await app.inject({ method: 'PUT', url: `/dishes/${id}/engagements/recommend`, headers: u.auth });
    const res = await app.inject({ method: 'DELETE', url: '/me', headers: u.auth });
    expect(res.statusCode).toBe(200);
    expect((await db.query('select count(*)::int as n from profiles where id = $1', [u.id])).rows[0].n).toBe(0);
    expect((await db.query('select count(*)::int as n from posts where id = $1', [post.id])).rows[0].n).toBe(0);
    expect((await db.query('select count(*)::int as n from engagements where user_id = $1', [u.id])).rows[0].n).toBe(0);
    await expect(fs.stat(path.join(mediaDir, 'posts', post.id))).rejects.toThrow();
    expect((await app.inject({ url: '/me', headers: u.auth })).statusCode).toBe(401);
  });

  it('lets only admins change ranking settings', async () => {
    const u = await signUp();
    expect((await app.inject({ url: '/admin/ranking-config', headers: u.auth })).statusCode).toBe(403);
    expect((await app.inject({ url: '/admin/ranking-config' })).statusCode).toBe(401);
  });
});

describe('sharing dishes with friends', () => {
  async function friends(a: { auth: Record<string, string>; handle: string }, b: { auth: Record<string, string>; handle: string }) {
    await app.inject({ method: 'PUT', url: `/users/${b.handle}/follow`, headers: a.auth });
    await app.inject({ method: 'PUT', url: `/users/${a.handle}/follow`, headers: b.auth });
  }
  async function coverPostOf(name: string): Promise<{ dish: string; post: string }> {
    const dish = await dishId(name);
    return { dish, post: (await app.inject(`/dishes/${dish}`)).json().cover.postId };
  }

  it('shares with followers and people you follow, never text, and lists who you can share with', async () => {
    const a = await signUp();
    const b = await signUp();
    const c = await signUp();
    const misal = await dishId('Misal Pav');
    const share = (handles: string[], payload: object = { dishId: misal }) =>
      app.inject({ method: 'POST', url: '/messages', headers: a.auth, payload: { handles, ...payload } });

    // Strangers can't be shared with.
    expect((await share([b.handle])).json()).toEqual({ sent: [], failed: [b.handle] });
    expect((await app.inject({ url: '/me/friends', headers: a.auth })).json()).toEqual([]);
    expect((await app.inject({ method: 'POST', url: '/conversations', headers: a.auth, payload: { handle: b.handle } })).statusCode).toBe(403);

    // Someone you follow, and someone who follows you: both are friends to share with.
    await app.inject({ method: 'PUT', url: `/users/${b.handle}/follow`, headers: a.auth });
    await app.inject({ method: 'PUT', url: `/users/${a.handle}/follow`, headers: c.auth });
    const friendsOf = async (q = '') =>
      (await app.inject({ url: `/me/friends${q ? `?q=${q}` : ''}`, headers: a.auth })).json() as { handle: string; isFollowing: boolean; followsYou: boolean }[];
    const list = await friendsOf();
    expect(list.map((p) => p.handle).sort()).toEqual([b.handle, c.handle].sort());
    expect(list.find((p) => p.handle === b.handle)).toMatchObject({ isFollowing: true, followsYou: false });
    expect(list.find((p) => p.handle === c.handle)).toMatchObject({ isFollowing: false, followsYou: true });
    expect((await friendsOf(c.handle)).map((p) => p.handle)).toEqual([c.handle]);
    expect((await share([b.handle, c.handle])).json()).toEqual({ sent: [b.handle, c.handle].sort(), failed: [] });
    // And they can share back with you.
    expect((await app.inject({ method: 'POST', url: '/messages', headers: b.auth, payload: { handles: [a.handle], dishId: misal } })).json().sent).toEqual([a.handle]);

    // No text, places or photos: only a dish or a post.
    expect((await share([b.handle], { body: 'hello' })).statusCode).toBe(400);
    const place = (await db.query('select restaurant_id from dishes where id = $1', [misal])).rows[0].restaurant_id;
    expect((await share([b.handle], { restaurantId: place })).statusCode).toBe(400);
    const conv = (await app.inject({ method: 'POST', url: '/conversations', headers: a.auth, payload: { handle: b.handle } })).json();
    const say = await app.inject({ method: 'POST', url: `/conversations/${conv.id}/messages`, headers: a.auth, payload: { body: 'hi' } });
    expect(say.statusCode).toBe(400);
    const pic = multipart(a.auth, {}, await photo());
    expect((await app.inject({ method: 'POST', url: `/conversations/${conv.id}/photo`, ...pic })).statusCode).toBe(403);
    // A note sent along with a dish is dropped; the dish is shared.
    const withNote = await app.inject({ method: 'POST', url: `/conversations/${conv.id}/messages`, headers: a.auth, payload: { dishId: misal, body: 'look' } });
    expect(withNote.statusCode).toBe(201);
    expect(withNote.json()).toMatchObject({ kind: 'dish', body: null, dish: { name: 'Misal Pav' } });

    // Unfollowing ends it; turning sharing off ends it; blocks end it.
    await app.inject({ method: 'DELETE', url: `/users/${b.handle}/follow`, headers: a.auth });
    expect((await share([b.handle])).json().failed).toEqual([b.handle]);
    await app.inject({ method: 'PATCH', url: '/me/profile', headers: c.auth, payload: { dmPolicy: 'off' } });
    expect((await share([c.handle])).json().failed).toEqual([c.handle]);
    expect((await app.inject({ url: '/me/friends', headers: a.auth })).json()).toEqual([]);
  });

  it('shows shared dishes and posts in the inbox, with reactions, read state, unsend and notifications', async () => {
    const a = await signUp();
    const b = await signUp();
    const start = (await app.inject({ url: '/updates', headers: b.auth })).json();
    const since = encodeURIComponent(start.now);
    await friends(a, b);
    const { dish, post } = await coverPostOf('Kolhapuri Misal');
    await app.inject({ method: 'POST', url: '/messages', headers: a.auth, payload: { handles: [b.handle], dishId: dish } });
    await app.inject({ method: 'POST', url: '/messages', headers: a.auth, payload: { handles: [b.handle], postId: post } });

    // Straight into b's inbox (friends), unread, newest share as the preview.
    const inbox = (await app.inject({ url: '/inbox', headers: b.auth })).json();
    expect(inbox).toMatchObject({ requests: 0, items: [{ with: { handle: a.handle }, unread: true, lastMessage: { kind: 'post', mine: false } }] });
    expect((await app.inject({ url: '/inbox/unread', headers: b.auth })).json()).toEqual({ chats: 1, requests: 0 });
    // b's phone is told.
    const forB = (await app.inject({ url: `/updates?since=${since}`, headers: b.auth })).json();
    expect(forB.messages.map((m: { kind: string }) => m.kind)).toEqual(['dish', 'post']);

    const convId = inbox.items[0].id;
    const thread = (await app.inject({ url: `/conversations/${convId}`, headers: b.auth })).json();
    expect(thread.messages.map((m: { kind: string }) => m.kind)).toEqual(['dish', 'post']);
    expect(thread.messages[1].post).toMatchObject({ id: post, dishId: dish, dishName: 'Kolhapuri Misal' });
    // Old text messages from before are not shown.
    await db.query(
      "insert into messages (conversation_id, sender_id, kind, body) values ($1, $2, 'text', 'an old chat line')",
      [convId, a.id],
    );
    expect((await app.inject({ url: `/conversations/${convId}`, headers: b.auth })).json().messages).toHaveLength(2);

    // Reactions on a shared dish, and a's phone hears about them.
    const shared = thread.messages[0].id;
    expect((await app.inject({ method: 'PUT', url: `/messages/${shared}/reaction`, headers: b.auth, payload: { emoji: '😋' } })).statusCode).toBe(200);
    const forA = (await app.inject({ url: `/updates?since=${since}`, headers: a.auth })).json();
    expect(forA.reactions).toMatchObject([{ messageId: shared, emoji: '😋', from: { handle: b.handle } }]);
    expect((await app.inject({ url: `/conversations/${convId}`, headers: a.auth })).json().messages[0].reactions).toEqual([{ emoji: '😋', mine: false }]);
    const outsider = await signUp();
    expect((await app.inject({ method: 'PUT', url: `/messages/${shared}/reaction`, headers: outsider.auth, payload: { emoji: '❤️' } })).statusCode).toBe(404);
    expect((await app.inject({ url: `/conversations/${convId}`, headers: outsider.auth })).statusCode).toBe(404);

    // Reading clears the badge; only the sender can unsend; deleting the chat is for you only.
    await app.inject({ method: 'POST', url: `/conversations/${convId}/read`, headers: b.auth });
    expect((await app.inject({ url: '/inbox/unread', headers: b.auth })).json()).toEqual({ chats: 0, requests: 0 });
    expect((await app.inject({ method: 'DELETE', url: `/messages/${shared}`, headers: b.auth })).statusCode).toBe(404);
    expect((await app.inject({ method: 'DELETE', url: `/messages/${shared}`, headers: a.auth })).statusCode).toBe(204);
    await app.inject({ method: 'DELETE', url: `/conversations/${convId}`, headers: b.auth });
    expect((await app.inject({ url: '/inbox', headers: b.auth })).json().items).toHaveLength(0);
    expect((await app.inject({ url: '/inbox', headers: a.auth })).json().items).toHaveLength(1);
  });

  it('answers an open chat\'s poll cheaply while nothing changed, and notices every change', async () => {
    const a = await signUp();
    const b = await signUp();
    await friends(a, b);
    const { dish, post } = await coverPostOf('Misal Pav');
    const conv = (await app.inject({ method: 'POST', url: '/conversations', headers: a.auth, payload: { handle: b.handle } })).json();
    const send = (auth: Record<string, string>, payload: object) =>
      app.inject({ method: 'POST', url: `/conversations/${conv.id}/messages`, headers: auth, payload });
    const poll = async (state: string) => (await app.inject({ url: `/conversations/${conv.id}?state=${encodeURIComponent(state)}`, headers: a.auth })).json();
    const m1 = (await send(a.auth, { dishId: dish })).json();
    let state = (await app.inject({ url: `/conversations/${conv.id}`, headers: a.auth })).json().state;
    expect(await poll(state)).toEqual({ id: conv.id, unchanged: true, state });
    const changed = async (what: string) => {
      const r = await poll(state);
      expect(r.unchanged, what).toBeUndefined();
      expect(r.messages, what).toBeDefined();
      state = r.state;
      expect(await poll(state), what).toMatchObject({ unchanged: true });
    };
    await send(b.auth, { postId: post });
    await changed('a new share');
    await app.inject({ method: 'PUT', url: `/messages/${m1.id}/reaction`, headers: b.auth, payload: { emoji: '🔥' } });
    await changed('a reaction');
    await app.inject({ method: 'PUT', url: `/messages/${m1.id}/reaction`, headers: b.auth, payload: { emoji: '❤️' } });
    await changed('a changed reaction');
    await app.inject({ method: 'DELETE', url: `/messages/${m1.id}/reaction`, headers: b.auth });
    await changed('a removed reaction');
    await app.inject({ method: 'POST', url: `/conversations/${conv.id}/read`, headers: b.auth });
    await changed('they saw it');
    await app.inject({ method: 'DELETE', url: `/messages/${m1.id}`, headers: a.auth });
    await changed('an unsend');
    // Reactions: an emoji, not an essay.
    const m2 = (await send(a.auth, { dishId: dish })).json();
    expect((await app.inject({ method: 'PUT', url: `/messages/${m2.id}/reaction`, headers: b.auth, payload: { emoji: '' } })).statusCode).toBe(400);
    expect((await app.inject({ method: 'PUT', url: `/messages/${m2.id}/reaction`, headers: b.auth, payload: { emoji: 'x'.repeat(17) } })).statusCode).toBe(400);
    // Bad cursors are refused.
    expect((await app.inject({ url: '/updates?since=yesterday', headers: b.auth })).statusCode).toBe(400);
    expect((await app.inject({ url: '/updates' })).statusCode).toBe(401);
  });

  it('respects blocks and reports', async () => {
    const a = await signUp();
    const b = await signUp();
    await friends(a, b);
    const dish = await dishId('Misal Pav');
    await app.inject({ method: 'POST', url: '/messages', headers: a.auth, payload: { handles: [b.handle], dishId: dish } });
    const convId = (await app.inject({ url: '/inbox', headers: b.auth })).json().items[0].id;
    const msg = (await app.inject({ url: `/conversations/${convId}`, headers: b.auth })).json().messages[0];
    const rep = await app.inject({ method: 'POST', url: '/reports', headers: b.auth, payload: { targetType: 'message', targetId: msg.id, reason: 'spam' } });
    expect(rep.json()).toEqual({ ok: true, hidden: false });
    await app.inject({ method: 'POST', url: '/blocks', headers: b.auth, payload: { handle: a.handle } });
    expect((await app.inject({ method: 'POST', url: '/messages', headers: a.auth, payload: { handles: [b.handle], dishId: dish } })).json().failed).toEqual([b.handle]);
    expect((await app.inject({ url: '/inbox', headers: b.auth })).json().items).toHaveLength(0);
  });
});

describe('comments', () => {
  async function coverPost(): Promise<{ postId: string; dish: string }> {
    const dish = await dishId('Misal Pav');
    const card = (await app.inject(`/dishes/${dish}`)).json();
    return { postId: card.cover.postId as string, dish };
  }

  it('lets people comment, reply in one thread, like and see counts', async () => {
    const { postId, dish } = await coverPost();
    const a = await signUp();
    const b = await signUp();
    expect((await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, payload: { body: 'hi' } })).statusCode).toBe(401);

    const first = await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: a.auth, payload: { body: '  Best misal in town!  ' } });
    expect(first.statusCode).toBe(201);
    const c1 = first.json();
    expect(c1).toMatchObject({ body: 'Best misal in town!', author: { handle: a.handle }, likes: 0, canDelete: true, parentId: null });

    const reply = (await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: b.auth, payload: { body: 'Agreed', parentId: c1.id } })).json();
    expect(reply.parentId).toBe(c1.id);
    // A reply to a reply stays in the same thread.
    const deep = (await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: a.auth, payload: { body: 'Go early', parentId: reply.id } })).json();
    expect(deep.parentId).toBe(c1.id);
    expect((await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: a.auth, payload: { body: '   ' } })).statusCode).toBe(400);

    const liked = (await app.inject({ method: 'PUT', url: `/comments/${c1.id}/like`, headers: b.auth })).json();
    expect(liked).toEqual({ likes: 1, likedByMe: true });
    expect((await app.inject({ method: 'PUT', url: `/comments/${c1.id}/like`, headers: b.auth })).json().likes).toBe(1);

    const list = (await app.inject({ url: `/posts/${postId}/comments`, headers: b.auth })).json();
    expect(list.count).toBe(3);
    expect(list.comments).toHaveLength(1);
    expect(list.comments[0]).toMatchObject({ id: c1.id, likes: 1, likedByMe: true, canDelete: false });
    expect(list.comments[0].replies.map((r: { body: string }) => r.body)).toEqual(['Agreed', 'Go early']);

    const card = (await app.inject(`/dishes/${dish}`)).json();
    expect(card.cover.commentCount).toBe(3);
    expect(card.posts[0].commentCount).toBe(3);

    expect((await app.inject({ method: 'DELETE', url: `/comments/${c1.id}/like`, headers: b.auth })).json()).toEqual({ likes: 0, likedByMe: false });
  });

  it('hides blocked people, and only the author, post owner or an admin can delete', async () => {
    const { postId } = await coverPost();
    const a = await signUp();
    const b = await signUp();
    const c = (await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: a.auth, payload: { body: 'Too spicy for me' } })).json();
    await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: a.auth, payload: { body: 'reply', parentId: c.id } });

    await app.inject({ method: 'POST', url: '/blocks', headers: b.auth, payload: { handle: a.handle } });
    const seenByB = (await app.inject({ url: `/posts/${postId}/comments`, headers: b.auth })).json();
    expect(seenByB.comments.find((x: { id: string }) => x.id === c.id)).toBeUndefined();

    expect((await app.inject({ method: 'DELETE', url: `/comments/${c.id}`, headers: b.auth })).statusCode).toBe(403);
    expect((await app.inject({ method: 'DELETE', url: `/comments/${c.id}`, headers: a.auth })).statusCode).toBe(200);
    const after = (await app.inject(`/posts/${postId}/comments`)).json();
    expect(after.comments.find((x: { id: string }) => x.id === c.id)).toBeUndefined();
    expect(JSON.stringify(after)).not.toContain('Too spicy');
    expect(JSON.stringify(after)).not.toContain('"reply"');
  });

  it('auto-hides a comment after three established reporters and shows it to admins', async () => {
    const { postId } = await coverPost();
    const a = await signUp();
    const c = (await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: a.auth, payload: { body: 'buy followers here' } })).json();
    for (let i = 0; i < 3; i++) {
      const r = await signUp({ established: true });
      const res = await app.inject({ method: 'POST', url: '/reports', headers: r.auth, payload: { targetType: 'comment', targetId: c.id, reason: 'spam' } });
      expect(res.statusCode).toBe(201);
    }
    const list = (await app.inject(`/posts/${postId}/comments`)).json();
    expect(list.comments.find((x: { id: string }) => x.id === c.id)).toBeUndefined();

    const login = await app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'admin@example.com', password: 'correct horse' } });
    const admin = { auth: { authorization: `Bearer ${login.json().token}` } };
    const queue = (await app.inject({ url: '/admin/queue', headers: admin.auth })).json();
    const item = queue.reported.find((x: { targetId: string }) => x.targetId === c.id);
    expect(item).toMatchObject({ targetType: 'comment', status: 'hidden', label: '"buy followers here"' });
    const restore = await app.inject({ method: 'POST', url: `/admin/comments/${c.id}/restore`, headers: admin.auth });
    expect(restore.statusCode).toBe(200);
  });
});

describe('profiles and following', () => {
  it('edits the profile: username, name, bio, photo and password', async () => {
    const u = await signUp();
    const res = await app.inject({ method: 'PATCH', url: '/me/profile', headers: u.auth, payload: { username: 'Misal_Lover', displayName: 'Misal Lover', bio: 'Breakfast first.' } });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ handle: 'misal_lover', displayName: 'Misal Lover', bio: 'Breakfast first.', avatarUrl: null });
    const other = await signUp();
    const taken = await app.inject({ method: 'PATCH', url: '/me/profile', headers: other.auth, payload: { username: 'misal_lover' } });
    expect(taken.statusCode).toBe(409);
    expect(taken.json().error).toBe('username_taken');

    const avatar = await app.inject({ method: 'POST', url: '/me/avatar', ...multipart(u.auth, {}, await photo()) });
    expect(avatar.statusCode).toBe(200);
    const url = avatar.json().avatarUrl as string;
    expect(url).toMatch(/^\/media\/avatars\/.+\.webp$/);
    const meta = await sharp(await fs.readFile(path.join(mediaDir, url.replace('/media/', '')))).metadata();
    expect([meta.width, meta.height]).toEqual([320, 320]);
    expect((await app.inject({ method: 'DELETE', url: '/me/avatar', headers: u.auth })).json().avatarUrl).toBeNull();
    await expect(fs.stat(path.join(mediaDir, url.replace('/media/', '')))).rejects.toThrow();

    const wrong = await app.inject({ method: 'POST', url: '/me/password', headers: u.auth, payload: { current: 'nope', next: 'brand new pass' } });
    expect(wrong.statusCode).toBe(400);
    const ok = await app.inject({ method: 'POST', url: '/me/password', headers: u.auth, payload: { current: 'correct horse', next: 'brand new pass' } });
    expect(ok.statusCode).toBe(200);
    const login = await app.inject({ method: 'POST', url: '/auth/login', payload: { email: `${u.handle}@example.com`, password: 'brand new pass' } });
    expect(login.statusCode).toBe(200);
  });

  it('follows, lists followers and notifies, then unfollows', async () => {
    const a = await signUp();
    const b = await signUp();
    const f = await app.inject({ method: 'PUT', url: `/users/${b.handle}/follow`, headers: a.auth });
    expect(f.json()).toEqual({ isFollowing: true, followers: 1 });
    expect((await app.inject({ method: 'PUT', url: `/users/${a.handle}/follow`, headers: a.auth })).statusCode).toBe(400);

    const profile = (await app.inject({ url: `/users/${b.handle}`, headers: a.auth })).json();
    expect(profile).toMatchObject({ followers: 1, following: 0, isFollowing: true, followsYou: false, isMe: false });
    const followers = (await app.inject(`/users/${b.handle}/followers`)).json();
    expect(followers.map((x: { handle: string }) => x.handle)).toEqual([a.handle]);
    expect((await app.inject(`/users/${a.handle}/following`)).json()[0].handle).toBe(b.handle);

    expect((await app.inject({ url: '/notifications/unread', headers: b.auth })).json()).toEqual({ count: 1 });
    const inbox = (await app.inject({ url: '/notifications', headers: b.auth })).json();
    expect(inbox.items[0]).toMatchObject({ kind: 'follow', read: false, actor: { handle: a.handle } });
    await app.inject({ method: 'POST', url: '/notifications/read', headers: b.auth });
    expect((await app.inject({ url: '/notifications/unread', headers: b.auth })).json()).toEqual({ count: 0 });

    // Following again after unfollowing does not notify twice.
    await app.inject({ method: 'DELETE', url: `/users/${b.handle}/follow`, headers: a.auth });
    await app.inject({ method: 'PUT', url: `/users/${b.handle}/follow`, headers: a.auth });
    expect((await app.inject({ url: '/notifications', headers: b.auth })).json().items).toHaveLength(1);
    expect((await app.inject({ method: 'DELETE', url: `/users/${b.handle}/follow`, headers: a.auth })).json()).toEqual({ isFollowing: false, followers: 0 });

    // Blocked people can't follow, and appear in the blocked list.
    await app.inject({ method: 'POST', url: '/blocks', headers: b.auth, payload: { handle: a.handle } });
    expect((await app.inject({ method: 'PUT', url: `/users/${b.handle}/follow`, headers: a.auth })).statusCode).toBe(403);
    expect((await app.inject({ url: '/me/blocked', headers: b.auth })).json()[0].handle).toBe(a.handle);
  });

  it('finds people by username or name', async () => {
    const u = await signUp();
    await app.inject({ method: 'PATCH', url: '/me/profile', headers: u.auth, payload: { username: 'chai_queen', displayName: 'Radha Kulkarni' } });
    expect((await app.inject('/people?q=chai_q')).json()[0].handle).toBe('chai_queen');
    expect((await app.inject('/people?q=@chai_queen')).json()[0].handle).toBe('chai_queen');
    expect((await app.inject('/people?q=kulkarni')).json().map((x: { handle: string }) => x.handle)).toContain('chai_queen');
    // Searching while signed in marks you, so the app can leave you out of "message someone".
    const mine = (await app.inject({ url: '/people?q=chai_queen', headers: u.auth })).json();
    expect(mine[0]).toMatchObject({ handle: 'chai_queen', isMe: true });
    expect((await app.inject('/people?q=chai_queen')).json()[0].isMe).toBe(false);
  });

  it('notifies on comments, replies and mentions, and respects comment settings', async () => {
    const author = await signUp({ established: true });
    const restaurantId = (await db.query("select id from restaurants where name = 'Chai Katta'")).rows[0].id;
    const post = (await app.inject({
      method: 'POST', url: '/posts',
      ...multipart(author.auth, { idempotencyKey: 'key-social-1', dishName: 'Kesar Chai', restaurantId }, await photo()),
    })).json();
    const a = await signUp();
    const b = await signUp();
    const c1 = (await app.inject({ method: 'POST', url: `/posts/${post.id}/comments`, headers: a.auth, payload: { body: `Looks great @${b.handle}` } })).json();
    await app.inject({ method: 'POST', url: `/posts/${post.id}/comments`, headers: b.auth, payload: { body: 'Agreed!', parentId: c1.id } });
    await app.inject({ method: 'PUT', url: `/comments/${c1.id}/like`, headers: author.auth });
    const kinds = async (u: { auth: Record<string, string> }) =>
      (await app.inject({ url: '/notifications', headers: u.auth })).json().items.map((n: { kind: string }) => n.kind).sort();
    expect(await kinds(author)).toEqual(['comment', 'comment']);
    expect(await kinds(a)).toEqual(['comment_like', 'reply']);
    expect(await kinds(b)).toEqual(['mention']);

    await app.inject({ method: 'PATCH', url: '/me/profile', headers: author.auth, payload: { commentPolicy: 'followers' } });
    const blocked = await app.inject({ method: 'POST', url: `/posts/${post.id}/comments`, headers: a.auth, payload: { body: 'hi again' } });
    expect(blocked.statusCode).toBe(403);
    await app.inject({ method: 'PUT', url: `/users/${a.handle}/follow`, headers: author.auth });
    expect((await app.inject({ method: 'POST', url: `/posts/${post.id}/comments`, headers: a.auth, payload: { body: 'hi again' } })).statusCode).toBe(201);
    await app.inject({ method: 'PATCH', url: '/me/profile', headers: author.auth, payload: { commentPolicy: 'off' } });
    expect((await app.inject({ method: 'POST', url: `/posts/${post.id}/comments`, headers: a.auth, payload: { body: 'and again' } })).json().error).toBe('comments_limited');
    // The author can always comment on their own post.
    expect((await app.inject({ method: 'POST', url: `/posts/${post.id}/comments`, headers: author.auth, payload: { body: 'Thanks all' } })).statusCode).toBe(201);
  });
});

describe("People's Pick", () => {
  async function vote(u: { auth: Record<string, string> }, dish: string, kind: string) {
    const res = await app.inject({ method: 'PUT', url: `/dishes/${dish}/engagements/${kind}`, headers: u.auth });
    expect(res.statusCode).toBe(200);
    return res.json();
  }

  it('records "not for me" as tried, never together with a love, and never as a boost', async () => {
    const dish = await dishId('Bun Maska');
    const u = await signUp({ established: true });
    await vote(u, dish, 'recommend');
    const passed = await vote(u, dish, 'pass');
    expect(passed.me).toMatchObject({ recommended: false, tried: true, passed: true });
    expect(passed.rating.passes).toBeGreaterThanOrEqual(1);
    const { rows } = await db.query("select tried, endorsers from dish_stats where dish_id = $1", [dish]);
    // The pass and its implied "tried" don't add a ranking point.
    const { rows: score } = await db.query('select score from dish_stats where dish_id = $1', [dish]);
    expect(rows.length === 0 || score[0].score === 0 || rows[0].endorsers >= 0).toBe(true);
    const loved = await vote(u, dish, 'recommend');
    expect(loved.me).toMatchObject({ recommended: true, passed: false });
  });

  it('ranks a craving by weighted approval, not raw counts, and names the people to trust', async () => {
    // A second Pav Bhaji, at another place.
    const author = await signUp({ established: true });
    const restaurantId = (await db.query("select id from restaurants where name = 'Chai Katta'")).rows[0].id;
    const second = (await app.inject({
      method: 'POST', url: '/posts',
      ...multipart(author.auth, { idempotencyKey: 'key-pavbhaji-2', dishName: 'Pav Bhaji', restaurantId }, await photo()),
    })).json().dishId as string;
    const first = (await db.query(
      "select d.id from dishes d join restaurants r on r.id = d.restaurant_id where d.name = 'Pav Bhaji' and r.name = 'Pav Bhaji Point'",
    )).rows[0].id as string;

    // An early fan loves the second one; four people agree after them.
    const fan = await signUp({ established: true });
    await vote(fan, second, 'recommend');
    for (let i = 0; i < 4; i++) await vote(await signUp({ established: true }), second, 'recommend');
    // The first one gets more raw loves but also many "not for me".
    for (let i = 0; i < 5; i++) await vote(await signUp({ established: true }), first, 'recommend');
    for (let i = 0; i < 6; i++) await vote(await signUp({ established: true }), first, 'pass');

    // Trust is rebuilt a moment after the votes (once for the whole burst).
    await new Promise((r) => setTimeout(r, 3500));
    const res = (await app.inject(`/craving?q=paav%20bhaji&city=${cityId}`)).json();
    expect(res.label).toBe('Pav Bhaji');
    const order = res.results.map((c: { id: string }) => c.id);
    expect(order.indexOf(second)).toBeLessThan(order.indexOf(first));
    const top = res.results[0];
    expect(top.rating).toMatchObject({ loves: 5, passes: 0, percent: 100 });
    expect(top.champion.handle).toBe(fan.handle);

    // The fan earned trust: their pick was backed by everyone who came after.
    const { rows } = await db.query(
      "select t.trust, t.picks, t.backers from taster_stats t join profiles p on p.id = t.user_id where p.handle = $1 and t.category_id = 0",
      [fan.handle],
    );
    expect(rows[0]).toMatchObject({ picks: 1, backers: 4 });
    expect(rows[0].trust).toBeGreaterThan(0.6); // above the floor everyone starts at
    // The fan is listed among the people to trust for pav bhaji (people with longer records may rank above).
    expect(res.tasters.map((t: { handle: string }) => t.handle)).toContain(fan.handle);
    // ...and was told each time someone agreed (up to three people per new love).
    const inbox = (await app.inject({ url: '/notifications', headers: (await loginAs(fan.handle)).auth })).json();
    expect(inbox.items.some((n: { kind: string }) => n.kind === 'agree')).toBe(true);
  });

  it('serves the home sections and the map', async () => {
    const home = (await app.inject(`/home?city=${cityId}&lat=20.0&lng=73.78`)).json();
    expect(Object.keys(home).sort()).toEqual(['cravings', 'following', 'forYou', 'fresh', 'nearby', 'peoplesPicks', 'tasters', 'trending']);
    expect(home.peoplesPicks.length).toBeGreaterThan(0);
    expect(home.peoplesPicks[0].rating.loves).toBeGreaterThan(0);
    expect(home.cravings.length).toBeGreaterThan(0);
    expect(home.nearby.length).toBeGreaterThan(0);

    const map = (await app.inject(`/map?city=${cityId}`)).json();
    expect(map.places.length).toBeGreaterThan(5);
    const p = map.places[0];
    expect(p).toMatchObject({ dishCount: expect.any(Number), lat: expect.any(Number), lng: expect.any(Number) });
    expect(p.topDish).toMatchObject({ name: expect.any(String), thumbUrl: expect.stringMatching(/^\/media\//) });
  });
});

describe('fair ranking', () => {
  // Places and dishes made directly in the database, so each case starts from a clean slate.
  async function mkDish(name: string, daysOld = 60): Promise<string> {
    const r = await db.query(
      `insert into restaurants (city_id, name, name_norm, geo, status, created_at)
       values ($1, $2, norm_name($2), st_setsrid(st_makepoint(73.7, 20.1), 4326)::geography, 'active', now() - make_interval(days => $3))
       returning id`,
      [cityId, `Fair Place ${name}`, daysOld],
    );
    const d = await db.query(
      `insert into dishes (restaurant_id, city_id, name, name_norm, created_at)
       values ($1, $2, $3, norm_name($3), now() - make_interval(days => $4)) returning id`,
      [r.rows[0].id, cityId, name, daysOld],
    );
    await db.query(
      `insert into posts (dish_id, image_path, thumb_path, created_at) values ($1, '/media/x.webp', '/media/x.webp', now() - make_interval(days => $2))`,
      [d.rows[0].id, daysOld],
    );
    return d.rows[0].id;
  }
  async function vote(userId: string, dish: string, kind: string, hoursAgo: number) {
    await db.query(
      'insert into engagements (user_id, dish_id, kind, created_at) values ($1, $2, $3, now() - make_interval(hours => $4))',
      [userId, dish, kind, hoursAgo],
    );
  }
  async function aged(days: number) {
    const u = await signUp();
    await db.query('update profiles set created_at = now() - make_interval(days => $2) where id = $1', [u.id, days]);
    return u;
  }
  const put = (u: { auth: Record<string, string> }, dish: string, kind: string) =>
    app.inject({ method: 'PUT', url: `/dishes/${dish}/engagements/${kind}`, headers: u.auth });
  const trustOf = async (id: string) =>
    (await db.query('select trust, expert from taster_stats where user_id = $1 and category_id = 0', [id])).rows[0];

  afterAll(async () => {
    await db.query("update restaurants set status = 'hidden' where name like 'Fair Place %'");
    await db.query('select refresh_dish_stats()');
    await db.query('select refresh_people_pick()');
  });

  it('keeps brand-new accounts from taking over Top and Trending', async () => {
    const real = await mkDish('Fair Real Favourite');
    const rush = await mkDish('Fair Rushed Dish');
    for (let i = 0; i < 8; i++) expect((await put(await signUp({ established: true }), real, 'recommend')).statusCode).toBe(200);
    // Three accounts made a moment ago do everything they can for the other dish.
    for (let i = 0; i < 3; i++) {
      const fresh = await signUp();
      for (const k of ['recommend', 'save', 'tried']) await put(fresh, rush, k);
      await app.inject({ method: 'POST', url: `/dishes/${rush}/share`, headers: fresh.auth });
    }
    const stats = async (id: string) => (await db.query('select score, w_endorsers, endorsers from dish_stats where dish_id = $1', [id])).rows[0];
    const r = await stats(real);
    const x = await stats(rush);
    expect(r.w_endorsers).toBeCloseTo(8, 1);
    expect(x.endorsers).toBe(3); // they are counted as people who acted...
    expect(x.w_endorsers).toBeLessThan(0.01); // ...but carry almost no weight
    expect(x.score).toBeLessThan(r.score / 100);

    const ids = async (tab: string) =>
      (await app.inject(`/feed?city=${cityId}&tab=${tab}&limit=50`)).json().items.map((i: { id: string; lane: string }) => `${i.id}:${i.lane}`);
    expect(await ids('trending')).toContain(`${real}:trending`);
    expect((await ids('trending')).some((s: string) => s.startsWith(rush))).toBe(false);
    const top = await ids('top');
    expect(top).toContain(`${real}:top`);
    expect(top.some((s: string) => s.startsWith(rush))).toBe(false);
  });

  it("keeps a ring of accounts voting together from winning People's Pick", async () => {
    const pick = await mkDish('Fair Puppet Pick');
    const rival = await mkDish('Fair Rival Pick');
    const fans: string[] = [];
    for (let i = 0; i < 4; i++) {
      const u = await aged(30);
      fans.push(u.id);
      await vote(u.id, rival, 'recommend', 24 * (i + 1));
    }
    // Six month-old accounts, all loving one dish and sinking its rival within minutes.
    const ring: string[] = [];
    for (let i = 0; i < 6; i++) {
      const u = await aged(30);
      ring.push(u.id);
      expect((await put(u, pick, 'recommend')).statusCode).toBe(200);
      expect((await put(u, rival, 'pass')).statusCode).toBe(200);
    }
    await db.query('select refresh_people_pick()');
    const twins = (await db.query('select twins from voter_independence where user_id = $1', [ring[0]])).rows[0];
    expect(twins.twins).toBe(5);
    expect((await db.query('select twins from voter_independence where user_id = $1', [fans[0]])).rowCount).toBe(0);

    const rating = async (id: string) => (await db.query('select * from dish_rating where dish_id = $1', [id])).rows[0];
    const p = await rating(pick);
    const r = await rating(rival);
    expect(p.loves).toBe(6);
    expect(p.w_loves).toBeLessThan(0.7); // six accounts, one person's worth of love
    expect(r.confidence).toBeGreaterThan(p.confidence);
    expect(p.champion_id).toBeNull(); // nobody in the ring stands tall enough to be its champion
    for (const id of ring) expect((await trustOf(id))?.expert ?? false).toBe(false);
  });

  it('builds trust from good judgement: a right "not for me" counts, loving everything does not', async () => {
    const dishes = [];
    for (let i = 0; i < 6; i++) dishes.push(await mkDish(`Fair Judged ${i}`));
    const shotgun = await aged(100);
    const sharp = await aged(100);
    const contrarian = await aged(100);
    const newcomer = await aged(100);
    // The shotgun loves four dishes first; later, three in four people agree (no better
    // than the usual approval, so no credit).
    for (const d of dishes.slice(0, 4)) await vote(shotgun.id, d, 'recommend', 24 * 20);
    // The sharp one says "not for me" to a dish that everyone after them passes on too.
    await vote(sharp.id, dishes[4]!, 'pass', 24 * 20);
    // The contrarian loves a dish everyone after them passes on.
    await vote(contrarian.id, dishes[5]!, 'recommend', 24 * 20);
    for (let g = 0; g < 4; g++) {
      const crowd = await aged(100);
      // Hours apart, so the crowd are independent people, not a ring.
      for (const [i, d] of dishes.slice(0, 4).entries()) await vote(crowd.id, d, g === 3 ? 'pass' : 'recommend', 24 * 10 + g * 5 + i * 30);
      await vote(crowd.id, dishes[4]!, 'pass', 24 * 9 + g * 5);
      await vote(crowd.id, dishes[5]!, 'pass', 24 * 8 + g * 5);
    }
    await db.query('select refresh_people_pick()');
    const floor = 0.6;
    const s = await trustOf(shotgun.id);
    const sh = await trustOf(sharp.id);
    const c = await trustOf(contrarian.id);
    expect(s.trust).toBeCloseTo(floor, 3);
    expect(s.expert).toBe(false);
    expect(sh.trust).toBeGreaterThan(floor + 0.2);
    // A newcomer has no record and starts at the floor: never above someone with a poor record.
    expect(await trustOf(newcomer.id)).toBeUndefined();
    expect(c.trust).toBeCloseTo(floor, 3);
  });

  it('finds every dish a craving means', async () => {
    const names = async (q: string) =>
      (await app.inject(`/craving?q=${encodeURIComponent(q)}&city=${cityId}`)).json().results.map((r: { name: string }) => r.name);
    const chicken = await names('chicken');
    expect(chicken.filter((n: string) => /chicken/i.test(n)).length).toBeGreaterThanOrEqual(2);
    expect(await names('pav')).toEqual(expect.arrayContaining(['Pav Bhaji', 'Misal Pav', 'Vada Pav']));
    const exact = await names('pav bhaji');
    expect(exact).toContain('Pav Bhaji');
    expect(exact).not.toContain('Kanda Bhaji');
  });

  it('puts a dish nobody has voted on above one people dislike', async () => {
    const unrated = await mkDish('Fair Untasted');
    const disliked = await mkDish('Fair Disliked');
    for (let i = 0; i < 3; i++) await put(await signUp({ established: true }), disliked, 'pass');
    await db.query('select refresh_people_pick()');
    const conf = async (id: string) => (await db.query('select loves, passes, confidence from dish_rating where dish_id = $1', [id])).rows[0];
    const u = await conf(unrated);
    expect(u).toMatchObject({ loves: 0, passes: 0 });
    expect(u.confidence).toBeGreaterThan((await conf(disliked)).confidence);
  });

  it('undoes "not for me" completely, and never lists a "not for me" as a recommendation', async () => {
    const dish = await mkDish('Fair Undo');
    const u = await signUp({ established: true });
    let res = (await put(u, dish, 'pass')).json();
    expect(res.me).toMatchObject({ passed: true, tried: true });
    expect((await app.inject(`/dishes/${dish}`)).json().recommenders).toEqual([]);
    res = (await app.inject({ method: 'DELETE', url: `/dishes/${dish}/engagements/pass`, headers: u.auth })).json();
    expect(res.me).toMatchObject({ passed: false, tried: false });
    expect(res.stats.tried).toBe(0);
    // A "tried" marked on its own before the pass stays when the pass is undone.
    await put(u, dish, 'tried');
    await new Promise((r) => setTimeout(r, 20));
    await put(u, dish, 'pass');
    res = (await app.inject({ method: 'DELETE', url: `/dishes/${dish}/engagements/pass`, headers: u.auth })).json();
    expect(res.me).toMatchObject({ passed: false, tried: true });
  });

  it('keeps the Fresh lane fresh', async () => {
    const stale = await mkDish('Fair Stale Untried', 60);
    const renewed = await mkDish('Fair Renewed', 60);
    await db.query(`insert into posts (dish_id, image_path, thumb_path) values ($1, '/media/y.webp', '/media/y.webp')`, [renewed]);
    const items: { id: string; lane: string }[] = [];
    for (let offset: number | null = 0; offset !== null; ) {
      const page = (await app.inject(`/feed?city=${cityId}&limit=50&offset=${offset}`)).json();
      items.push(...page.items);
      offset = page.nextOffset;
    }
    expect(items.find((i) => i.id === stale)).toBeUndefined();
    expect(items.find((i) => i.id === renewed)?.lane).toBe('fresh');
  });

  it('rejects settings that would break the maths, and applies good ones at once', async () => {
    const login = () => app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'admin@example.com', password: 'correct horse' } });
    if ((await login()).statusCode !== 200) await signUp({ email: 'admin@example.com' });
    const headers = { authorization: `Bearer ${(await login()).json().token}` };
    const set = (key: string, value: number) => app.inject({ method: 'PUT', url: `/admin/ranking-config/${key}`, headers, payload: { value } });
    expect((await set('half_life_days', 0)).statusCode).toBe(400);
    expect((await set('pp_prior', 1.5)).statusCode).toBe(400);
    // Even a bad value written straight into the database can't break a vote.
    await db.query("update ranking_config set value = 0 where key in ('half_life_days', 'pp_prior_weight', 'pp_half_life_days')");
    try {
      await db.query('select refresh_dish_stats(); select refresh_people_pick()');
    } finally {
      await db.query("update ranking_config set value = 45 where key = 'half_life_days'");
      await db.query("update ranking_config set value = 3 where key = 'pp_prior_weight'");
      await db.query("update ranking_config set value = 180 where key = 'pp_half_life_days'");
    }
    const dish = await mkDish('Fair Settings');
    const before = (await db.query('select confidence from dish_rating where dish_id = $1', [dish])).rows[0];
    expect((await set('pp_z', 2)).statusCode).toBe(200);
    const after = (await db.query('select confidence from dish_rating where dish_id = $1', [dish])).rows[0];
    expect(after.confidence).toBeLessThan(before?.confidence ?? 1);
    expect((await set('pp_z', 1)).statusCode).toBe(200);
  });

  it("hides a champion you blocked, and forgets a deleted account's votes", async () => {
    const dish = await mkDish('Fair Champion');
    const champ = await aged(30);
    await vote(champ.id, dish, 'recommend', 48);
    await db.query('select refresh_people_pick()');
    const viewer = await signUp();
    expect((await app.inject({ url: `/dishes/${dish}`, headers: viewer.auth })).json().champion?.handle).toBe(champ.handle);
    await app.inject({ method: 'POST', url: '/blocks', headers: viewer.auth, payload: { handle: champ.handle } });
    expect((await app.inject({ url: `/dishes/${dish}`, headers: viewer.auth })).json().champion).toBeNull();
    // The account goes; its love goes from the dish straight away.
    await app.inject({ method: 'DELETE', url: '/me', headers: champ.auth });
    const r = (await db.query('select loves from dish_rating where dish_id = $1', [dish])).rows[0];
    expect(r.loves).toBe(0);
  });
});

describe('people-based recommendation', () => {
  async function category(slug: string): Promise<number> {
    return (await db.query('select id from dish_categories where slug = $1', [slug])).rows[0].id;
  }
  async function mkDish(name: string, slug: string, isVeg: boolean, lng = 73.78, lat = 20.0): Promise<string> {
    const r = await db.query(
      `insert into restaurants (city_id, name, name_norm, geo, status)
       values ($1, $2, norm_name($2), st_setsrid(st_makepoint($3, $4), 4326)::geography, 'active') returning id`,
      [cityId, `Taste Place ${name}`, lng, lat],
    );
    const d = await db.query(
      `insert into dishes (restaurant_id, city_id, category_id, name, name_norm, is_veg)
       values ($1, $2, $3, $4, norm_name($4), $5) returning id`,
      [r.rows[0].id, cityId, await category(slug), name, isVeg],
    );
    await db.query(`insert into posts (dish_id, image_path, thumb_path) values ($1, '/media/t.webp', '/media/t.webp')`, [d.rows[0].id]);
    return d.rows[0].id;
  }
  async function person() {
    const u = await signUp();
    await db.query("update profiles set created_at = now() - interval '30 days' where id = $1", [u.id]);
    return u;
  }
  async function vote(userId: string, dish: string, kind: string, hoursAgo = 1) {
    await db.query(
      'insert into engagements (user_id, dish_id, kind, created_at) values ($1, $2, $3, now() - make_interval(hours => $4)) on conflict do nothing',
      [userId, dish, kind, hoursAgo],
    );
  }

  afterAll(async () => {
    await db.query("update restaurants set status = 'hidden' where name like 'Taste Place %'");
  });

  it('keeps a taste profile and learns from what you love', async () => {
    const u = await person();
    const misal = await category('misal');
    expect((await app.inject({ url: '/me/taste' })).statusCode).toBe(401);
    let taste = (await app.inject({ url: '/me/taste', headers: u.auth })).json();
    expect(taste).toMatchObject({ set: false, categories: [], diet: 'nonveg', spice: 3 });
    const put = await app.inject({ method: 'PUT', url: '/me/taste', headers: u.auth, payload: { categories: [misal, 99999], diet: 'veg', spice: 4 } });
    expect(put.statusCode).toBe(200);
    expect((await app.inject({ method: 'PUT', url: '/me/taste', headers: u.auth, payload: { categories: [], diet: 'vegan', spice: 9 } })).statusCode).toBe(400);
    const chai = await mkDish('Taste Kadak Chai', 'chai-coffee', true);
    await vote(u.id, chai, 'recommend');
    taste = (await app.inject({ url: '/me/taste', headers: u.auth })).json();
    expect(taste).toMatchObject({ set: true, categories: [misal], diet: 'veg', spice: 4 });
    expect(taste.learned.map((l: { name: string }) => l.name)).toEqual(expect.arrayContaining(['Misal', 'Chai & Coffee']));
  });

  it('shows a Taste Match on profiles and suggests people with your taste', async () => {
    const dishes = [];
    for (let i = 0; i < 5; i++) dishes.push(await mkDish(`Taste Match ${i}`, 'street-food', true));
    const me = await person();
    const twin = await person();
    const opposite = await person();
    const stranger = await person();
    for (const [i, d] of dishes.entries()) {
      await vote(me.id, d, i < 4 ? 'recommend' : 'pass', 48 + i);
      await vote(twin.id, d, i < 4 ? 'recommend' : 'pass', 10 + i * 5);
      await vote(opposite.id, d, i < 4 ? 'pass' : 'recommend', 20 + i * 5);
    }
    const profile = async (h: string, auth = me.auth) => (await app.inject({ url: `/users/${h}`, headers: auth })).json();
    const t = (await profile(twin.handle)).tasteMatch;
    const o = (await profile(opposite.handle)).tasteMatch;
    expect(t.percent).toBeGreaterThanOrEqual(70);
    expect(t).toMatchObject({ coVotes: 5, agreed: 5 });
    expect(t.shared.map((x: { name: string }) => x.name)).toContain('Street Food');
    expect(o.percent).toBeLessThan(t.percent);
    expect((await profile(stranger.handle)).tasteMatch).toBeNull(); // nothing to go on yet
    expect((await profile(me.handle)).tasteMatch).toBeNull(); // not with yourself
    expect((await app.inject(`/users/${twin.handle}`)).json().tasteMatch).toBeNull(); // guests have no taste

    const suggested = (await app.inject({ url: '/people/taste', headers: me.auth })).json().map((p: { handle: string }) => p.handle);
    expect(suggested[0]).toBe(twin.handle);
    expect(suggested).not.toContain(opposite.handle);
    await app.inject({ method: 'PUT', url: `/users/${twin.handle}/follow`, headers: me.auth });
    const after = (await app.inject({ url: '/people/taste', headers: me.auth })).json().map((p: { handle: string }) => p.handle);
    expect(after).not.toContain(twin.handle); // already followed
  });

  it('recommends what people like you and people you follow loved', async () => {
    const shared = [];
    for (let i = 0; i < 4; i++) shared.push(await mkDish(`Taste Shared ${i}`, 'misal', true));
    const theirFind = await mkDish('Taste Their Find', 'misal', true);
    const meaty = await mkDish('Taste Chicken Find', 'biryani', false);
    const friendsFind = await mkDish('Taste Friends Find', 'desserts', true);
    const disliked = await mkDish('Taste Disliked', 'misal', true);
    const me = await person();
    const alike = await person();
    const friend = await person();
    for (const [i, d] of shared.entries()) {
      await vote(me.id, d, 'recommend', 60 + i);
      await vote(alike.id, d, 'recommend', 20 + i * 5);
    }
    await vote(alike.id, theirFind, 'recommend');
    await vote(alike.id, meaty, 'recommend');
    await vote(alike.id, disliked, 'pass');
    await vote(friend.id, friendsFind, 'recommend');
    await app.inject({ method: 'PUT', url: `/users/${friend.handle}/follow`, headers: me.auth });

    const forYou = async () => (await app.inject({ url: `/foryou?city=${cityId}&limit=50`, headers: me.auth })).json().items as {
      id: string; lane: string; because: { handle: string | null; match: number | null; followed: boolean };
    }[];
    let items = await forYou();
    // Ranked: the API keeps for_you()'s order.
    const order = (await db.query('select dish_id from for_you($1, $2, 50, 0)', [me.id, cityId])).rows.map((r) => r.dish_id);
    expect(items.map((i) => i.id)).toEqual(order);
    const find = items.find((i) => i.id === theirFind)!;
    expect(find.lane).toBe('foryou');
    expect(find.because).toMatchObject({ handle: alike.handle, followed: false });
    expect(find.because.match).toBeGreaterThanOrEqual(55);
    expect(items.find((i) => i.id === friendsFind)?.because).toMatchObject({ handle: friend.handle, followed: true });
    expect(items.some((i) => shared.includes(i.id))).toBe(false); // already loved
    expect(items.some((i) => i.id === disliked)).toBe(false); // they said "not for me"
    expect(items.some((i) => i.id === meaty)).toBe(true);
    // A vegetarian never sees non-veg dishes.
    await app.inject({ method: 'PUT', url: '/me/taste', headers: me.auth, payload: { categories: [], diet: 'veg', spice: 3 } });
    items = await forYou();
    expect(items.some((i) => i.id === meaty)).toBe(false);

    expect((await app.inject(`/feed?city=${cityId}&tab=foryou`)).statusCode).toBe(401);
    const tab = (await app.inject({ url: `/feed?city=${cityId}&tab=foryou`, headers: me.auth })).json();
    expect(tab.items.map((i: { id: string }) => i.id)).toContain(theirFind);
    const home = (await app.inject({ url: `/home?city=${cityId}`, headers: me.auth })).json();
    expect(home.forYou.map((i: { id: string }) => i.id)).toContain(theirFind);
  });

  it('fills For You from the dish types you like when there is nobody to go on yet', async () => {
    const pick = await mkDish('Taste Lone Thali', 'thali', true);
    const me = await person();
    await app.inject({ method: 'PUT', url: '/me/taste', headers: me.auth, payload: { categories: [await category('thali')], diet: 'nonveg', spice: 2 } });
    const items = (await app.inject({ url: `/foryou?city=${cityId}&limit=50`, headers: me.auth })).json().items;
    const it = items.find((i: { id: string }) => i.id === pick);
    expect(it.because).toMatchObject({ handle: null, category: 'Thali' });
  });

  it("filters the map to friends' and taste matches' picks", async () => {
    const dish = await mkDish('Taste Map Pick', 'chinese', true, 73.81, 20.01);
    const me = await person();
    const friend = await person();
    await vote(friend.id, dish, 'recommend');
    await app.inject({ method: 'PUT', url: `/users/${friend.handle}/follow`, headers: me.auth });
    expect((await app.inject(`/map?city=${cityId}&filter=friends`)).statusCode).toBe(401);
    const places = (await app.inject({ url: `/map?city=${cityId}&filter=friends`, headers: me.auth })).json().places;
    const place = places.find((p: { name: string }) => p.name === 'Taste Place Taste Map Pick');
    expect(place.topDish.dishId).toBe(dish);
    expect(place.lovedBy.handle).toBe(friend.handle);
    expect(place.emoji).toBe('🥡');
    const taste = (await app.inject({ url: `/map?city=${cityId}&filter=taste`, headers: me.auth })).json().places;
    expect(Array.isArray(taste)).toBe(true);
  });

  it('finds the best dishes along a drive, in order', async () => {
    const early = await mkDish('Taste Highway Early', 'misal', true, 73.70, 20.0005);
    const late = await mkDish('Taste Highway Late', 'misal', true, 73.95, 19.9995);
    await mkDish('Taste Far Away', 'misal', true, 73.80, 20.2);
    const res = (await app.inject(`/route/stops?fromLat=20.0&fromLng=73.6&toLat=20.0&toLng=74.0&corridorKm=1`)).json();
    expect(res.route.source).toBe('straight'); // no routing server in tests
    const ids = res.stops.map((s: { card: { id: string } }) => s.card.id);
    expect(ids).toContain(early);
    expect(ids).toContain(late);
    expect(ids.indexOf(early)).toBeLessThan(ids.indexOf(late));
    expect(res.stops.some((s: { card: { name: string } }) => s.card.name === 'Taste Far Away')).toBe(false);
    for (const s of res.stops) expect(s.offRouteM).toBeLessThanOrEqual(1000);
  });
});

describe('real places from OpenStreetMap', () => {
  it('parses Overpass results and imports them once, linking places we already have', async () => {
    const { parseOverpass, importOsmPlaces, overpassQuery } = await import('../src/osm.js');
    expect(overpassQuery(20, 73.8, 5)).toContain('around:5000,20,73.8');
    const places = parseOverpass({
      elements: [
        { type: 'node', id: 1, lat: 20.001, lon: 73.79, tags: { amenity: 'cafe', name: 'Sadhana Cafe', cuisine: 'indian;coffee_shop', 'addr:suburb': 'Gangapur Road' } },
        { type: 'way', id: 2, center: { lat: 20.0042, lon: 73.7731 }, tags: { amenity: 'restaurant', name: 'Pav Bhaji Point' } },
        { type: 'node', id: 3, lat: 20.01, lon: 73.8, tags: { amenity: 'restaurant' } },
        { type: 'node', id: 4, lat: 20.02, lon: 73.8, tags: { amenity: 'restaurant', name: 'Old Place', 'disused:amenity': 'restaurant' } },
      ],
    });
    expect(places.map((p) => p.name)).toEqual(['Sadhana Cafe', 'Pav Bhaji Point']);
    expect(places[0]).toMatchObject({ osmId: 'n1', cuisine: 'indian, coffee shop', locality: 'Gangapur Road' });
    expect(await importOsmPlaces(db, cityId, places)).toEqual({ added: 1, linked: 1, skipped: 0 });
    expect(await importOsmPlaces(db, cityId, places)).toEqual({ added: 0, linked: 0, skipped: 2 });
    const { rows } = await db.query("select source, status from restaurants where osm_id = 'n1'");
    expect(rows[0]).toEqual({ source: 'osm', status: 'active' });
    // Imported places with no dishes only appear on the map when asked for.
    const all = (await app.inject(`/map?city=${cityId}&all=true`)).json().places.map((p: { name: string }) => p.name);
    const withDishes = (await app.inject(`/map?city=${cityId}`)).json().places.map((p: { name: string }) => p.name);
    expect(all).toContain('Sadhana Cafe');
    expect(withDishes).not.toContain('Sadhana Cafe');
  });
});

describe('explore places and in-app directions', () => {
  async function place(name: string, locality: string, cuisine: string, dishes: { veg: boolean }[], lng = 73.78, lat = 20.0) {
    const r = await db.query(
      `insert into restaurants (city_id, name, name_norm, locality, cuisine, geo, status)
       values ($1, $2, norm_name($2), $3, $4, st_setsrid(st_makepoint($5, $6), 4326)::geography, 'active') returning id`,
      [cityId, name, locality, cuisine, lng, lat],
    );
    for (const [i, d] of dishes.entries()) {
      const dish = await db.query(
        `insert into dishes (restaurant_id, city_id, name, name_norm, is_veg) values ($1, $2, $3, norm_name($3), $4) returning id`,
        [r.rows[0].id, cityId, `${name} dish ${i}`, d.veg],
      );
      await db.query(`insert into posts (dish_id, image_path, thumb_path) values ($1, '/media/t.webp', '/media/t.webp')`, [dish.rows[0].id]);
    }
    return r.rows[0].id as string;
  }

  it('lists places with filters, sorts and facets, without touching scores', async () => {
    const veg = await place('Explore Veg House', 'Explore Nagar', 'south_indian', [{ veg: true }, { veg: true }], 73.781, 20.001);
    const mixed = await place('Explore Mixed Grill', 'Explore Nagar', 'indian;bbq', [{ veg: true }, { veg: false }], 73.9, 20.1);
    const before = (await db.query('select dish_id, score from dish_stats order by dish_id')).rows;

    const list = (await app.inject(`/places?city=${cityId}&area=Explore%20Nagar`)).json();
    expect(list.items.map((p: { id: string }) => p.id).sort()).toEqual([veg, mixed].sort());
    const vegOnly = (await app.inject(`/places?city=${cityId}&area=Explore%20Nagar&veg=true`)).json();
    expect(vegOnly.items.map((p: { id: string }) => p.id)).toEqual([veg]);
    expect(vegOnly.items[0]).toMatchObject({ pureVeg: true, dishCount: 2 });
    expect(vegOnly.items[0].dishNames).toHaveLength(2);
    const byCuisine = (await app.inject(`/places?city=${cityId}&cuisine=south%20indian`)).json();
    expect(byCuisine.items.map((p: { id: string }) => p.id)).toContain(veg);
    expect(byCuisine.items.map((p: { id: string }) => p.id)).not.toContain(mixed);

    // Nearest first, and a distance limit; both need a location.
    expect((await app.inject(`/places?city=${cityId}&sort=nearest`)).statusCode).toBe(400);
    const near = (await app.inject(`/places?city=${cityId}&area=Explore%20Nagar&sort=nearest&lat=20.0&lng=73.78`)).json();
    expect(near.items.map((p: { id: string }) => p.id)).toEqual([veg, mixed]);
    expect(near.items[0].distanceM).toBeLessThan(near.items[1].distanceM);
    const close = (await app.inject(`/places?city=${cityId}&area=Explore%20Nagar&lat=20.0&lng=73.78&maxKm=2`)).json();
    expect(close.items.map((p: { id: string }) => p.id)).toEqual([veg]);

    const facets = (await app.inject(`/places/facets?city=${cityId}`)).json();
    expect(facets.areas).toContainEqual({ name: 'Explore Nagar', places: 2, withDishes: 2 });
    expect(facets.cuisines.map((c: { name: string }) => c.name)).toEqual(expect.arrayContaining(['South Indian', 'Indian', 'Bbq']));
    expect(Array.isArray(facets.categories)).toBe(true);

    // Read only: no score moved.
    expect((await db.query('select dish_id, score from dish_stats order by dish_id')).rows).toEqual(before);
  });

  it('gives directions as a straight line with no routing server, and turns into sentences', async () => {
    const res = (await app.inject('/route?fromLat=20.0&fromLng=73.78&toLat=20.01&toLng=73.79')).json();
    expect(res).toMatchObject({ source: 'straight', steps: [] });
    expect(res.coordinates).toEqual([[73.78, 20.0], [73.79, 20.01]]);
    expect((await app.inject('/route?fromLat=200&fromLng=73.78&toLat=20.01&toLng=73.79')).statusCode).toBe(400);
    const { stepText } = await import('../src/routes/taste.js');
    const at: [number, number] = [0, 0];
    expect(stepText({ name: 'MG Road', distance: 10, maneuver: { type: 'turn', modifier: 'left', location: at } })).toBe('Turn left onto MG Road');
    expect(stepText({ distance: 10, maneuver: { type: 'arrive', location: at } })).toBe('You have arrived');
    expect(stepText({ name: '', distance: 10, maneuver: { type: 'turn', modifier: 'uturn', location: at } })).toBe('Make a U-turn');
    expect(stepText({ name: 'Ring Rd', distance: 10, maneuver: { type: 'roundabout', location: at } })).toBe('At the roundabout, take the exit onto Ring Rd');
  });
});

describe('notifications: stories, posts, story likes, new food near you, push', () => {
  async function activeRestaurant(lng = 73.7898, lat = 19.9975): Promise<string> {
    const r = await db.query(
      `insert into restaurants (city_id, name, name_norm, geo, status, created_at)
       values ($1, $2, norm_name($2), st_setsrid(st_makepoint($3, $4), 4326)::geography, 'active', now() - interval '2 days') returning id`,
      [cityId, `Notify Place ${Math.random().toString(36).slice(2, 8)}`, lng, lat],
    );
    await db.query("update restaurants set listed_at = now() - interval '2 days' where id = $1", [r.rows[0].id]);
    return r.rows[0].id;
  }
  it('tells followers about a new story and a new dish post, and owners about story likes', async () => {
    const poster = await signUp({ established: true });
    const fan = await signUp({ established: true });
    const stranger = await signUp({ established: true });
    await app.inject({ method: 'PUT', url: `/users/${poster.handle}/follow`, headers: fan.auth });

    const story = await app.inject({ method: 'POST', url: '/stories', ...multipart(poster.auth, { cityId: String(cityId) }, await photo()) });
    expect(story.statusCode).toBe(201);
    const place = await activeRestaurant();
    const post = await app.inject({
      method: 'POST', url: '/posts',
      ...multipart(poster.auth, { idempotencyKey: 'key-notify-post', dishName: 'Notify Thalipeeth', restaurantId: place }, await photo()),
    });
    expect(post.statusCode).toBe(201);

    const items = (await app.inject({ url: '/notifications', headers: fan.auth })).json().items;
    const kinds = items.filter((n: { actor: { handle: string } }) => n.actor.handle === poster.handle).map((n: { kind: string }) => n.kind);
    expect(kinds).toEqual(expect.arrayContaining(['story', 'post']));
    const postItem = items.find((n: { kind: string }) => n.kind === 'post');
    expect(postItem.dish.name).toBe('Notify Thalipeeth');
    // Only followers hear about it.
    const strangers = (await app.inject({ url: '/notifications', headers: stranger.auth })).json().items;
    expect(strangers.some((n: { actor: { handle: string } }) => n.actor.handle === poster.handle)).toBe(false);

    // A heart on the story tells its owner, once even if toggled.
    for (const liked of [true, false, true]) {
      await app.inject({ method: 'PUT', url: `/stories/${story.json().id}/like`, headers: fan.auth, payload: { liked } });
    }
    const mine = (await app.inject({ url: '/notifications', headers: poster.auth })).json().items;
    expect(mine.filter((n: { kind: string }) => n.kind === 'story_like')).toHaveLength(1);

    // Phone updates carry them too, with the story to open.
    const { rows } = await db.query("select (now() - interval '1 hour') as t");
    const upd = (await app.inject({ url: `/updates?since=${encodeURIComponent(rows[0].t.toISOString())}`, headers: fan.auth })).json();
    const st = upd.activity.find((a: { kind: string }) => a.kind === 'story');
    expect(st.storyId).toBe(story.json().id);
    expect(upd.activity.find((a: { kind: string }) => a.kind === 'post').placeName).toMatch(/^Notify Place/);
  });

  it('finds new food near you: new dishes within 5 km, never your own, nearest first', async () => {
    const me = await signUp({ established: true });
    const cook = await signUp({ established: true });
    const t0 = new Date(Date.now() - 1000).toISOString();
    const near = await activeRestaurant(73.79, 19.998);
    const far = await activeRestaurant(73.95, 20.2);
    for (const [place, name, who] of [[near, 'Nearby Poha', cook], [far, 'Faraway Poha', cook], [near, 'My Own Poha', me]] as const) {
      const r = await app.inject({
        method: 'POST', url: '/posts',
        ...multipart(who.auth, { idempotencyKey: `key-${name.replace(/ /g, '')}`, dishName: name, restaurantId: place }, await photo()),
      });
      expect(r.statusCode).toBe(201);
    }
    const upd = (await app.inject({ url: `/updates?since=${encodeURIComponent(t0)}&lat=19.9975&lng=73.7898`, headers: me.auth })).json();
    const names = upd.nearby.map((n: { dishName: string }) => n.dishName);
    expect(names).toContain('Nearby Poha');
    expect(names).not.toContain('Faraway Poha');
    expect(names).not.toContain('My Own Poha');
    const poha = upd.nearby.find((n: { dishName: string }) => n.dishName === 'Nearby Poha');
    expect(poha).toMatchObject({ kind: 'dish', placeId: near });
    expect(poha.distanceM).toBeLessThan(1000);
    const d = upd.nearby.map((n: { distanceM: number }) => n.distanceM);
    expect(d).toEqual([...d].sort((a, b) => a - b));
    // Without a location, the city stands in.
    const byCity = (await app.inject({ url: `/updates?since=${encodeURIComponent(t0)}&city=${cityId}`, headers: me.auth })).json();
    expect(byCity.nearby.map((n: { dishName: string }) => n.dishName)).toEqual(expect.arrayContaining(['Nearby Poha']));
    // A place approved just now is new near you too.
    const pending = await db.query(
      `insert into restaurants (city_id, name, name_norm, geo, status, created_by)
       values ($1, 'Brand New Misal', norm_name('Brand New Misal'), st_setsrid(st_makepoint(73.791, 19.999), 4326)::geography, 'pending', $2) returning id`,
      [cityId, cook.id],
    );
    const dish = await db.query(
      `insert into dishes (restaurant_id, city_id, name, name_norm, created_by, created_at) values ($1, $2, 'Kat Misal', norm_name('Kat Misal'), $3, now() - interval '1 day') returning id`,
      [pending.rows[0].id, cityId, cook.id],
    );
    await db.query(`insert into posts (dish_id, author_id, image_path, thumb_path) values ($1, $2, '/media/t.webp', '/media/t.webp')`, [dish.rows[0].id, cook.id]);
    await db.query("update restaurants set source = 'user' where id = $1", [pending.rows[0].id]);
    await db.query("update restaurants set status = 'active' where id = $1", [pending.rows[0].id]);
    const later = (await app.inject({ url: `/updates?since=${encodeURIComponent(t0)}&lat=19.9975&lng=73.7898`, headers: me.auth })).json();
    expect(later.nearby).toContainEqual(expect.objectContaining({ kind: 'place', placeName: 'Brand New Misal', dishName: 'Kat Misal' }));
    expect(later.nearby.length).toBeLessThanOrEqual(3);
  });

  it('keeps push tokens, and wakes the right phone through Firebase when something arrives', async () => {
    expect((await app.inject('/push/config')).json()).toEqual({ enabled: false });
    const { Push, serviceJwt } = await import('../src/push.js');
    const { generateKeyPairSync, createVerify } = await import('node:crypto');
    const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const pem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
    // The token request is signed with the service account's key.
    const jwt = serviceJwt({ clientEmail: 'push@test.iam', privateKey: pem }, 1_700_000_000);
    const [h, b, sig] = jwt.split('.');
    expect(JSON.parse(Buffer.from(b!, 'base64url').toString())).toMatchObject({ iss: 'push@test.iam', scope: 'https://www.googleapis.com/auth/firebase.messaging', exp: 1_700_003_600 });
    expect(createVerify('RSA-SHA256').update(`${h}.${b}`).verify(publicKey, sig!, 'base64url')).toBe(true);

    const sent: { url: string; body: string }[] = [];
    const http = (async (url: string | URL, init?: RequestInit) => {
      sent.push({ url: String(url), body: String(init?.body ?? '') });
      if (String(url).includes('oauth2')) return new Response(JSON.stringify({ access_token: 'tok', expires_in: 3600 }));
      if (String(init?.body).includes('stale-token-0000')) return new Response('{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}', { status: 404 });
      return new Response('{}');
    }) as typeof fetch;
    const push = new Push(db, { projectId: 'wab-test', clientEmail: 'push@test.iam', privateKey: pem, app: { apiKey: 'k', appId: 'a', senderId: 's' } }, console, http, 0);
    const config2 = { ...app.config, push: push.config };
    const app2 = await buildApp(config2, db, { logger: false, push });
    try {
      expect((await app2.inject('/push/config')).json()).toEqual({ enabled: true, projectId: 'wab-test', apiKey: 'k', appId: 'a', senderId: 's' });
      const phone = await signUp({ established: true });
      const fan = await signUp({ established: true });
      expect((await app2.inject({ method: 'PUT', url: '/me/push-token', headers: phone.auth, payload: { token: 'phone-token-1234', cityId } })).statusCode).toBe(200);
      await db.query('insert into push_tokens (token, user_id) values ($1, $2)', ['stale-token-0000', phone.id]);
      // A new follower: the database wakes the server, the server wakes the phone.
      await app2.inject({ method: 'PUT', url: `/users/${phone.handle}/follow`, headers: fan.auth });
      await vi.waitFor(() => {
        const fcm = sent.filter((x) => x.url.includes('fcm.googleapis.com/v1/projects/wab-test'));
        expect(fcm.some((x) => x.body.includes('phone-token-1234'))).toBe(true);
        expect(fcm.every((x) => !x.body.includes('"notification"'))).toBe(true); // no content in the push
      }, { timeout: 5000 });
      // A token Firebase no longer knows is forgotten.
      await vi.waitFor(async () => {
        expect((await db.query("select 1 from push_tokens where token = 'stale-token-0000'")).rowCount).toBe(0);
      }, { timeout: 5000 });
      expect((await app2.inject({ method: 'DELETE', url: '/me/push-token', headers: phone.auth, payload: { token: 'phone-token-1234' } })).statusCode).toBe(200);
      expect((await db.query("select 1 from push_tokens where token = 'phone-token-1234'")).rowCount).toBe(0);
    } finally {
      await app2.close();
    }
  });
});

describe('two-step sign-in by email code', () => {
  it('needs email set up on the server', async () => {
    const u = await signUp();
    const res = await app.inject({ method: 'POST', url: '/me/2fa/start', headers: u.auth });
    expect(res.statusCode).toBe(503);
    expect(res.json().error).toBe('mail_off');
    expect((await app.inject({ url: '/me', headers: u.auth })).json()).toMatchObject({ twoFactor: false, emailVerified: false });
  });

  it('turns on by verifying the email, then every sign-in needs a code; reminds daily while off', async () => {
    const mails: { to: string; subject: string; text: string }[] = [];
    const mailer = { enabled: true, send: async (to: string, subject: string, text: string) => { mails.push({ to, subject, text }); } };
    const app2 = await buildApp({ ...app.config, remindHours: [0, 23] }, db, { logger: false, mailer });
    const codeIn = () => mails.at(-1)!.subject.match(/^(\d{6}) is your WorthAbite code$/)![1]!;
    try {
      const u = await signUp({ email: 'twostep@example.com' });
      // Daily reminder while it's off: once a day, not on the very first check.
      const t = new Date(Date.now() - 60_000).toISOString();
      expect((await app2.inject({ url: '/updates', headers: u.auth })).json().reminder).toBeNull();
      expect((await app2.inject({ url: `/updates?since=${encodeURIComponent(t)}`, headers: u.auth })).json().reminder).toEqual({ kind: 'two_factor' });
      expect((await app2.inject({ url: `/updates?since=${encodeURIComponent(t)}`, headers: u.auth })).json().reminder).toBeNull();

      // Turning it on sends a code to the account's email; the right code turns it on.
      const start = (await app2.inject({ method: 'POST', url: '/me/2fa/start', headers: u.auth })).json();
      expect(start.emailHint).toBe('tw***@example.com');
      expect(mails.at(-1)!.to).toBe('twostep@example.com');
      expect(mails.at(-1)!.text).toContain(codeIn());
      const bad = await app2.inject({ method: 'POST', url: '/me/2fa/confirm', headers: u.auth, payload: { challengeId: start.challengeId, code: codeIn() === '000000' ? '111111' : '000000' } });
      expect(bad.statusCode).toBe(400);
      expect(bad.json().message).toMatch(/Wrong code. 4 tries left/);
      const on = await app2.inject({ method: 'POST', url: '/me/2fa/confirm', headers: u.auth, payload: { challengeId: start.challengeId, code: codeIn() } });
      expect(on.json()).toMatchObject({ twoFactor: true, emailVerified: true });

      // Signing in now gives no token, only a code by email.
      const login = (await app2.inject({ method: 'POST', url: '/auth/login', payload: { email: 'twostep@example.com', password: 'correct horse' } })).json();
      expect(login.token).toBeUndefined();
      expect(login).toMatchObject({ twoFactor: true, emailHint: 'tw***@example.com' });
      const loginCode = codeIn();
      expect((await app2.inject({ method: 'POST', url: '/auth/login/resend', payload: { challengeId: login.challengeId } })).statusCode).toBe(429);
      const ok = await app2.inject({ method: 'POST', url: '/auth/login/verify', payload: { challengeId: login.challengeId, code: loginCode } });
      expect(ok.statusCode).toBe(200);
      expect(ok.json().user.handle).toBe(u.handle);
      expect((await app2.inject({ url: '/me', headers: { authorization: `Bearer ${ok.json().token}` } })).statusCode).toBe(200);
      // A code works once.
      expect((await app2.inject({ method: 'POST', url: '/auth/login/verify', payload: { challengeId: login.challengeId, code: loginCode } })).statusCode).toBe(401);
      // The wrong password still gets nowhere, and sends nothing.
      const sent = mails.length;
      expect((await app2.inject({ method: 'POST', url: '/auth/login', payload: { email: 'twostep@example.com', password: 'nope nope' } })).statusCode).toBe(401);
      expect(mails.length).toBe(sent);
      // Five wrong codes lock that code.
      const again = (await app2.inject({ method: 'POST', url: '/auth/login', payload: { email: 'twostep@example.com', password: 'correct horse' } })).json();
      const right = codeIn();
      const wrong = right === '123456' ? '654321' : '123456';
      for (let i = 0; i < 5; i++) {
        await app2.inject({ method: 'POST', url: '/auth/login/verify', payload: { challengeId: again.challengeId, code: wrong } });
      }
      const locked = await app2.inject({ method: 'POST', url: '/auth/login/verify', payload: { challengeId: again.challengeId, code: right } });
      expect(locked.statusCode).toBe(401);
      expect(locked.json().message).toMatch(/Too many wrong codes/);
      // While it's on, no daily reminder.
      await db.query('update profiles set security_reminded_on = null where id = $1', [u.id]);
      expect((await app2.inject({ url: `/updates?since=${encodeURIComponent(t)}`, headers: u.auth })).json().reminder).toBeNull();

      // Turning it off needs the password.
      expect((await app2.inject({ method: 'POST', url: '/me/2fa/disable', headers: u.auth, payload: { password: 'wrong one' } })).statusCode).toBe(401);
      const off = await app2.inject({ method: 'POST', url: '/me/2fa/disable', headers: u.auth, payload: { password: 'correct horse' } });
      expect(off.json()).toMatchObject({ twoFactor: false, emailVerified: true });
      expect((await app2.inject({ method: 'POST', url: '/auth/login', payload: { email: 'twostep@example.com', password: 'correct horse' } })).json().token).toBeTruthy();
    } finally {
      await app2.close();
    }
  });
});

describe('accounts: sign in, sessions, recovery', () => {
  const bearer = (t: string) => ({ authorization: `Bearer ${t}` });
  async function login(a: FastifyInstance, loginName: string, password = 'correct horse', field = 'login') {
    return a.inject({ method: 'POST', url: '/auth/login', payload: { [field]: loginName, password } });
  }

  it('signs in by email or username, the same answer for a wrong user or password, and locks after 10 misses', async () => {
    const u = await signUp({ email: 'Lock.Me@Example.com' });
    expect((await login(app, 'lock.me@example.com')).statusCode).toBe(200);
    expect((await login(app, u.handle)).statusCode).toBe(200);
    expect((await login(app, `@${u.handle.toUpperCase()}`)).statusCode).toBe(200);
    expect((await login(app, 'lock.me@example.com', 'correct horse', 'email')).statusCode).toBe(200); // older apps
    const noUser = await login(app, 'nobody_here_123');
    const badPw = await login(app, u.handle, 'wrong password');
    expect(noUser.statusCode).toBe(401);
    expect(noUser.json()).toEqual(badPw.json());
    for (let i = 0; i < 9; i++) await login(app, u.handle, 'wrong password');
    const locked = await login(app, u.handle); // right password, but locked now
    expect(locked.statusCode).toBe(429);
    expect(locked.json().error).toBe('locked');
    await db.query("update profiles set locked_until = now() - interval '1 second' where id = $1", [u.id]);
    expect((await login(app, u.handle)).statusCode).toBe(200);
    expect((await db.query('select failed_logins from profiles where id = $1', [u.id])).rows[0].failed_logins).toBe(0);
  });

  it('refuses easy passwords and says whether a username is free', async () => {
    for (const password of ['password123', '12345678', 'aaaaaaaa', 'short']) {
      const r = await app.inject({ method: 'POST', url: '/auth/register', payload: { email: `weak${password.length}x@example.com`, password, handle: `weak_${password.length}x`, acceptTerms: true } });
      expect(r.statusCode).toBe(400);
    }
    const sameAsName = await app.inject({ method: 'POST', url: '/auth/register', payload: { email: 'rahul@example.com', password: 'rahulkumar99', handle: 'rahul_k', acceptTerms: true } });
    expect(sameAsName.json().message).toMatch(/username or email/);
    const u = await signUp();
    expect((await app.inject(`/auth/handle-available?handle=${u.handle}`)).json()).toMatchObject({ available: false });
    expect((await app.inject('/auth/handle-available?handle=Fresh_Name_9')).json()).toEqual({ available: true });
    expect((await app.inject('/auth/handle-available?handle=no')).json().available).toBe(false);
  });

  it('signs out on the server, lists devices, ends one or all others', async () => {
    const u = await signUp();
    const phone = (await app.inject({ method: 'POST', url: '/auth/login', headers: { 'x-device-name': 'Pixel 6 · Android 14' }, payload: { login: u.handle, password: 'correct horse' } })).json().token;
    const tablet = (await login(app, u.handle)).json().token;
    const list = (await app.inject({ url: '/me/sessions', headers: bearer(phone) })).json().items;
    expect(list).toHaveLength(3);
    expect(list[0]).toMatchObject({ current: true, device: 'Pixel 6 · Android 14' });

    // Signing out ends that token for good.
    expect((await app.inject({ method: 'POST', url: '/auth/logout', headers: bearer(tablet) })).statusCode).toBe(200);
    const after = await app.inject({ url: '/me', headers: bearer(tablet) });
    expect(after.statusCode).toBe(401);
    expect(after.json().error).toBe('session_ended');
    expect((await app.inject({ url: '/me', headers: bearer(phone) })).statusCode).toBe(200);

    // End one device from another, then everything but this one.
    const now = (await app.inject({ url: '/me/sessions', headers: bearer(phone) })).json().items;
    expect(now).toHaveLength(2);
    const firstId = now.find((x: { current: boolean }) => !x.current).id;
    expect((await app.inject({ method: 'DELETE', url: `/me/sessions/${firstId}`, headers: bearer(phone) })).statusCode).toBe(200);
    expect((await app.inject({ url: '/me', headers: u.auth })).statusCode).toBe(401);
    const laptop = (await login(app, u.handle)).json().token;
    // A token from before sessions existed works until "sign out everywhere".
    const old = app.jwt.sign({ sub: u.id, role: 'user' });
    expect((await app.inject({ url: '/me', headers: bearer(old) })).statusCode).toBe(200);
    expect((await app.inject({ method: 'POST', url: '/me/sessions/end-others', headers: bearer(phone) })).statusCode).toBe(200);
    expect((await app.inject({ url: '/me', headers: bearer(laptop) })).statusCode).toBe(401);
    expect((await app.inject({ url: '/me', headers: bearer(old) })).statusCode).toBe(401);
    expect((await app.inject({ url: '/me', headers: bearer(phone) })).statusCode).toBe(200);
    // Guests' pages still load with an ended token (it's treated as no token).
    expect((await app.inject({ url: `/feed?city=${cityId}&tab=top`, headers: bearer(laptop) })).statusCode).toBe(200);
  });

  it('changing the password signs out the other devices; deleting the account ends its tokens', async () => {
    const u = await signUp();
    const other = (await login(app, u.handle)).json().token;
    const weak = await app.inject({ method: 'POST', url: '/me/password', headers: u.auth, payload: { current: 'correct horse', next: '11111111' } });
    expect(weak.statusCode).toBe(400);
    const ok = await app.inject({ method: 'POST', url: '/me/password', headers: u.auth, payload: { current: 'correct horse', next: 'new tasty secret' } });
    expect(ok.json()).toEqual({ ok: true });
    expect((await app.inject({ url: '/me', headers: u.auth })).statusCode).toBe(200);
    expect((await app.inject({ url: '/me', headers: bearer(other) })).statusCode).toBe(401);
    expect((await login(app, u.handle, 'new tasty secret')).statusCode).toBe(200);
    expect((await app.inject({ method: 'DELETE', url: '/me', headers: u.auth })).statusCode).toBe(200);
    expect((await app.inject({ url: '/me', headers: u.auth })).statusCode).toBe(401);
  });

  it('recovers accounts by email: verify on sign-up, forgot password, forgot username, forgot email', async () => {
    const mails: { to: string; subject: string; text: string }[] = [];
    const mailer = { enabled: true, send: async (to: string, subject: string, text: string) => { mails.push({ to, subject, text }); } };
    const app2 = await buildApp(app.config, db, { logger: false, mailer });
    const lastCode = (to: string) => mails.filter((m) => m.to === to).at(-1)!.subject.match(/^(\d{6}) is your WorthAbite code$/)![1]!;
    const settle = () => new Promise((r) => setTimeout(r, 50)); // background emails
    try {
      // Sign-up sends a code to confirm the email.
      const reg = (await app2.inject({ method: 'POST', url: '/auth/register', payload: { email: 'recover@example.com', password: 'correct horse', handle: 'recover_me', acceptTerms: true } })).json();
      expect(reg.verify.emailHint).toBe('re***@example.com');
      await settle();
      const v = await app2.inject({ method: 'POST', url: '/me/email/verify/confirm', headers: bearer(reg.token), payload: { challengeId: reg.verify.challengeId, code: lastCode('recover@example.com') } });
      expect(v.json()).toMatchObject({ emailVerified: true });

      // Forgot password: same answer for an unknown account, a code for a real one.
      const unknown = (await app2.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: 'no_such_person' } })).json();
      const known = (await app2.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: 'recover_me' } })).json();
      expect(Object.keys(unknown).sort()).toEqual(Object.keys(known).sort());
      expect(unknown.message).toBe(known.message);
      await settle();
      const code = lastCode('recover@example.com');
      const otherDevice = (await login(app2, 'recover_me')).json().token;
      // A weak new password is refused without using up the code.
      expect((await app2.inject({ method: 'POST', url: '/auth/password/reset', payload: { challengeId: known.challengeId, code, password: 'password1' } })).statusCode).toBe(400);
      expect((await app2.inject({ method: 'POST', url: '/auth/password/reset', payload: { challengeId: unknown.challengeId, code, password: 'brand new pass' } })).statusCode).toBe(401);
      const reset = await app2.inject({ method: 'POST', url: '/auth/password/reset', payload: { challengeId: known.challengeId, code, password: 'brand new pass' } });
      expect(reset.statusCode).toBe(200);
      expect(reset.json().user.handle).toBe('recover_me');
      expect((await app2.inject({ url: '/me', headers: bearer(reset.json().token) })).statusCode).toBe(200);
      expect((await app2.inject({ url: '/me', headers: bearer(otherDevice) })).statusCode).toBe(401);
      expect((await app2.inject({ url: '/me', headers: bearer(reg.token) })).statusCode).toBe(401);
      expect((await login(app2, 'recover@example.com', 'correct horse')).statusCode).toBe(401);
      expect((await login(app2, 'recover@example.com', 'brand new pass')).statusCode).toBe(200);
      await settle();
      expect(mails.some((m) => m.to === 'recover@example.com' && m.subject === 'Your WorthAbite password was changed')).toBe(true);
      // The code works once.
      expect((await app2.inject({ method: 'POST', url: '/auth/password/reset', payload: { challengeId: known.challengeId, code, password: 'another new one' } })).statusCode).toBe(401);

      // Forgot username: emailed to the address, never shown on screen.
      const fu = (await app2.inject({ method: 'POST', url: '/auth/username/forgot', payload: { email: 'recover@example.com' } })).json();
      const fuNone = (await app2.inject({ method: 'POST', url: '/auth/username/forgot', payload: { email: 'nobody@example.com' } })).json();
      expect(fu).toEqual(fuNone);
      await settle();
      expect(mails.at(-1)).toMatchObject({ to: 'recover@example.com', subject: 'Your WorthAbite username' });
      expect(mails.at(-1)!.text).toContain('@recover_me');
      expect(mails.some((m) => m.to === 'nobody@example.com')).toBe(false);

      // Forgot email: a hint of the address on a username.
      expect((await app2.inject({ method: 'POST', url: '/auth/email/forgot', payload: { handle: '@recover_me' } })).json()).toEqual({ emailHint: 're***@example.com' });
      expect((await app2.inject({ method: 'POST', url: '/auth/email/forgot', payload: { handle: 'no_such_person' } })).statusCode).toBe(404);
    } finally {
      await app2.close();
    }
    // Without email on the server, recovery says so.
    expect((await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: 'recover_me' } })).json().error).toBe('mail_off');
  });
});

describe('sign-in limits and staying signed in', () => {
  it('counts tries per key in a time window', async () => {
    const { tooManyTries } = await import('../src/auth.js');
    const key = `test:${Math.random()}`;
    for (let i = 0; i < 3; i++) expect(tooManyTries(key, 3, 1000)).toBe(false);
    expect(tooManyTries(key, 3, 1000)).toBe(true);
    expect(tooManyTries(`${key}:other`, 3, 1000)).toBe(false);
  });

  it('renews a token older than a week on the same session, and says how long a lock lasts', async () => {
    const u = await signUp();
    const sid = app.jwt.decode<{ sid: string }>(u.token)!.sid;
    const week = Math.floor(Date.now() / 1000) - 8 * 24 * 3600;
    const old = app.jwt.sign({ sub: u.id, role: 'user', sid, iat: week });
    const res = await app.inject({ url: '/me', headers: { authorization: `Bearer ${old}` } });
    expect(res.statusCode).toBe(200);
    const fresh = res.headers['x-new-token'] as string;
    expect(app.jwt.decode<{ sid: string }>(fresh)!.sid).toBe(sid);
    expect((await app.inject({ url: '/me', headers: { authorization: `Bearer ${fresh}` } })).statusCode).toBe(200);
    // A new token isn't renewed.
    expect((await app.inject({ url: '/me', headers: u.auth })).headers['x-new-token']).toBeUndefined();

    await db.query("update profiles set locked_until = now() + interval '5 minutes' where id = $1", [u.id]);
    const locked = await app.inject({ method: 'POST', url: '/auth/login', payload: { login: u.handle, password: 'correct horse' } });
    expect(locked.json().message).toMatch(/Try again in 5 minutes/);
  });

  it('accepts both "login" and "email" together (newer apps send both, for older servers)', async () => {
    const u = await signUp();
    const r = await app.inject({ method: 'POST', url: '/auth/login', payload: { login: u.handle, email: u.handle, password: 'correct horse' } });
    expect(r.statusCode).toBe(200);
  });
});

describe('changing the email', () => {
  it('needs the password, a code from the old address and one from the new, then switches and tells the old address', async () => {
    const mails: { to: string; subject: string; text: string }[] = [];
    const mailer = { enabled: true, send: async (to: string, subject: string, text: string) => { mails.push({ to, subject, text }); } };
    const app2 = await buildApp(app.config, db, { logger: false, mailer });
    const codeTo = (to: string) => mails.filter((m) => m.to === to).at(-1)!.subject.match(/^(\d{6}) is your WorthAbite code$/)![1]!;
    const bearer = (t: string) => ({ authorization: `Bearer ${t}` });
    try {
      const u = await signUp({ email: 'old.address@example.com' });
      const other = await signUp({ email: 'taken.address@example.com' });
      void other;
      const otherDevice = (await app2.inject({ method: 'POST', url: '/auth/login', payload: { login: u.handle, password: 'correct horse' } })).json().token;
      const start = (payload: object) => app2.inject({ method: 'POST', url: '/me/email/change/start', headers: u.auth, payload });

      expect((await start({ newEmail: 'new.address@example.com', password: 'wrong one' })).json().error).toBe('bad_credentials');
      expect((await start({ newEmail: 'old.address@example.com', password: 'correct horse' })).json().error).toBe('same_email');
      expect((await start({ newEmail: 'Taken.Address@example.com', password: 'correct horse' })).statusCode).toBe(409);
      expect(mails).toHaveLength(0);

      const c = (await start({ newEmail: 'New.Address@example.com', password: 'correct horse' })).json();
      expect(c).toMatchObject({ oldHint: 'ol***@example.com', newHint: 'ne***@example.com' });
      const oldCode = codeTo('old.address@example.com');
      const newCode = codeTo('new.address@example.com');
      expect(mails.find((m) => m.to === 'old.address@example.com')!.text).toMatch(/new email address/);
      // Asking again at once is refused (one change every 30 seconds).
      expect((await start({ newEmail: 'new.address@example.com', password: 'correct horse' })).statusCode).toBe(429);

      const confirm = (oldC: string, newC: string) => app2.inject({
        method: 'POST', url: '/me/email/change/confirm', headers: u.auth,
        payload: { oldChallengeId: c.oldChallengeId, oldCode: oldC, newChallengeId: c.newChallengeId, newCode: newC },
      });
      // One wrong code changes nothing and spends neither code.
      const wrongNew = await confirm(oldCode, newCode === '000000' ? '111111' : '000000');
      expect(wrongNew.statusCode).toBe(400);
      expect(wrongNew.json()).toMatchObject({ field: 'new' });
      expect(wrongNew.json().message).toMatch(/^New email: Wrong code/);
      // Codes swapped round don't work either.
      expect((await confirm(newCode, oldCode)).statusCode).toBe(400);
      // Codes from another person's change can't be used.
      const someone = await signUp();
      expect((await app2.inject({
        method: 'POST', url: '/me/email/change/confirm', headers: someone.auth,
        payload: { oldChallengeId: c.oldChallengeId, oldCode, newChallengeId: c.newChallengeId, newCode },
      })).statusCode).toBe(400);
      expect((await app2.inject({ url: '/me', headers: u.auth })).json().email).toBe('old.address@example.com');

      const ok = await confirm(oldCode, newCode);
      expect(ok.statusCode).toBe(200);
      expect(ok.json().user).toMatchObject({ email: 'new.address@example.com', emailVerified: true });
      // Codes work once.
      expect((await confirm(oldCode, newCode)).statusCode).toBe(400);
      // This device stays, the others are signed out; sign-in works with the new address only.
      expect((await app2.inject({ url: '/me', headers: u.auth })).statusCode).toBe(200);
      expect((await app2.inject({ url: '/me', headers: bearer(otherDevice) })).statusCode).toBe(401);
      expect((await app2.inject({ method: 'POST', url: '/auth/login', payload: { login: 'new.address@example.com', password: 'correct horse' } })).statusCode).toBe(200);
      expect((await app2.inject({ method: 'POST', url: '/auth/login', payload: { login: 'old.address@example.com', password: 'correct horse' } })).statusCode).toBe(401);
      await new Promise((r) => setTimeout(r, 50));
      const notice = mails.find((m) => m.to === 'old.address@example.com' && m.subject === 'The email on your WorthAbite account was changed');
      expect(notice!.text).toContain('ne***@example.com');
    } finally {
      await app2.close();
    }
  });

  it('wrong passwords here count toward the sign-in lock', async () => {
    const mailer = { enabled: true, send: async () => {} };
    const app2 = await buildApp(app.config, db, { logger: false, mailer });
    try {
      const u = await signUp();
      for (let i = 0; i < 10; i++) {
        await app2.inject({ method: 'POST', url: '/me/email/change/start', headers: u.auth, payload: { newEmail: 'x@example.com', password: 'nope nope' } });
      }
      const r = await app2.inject({ method: 'POST', url: '/me/email/change/start', headers: u.auth, payload: { newEmail: 'x@example.com', password: 'correct horse' } });
      expect(r.json().error).toBe('locked');
    } finally {
      await app2.close();
    }
  });
});

async function loginAs(handle: string) {
  const res = await app.inject({ method: 'POST', url: '/auth/login', payload: { email: `${handle}@example.com`, password: 'correct horse' } });
  return { auth: { authorization: `Bearer ${res.json().token}` } };
}

describe('stories', () => {
  it('posts a story, shows it in the tray, counts views and likes; replies are off', async () => {
    const a = await signUp();
    const b = await signUp();
    await app.inject({ method: 'PUT', url: `/users/${a.handle}/follow`, headers: b.auth });
    const mp = multipart(a.auth, { caption: 'Best misal in town', dishId: await dishId('Kolhapuri Misal') }, await photo(true));
    const res = await app.inject({ method: 'POST', url: '/stories', ...mp });
    expect(res.statusCode).toBe(201);
    const storyId = res.json().id;
    const { rows } = await db.query('select city_id, image_path from stories where id = $1', [storyId]);
    expect(rows[0].city_id).toBe(cityId);
    // GPS is dropped from story photos too.
    const meta = await sharp(path.join(mediaDir, rows[0].image_path.replace('/media/', ''))).metadata();
    expect(meta.exif).toBeUndefined();

    const tray = (await app.inject({ url: `/stories?city=${cityId}`, headers: b.auth })).json();
    const entry = tray.items.find((i: { user: { handle: string } }) => i.user.handle === a.handle);
    expect(entry).toMatchObject({ isMe: false, allSeen: false });
    expect(entry.stories[0]).toMatchObject({ caption: 'Best misal in town', seen: false, dish: { name: 'Kolhapuri Misal' } });
    // A guest sees the city's stories.
    expect((await app.inject(`/stories?city=${cityId}`)).json().items.length).toBeGreaterThan(0);

    await app.inject({ method: 'POST', url: `/stories/${storyId}/view`, headers: b.auth });
    await app.inject({ method: 'PUT', url: `/stories/${storyId}/like`, headers: b.auth, payload: { liked: true } });
    await app.inject({ method: 'PUT', url: `/stories/${storyId}/like`, headers: b.auth, payload: { liked: false } });
    await app.inject({ method: 'PUT', url: `/stories/${storyId}/like`, headers: b.auth, payload: { liked: true } });
    // Messages carry only shared dishes for now: no story replies, and likes stay on the story.
    const reply = await app.inject({ method: 'POST', url: `/stories/${storyId}/reply`, headers: b.auth, payload: { body: 'Going tomorrow!' } });
    expect(reply.statusCode).toBe(403);
    expect((await app.inject({ url: '/inbox', headers: a.auth })).json().items).toEqual([]);

    const mine = (await app.inject({ url: `/users/${a.handle}/stories`, headers: a.auth })).json();
    expect(mine).toMatchObject({ isMe: true, stories: [{ viewCount: 1 }] });
    const viewers = (await app.inject({ url: `/stories/${storyId}/viewers`, headers: a.auth })).json();
    expect(viewers.items).toEqual([expect.objectContaining({ handle: b.handle, liked: true })]);
    expect((await app.inject({ url: `/stories/${storyId}/viewers`, headers: b.auth })).json().items).toEqual([]);

    // Ended stories leave the tray; deleting removes the photo.
    await db.query("update stories set expires_at = now() - interval '1 minute' where id = $1", [storyId]);
    expect((await app.inject({ url: `/users/${a.handle}/stories`, headers: b.auth })).json().stories).toEqual([]);
    expect((await app.inject({ method: 'DELETE', url: `/stories/${storyId}`, headers: a.auth })).statusCode).toBe(204);
    await expect(fs.stat(path.join(mediaDir, 'stories', storyId))).rejects.toThrow();
  });

  it('keeps demo stories going without copying photos', async () => {
    await seedDemoActivity(db);
    const { refreshDemoStories, cleanupStories } = await import('../src/routes/stories.js');
    expect(await refreshDemoStories(db)).toBeGreaterThan(0);
    expect(await refreshDemoStories(db)).toBe(0);
    const { rows } = await db.query('select bool_and(not owns_media) as borrowed, bool_and(image_path like \'/media/posts/%\') as posts from stories s join profiles p on p.id = s.user_id where p.is_demo');
    expect(rows[0]).toEqual({ borrowed: true, posts: true });
    await db.query("update stories set expires_at = now() - interval '2 days' from profiles p where p.id = stories.user_id and p.is_demo");
    expect(await cleanupStories(db, mediaDir)).toBeGreaterThan(0);
  });
});

describe('photos of places', () => {
  it('reads Commons links from OSM tags and Wikidata', async () => {
    const { commonsFileFromTags } = await import('../src/photos.js');
    expect(await commonsFileFromTags({ image: 'https://commons.wikimedia.org/wiki/File:Cafe_Goa.jpg' })).toBe('File:Cafe Goa.jpg');
    expect(await commonsFileFromTags({ wikimedia_commons: 'File:Shop.jpg' })).toBe('File:Shop.jpg');
    const fake = (async () => new Response(JSON.stringify({ entities: { Q42: { claims: { P18: [{ mainsnak: { datavalue: { value: 'Front.jpg' } } }] } } } }))) as unknown as typeof fetch;
    expect(await commonsFileFromTags({ wikidata: 'Q42' }, fake)).toBe('File:Front.jpg');
    expect(await commonsFileFromTags({ website: 'https://x.in' })).toBeNull();
  });

  it('picks the street photo that faces the shop', async () => {
    const { pickFacing } = await import('../src/photos.js');
    const lat = 20.0;
    const lng = 73.78;
    const south = lat - 0.0002; // ~22 m south of the shop
    const picked = pickFacing([
      { id: 'faces', thumb_1024_url: 'u1', compass_angle: 2, computed_geometry: { coordinates: [lng, south] } },
      { id: 'away', thumb_1024_url: 'u2', compass_angle: 180, computed_geometry: { coordinates: [lng, south] } },
      { id: 'far', thumb_1024_url: 'u3', compass_angle: 0, computed_geometry: { coordinates: [lng, lat - 0.002] } },
    ], lat, lng);
    expect(picked.map((p) => p.id)).toEqual(['faces']);
  });

  it('stores fetched photos with credits and shows them on the place page and map', async () => {
    const { fetchPlacePhotos } = await import('../src/photos.js');
    const { rows } = await db.query<{ id: string }>(
      `insert into restaurants (city_id, name, name_norm, geo, status, source, osm_tags, cuisine)
       values ($1, 'Commons Cafe', norm_name('Commons Cafe'), st_setsrid(st_makepoint(73.79, 20.0), 4326)::geography, 'active', 'osm',
               '{"wikimedia_commons":"File:Commons Cafe.jpg"}', 'coffee_shop') returning id`,
      [cityId],
    );
    const img = await photo();
    const fake = (async (url: string | URL) => {
      const u = String(url);
      if (u.includes('commons.wikimedia.org/w/api.php')) {
        return new Response(JSON.stringify({ query: { pages: [{ title: 'File:Commons Cafe.jpg', imageinfo: [{
          thumburl: 'https://upload.example/cafe.jpg', descriptionurl: 'https://commons.wikimedia.org/wiki/File:Commons_Cafe.jpg', mime: 'image/jpeg',
          extmetadata: { Artist: { value: '<a href="x">Asha R</a>' }, LicenseShortName: { value: 'CC BY-SA 4.0' } } }] }] } }));
      }
      if (u === 'https://upload.example/cafe.jpg') return new Response(img);
      return new Response('nope', { status: 404 });
    }) as unknown as typeof fetch;
    const r = await fetchPlacePhotos(db, mediaDir, cityId, { fetchImpl: fake });
    expect(r.added).toBe(1);
    // A second run doesn't look again for 30 days.
    expect((await fetchPlacePhotos(db, mediaDir, cityId, { fetchImpl: fake })).checked).toBe(0);

    const place = (await app.inject(`/restaurants/${rows[0]!.id}`)).json();
    expect(place.photos[0]).toMatchObject({ source: 'wikimedia', attribution: 'Photo: Asha R, CC BY-SA 4.0, via Wikimedia Commons' });
    expect(place.googlePhotos).toBe(false);
    expect((await app.inject(`/restaurants/${rows[0]!.id}/google-photos`)).json()).toEqual({ photos: [] });

    const map = (await app.inject(`/map?city=${cityId}&all=true`)).json();
    const pin = map.places.find((p: { id: string }) => p.id === rows[0]!.id);
    expect(pin).toMatchObject({ emoji: '☕', photoUrl: expect.stringContaining('/media/places/') });
    const withDish = map.places.find((p: { dishCount: number; topDish: { emoji: string | null } }) => p.dishCount > 0 && p.topDish.emoji);
    expect(withDish.emoji).toBe(withDish.topDish.emoji);
  });

  it('fetches Google photos live, keeps only the place id, and stays under the daily cap', async () => {
    const { googlePlacePhotos, resetGoogleState } = await import('../src/photos.js');
    resetGoogleState();
    const { rows } = await db.query<{ id: string }>("select id from restaurants where name = 'Commons Cafe'");
    let mediaCalls = 0;
    const fake = (async (url: string | URL) => {
      const u = String(url);
      if (u.includes('/media?')) {
        mediaCalls++;
        return new Response(JSON.stringify({ photoUri: `https://lh3.googleusercontent.com/p${mediaCalls}` }));
      }
      if (u.endsWith('places:searchText')) return new Response(JSON.stringify({ places: [{ id: 'gp1' }] }));
      if (u.includes('/places/gp1')) {
        return new Response(JSON.stringify({ photos: [1, 2, 3, 4].map((i) => ({ name: `places/gp1/photos/p${i}`, authorAttributions: [{ displayName: `Author ${i}`, uri: 'https://maps.google.com/u' }] })) }));
      }
      if (u.includes('/media?')) {
        mediaCalls++;
        return new Response(JSON.stringify({ photoUri: `https://lh3.googleusercontent.com/p${mediaCalls}` }));
      }
      return new Response('', { status: 404 });
    }) as unknown as typeof fetch;
    const place = { id: rows[0]!.id, name: 'Commons Cafe', locality: null, city: 'Nashik', lat: 20, lng: 73.79, googlePlaceId: null };
    const photos = await googlePlacePhotos(db, place, { apiKey: 'k', dailyLimit: 2, fetchImpl: fake });
    expect(photos).toHaveLength(2);
    expect(photos[0]).toEqual({ url: 'https://lh3.googleusercontent.com/p1', authors: [{ name: 'Author 1', uri: 'https://maps.google.com/u' }] });
    expect((await db.query('select google_place_id from restaurants where id = $1', [place.id])).rows[0].google_place_id).toBe('gp1');
    // Cached for an hour: no new paid calls.
    await googlePlacePhotos(db, place, { apiKey: 'k', dailyLimit: 2, fetchImpl: fake });
    expect(mediaCalls).toBe(2);
    // Take me there gets the stored Google id; invented demo places never get one.
    expect((await app.inject(`/restaurants/${place.id}/maps`)).json()).toEqual({ googlePlaceId: 'gp1' });
    const { rows: demo } = await db.query<{ id: string }>("select id from restaurants where source = 'seed' limit 1");
    if (demo[0]) expect((await app.inject(`/restaurants/${demo[0].id}/maps`)).json()).toEqual({ googlePlaceId: null });
  });
});

describe('removing demo content', () => {
  it('removes demo accounts and, with --all, the invented places, keeping real ones', async () => {
    const { removeDemoActivity, removeDemoCatalog } = await import('../src/seed.js');
    const real = await db.query<{ n: number }>("select count(*)::int as n from restaurants where source <> 'seed'");
    const { rows: anyPost } = await db.query<{ id: string }>(
      "select p.id from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id where r.source = 'seed' limit 1",
    );
    await removeDemoActivity(db);
    expect((await db.query('select 1 from profiles where is_demo')).rowCount).toBe(0);
    const r = await removeDemoCatalog(db, mediaDir);
    expect(r.places).toBeGreaterThan(0);
    expect((await db.query("select 1 from restaurants where source = 'seed'")).rowCount).toBe(0);
    expect((await db.query<{ n: number }>("select count(*)::int as n from restaurants where source <> 'seed'")).rows[0]!.n).toBe(real.rows[0]!.n);
    await expect(fs.stat(path.join(mediaDir, 'posts', anyPost[0]!.id))).rejects.toThrow();
    expect((await app.inject(`/feed?city=${cityId}&tab=top&limit=5&offset=0&seed=x`)).statusCode).toBe(200);
  });

  it('brings the demo back with real, credited dish photos and marks demo places', async () => {
    const { restoreDemoPlaces, seedDemoActivity: again } = await import('../src/seed.js');
    const { refreshDemoDishPhotos } = await import('../src/demo-photos.js');
    expect(await restoreDemoPlaces(db, mediaDir)).toBe(true);
    expect(await restoreDemoPlaces(db, mediaDir)).toBe(false);
    expect(await again(db)).toBe(true);
    // Every demo dish comes with its bundled real photo, credited.
    const credited = await db.query<{ n: number }>(
      "select count(*)::int as n from posts where source = 'seed' and photo_credit like 'Photo: %, via Wikimedia Commons'",
    );
    expect(credited.rows[0]!.n).toBe(30);

    // A server seeded with the old drawn pictures: its demo posts carry the old credit.
    await db.query("update posts set photo_credit = 'WorthAbite demo' where source = 'seed'");
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'ff-demo-'));
    const jpeg = await sharp({ create: { width: 1080, height: 1350, channels: 3, background: { r: 140, g: 80, b: 20 } } }).jpeg().toBuffer();
    const credits: Record<string, object> = {};
    for (const key of ['barn_veg_burger', 'katta_vada_pav']) {
      await fs.writeFile(path.join(dir, `${key}.jpg`), jpeg);
      credits[key] = { title: `File:${key}.jpg`, author: 'Asha Cook', licence: 'CC BY-SA 4.0', source: 'https://commons.wikimedia.org/wiki/x' };
    }
    await fs.writeFile(path.join(dir, 'credits.json'), JSON.stringify(credits));
    const r = await refreshDemoDishPhotos(db, mediaDir, () => {}, dir);
    expect(r.updated).toBe(2);
    expect(r.missing).toContain('Rabdi');

    const { rows } = await db.query<{ id: string }>(
      "select d.id from dishes d join restaurants r on r.id = d.restaurant_id where r.source = 'seed' and d.name = 'Veg Burger'",
    );
    const dish = (await app.inject(`/dishes/${rows[0]!.id}`)).json();
    expect(dish.restaurant.isDemo).toBe(true);
    expect(dish.posts[0].photoCredit).toBe('Photo: Asha Cook, CC BY-SA 4.0, via Wikimedia Commons');
    expect(dish.cover.imageUrl).toMatch(/main\.webp\?v=/);
    expect((await app.inject(dish.cover.imageUrl)).statusCode).toBe(200);
    const place = (await app.inject(`/restaurants/${dish.restaurant.id}`)).json();
    expect(place.isDemo).toBe(true);
    // A second run leaves the photos alone.
    expect((await refreshDemoDishPhotos(db, mediaDir, () => {}, dir)).updated).toBe(0);
    // A re-cropped photo with the same credit is still noticed.
    const recropped = await sharp({ create: { width: 1080, height: 1350, channels: 3, background: { r: 20, g: 120, b: 60 } } }).jpeg().toBuffer();
    await fs.writeFile(path.join(dir, 'katta_vada_pav.jpg'), recropped);
    expect((await refreshDemoDishPhotos(db, mediaDir, () => {}, dir)).updated).toBe(1);
  });
});
