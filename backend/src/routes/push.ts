import type { FastifyInstance } from 'fastify';
import type pg from 'pg';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';

/**
 * Push setup for the app: the public Firebase values it starts with, and where it leaves
 * its token. While push is on, the server listens for 'wake' (see 009_notifications.sql)
 * and wakes those people's phones.
 */
export async function pushRoutes(app: FastifyInstance) {
  const { db, push } = app;

  app.get('/push/config', async () => {
    const c = push.config;
    if (!c?.app) return { enabled: false };
    return { enabled: true, projectId: c.projectId, ...c.app };
  });

  app.put('/me/push-token', { preHandler: requireAuth }, async (req, reply) => {
    const b = parse(z.object({ token: z.string().min(10).max(4096), cityId: z.number().int().positive().nullish() }), req.body, reply);
    if (!b) return;
    // A token belongs to one phone; if someone else signs in there, it moves to them.
    await db.query(
      `insert into push_tokens (token, user_id, city_id) values ($1, $2, (select id from cities where id = $3))
       on conflict (token) do update set user_id = excluded.user_id, city_id = excluded.city_id, updated_at = now()`,
      [b.token, userId(req), b.cityId ?? null],
    );
    return { ok: true };
  });

  app.delete('/me/push-token', { preHandler: requireAuth }, async (req, reply) => {
    const b = parse(z.object({ token: z.string().min(10).max(4096) }), req.body, reply);
    if (!b) return;
    await db.query('delete from push_tokens where token = $1 and user_id = $2', [b.token, userId(req)]);
    return { ok: true };
  });

  if (!push.enabled) return;
  // Every half hour in the day: wake the phones of people due their daily two-step
  // sign-in reminder (the phone then hears about it from /updates).
  const [from, to] = app.config.remindHours ?? [10, 20];
  const nudge = setInterval(() => {
    void db
      .query<{ user_id: string }>(
        `select distinct t.user_id from push_tokens t join profiles p on p.id = t.user_id
         where not p.two_factor
           and p.security_reminded_on is distinct from (now() at time zone 'Asia/Kolkata')::date
           and extract(hour from now() at time zone 'Asia/Kolkata') between $1 and $2
         limit 5000`,
        [from, to],
      )
      .then(({ rows }) => push.tickle(rows.map((r) => r.user_id)))
      .catch((err) => app.log.warn({ err }, 'reminder wake-ups failed'));
  }, 30 * 60_000);
  nudge.unref();
  app.addHook('onClose', async () => clearInterval(nudge));
  // One connection that hears every 'wake'; it reconnects if the database restarts.
  let client: pg.PoolClient | null = null;
  let closed = false;
  const listen = async () => {
    if (closed) return;
    try {
      client = await db.connect();
      client.on('notification', (n) => {
        if (n.channel === 'wake' && n.payload) push.tickle([n.payload]);
      });
      client.on('error', (err) => {
        app.log.warn({ err }, 'push listener lost its connection');
        client?.release(true);
        client = null;
        setTimeout(() => void listen(), 5000).unref();
      });
      await client.query('listen wake');
    } catch (err) {
      app.log.warn({ err }, 'push listener could not start');
      client?.release(true);
      client = null;
      setTimeout(() => void listen(), 5000).unref();
    }
  };
  await listen();
  app.addHook('onClose', async () => {
    closed = true;
    if (client) {
      await client.query('unlisten wake').catch(() => {});
      client.release();
    }
  });
}
