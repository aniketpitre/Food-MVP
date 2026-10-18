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
import { seedCatalog, seedDemoActivity, syncVocabulary } from '../src/seed.js';

const adminUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5433/postgres';
const dbName = `worthabite_social_${process.pid}`;

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
async function signUp(opts: { email?: string; established?: boolean } = {}) {
  userCounter++;
  const handle = `soc_${userCounter}`;
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

/** A different photo every time (a soft blur of random colours), as real photos are. */
async function photo(withGps = false): Promise<Buffer> {
  const raw = Buffer.from(Array.from({ length: 16 * 12 * 3 }, () => Math.floor(Math.random() * 256)));
  let img = sharp(raw, { raw: { width: 16, height: 12, channels: 3 } }).resize(1600, 1200).jpeg();
  if (withGps) {
    img = img.withExif({ IFD0: { Make: 'TestPhone' }, IFD3: { GPSLatitudeRef: 'N', GPSLatitude: '20/1 0/1 0/1' } });
  }
  return img.toBuffer();
}


async function follow(a: { auth: Record<string, string> }, handle: string) {
  return app.inject({ method: 'PUT', url: `/users/${handle}/follow`, headers: a.auth });
}

async function friends(u: { auth: Record<string, string> }, q = '') {
  return (await app.inject({ url: `/me/friends${q ? `?q=${q}` : ''}`, headers: u.auth })).json().map((p: { handle: string }) => p.handle);
}

describe('sharing with followers', () => {
  it('a brand-new follower can be shared with at once, both ways', async () => {
    const me = await signUp();
    const fan = await signUp();
    await follow(fan, me.handle);
    expect(await friends(me)).toContain(fan.handle);
    expect(await friends(fan)).toContain(me.handle);
    const dish = await dishId('Misal Pav');
    const r = (await app.inject({ method: 'POST', url: '/messages', headers: me.auth, payload: { handles: [fan.handle], dishId: dish } })).json();
    expect(r).toEqual({ sent: [fan.handle], failed: [] });
  });

  it('saving your profile keeps your sharing setting (and two-step) in what the app gets back', async () => {
    const me = await signUp();
    await app.inject({ method: 'PATCH', url: '/me/profile', headers: me.auth, payload: { dmPolicy: 'off' } });
    const after = (await app.inject({ method: 'PATCH', url: '/me/profile', headers: me.auth, payload: { bio: 'hi' } })).json();
    expect(after).toMatchObject({ dmPolicy: 'off', twoFactor: false, emailVerified: false, bio: 'hi' });
    const back = (await app.inject({ method: 'PATCH', url: '/me/profile', headers: me.auth, payload: { dmPolicy: 'everyone' } })).json();
    expect(back.dmPolicy).toBe('everyone');
  });

  it('"only people I follow" is respected in the list and when sending', async () => {
    const picky = await signUp();
    const fan = await signUp();
    await follow(fan, picky.handle); // fan follows picky, picky doesn't follow fan
    await app.inject({ method: 'PATCH', url: '/me/profile', headers: picky.auth, payload: { dmPolicy: 'following' } });
    expect(await friends(fan)).not.toContain(picky.handle);
    const dish = await dishId('Misal Pav');
    const r = (await app.inject({ method: 'POST', url: '/messages', headers: fan.auth, payload: { handles: [picky.handle], dishId: dish } })).json();
    expect(r.failed).toEqual([picky.handle]);
    await follow(picky, fan.handle);
    expect(await friends(fan)).toContain(picky.handle);
  });

  it('reports people it could not find, and refuses a dish that is gone before sending anything', async () => {
    const me = await signUp();
    const fan = await signUp();
    await follow(fan, me.handle);
    const dish = await dishId('Misal Pav');
    const r = (await app.inject({ method: 'POST', url: '/messages', headers: me.auth, payload: { handles: [fan.handle, 'nobody_here'], dishId: dish } })).json();
    expect(r).toEqual({ sent: [fan.handle], failed: ['nobody_here'] });
    const gone = await app.inject({ method: 'POST', url: '/messages', headers: me.auth, payload: { handles: [fan.handle], dishId: '00000000-0000-4000-8000-000000000000' } });
    expect(gone.statusCode).toBe(404);
  });

  it('searches beyond the first page of connections', async () => {
    const me = await signUp();
    const late = await signUp();
    await follow(late, me.handle);
    expect(await friends(me, late.handle)).toEqual([late.handle]);
  });
});

describe('blocking', () => {
  it('ends following both ways and hides each from the other', async () => {
    const a = await signUp();
    const b = await signUp();
    await follow(a, b.handle);
    await follow(b, a.handle);
    await app.inject({ method: 'POST', url: '/blocks', headers: a.auth, payload: { handle: b.handle } });
    const { rows } = await db.query('select count(*)::int as n from follows where follower_id = any($1) and followee_id = any($1)', [[a.id, b.id]]);
    expect(rows[0].n).toBe(0);
    // The blocked person can't see the profile of whoever blocked them.
    expect((await app.inject({ url: `/users/${a.handle}`, headers: b.auth })).statusCode).toBe(404);
    expect((await app.inject({ url: `/users/${a.handle}/loved`, headers: b.auth })).statusCode).toBe(404);
    expect((await app.inject({ url: `/users/${a.handle}/followers`, headers: b.auth })).statusCode).toBe(404);
    // The blocker still can (to unblock).
    expect((await app.inject({ url: `/users/${b.handle}`, headers: a.auth })).json().blockedByMe).toBe(true);
  });

  it('the Activity badge only counts what the list can show', async () => {
    const me = await signUp();
    const pest = await signUp();
    await follow(pest, me.handle);
    expect((await app.inject({ url: '/notifications/unread', headers: me.auth })).json().count).toBe(1);
    await app.inject({ method: 'POST', url: '/blocks', headers: me.auth, payload: { handle: pest.handle } });
    expect((await app.inject({ url: '/notifications/unread', headers: me.auth })).json().count).toBe(0);
  });
});

describe('comments', () => {
  it('deleting a comment takes its replies with it, in every count', async () => {
    const me = await signUp();
    const dish = await dishId('Misal Pav');
    const page = (await app.inject(`/dishes/${dish}`)).json();
    const postId = page.cover.postId;
    const before = page.cover.commentCount;
    const top = (await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: me.auth, payload: { body: 'Top' } })).json();
    for (const body of ['r1', 'r2']) {
      await app.inject({ method: 'POST', url: `/posts/${postId}/comments`, headers: me.auth, payload: { body, parentId: top.id } });
    }
    expect((await app.inject(`/dishes/${dish}`)).json().cover.commentCount).toBe(before + 3);
    await app.inject({ method: 'DELETE', url: `/comments/${top.id}`, headers: me.auth });
    expect((await app.inject(`/dishes/${dish}`)).json().cover.commentCount).toBe(before);
  });
});

describe('profile counts', () => {
  it('a post at a new place shows on the profile and in Following at once', async () => {
    const u = await signUp({ established: true });
    const placeless = await app.inject({
      method: 'POST', url: '/posts',
      ...multipart(u.auth, { idempotencyKey: 'pc-1-xxxx', dishName: 'Hidden Gem Roll', newPlaceName: 'Pending Shack', newPlaceLat: '20.01', newPlaceLng: '73.79' }, await photo()),
    });
    expect(placeless.statusCode).toBe(201);
    // A post at a new place waiting for review shows on the profile, for everyone, at once.
    const viewer = await signUp();
    const p = (await app.inject({ url: `/users/${u.handle}`, headers: viewer.auth })).json();
    expect(p.posts.map((x: { dishName: string; restaurantStatus: string }) => [x.dishName, x.restaurantStatus]))
      .toEqual([['Hidden Gem Roll', 'pending']]);
    // ...and in their followers' Following feed.
    await follow(viewer, u.handle);
    const feed = (await app.inject({ url: '/feed/following', headers: viewer.auth })).json();
    expect(feed.items.map((i: { dish: { name: string } }) => i.dish.name)).toContain('Hidden Gem Roll');
    // But not in the public rankings until a person approves the place.
    const top = (await app.inject(`/feed?city=${cityId}&limit=50`)).json();
    expect(top.items.map((i: { name: string }) => i.name)).not.toContain('Hidden Gem Roll');
  });
});

describe('a new place from a verified visit', () => {
  it('goes live at once for an established account; otherwise waits for review', async () => {
    const vet = await signUp({ established: true });
    const at = { newPlaceLat: '20.0412', newPlaceLng: '73.7712', lat: '20.0412', lng: '73.7712', capture: 'camera' };
    const ok = await app.inject({ method: 'POST', url: '/posts', ...multipart(vet.auth, { idempotencyKey: 'vv-1-xxxx', dishName: 'Kesar Kulfi', newPlaceName: 'Kulfi Kendra', ...at }, await photo()) });
    expect(ok.json()).toMatchObject({ visit: 'verified', restaurantStatus: 'active' });
    const fresh = await signUp();
    const wait = await app.inject({ method: 'POST', url: '/posts', ...multipart(fresh.auth, { idempotencyKey: 'vv-2-xxxx', dishName: 'Malai Kulfi', newPlaceName: 'Kulfi Corner Two', ...at, newPlaceLat: '20.0612', lat: '20.0612' }, await photo()) });
    expect(wait.json()).toMatchObject({ visit: 'verified', restaurantStatus: 'pending' });
    // Posting from home (not at the place) never makes a new place go live by itself.
    const home = await app.inject({ method: 'POST', url: '/posts', ...multipart(vet.auth, { idempotencyKey: 'vv-3-xxxx', dishName: 'Rose Kulfi', newPlaceName: 'Far Kulfi', newPlaceLat: '20.08', newPlaceLng: '73.75', lat: '20.0412', lng: '73.7712', capture: 'camera' }, await photo()) });
    expect(home.json()).toMatchObject({ visit: null, restaurantStatus: 'pending' });
  });
});

