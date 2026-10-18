import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import type pg from 'pg';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';
import { type Db, inTransaction } from '../db.js';
import { notifyFollowers } from '../notify.js';

/**
 * Admins only. The role and email are re-read from the database on every admin request,
 * and the email must still be in ADMIN_EMAILS: removing someone from the list takes effect
 * at once instead of when their token expires, and a role set by hand in the database
 * alone isn't enough to get in.
 */
export async function requireAdmin(req: FastifyRequest, reply: FastifyReply) {
  await requireAuth(req, reply);
  if (reply.sent) return;
  const { rows } = await req.server.db.query<{ role: string; email: string }>(
    'select role, email from profiles where id = $1',
    [userId(req)],
  );
  const me = rows[0];
  if (me?.role !== 'admin' || !req.server.config.adminEmails.includes(me.email)) {
    return reply.code(403).send({ error: 'forbidden', message: 'Admins only.' });
  }
}

/**
 * Moves everything from dish [from] to dish [into] inside the caller's transaction:
 * votes (each person keeps one vote: their latest love or "not for me"), saves, photos,
 * views, notifications, messages and stories. [from] is hidden and remembered in
 * dish_merges so old links open the dish it became. The caller refreshes the ranking
 * afterwards, outside the transaction, so a big merge doesn't hold the ranking locks.
 */
export async function mergeDishesTx(tx: pg.PoolClient, from: string, into: string, by: string | null): Promise<void> {
  // Votes: the latest love / "not for me" per person wins; saves and the rest just join.
  await tx.query(
    `with v as (
       select distinct on (user_id) user_id, kind, created_at from engagements
       where dish_id = any($1::uuid[]) and kind in ('recommend', 'pass')
       order by user_id, created_at desc
     )
     delete from engagements e using v
     where e.dish_id = any($1::uuid[]) and e.user_id = v.user_id and e.kind in ('recommend', 'pass') and e.kind <> v.kind`,
    [[from, into]],
  );
  await tx.query(
    `insert into engagements (user_id, dish_id, kind, created_at)
     select user_id, $2, kind, created_at from engagements where dish_id = $1
     on conflict do nothing`,
    [from, into],
  );
  await tx.query('delete from engagements where dish_id = $1', [from]);
  for (const t of ['posts', 'dish_views', 'notifications', 'messages', 'stories']) {
    await tx.query('savepoint move');
    try {
      await tx.query(`update ${t} set dish_id = $2 where dish_id = $1`, [from, into]);
    } catch (err) {
      // A notification about both dishes is the same notification: keep one.
      if ((err as { code?: string }).code !== '23505') throw err;
      await tx.query('rollback to savepoint move');
      await tx.query(`delete from ${t} where dish_id = $1`, [from]);
    }
  }
  await tx.query("update dishes set status = 'hidden', cover_post_id = null where id = $1", [from]);
  await tx.query(
    `insert into dish_merges (from_id, into_id, merged_by) values ($1, $2, $3)
     on conflict (from_id) do update set into_id = excluded.into_id, merged_at = now()`,
    [from, into, by],
  );
  // Anything merged into [from] earlier now points at [into] too.
  await tx.query('update dish_merges set into_id = $2 where into_id = $1', [from, into]);
}

/** Rebuilds the feed points and People's Pick of the given dishes. */
async function refreshDishes(db: Db, ids: string[]) {
  for (const id of ids) {
    await db.query('select refresh_dish_stats($1)', [id]);
    await db.query('select refresh_people_pick($1)', [id]);
  }
}

/**
 * One place listed twice (someone added it, then it came in from OpenStreetMap): its
 * dishes move to [into]. A dish [into] already has under the same name is merged into
 * that one (votes, photos and links follow); the rest move as they are. Place photos,
 * stories and shared-place messages follow too, and the old place is hidden.
 */
export async function mergePlaces(db: Db, from: string, into: string, by: string | null) {
  return inTransaction(db, async (tx) => {
    const { rows: places } = await tx.query<{ id: string; city_id: number; status: string }>(
      'select id, city_id, status from restaurants where id = any($1::uuid[]) for update',
      [[from, into]],
    );
    const target = places.find((p) => p.id === into);
    if (places.length !== 2 || !target) return null;
    const { rows: dishes } = await tx.query<{ id: string; name_norm: string; status: string; twin: string | null; twin_status: string | null }>(
      `select d.id, d.name_norm, d.status, t.id as twin, t.status as twin_status
       from dishes d left join dishes t on t.restaurant_id = $2 and t.name_norm = d.name_norm
       where d.restaurant_id = $1`,
      [from, into],
    );
    let moved = 0;
    let merged = 0;
    const touched = new Set<string>();
    for (const d of dishes) {
      if (!d.twin) {
        await tx.query('update dishes set restaurant_id = $2, city_id = $3 where id = $1', [d.id, into, target.city_id]);
        moved++;
        touched.add(d.id);
      } else if (d.status !== 'hidden') {
        // A same-name dish hidden at the target comes back: it is the live one now.
        if (d.twin_status === 'hidden') await tx.query("update dishes set status = 'live' where id = $1", [d.twin]);
        await mergeDishesTx(tx, d.id, d.twin, by);
        merged++;
        touched.add(d.id).add(d.twin);
      }
      // A hidden dish (merged away earlier) whose name is taken at the target stays behind
      // with the hidden place; its dish_merges row already points at the live dish.
    }
    // Place photos: the same photo can't be listed twice for one place.
    await tx.query(
      `delete from restaurant_photos f using restaurant_photos t
       where f.restaurant_id = $1 and t.restaurant_id = $2 and t.source = f.source and t.source_ref = f.source_ref`,
      [from, into],
    );
    const { rowCount: photos } = await tx.query('update restaurant_photos set restaurant_id = $2 where restaurant_id = $1', [from, into]);
    await tx.query('update stories set restaurant_id = $2 where restaurant_id = $1', [from, into]);
    await tx.query('update messages set restaurant_id = $2 where restaurant_id = $1', [from, into]);
    await tx.query("update restaurants set status = 'hidden' where id = $1", [from]);
    return { moved, merged, photos: photos ?? 0, touched: [...touched] };
  });
}

export async function adminRoutes(app: FastifyInstance) {
  const { db } = app;

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
              coalesce(p.status, d.status, c.status) as status,
              coalesce(pd.name, d.name, u.handle, '"' || left(c.body, 80) || '"', '"' || left(m.body, 80) || '"',
                       case when s.id is not null then 'Story by @' || su.handle end) as label,
              coalesce(p.thumb_path, cp.thumb_path, s.thumb_path) as "thumbUrl", coalesce(p.dish_id, d.id, cp.dish_id) as "dishId"
       from reports rp
       left join posts p    on rp.target_type = 'post' and p.id = rp.target_id
       left join dishes pd  on pd.id = p.dish_id
       left join dishes d   on rp.target_type = 'dish' and d.id = rp.target_id
       left join profiles u on rp.target_type = 'user' and u.id = rp.target_id
       left join comments c on rp.target_type = 'comment' and c.id = rp.target_id
       left join posts cp   on cp.id = c.post_id
       left join messages m on rp.target_type = 'message' and m.id = rp.target_id
       left join stories s  on rp.target_type = 'story' and s.id = rp.target_id
       left join profiles su on su.id = s.user_id
       where rp.status = 'open'
       group by rp.target_type, rp.target_id, p.status, d.status, c.status, pd.name, d.name, u.handle, c.body,
                p.thumb_path, cp.thumb_path, p.dish_id, cp.dish_id, d.id, m.body, s.id, s.thumb_path, su.handle
       order by count(*) desc, max(rp.created_at) desc`,
    );
    // Photos that look like a copy of someone else's: they wait here until a person decides.
    const { rows: heldPhotos } = await db.query(
      `select p.id, p.thumb_path as "thumbUrl", p.created_at as "createdAt", d.id as "dishId", d.name as "dishName",
              a.handle as "authorHandle", o.thumb_path as "originalThumbUrl", oa.handle as "originalAuthorHandle",
              o.created_at as "originalCreatedAt"
       from posts p
       join dishes d on d.id = p.dish_id
       left join profiles a on a.id = p.author_id
       left join posts o on o.id = p.copy_of
       left join profiles oa on oa.id = o.author_id
       where p.status = 'pending' and p.copy_of is not null
       order by p.created_at`,
    );
    return { pendingPlaces, reported, heldPhotos };
  });

  app.post('/admin/restaurants/:id/:action', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid(), action: z.enum(['approve', 'hide']) }), req.params, reply);
    if (!p) return;
    const status = p.action === 'approve' ? 'active' : 'hidden';
    const { rows } = await db.query<{ city_id: number; was: string }>(
      `update restaurants r set status = $2 from (select id, status from restaurants where id = $1 for update) o
       where r.id = o.id returning r.city_id, o.status as was`,
      [p.id, status],
    );
    if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'Place not found.' });
    if (status === 'active' && rows[0].was !== 'active') {
      // The photos posted while it waited reach their authors' followers now.
      const { rows: posts } = await db.query<{ id: string; dish_id: string; author_id: string }>(
        `select p.id, p.dish_id, p.author_id from posts p join dishes d on d.id = p.dish_id
         where d.restaurant_id = $1 and p.status = 'live' and p.author_id is not null`,
        [p.id],
      );
      for (const post of posts) await notifyFollowers(db, { actorId: post.author_id, kind: 'post', postId: post.id, dishId: post.dish_id });
      // A newly approved place is new food near people in that city.
      await app.push.tickleCity(rows[0].city_id);
    }
    return { ok: true, status };
  });

  // Hide or restore a reported post, dish or comment. Either way its open reports are resolved.
  app.post('/admin/:type/:id/:action', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(
      z.object({ type: z.enum(['posts', 'dishes', 'comments']), id: z.string().uuid(), action: z.enum(['hide', 'restore', 'dismiss']) }),
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
      ({ posts: 'post', dishes: 'dish', comments: 'comment' } as const)[p.type],
      p.id,
    ]);
    // Hiding or restoring a photo or dish changes its rating (a verified photo adds weight,
    // a cover can change): re-rate it now rather than on the next timer.
    const dish = p.type === 'dishes' ? p.id
      : p.type === 'posts' ? (await db.query<{ dish_id: string }>('select dish_id from posts where id = $1', [p.id])).rows[0]?.dish_id
      : undefined;
    if (dish) {
      await db.query('select refresh_dish_stats($1)', [dish]);
      await db.query('select refresh_people_pick($1)', [dish]);
    }
    return { ok: true };
  });

  // A reported message or story: remove it (a story ends now, a message is deleted) or dismiss.
  app.post('/admin/talk/:type/:id/:action', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(
      z.object({ type: z.enum(['message', 'story']), id: z.string().uuid(), action: z.enum(['remove', 'dismiss']) }),
      req.params,
      reply,
    );
    if (!p) return;
    if (p.action === 'remove') {
      if (p.type === 'story') await db.query('update stories set expires_at = now() where id = $1', [p.id]);
      else await db.query('delete from messages where id = $1', [p.id]);
    }
    await db.query("update reports set status = 'resolved' where target_type = $1 and target_id = $2", [p.type, p.id]);
    return { ok: true };
  });

  app.post('/admin/users/:id/dismiss', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    if (!p) return;
    await db.query("update reports set status = 'resolved' where target_type = 'user' and target_id = $1", [p.id]);
    return { ok: true };
  });

  // Two names for one dish at one place ("Butter Chkn" and "Butter Chicken"): everything
  // moves to [into]. Each person still has one vote: if they voted on both, their latest
  // love or "not for me" stays. Old links to the merged dish open the one it became.
  app.post('/admin/dishes/:id/merge', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    const b = parse(z.object({ into: z.string().uuid() }), req.body, reply);
    if (!p || !b) return;
    if (p.id === b.into) return reply.code(400).send({ error: 'invalid_input', message: 'Pick a different dish to merge into.' });
    const { rows } = await db.query<{ id: string; restaurant_id: string }>(
      "select id, restaurant_id from dishes where id = any($1::uuid[]) and status <> 'hidden'",
      [[p.id, b.into]],
    );
    if (rows.length !== 2) return reply.code(404).send({ error: 'not_found', message: 'Dish not found.' });
    if (rows[0]!.restaurant_id !== rows[1]!.restaurant_id) {
      return reply.code(400).send({ error: 'invalid_input', message: 'Only dishes at the same place can be merged.' });
    }
    await inTransaction(db, (tx) => mergeDishesTx(tx, p.id, b.into, userId(req)));
    await refreshDishes(db, [p.id, b.into]);
    return { ok: true, into: b.into };
  });

  // The same place listed twice: everything moves to [into] and this one is hidden.
  app.post('/admin/restaurants/:id/merge', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    const b = parse(z.object({ into: z.string().uuid() }), req.body, reply);
    if (!p || !b) return;
    if (p.id === b.into) return reply.code(400).send({ error: 'invalid_input', message: 'Pick a different place to merge into.' });
    const r = await mergePlaces(db, p.id, b.into, userId(req));
    if (!r) return reply.code(404).send({ error: 'not_found', message: 'Place not found.' });
    await db.query(
      `insert into place_merges (from_id, into_id, merged_by) values ($1, $2, $3)
       on conflict (from_id) do update set into_id = excluded.into_id, merged_at = now()`,
      [p.id, b.into, userId(req)],
    );
    req.log.info({ from: p.id, into: b.into, by: userId(req), ...r, touched: undefined }, 'admin merged places');
    await refreshDishes(db, r.touched);
    return { ok: true, into: b.into, moved: r.moved, merged: r.merged, photos: r.photos };
  });

  // Dishes at one place whose names look alike: candidates for a merge.
  app.get('/admin/dishes/similar', { preHandler: requireAdmin }, async () => {
    const { rows } = await db.query(
      `select a.id as "aId", a.name as "aName", b.id as "bId", b.name as "bName", r.name as "placeName",
              (select count(*)::int from posts p where p.dish_id = a.id and p.status = 'live') as "aPosts",
              (select count(*)::int from posts p where p.dish_id = b.id and p.status = 'live') as "bPosts",
              round(similarity(a.name_norm, b.name_norm)::numeric, 2)::float8 as similarity
       from dishes a
       join dishes b on b.restaurant_id = a.restaurant_id and a.id < b.id and b.status = 'live'
       join restaurants r on r.id = a.restaurant_id
       where a.status = 'live' and similarity(a.name_norm, b.name_norm) >= 0.45
       order by similarity(a.name_norm, b.name_norm) desc limit 50`,
    );
    return rows;
  });

  // ---------- Is the ranking working? ----------

  app.get('/admin/health', { preHandler: requireAdmin }, async () => {
    const { rows: votesPerDay } = await db.query(
      `select to_char(d.day, 'YYYY-MM-DD') as day,
              count(e.*) filter (where e.kind = 'recommend')::int as loves,
              count(e.*) filter (where e.kind = 'pass')::int as passes,
              count(distinct e.user_id)::int as voters
       from generate_series(current_date - 13, current_date, interval '1 day') d(day)
       left join engagements e on e.created_at >= d.day and e.created_at < d.day + interval '1 day'
                              and e.kind in ('recommend', 'pass')
       group by d.day order by d.day`,
    );
    const { rows: [totals] } = await db.query(
      `select (select count(*)::int from dishes where status = 'live') as "liveDishes",
              (select count(*)::int from dish_rating where loves + passes >= 3) as "dishesWith3Votes",
              (select count(*)::int from engagements e join dishes d on d.id = e.dish_id
                 join restaurants r on r.id = d.restaurant_id
                where e.kind in ('recommend', 'pass') and e.user_id = r.created_by) as "ownPlaceVotesIgnored",
              (select count(*)::int from voter_independence where twins > 0) as "lockstepAccounts",
              (select count(*)::int from posts where status = 'pending' and copy_of is not null) as "copiesHeld",
              (select count(*)::int from posts where status = 'hidden' and copy_of is not null) as "copiesRemoved",
              (select count(*)::int from posts where status = 'live' and source = 'user') as "userPhotos",
              (select count(*)::int from posts where status = 'live' and visit = 'verified') as "verifiedPhotos",
              (select count(*)::int from dish_merges) as "dishesMerged"`,
    );
    // How much each city's Top changed in a week: the share of today's top 20 that's new.
    const { rows: topChange } = await db.query(
      `select c.name as city, t.day::text as day, w.day::text as "comparedWith",
              cardinality(t.dish_ids) as size,
              (select count(*)::int from unnest(t.dish_ids) x where not x = any(w.dish_ids)) as "newInTop"
       from top_snapshots t join cities c on c.id = t.city_id
       join lateral (select * from top_snapshots w where w.city_id = t.city_id and w.day <= t.day - 7
                     order by w.day desc limit 1) w on true
       where t.day = (select max(day) from top_snapshots)`,
    );
    // Card taps by feed tab over the last week: cards opened / cards seen.
    const { rows: taps } = await db.query(
      `select coalesce(tab, 'other') as tab, count(*) filter (where not opened)::int as seen,
              count(*) filter (where opened)::int as opened
       from dish_views where viewed_at > now() - interval '7 days'
       group by coalesce(tab, 'other') order by 2 desc`,
    );
    const prediction = {
      ...(await predictionCheck(30)),
      // Trust is learned from these same votes, so the check isn't fully out-of-sample.
      note: 'Each vote is predicted only from the votes cast before it, as if recommending at that moment. "Without trust" counts every voter equally: the gap is what trust weighting adds.',
    };
    return { totals, votesPerDay, topChange, taps, prediction };
  });

  /**
   * The prediction check, done honestly: every love or "not for me" in the last [days] days
   * is predicted from the votes cast on that dish BEFORE it, as if we had to recommend the
   * dish at that moment. Nothing from the future leaks in. "skill" compares that with always
   * guessing the overall share of loves: above 0 means the ranking knows something, 1 would
   * be perfect. "skillWithoutTrust" is the same with every voter counted equally, so the
   * difference shows what the trust weighting adds (trust itself is learned from all votes,
   * so a small part of its edge can still come from hindsight).
   */
  async function predictionCheck(days: number) {
    const { rows } = await db.query<{
      n: number; accuracy: number | null; brier: number | null; brier_plain: number | null; base_rate: number | null; base_brier: number | null;
    }>(
      `with votes as (
         select e.dish_id, e.kind, e.created_at,
                coalesce(ts.trust, tall.trust, cfg('trust_floor')) as trust,
                standing(p.created_at, vi.twins)
                  * (case when exists (select 1 from posts vp where vp.dish_id = e.dish_id and vp.author_id = e.user_id
                                         and vp.status = 'live' and vp.visit = 'verified')
                          then least(greatest(cfg('visit_weight'), 1), 2) else 1 end)
                  * decay(e.created_at, greatest(cfg('pp_half_life_days'), 0.01)) as w0
         from engagements e
         join dishes d on d.id = e.dish_id and d.status = 'live'
         join restaurants r on r.id = d.restaurant_id and e.user_id is distinct from r.created_by
         join profiles p on p.id = e.user_id
         left join voter_independence vi on vi.user_id = e.user_id
         left join taster_stats ts on ts.user_id = e.user_id and ts.category_id = coalesce(d.category_id, -1)
         left join taster_stats tall on tall.user_id = e.user_id and tall.category_id = 0
         where e.kind in ('recommend', 'pass')
       ),
       -- What the dish's earlier votes said, just before each vote.
       before as (
         select v.kind, v.created_at,
                coalesce(sum(v.trust * v.w0) filter (where v.kind = 'recommend') over earlier, 0) as wl,
                coalesce(sum(v.trust * v.w0) filter (where v.kind = 'pass') over earlier, 0) as wp,
                coalesce(sum(v.w0) filter (where v.kind = 'recommend') over earlier, 0) as pl,
                coalesce(sum(v.w0) filter (where v.kind = 'pass') over earlier, 0) as pp
         from votes v
         window earlier as (partition by v.dish_id order by v.created_at rows between unbounded preceding and 1 preceding)
       ),
       pred as (
         select (kind = 'recommend')::int as y,
                (wl + cfg('pp_prior_weight') * cfg('pp_prior')) / (wl + wp + cfg('pp_prior_weight')) as p,
                (pl + cfg('pp_prior_weight') * cfg('pp_prior')) / (pl + pp + cfg('pp_prior_weight')) as q
         from before where created_at > now() - make_interval(days => $1)
       ),
       base as (select avg(y)::float8 as r from pred)
       select count(*)::int as n,
              avg(((p >= 0.5)::int = y)::int)::float8 as accuracy,
              avg((y - p) ^ 2)::float8 as brier,
              avg((y - q) ^ 2)::float8 as brier_plain,
              (select r from base) as base_rate,
              avg((y - (select r from base)) ^ 2)::float8 as base_brier
       from pred`,
      [days],
    );
    const r = rows[0]!;
    const round = (x: number | null) => (x === null ? null : Math.round(x * 1000) / 1000);
    const skillOf = (b: number | null) => (b !== null && r.base_brier ? 1 - b / r.base_brier : null);
    return {
      days,
      votes: r.n,
      accuracy: round(r.accuracy),
      alwaysLoveAccuracy: r.base_rate === null ? null : round(Math.max(r.base_rate, 1 - r.base_rate)),
      skill: round(skillOf(r.brier)),
      skillWithoutTrust: round(skillOf(r.brier_plain)),
    };
  }

  app.get('/admin/ranking-config', { preHandler: requireAdmin }, async () => {
    const { rows } = await db.query('select key, value::float8 as value, note from ranking_config order by key');
    return rows;
  });

  app.put('/admin/ranking-config/:key', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ key: z.string().max(40) }), req.params, reply);
    const b = parse(z.object({ value: z.number().min(0).max(100000) }), req.body, reply);
    if (!p || !b) return;
    const [min, max] = SETTING_RANGES[p.key] ?? [0, 100000];
    if (b.value < min || b.value > max) {
      return reply.code(400).send({ error: 'out_of_range', message: `${p.key} must be between ${min} and ${max}.` });
    }
    const { rowCount } = await db.query('update ranking_config set value = $2 where key = $1', [p.key, b.value]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'Unknown setting.' });
    // Everything that reads the settings is rebuilt now, not on the next timer.
    await db.query('select refresh_dish_stats()');
    await db.query('select refresh_people_pick()');
    return { ok: true };
  });
}

/** Values that keep the maths meaningful (each is also clamped where it's used). */
const SETTING_RANGES: Record<string, [number, number]> = {
  pp_prior: [0.01, 0.99],
  pp_prior_weight: [0.1, 1000],
  pp_z: [0, 5],
  pp_half_life_days: [1, 3650],
  half_life_days: [1, 3650],
  trust_floor: [0.01, 10],
  trust_span: [0, 10],
  trust_prior_picks: [0.1, 100],
  standing_ramp_days: [0, 365],
  twin_window_hours: [0, 168],
  twin_min_votes: [1, 1000],
  twin_overlap: [0, 1],
  expert_min_standing: [0, 1],
  gem_top_fraction: [0, 1],
  fresh_days: [1, 365],
  visit_weight: [1, 2],
  place_max_in_row: [1, 20],
  place_spread: [0, 100],
  visit_radius_m: [50, 5000],
  photo_fresh_days: [0, 60],
  posts_per_dish_day: [1, 20],
  copy_distance: [0, 20],
  cover_days: [1, 3650],
};
