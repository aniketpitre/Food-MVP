import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import type { FastifyRequest } from 'fastify';
import { randomUUID } from 'node:crypto';
import {
  burnPasswordCheck, forgetSessions, hashPassword, passwordProblem, requireAuth, sessionId, userId, verifyPassword,
} from '../auth.js';
import { noticeEmail } from '../mail.js';
import { deletePostImages } from '../media.js';
import { scheduleTrustRefresh } from '../ranking.js';
import { checkCode, emailHint, MAIL_OFF, startChallenge } from './twofactor.js';

const handleSchema = z
  .string()
  .trim()
  .toLowerCase()
  .regex(/^[a-z0-9_]{3,20}$/, 'use 3 to 20 letters, numbers or underscores');

const registerSchema = z.object({
  email: z.string().trim().toLowerCase().email().max(254),
  password: z.string().min(8, 'use at least 8 characters').max(200),
  handle: handleSchema,
  displayName: z.string().trim().max(40).optional(),
  acceptTerms: z.literal(true, 'you must accept the terms'),
});

// "login" is an email or a username; older app versions send "email".
const loginSchema = z
  .object({
    login: z.string().trim().toLowerCase().max(254).optional(),
    email: z.string().trim().toLowerCase().max(254).optional(),
    password: z.string().max(200),
  })
  .refine((b) => !!(b.login || b.email), 'enter your email or username');

/** Wrong passwords in a row before the account locks, and for how long. */
const LOCK_AFTER = 10;
const LOCK_MINUTES = 15;

/** A short name for the device a session is on ("Pixel 6 · Android 11"), from the app. */
function deviceName(req: FastifyRequest): string | null {
  const raw = req.headers['x-device-name'];
  const v = (Array.isArray(raw) ? raw[0] : raw)?.replace(/[^\p{L}\p{N} ._()·,\-]/gu, '').trim().slice(0, 80);
  return v || null;
}

/** Starts a sign-in session for [user] on this device and returns its token. */
export async function startSession(app: FastifyInstance, user: { id: string; role: string }, req: FastifyRequest): Promise<string> {
  const { rows } = await app.db.query<{ id: string }>(
    'insert into sessions (user_id, device) values ($1, $2) returning id',
    [user.id, deviceName(req)],
  );
  return app.jwt.sign({ sub: user.id, role: user.role, sid: rows[0]!.id });
}

/** Ends sessions: every one of [userId]'s, or all but [keep]. Older tokens stop too. */
export async function endSessions(app: FastifyInstance, uid: string, keep?: string | null) {
  const { rows } = await app.db.query<{ id: string }>(
    `update sessions set revoked_at = now()
     where user_id = $1 and revoked_at is null and ($2::uuid is null or id <> $2) returning id`,
    [uid, keep ?? null],
  );
  await app.db.query('update profiles set tokens_valid_after = now() where id = $1', [uid]);
  forgetSessions({ sessionIds: rows.map((r) => r.id), userId: uid });
}

interface ProfileRow {
  id: string;
  email: string;
  handle: string;
  display_name: string | null;
  role: string;
  home_city_id: number | null;
  bio?: string | null;
  avatar_path?: string | null;
  comment_policy?: string;
  dm_policy?: string;
  two_factor?: boolean;
  email_verified_at?: Date | null;
  created_at: Date;
}

export function profileJson(p: ProfileRow) {
  return {
    id: p.id,
    email: p.email,
    handle: p.handle,
    displayName: p.display_name,
    role: p.role,
    homeCityId: p.home_city_id,
    bio: p.bio ?? null,
    avatarUrl: p.avatar_path ?? null,
    commentPolicy: p.comment_policy ?? 'everyone',
    dmPolicy: p.dm_policy ?? 'everyone',
    twoFactor: p.two_factor ?? false,
    emailVerified: !!p.email_verified_at,
    createdAt: p.created_at.toISOString(),
  };
}

export async function authRoutes(app: FastifyInstance) {
  const { db, config } = app;
  const authLimit = { config: { rateLimit: { max: config.authRateLimitPerMinute, timeWindow: '1 minute' } } };

  app.post('/auth/register', authLimit, async (req, reply) => {
    const body = parse(registerSchema, req.body, reply);
    if (!body) return;
    const weak = passwordProblem(body.password, [body.handle, body.email]);
    if (weak) return reply.code(400).send({ error: 'weak_password', message: weak });
    const role = config.adminEmails.includes(body.email) ? 'admin' : 'user';
    try {
      const { rows } = await db.query<ProfileRow>(
        `insert into profiles (email, password_hash, handle, display_name, role)
         values ($1, $2, $3, $4, $5) returning *`,
        [body.email, await hashPassword(body.password), body.handle, body.displayName || null, role],
      );
      const user = rows[0]!;
      const token = await startSession(app, user, req);
      // A code to confirm the email is theirs (they can do it later from Settings).
      const verify = app.mailer.enabled ? await startChallenge(app, user, 'verify', { background: true }) : null;
      return reply.code(201).send({ token, user: profileJson(user), verify: verify && 'challengeId' in verify ? verify : null });
    } catch (err) {
      if ((err as { code?: string }).code === '23505') {
        const detail = String((err as { detail?: string }).detail ?? '');
        const field = detail.includes('handle') ? 'handle' : 'email';
        const label = field === 'handle' ? 'username' : 'email';
        return reply.code(409).send({ error: `${field}_taken`, message: `That ${label} is already in use.` });
      }
      throw err;
    }
  });

  app.post('/auth/login', authLimit, async (req, reply) => {
    const body = parse(loginSchema, req.body, reply);
    if (!body) return;
    const login = (body.login || body.email)!.replace(/^@/, '');
    const { rows } = await db.query<ProfileRow & { password_hash: string; locked: boolean; failed_logins: number }>(
      `select *, coalesce(locked_until > now(), false) as locked from profiles
       where ${login.includes('@') ? 'email' : 'handle'} = $1`,
      [login],
    );
    const user = rows[0];
    const wrong = { error: 'bad_credentials', message: 'Wrong email, username or password.' };
    if (!user) {
      await burnPasswordCheck(body.password);
      return reply.code(401).send(wrong);
    }
    if (user.locked) {
      await burnPasswordCheck(body.password);
      return reply.code(429).send({
        error: 'locked',
        message: `Too many wrong passwords. Try again in ${LOCK_MINUTES} minutes, or reset your password.`,
      });
    }
    if (!(await verifyPassword(body.password, user.password_hash))) {
      await db.query(
        `update profiles set failed_logins = failed_logins + 1,
           locked_until = case when failed_logins + 1 >= $2 then now() + make_interval(mins => $3) else locked_until end
         where id = $1`,
        [user.id, LOCK_AFTER, LOCK_MINUTES],
      );
      return reply.code(401).send(wrong);
    }
    if (user.failed_logins > 0) await db.query('update profiles set failed_logins = 0, locked_until = null where id = $1', [user.id]);
    // Promote or demote according to ADMIN_EMAILS at every sign-in.
    const role = config.adminEmails.includes(user.email) ? 'admin' : user.role === 'admin' ? 'user' : user.role;
    if (role !== user.role) {
      await db.query('update profiles set role = $2 where id = $1', [user.id, role]);
      user.role = role;
    }
    // Two-step sign-in: no token yet; a code goes to their email first.
    if (user.two_factor) {
      const challenge = await startChallenge(app, user, 'login');
      if ('error' in challenge) return reply.code(503).send(challenge);
      return { twoFactor: true, ...challenge };
    }
    const token = await startSession(app, user, req);
    return { token, user: profileJson(user) };
  });

  // ---------- Sessions: sign out, devices, sign out everywhere ----------

  app.post('/auth/logout', { preHandler: requireAuth }, async (req) => {
    const sid = sessionId(req);
    if (sid) {
      await db.query('update sessions set revoked_at = now() where id = $1 and revoked_at is null', [sid]);
      forgetSessions({ sessionIds: [sid] });
    }
    return { ok: true };
  });

  app.get('/me/sessions', { preHandler: requireAuth }, async (req) => {
    const { rows } = await db.query(
      `select id, device, created_at as "createdAt", last_seen_at as "lastSeenAt", id = $2 as current
       from sessions where user_id = $1 and revoked_at is null
       order by (id = $2) desc, last_seen_at desc limit 50`,
      [userId(req), sessionId(req)],
    );
    return { items: rows };
  });

  app.delete('/me/sessions/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    if (!p) return;
    const { rowCount } = await db.query(
      'update sessions set revoked_at = now() where id = $1 and user_id = $2 and revoked_at is null',
      [p.id, userId(req)],
    );
    forgetSessions({ sessionIds: [p.id] });
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'That device is already signed out.' });
    return { ok: true };
  });

  app.post('/me/sessions/end-others', { preHandler: requireAuth }, async (req) => {
    await endSessions(app, userId(req)!, sessionId(req));
    return { ok: true };
  });

  // ---------- Signing up: is a username free, confirm the email ----------

  app.get('/auth/handle-available', authLimit, async (req, reply) => {
    const q = parse(z.object({ handle: z.string().max(40) }), req.query, reply);
    if (!q) return;
    const h = q.handle.trim().toLowerCase();
    if (!/^[a-z0-9_]{3,20}$/.test(h)) return { available: false, reason: 'Use 3 to 20 letters, numbers or _.' };
    const { rowCount } = await db.query('select 1 from profiles where handle = $1', [h]);
    return rowCount ? { available: false, reason: 'That username is taken.' } : { available: true };
  });

  app.post('/me/email/verify/start', { preHandler: requireAuth, ...authLimit }, async (req, reply) => {
    const { rows } = await db.query<{ id: string; email: string; email_verified_at: Date | null }>(
      'select id, email, email_verified_at from profiles where id = $1',
      [userId(req)],
    );
    if (rows[0]!.email_verified_at) return reply.code(409).send({ error: 'already_verified', message: 'Your email is already verified.' });
    const c = await startChallenge(app, rows[0]!, 'verify');
    if ('error' in c) return reply.code(503).send(c);
    return c;
  });

  app.post('/me/email/verify/confirm', { preHandler: requireAuth, ...authLimit }, async (req, reply) => {
    const b = parse(z.object({ challengeId: z.string().uuid(), code: z.string().trim().regex(/^\d{6}$/, 'enter the 6-digit code') }), req.body, reply);
    if (!b) return;
    const r = await checkCode(app, b.challengeId, b.code, 'verify', userId(req) ?? undefined);
    if (!r.ok) return reply.code(400).send({ error: 'bad_code', message: r.message });
    const { rows } = await db.query('update profiles set email_verified_at = coalesce(email_verified_at, now()) where id = $1 returning *', [r.userId]);
    return profileJson(rows[0]);
  });

  // ---------- Changing the email ----------

  // Step 1: the password (it's really you), then a code to the old address (you own the
  // account's mailbox) and one to the new (the new address is real and yours).
  app.post('/me/email/change/start', { preHandler: requireAuth, ...authLimit }, async (req, reply) => {
    const b = parse(z.object({ newEmail: z.string().trim().toLowerCase().email('enter a valid email').max(254), password: z.string().max(200) }), req.body, reply);
    if (!b) return;
    const uid = userId(req)!;
    const { rows } = await db.query<{ id: string; email: string; password_hash: string; locked: boolean; recent: boolean }>(
      `select id, email, password_hash, coalesce(locked_until > now(), false) as locked,
              exists (select 1 from auth_codes c where c.user_id = profiles.id and c.purpose = 'change_new'
                        and c.sent_at > now() - interval '30 seconds') as recent
       from profiles where id = $1`,
      [uid],
    );
    const me = rows[0]!;
    if (me.locked) return reply.code(429).send({ error: 'locked', message: `Too many wrong passwords. Try again in ${LOCK_MINUTES} minutes.` });
    if (!(await verifyPassword(b.password, me.password_hash))) {
      // Wrong passwords here count toward the same lock as signing in.
      await db.query(
        `update profiles set failed_logins = failed_logins + 1,
           locked_until = case when failed_logins + 1 >= $2 then now() + make_interval(mins => $3) else locked_until end
         where id = $1`,
        [uid, LOCK_AFTER, LOCK_MINUTES],
      );
      return reply.code(400).send({ error: 'bad_credentials', message: 'Your password is wrong.' });
    }
    if (b.newEmail === me.email) return reply.code(400).send({ error: 'same_email', message: "That's already your email." });
    const taken = await db.query('select 1 from profiles where email = $1', [b.newEmail]);
    if (taken.rowCount) return reply.code(409).send({ error: 'email_taken', message: 'Another account already uses that email.' });
    if (!app.mailer.enabled) return reply.code(503).send(MAIL_OFF);
    if (me.recent) return reply.code(429).send({ error: 'too_soon', message: 'Wait a few seconds before asking for new codes.' });
    // Any earlier unfinished change stops here.
    await db.query(
      "update auth_codes set used_at = now() where user_id = $1 and purpose in ('change_old', 'change_new') and used_at is null",
      [uid],
    );
    const old = await startChallenge(app, me, 'change_old', { newEmail: b.newEmail });
    if ('error' in old) return reply.code(503).send(old);
    const fresh = await startChallenge(app, me, 'change_new', { to: b.newEmail, newEmail: b.newEmail });
    if ('error' in fresh) {
      await db.query('update auth_codes set used_at = now() where id = $1', [old.challengeId]);
      return reply.code(503).send(fresh);
    }
    return { oldChallengeId: old.challengeId, oldHint: old.emailHint, newChallengeId: fresh.challengeId, newHint: fresh.emailHint };
  });

  // Step 2: both codes right, then the switch. Other devices are signed out and the old
  // address hears about it.
  app.post('/me/email/change/confirm', { preHandler: requireAuth, ...authLimit }, async (req, reply) => {
    const code = z.string().trim().regex(/^\d{6}$/, 'enter the 6-digit code');
    const b = parse(
      z.object({ oldChallengeId: z.string().uuid(), oldCode: code, newChallengeId: z.string().uuid(), newCode: code }),
      req.body,
      reply,
    );
    if (!b) return;
    const uid = userId(req)!;
    const { rows: pair } = await db.query<{ purpose: string; new_email: string | null }>(
      `select purpose, new_email from auth_codes
       where user_id = $1 and ((id = $2 and purpose = 'change_old') or (id = $3 and purpose = 'change_new'))`,
      [uid, b.oldChallengeId, b.newChallengeId],
    );
    const newEmail = pair.find((p) => p.purpose === 'change_new')?.new_email;
    if (pair.length !== 2 || !newEmail || pair.some((p) => p.new_email !== newEmail)) {
      return reply.code(400).send({ error: 'bad_code', message: 'Those codes are not for this change. Start again.' });
    }
    const oldOk = await checkCode(app, b.oldChallengeId, b.oldCode, 'change_old', uid, { consume: false });
    if (!oldOk.ok) return reply.code(400).send({ error: 'bad_code', field: 'old', message: `Current email: ${oldOk.message}` });
    const newOk = await checkCode(app, b.newChallengeId, b.newCode, 'change_new', uid, { consume: false });
    if (!newOk.ok) return reply.code(400).send({ error: 'bad_code', field: 'new', message: `New email: ${newOk.message}` });
    await db.query('update auth_codes set used_at = now() where id = any($1::uuid[])', [[b.oldChallengeId, b.newChallengeId]]);
    const { rows: before } = await db.query<{ email: string }>('select email from profiles where id = $1', [uid]);
    let user: ProfileRow;
    try {
      const { rows } = await db.query<ProfileRow>(
        'update profiles set email = $2, email_verified_at = now() where id = $1 returning *',
        [uid, newEmail],
      );
      user = rows[0]!;
    } catch (err) {
      if ((err as { code?: string }).code === '23505') {
        return reply.code(409).send({ error: 'email_taken', message: 'Another account started using that email. Try a different one.' });
      }
      throw err;
    }
    await endSessions(app, uid, sessionId(req));
    void sendNotice(before[0]!.email, 'The email on your WorthAbite account was changed', [
      `The email on @${user.handle} was changed to ${emailHint(newEmail)}, and other devices were signed out.`,
      `If this wasn't you, write to ${config.contactEmail} right away so we can lock the account.`,
    ]);
    // A sign-in from before sessions existed gets a fresh token (old ones just ended).
    return { user: profileJson(user), token: sessionId(req) ? undefined : await startSession(app, user, req) };
  });

  // ---------- Forgot password, username or email ----------

  // A code to the account's email, found by email or username. The answer is the same
  // whether or not the account exists, so this can't be used to find out who has one.
  app.post('/auth/password/forgot', authLimit, async (req, reply) => {
    const b = parse(z.object({ login: z.string().trim().toLowerCase().max(254).min(1, 'enter your email or username') }), req.body, reply);
    if (!b) return;
    if (!app.mailer.enabled) return reply.code(503).send(MAIL_OFF);
    const login = b.login.replace(/^@/, '');
    const { rows } = await db.query<{ id: string; email: string; recent: boolean }>(
      `select p.id, p.email,
              exists (select 1 from auth_codes c where c.user_id = p.id and c.purpose = 'reset' and c.sent_at > now() - interval '30 seconds') as recent
       from profiles p where ${login.includes('@') ? 'p.email' : 'p.handle'} = $1`,
      [login],
    );
    const u = rows[0];
    const sent = { challengeId: randomUUID(), message: 'If an account matches, we emailed it a 6-digit code.' };
    if (!u || u.recent) return sent;
    const c = await startChallenge(app, u, 'reset', { background: true });
    return 'challengeId' in c ? { ...sent, challengeId: c.challengeId } : sent;
  });

  app.post('/auth/password/reset', authLimit, async (req, reply) => {
    const b = parse(
      z.object({ challengeId: z.string().uuid(), code: z.string().trim().regex(/^\d{6}$/, 'enter the 6-digit code'), password: z.string().max(200) }),
      req.body,
      reply,
    );
    if (!b) return;
    // Check the new password first, so a weak one doesn't use up the code.
    const { rows: who } = await db.query<{ handle: string; email: string }>(
      'select p.handle, p.email from auth_codes c join profiles p on p.id = c.user_id where c.id = $1',
      [b.challengeId],
    );
    const weak = passwordProblem(b.password, [who[0]?.handle, who[0]?.email]);
    if (weak) return reply.code(400).send({ error: 'weak_password', message: weak });
    const r = await checkCode(app, b.challengeId, b.code, 'reset');
    if (!r.ok) return reply.code(401).send({ error: 'bad_code', message: r.message });
    const { rows } = await db.query<ProfileRow>(
      `update profiles set password_hash = $2, failed_logins = 0, locked_until = null,
              email_verified_at = coalesce(email_verified_at, now())
       where id = $1 returning *`,
      [r.userId, await hashPassword(b.password)],
    );
    const user = rows[0]!;
    // A new password signs out every device (whoever knew the old one).
    await endSessions(app, user.id);
    void sendNotice(user.email, 'Your WorthAbite password was changed', [
      `The password for @${user.handle} was just reset, and every device was signed out.`,
      "If this wasn't you, reset your password again right away from the sign-in screen.",
    ]);
    return { token: await startSession(app, user, req), user: profileJson(user) };
  });

  // Emails the username(s) on an email address. Same answer either way.
  app.post('/auth/username/forgot', authLimit, async (req, reply) => {
    const b = parse(z.object({ email: z.string().trim().toLowerCase().email().max(254) }), req.body, reply);
    if (!b) return;
    if (!app.mailer.enabled) return reply.code(503).send(MAIL_OFF);
    const { rows } = await db.query<{ handle: string }>('select handle from profiles where email = $1', [b.email]);
    if (rows[0]) {
      void sendNotice(b.email, 'Your WorthAbite username', [
        `Your WorthAbite username is @${rows[0].handle}.`,
        'You can sign in with it or with this email address.',
      ]);
    }
    return { ok: true, message: 'If an account uses that email, we sent its username there.' };
  });

  // Which email an account uses, partly hidden (usernames are public; emails aren't).
  app.post('/auth/email/forgot', authLimit, async (req, reply) => {
    const b = parse(z.object({ handle: z.string().trim().toLowerCase().max(40) }), req.body, reply);
    if (!b) return;
    const { rows } = await db.query<{ email: string }>('select email from profiles where handle = $1', [b.handle.replace(/^@/, '')]);
    if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'No account has that username.' });
    return { emailHint: emailHint(rows[0].email) };
  });

  async function sendNotice(to: string, subject: string, lines: string[]) {
    if (!app.mailer.enabled) return;
    const m = noticeEmail(subject, lines);
    await app.mailer.send(to, m.subject, m.text, m.html).catch((err) => app.log.warn({ err }, 'notice email failed'));
  }

  app.get('/me', { preHandler: requireAuth }, async (req, reply) => {
    const { rows } = await db.query<ProfileRow>('select * from profiles where id = $1', [userId(req)]);
    if (!rows[0]) return reply.code(401).send({ error: 'sign_in_required', message: 'Account not found.' });
    return profileJson(rows[0]);
  });

  app.patch('/me', { preHandler: requireAuth }, async (req, reply) => {
    const body = parse(
      z.object({
        displayName: z.string().trim().max(40).nullable().optional(),
        homeCityId: z.number().int().positive().nullable().optional(),
      }),
      req.body,
      reply,
    );
    if (!body) return;
    const { rows } = await db.query<ProfileRow>(
      `update profiles set
         display_name = case when $2 then $3 else display_name end,
         home_city_id = case when $4 then $5 else home_city_id end
       where id = $1 returning *`,
      [userId(req), body.displayName !== undefined, body.displayName ?? null, body.homeCityId !== undefined, body.homeCityId ?? null],
    );
    return profileJson(rows[0]!);
  });

  // Account deletion (Play Store requirement). Removes the profile, the person's own
  // photos and every action they took. Dishes and places they added stay, because
  // other people's photos and recommendations may be attached to them.
  app.delete('/me', { preHandler: requireAuth }, async (req) => {
    const id = userId(req)!;
    const { rows: posts } = await db.query<{ id: string; dish_id: string }>(
      'select id, dish_id from posts where author_id = $1',
      [id],
    );
    const { rows: touched } = await db.query<{ dish_id: string }>(
      'select distinct dish_id from engagements where user_id = $1',
      [id],
    );
    await db.query('delete from profiles where id = $1', [id]);
    forgetSessions({ sessionIds: sessionId(req) ? [sessionId(req)!] : [], userId: id });
    for (const p of posts) await deletePostImages(config.mediaDir, p.id);
    const dishes = new Set([...touched.map((t) => t.dish_id), ...posts.map((p) => p.dish_id)]);
    for (const dishId of dishes) {
      await db.query('select refresh_dish_stats($1)', [dishId]);
      await db.query('select refresh_people_pick($1)', [dishId]);
    }
    // Their votes also shaped other people's trust; rebuild it (once, in the background).
    scheduleTrustRefresh(db, 3000, (err) => app.log.error(err));
    return { ok: true };
  });
}
