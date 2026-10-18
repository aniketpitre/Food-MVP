// Sign-in and account safety: admin only by a verified email, password resets that can't be
// probed or broken, locks that reset, and the password asked again before sensitive changes.
// Needs TEST_DATABASE_URL like api.test.ts; uses its own database so both can run at once.
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import type { Config } from '../src/config.js';
import { createPool, migrate, type Db } from '../src/db.js';

const adminUrl = process.env.TEST_DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5433/postgres';
const dbName = `worthabite_auth_${process.pid}`;

let app: FastifyInstance;
let db: Db;
let mediaDir: string;
const mails: { to: string; subject: string; text: string }[] = [];

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

  mediaDir = await fs.mkdtemp(path.join(os.tmpdir(), 'worthabite-auth-media-'));
  db = createPool(testUrl(dbName));
  const config: Config = {
    port: 0,
    databaseUrl: testUrl(dbName),
    jwtSecret: 'test-secret-that-is-long-enough-123456',
    mediaDir,
    publicUrl: 'http://test.local',
    adminEmails: ['boss@example.com', 'boss2@example.com'],
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
  const mailer = { enabled: true, send: async (to: string, subject: string, text: string) => { mails.push({ to, subject, text }); } };
  app = await buildApp(config, db, { logger: false, mailer });
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

const settle = () => new Promise((r) => setTimeout(r, 50)); // background emails
const lastCode = (to: string) => mails.filter((m) => m.to === to).at(-1)!.subject.match(/^(\d{6}) is your WorthAbite code$/)![1]!;
const otherCode = (code: string) => (code === '000000' ? '111111' : '000000');

let userCounter = 0;
async function signUp(opts: { email?: string } = {}) {
  userCounter++;
  const handle = `auth_${userCounter}`;
  const email = opts.email ?? `${handle}@example.com`;
  const res = await app.inject({
    method: 'POST',
    url: '/auth/register',
    payload: { email, password: 'correct horse', handle, acceptTerms: true },
  });
  expect(res.statusCode).toBe(201);
  const body = res.json();
  return { token: body.token as string, id: body.user.id as string, handle, email, verify: body.verify, user: body.user, auth: { authorization: `Bearer ${body.token}` } };
}

const login = (name: string, password = 'correct horse') =>
  app.inject({ method: 'POST', url: '/auth/login', payload: { login: name, password } });
const role = async (id: string) => (await db.query('select role from profiles where id = $1', [id])).rows[0].role as string;

describe('admin by email', () => {
  it('is not admin until the listed email is verified, then is; and stops when no longer listed', async () => {
    const u = await signUp({ email: 'boss@example.com' });
    expect(u.user.role).toBe('user');
    expect((await login(u.handle)).json().user.role).toBe('user');
    expect(await role(u.id)).toBe('user');
    await settle();
    const v = await app.inject({ method: 'POST', url: '/me/email/verify/confirm', headers: u.auth, payload: { challengeId: u.verify.challengeId, code: lastCode(u.email) } });
    expect(v.json()).toMatchObject({ emailVerified: true, role: 'admin' });
    expect((await login(u.handle)).json().user.role).toBe('admin');
    // Taken off the list (here: the address changed behind its back), signing in demotes.
    await db.query("update profiles set email = 'not.boss@example.com' where id = $1", [u.id]);
    expect((await login(u.handle)).json().user.role).toBe('user');
  });

  it('turning on two-step proves the email too', async () => {
    const u = await signUp({ email: 'boss2@example.com' });
    const start = (await app.inject({ method: 'POST', url: '/me/2fa/start', headers: u.auth })).json();
    const on = await app.inject({ method: 'POST', url: '/me/2fa/confirm', headers: u.auth, payload: { challengeId: start.challengeId, code: lastCode(u.email) } });
    expect(on.json()).toMatchObject({ twoFactor: true, role: 'admin' });
  });
});

describe('password reset', () => {
  it('checks the code before the password, so a wrong code never says "weak password"', async () => {
    const u = await signUp();
    const forgot = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: u.handle } })).json();
    await settle();
    const code = lastCode(u.email);
    const reset = (c: string, password: string) =>
      app.inject({ method: 'POST', url: '/auth/password/reset', payload: { challengeId: forgot.challengeId, code: c, password } });
    const probe = await reset(otherCode(code), 'password1');
    expect(probe.statusCode).toBe(401);
    expect(probe.json().error).toBe('bad_code');
    // The right code with a weak password is refused without spending the code.
    expect((await reset(code, 'password1')).json().error).toBe('weak_password');
    expect((await reset(code, 'brand new pass')).statusCode).toBe(200);
    expect((await reset(code, 'another new one')).statusCode).toBe(401);
  });

  it('a second request within 30 s gives the same challenge, and the emailed code works with it', async () => {
    const u = await signUp();
    const first = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: u.handle } })).json();
    await settle();
    const sentBefore = mails.length;
    const second = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: u.handle } })).json();
    await settle();
    expect(mails.length).toBe(sentBefore);
    expect(second.challengeId).toBe(first.challengeId);
    // A missing account still gets a fresh random id each time, so it looks the same.
    const a = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: 'nobody_at_all' } })).json();
    const b = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: 'nobody_at_all' } })).json();
    expect(a.challengeId).not.toBe(b.challengeId);
    expect(Object.keys(a).sort()).toEqual(Object.keys(second).sort());
    const ok = await app.inject({ method: 'POST', url: '/auth/password/reset', payload: { challengeId: second.challengeId, code: lastCode(u.email), password: 'brand new pass' } });
    expect(ok.statusCode).toBe(200);
  });

  it('a new code ends the older ones, and an account gets at most 10 a day', async () => {
    const u = await signUp();
    const first = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: u.handle } })).json();
    await settle();
    const oldCode = lastCode(u.email);
    await db.query("update auth_codes set sent_at = now() - interval '1 minute' where user_id = $1", [u.id]);
    const next = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: u.handle } })).json();
    await settle();
    expect(next.challengeId).not.toBe(first.challengeId);
    const old = await app.inject({ method: 'POST', url: '/auth/password/reset', payload: { challengeId: first.challengeId, code: oldCode, password: 'brand new pass' } });
    expect(old.statusCode).toBe(401);

    await db.query("update auth_codes set sent_at = now() - interval '1 minute' where user_id = $1", [u.id]);
    for (let i = 0; i < 8; i++) {
      await db.query(
        "insert into auth_codes (user_id, purpose, code_hash, expires_at, sent_at, used_at) values ($1, 'reset', 'x', now(), now() - interval '1 minute', now())",
        [u.id],
      );
    }
    const sentBefore = mails.length;
    const capped = (await app.inject({ method: 'POST', url: '/auth/password/forgot', payload: { login: u.handle } })).json();
    await settle();
    expect(mails.length).toBe(sentBefore);
    expect(capped.message).toBe(first.message);
  });
});

describe('the lock after wrong passwords', () => {
  it('starts counting again once an old lock has run out', async () => {
    const u = await signUp();
    for (let i = 0; i < 10; i++) await login(u.handle, 'wrong password');
    expect((await login(u.handle)).statusCode).toBe(429);
    await db.query("update profiles set locked_until = now() - interval '1 second' where id = $1", [u.id]);
    // One typo after the lock ran out doesn't lock again.
    expect((await login(u.handle, 'wrong password')).statusCode).toBe(401);
    const row = (await db.query('select failed_logins, locked_until from profiles where id = $1', [u.id])).rows[0];
    expect(row).toEqual({ failed_logins: 1, locked_until: null });
    expect((await login(u.handle)).statusCode).toBe(200);
  });

  it('turning off two-step counts wrong passwords toward the lock', async () => {
    const u = await signUp();
    await db.query('update profiles set two_factor = true, failed_logins = 8 where id = $1', [u.id]);
    const off = (password: string) => app.inject({ method: 'POST', url: '/me/2fa/disable', headers: u.auth, payload: { password } });
    expect((await off('wrong one')).statusCode).toBe(401);
    expect((await off('wrong one')).statusCode).toBe(401);
    const locked = await off('correct horse');
    expect(locked.statusCode).toBe(429);
    expect(locked.json().error).toBe('locked');
    expect((await login(u.handle)).statusCode).toBe(429);
  });

  it('changing the password counts wrong ones toward the lock', async () => {
    const u = await signUp();
    // (This route also allows only 10 calls an hour, so start near the lock.)
    await db.query('update profiles set failed_logins = 7 where id = $1', [u.id]);
    const change = (current: string) => app.inject({ method: 'POST', url: '/me/password', headers: u.auth, payload: { current, next: 'new tasty secret' } });
    for (let i = 0; i < 3; i++) expect((await change('wrong one')).json().error).toBe('bad_credentials');
    const locked = await change('correct horse');
    expect(locked.statusCode).toBe(429);
    expect(locked.json().error).toBe('locked');
  });
});

describe('deleting the account', () => {
  it('needs the password', async () => {
    const u = await signUp();
    expect((await app.inject({ method: 'DELETE', url: '/me', headers: u.auth })).statusCode).toBe(400);
    const wrong = await app.inject({ method: 'DELETE', url: '/me', headers: u.auth, payload: { password: 'wrong one' } });
    expect(wrong.statusCode).toBe(400);
    expect(wrong.json().error).toBe('bad_credentials');
    expect((await db.query('select failed_logins from profiles where id = $1', [u.id])).rows[0].failed_logins).toBe(1);
    expect((await app.inject({ url: '/me', headers: u.auth })).statusCode).toBe(200);
    const ok = await app.inject({ method: 'DELETE', url: '/me', headers: u.auth, payload: { password: 'correct horse' } });
    expect(ok.statusCode).toBe(200);
    expect((await app.inject({ url: '/me', headers: u.auth })).statusCode).toBe(401);
  });
});
