import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, userId } from '../auth.js';
import { isExpert, loadDishCards } from '../cards.js';

const coords = {
  lat: z.coerce.number().min(-90).max(90).optional(),
  lng: z.coerce.number().min(-180).max(180).optional(),
};

/** People whose picks for these dishes the community backed, most trusted first. */
async function tastersFor(app: FastifyInstance, dishIds: string[], viewer: string | null, limit: number) {
  if (dishIds.length === 0) return [];
  const { rows } = await app.db.query(
    `select pr.handle, pr.display_name as "displayName", pr.avatar_path as "avatarUrl",
            max(ts.trust) as trust, max(ts.picks)::int as picks, max(ts.backers)::int as backers,
            (array_agg(d.name order by coalesce(dr.confidence, 0) desc))[1] as "topPick"
     from engagements e
     join dishes d on d.id = e.dish_id
     join profiles pr on pr.id = e.user_id
     join taster_stats ts on ts.user_id = e.user_id and ts.category_id = coalesce(d.category_id, -1)
     left join dish_rating dr on dr.dish_id = d.id
     where e.dish_id = any($1) and e.kind = 'recommend' and ts.picks > 0
       and ($2::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = pr.id))
     group by pr.id, pr.handle, pr.display_name, pr.avatar_path
     order by max(ts.trust) desc, max(ts.backers) desc
     limit $3`,
    [dishIds, viewer, limit],
  );
  return rows.map((r) => ({
    handle: r.handle,
    displayName: r.displayName,
    avatarUrl: r.avatarUrl,
    picks: r.picks,
    backers: r.backers,
    topPick: r.topPick,
    isExpert: isExpert(r.trust, r.picks),
  }));
}

/**
 * Discovery built around the real question, "what should I eat?":
 *  - /craving: type a dish, get the places ranked by the People's Pick (weighted loves vs
 *    "not for me" from people whose picks others backed), plus the people to trust for it;
 *  - /home: the sections of the home screen;
 *  - /map: the places to pin on the in-app map.
 */
export async function discoverRoutes(app: FastifyInstance) {
  const { db } = app;

  app.get('/craving', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(
      z.object({ q: z.string().trim().min(2).max(60), city: z.coerce.number().int().positive(), ...coords }),
      req.query,
      reply,
    );
    if (!q) return;
    const viewer = userId(req);
    // What the person means: a known dish (any spelling), a dish type, or free text.
    const { rows: meaning } = await db.query<{ canonical: string | null; category_id: number | null; category: string | null }>(
      `with term as (select norm_name($1) as t)
       select a.canonical, a.category_id, c.name as category
       from term, dish_aliases a left join dish_categories c on c.id = a.category_id
       where a.alias_norm = term.t or word_similarity(term.t, a.alias_norm) >= 0.6
       order by (a.alias_norm = term.t) desc, word_similarity(term.t, a.alias_norm) desc
       limit 1`,
      [q.q],
    );
    const { rows: cat } = await db.query<{ id: number; name: string }>(
      `select id, name from dish_categories, (select norm_name($1) as t) term
       where norm_name(name) = term.t or norm_name(slug) = term.t or word_similarity(term.t, norm_name(name)) >= 0.7
       order by word_similarity(term.t, norm_name(name)) desc limit 1`,
      [q.q],
    );
    const canonical = meaning[0]?.canonical ?? null;
    const categoryId = cat[0]?.id ?? null;
    const label = canonical ?? cat[0]?.name ?? q.q.replace(/\b\w/g, (c) => c.toUpperCase());

    const { rows } = await db.query<{ id: string }>(
      `with term as (select norm_name($1) as t)
       select d.id from term, dishes d
       join restaurants r on r.id = d.restaurant_id and r.status = 'active'
       left join dish_rating dr on dr.dish_id = d.id
       left join dish_stats s on s.dish_id = d.id
       where d.city_id = $2 and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live'
                     and ($5::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $5 and b.blocked_id = p.author_id)))
         and (
           -- A known dish: that dish under any spelling (and close typos), not its neighbours
           -- ("pav bhaji" must not bring up "kanda bhaji").
           ($3::text is not null and (d.name_norm = norm_name($3) or d.name_norm like '%' || norm_name($3) || '%'
                                      or similarity(norm_name($3), d.name_norm) >= 0.75))
           -- A dish type: everything of that type.
           or ($4::int is not null and d.category_id = $4)
           -- Anything else: fuzzy match on the dish name.
           or ($3::text is null and $4::int is null
               and (word_similarity(term.t, d.name_norm) >= 0.6 or d.name_norm like '%' || term.t || '%')))
       order by coalesce(dr.confidence, 0) desc, coalesce(dr.loves, 0) desc, coalesce(s.score, 0) desc,
                word_similarity(term.t, d.name_norm) desc
       limit 30`,
      [q.q, q.city, canonical, categoryId, viewer],
    );
    const ids = rows.map((r) => r.id);
    return {
      query: q.q,
      label,
      results: await loadDishCards(db, ids, viewer, q.lat, q.lng),
      tasters: await tastersFor(app, ids, viewer, 6),
    };
  });

  app.get('/home', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(z.object({ city: z.coerce.number().int().positive(), ...coords }), req.query, reply);
    if (!q) return;
    const viewer = userId(req);
    const visible = `d.city_id = $1 and d.status = 'live'
      and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live'
                  and ($2::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = p.author_id)))
      and exists (select 1 from restaurants r where r.id = d.restaurant_id and r.status = 'active')`;

    const picks = await db.query<{ id: string }>(
      `select d.id from dishes d join dish_rating dr on dr.dish_id = d.id
       where ${visible} and dr.loves > 0
       order by dr.confidence desc, dr.loves desc limit 10`,
      [q.city, viewer],
    );
    const trending = await db.query<{ id: string }>(
      `select d.id from dishes d join dish_stats s on s.dish_id = d.id
       where ${visible} and s.score_7d > 0 order by s.score_7d desc limit 10`,
      [q.city, viewer],
    );
    const fresh = await db.query<{ id: string }>(
      `select d.id from dishes d where ${visible} order by d.created_at desc limit 10`,
      [q.city, viewer],
    );
    const following = viewer
      ? await db.query<{ id: string; handle: string }>(
          `select distinct on (d.id) d.id, pr.handle, e.created_at from engagements e
           join follows f on f.followee_id = e.user_id and f.follower_id = $2
           join dishes d on d.id = e.dish_id join profiles pr on pr.id = e.user_id
           where ${visible} and e.kind = 'recommend' and e.created_at > now() - interval '60 days'
           order by d.id, e.created_at desc`,
          [q.city, viewer],
        )
      : { rows: [] as { id: string; handle: string }[] };
    const nearby = q.lat !== undefined && q.lng !== undefined
      ? await db.query<{ id: string }>(
          `select d.id from dishes d join restaurants r on r.id = d.restaurant_id
           left join dish_rating dr on dr.dish_id = d.id
           where ${visible} and st_dwithin(r.geo, st_setsrid(st_makepoint($4, $3), 4326)::geography, 5000)
           order by coalesce(dr.confidence, 0) desc limit 10`,
          [q.city, viewer, q.lat, q.lng],
        )
      : { rows: [] as { id: string }[] };
    // Cravings to tap: dish types people here actually post, most loved first.
    const { rows: cravings } = await db.query(
      `select c.name as label, c.emoji, count(distinct d.id)::int as dishes
       from dish_categories c join dishes d on d.category_id = c.id
       left join dish_rating dr on dr.dish_id = d.id
       where ${visible}
       group by c.id, c.name, c.emoji order by coalesce(sum(dr.loves), 0) desc, c.sort
       limit 12`,
      [q.city, viewer],
    );
    const { rows: cityDishes } = await db.query<{ id: string }>(`select d.id from dishes d where ${visible}`, [q.city, viewer]);

    const cards = (ids: { id: string }[]) => loadDishCards(db, ids.map((r) => r.id), viewer, q.lat, q.lng);
    const followedRows = following.rows.slice(0, 10);
    const followedCards = await cards(followedRows);
    return {
      cravings,
      peoplesPicks: await cards(picks.rows),
      nearby: await cards(nearby.rows),
      following: followedCards.map((c, i) => ({ card: c, lovedBy: followedRows[i]!.handle })),
      trending: await cards(trending.rows),
      fresh: await cards(fresh.rows),
      tasters: await tastersFor(app, cityDishes.map((d) => d.id), viewer, 10),
    };
  });

  // Places for the map: the ones with dishes on FoodFeed, each with its best dish.
  // With all=true, also real places nobody has posted from yet (imported from OpenStreetMap).
  app.get('/map', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(
      z.object({ city: z.coerce.number().int().positive(), all: z.enum(['true', 'false']).default('false') }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select r.id, r.name, r.locality, r.cuisine, st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              count(d.id)::int as "dishCount",
              (select json_build_object('dishId', d2.id, 'name', d2.name, 'loves', coalesce(dr.loves, 0),
                        'percent', case when coalesce(dr.loves, 0) + coalesce(dr.passes, 0) > 0
                                        then round(100.0 * dr.loves / (dr.loves + dr.passes)) end,
                        'thumbUrl', (select p.thumb_path from posts p where p.dish_id = d2.id and p.status = 'live'
                                     order by (p.id = d2.cover_post_id) desc, p.created_at desc limit 1))
               from dishes d2 left join dish_rating dr on dr.dish_id = d2.id
               where d2.restaurant_id = r.id and d2.status = 'live'
                 and exists (select 1 from posts p where p.dish_id = d2.id and p.status = 'live')
               order by coalesce(dr.confidence, 0) desc, coalesce(dr.loves, 0) desc limit 1) as "topDish"
       from restaurants r
       left join dishes d on d.restaurant_id = r.id and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
       where r.city_id = $1 and r.status = 'active'
       group by r.id
       having $2 or count(d.id) > 0
       order by count(d.id) desc
       limit 3000`,
      [q.city, q.all === 'true'],
    );
    return { places: rows };
  });
}
