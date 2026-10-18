import { createHmac, randomInt, randomUUID, timingSafeEqual } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';
import { type CodePurpose, codeEmail } from '../mail.js';
import { confirmPassword, profileJson, startSession, syncAdminRole } from './auth.js';

const CODE_MINUTES = 10;
const MAX_ATTEMPTS = 5;
const RESEND_SECONDS = 30;

/** "an***@gmail.com": enough to recognise your own address, not enough to learn someone's. */
export function emailHint(email: string): string {
  const [name, domain] = email.split('@');
  if (!name || !domain) return email;
  return `${name.slice(0, Math.min(2, name.length))}***@${domain}`;
}

function hash(secret: string, id: string, code: string) {
  return createHmac('sha256', secret).update(`${id}:${code}`).digest('hex');
}

/**
 * A new 6-digit code for [user], emailed to them. Returns the challenge to send back with
 * the code, or an error if the email couldn't go out.
 */
export const MAIL_OFF = { error: 'mail_off', message: "Email isn't set up on this server yet, so codes can't be sent." };

export async function startChallenge(
  app: FastifyInstance,
  user: { id: string; email: string },
  purpose: CodePurpose,
  opts: { background?: boolean; to?: string; newEmail?: string } = {},
): Promise<{ challengeId: string; emailHint: string } | { error: string; message: string }> {
  if (!app.mailer.enabled) return MAIL_OFF;
  const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
  const id = randomUUID();
  await app.db.query(
    `insert into auth_codes (id, user_id, purpose, code_hash, expires_at, new_email)
     values ($1, $2, $3, $4, now() + make_interval(mins => $5), $6)`,
    [id, user.id, purpose, hash(app.config.jwtSecret, id, code), CODE_MINUTES, opts.newEmail ?? null],
  );
  const send = async () => {
    try {
      const mail = codeEmail(code, purpose);
      await app.mailer.send(opts.to ?? user.email, mail.subject, mail.text, mail.html);
      return true;
    } catch (err) {
      app.log.warn({ err }, 'code email failed');
      await app.db.query('delete from auth_codes where id = $1', [id]).catch(() => {});
      return false;
    }
  };
  // In the background, the answer comes back as fast whether or not the account exists.
  if (opts.background) void send();
  else if (!(await send())) return { error: 'mail_failed', message: "We couldn't send the code. Try again in a minute." };
  return { challengeId: id, emailHint: emailHint(opts.to ?? user.email) };
}

/** Checks a code; it's spent once right, and after 5 wrong tries. */
export async function checkCode(
  app: FastifyInstance,
  challengeId: string,
  code: string,
  purpose: CodePurpose,
  owner?: string,
  opts: { consume?: boolean } = {},
) {
  const { rows } = await app.db.query<{ user_id: string; code_hash: string; attempts: number; expired: boolean; used: boolean }>(
    `select user_id, code_hash, attempts, expires_at < now() as expired, used_at is not null as used
     from auth_codes where id = $1 and purpose = $2`,
    [challengeId, purpose],
  );
  const c = rows[0];
  if (!c || c.used || (owner && c.user_id !== owner)) return { ok: false as const, message: 'That code has been used or is not valid. Ask for a new one.' };
  if (c.expired) return { ok: false as const, message: 'That code has expired. Ask for a new one.' };
  if (c.attempts >= MAX_ATTEMPTS) return { ok: false as const, message: 'Too many wrong codes. Ask for a new one.' };
  const want = Buffer.from(c.code_hash);
  const got = Buffer.from(hash(app.config.jwtSecret, challengeId, code));
  if (want.length !== got.length || !timingSafeEqual(want, got)) {
    await app.db.query('update auth_codes set attempts = attempts + 1 where id = $1', [challengeId]);
    const left = MAX_ATTEMPTS - c.attempts - 1;
    return { ok: false as const, message: left > 0 ? `Wrong code. ${left} ${left === 1 ? 'try' : 'tries'} left.` : 'Too many wrong codes. Ask for a new one.' };
  }
  // Some flows need two codes right before spending either (see changing the email).
  if (opts.consume !== false) await app.db.query('update auth_codes set used_at = now() where id = $1', [challengeId]);
  return { ok: true as const, userId: c.user_id };
}

/**
 * Two-step sign-in by email code: finishing a sign-in, sending a new code, and turning it
 * on (which proves the email works) or off (with the password).
 */
export async function twoFactorRoutes(app: FastifyInstance) {
  const { db, config } = app;
  const limit = { config: { rateLimit: { max: config.authRateLimitPerMinute, timeWindow: '1 minute' } } };
  const codeBody = z.object({ challengeId: z.string().uuid(), code: z.string().trim().regex(/^\d{6}$/, 'enter the 6-digit code') });

  app.post('/auth/login/verify', limit, async (req, reply) => {
    const b = parse(codeBody, req.body, reply);
    if (!b) return;
    const r = await checkCode(app, b.challengeId, b.code, 'login');
    if (!r.ok) return reply.code(401).send({ error: 'bad_code', message: r.message });
    const { rows } = await db.query('select * from profiles where id = $1', [r.userId]);
    const user = rows[0];
    if (!user) return reply.code(401).send({ error: 'bad_code', message: 'Account not found.' });
    return { token: await startSession(app, user, req), user: profileJson(user) };
  });

  app.post('/auth/login/resend', limit, async (req, reply) => {
    const b = parse(z.object({ challengeId: z.string().uuid() }), req.body, reply);
    if (!b) return;
    const { rows } = await db.query<{ user_id: string; email: string; recent: boolean }>(
      `select c.user_id, p.email, c.sent_at > now() - make_interval(secs => $2) as recent
       from auth_codes c join profiles p on p.id = c.user_id
       where c.id = $1 and c.purpose = 'login' and c.used_at is null and c.sent_at > now() - interval '1 hour'`,
      [b.challengeId, RESEND_SECONDS],
    );
    const c = rows[0];
    if (!c) return reply.code(400).send({ error: 'bad_challenge', message: 'Start signing in again.' });
    if (c.recent) return reply.code(429).send({ error: 'too_soon', message: 'Wait a few seconds before asking for another code.' });
    await db.query('update auth_codes set used_at = now() where id = $1', [b.challengeId]);
    const next = await startChallenge(app, { id: c.user_id, email: c.email }, 'login');
    if ('error' in next) return reply.code(503).send(next);
    return next;
  });

  app.post('/me/2fa/start', { preHandler: requireAuth, ...limit }, async (req, reply) => {
    const { rows } = await db.query<{ id: string; email: string; two_factor: boolean }>(
      'select id, email, two_factor from profiles where id = $1',
      [userId(req)],
    );
    const me = rows[0]!;
    if (me.two_factor) return reply.code(409).send({ error: 'already_on', message: 'Two-step sign-in is already on.' });
    const c = await startChallenge(app, me, 'enable');
    if ('error' in c) return reply.code(503).send(c);
    return c;
  });

  app.post('/me/2fa/confirm', { preHandler: requireAuth, ...limit }, async (req, reply) => {
    const b = parse(codeBody, req.body, reply);
    if (!b) return;
    const r = await checkCode(app, b.challengeId, b.code, 'enable', userId(req) ?? undefined);
    if (!r.ok) return reply.code(400).send({ error: 'bad_code', message: r.message });
    const { rows } = await db.query(
      'update profiles set two_factor = true, email_verified_at = coalesce(email_verified_at, now()) where id = $1 returning *',
      [r.userId],
    );
    // The code proved the email, which can make a listed admin email admin.
    await syncAdminRole(app, rows[0]);
    return profileJson(rows[0]);
  });

  app.post('/me/2fa/disable', { preHandler: requireAuth, ...limit }, async (req, reply) => {
    const b = parse(z.object({ password: z.string().max(200) }), req.body, reply);
    if (!b) return;
    // Wrong passwords count toward the sign-in lock, so a stolen token can't guess it.
    const denied = await confirmPassword(app, userId(req)!, b.password, { status: 401, message: 'Wrong password.' });
    if (denied) return reply.code(denied.status).send(denied.body);
    const { rows: me } = await db.query('update profiles set two_factor = false where id = $1 returning *', [userId(req)]);
    return profileJson(me[0]);
  });

  // Old codes go after a day.
  const sweep = setInterval(() => {
    void db.query("delete from auth_codes where sent_at < now() - interval '1 day'").catch(() => {});
  }, 6 * 3600_000);
  sweep.unref();
  app.addHook('onClose', async () => clearInterval(sweep));
}
