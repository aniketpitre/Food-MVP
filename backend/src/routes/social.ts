import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { hashPassword, optionalAuth, requireAuth, userId, verifyPassword } from '../auth.js';
import { loadDishCards, wholePicks } from '../cards.js';
import { deleteAvatar, storeAvatar } from '../media.js';
import { notify } from '../notify.js';

const username = z.string().trim().toLowerCase().max(20);

/** A person as shown in lists: avatar, names, and whether the viewer follows them. */
const personSql = (alias: string) => `
  ${alias}.handle, ${alias}.display_name as "displayName", ${alias}.avatar_path as "avatarUrl"`;

/**
 * Instagram-style social layer: public profiles with photo, bio and counts; follow and
 * unfollow; follower and following lists; the Activity inbox; people search; and the
 * account settings that go with them (username, password, comment controls, blocked list).
 */
export async function socialRoutes(app: FastifyInstance) {
  const { db, config } = app;

  async function profileId(handle: string): Promise<string | null> {
    const { rows } = await db.query<{ id: string }>('select id from profiles where handle = $1', [handle.toLowerCase()]);
    return rows[0]?.id ?? null;
  }

  // ---------- Profiles ----------

  app.get('/users/:handle', { preHandler: optionalAuth }, async (req, reply) => {
    const p = parse(z.object({ handle: username }), req.params, reply);
    if (!p) return;
    const viewer = userId(req);
    const { rows } = await db.query(
      `select id, handle, display_name as "displayName", bio, avatar_path as "avatarUrl", role, created_at as "createdAt",
              comment_policy as "commentPolicy",
              (select count(*) from posts where author_id = profiles.id and status = 'live')::int as posts,
              (select count(*) from engagements where user_id = profiles.id and kind = 'recommend')::int as recommendations,
              (select count(*) from follows where followee_id = profiles.id)::int as followers,
              (select count(*) from follows where follower_id = profiles.id)::int as following,
              ($2::uuid is not null and exists (select 1 from follows where follower_id = $2 and followee_id = profiles.id)) as "isFollowing",
              ($2::uuid is not null and exists (select 1 from follows where follower_id = profiles.id and followee_id = $2)) as "followsYou",
              ($2::uuid is not null and exists (select 1 from blocks where blocker_id = $2 and blocked_id = profiles.id)) as "blockedByMe"
       from profiles where handle = $1`,
      [p.handle, viewer],
    );
    const u = rows[0];
    if (!u) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    const isMe = viewer === u.id;
    const { rows: posts } = await db.query(
      `select p.id, p.dish_id as "dishId", p.thumb_path as "thumbUrl", p.placeholder_color as "placeholderColor",
              p.status, d.name as "dishName", r.name as "restaurantName", r.status as "restaurantStatus"
       from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
       where p.author_id = $1 and ($2 or (p.status = 'live' and d.status = 'live' and r.status = 'active'))
       order by p.created_at desc limit 60`,
      [u.id, isMe],
    );
    // Taste badges: dish types where this person's picks were backed by others.
    const { rows: badges } = await db.query(
      `select c.name as category, c.emoji, t.picks, t.backers, t.trust, t.expert
       from taster_stats t join dish_categories c on c.id = t.category_id
       where t.user_id = $1 and t.picks >= 0.5 order by t.trust desc, t.backers desc, c.id limit 6`,
      [u.id],
    );
    const tasteBadges = badges.map((b) => ({
      category: b.category,
      emoji: b.emoji,
      picks: wholePicks(b.picks),
      backers: b.backers,
      isExpert: b.expert,
    }));
    delete u.id;
    return { ...u, isMe, posts, tasteBadges };
  });

  // Dishes someone loved, newest first: the "Loved" tab on a profile.
  app.get('/users/:handle/loved', { preHandler: optionalAuth }, async (req, reply) => {
    const p = parse(z.object({ handle: username }), req.params, reply);
    if (!p) return;
    const id = await profileId(p.handle);
    if (!id) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    const { rows } = await db.query<{ dish_id: string }>(
      `select e.dish_id from engagements e join dishes d on d.id = e.dish_id
       where e.user_id = $1 and e.kind = 'recommend' and d.status = 'live'
       order by e.created_at desc limit 60`,
      [id],
    );
    return { items: await loadDishCards(db, rows.map((r) => r.dish_id), userId(req)) };
  });

  // Edit profile: name, username, bio and who may comment on your posts.
  app.patch('/me/profile', { preHandler: requireAuth }, async (req, reply) => {
    const body = parse(
      z.object({
        displayName: z.string().trim().max(40).nullable().optional(),
        username: z.string().trim().toLowerCase().regex(/^[a-z0-9_]{3,20}$/, 'use 3 to 20 letters, numbers or underscores').optional(),
        bio: z.string().trim().max(160).nullable().optional(),
        commentPolicy: z.enum(['everyone', 'followers', 'off']).optional(),
        dmPolicy: z.enum(['everyone', 'following', 'off']).optional(),
      }),
      req.body,
      reply,
    );
    if (!body) return;
    const me = userId(req)!;
    try {
      await db.query(
        `update profiles set
           display_name   = case when $2 then nullif($3, '') else display_name end,
           handle         = coalesce($4, handle),
           bio            = case when $5 then nullif($6, '') else bio end,
           comment_policy = coalesce($7, comment_policy),
           dm_policy      = coalesce($8, dm_policy)
         where id = $1`,
        [me, body.displayName !== undefined, body.displayName ?? null, body.username ?? null,
          body.bio !== undefined, body.bio ?? null, body.commentPolicy ?? null, body.dmPolicy ?? null],
      );
    } catch (err) {
      if ((err as { code?: string }).code === '23505') {
        return reply.code(409).send({ error: 'username_taken', message: 'That username is taken.' });
      }
      throw err;
    }
    return meJson(me);
  });

  app.post('/me/avatar', { preHandler: requireAuth, config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req, reply) => {
    const me = userId(req)!;
    let image: Buffer | undefined;
    for await (const part of req.parts()) {
      if (part.type === 'file' && part.fieldname === 'image') image = await part.toBuffer();
      else if (part.type === 'file') part.file.resume();
    }
    if (!image) return reply.code(400).send({ error: 'invalid_input', message: 'Choose a photo.' });
    let url: string;
    try {
      url = await storeAvatar(config.mediaDir, me, image);
    } catch {
      return reply.code(400).send({ error: 'bad_image', message: 'That file is not a photo we can read.' });
    }
    const { rows } = await db.query<{ old: string | null }>(
      'update profiles p set avatar_path = $2 from (select avatar_path as old from profiles where id = $1) o where p.id = $1 returning o.old',
      [me, url],
    );
    await deleteAvatar(config.mediaDir, rows[0]?.old ?? null);
    return meJson(me);
  });

  app.delete('/me/avatar', { preHandler: requireAuth }, async (req) => {
    const me = userId(req)!;
    const { rows } = await db.query<{ old: string | null }>(
      'update profiles p set avatar_path = null from (select avatar_path as old from profiles where id = $1) o where p.id = $1 returning o.old',
      [me],
    );
    await deleteAvatar(config.mediaDir, rows[0]?.old ?? null);
    return meJson(me);
  });

  app.post('/me/password', { preHandler: requireAuth, config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req, reply) => {
    const body = parse(
      z.object({ current: z.string().max(200), next: z.string().min(8, 'use at least 8 characters').max(200) }),
      req.body,
      reply,
    );
    if (!body) return;
    const me = userId(req)!;
    const { rows } = await db.query<{ password_hash: string }>('select password_hash from profiles where id = $1', [me]);
    if (!rows[0] || !(await verifyPassword(body.current, rows[0].password_hash))) {
      return reply.code(400).send({ error: 'bad_credentials', message: 'Your current password is wrong.' });
    }
    await db.query('update profiles set password_hash = $2 where id = $1', [me, await hashPassword(body.next)]);
    return { ok: true };
  });

  app.get('/me/blocked', { preHandler: requireAuth }, async (req) => {
    const { rows } = await db.query(
      `select ${personSql('p')} from blocks b join profiles p on p.id = b.blocked_id
       where b.blocker_id = $1 order by b.created_at desc`,
      [userId(req)],
    );
    return rows;
  });

  async function meJson(id: string) {
    const { rows } = await db.query(
      `select id, email, handle, display_name as "displayName", bio, avatar_path as "avatarUrl", role,
              home_city_id as "homeCityId", comment_policy as "commentPolicy", created_at as "createdAt"
       from profiles where id = $1`,
      [id],
    );
    return rows[0];
  }

  // ---------- Follow ----------

  app.put('/users/:handle/follow', { preHandler: requireAuth, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (req, reply) => {
    const p = parse(z.object({ handle: username }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    const target = await profileId(p.handle);
    if (!target) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    if (target === me) return reply.code(400).send({ error: 'invalid_input', message: "You can't follow yourself." });
    const { rowCount: blocked } = await db.query(
      'select 1 from blocks where (blocker_id = $1 and blocked_id = $2) or (blocker_id = $2 and blocked_id = $1)',
      [me, target],
    );
    if (blocked) return reply.code(403).send({ error: 'forbidden', message: "You can't follow this person." });
    await db.query('insert into follows (follower_id, followee_id) values ($1, $2) on conflict do nothing', [me, target]);
    await notify(db, { userId: target, actorId: me, kind: 'follow' });
    return followState(me, target);
  });

  app.delete('/users/:handle/follow', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ handle: username }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    const target = await profileId(p.handle);
    if (!target) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    await db.query('delete from follows where follower_id = $1 and followee_id = $2', [me, target]);
    return followState(me, target);
  });

  async function followState(me: string, target: string) {
    const { rows } = await db.query(
      `select exists (select 1 from follows where follower_id = $1 and followee_id = $2) as "isFollowing",
              (select count(*) from follows where followee_id = $2)::int as followers`,
      [me, target],
    );
    return rows[0];
  }

  for (const which of ['followers', 'following'] as const) {
    app.get(`/users/:handle/${which}`, { preHandler: optionalAuth }, async (req, reply) => {
      const p = parse(z.object({ handle: username }), req.params, reply);
      if (!p) return;
      const id = await profileId(p.handle);
      if (!id) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
      const viewer = userId(req);
      const [mine, theirs] = which === 'followers' ? ['followee_id', 'follower_id'] : ['follower_id', 'followee_id'];
      const { rows } = await db.query(
        `select ${personSql('p')},
                ($2::uuid is not null and exists (select 1 from follows x where x.follower_id = $2 and x.followee_id = p.id)) as "isFollowing",
                (p.id = $2) as "isMe"
         from follows f join profiles p on p.id = f.${theirs}
         where f.${mine} = $1
           and ($2::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = p.id))
         order by f.created_at desc limit 300`,
        [id, viewer],
      );
      return rows;
    });
  }

  // ---------- People search ----------

  app.get('/people', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(z.object({ q: z.string().trim().min(1).max(40) }), req.query, reply);
    if (!q) return;
    const viewer = userId(req);
    const term = q.q.replace(/^@/, '').toLowerCase();
    const { rows } = await db.query(
      `select ${personSql('p')},
              ($2::uuid is not null and exists (select 1 from follows x where x.follower_id = $2 and x.followee_id = p.id)) as "isFollowing",
              (p.id = $2::uuid) is true as "isMe",
              (select count(*) from follows where followee_id = p.id)::int as followers
       from profiles p
       where ($2::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = p.id))
         and (p.handle like $1 || '%' or p.handle % $1 or lower(coalesce(p.display_name, '')) like '%' || $1 || '%')
       order by (p.handle = $1) desc, (p.handle like $1 || '%') desc, similarity(p.handle, $1) desc
       limit 20`,
      [term, viewer],
    );
    return rows;
  });

  // ---------- Activity ----------

  app.get('/notifications', { preHandler: requireAuth }, async (req, reply) => {
    const q = parse(z.object({ before: z.coerce.number().int().positive().optional() }), req.query, reply);
    if (!q) return;
    const me = userId(req)!;
    const { rows } = await db.query(
      `select n.id, n.kind, n.created_at as "createdAt", (n.read_at is not null) as read,
              json_build_object('handle', a.handle, 'displayName', a.display_name, 'avatarUrl', a.avatar_path) as actor,
              case when d.id is null then null else json_build_object('id', d.id, 'name', d.name,
                'thumbUrl', (select p.thumb_path from posts p where p.dish_id = d.id and p.status = 'live'
                             order by (p.id = d.cover_post_id) desc, p.created_at desc limit 1)) end as dish,
              n.post_id as "postId",
              c.body as "commentBody",
              ($1::uuid is not null and exists (select 1 from follows x where x.follower_id = $1 and x.followee_id = a.id)) as "followingActor"
       from notifications n
       join profiles a on a.id = n.actor_id
       left join dishes d on d.id = coalesce(n.dish_id, (select po.dish_id from posts po where po.id = n.post_id))
       left join comments c on c.id = n.comment_id and c.status = 'live'
       where n.user_id = $1 and ($2::bigint is null or n.id < $2)
         and not exists (select 1 from blocks b where b.blocker_id = $1 and b.blocked_id = n.actor_id)
       order by n.id desc limit 50`,
      [me, q.before ?? null],
    );
    return { items: rows.map((r) => ({ ...r, id: Number(r.id) })) };
  });

  app.get('/notifications/unread', { preHandler: requireAuth }, async (req) => {
    const { rows } = await db.query<{ n: number }>(
      'select count(*)::int as n from notifications where user_id = $1 and read_at is null',
      [userId(req)],
    );
    return { count: rows[0]!.n };
  });

  app.post('/notifications/read', { preHandler: requireAuth }, async (req) => {
    await db.query('update notifications set read_at = now() where user_id = $1 and read_at is null', [userId(req)]);
    return { ok: true };
  });
}
