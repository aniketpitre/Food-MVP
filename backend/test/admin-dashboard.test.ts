// The admin dashboard: the page at GET /admin and the admin-only JSON behind it.
// Same set-up as api.test.ts (a throwaway database on TEST_DATABASE_URL), its own database.
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Config } from '../src/config.js';
import { createPool, migrate, type Db } from '../src/db.js';
import { ensureCities } from '../src/geo.js';
import { seedCatalog, seedDemoActivity, syncVocabulary } from '../src/seed.js';

const adminUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5433/postgres';
const dbName = `worthabite_dash_${process.pid}`;

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

  mediaDir = await fs.mkdtemp(path.join(os.tmpdir(), 'worthabite-dash-media-'));
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
  // The demo voters give the ranking something to explain.
  await seedDemoActivity(db);
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
  const handle = `dash_${userCounter}`;
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

// An admin is a listed email whose account is verified; sign-up alone may not be enough
// (an unverified address could belong to anyone), so it's set here and signed in again.
let adminHeaders: Record<string, string> | null = null;
async function adminAuth() {
  if (adminHeaders) return adminHeaders;
  const exists = (await db.query("select 1 from profiles where email = 'admin@example.com'")).rowCount;
  if (!exists) await signUp({ email: 'admin@example.com' });
  await db.query("update profiles set role = 'admin', email_verified_at = now() where email = 'admin@example.com'");
  const login = await app.inject({ method: 'POST', url: '/auth/login', payload: { login: 'admin@example.com', password: 'correct horse' } });
  expect(login.statusCode).toBe(200);
  adminHeaders = { authorization: `Bearer ${login.json().token}` };
  return adminHeaders;
}

async function place(name: string, createdBy: string | null = null, lng = 73.79) {
  const { rows } = await db.query<{ id: string }>(
    `insert into restaurants (city_id, name, name_norm, geo, status, created_by)
     values ($1, $2, norm_name($2), st_setsrid(st_makepoint($4, 20.0), 4326)::geography, 'active', $3) returning id`,
    [cityId, name, createdBy, lng],
  );
  return rows[0]!.id;
}

async function dish(restaurantId: string, name: string) {
  const { rows } = await db.query<{ id: string }>(
    `insert into dishes (restaurant_id, city_id, name, name_norm) values ($1, $2, $3, norm_name($3)) returning id`,
    [restaurantId, cityId, name],
  );
  return rows[0]!.id;
}

async function photoRow(dishId: string, authorId: string | null, visit: string | null = null) {
  const { rows } = await db.query<{ id: string }>(
    `insert into posts (dish_id, author_id, image_path, thumb_path, visit)
     values ($1, $2, '/media/x/main.webp', '/media/x/thumb.webp', $3) returning id`,
    [dishId, authorId, visit],
  );
  return rows[0]!.id;
}

async function vote(userId: string, dishId: string, kind: 'recommend' | 'pass' | 'save') {
  await db.query('insert into engagements (user_id, dish_id, kind) values ($1, $2, $3) on conflict do nothing', [userId, dishId, kind]);
}

describe('the dashboard page', () => {
  it('serves a self-contained page with no data in it', async () => {
    const res = await app.inject('/admin');
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toContain('text/html');
    expect(res.body).toContain('WorthAbite');
    expect(res.body).toContain('/auth/login/verify');
    expect(res.body).toContain('Admins only');
    // Nothing loaded from elsewhere.
    expect(res.body).not.toMatch(/<script[^>]+src=/);
    expect(res.body).not.toMatch(/<link[^>]+stylesheet/);
  });
});

describe('admin only', () => {
  it('refuses guests and normal users on every dashboard endpoint, and serves admins', async () => {
    const user = await signUp();
    const admin = await adminAuth();
    const anyDish = (await db.query("select id from dishes where status = 'live' limit 1")).rows[0].id;
    const anyPlace = (await db.query('select id from restaurants limit 1')).rows[0].id;
    const gets = [
      '/admin/overview',
      `/admin/recs?city=${cityId}&tab=top`,
      `/admin/dishes/${anyDish}/explain`,
      '/admin/users?q=dash',
      `/admin/users/${user.id}`,
      '/admin/places?limit=5',
      `/admin/places/duplicates?city=${cityId}`,
      '/admin/jobs',
      '/admin/activity?limit=5',
      '/admin/health',
    ];
    for (const url of gets) {
      expect((await app.inject({ url })).statusCode, url).toBe(401);
      expect((await app.inject({ url, headers: user.auth })).statusCode, url).toBe(403);
      const ok = await app.inject({ url, headers: admin });
      expect(ok.statusCode, `${url}: ${ok.body}`).toBe(200);
    }
    const posts: [string, unknown][] = [
      [`/admin/restaurants/${anyPlace}/merge`, { into: anyPlace }],
      ['/admin/cities/nashik/import-overture', {}],
      ['/admin/places/retire-osm', {}],
      ['/admin/demo/remove', { all: false }],
    ];
    for (const [url, payload] of posts) {
      expect((await app.inject({ method: 'POST', url, headers: user.auth, payload })).statusCode, url).toBe(403);
    }
  });

  it('needs the email to still be on the admin list, not just the role', async () => {
    const sneaky = await signUp({ email: 'not-listed@example.com' });
    await db.query("update profiles set role = 'admin' where id = $1", [sneaky.id]);
    expect((await app.inject({ url: '/admin/overview', headers: sneaky.auth })).statusCode).toBe(403);
    expect((await app.inject({ url: '/admin/queue', headers: sneaky.auth })).statusCode).toBe(403);
  });
});

describe('overview and lists', () => {
  it('counts everything and draws 30 days', async () => {
    const o = (await app.inject({ url: '/admin/overview', headers: await adminAuth() })).json();
    expect(o.totals.users).toBeGreaterThanOrEqual(40);
    expect(o.totals.demoUsers).toBe(40);
    expect(o.totals.loves).toBeGreaterThan(0);
    expect(o.series).toHaveLength(30);
    expect(o.series[0]).toHaveProperty('loves');
    expect(o.places.find((p: { source: string }) => p.source === 'seed')).toBeTruthy();
  });

  it('explains the prediction check and reports skill with and without trust', async () => {
    const h = (await app.inject({ url: '/admin/health', headers: await adminAuth() })).json();
    expect(h.prediction.note).toMatch(/before it/);
    expect(h.prediction).toHaveProperty('skillWithoutTrust');
  });

  it('finds users and places and lists the latest activity', async () => {
    const admin = await adminAuth();
    const users = (await app.inject({ url: '/admin/users?q=demo_aarav1', headers: admin })).json();
    expect(users[0]).toMatchObject({ handle: 'demo_aarav1', isDemo: true });
    expect(users[0]).toHaveProperty('standing');
    expect(users[0]).toHaveProperty('sessions');
    const places = (await app.inject({ url: `/admin/places?source=seed&city=${cityId}`, headers: admin })).json();
    expect(places.length).toBeGreaterThan(0);
    expect(places.every((p: { source: string }) => p.source === 'seed')).toBe(true);
    const a = (await app.inject({ url: '/admin/activity?limit=10', headers: admin })).json();
    expect(a.votes.length).toBe(10);
    expect(a.signups.length).toBeGreaterThan(0);
  });
});

describe('recommendations explorer', () => {
  it('shows a tab with the numbers behind each card', async () => {
    const admin = await adminAuth();
    const r = (await app.inject({ url: `/admin/recs?city=${cityId}&tab=top&limit=20`, headers: admin })).json();
    expect(r.items.length).toBeGreaterThan(0);
    expect(r.items[0]).toMatchObject({ rank: 1 });
    for (const k of ['loves', 'passes', 'wLoves', 'wPasses', 'rating', 'confidence', 'score', 'score7d', 'endorsers', 'wEndorsers', 'lane', 'place']) {
      expect(r.items[0]).toHaveProperty(k);
    }
    expect((await app.inject({ url: `/admin/recs?city=${cityId}&tab=nearby`, headers: admin })).statusCode).toBe(400);
    const near = (await app.inject({ url: `/admin/recs?city=${cityId}&tab=nearby&lat=20&lng=73.79`, headers: admin })).json();
    expect(typeof near.items[0].distanceM).toBe('number');
  });

  it('explains For You: who it came from and why', async () => {
    const admin = await adminAuth();
    const me = await signUp({ established: true });
    // Follow a demo voter: their loves become this person's For You.
    const demo = (await db.query("select id from profiles where handle = 'demo_aarav1'")).rows[0].id;
    await db.query('insert into follows (follower_id, followee_id) values ($1, $2)', [me.id, demo]);
    expect((await app.inject({ url: `/admin/recs?city=${cityId}&tab=foryou`, headers: admin })).statusCode).toBe(400);
    const r = (await app.inject({ url: `/admin/recs?city=${cityId}&tab=foryou&user=${me.handle}`, headers: admin })).json();
    expect(r.items.length).toBeGreaterThan(0);
    expect(r.items[0]).toMatchObject({ byUser: 'demo_aarav1', followed: true });
    const u = (await app.inject({ url: `/admin/users/${me.handle}?city=${cityId}`, headers: admin })).json();
    expect(u.user.handle).toBe(me.handle);
    expect(u.follows.map((f: { handle: string }) => f.handle)).toContain('demo_aarav1');
    expect(u.forYou[0]).toMatchObject({ byUser: 'demo_aarav1', followed: true });
    expect(Array.isArray(u.matches)).toBe(true);
  });
});

describe('dish detail', () => {
  it('explains every vote, and that the place owner is ignored', async () => {
    const admin = await adminAuth();
    const owner = await signUp({ established: true });
    const fan = await signUp({ established: true });
    const critic = await signUp({ established: true });
    const p = await place('Explain Dhaba', owner.id);
    const d = await dish(p, 'Explain Thali');
    await photoRow(d, fan.id, 'verified');
    await vote(owner.id, d, 'recommend');
    await vote(fan.id, d, 'recommend');
    await vote(fan.id, d, 'save');
    await vote(critic.id, d, 'pass');
    await db.query('select refresh_dish_stats($1)', [d]);
    await db.query('select refresh_people_pick()');

    const x = (await app.inject({ url: `/admin/dishes/${d}/explain`, headers: admin })).json();
    expect(x.dish).toMatchObject({ name: 'Explain Thali', loves: 1, passes: 1, placeAddedBy: owner.handle });
    const byHandle = (h: string, kind: string) => x.votes.find((v: { handle: string; kind: string }) => v.handle === h && v.kind === kind);
    expect(byHandle(owner.handle, 'recommend')).toMatchObject({ isOwner: true, weight: 0 });
    const fanVote = byHandle(fan.handle, 'recommend');
    expect(fanVote).toMatchObject({ isOwner: false, verifiedVisit: true, twins: 0 });
    expect(fanVote.weight).toBeGreaterThan(0);
    expect(fanVote).toHaveProperty('trustOverall');
    expect(fanVote).toHaveProperty('standing');
    expect(byHandle(critic.handle, 'pass')).toMatchObject({ verifiedVisit: false });
    // The visit bonus shows in the weight: the fan's vote outweighs the critic's.
    expect(fanVote.weight).toBeGreaterThan(byHandle(critic.handle, 'pass').weight);
    // Stored weighted loves match the sum of the explained weights.
    const sumLoves = x.votes.filter((v: { kind: string }) => v.kind === 'recommend').reduce((s: number, v: { weight: number }) => s + v.weight, 0);
    expect(sumLoves).toBeCloseTo(x.dish.wLoves, 2);
    expect(x.feedPoints.find((f: { handle: string }) => f.handle === owner.handle)).toMatchObject({ isOwner: true, points: 0 });
    expect(x.photos[0]).toMatchObject({ visit: 'verified', held: false });
    expect(x.settings).toHaveProperty('pp_prior');
  });
});

describe('places', () => {
  it('merges one place into another: dishes move, same-name dishes merge', async () => {
    const admin = await adminAuth();
    const a = await signUp({ established: true });
    const b = await signUp({ established: true });
    const from = await place('Sharma Snacks Old', null, 73.791);
    const into = await place('Sharma Snacks', null, 73.7911);
    const misalFrom = await dish(from, 'Misal Special');
    const pohaFrom = await dish(from, 'Kanda Poha');
    const misalInto = await dish(into, 'Misal Special');
    const postFrom = await photoRow(misalFrom, a.id);
    await photoRow(misalInto, b.id);
    await vote(a.id, misalFrom, 'recommend');
    await vote(b.id, misalInto, 'recommend');
    await vote(a.id, pohaFrom, 'pass');
    await db.query(
      `insert into restaurant_photos (restaurant_id, source, source_ref, image_path, thumb_path, attribution)
       values ($1, 'wikimedia', 'File:x.jpg', '/m/a', '/m/b', 'Photo: X')`,
      [from],
    );

    const dup = (await app.inject({ url: `/admin/places/duplicates?city=${cityId}`, headers: admin })).json();
    expect(dup.some((x: { aId: string; bId: string }) => [x.aId, x.bId].includes(from) && [x.aId, x.bId].includes(into))).toBe(true);

    expect((await app.inject({ method: 'POST', url: `/admin/restaurants/${from}/merge`, headers: admin, payload: { into: from } })).statusCode).toBe(400);
    const res = await app.inject({ method: 'POST', url: `/admin/restaurants/${from}/merge`, headers: admin, payload: { into } });
    expect(res.statusCode, res.body).toBe(200);
    expect(res.json()).toMatchObject({ moved: 1, merged: 1, photos: 1 });

    expect((await db.query('select status from restaurants where id = $1', [from])).rows[0].status).toBe('hidden');
    expect((await db.query('select restaurant_id from dishes where id = $1', [pohaFrom])).rows[0].restaurant_id).toBe(into);
    expect((await db.query('select status from dishes where id = $1', [misalFrom])).rows[0].status).toBe('hidden');
    expect((await db.query('select into_id from dish_merges where from_id = $1', [misalFrom])).rows[0].into_id).toBe(misalInto);
    expect((await db.query('select dish_id from posts where id = $1', [postFrom])).rows[0].dish_id).toBe(misalInto);
    expect((await db.query('select count(*)::int as n from restaurant_photos where restaurant_id = $1', [into])).rows[0].n).toBe(1);
    // Both loves now sit on the one dish, and its rating was rebuilt.
    expect((await db.query('select loves from dish_rating where dish_id = $1', [misalInto])).rows[0].loves).toBe(2);
  });

  it('imports the bundled Overture Maps places in the background and records the job', async () => {
    const admin = await adminAuth();
    const res = await app.inject({ method: 'POST', url: '/admin/cities/nashik/import-overture', headers: admin, payload: {} });
    expect(res.statusCode).toBe(202);
    let job: { status: string; result: { fetched: number; added: number; linked: number }; log: string[] } | undefined;
    for (let i = 0; i < 300; i++) {
      const jobs = (await app.inject({ url: '/admin/jobs', headers: admin })).json();
      job = jobs.find((j: { id: number }) => j.id === res.json().jobId);
      if (job && job.status !== 'running') break;
      await new Promise((r) => setTimeout(r, 100));
    }
    expect(job).toMatchObject({ status: 'done' });
    expect(job!.result.fetched).toBeGreaterThan(1000);
    expect(job!.result.added + job!.result.linked).toBeGreaterThan(1000);
    expect((await db.query("select count(*)::int as n from restaurants where source = 'overture' and status = 'active'")).rows[0].n).toBeGreaterThan(900);
    expect((await app.inject({ method: 'POST', url: '/admin/cities/nowhere/import-overture', headers: admin, payload: {} })).statusCode).toBe(404);
  });

  it('retires OpenStreetMap places: matched ones become Overture, unknown empty ones are hidden', async () => {
    const admin = await adminAuth();
    const city = (await db.query("select id from cities where slug = 'nashik'")).rows[0].id;
    const add = async (name: string, osm: string, overture: string | null) => (await db.query(
      `insert into restaurants (city_id, name, name_norm, geo, status, source, osm_id, overture_id)
       values ($1, $2, norm_name($2), st_setsrid(st_makepoint(73.7, 20.1), 4326)::geography, 'active', 'osm', $3, $4) returning id`,
      [city, name, osm, overture],
    )).rows[0].id as string;
    const matched = await add('Old Matched Cafe', 'n7001', 'ov-matched');
    const empty = await add('Old Empty Cafe', 'n7002', null);
    const withDish = await add('Old Loved Cafe', 'n7003', null);
    await db.query(`insert into dishes (restaurant_id, city_id, name, name_norm) values ($1, $2, 'Old Dish', norm_name('Old Dish'))`, [withDish, city]);
    const r = (await app.inject({ method: 'POST', url: '/admin/places/retire-osm', headers: admin, payload: {} })).json();
    expect(r).toMatchObject({ ok: true, matched: 1, hidden: 1, keptWithDishes: 1 });
    const state = async (id: string) => (await db.query('select source, status from restaurants where id = $1', [id])).rows[0];
    expect(await state(matched)).toEqual({ source: 'overture', status: 'active' });
    expect(await state(empty)).toEqual({ source: 'osm', status: 'hidden' });
    expect(await state(withDish)).toEqual({ source: 'osm', status: 'active' });
  });
});

describe('removing demo content', () => {
  it('removes the demo accounts, then the demo places', async () => {
    const admin = await adminAuth();
    const first = await app.inject({ method: 'POST', url: '/admin/demo/remove', headers: admin, payload: { all: false } });
    expect(first.statusCode).toBe(200);
    expect(first.json()).toMatchObject({ ok: true, accounts: 40, places: 0 });
    expect((await db.query("select count(*)::int as n from restaurants where source = 'seed'")).rows[0].n).toBeGreaterThan(0);
    // A real person's post at a demo place keeps that place (and the post).
    const real = await signUp({ email: 'real-at-demo@example.com' });
    const demoPlace = (await db.query("select id, name from restaurants where source = 'seed' order by name limit 1")).rows[0];
    const demoDish = (await db.query('select id from dishes where restaurant_id = $1 limit 1', [demoPlace.id])).rows[0].id;
    await db.query(`insert into posts (dish_id, author_id, image_path, thumb_path) values ($1, $2, '/media/t.webp', '/media/t.webp')`, [demoDish, real.id]);
    const all = (await app.inject({ method: 'POST', url: '/admin/demo/remove', headers: admin, payload: { all: true } })).json();
    expect(all.accounts).toBe(0);
    expect(all.places).toBeGreaterThan(0);
    expect(all.kept).toEqual([demoPlace.name]);
    expect((await db.query("select id from restaurants where source = 'seed'")).rows.map((r: { id: string }) => r.id)).toEqual([demoPlace.id]);
    expect((await db.query('select count(*)::int as n from posts where author_id = $1', [real.id])).rows[0].n).toBe(1);
    // Retired for good: start-up seeding (DEMO_ACTIVITY on) doesn't bring the accounts back.
    const { seedDemoActivity } = await import('../src/seed.js');
    expect(await seedDemoActivity(db)).toBe(false);
    expect((await db.query('select count(*)::int as n from profiles where is_demo')).rows[0].n).toBe(0);
    // Imported and people's places are untouched.
    expect((await db.query("select count(*)::int as n from restaurants where source = 'overture' and status = 'active'")).rows[0].n).toBeGreaterThan(900);
    const jobs = (await app.inject({ url: '/admin/jobs', headers: admin })).json();
    expect(jobs.filter((j: { kind: string }) => j.kind === 'remove-demo')).toHaveLength(2);
  });
});
