import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, requireAuth, userId } from '../auth.js';
import type { Db } from '../db.js';
import { deleteImages, storeImage } from '../media.js';

const uuid = z.string().uuid();

/** Live stories with their dish/place tag, grouped per person, for a set of people. */
async function storiesFor(db: Pick<Db, 'query'>, userIds: string[], viewer: string | null) {
  if (userIds.length === 0) return new Map<string, unknown[]>();
  const { rows } = await db.query(
    `select s.id, s.user_id, s.image_path as "imageUrl", s.thumb_path as "thumbUrl", s.placeholder_color as "placeholderColor",
            s.caption, s.created_at as "createdAt", s.expires_at as "expiresAt",
            ($2::uuid is not null and exists (select 1 from story_views v where v.story_id = s.id and v.viewer_id = $2)) as seen,
            ($2::uuid is not null and exists (select 1 from story_views v where v.story_id = s.id and v.viewer_id = $2 and v.liked)) as liked,
            case when s.user_id = $2 then (select count(*)::int from story_views v where v.story_id = s.id) end as "viewCount",
            case when d.id is not null then json_build_object('id', d.id, 'name', d.name, 'restaurantName', dr.name) end as dish,
            case when r.id is not null then json_build_object('id', r.id, 'name', r.name) end as place
     from stories s
     left join dishes d on d.id = s.dish_id and d.status = 'live'
     left join restaurants dr on dr.id = d.restaurant_id
     left join restaurants r on r.id = coalesce(s.restaurant_id, d.restaurant_id) and r.status = 'active'
     where s.user_id = any($1) and s.expires_at > now()
     order by s.created_at`,
    [userIds, viewer],
  );
  const out = new Map<string, unknown[]>();
  for (const r of rows) {
    const uid = r.user_id as string;
    delete r.user_id;
    if (!out.has(uid)) out.set(uid, []);
    out.get(uid)!.push(r);
  }
  return out;
}

/**
 * 24-hour stories, Instagram style: a photo with a caption and an optional dish or place
 * tag. The tray shows you first, then people you follow (unseen first), then others in
 * your city so a new account still has something to watch. Viewers can like a story or
 * reply, which arrives as a direct message.
 */
export async function storyRoutes(app: FastifyInstance) {
  const { db, config } = app;

  app.get('/stories', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(z.object({ city: z.coerce.number().int().positive().optional() }), req.query, reply);
    if (!q) return;
    const viewer = userId(req);
    const { rows: people } = await db.query<{ id: string; handle: string; displayName: string | null; avatarUrl: string | null; latest: Date; followed: boolean; allSeen: boolean }>(
      `select p.id, p.handle, p.display_name as "displayName", p.avatar_path as "avatarUrl",
              max(s.created_at) as latest,
              ($1::uuid is not null and exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = p.id)) as followed,
              ($1::uuid is not null and bool_and(exists (select 1 from story_views v where v.story_id = s.id and v.viewer_id = $1))) as "allSeen"
       from stories s join profiles p on p.id = s.user_id
       where s.expires_at > now()
         and (s.user_id = $1
              or exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = s.user_id)
              or ($2::int is not null and s.city_id = $2))
         and ($1::uuid is null or not exists (select 1 from blocks b where (b.blocker_id = $1 and b.blocked_id = p.id)
                                                                     or (b.blocker_id = p.id and b.blocked_id = $1)))
       group by p.id
       order by (p.id = $1) desc, bool_and(exists (select 1 from story_views v where v.story_id = s.id and v.viewer_id = $1)) asc,
                ($1::uuid is not null and exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = p.id)) desc,
                max(s.created_at) desc
       limit 30`,
      [viewer, q.city ?? null],
    );
    const stories = await storiesFor(db, people.map((p) => p.id), viewer);
    return {
      items: people.map((p) => ({
        user: { handle: p.handle, displayName: p.displayName, avatarUrl: p.avatarUrl },
        isMe: p.id === viewer,
        allSeen: !!p.allSeen,
        stories: stories.get(p.id) ?? [],
      })),
    };
  });

  // One person's live stories (the ring on their profile).
  app.get('/users/:handle/stories', { preHandler: optionalAuth }, async (req, reply) => {
    const p = parse(z.object({ handle: z.string().toLowerCase().max(20) }), req.params, reply);
    if (!p) return;
    const viewer = userId(req);
    const { rows } = await db.query<{ id: string; handle: string; displayName: string | null; avatarUrl: string | null }>(
      `select id, handle, display_name as "displayName", avatar_path as "avatarUrl" from profiles p where handle = $1
         and ($2::uuid is null or not exists (select 1 from blocks b where (b.blocker_id = $2 and b.blocked_id = p.id)
                                                                     or (b.blocker_id = p.id and b.blocked_id = $2)))`,
      [p.handle, viewer],
    );
    const u = rows[0];
    if (!u) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    const list = ((await storiesFor(db, [u.id], viewer)).get(u.id) ?? []) as { seen: boolean }[];
    return {
      user: { handle: u.handle, displayName: u.displayName, avatarUrl: u.avatarUrl },
      isMe: u.id === viewer,
      allSeen: list.every((s) => s.seen),
      stories: list,
    };
  });

  app.post('/stories', { preHandler: requireAuth, config: { rateLimit: { max: 30, timeWindow: '1 day' } } }, async (req, reply) => {
    const me = userId(req)!;
    let image: Buffer | undefined;
    const fields: Record<string, string> = {};
    for await (const part of req.parts()) {
      if (part.type === 'file' && part.fieldname === 'image') image = await part.toBuffer();
      else if (part.type === 'file') part.file.resume();
      else fields[part.fieldname] = String(part.value);
    }
    const body = parse(
      z.object({
        caption: z.string().trim().max(200).optional(),
        dishId: uuid.optional(),
        restaurantId: uuid.optional(),
        cityId: z.coerce.number().int().positive().optional(),
      }),
      fields,
      reply,
    );
    if (!body) return;
    if (!image) return reply.code(400).send({ error: 'invalid_input', message: 'Choose a photo.' });
    const id = randomUUID();
    let stored;
    try {
      stored = await storeImage(config.mediaDir, 'stories', id, image);
    } catch {
      return reply.code(400).send({ error: 'bad_image', message: 'That file is not a photo we can read.' });
    }
    // The city comes from the tag when there is one, so the story shows to people there.
    await db.query(
      `insert into stories (id, user_id, city_id, image_path, thumb_path, placeholder_color, caption, dish_id, restaurant_id)
       values ($1, $2, coalesce((select city_id from dishes where id = $7), (select city_id from restaurants where id = $8), $9),
               $3, $4, $5, $6, $7, $8)`,
      [id, me, stored.imagePath, stored.thumbPath, stored.placeholderColor, body.caption || null,
        body.dishId ?? null, body.restaurantId ?? null, body.cityId ?? null],
    );
    return reply.code(201).send({ id });
  });

  app.post('/stories/:id/view', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    await db.query(
      `insert into story_views (story_id, viewer_id)
       select id, $2 from stories where id = $1 and user_id <> $2 on conflict do nothing`,
      [p.id, userId(req)],
    );
    return { ok: true };
  });

  // A heart on a story. The owner sees it in the list of who watched (messages are only
  // for sharing dishes for now, so likes and replies don't go there).
  app.put('/stories/:id/like', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    const b = parse(z.object({ liked: z.boolean() }), req.body ?? {}, reply);
    if (!p || !b) return;
    const me = userId(req)!;
    const { rows } = await db.query<{ owner: string }>(
      'select s.user_id as owner from stories s where s.id = $1 and s.expires_at > now()',
      [p.id],
    );
    if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'This story has ended.' });
    await db.query(
      `insert into story_views (story_id, viewer_id, liked) values ($1, $2, $3)
       on conflict (story_id, viewer_id) do update set liked = excluded.liked`,
      [p.id, me, b.liked],
    );
    return { liked: b.liked };
  });

  // Replying to a story by message is off for now (messages only carry shared dishes).
  app.post('/stories/:id/reply', { preHandler: requireAuth }, async (_req, reply) =>
    reply.code(403).send({ error: 'share_only', message: 'Replies are off for now. You can like the story.' }));

  // Who watched your story, likes first.
  app.get('/stories/:id/viewers', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const { rows } = await db.query(
      `select pr.handle, pr.display_name as "displayName", pr.avatar_path as "avatarUrl", v.liked, v.viewed_at as "viewedAt"
       from story_views v join stories s on s.id = v.story_id join profiles pr on pr.id = v.viewer_id
       where v.story_id = $1 and s.user_id = $2
       order by v.liked desc, v.viewed_at desc limit 500`,
      [p.id, userId(req)],
    );
    return { items: rows };
  });

  app.delete('/stories/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const { rows } = await db.query<{ owns_media: boolean }>(
      'delete from stories where id = $1 and user_id = $2 returning owns_media',
      [p.id, userId(req)],
    );
    if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'Story not found.' });
    if (rows[0].owns_media) await deleteImages(config.mediaDir, 'stories', p.id);
    return reply.code(204).send();
  });
}

/** Deletes stories that ended more than a day ago, with their photos. */
export async function cleanupStories(db: Db, mediaDir: string): Promise<number> {
  const { rows } = await db.query<{ id: string; owns_media: boolean }>(
    "delete from stories where expires_at < now() - interval '1 day' returning id, owns_media",
  );
  for (const r of rows) if (r.owns_media) await deleteImages(mediaDir, 'stories', r.id);
  return rows.length;
}

/**
 * DEMO ONLY. Keeps a handful of stories from demo accounts alive (they borrow the demo
 * dish photos), so the stories tray isn't empty on a demo server.
 */
export async function refreshDemoStories(db: Db): Promise<number> {
  const { rows: live } = await db.query<{ n: number }>(
    `select count(*)::int as n from stories s join profiles p on p.id = s.user_id
     where p.is_demo and s.expires_at > now() + interval '2 hours'`,
  );
  if (live[0]!.n > 0) return 0;
  const { rows } = await db.query<{ id: string }>(
    `with picks as (
       select distinct on (u.id) u.id as user_id, po.image_path, po.thumb_path, po.placeholder_color, d.id as dish_id, d.city_id
       from profiles u
       join engagements e on e.user_id = u.id and e.kind = 'recommend'
       join dishes d on d.id = e.dish_id and d.status = 'live'
       join posts po on po.dish_id = d.id and po.status = 'live'
       where u.is_demo
       order by u.id, e.created_at desc)
     insert into stories (user_id, city_id, image_path, thumb_path, placeholder_color, owns_media, caption, dish_id, created_at, expires_at)
     select user_id, city_id, image_path, thumb_path, placeholder_color, false,
            (array['Lunch sorted', 'Worth the queue', 'Again? Again.', 'Tonight''s pick', null])[1 + (abs(hashtext(user_id::text)) % 5)],
            dish_id, now() - make_interval(hours => abs(hashtext(user_id::text)) % 6),
            now() - make_interval(hours => abs(hashtext(user_id::text)) % 6) + interval '24 hours'
     from picks order by hashtext(user_id::text) limit 8
     returning id`,
  );
  return rows.length;
}
