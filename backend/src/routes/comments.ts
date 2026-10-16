import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, requireAuth, userId } from '../auth.js';

const uuid = z.string().uuid();

export interface CommentOut {
  id: string;
  parentId: string | null;
  body: string;
  createdAt: string;
  author: { handle: string; displayName: string | null };
  likes: number;
  likedByMe: boolean;
  canDelete: boolean;
  replies: CommentOut[];
}

interface CommentRow {
  id: string;
  parent_id: string | null;
  body: string;
  created_at: Date;
  author_id: string;
  handle: string;
  display_name: string | null;
  likes: number;
  liked: boolean;
}

/**
 * Comments on posts, Instagram style: a flat list of comments, each with one level of
 * replies (a reply to a reply joins the same thread), and likes. People see nothing from
 * anyone they blocked. Authors can delete their own comments, a post's author can delete
 * comments on their post, and admins can delete anything; deleted comments disappear
 * together with their replies.
 */
export async function commentRoutes(app: FastifyInstance) {
  const { db } = app;

  async function livePost(id: string): Promise<{ authorId: string | null } | null> {
    const { rows } = await db.query<{ author_id: string | null }>(
      `select p.author_id from posts p join dishes d on d.id = p.dish_id
       where p.id = $1 and p.status = 'live' and d.status = 'live'`,
      [id],
    );
    return rows[0] ? { authorId: rows[0].author_id } : null;
  }

  async function isAdmin(me: string | null): Promise<boolean> {
    if (!me) return false;
    const { rows } = await db.query<{ role: string }>('select role from profiles where id = $1', [me]);
    return rows[0]?.role === 'admin';
  }

  async function load(postId: string, viewer: string | null, onlyId?: string): Promise<CommentRow[]> {
    const { rows } = await db.query<CommentRow>(
      `select c.id, c.parent_id, c.body, c.created_at, c.author_id, pr.handle, pr.display_name,
              (select count(*)::int from comment_likes l where l.comment_id = c.id) as likes,
              ($2::uuid is not null and exists
                 (select 1 from comment_likes l where l.comment_id = c.id and l.user_id = $2)) as liked
       from comments c join profiles pr on pr.id = c.author_id
       where c.post_id = $1 and c.status = 'live' and ($3::uuid is null or c.id = $3)
         and ($2::uuid is null or not exists
              (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = c.author_id))
       order by c.created_at, c.id
       limit 1000`,
      [postId, viewer, onlyId ?? null],
    );
    return rows;
  }

  function shape(r: CommentRow, viewer: string | null, postAuthor: string | null, admin: boolean): CommentOut {
    return {
      id: r.id,
      parentId: r.parent_id,
      body: r.body,
      createdAt: r.created_at.toISOString(),
      author: { handle: r.handle, displayName: r.display_name },
      likes: Number(r.likes),
      likedByMe: r.liked,
      canDelete: viewer !== null && (r.author_id === viewer || postAuthor === viewer || admin),
      replies: [],
    };
  }

  async function likeState(id: string, viewer: string) {
    const { rows } = await db.query<{ likes: number; liked: boolean }>(
      `select (select count(*)::int from comment_likes where comment_id = $1) as likes,
              exists (select 1 from comment_likes where comment_id = $1 and user_id = $2) as liked`,
      [id, viewer],
    );
    return { likes: rows[0]!.likes, likedByMe: rows[0]!.liked };
  }

  app.get('/posts/:id/comments', { preHandler: optionalAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const post = await livePost(p.id);
    if (!post) return reply.code(404).send({ error: 'not_found', message: 'This post is not available.' });
    const viewer = userId(req);
    const admin = await isAdmin(viewer);
    const rows = await load(p.id, viewer, undefined);
    const top: CommentOut[] = [];
    const byId = new Map<string, CommentOut>();
    for (const r of rows) {
      const c = shape(r, viewer, post.authorId, admin);
      byId.set(c.id, c);
      if (!r.parent_id) top.push(c);
    }
    let count = top.length;
    for (const r of rows) {
      if (!r.parent_id) continue;
      const parent = byId.get(r.parent_id);
      // Replies to a hidden or removed comment go with it.
      if (parent) {
        parent.replies.push(byId.get(r.id)!);
        count++;
      }
    }
    return { count, comments: top };
  });

  app.post(
    '/posts/:id/comments',
    { preHandler: requireAuth, config: { rateLimit: { max: 12, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const p = parse(z.object({ id: uuid }), req.params, reply);
      if (!p) return;
      const body = parse(
        z.object({ body: z.string().trim().min(1, 'Write something first.').max(500), parentId: uuid.optional() }),
        req.body,
        reply,
      );
      if (!body) return;
      const post = await livePost(p.id);
      if (!post) return reply.code(404).send({ error: 'not_found', message: 'This post is not available.' });
      const me = userId(req)!;

      let parentId: string | null = null;
      if (body.parentId) {
        const { rows } = await db.query<{ id: string; parent_id: string | null }>(
          "select id, parent_id from comments where id = $1 and post_id = $2 and status = 'live'",
          [body.parentId, p.id],
        );
        if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'That comment is gone.' });
        parentId = rows[0].parent_id ?? rows[0].id;
      }

      const { rows } = await db.query<{ id: string }>(
        'insert into comments (post_id, author_id, parent_id, body) values ($1, $2, $3, $4) returning id',
        [p.id, me, parentId, body.body],
      );
      const [row] = await load(p.id, me, rows[0]!.id);
      return reply.code(201).send(shape(row!, me, post.authorId, await isAdmin(me)));
    },
  );

  app.delete('/comments/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    const { rows } = await db.query<{ author_id: string; post_author: string | null }>(
      `select c.author_id, po.author_id as post_author from comments c join posts po on po.id = c.post_id
       where c.id = $1 and c.status <> 'removed'`,
      [p.id],
    );
    const c = rows[0];
    if (!c) return reply.code(404).send({ error: 'not_found', message: 'That comment is gone.' });
    if (c.author_id !== me && c.post_author !== me && !(await isAdmin(me))) {
      return reply.code(403).send({ error: 'forbidden', message: "You can't delete this comment." });
    }
    await db.query("update comments set status = 'removed' where id = $1", [p.id]);
    return { ok: true };
  });

  const likeLimit = { rateLimit: { max: 120, timeWindow: '1 minute' } };

  app.put('/comments/:id/like', { preHandler: requireAuth, config: likeLimit }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    const { rowCount } = await db.query("select 1 from comments where id = $1 and status = 'live'", [p.id]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'That comment is gone.' });
    await db.query('insert into comment_likes (comment_id, user_id) values ($1, $2) on conflict do nothing', [p.id, me]);
    return likeState(p.id, me);
  });

  app.delete('/comments/:id/like', { preHandler: requireAuth, config: likeLimit }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    await db.query('delete from comment_likes where comment_id = $1 and user_id = $2', [p.id, me]);
    return likeState(p.id, me);
  });
}
