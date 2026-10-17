// Integration tests against a real Postgres + PostGIS database.
// Needs TEST_DATABASE_URL pointing at a server where the user may create databases,
// e.g. postgres://postgres:postgres@localhost:5433/postgres (see README).
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
import { seedCatalog, seedDemoActivity } from '../src/seed.js';

const adminUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5433/postgres';
const dbName = `foodfeed_test_${process.pid}`;

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

  mediaDir = await fs.mkdtemp(path.join(os.tmpdir(), 'foodfeed-media-'));
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
  const boundary = '----foodfeedtest' + Math.random().toString(16).slice(2);
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
    expect(share.body).toContain(`foodfeed://dish/${id}`);
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

  it('caps one person at 3 points and halves brand-new accounts', async () => {
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
    expect(score).toBeCloseTo(3.5, 3); // newcomer's recommend counts 0.5
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

describe('rich messages and notifications', () => {
  it('shares places and posts, sends photos, replies, reacts, and reports it all as updates', async () => {
    const a = await signUp();
    const b = await signUp();
    const start = (await app.inject({ url: '/updates', headers: b.auth })).json();
    expect(start).toMatchObject({ messages: [], reactions: [], activity: [] });
    const since = encodeURIComponent(start.now);
    await app.inject({ method: 'PUT', url: `/users/${a.handle}/follow`, headers: b.auth });

    const conv = (await app.inject({ method: 'POST', url: '/conversations', headers: a.auth, payload: { handle: b.handle } })).json();
    const dish = await dishId('Misal Pav');
    const place = (await db.query('select restaurant_id from dishes where id = $1', [dish])).rows[0].restaurant_id;
    const postId = (await app.inject(`/dishes/${dish}`)).json().cover.postId;
    const send = (payload: object) =>
      app.inject({ method: 'POST', url: `/conversations/${conv.id}/messages`, headers: a.auth, payload });

    const placeMsg = (await send({ restaurantId: place })).json();
    expect(placeMsg).toMatchObject({ kind: 'place', place: { id: place } });
    expect(typeof placeMsg.place.lat).toBe('number');
    const postMsg = (await send({ postId, body: 'look at this' })).json();
    expect(postMsg).toMatchObject({ kind: 'post', body: 'look at this', post: { id: postId, dishId: dish, dishName: 'Misal Pav' } });

    const up = multipart(a.auth, { body: 'my plate' }, await photo(true));
    const photoRes = await app.inject({ method: 'POST', url: `/conversations/${conv.id}/photo`, ...up });
    expect(photoRes.statusCode).toBe(201);
    expect(photoRes.json()).toMatchObject({ kind: 'photo', body: 'my plate', photo: { imageUrl: expect.stringContaining('messages/') } });

    // b's phone sees a's three messages.
    const forB = (await app.inject({ url: `/updates?since=${since}`, headers: b.auth })).json();
    expect(forB.messages.map((m: { kind: string }) => m.kind)).toEqual(['place', 'post', 'photo']);
    expect(forB.messages[0]).toMatchObject({ conversationId: conv.id, from: { handle: a.handle } });

    // b replies to the photo (which marks the chat read) and double-taps the place with a heart.
    const replyRes = await app.inject({
      method: 'POST', url: `/conversations/${conv.id}/messages`, headers: b.auth,
      payload: { body: 'Yum!', replyTo: photoRes.json().id },
    });
    expect(replyRes.json()).toMatchObject({ replyTo: { id: photoRes.json().id, kind: 'photo', mine: false } });
    expect((await app.inject({ method: 'PUT', url: `/messages/${placeMsg.id}/reaction`, headers: b.auth, payload: { emoji: '❤️' } })).statusCode).toBe(200);
    const outsider = await signUp();
    expect((await app.inject({ method: 'PUT', url: `/messages/${placeMsg.id}/reaction`, headers: outsider.auth, payload: { emoji: '❤️' } })).statusCode).toBe(404);
    const thread = (await app.inject({ url: `/conversations/${conv.id}`, headers: a.auth })).json();
    expect(thread.messages.find((m: { id: string }) => m.id === placeMsg.id).reactions).toEqual([{ emoji: '❤️', mine: false }]);

    // a's sees the reaction, the reply, and the follow.
    const forA = (await app.inject({ url: `/updates?since=${since}`, headers: a.auth })).json();
    expect(forA.reactions).toMatchObject([{ messageId: placeMsg.id, emoji: '❤️', from: { handle: b.handle } }]);
    expect(forA.messages).toMatchObject([{ body: 'Yum!' }]);
    expect(forA.activity).toMatchObject([{ kind: 'follow', actor: { handle: b.handle } }]);
    // Nothing new after the cursor moves on; reading a chat silences it.
    expect((await app.inject({ url: `/updates?since=${encodeURIComponent(forA.now)}`, headers: a.auth })).json().messages).toEqual([]);
    expect((await app.inject({ url: `/updates?since=${since}`, headers: b.auth })).json().messages).toEqual([]);

    // Removing the reaction and unsending the photo.
    await app.inject({ method: 'DELETE', url: `/messages/${placeMsg.id}/reaction`, headers: b.auth });
    expect((await app.inject({ method: 'DELETE', url: `/messages/${photoRes.json().id}`, headers: a.auth })).statusCode).toBe(204);
    const after = (await app.inject({ url: `/conversations/${conv.id}`, headers: a.auth })).json().messages;
    expect(after.find((m: { id: string }) => m.id === placeMsg.id).reactions).toEqual([]);
    expect(after.some((m: { kind: string }) => m.kind === 'photo')).toBe(false);
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
    expect(rows[0].trust).toBeGreaterThan(1.0);
    // The fan is listed among the people to trust for pav bhaji (people with longer records may rank above).
    expect(res.tasters.map((t: { handle: string }) => t.handle)).toContain(fan.handle);
    // ...and was told each time someone agreed (up to three people per new love).
    const inbox = (await app.inject({ url: '/notifications', headers: (await loginAs(fan.handle)).auth })).json();
    expect(inbox.items.some((n: { kind: string }) => n.kind === 'agree')).toBe(true);
  });

  it('serves the home sections and the map', async () => {
    const home = (await app.inject(`/home?city=${cityId}&lat=20.0&lng=73.78`)).json();
    expect(Object.keys(home).sort()).toEqual(['cravings', 'following', 'fresh', 'nearby', 'peoplesPicks', 'tasters', 'trending']);
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

async function loginAs(handle: string) {
  const res = await app.inject({ method: 'POST', url: '/auth/login', payload: { email: `${handle}@example.com`, password: 'correct horse' } });
  return { auth: { authorization: `Bearer ${res.json().token}` } };
}

describe('direct messages', () => {
  it('sends, lists, marks read and unsends; strangers land in Requests until accepted', async () => {
    const a = await signUp();
    const b = await signUp();
    // a writes to b, whom b doesn't follow: a request for b.
    const conv = (await app.inject({ method: 'POST', url: '/conversations', headers: a.auth, payload: { handle: b.handle } })).json();
    const sent = await app.inject({ method: 'POST', url: `/conversations/${conv.id}/messages`, headers: a.auth, payload: { body: 'Misal tonight?' } });
    expect(sent.statusCode).toBe(201);
    expect(sent.json()).toMatchObject({ kind: 'text', body: 'Misal tonight?', mine: true });

    expect((await app.inject({ url: '/inbox', headers: b.auth })).json()).toMatchObject({ items: [], requests: 1 });
    const req = (await app.inject({ url: '/inbox?folder=request', headers: b.auth })).json();
    expect(req.items[0]).toMatchObject({ with: { handle: a.handle }, unread: true, lastMessage: { body: 'Misal tonight?', mine: false } });
    expect((await app.inject({ url: '/inbox/unread', headers: b.auth })).json()).toEqual({ chats: 0, requests: 1 });

    // Replying accepts the request; a dish can be shared too.
    const dish = await dishId('Kolhapuri Misal');
    await app.inject({ method: 'POST', url: `/conversations/${conv.id}/messages`, headers: b.auth, payload: { dishId: dish } });
    expect((await app.inject({ url: '/inbox', headers: b.auth })).json().items).toHaveLength(1);
    const thread = (await app.inject({ url: `/conversations/${conv.id}`, headers: a.auth })).json();
    expect(thread).toMatchObject({ folder: 'inbox', with: { handle: b.handle }, canMessage: true });
    expect(thread.messages.map((m: { kind: string }) => m.kind)).toEqual(['text', 'dish']);
    expect(thread.messages[1].dish).toMatchObject({ name: 'Kolhapuri Misal' });
    expect((await app.inject({ url: '/inbox/unread', headers: a.auth })).json()).toEqual({ chats: 1, requests: 0 });
    await app.inject({ method: 'POST', url: `/conversations/${conv.id}/read`, headers: a.auth });
    expect((await app.inject({ url: '/inbox/unread', headers: a.auth })).json()).toEqual({ chats: 0, requests: 0 });

    // Polling with ?after returns only newer messages.
    const after = thread.messages[1].createdAt;
    await app.inject({ method: 'POST', url: `/conversations/${conv.id}/messages`, headers: a.auth, payload: { body: 'See you at 8' } });
    const newer = (await app.inject({ url: `/conversations/${conv.id}?after=${encodeURIComponent(after)}`, headers: b.auth })).json();
    expect(newer.messages.map((m: { body: string }) => m.body)).toEqual(['See you at 8']);

    // Unsend removes it for both; only the sender can.
    const id = newer.messages[0].id;
    expect((await app.inject({ method: 'DELETE', url: `/messages/${id}`, headers: b.auth })).statusCode).toBe(404);
    expect((await app.inject({ method: 'DELETE', url: `/messages/${id}`, headers: a.auth })).statusCode).toBe(204);

    // Deleting the chat clears it for you only.
    await app.inject({ method: 'DELETE', url: `/conversations/${conv.id}`, headers: b.auth });
    expect((await app.inject({ url: '/inbox', headers: b.auth })).json().items).toHaveLength(0);
    expect((await app.inject({ url: '/inbox', headers: a.auth })).json().items).toHaveLength(1);
  });

  it('respects message settings and blocks, and shares to several people', async () => {
    const a = await signUp();
    const b = await signUp();
    const c = await signUp();
    await app.inject({ method: 'PATCH', url: '/me/profile', headers: b.auth, payload: { dmPolicy: 'following' } });
    const toB = (await app.inject({ method: 'POST', url: '/conversations', headers: a.auth, payload: { handle: b.handle } })).json();
    const no = await app.inject({ method: 'POST', url: `/conversations/${toB.id}/messages`, headers: a.auth, payload: { body: 'hi' } });
    expect(no.statusCode).toBe(403);
    // Once b follows a, a can write, straight into b's inbox.
    await app.inject({ method: 'PUT', url: `/users/${a.handle}/follow`, headers: b.auth });
    const share = await app.inject({
      method: 'POST', url: '/messages', headers: a.auth,
      payload: { handles: [b.handle, c.handle], dishId: await dishId('Kolhapuri Misal'), body: 'This one?' },
    });
    expect(share.json()).toEqual({ sent: [b.handle, c.handle].sort(), failed: [] });
    expect((await app.inject({ url: '/inbox/unread', headers: b.auth })).json()).toEqual({ chats: 1, requests: 0 });

    // A block stops messages both ways and hides the chat.
    await app.inject({ method: 'POST', url: '/blocks', headers: c.auth, payload: { handle: a.handle } });
    expect((await app.inject({ method: 'POST', url: '/messages', headers: a.auth, payload: { handles: [c.handle], body: 'hello?' } })).json())
      .toEqual({ sent: [], failed: [c.handle] });
    expect((await app.inject({ url: '/inbox?folder=request', headers: c.auth })).json().items).toHaveLength(0);

    // Messages can be reported; they go to the admin queue.
    const msg = (await app.inject({ url: `/conversations/${toB.id}`, headers: b.auth })).json().messages[0];
    const rep = await app.inject({ method: 'POST', url: '/reports', headers: b.auth, payload: { targetType: 'message', targetId: msg.id, reason: 'spam' } });
    expect(rep.json()).toEqual({ ok: true, hidden: false });
  });
});

describe('stories', () => {
  it('posts a story, shows it in the tray, counts views and likes, and replies by message', async () => {
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
    const reply = await app.inject({ method: 'POST', url: `/stories/${storyId}/reply`, headers: b.auth, payload: { body: 'Going tomorrow!' } });
    expect(reply.statusCode).toBe(201);

    const mine = (await app.inject({ url: `/users/${a.handle}/stories`, headers: a.auth })).json();
    expect(mine).toMatchObject({ isMe: true, stories: [{ viewCount: 1 }] });
    const viewers = (await app.inject({ url: `/stories/${storyId}/viewers`, headers: a.auth })).json();
    expect(viewers.items).toEqual([expect.objectContaining({ handle: b.handle, liked: true })]);
    expect((await app.inject({ url: `/stories/${storyId}/viewers`, headers: b.auth })).json().items).toEqual([]);

    // One like message (not one per toggle) and the reply, both pointing at the story.
    const chat = (await app.inject({ url: `/conversations/${reply.json().conversationId}`, headers: a.auth })).json();
    expect(chat.messages.map((m: { kind: string }) => m.kind)).toEqual(['story_like', 'story_reply']);
    expect(chat.messages[1].story).toMatchObject({ id: storyId, expired: false });

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
    await db.query("update posts set photo_credit = 'FoodFeed demo' where source = 'seed'");
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
  });
});
