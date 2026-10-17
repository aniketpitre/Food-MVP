import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { hashPassword, requireAuth, userId, verifyPassword } from '../auth.js';
import { deletePostImages } from '../media.js';
import { scheduleTrustRefresh } from '../ranking.js';

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

const loginSchema = z.object({
  email: z.string().trim().toLowerCase().max(254),
  password: z.string().max(200),
});

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
    createdAt: p.created_at.toISOString(),
  };
}

export async function authRoutes(app: FastifyInstance) {
  const { db, config } = app;
  const authLimit = { config: { rateLimit: { max: config.authRateLimitPerMinute, timeWindow: '1 minute' } } };

  app.post('/auth/register', authLimit, async (req, reply) => {
    const body = parse(registerSchema, req.body, reply);
    if (!body) return;
    const role = config.adminEmails.includes(body.email) ? 'admin' : 'user';
    try {
      const { rows } = await db.query<ProfileRow>(
        `insert into profiles (email, password_hash, handle, display_name, role)
         values ($1, $2, $3, $4, $5) returning *`,
        [body.email, await hashPassword(body.password), body.handle, body.displayName || null, role],
      );
      const user = rows[0]!;
      const token = app.jwt.sign({ sub: user.id, role: user.role });
      return reply.code(201).send({ token, user: profileJson(user) });
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
    const { rows } = await db.query<ProfileRow & { password_hash: string }>(
      'select * from profiles where email = $1',
      [body.email],
    );
    const user = rows[0];
    if (!user || !(await verifyPassword(body.password, user.password_hash))) {
      return reply.code(401).send({ error: 'bad_credentials', message: 'Wrong email or password.' });
    }
    // Promote or demote according to ADMIN_EMAILS at every sign-in.
    const role = config.adminEmails.includes(user.email) ? 'admin' : user.role === 'admin' ? 'user' : user.role;
    if (role !== user.role) {
      await db.query('update profiles set role = $2 where id = $1', [user.id, role]);
      user.role = role;
    }
    const token = app.jwt.sign({ sub: user.id, role: user.role });
    return { token, user: profileJson(user) };
  });

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
