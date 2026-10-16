import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, requireAuth, userId } from '../auth.js';
import { loadDishCards } from '../cards.js';

const uuid = z.string().uuid();
const actionLimit = { rateLimit: { max: 60, timeWindow: '1 minute' } };

export async function actionRoutes(app: FastifyInstance) {
  const { db } = app;

  async function dishIsLive(id: string): Promise<boolean> {
    const { rowCount } = await db.query("select 1 from dishes where id = $1 and status = 'live'", [id]);
    return (rowCount ?? 0) > 0;
  }

  async function counts(dishId: string, viewer: string) {
    await db.query('select refresh_dish_stats($1)', [dishId]);
    const [card] = await loadDishCards(db, [dishId], viewer);
    return { stats: card!.stats, me: card!.me };
  }

  // Recommend, save and tried are on/off toggles. PUT turns one on, DELETE turns it off;
  // both are idempotent, so a retried request never double counts.
  app.put('/dishes/:id/engagements/:kind', { preHandler: requireAuth, config: actionLimit }, async (req, reply) => {
    const p = parse(z.object({ id: uuid, kind: z.enum(['recommend', 'save', 'tried']) }), req.params, reply);
    if (!p) return;
    if (!(await dishIsLive(p.id))) return reply.code(404).send({ error: 'not_found', message: 'Dish not found.' });
    const me = userId(req)!;
    await db.query(
      'insert into engagements (user_id, dish_id, kind) values ($1, $2, $3) on conflict do nothing',
      [me, p.id, p.kind],
    );
    return counts(p.id, me);
  });

  app.delete('/dishes/:id/engagements/:kind', { preHandler: requireAuth, config: actionLimit }, async (req, reply) => {
    const p = parse(z.object({ id: uuid, kind: z.enum(['recommend', 'save', 'tried']) }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    await db.query('delete from engagements where user_id = $1 and dish_id = $2 and kind = $3', [me, p.id, p.kind]);
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

  // Reports. A post or dish is hidden automatically once enough distinct, established
  // accounts report it; reports from brand-new accounts only go to the review queue so
  // a rival cannot hide a dish with three throwaway accounts.
  app.post('/reports', { preHandler: requireAuth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const body = parse(
      z.object({
        targetType: z.enum(['post', 'dish', 'user', 'comment']),
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
    const table = { post: 'posts', dish: 'dishes', user: 'profiles', comment: 'comments' }[body.targetType];
    const { rowCount } = await db.query(`select 1 from ${table} where id = $1`, [targetId]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Nothing to report.' });

    await db.query(
      `insert into reports (reporter_id, target_type, target_id, reason, note)
       values ($1, $2, $3, $4, $5) on conflict (reporter_id, target_type, target_id) do nothing`,
      [userId(req), body.targetType, targetId, body.reason, body.note || null],
    );

    let hidden = false;
    if (body.targetType !== 'user') {
      const { rows } = await db.query<{ n: number }>(
        `select count(*)::int as n from reports rp join profiles pr on pr.id = rp.reporter_id
         where rp.target_type = $1 and rp.target_id = $2 and rp.status = 'open'
           and pr.created_at < now() - make_interval(hours => cfg('report_min_account_hours')::int)`,
        [body.targetType, targetId],
      );
      const { rows: t } = await db.query<{ v: string }>("select cfg('report_hide_threshold') as v");
      if (rows[0]!.n >= Number(t[0]!.v)) {
        await db.query(`update ${table} set status = 'hidden' where id = $1 and status = 'live'`, [targetId]);
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
      z.object({ dishIds: z.array(uuid).min(1).max(50), deviceId: z.string().max(64).optional() }),
      req.body,
      reply,
    );
    if (!body) return;
    await db.query(
      `insert into dish_views (dish_id, user_id, device_id)
       select d.id, $2, $3 from dishes d where d.id = any($1::uuid[])`,
      [[...new Set(body.dishIds)], userId(req), body.deviceId ?? null],
    );
    return reply.code(202).send({ ok: true });
  });
}
