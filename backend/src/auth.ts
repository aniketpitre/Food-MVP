import crypto from 'node:crypto';
import { promisify } from 'node:util';
import type { FastifyReply, FastifyRequest } from 'fastify';

const scrypt = promisify(crypto.scrypt) as (pw: string, salt: Buffer, len: number) => Promise<Buffer>;

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(password, salt, 64);
  return `scrypt$${salt.toString('base64')}$${key.toString('base64')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, saltB64, keyB64] = stored.split('$');
  if (scheme !== 'scrypt' || !saltB64 || !keyB64) return false;
  const expected = Buffer.from(keyB64, 'base64');
  const actual = await scrypt(password, Buffer.from(saltB64, 'base64'), expected.length);
  return crypto.timingSafeEqual(actual, expected);
}

export interface TokenPayload {
  sub: string;
  role: string;
  /** The sign-in session (see sessions table); absent on tokens from before sessions. */
  sid?: string;
  iat?: number;
}

declare module '@fastify/jwt' {
  interface FastifyJWT {
    payload: TokenPayload;
    user: TokenPayload;
  }
}

/**
 * Whether a signed token's session is still on: not signed out, not ended from another
 * device, the account not deleted. Answers are kept for 30 seconds per session so a busy
 * phone costs one small query every 30 s, not one per request; ending a session on this
 * server takes effect at once, on other servers within 30 s.
 */
const sessionCache = new Map<string, { ok: boolean; until: number }>();
const SESSION_CACHE_MS = 30_000;

function cacheKey(p: TokenPayload) {
  return p.sid ? `s:${p.sid}` : `u:${p.sub}:${p.iat ?? 0}`;
}

/** Forgets cached answers for these sessions (after sign-out) or a whole account. */
export function forgetSessions(keys: { sessionIds?: string[]; userId?: string }) {
  for (const id of keys.sessionIds ?? []) sessionCache.delete(`s:${id}`);
  if (keys.userId) {
    for (const k of sessionCache.keys()) if (k.startsWith(`u:${keys.userId}:`)) sessionCache.delete(k);
  }
}

async function sessionOn(req: FastifyRequest): Promise<boolean> {
  const p = req.user as TokenPayload;
  const key = cacheKey(p);
  const hit = sessionCache.get(key);
  if (hit && hit.until > Date.now()) return hit.ok;
  const db = req.server.db;
  let ok: boolean;
  if (p.sid) {
    const { rows } = await db.query<{ stale: boolean }>(
      'select last_seen_at < now() - interval \'10 minutes\' as stale from sessions where id = $1 and user_id = $2 and revoked_at is null',
      [p.sid, p.sub],
    );
    ok = !!rows[0];
    // "Last active" for the devices list, written at most every 10 minutes.
    if (rows[0]?.stale) void db.query('update sessions set last_seen_at = now() where id = $1', [p.sid]).catch(() => {});
  } else {
    const { rows } = await db.query<{ ok: boolean }>(
      'select tokens_valid_after is null or to_timestamp($2) >= tokens_valid_after as ok from profiles where id = $1',
      [p.sub, p.iat ?? 0],
    );
    ok = !!rows[0]?.ok;
  }
  if (sessionCache.size > 100_000) sessionCache.clear();
  sessionCache.set(key, { ok, until: Date.now() + SESSION_CACHE_MS });
  return ok;
}

/** Sets request.user when a valid bearer token is present; guests pass through. */
export async function optionalAuth(req: FastifyRequest): Promise<void> {
  if (!req.headers.authorization) return;
  try {
    await req.jwtVerify();
    if (!(await sessionOn(req))) (req as { user?: unknown }).user = undefined;
  } catch {
    // An invalid or expired token is treated as a guest; protected routes reject below.
    (req as { user?: unknown }).user = undefined;
  }
}

export async function requireAuth(req: FastifyRequest, reply: FastifyReply): Promise<void> {
  try {
    await req.jwtVerify();
  } catch {
    return reply.code(401).send({ error: 'sign_in_required', message: 'Please sign in to do this.' });
  }
  if (!(await sessionOn(req))) {
    return reply.code(401).send({ error: 'session_ended', message: 'You were signed out. Please sign in again.' });
  }
}

export function userId(req: FastifyRequest): string | null {
  return (req.user as TokenPayload | undefined)?.sub ?? null;
}

export function sessionId(req: FastifyRequest): string | null {
  return (req.user as TokenPayload | undefined)?.sid ?? null;
}

/** Always takes as long as checking a real password, so a missing account can't be told apart by timing. */
const DUMMY_HASH = 'scrypt$AAAAAAAAAAAAAAAAAAAAAA==$' + Buffer.alloc(64).toString('base64');
export async function burnPasswordCheck(password: string) {
  await verifyPassword(password, DUMMY_HASH).catch(() => false);
}

const COMMON = new Set([
  'password', 'password1', 'password123', '12345678', '123456789', '1234567890', 'qwerty123', 'qwertyuiop',
  'iloveyou', '11111111', '00000000', 'abc12345', 'abcd1234', 'welcome1', 'letmein1', 'admin123', 'india123',
  'worthabite', 'foodfeed', 'passw0rd', '87654321', 'asdfghjk', 'sunshine', 'princess', 'football',
]);

/** Why a new password isn't good enough, or null. */
export function passwordProblem(password: string, others: (string | undefined)[] = []): string | null {
  if (password.length < 8) return 'Use at least 8 characters.';
  if (password.length > 200) return 'Use at most 200 characters.';
  const lower = password.toLowerCase();
  if (COMMON.has(lower) || /^(.)\1+$/.test(password) || /^\d+$/.test(password)) {
    return 'That password is too easy to guess. Try a few words or mix in letters and numbers.';
  }
  for (const o of others) {
    const v = o?.toLowerCase().split('@')[0];
    if (v && v.length >= 3 && lower.includes(v)) return "Don't use your username or email in your password.";
  }
  return null;
}
