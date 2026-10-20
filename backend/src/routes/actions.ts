import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, requireAuth, userId } from '../auth.js';
import { loadDishCards } from '../cards.js';
import { inTransaction } from '../db.js';
import { notify } from '../notify.js';
import { scheduleTrustRefresh } from '../ranking.js';

const uuid = z.string().uuid();
type Queryable = { query: (sql: string, params?: unknown[]) => Promise<unknown> };
const actionLimit = { rateLimit: { max: 60, timeWindow: '1 minute' } };

export async function actionRoutes(app: FastifyInstance) {
  const { db } = app;

  async function dishIsLive(id: string): Promise<boolean> {
    const { rowCount } = await db.query("select 1 from dishes where id = $1 and status = 'live'", [id]);
    return (rowCount ?? 0) > 0;
  }

  async function counts(dishId: string, viewer: string) {
    await db.query('select refresh_dish_stats($1)', [dishId]);
    // Re-rate just this dish now; everyone's trust is rebuilt a few seconds later, once for
    // a whole burst of votes (and on the ranking timer).
    await db.query('select refresh_people_pick($1)', [dishId]);
    scheduleTrustRefresh(db, 3000, (err) => app.log.error(err));
    const [card] = await loadDishCards(db, [dishId], viewer);
    return { stats: card!.stats, me: card!.me, rating: card!.rating };
  }

  // Recommend, save, tried and "not for me" (pass) are on/off toggles. PUT turns one on,
  // DELETE turns it off; both are idempotent, so a retried request never double counts.
  // Recommend and pass exclude each other; a pass also marks the dish as tried (and
  // undoing the pass takes that "tried" back, unless it was marked on its own before).
  // Each toggle is one transaction, so a half-done vote never shows.
  const kinds = z.enum(['recommend', 'save', 'tried', 'pass']);
  app.put('/dishes/:id/engagements/:kind', { preHandler: requireAuth, config: actionLimit }, async (req, reply) => {
    const p = parse(z.object({ id: uuid, kind: kinds }), req.params, reply);
    if (!p) return;
    if (!(await dishIsLive(p.id))) return reply.code(404).send({ error: 'not_found', message: 'Dish not found.' });
    const me = userId(req)!;
    // A love and a "not for me" can't both stand (a unique index says so), so the other one
    // goes first. Two taps racing each other: the loser simply tries again.
    const toggle = () => inTransaction(db, async (tx) => {
      if (p.kind === 'pass') await tx.query("delete from engagements where user_id = $1 and dish_id = $2 and kind = 'recommend'", [me, p.id]);
      // Changing your mind to a love: you did try it, so "tried" stays.
      if (p.kind === 'recommend') await tx.query("delete from engagements where user_id = $1 and dish_id = $2 and kind = 'pass'", [me, p.id]);
      const { rowCount } = await tx.query(
        'insert into engagements (user_id, dish_id, kind) values ($1, $2, $3) on conflict do nothing',
        [me, p.id, p.kind],
      );
      if (p.kind === 'pass') {
        // The same timestamp as the pass (a re-vote keeps its first date): that's how undo knows it.
        await tx.query(
          `insert into engagements (user_id, dish_id, kind, created_at)
           select user_id, dish_id, 'tried', created_at from engagements where user_id = $1 and dish_id = $2 and kind = 'pass'
           on conflict do nothing`,
          [me, p.id],
        );
      }
      return (rowCount ?? 0) > 0;
    });
    let added: boolean;
    try {
      added = await toggle();
    } catch (err) {
      if ((err as { code?: string }).code !== '23505') throw err;
      added = await toggle();
    }
    if (p.kind === 'recommend' && added) await tellPeople(p.id, me);
    return counts(p.id, me);
  });

  /**
   * A new love tells the person who posted the dish's photo, and up to three people who
   * loved it earlier that someone agreed with their pick (that agreement is what builds
   * their trust in the People's Pick).
   */
  async function tellPeople(dishId: string, me: string) {
    const { rows: cover } = await db.query<{ author_id: string | null; id: string }>(
      'select p.author_id, p.id from posts p where p.id = dish_cover($1, null)',
      [dishId],
    );
    if (cover[0]) await notify(db, { userId: cover[0].author_id, actorId: me, kind: 'recommend', dishId, postId: cover[0].id });
    const { rows: earlier } = await db.query<{ user_id: string }>(
      `select user_id from engagements where dish_id = $1 and kind = 'recommend' and user_id <> $2
         and created_at < (select created_at from engagements where dish_id = $1 and user_id = $2 and kind = 'recommend')
       order by created_at desc limit 3`,
      [dishId, me],
    );
    for (const e of earlier) {
      if (e.user_id !== cover[0]?.author_id) await notify(db, { userId: e.user_id, actorId: me, kind: 'agree', dishId });
    }
  }

  /** Removes a "not for me" and the "tried" it added (a "tried" marked earlier stays). */
  async function undoPass(tx: Queryable, me: string, dishId: string) {
    await tx.query(
      `delete from engagements t using engagements x
       where x.user_id = $1 and x.dish_id = $2 and x.kind = 'pass'
         and t.user_id = x.user_id and t.dish_id = x.dish_id and t.kind = 'tried' and t.created_at = x.created_at`,
      [me, dishId],
    );
    await tx.query("delete from engagements where user_id = $1 and dish_id = $2 and kind = 'pass'", [me, dishId]);
  }

  app.delete('/dishes/:id/engagements/:kind', { preHandler: requireAuth, config: actionLimit }, async (req, reply) => {
    const p = parse(z.object({ id: uuid, kind: kinds }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    await inTransaction(db, async (tx) => {
      if (p.kind === 'pass') await undoPass(tx, me, p.id);
      else await tx.query('delete from engagements where user_id = $1 and dish_id = $2 and kind = $3', [me, p.id, p.kind]);
    });
    return counts(p.id, me);
  });

  // A share is recorded once per person and dish and cannot be undone. Guests can
  // share too; their shares are simply not counted.
  app.post('/dishes/:id/share', { preHandler: optionalAuth, config: actionLimit }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    if (!(await dishIsLive(p.id))) return reply.code(404).send({ error: 'not_found', message: 'Dish not found.' });
    const me = userId(req);
    if (me) {
      await db.query(
        "insert into engagements (user_id, dish_id, kind) values ($1, $2, 'share') on conflict do nothing",
        [me, p.id],
      );
      await db.query('select refresh_dish_stats($1)', [p.id]);
    }
    return { url: `${app.config.publicUrl}/d/${p.id}`, counted: me !== null };
  });

  app.get('/me/saved', { preHandler: requireAuth }, async (req, reply) => {
    const q = parse(
      z.object({
        lat: z.coerce.number().min(-90).max(90).optional(),
        lng: z.coerce.number().min(-180).max(180).optional(),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const me = userId(req)!;
    const { rows } = await db.query<{ dish_id: string }>(
      `select e.dish_id from engagements e join dishes d on d.id = e.dish_id
       where e.user_id = $1 and e.kind = 'save' and d.status = 'live'
       order by e.created_at desc limit 200`,
      [me],
    );
    return { items: await loadDishCards(db, rows.map((r) => r.dish_id), me, q.lat, q.lng) };
  });

  // Reports. A post, comment or story is hidden automatically once enough reports from
  // established accounts add up, each counted by its standing (account age, lockstep
  // partners), so a rival cannot hide a dish with three throwaway accounts. Reports from
  // brand-new accounts, and every report on a whole dish (many people's photos and votes),
  // only go to the review queue.
  app.post('/reports', { preHandler: requireAuth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const body = parse(
      z.object({
        targetType: z.enum(['post', 'dish', 'user', 'comment', 'message', 'story']),
        targetId: uuid.optional(),
        targetHandle: z.string().max(20).optional(),
        reason: z.enum(['wrong_place', 'not_food', 'inappropriate', 'spam', 'other']),
        note: z.string().trim().max(500).optional(),
      }),
      req.body,
      reply,
    );
    if (!body) return;
    let targetId = body.targetId;
    if (body.targetType === 'user' && !targetId && body.targetHandle) {
      const { rows } = await db.query<{ id: string }>('select id from profiles where handle = $1', [body.targetHandle.toLowerCase()]);
      targetId = rows[0]?.id;
    }
    if (!targetId) return reply.code(400).send({ error: 'invalid_input', message: 'Missing report target.' });
    const table = { post: 'posts', dish: 'dishes', user: 'profiles', comment: 'comments', message: 'messages', story: 'stories' }[body.targetType];
    const { rowCount } = await db.query(`select 1 from ${table} where id = $1`, [targetId]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Nothing to report.' });

    await db.query(
      `insert into reports (reporter_id, target_type, target_id, reason, note)
       values ($1, $2, $3, $4, $5) on conflict (reporter_id, target_type, target_id) do nothing`,
      [userId(req), body.targetType, targetId, body.reason, body.note || null],
    );

    let hidden = false;
    // Messages go to the review queue only; the reporter can already block the sender.
    if (body.targetType !== 'user' && body.targetType !== 'message' && body.targetType !== 'dish') {
      const { rows } = await db.query<{ n: number }>(
        `select coalesce(sum(standing(pr.created_at, vi.twins)), 0)::float8 as n
         from reports rp join profiles pr on pr.id = rp.reporter_id
         left join voter_independence vi on vi.user_id = rp.reporter_id
         where rp.target_type = $1 and rp.target_id = $2 and rp.status = 'open'
           and pr.created_at < now() - make_interval(hours => cfg('report_min_account_hours')::int)`,
        [body.targetType, targetId],
      );
      const { rows: t } = await db.query<{ v: string }>("select cfg('report_hide_threshold') as v");
      if (rows[0]!.n >= Number(t[0]!.v) - 1e-9) {
        if (body.targetType === 'story') await db.query('update stories set expires_at = now() where id = $1', [targetId]);
        else await db.query(`update ${table} set status = 'hidden' where id = $1 and status = 'live'`, [targetId]);
        hidden = true;
      }
    }
    return reply.code(201).send({ ok: true, hidden });
  });

  app.post('/blocks', { preHandler: requireAuth }, async (req, reply) => {
    const body = parse(z.object({ handle: z.string().toLowerCase().max(20) }), req.body, reply);
    if (!body) return;
    const { rows } = await db.query<{ id: string }>('select id from profiles where handle = $1', [body.handle]);
    const target = rows[0];
    if (!target) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    if (target.id === userId(req)) return reply.code(400).send({ error: 'invalid_input', message: 'You cannot block yourself.' });
    await db.query('insert into blocks (blocker_id, blocked_id) values ($1, $2) on conflict do nothing', [userId(req), target.id]);
    // Blocking ends following both ways, so neither sees the other's posts in Following.
    await db.query(
      'delete from follows where (follower_id = $1 and followee_id = $2) or (follower_id = $2 and followee_id = $1)',
      [userId(req), target.id],
    );
    return { ok: true };
  });

  app.delete('/blocks/:handle', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ handle: z.string().toLowerCase().max(20) }), req.params, reply);
    if (!p) return;
    await db.query(
      'delete from blocks where blocker_id = $1 and blocked_id = (select id from profiles where handle = $2)',
      [userId(req), p.handle],
    );
    return { ok: true };
  });

  // Batched impressions (a card at least 60% visible for 1 second). The user id comes
  // from the token only, so nobody can record views as someone else.
  app.post('/views', { preHandler: optionalAuth, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const body = parse(
      z.object({
        dishIds: z.array(uuid).min(1).max(50),
        deviceId: z.string().max(64).optional(),
        // Which feed the cards were in, and whether these are cards someone tapped open.
        tab: z.string().max(20).regex(/^[a-z_]+$/).optional(),
        opened: z.boolean().default(false),
      }),
      req.body,
      reply,
    );
    if (!body) return;
    await db.query(
      `insert into dish_views (dish_id, user_id, device_id, tab, opened)
       select d.id, $2, $3, $4, $5 from dishes d where d.id = any($1::uuid[])`,
      [[...new Set(body.dishIds)], userId(req), body.deviceId ?? null, body.tab ?? null, body.opened],
    );
    return reply.code(202).send({ ok: true });
  });
}
