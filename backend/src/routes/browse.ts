import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, userId } from '../auth.js';
import { loadDishCards } from '../cards.js';

const coords = {
  lat: z.coerce.number().min(-90).max(90).optional(),
  lng: z.coerce.number().min(-180).max(180).optional(),
};
const uuid = z.string().uuid();

export async function browseRoutes(app: FastifyInstance) {
  const { db } = app;

  app.get('/cities', async () => {
    const { rows } = await db.query(
      `select id, slug, name, state, is_active as "isActive", radius_km as "radiusKm",
              st_y(center::geometry) as lat, st_x(center::geometry) as lng
       from cities order by is_active desc, name`,
    );
    return rows;
  });

  // Nearest active city to a coarse location; the location is not stored.
  app.get('/cities/nearest', async (req, reply) => {
    const q = parse(z.object({ lat: coords.lat.unwrap(), lng: coords.lng.unwrap() }), req.query, reply);
    if (!q) return;
    const { rows } = await db.query(
      `select id, slug, name, state, radius_km as "radiusKm",
              round(st_distance(center, st_setsrid(st_makepoint($2, $1), 4326)::geography) / 1000) as "distanceKm"
       from cities where is_active
       order by center <-> st_setsrid(st_makepoint($2, $1), 4326)::geography limit 1`,
      [q.lat, q.lng],
    );
    const city = rows[0];
    if (!city) return { city: null, inRange: false };
    return { city, inRange: Number(city.distanceKm) <= Number(city.radiusKm) };
  });

  app.get('/categories', async () => {
    const { rows } = await db.query('select id, slug, name, emoji from dish_categories order by sort, name');
    return rows;
  });

  app.get('/feed', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(
      z.object({
        city: z.coerce.number().int().positive(),
        category: z.coerce.number().int().positive().optional(),
        tab: z.enum(['top', 'trending', 'gems', 'nearby']).default('top'),
        limit: z.coerce.number().int().min(1).max(50).default(20),
        offset: z.coerce.number().int().min(0).max(5000).default(0),
        seed: z.string().max(64).default(''),
        ...coords,
      }),
      req.query,
      reply,
    );
    if (!q) return;
    if (q.tab === 'nearby' && (q.lat === undefined || q.lng === undefined)) {
      return reply.code(400).send({ error: 'location_required', message: 'Nearby needs your location.' });
    }
    const viewer = userId(req);
    const { rows } = await db.query<{ dish_id: string; lane: string }>(
      'select * from get_feed($1, $2, $3, $4, $5, $6, $7, $8, $9)',
      [q.city, q.category ?? null, q.tab, q.lat ?? null, q.lng ?? null, q.limit, q.offset, q.seed, viewer],
    );
    const cards = await loadDishCards(db, rows.map((r) => r.dish_id), viewer, q.lat, q.lng);
    cards.forEach((c, i) => (c.lane = rows[i]!.lane));
    return { items: cards, nextOffset: rows.length < q.limit ? null : q.offset + rows.length };
  });

  app.get('/dishes/:id', { preHandler: optionalAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const q = parse(z.object(coords), req.query, reply);
    if (!q) return;
    const viewer = userId(req);
    const { rows: found } = await db.query<{ status: string; restaurant_id: string; category_id: number | null; description: string | null; created_by: string | null }>(
      'select status, restaurant_id, category_id, description, created_by from dishes where id = $1',
      [p.id],
    );
    const dish = found[0];
    if (!dish || (dish.status !== 'live' && dish.created_by !== viewer)) {
      return reply.code(404).send({ error: 'not_found', message: 'This dish is not available.' });
    }
    const [card] = await loadDishCards(db, [p.id], viewer, q.lat, q.lng);
    const { rows: posts } = await db.query(
      `select p.id, p.caption, p.image_path as "imageUrl", p.thumb_path as "thumbUrl",
              p.placeholder_color as "placeholderColor", p.width, p.height, p.source,
              p.photo_credit as "photoCredit", p.is_sponsored as "isSponsored", p.sponsor_note as "sponsorNote",
              p.instagram_url as "instagramUrl", p.created_at as "createdAt",
              pr.handle as "authorHandle", pr.display_name as "authorName"
       from posts p left join profiles pr on pr.id = p.author_id
       where p.dish_id = $1 and p.status = 'live'
         and ($2::uuid is null or not exists
              (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = p.author_id))
       order by (p.id = (select cover_post_id from dishes where id = $1)) desc, p.created_at desc
       limit 20`,
      [p.id, viewer],
    );
    const { rows: recommenders } = await db.query(
      `select pr.handle, pr.display_name as "displayName"
       from engagements e join profiles pr on pr.id = e.user_id
       where e.dish_id = $1 and e.kind in ('recommend', 'tried')
       group by pr.id, pr.handle, pr.display_name
       order by max(e.created_at) desc limit 12`,
      [p.id],
    );
    const { rows: more } = await db.query<{ id: string }>(
      `select d.id from dishes d left join dish_stats s on s.dish_id = d.id
       where d.restaurant_id = $1 and d.id <> $2 and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
       order by coalesce(s.score, 0) desc, d.featured_rank nulls last, d.created_at desc limit 6`,
      [dish.restaurant_id, p.id],
    );
    return {
      ...card,
      description: dish.description,
      posts,
      recommenders,
      moreAtPlace: await loadDishCards(db, more.map((m) => m.id), viewer, q.lat, q.lng),
    };
  });

  app.get('/restaurants/:id', { preHandler: optionalAuth }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const q = parse(z.object(coords), req.query, reply);
    if (!q) return;
    const viewer = userId(req);
    const { rows } = await db.query(
      `select id, name, locality, address, maps_url as "mapsUrl", instagram_handle as "instagramHandle",
              price_band as "priceBand", status, created_by as "createdBy",
              st_y(geo::geometry) as lat, st_x(geo::geometry) as lng,
              case when $2::float8 is not null and $3::float8 is not null
                   then round(st_distance(geo, st_setsrid(st_makepoint($3, $2), 4326)::geography)) end as "distanceM"
       from restaurants where id = $1`,
      [p.id, q.lat ?? null, q.lng ?? null],
    );
    const r = rows[0];
    if (!r || (r.status !== 'active' && r.createdBy !== viewer)) {
      return reply.code(404).send({ error: 'not_found', message: 'This place is not available.' });
    }
    const { rows: dishIds } = await db.query<{ id: string }>(
      `select d.id from dishes d left join dish_stats s on s.dish_id = d.id
       where d.restaurant_id = $1 and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
       order by coalesce(s.score, 0) desc, d.featured_rank nulls last, d.created_at desc limit 50`,
      [p.id],
    );
    delete r.createdBy;
    return { ...r, dishes: await loadDishCards(db, dishIds.map((d) => d.id), viewer, q.lat, q.lng) };
  });

  // Dish and place search with typo tolerance (trigram similarity) and the alias table.
  app.get('/search', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(
      z.object({ q: z.string().trim().min(2).max(60), city: z.coerce.number().int().positive(), ...coords }),
      req.query,
      reply,
    );
    if (!q) return;
    const viewer = userId(req);
    const { rows: dishIds } = await db.query<{ id: string }>(
      `with term as (select norm_name($1) as t),
       alias_hits as (
         select a.canonical, a.category_id from dish_aliases a, term
         where word_similarity(term.t, a.alias_norm) >= 0.5 or a.alias_norm like term.t || '%')
       select d.id from term, dishes d
       join restaurants r on r.id = d.restaurant_id and r.status = 'active'
       left join dish_stats s on s.dish_id = d.id
       where d.city_id = $2 and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
         and (word_similarity(term.t, d.name_norm) >= 0.5 or d.name_norm like '%' || term.t || '%'
              or d.name_norm in (select norm_name(canonical) from alias_hits)
              or d.category_id in (select category_id from alias_hits where category_id is not null)
              or word_similarity(term.t, r.name_norm) >= 0.5 or r.name_norm like '%' || term.t || '%')
       order by greatest(word_similarity(term.t, d.name_norm), word_similarity(term.t, r.name_norm)) desc,
                coalesce(s.score, 0) desc
       limit 30`,
      [q.q, q.city],
    );
    const { rows: places } = await db.query(
      `select id, name, locality from restaurants, (select norm_name($1) as t) term
       where city_id = $2 and status = 'active' and (word_similarity(term.t, name_norm) >= 0.5 or name_norm like '%' || term.t || '%')
       order by word_similarity(term.t, name_norm) desc limit 10`,
      [q.q, q.city],
    );
    return { dishes: await loadDishCards(db, dishIds.map((d) => d.id), viewer, q.lat, q.lng), restaurants: places };
  });

  // Dish-name suggestions for the add flow: alias table first, then existing dishes.
  app.get('/suggest/dishes', async (req, reply) => {
    const q = parse(
      z.object({ q: z.string().trim().min(1).max(60), city: z.coerce.number().int().positive().optional() }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `with term as (select norm_name($1) as t)
       select name, "categoryId" from (
         select distinct on (lower(canonical)) canonical as name, category_id as "categoryId",
                word_similarity(term.t, alias_norm) as sim
         from dish_aliases, term
         where word_similarity(term.t, alias_norm) >= 0.5 or alias_norm like term.t || '%'
         order by lower(canonical), word_similarity(term.t, alias_norm) desc
       ) a
       union
       select name, "categoryId" from (
         select distinct on (name_norm) name, category_id as "categoryId"
         from dishes, term
         where status = 'live' and ($2::int is null or city_id = $2)
           and (word_similarity(term.t, name_norm) >= 0.5 or name_norm like term.t || '%')
         limit 10
       ) d
       limit 10`,
      [q.q, q.city ?? null],
    );
    return rows;
  });

  app.get('/restaurants/nearby', async (req, reply) => {
    const q = parse(
      z.object({
        lat: coords.lat.unwrap(),
        lng: coords.lng.unwrap(),
        radius: z.coerce.number().int().min(100).max(10000).default(2000),
        q: z.string().trim().max(60).optional(),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select id, name, locality,
              round(st_distance(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography)) as "distanceM"
       from restaurants
       where status = 'active'
         and st_dwithin(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography, $3)
         and ($4::text is null or word_similarity(norm_name($4), name_norm) >= 0.5 or name_norm like '%' || norm_name($4) || '%')
       order by geo <-> st_setsrid(st_makepoint($2, $1), 4326)::geography
       limit 30`,
      [q.lat, q.lng, q.radius, q.q || null],
    );
    return rows;
  });

  // Place search without location (for people who deny location in the add flow).
  app.get('/restaurants', async (req, reply) => {
    const q = parse(
      z.object({ city: z.coerce.number().int().positive(), q: z.string().trim().max(60).default('') }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select id, name, locality from restaurants
       where city_id = $1 and status = 'active'
         and ($2 = '' or word_similarity(norm_name($2), name_norm) >= 0.5 or name_norm like '%' || norm_name($2) || '%')
       order by case when $2 = '' then 0 else word_similarity(norm_name($2), name_norm) end desc, name
       limit 30`,
      [q.city, q.q],
    );
    return rows;
  });

  app.get('/users/:handle', { preHandler: optionalAuth }, async (req, reply) => {
    const p = parse(z.object({ handle: z.string().toLowerCase().max(20) }), req.params, reply);
    if (!p) return;
    const viewer = userId(req);
    const { rows } = await db.query(
      `select id, handle, display_name as "displayName", role, created_at as "createdAt",
              (select count(*) from posts where author_id = profiles.id and status = 'live')::int as posts,
              (select count(*) from engagements where user_id = profiles.id and kind in ('recommend','tried'))::int as recommendations
       from profiles where handle = $1`,
      [p.handle],
    );
    const u = rows[0];
    if (!u) return reply.code(404).send({ error: 'not_found', message: 'No such person.' });
    const isMe = viewer === u.id;
    const { rows: posts } = await db.query(
      `select p.id, p.dish_id as "dishId", p.thumb_path as "thumbUrl", p.placeholder_color as "placeholderColor",
              p.status, d.name as "dishName", r.name as "restaurantName", r.status as "restaurantStatus"
       from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
       where p.author_id = $1 and ($2 or (p.status = 'live' and d.status = 'live' and r.status = 'active'))
       order by p.created_at desc limit 60`,
      [u.id, isMe],
    );
    const blocked = viewer
      ? (await db.query('select 1 from blocks where blocker_id = $1 and blocked_id = $2', [viewer, u.id])).rowCount! > 0
      : false;
    delete u.id;
    return { ...u, isMe, blockedByMe: blocked, posts };
  });
}
