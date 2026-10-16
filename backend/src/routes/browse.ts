import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, userId } from '../auth.js';
import { loadDishCards } from '../cards.js';
import { inCity, nearestCity } from '../geo.js';
import { googlePlacePhotos } from '../photos.js';

const coords = {
  lat: z.coerce.number().min(-90).max(90).optional(),
  lng: z.coerce.number().min(-180).max(180).optional(),
};
const uuid = z.string().uuid();

export async function browseRoutes(app: FastifyInstance) {
  const { db, config } = app;

  // Cities with dishes first (most dishes first), then the biggest. With q, a name search.
  app.get('/cities', async (req, reply) => {
    const q = parse(
      z.object({ q: z.string().trim().max(60).optional(), limit: z.coerce.number().int().min(1).max(1000).default(40) }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select c.id, c.slug, c.name, c.state, c.is_active as "isActive", c.radius_km as "radiusKm",
              st_y(c.center::geometry) as lat, st_x(c.center::geometry) as lng,
              coalesce(n.dishes, 0) as "dishCount"
       from cities c
       left join (select city_id, count(*)::int as dishes from dishes where status = 'live' group by city_id) n
         on n.city_id = c.id
       where $1::text is null or c.name ilike '%' || $1 || '%' or c.state ilike $1 || '%'
       order by ($1::text is not null and lower(c.name) = lower($1)) desc,
                ($1::text is not null and c.name ilike $1 || '%') desc,
                coalesce(n.dishes, 0) desc, c.population desc nulls last, c.name
       limit $2`,
      [q.q || null, q.limit],
    );
    return rows;
  });

  // The city for a coarse location; the location is not stored.
  app.get('/cities/nearest', async (req, reply) => {
    const q = parse(z.object({ lat: coords.lat.unwrap(), lng: coords.lng.unwrap() }), req.query, reply);
    if (!q) return;
    const city = await nearestCity(db, q.lat, q.lng);
    if (!city) return { city: null, inRange: false };
    return { city, inRange: inCity(city) };
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
              pr.handle as "authorHandle", pr.display_name as "authorName",
              (select count(*)::int from comments cm where cm.post_id = p.id and cm.status = 'live') as "commentCount"
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
              price_band as "priceBand", status, created_by as "createdBy", cuisine, opening_hours as "openingHours",
              coalesce(osm_tags->>'phone', osm_tags->>'contact:phone') as phone,
              coalesce(osm_tags->>'website', osm_tags->>'contact:website') as website,
              st_y(geo::geometry) as lat, st_x(geo::geometry) as lng,
              case when $2::float8 is not null and $3::float8 is not null
                   then round(st_distance(geo, st_setsrid(st_makepoint($3, $2), 4326)::geography))::int end as "distanceM"
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
    // Photos of the place itself, openly licensed (see photos.ts), with their credits.
    const { rows: photos } = await db.query(
      `select image_path as "imageUrl", thumb_path as "thumbUrl", placeholder_color as "placeholderColor",
              attribution, source_url as "sourceUrl", source
       from restaurant_photos where restaurant_id = $1 order by (source = 'owner') desc, created_at limit 10`,
      [p.id],
    );
    return {
      ...r,
      photos,
      googlePhotos: !!config.googlePlacesApiKey,
      dishes: await loadDishCards(db, dishIds.map((d) => d.id), viewer, q.lat, q.lng),
    };
  });

  // Google Maps photos of a place, fetched live (never stored). Empty when not configured.
  app.get('/restaurants/:id/google-photos', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    if (!config.googlePlacesApiKey) return { photos: [] };
    const { rows } = await db.query(
      `select r.id, r.name, r.locality, c.name as city, st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              r.google_place_id as "googlePlaceId"
       from restaurants r join cities c on c.id = r.city_id where r.id = $1 and r.status = 'active'`,
      [p.id],
    );
    if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'This place is not available.' });
    try {
      return {
        photos: await googlePlacePhotos(db, rows[0], { apiKey: config.googlePlacesApiKey, dailyLimit: config.googlePhotosDailyLimit }),
      };
    } catch (err) {
      req.log.warn(err);
      return { photos: [] };
    }
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
              round(st_distance(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography))::int as "distanceM"
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
}
