import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';

export async function adminRoutes(app: FastifyInstance) {
  const { db } = app;

  // The role is re-read from the database on every admin request, so removing someone
  // from ADMIN_EMAILS takes effect at once instead of when their token expires.
  async function requireAdmin(req: FastifyRequest, reply: FastifyReply) {
    await requireAuth(req, reply);
    if (reply.sent) return;
    const { rows } = await db.query<{ role: string }>('select role from profiles where id = $1', [userId(req)]);
    if (rows[0]?.role !== 'admin') return reply.code(403).send({ error: 'forbidden', message: 'Admins only.' });
  }

  app.get('/admin/queue', { preHandler: requireAdmin }, async () => {
    const { rows: pendingPlaces } = await db.query(
      `select r.id, r.name, r.locality, r.created_at as "createdAt",
              st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              pr.handle as "createdBy",
              (select count(*)::int from dishes d where d.restaurant_id = r.id) as dishes
       from restaurants r left join profiles pr on pr.id = r.created_by
       where r.status = 'pending' order by r.created_at`,
    );
    const { rows: reported } = await db.query(
      `select rp.target_type as "targetType", rp.target_id as "targetId",
              count(*)::int as reports,
              array_agg(distinct rp.reason) as reasons,
              max(rp.created_at) as "lastReportAt",
              coalesce(p.status, d.status) as status,
              coalesce(pd.name, d.name, u.handle) as label,
              p.thumb_path as "thumbUrl", coalesce(p.dish_id, d.id) as "dishId"
       from reports rp
       left join posts p    on rp.target_type = 'post' and p.id = rp.target_id
       left join dishes pd  on pd.id = p.dish_id
       left join dishes d   on rp.target_type = 'dish' and d.id = rp.target_id
       left join profiles u on rp.target_type = 'user' and u.id = rp.target_id
       where rp.status = 'open'
       group by rp.target_type, rp.target_id, p.status, d.status, pd.name, d.name, u.handle, p.thumb_path, p.dish_id, d.id
       order by count(*) desc, max(rp.created_at) desc`,
    );
    return { pendingPlaces, reported };
  });

  app.post('/admin/restaurants/:id/:action', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid(), action: z.enum(['approve', 'hide']) }), req.params, reply);
    if (!p) return;
    const status = p.action === 'approve' ? 'active' : 'hidden';
    const { rowCount } = await db.query('update restaurants set status = $2 where id = $1', [p.id, status]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Place not found.' });
    return { ok: true, status };
  });

  // Hide or restore a reported post/dish. Either way its open reports are resolved.
  app.post('/admin/:type/:id/:action', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(
      z.object({ type: z.enum(['posts', 'dishes']), id: z.string().uuid(), action: z.enum(['hide', 'restore', 'dismiss']) }),
      req.params,
      reply,
    );
    if (!p) return;
    if (p.action !== 'dismiss') {
      const status = p.action === 'hide' ? 'hidden' : 'live';
      const { rowCount } = await db.query(`update ${p.type} set status = $2 where id = $1`, [p.id, status]);
      if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Not found.' });
    }
    await db.query("update reports set status = 'resolved' where target_type = $1 and target_id = $2", [
      p.type === 'posts' ? 'post' : 'dish',
      p.id,
    ]);
    if (p.type === 'dishes') await db.query('select refresh_dish_stats($1)', [p.id]);
    return { ok: true };
  });

  app.post('/admin/users/:id/dismiss', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    if (!p) return;
    await db.query("update reports set status = 'resolved' where target_type = 'user' and target_id = $1", [p.id]);
    return { ok: true };
  });

  app.get('/admin/ranking-config', { preHandler: requireAdmin }, async () => {
    const { rows } = await db.query('select key, value::float8 as value, note from ranking_config order by key');
    return rows;
  });

  app.put('/admin/ranking-config/:key', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ key: z.string().max(40) }), req.params, reply);
    const b = parse(z.object({ value: z.number().min(0).max(100000) }), req.body, reply);
    if (!p || !b) return;
    const { rowCount } = await db.query('update ranking_config set value = $2 where key = $1', [p.key, b.value]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Unknown setting.' });
    await db.query('select refresh_dish_stats()');
    return { ok: true };
  });
}
