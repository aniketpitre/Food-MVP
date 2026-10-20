import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth } from '../auth.js';
import { foodLogo } from './discover.js';

/**
 * Explore by places: a list of the city's places with filters (area, dish type, cuisine,
 * pure veg, distance) and a sort, plus the facets the filter sheet offers. Read only: it shows
 * what the ranking already worked out (each place's best dish) and never changes a score.
 */
export async function exploreRoutes(app: FastifyInstance) {
  const { db } = app;

  app.get('/places', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(
      z.object({
        city: z.coerce.number().int().positive(),
        lat: z.coerce.number().min(-90).max(90).optional(),
        lng: z.coerce.number().min(-180).max(180).optional(),
        sort: z.enum(['nearest', 'loved', 'dishes', 'new']).default('loved'),
        area: z.string().trim().max(60).optional(),
        cuisine: z.string().trim().max(40).optional(),
        category: z.coerce.number().int().positive().optional(),
        veg: z.enum(['true', 'false']).default('false'),
        maxKm: z.coerce.number().min(0.5).max(50).optional(),
        // Places nobody has posted from yet: shown while the city is young unless asked.
        all: z.enum(['true', 'false']).optional(),
        limit: z.coerce.number().int().min(1).max(50).default(20),
        offset: z.coerce.number().int().min(0).max(5000).default(0),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const here = q.lat !== undefined && q.lng !== undefined;
    if ((q.sort === 'nearest' || q.maxKm) && !here) {
      return reply.code(400).send({ error: 'location', message: 'Allow location to sort or filter by distance.' });
    }
    const all = q.all !== undefined
      ? q.all === 'true'
      : (await db.query<{ y: boolean }>('select city_is_young($1) as y', [q.city])).rows[0]!.y;
    // A cuisine filter matches whole words ("thai" isn't in "thali").
    const cuisineRe = q.cuisine ? `\\m${q.cuisine.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\M` : null;
    const { rows } = await db.query(
      `with base as (
         select r.*,
                (select count(*)::int from dishes d where d.restaurant_id = r.id and d.status = 'live'
                   and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')) as dish_count,
                (select count(distinct e.user_id)::int from engagements e join dishes de on de.id = e.dish_id
                 where de.restaurant_id = r.id and de.status = 'live' and e.kind = 'recommend'
                   and not exists (select 1 from place_claimants pc where pc.restaurant_id = r.id and pc.user_id = e.user_id)) as lovers,
                -- "Most loved" ranks by the People's Pick weight of the loves (trust x standing),
                -- over dishes still up: a few throwaway accounts or a hidden dish don't count.
                (select coalesce(sum(dr.w_loves), 0)::float8 from dishes dl join dish_rating dr on dr.dish_id = dl.id
                 where dl.restaurant_id = r.id and dl.status = 'live') as w_loves,
                (select max(p.created_at) from posts p join dishes d on d.id = p.dish_id
                 where d.restaurant_id = r.id and p.status = 'live' and d.status = 'live') as last_post,
                case when $2::float8 is not null and $3::float8 is not null
                     then st_distance(r.geo, st_setsrid(st_makepoint($3, $2), 4326)::geography) end as dist
         from restaurants r
         where r.city_id = $1 and r.status = 'active'
           and ($4::text is null or lower(r.locality) = lower($4))
           and ($5::text is null or replace(lower(r.cuisine), '_', ' ') ~ $5)
           and ($6::int is null or exists (select 1 from dishes d where d.restaurant_id = r.id and d.status = 'live' and d.category_id = $6))
           and (not $7 or r.osm_tags->>'diet:vegetarian' = 'only'
                or (exists (select 1 from dishes d where d.restaurant_id = r.id and d.status = 'live')
                    and not exists (select 1 from dishes d where d.restaurant_id = r.id and d.status = 'live' and d.is_veg is not true)))
           and ($8::float8 is null or st_dwithin(r.geo, st_setsrid(st_makepoint($3, $2), 4326)::geography, $8 * 1000))
           -- Skip places nobody has posted from before counting anything (the city can hold
           -- thousands of imported places with no dishes).
           and ($9 or exists (select 1 from dishes d where d.restaurant_id = r.id and d.status = 'live'
                              and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')))
       )
       select b.id, b.name, b.locality, b.cuisine, b.address, b.opening_hours as "openingHours",
              (b.source = 'seed') as demo, st_y(b.geo::geometry) as lat, st_x(b.geo::geometry) as lng,
              b.dish_count as "dishCount", b.lovers, round(b.dist)::int as "distanceM",
              (b.osm_tags->>'diet:vegetarian' = 'only'
               or (b.dish_count > 0 and not exists (select 1 from dishes d where d.restaurant_id = b.id and d.status = 'live' and d.is_veg is not true))) as "pureVeg",
              (select rp.thumb_path from restaurant_photos rp where rp.restaurant_id = b.id
               order by (rp.source = 'owner') desc, rp.created_at limit 1) as "photoUrl",
              (select json_build_object('dishId', d2.id, 'name', d2.name, 'loves', coalesce(dr.loves, 0),
                        'percent', case when coalesce(dr.loves, 0) + coalesce(dr.passes, 0) > 0
                                        then round(100.0 * dr.loves / (dr.loves + dr.passes)) end,
                        'priceInr', d2.price_inr, 'isVeg', d2.is_veg, 'emoji', c.emoji,
                        'thumbUrl', (select thumb_path from posts where id = dish_cover(d2.id, null)))
               from dishes d2 left join dish_rating dr on dr.dish_id = d2.id
               left join dish_categories c on c.id = d2.category_id
               where d2.restaurant_id = b.id and d2.status = 'live'
                 and exists (select 1 from posts p where p.dish_id = d2.id and p.status = 'live')
               order by coalesce(dr.confidence, 0) desc, coalesce(dr.loves, 0) desc, d2.id limit 1) as "topDish",
              (select coalesce(json_agg(x.name), '[]'::json) from (
                 select d3.name from dishes d3 left join dish_rating dr3 on dr3.dish_id = d3.id
                 where d3.restaurant_id = b.id and d3.status = 'live'
                   and exists (select 1 from posts p where p.dish_id = d3.id and p.status = 'live')
                 order by coalesce(dr3.confidence, 0) desc, coalesce(dr3.loves, 0) desc, d3.id limit 3) x) as "dishNames"
       from base b
       where $9 or b.dish_count > 0
       order by
         case when $10 = 'nearest' then b.dist end asc nulls last,
         case when $10 = 'loved' then b.w_loves end desc,
         case when $10 = 'dishes' then b.dish_count end desc,
         case when $10 = 'new' then b.last_post end desc nulls last,
         b.dish_count desc, b.dist asc nulls last, b.id
       limit $11 offset $12`,
      [q.city, q.lat ?? null, q.lng ?? null, q.area || null, cuisineRe, q.category ?? null, q.veg === 'true',
       q.maxKm ?? null, all, q.sort, q.limit, q.offset],
    );
    return {
      items: rows.map((r) => ({ ...r, emoji: r.topDish?.emoji ?? foodLogo(r.cuisine, r.name) })),
      nextOffset: rows.length < q.limit ? null : q.offset + rows.length,
    };
  });

  // What the filter sheet offers in a city: its areas, cuisines and dish types, with counts.
  app.get('/places/facets', async (req, reply) => {
    const q = parse(z.object({ city: z.coerce.number().int().positive() }), req.query, reply);
    if (!q) return;
    const withDishes = `exists (select 1 from dishes d where d.restaurant_id = r.id and d.status = 'live'
                          and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live'))`;
    const [areas, cuisines, categories] = await Promise.all([
      db.query(
        `select r.locality as name, count(*)::int as places, count(*) filter (where ${withDishes})::int as "withDishes"
         from restaurants r where r.city_id = $1 and r.status = 'active' and coalesce(r.locality, '') <> ''
         group by r.locality order by "withDishes" desc, places desc, r.locality limit 60`,
        [q.city],
      ),
      db.query(
        `select initcap(replace(trim(c), '_', ' ')) as name, count(*)::int as places,
                count(*) filter (where ${withDishes})::int as "withDishes"
         from restaurants r, unnest(string_to_array(r.cuisine, ';')) c
         where r.city_id = $1 and r.status = 'active' and trim(c) <> ''
         group by 1 order by "withDishes" desc, places desc, 1 limit 40`,
        [q.city],
      ),
      db.query(
        `select c.id, c.name, c.emoji, count(distinct d.id)::int as dishes, count(distinct d.restaurant_id)::int as places
         from dish_categories c
         join dishes d on d.category_id = c.id and d.city_id = $1 and d.status = 'live'
           and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
         group by c.id order by dishes desc, c.sort limit 40`,
        [q.city],
      ),
    ]);
    return { areas: areas.rows, cuisines: cuisines.rows, categories: categories.rows };
  });
}
