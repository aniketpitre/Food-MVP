import type { FastifyInstance, FastifyReply } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';
import { canMessage, conversationWith, MessageError, sendMessage } from '../messaging.js';

const uuid = z.string().uuid();

/** A message as the app shows it, with the dish or story it points to. */
const messageSql = `
  select m.id, m.kind, m.body, m.created_at as "createdAt", (m.sender_id = $2) as mine,
         case when d.id is not null then json_build_object(
           'id', d.id, 'name', d.name, 'restaurantName', r.name, 'priceInr', d.price_inr,
           'thumbUrl', (select p.thumb_path from posts p where p.dish_id = d.id and p.status = 'live'
                        order by (p.id = d.cover_post_id) desc, p.created_at desc limit 1)) end as dish,
         case when m.story_id is not null or m.kind in ('story_reply', 'story_like') then json_build_object(
           'id', s.id, 'thumbUrl', case when s.expires_at > now() then s.thumb_path end,
           'expired', s.id is null or s.expires_at <= now()) end as story
  from messages m
  left join dishes d on d.id = m.dish_id and d.status = 'live'
  left join restaurants r on r.id = d.restaurant_id
  left join stories s on s.id = m.story_id`;

/**
 * Direct messages, one to one. People you don't follow land in Requests until you reply or
 * accept; anyone can be blocked or reported from the chat; deleting a chat clears it for you
 * only. The app polls an open chat every few seconds, which is plenty for this scale.
 */
export async function messageRoutes(app: FastifyInstance) {
  const { db } = app;

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
  // ?after=<time> returns only newer messages (polling); ?before=<time> pages back.
  app.get('/conversations/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    const q = parse(z.object({ after: z.string().datetime().optional(), before: z.string().datetime().optional() }), req.query, reply);
    if (!p || !q) return;
    const me = userId(req)!;
    const m = await membership(p.id, me);
    if (!m) return reply.code(404).send({ error: 'not_found', message: 'Chat not found.' });
    const { rows } = await db.query(
      `select * from (${messageSql}
         where m.conversation_id = $1 and m.created_at > $3
           and ($4::timestamptz is null or date_trunc('milliseconds', m.created_at) > $4)
           and ($5::timestamptz is null or date_trunc('milliseconds', m.created_at) < $5)
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
    };
  });

  app.post(
    '/conversations/:id/messages',
    { preHandler: requireAuth, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const p = parse(z.object({ id: uuid }), req.params, reply);
      const body = parse(
        z.object({ body: z.string().trim().min(1).max(1000).optional(), dishId: uuid.optional() })
          .refine((b) => b.body || b.dishId, 'write something'),
        req.body,
        reply,
      );
      if (!p || !body) return;
      const me = userId(req)!;
      const m = await membership(p.id, me);
      if (!m) return reply.code(404).send({ error: 'not_found', message: 'Chat not found.' });
      try {
        const sent = await sendMessage(db, me, m.other_id, { kind: body.dishId ? 'dish' : 'text', body: body.body, dishId: body.dishId });
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
      }).refine((b) => b.body || b.dishId, 'write something'),
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
        if (body.dishId) await sendMessage(db, me, r.id, { kind: 'dish', dishId: body.dishId });
        if (body.body) await sendMessage(db, me, r.id, { kind: 'text', body: body.body });
        sent.push(r.handle);
      } catch (err) {
        if (!(err instanceof MessageError)) throw err;
        failed.push(r.handle);
      }
    }
    return { sent: sent.sort(), failed: failed.sort() };
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
    const { rowCount } = await db.query('delete from messages where id = $1 and sender_id = $2', [p.id, userId(req)]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Message not found.' });
    return reply.code(204).send();
  });
}
