import type { FastifyInstance, FastifyReply } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';
import { randomUUID } from 'node:crypto';
import { deleteImages, storeImage } from '../media.js';
import { canMessage, conversationWith, MessageError, sendMessage } from '../messaging.js';

const uuid = z.string().uuid();

/**
 * A message as the app shows it: its text, the dish / place / post / story it points to,
 * a photo, the message it replies to, and its reactions ($2 is the viewer).
 */
const messageSql = `
  select m.id, m.kind, m.body, m.created_at as "createdAt", (m.sender_id = $2) as mine,
         case when d.id is not null then json_build_object(
           'id', d.id, 'name', d.name, 'restaurantName', r.name, 'priceInr', d.price_inr,
           'thumbUrl', (select p.thumb_path from posts p where p.dish_id = d.id and p.status = 'live'
                        order by (p.id = d.cover_post_id) desc, p.created_at desc limit 1)) end as dish,
         case when pl.id is not null then json_build_object(
           'id', pl.id, 'name', pl.name, 'locality', pl.locality, 'cuisine', pl.cuisine,
           'lat', st_y(pl.geo::geometry), 'lng', st_x(pl.geo::geometry),
           'thumbUrl', coalesce(
             (select p.thumb_path from posts p join dishes d2 on d2.id = p.dish_id
              where d2.restaurant_id = pl.id and p.status = 'live' order by p.created_at desc limit 1),
             (select rp.thumb_path from restaurant_photos rp where rp.restaurant_id = pl.id limit 1))) end as place,
         case when po.id is not null then json_build_object(
           'id', po.id, 'dishId', po.dish_id, 'thumbUrl', po.thumb_path, 'caption', po.caption,
           'dishName', pd.name, 'author', pa.handle) end as post,
         case when m.image_path is not null then json_build_object('imageUrl', m.image_path, 'thumbUrl', m.thumb_path) end as photo,
         case when m.story_id is not null or m.kind in ('story_reply', 'story_like') then json_build_object(
           'id', s.id, 'thumbUrl', case when s.expires_at > now() then s.thumb_path end,
           'expired', s.id is null or s.expires_at <= now()) end as story,
         case when rt.id is not null then json_build_object(
           'id', rt.id, 'kind', rt.kind, 'body', left(rt.body, 120), 'mine', rt.sender_id = $2) end as "replyTo",
         (select coalesce(json_agg(json_build_object('emoji', x.emoji, 'mine', x.user_id = $2) order by x.created_at), '[]'::json)
          from message_reactions x where x.message_id = m.id) as reactions
  from messages m
  left join dishes d on d.id = m.dish_id and d.status = 'live'
  left join restaurants r on r.id = d.restaurant_id
  left join restaurants pl on pl.id = m.restaurant_id and pl.status = 'active'
  left join posts po on po.id = m.post_id and po.status = 'live'
  left join dishes pd on pd.id = po.dish_id
  left join profiles pa on pa.id = po.author_id
  left join stories s on s.id = m.story_id
  left join messages rt on rt.id = m.reply_to`;

/**
 * Direct messages, one to one. People you don't follow land in Requests until you reply or
 * accept; anyone can be blocked or reported from the chat; deleting a chat clears it for you
 * only. The app polls an open chat every few seconds, which is plenty for this scale.
 */
export async function messageRoutes(app: FastifyInstance) {
  const { db, config } = app;

  async function membership(conversationId: string, me: string) {
    const { rows } = await db.query<{ folder: string; cleared_at: Date; other_id: string; their_read: Date }>(
      `select mine.folder, mine.cleared_at, o.user_id as other_id, o.last_read_at as their_read
       from conversation_members mine
       join conversation_members o on o.conversation_id = mine.conversation_id and o.user_id <> mine.user_id
       where mine.conversation_id = $1 and mine.user_id = $2`,
      [conversationId, me],
    );
    return rows[0] ?? null;
  }

  async function person(id: string) {
    const { rows } = await db.query(
      `select handle, display_name as "displayName", avatar_path as "avatarUrl" from profiles where id = $1`,
      [id],
    );
    return rows[0];
  }

  function fail(reply: FastifyReply, err: unknown) {
    if (err instanceof MessageError) return reply.code(err.status).send({ error: err.code, message: err.message });
    throw err;
  }

  // Chats (or message requests), newest first, with the last message and an unread flag.
  app.get('/inbox', { preHandler: requireAuth }, async (req, reply) => {
    const q = parse(z.object({ folder: z.enum(['inbox', 'request']).default('inbox') }), req.query, reply);
    if (!q) return;
    const me = userId(req)!;
    const { rows } = await db.query(
      `select c.id, mine.folder,
              json_build_object('handle', p.handle, 'displayName', p.display_name, 'avatarUrl', p.avatar_path) as "with",
              json_build_object('kind', lm.kind, 'body', lm.body, 'mine', lm.sender_id = $1, 'createdAt', lm.created_at) as "lastMessage",
              (lm.sender_id <> $1 and lm.created_at > mine.last_read_at) as unread
       from conversation_members mine
       join conversations c on c.id = mine.conversation_id
       join conversation_members o on o.conversation_id = c.id and o.user_id <> $1
       join profiles p on p.id = o.user_id
       join lateral (select * from messages m where m.conversation_id = c.id and m.created_at > mine.cleared_at
                     order by m.created_at desc limit 1) lm on true
       where mine.user_id = $1 and mine.folder = $2
         and not exists (select 1 from blocks where (blocker_id = $1 and blocked_id = o.user_id)
                                                or (blocker_id = o.user_id and blocked_id = $1))
       order by c.last_message_at desc limit 100`,
      [me, q.folder],
    );
    const { rows: n } = await db.query<{ n: number }>(
      `select count(*)::int as n from conversation_members mine
       where mine.user_id = $1 and mine.folder = 'request'
         and exists (select 1 from messages m where m.conversation_id = mine.conversation_id and m.created_at > mine.cleared_at)`,
      [me],
    );
    return { items: rows, requests: n[0]!.n };
  });

  // For the badge on the messages button: chats with something new, and waiting requests.
  app.get('/inbox/unread', { preHandler: requireAuth }, async (req) => {
    const me = userId(req)!;
    const { rows } = await db.query<{ chats: number; requests: number }>(
      `select count(*) filter (where mine.folder = 'inbox')::int as chats,
              count(*) filter (where mine.folder = 'request')::int as requests
       from conversation_members mine
       -- Cheap first cut: chats with no message since you last read them are skipped
       -- without looking at their messages at all.
       join conversations c on c.id = mine.conversation_id
                           and c.last_message_at > greatest(mine.last_read_at, mine.cleared_at)
       where mine.user_id = $1
         and exists (select 1 from messages m where m.conversation_id = mine.conversation_id
                       and m.sender_id <> $1 and m.created_at > greatest(mine.last_read_at, mine.cleared_at))
         and not exists (select 1 from conversation_members o join blocks b
                           on (b.blocker_id = $1 and b.blocked_id = o.user_id) or (b.blocker_id = o.user_id and b.blocked_id = $1)
                         where o.conversation_id = mine.conversation_id and o.user_id <> $1)`,
      [me],
    );
    return rows[0];
  });

  /**
   * What's new since [since] for phone notifications: unread messages, reactions to my
   * messages, and unread Activity. The app keeps the returned `now` as its next cursor
   * (server time, so phone clocks don't matter). Without `since` it only returns `now`.
   */
  app.get('/updates', { preHandler: requireAuth }, async (req, reply) => {
    const q = parse(z.object({ since: z.string().datetime({ offset: true }).optional() }), req.query, reply);
    if (!q) return;
    const me = userId(req)!;
    const { rows: t } = await db.query<{ now: Date }>('select now() as now');
    const now = t[0]!.now;
    if (!q.since) return { now, messages: [], reactions: [], activity: [] };
    const notBlocked = (col: string) =>
      `not exists (select 1 from blocks b where (b.blocker_id = $1 and b.blocked_id = ${col}) or (b.blocker_id = ${col} and b.blocked_id = $1))`;
    const person = (a: string) =>
      `json_build_object('handle', ${a}.handle, 'displayName', ${a}.display_name, 'avatarUrl', ${a}.avatar_path)`;
    const [messages, reactions, activity] = await Promise.all([
      db.query(
        `select m.id, m.conversation_id as "conversationId", m.kind, left(m.body, 200) as body,
                m.created_at as "createdAt", mine.folder, ${person('p')} as "from"
         from messages m
         join conversation_members mine on mine.conversation_id = m.conversation_id and mine.user_id = $1
         join profiles p on p.id = m.sender_id
         where m.sender_id <> $1 and m.created_at > $2 and m.created_at <= $3
           and m.created_at > greatest(mine.last_read_at, mine.cleared_at) and ${notBlocked('m.sender_id')}
         order by m.created_at limit 30`,
        [me, q.since, now],
      ),
      db.query(
        `select x.message_id as "messageId", m.conversation_id as "conversationId", x.emoji,
                x.created_at as "createdAt", left(m.body, 80) as body, m.kind, ${person('p')} as "from"
         from message_reactions x
         join messages m on m.id = x.message_id and m.sender_id = $1
         join profiles p on p.id = x.user_id
         where x.user_id <> $1 and x.created_at > $2 and x.created_at <= $3 and ${notBlocked('x.user_id')}
         order by x.created_at limit 30`,
        [me, q.since, now],
      ),
      db.query(
        `select n.id, n.kind, n.created_at as "createdAt", ${person('a')} as actor,
                d.name as "dishName", d.id as "dishId", left(c.body, 120) as "commentBody"
         from notifications n
         join profiles a on a.id = n.actor_id
         left join dishes d on d.id = coalesce(n.dish_id, (select po.dish_id from posts po where po.id = n.post_id))
         left join comments c on c.id = n.comment_id and c.status = 'live'
         where n.user_id = $1 and n.read_at is null and n.created_at > $2 and n.created_at <= $3
           and ${notBlocked('n.actor_id')}
         order by n.id limit 30`,
        [me, q.since, now],
      ),
    ]);
    return {
      now,
      messages: messages.rows,
      reactions: reactions.rows,
      activity: activity.rows.map((r) => ({ ...r, id: Number(r.id) })),
    };
  });

  // Opens (or creates) the chat with someone, e.g. from the Message button on a profile.
  app.post('/conversations', { preHandler: requireAuth }, async (req, reply) => {
    const body = parse(z.object({ handle: z.string().trim().toLowerCase().max(20) }), req.body, reply);
    if (!body) return;
    const me = userId(req)!;
    const { rows } = await db.query<{ id: string }>('select id from profiles where handle = $1', [body.handle]);
    const other = rows[0]?.id;
    if (!other || other === me) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    const id = await conversationWith(db, me, other);
    return { id };
  });

  // One chat: the person, whether you can write, and a page of messages (oldest first).
  // ?after=<time> returns only newer messages; ?before=<time> pages back. The reply has a
  // `state`; an open chat polls with ?state=<last state> and gets just {unchanged: true}
  // while nothing (messages, reactions, unsends, their "seen") has changed.
  app.get('/conversations/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    const q = parse(
      z.object({ after: z.string().datetime().optional(), before: z.string().datetime().optional(), state: z.string().max(200).optional() }),
      req.query,
      reply,
    );
    if (!p || !q) return;
    const me = userId(req)!;
    const m = await membership(p.id, me);
    if (!m) return reply.code(404).send({ error: 'not_found', message: 'Chat not found.' });
    const { rows: f } = await db.query<{ last: string | null; n: number; rn: number; rlast: string | null }>(
      `select (select max(created_at)::text from messages where conversation_id = $1 and created_at > $2) as last,
              (select count(*)::int from messages where conversation_id = $1 and created_at > $2) as n,
              (select count(*)::int from message_reactions x join messages mm on mm.id = x.message_id
                where mm.conversation_id = $1 and mm.created_at > $2) as rn,
              (select max(x.created_at)::text from message_reactions x join messages mm on mm.id = x.message_id
                where mm.conversation_id = $1 and mm.created_at > $2) as rlast`,
      [p.id, m.cleared_at],
    );
    const state = [f[0]!.last, f[0]!.n, f[0]!.rn, f[0]!.rlast, m.their_read.toISOString(), m.folder].join('|');
    if (q.state && q.state === state && !q.after && !q.before) return { id: p.id, unchanged: true, state };
    // The truncated comparisons keep millisecond cursors exact; the plain ones let the index help.
    const { rows } = await db.query(
      `select * from (${messageSql}
         where m.conversation_id = $1 and m.created_at > $3
           and ($4::timestamptz is null or (m.created_at > $4 and date_trunc('milliseconds', m.created_at) > $4))
           and ($5::timestamptz is null or (m.created_at < $5 + interval '1 millisecond' and date_trunc('milliseconds', m.created_at) < $5))
         order by m.created_at desc limit 50) page order by "createdAt"`,
      [p.id, me, m.cleared_at, q.after ?? null, q.before ?? null],
    );
    return {
      id: p.id,
      folder: m.folder,
      with: await person(m.other_id),
      canMessage: await canMessage(db, me, m.other_id),
      theirLastReadAt: m.their_read,
      messages: rows,
      state,
    };
  });

  app.post(
    '/conversations/:id/messages',
    { preHandler: requireAuth, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const p = parse(z.object({ id: uuid }), req.params, reply);
      const body = parse(
        z.object({
          body: z.string().trim().min(1).max(1000).optional(),
          dishId: uuid.optional(),
          restaurantId: uuid.optional(),
          postId: uuid.optional(),
          replyTo: uuid.optional(),
        }).refine((b) => b.body || b.dishId || b.restaurantId || b.postId, 'write something'),
        req.body,
        reply,
      );
      if (!p || !body) return;
      const me = userId(req)!;
      const m = await membership(p.id, me);
      if (!m) return reply.code(404).send({ error: 'not_found', message: 'Chat not found.' });
      try {
        const kind = body.postId ? 'post' : body.dishId ? 'dish' : body.restaurantId ? 'place' : 'text';
        const sent = await sendMessage(db, me, m.other_id, {
          kind, body: body.body, dishId: body.dishId, restaurantId: body.restaurantId, postId: body.postId, replyTo: body.replyTo,
        });
        const { rows } = await db.query(`${messageSql} where m.id = $1`, [sent.messageId, me]);
        return reply.code(201).send(rows[0]);
      } catch (err) {
        return fail(reply, err);
      }
    },
  );

  // Send to several people at once: sharing a dish ("let's go here?") from the dish page.
  app.post('/messages', { preHandler: requireAuth, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const body = parse(
      z.object({
        handles: z.array(z.string().trim().toLowerCase().max(20)).min(1).max(15),
        body: z.string().trim().min(1).max(1000).optional(),
        dishId: uuid.optional(),
        restaurantId: uuid.optional(),
        postId: uuid.optional(),
      }).refine((b) => b.body || b.dishId || b.restaurantId || b.postId, 'write something'),
      req.body,
      reply,
    );
    if (!body) return;
    const me = userId(req)!;
    const { rows } = await db.query<{ id: string; handle: string }>('select id, handle from profiles where handle = any($1)', [body.handles]);
    const sent: string[] = [];
    const failed: string[] = [];
    for (const r of rows) {
      try {
        if (body.postId) await sendMessage(db, me, r.id, { kind: 'post', postId: body.postId });
        else if (body.dishId) await sendMessage(db, me, r.id, { kind: 'dish', dishId: body.dishId });
        else if (body.restaurantId) await sendMessage(db, me, r.id, { kind: 'place', restaurantId: body.restaurantId });
        if (body.body) await sendMessage(db, me, r.id, { kind: 'text', body: body.body });
        sent.push(r.handle);
      } catch (err) {
        if (!(err instanceof MessageError)) throw err;
        failed.push(r.handle);
      }
    }
    return { sent: sent.sort(), failed: failed.sort() };
  });

  // A photo in a chat (re-encoded like every photo: WebP, location data dropped).
  app.post(
    '/conversations/:id/photo',
    { preHandler: requireAuth, config: { rateLimit: { max: 20, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const p = parse(z.object({ id: uuid }), req.params, reply);
      if (!p) return;
      const me = userId(req)!;
      const m = await membership(p.id, me);
      if (!m) return reply.code(404).send({ error: 'not_found', message: 'Chat not found.' });
      let image: Buffer | undefined;
      let caption: string | undefined;
      for await (const part of req.parts()) {
        if (part.type === 'file' && part.fieldname === 'image') image = await part.toBuffer();
        else if (part.type === 'file') part.file.resume();
        else if (part.fieldname === 'body') caption = String(part.value).trim().slice(0, 1000) || undefined;
      }
      if (!image) return reply.code(400).send({ error: 'invalid_input', message: 'Choose a photo.' });
      const fileId = randomUUID();
      let stored;
      try {
        stored = await storeImage(config.mediaDir, 'messages', fileId, image);
      } catch {
        return reply.code(400).send({ error: 'bad_image', message: 'That file is not a photo we can read.' });
      }
      try {
        const sent = await sendMessage(db, me, m.other_id, { kind: 'photo', body: caption, imagePath: stored.imagePath, thumbPath: stored.thumbPath });
        const { rows } = await db.query(`${messageSql} where m.id = $1`, [sent.messageId, me]);
        return reply.code(201).send(rows[0]);
      } catch (err) {
        await deleteImages(config.mediaDir, 'messages', fileId);
        return fail(reply, err);
      }
    },
  );

  // React to a message (one reaction per person, like Instagram; double-tap sends a heart).
  app.put('/messages/:id/reaction', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    const b = parse(z.object({ emoji: z.string().min(1).max(16) }), req.body, reply);
    if (!p || !b) return;
    const me = userId(req)!;
    const { rowCount } = await db.query(
      `insert into message_reactions (message_id, user_id, emoji)
       select m.id, $2, $3 from messages m
       join conversation_members cm on cm.conversation_id = m.conversation_id and cm.user_id = $2
       where m.id = $1
       on conflict (message_id, user_id) do update set emoji = excluded.emoji, created_at = now()`,
      [p.id, me, b.emoji],
    );
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Message not found.' });
    return { ok: true };
  });

  app.delete('/messages/:id/reaction', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    await db.query('delete from message_reactions where message_id = $1 and user_id = $2', [p.id, userId(req)]);
    return { ok: true };
  });

  app.post('/conversations/:id/read', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    await db.query('update conversation_members set last_read_at = now() where conversation_id = $1 and user_id = $2', [p.id, userId(req)]);
    return { ok: true };
  });

  app.post('/conversations/:id/accept', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const { rowCount } = await db.query(
      "update conversation_members set folder = 'inbox' where conversation_id = $1 and user_id = $2",
      [p.id, userId(req)],
    );
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Chat not found.' });
    return { ok: true };
  });

  // Delete a chat (or decline a request): clears it for you; the other person keeps theirs.
  app.delete('/conversations/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    await db.query(
      'update conversation_members set cleared_at = now(), last_read_at = now() where conversation_id = $1 and user_id = $2',
      [p.id, userId(req)],
    );
    return reply.code(204).send();
  });

  // Unsend your own message, for both people.
  app.delete('/messages/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const { rows } = await db.query<{ image_path: string | null }>(
      'delete from messages where id = $1 and sender_id = $2 returning image_path',
      [p.id, userId(req)],
    );
    if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'Message not found.' });
    const img = rows[0].image_path?.match(/^\/media\/messages\/([0-9a-f-]+)\//)?.[1];
    if (img) await deleteImages(config.mediaDir, 'messages', img);
    return reply.code(204).send();
  });
}
