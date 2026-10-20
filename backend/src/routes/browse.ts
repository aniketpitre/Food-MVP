import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, userId } from '../auth.js';
import { loadDishCards } from '../cards.js';
import { forYouCards } from './taste.js';
import { inCity, nearestCity } from '../geo.js';
import { googlePlaceId, googlePlacePhotos } from '../photos.js';
import { cuisineBySlug, cuisineForQuery, dishInCuisineSql } from './search.js';

const coords = {
  lat: z.coerce.number().min(-90).max(90).optional(),
  lng: z.coerce.number().min(-180).max(180).optional(),
};
const uuid = z.string().uuid();

/**
 * Feed orders by scroll session (the app sends one seed per session): the first page ranks
 * the whole lane once, later pages read from that list, so a vote landing mid-scroll can't
 * shift dishes between pages (shown twice, or skipped).
 */
const FEED_CACHE_MS = 15 * 60_000;
const FEED_CACHE_MAX = 2000;
const FEED_DEPTH = 300;
const feedCache = new Map<string, { at: number; rows: { dish_id: string; lane: string }[] }>();

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
        tab: z.enum(['top', 'trending', 'gems', 'nearby', 'foryou']).default('top'),
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
    if (q.tab === 'foryou') {
      if (!viewer) return reply.code(401).send({ error: 'sign_in', message: 'Sign in to see dishes picked for you.' });
      return forYouCards(db, viewer, q.city, q.limit, q.offset, q.lat, q.lng, q.category);
    }
    const key = q.seed
      ? [q.city, q.category ?? '', q.tab, q.seed, viewer ?? '', q.lat?.toFixed(2) ?? '', q.lng?.toFixed(2) ?? ''].join('|')
      : null;
    let rows: { dish_id: string; lane: string }[];
    const cached = key ? feedCache.get(key) : undefined;
    if (key && q.offset + q.limit <= FEED_DEPTH && (q.offset === 0 || (cached && Date.now() - cached.at < FEED_CACHE_MS))) {
      let list = cached?.rows;
      if (q.offset === 0 || !list) {
        list = (await db.query<{ dish_id: string; lane: string }>(
          'select * from get_feed($1, $2, $3, $4, $5, $6, 0, $7, $8)',
          [q.city, q.category ?? null, q.tab, q.lat ?? null, q.lng ?? null, FEED_DEPTH, q.seed, viewer],
        )).rows;
        feedCache.delete(key);
        feedCache.set(key, { at: Date.now(), rows: list });
        while (feedCache.size > FEED_CACHE_MAX) feedCache.delete(feedCache.keys().next().value!);
      }
      rows = list.slice(q.offset, q.offset + q.limit);
    } else {
      rows = (await db.query<{ dish_id: string; lane: string }>(
        'select * from get_feed($1, $2, $3, $4, $5, $6, $7, $8, $9)',
        [q.city, q.category ?? null, q.tab, q.lat ?? null, q.lng ?? null, q.limit, q.offset, q.seed, viewer],
      )).rows;
    }
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
    // A dish merged into another opens as that one.
    const { rows: merged } = await db.query<{ into_id: string }>('select into_id from dish_merges where from_id = $1', [p.id]);
    if (merged[0]) p.id = merged[0].into_id;
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
              pr.avatar_path as "authorAvatarUrl",
              (p.visit = 'verified') as "verifiedVisit",
              (select count(*)::int from comments cm where cm.post_id = p.id and cm.status = 'live') as "commentCount"
       from posts p left join profiles pr on pr.id = p.author_id
       where p.dish_id = $1 and p.status = 'live'
         and ($2::uuid is null or not exists
              (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = p.author_id))
       order by (p.id = dish_cover($1, $2::uuid)) desc, p.created_at desc
       limit 20`,
      [p.id, viewer],
    );
    // People who loved it. "Tried" alone (or "tried it, not for me") is not a
    // recommendation, and whoever added the place isn't listed for its own dishes.
    const { rows: recommenders } = await db.query(
      `select pr.handle, pr.display_name as "displayName"
       from engagements e join profiles pr on pr.id = e.user_id
       join dishes d on d.id = e.dish_id join restaurants r on r.id = d.restaurant_id
       where e.dish_id = $1 and e.kind = 'recommend'
         and not exists (select 1 from place_claimants pc where pc.restaurant_id = r.id and pc.user_id = e.user_id)
         and ($2::uuid is null or not exists (select 1 from blocks b where (b.blocker_id = $2 and b.blocked_id = pr.id) or (b.blocker_id = pr.id and b.blocked_id = $2)))
       order by e.created_at desc, pr.handle limit 12`,
      [p.id, viewer],
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
              price_band as "priceBand", status, created_by as "createdBy", source, cuisine, opening_hours as "openingHours",
              coalesce(osm_tags->>'phone', osm_tags->>'contact:phone') as phone,
              coalesce(osm_tags->>'website', osm_tags->>'contact:website') as website,
              st_y(geo::geometry) as lat, st_x(geo::geometry) as lng,
              case when $2::float8 is not null and $3::float8 is not null
                   then round(st_distance(geo, st_setsrid(st_makepoint($3, $2), 4326)::geography))::int end as "distanceM"
       from restaurants where id = $1`,
      [p.id, q.lat ?? null, q.lng ?? null],
    );
    const r = rows[0];
    // A new place waiting for review can be opened (its posts already show in Following);
    // it just isn't in the rankings yet. Hidden places are gone.
    if (!r || (r.status === 'hidden' && r.createdBy !== viewer)) {
      return reply.code(404).send({ error: 'not_found', message: 'This place is not available.' });
    }
    const { rows: dishIds } = await db.query<{ id: string }>(
      // The best dishes here first, by People's Pick (loves against "not for me", by trust).
      // A dish nobody has rated yet sits at the prior: below a loved one, above one people
      // passed on (the same order as everywhere else). Then feed points, then the newest.
      `select d.id from dishes d
       left join dish_stats s on s.dish_id = d.id
       left join dish_rating dr on dr.dish_id = d.id
       where d.restaurant_id = $1 and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
       order by coalesce(dr.confidence, 0) desc,
                coalesce(s.score, 0) desc, d.featured_rank nulls last, d.created_at desc limit 50`,
      [p.id],
    );
    delete r.createdBy;
    // Invented demo places must never borrow a real business's Google photos.
    const isReal = r.source !== 'seed';
    delete r.source;
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
      googlePhotos: !!config.googlePlacesApiKey && isReal,
      isDemo: !isReal,
      dishes: await loadDishCards(db, dishIds.map((d) => d.id), viewer, q.lat, q.lng),
    };
  });

  // The Google Maps id for Take me there, so directions open the real listing. Null for
  // demo places, without a Google key, or when Google doesn't know the place.
  app.get('/restaurants/:id/maps', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    const { rows } = await db.query(
      `select r.id, r.name, r.locality, c.name as city, st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              r.google_place_id as "googlePlaceId"
       from restaurants r join cities c on c.id = r.city_id where r.id = $1 and r.status = 'active' and r.source <> 'seed'`,
      [p.id],
    );
    if (!rows[0] || !config.googlePlacesApiKey) return { googlePlaceId: rows[0]?.googlePlaceId ?? null };
    try {
      return { googlePlaceId: await googlePlaceId(db, rows[0], { apiKey: config.googlePlacesApiKey }) };
    } catch (err) {
      req.log.warn(err);
      return { googlePlaceId: null };
    }
  });

  // Google Maps photos of a place, fetched live (never stored). Empty when not configured.
  app.get('/restaurants/:id/google-photos', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const p = parse(z.object({ id: uuid }), req.params, reply);
    if (!p) return;
    if (!config.googlePlacesApiKey) return { photos: [] };
    const { rows } = await db.query(
      `select r.id, r.name, r.locality, c.name as city, st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              r.google_place_id as "googlePlaceId"
       from restaurants r join cities c on c.id = r.city_id where r.id = $1 and r.status = 'active' and r.source <> 'seed'`,
      [p.id],
    );
    if (!rows[0]) return { photos: [] };
    try {
      return {
        photos: await googlePlacePhotos(db, rows[0], { apiKey: config.googlePlacesApiKey, dailyLimit: config.googlePhotosDailyLimit }),
      };
    } catch (err) {
      req.log.warn(err);
      return { photos: [] };
    }
  });

  // Dish and place search with typo tolerance (trigram similarity) and the alias table. A
  // cuisine (?cuisine=italian, or a search that names one) also brings its dishes and places.
  app.get('/search', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(
      z.object({
        q: z.string().trim().min(2).max(60),
        city: z.coerce.number().int().positive(),
        cuisine: z.string().max(40).optional(),
        category: z.coerce.number().int().positive().optional(),
        ...coords,
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const viewer = userId(req);
    let cuisine = (q.cuisine ? cuisineBySlug(q.cuisine) : undefined) ?? (q.category ? undefined : cuisineForQuery(q.q));
    let facet: { kind: 'cuisine' | 'category'; slug: string; name: string; emoji: string | null } | null =
      cuisine ? { kind: 'cuisine', slug: cuisine.slug, name: cuisine.name, emoji: cuisine.emoji } : null;
    if (!cuisine && q.category) {
      // A dish type tapped from the list: every dish of that type, best first.
      const { rows: c } = await db.query<{ slug: string; name: string; emoji: string | null }>(
        'select slug, name, emoji from dish_categories where id = $1', [q.category]);
      if (c[0]) {
        cuisine = { slug: c[0].slug, name: c[0].name, emoji: c[0].emoji ?? '', categories: [c[0].slug], terms: [] };
        facet = { kind: 'category', ...c[0] };
      }
    }
    if (cuisine) {
      const { rows: ids } = await db.query<{ id: string }>(
        `select d.id from dishes d
         join restaurants r on r.id = d.restaurant_id and r.status = 'active'
         left join dish_stats s on s.dish_id = d.id
         where d.city_id = $1 and d.status = 'live'
           and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live'
                         and ($4::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $4 and b.blocked_id = p.author_id)))
           and ${dishInCuisineSql(2, 3)}
         order by coalesce(s.score, 0) desc limit 40`,
        [q.city, cuisine.categories, cuisine.terms, viewer],
      );
      const { rows: places } = await db.query(
        `select r.id, r.name, r.locality from restaurants r
         where r.city_id = $1 and r.status = 'active'
           and (exists (select 1 from unnest($3::text[]) t where coalesce(r.cuisine, '') ~* ('\\m' || t || '\\M'))
                or exists (select 1 from dishes d where d.restaurant_id = r.id and d.status = 'live' and ${dishInCuisineSql(2, 3)}))
         order by (select count(*) from dishes d where d.restaurant_id = r.id and d.status = 'live') desc, r.name
         limit 30`,
        [q.city, cuisine.categories, cuisine.terms],
      );
      return {
        dishes: await loadDishCards(db, ids.map((d) => d.id), viewer, q.lat, q.lng),
        restaurants: places,
        // What the results are: a cuisine or a dish type (the app shows it as a header).
        ...(facet!.kind === 'cuisine' ? { cuisine: facet } : { category: facet }),
      };
    }
    const { rows: dishIds } = await db.query<{ id: string }>(
      `with term as (select norm_name($1) as t),
       alias_hits as (
         select a.canonical, a.category_id from dish_aliases a, term
         where word_similarity(term.t, a.alias_norm) >= 0.5 or a.alias_norm like term.t || '%')
       select d.id from term, dishes d
       join restaurants r on r.id = d.restaurant_id and r.status = 'active'
       left join dish_stats s on s.dish_id = d.id
       where d.city_id = $2 and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live'
                       and ($3::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $3 and b.blocked_id = p.author_id)))
         and (word_similarity(term.t, d.name_norm) >= 0.5 or d.name_norm like '%' || term.t || '%'
              or d.name_norm in (select norm_name(canonical) from alias_hits)
              or d.category_id in (select category_id from alias_hits where category_id is not null)
              or word_similarity(term.t, r.name_norm) >= 0.5 or r.name_norm like '%' || term.t || '%')
       order by greatest(word_similarity(term.t, d.name_norm), word_similarity(term.t, r.name_norm)) desc,
                coalesce(s.score, 0) desc
       limit 30`,
      [q.q, q.city, viewer],
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

  // "Did you mean…?" while posting: dishes already at this place with a similar name, so
  // "Butter Chkn" joins "Butter Chicken" instead of splitting its votes.
  app.get('/dishes/similar', async (req, reply) => {
    const q = parse(
      z.object({ restaurantId: uuid, name: z.string().trim().min(2).max(60) }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select d.id, d.name, d.category_id as "categoryId",
              (select count(*)::int from posts p where p.dish_id = d.id and p.status = 'live') as photos
       from dishes d
       where d.restaurant_id = $1 and d.status = 'live' and d.name_norm <> norm_name($2)
         and (similarity(d.name_norm, norm_name($2)) >= 0.4 or word_similarity(norm_name($2), d.name_norm) >= 0.6)
       order by similarity(d.name_norm, norm_name($2)) desc limit 3`,
      [q.restaurantId, q.name],
    );
    return rows;
  });

  // Before adding a new place: places already this close with a similar name (or right
  // on the same spot), so one restaurant doesn't end up on the map twice.
  app.get('/restaurants/similar', async (req, reply) => {
    const q = parse(
      z.object({ name: z.string().trim().min(2).max(80), lat: coords.lat.unwrap(), lng: coords.lng.unwrap() }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select id, name, locality, status,
              round(st_distance(geo, st_setsrid(st_makepoint($3, $2), 4326)::geography))::int as "distanceM"
       from restaurants
       where status <> 'hidden'
         and st_dwithin(geo, st_setsrid(st_makepoint($3, $2), 4326)::geography, 150)
         and (similarity(name_norm, norm_name($1)) >= 0.35 or word_similarity(norm_name($1), name_norm) >= 0.6
              or word_similarity(name_norm, norm_name($1)) >= 0.6)
       order by greatest(similarity(name_norm, norm_name($1)), word_similarity(norm_name($1), name_norm),
                         word_similarity(name_norm, norm_name($1))) desc limit 3`,
      [q.name, q.lat, q.lng],
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
      `select id, name, locality, st_y(geo::geometry) as lat, st_x(geo::geometry) as lng,
              round(st_distance(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography))::int as "distanceM",
              (status = 'pending') as pending
       from restaurants
       where status in ('active', 'pending')
         and st_dwithin(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography, $3)
         and ($4::text is null or word_similarity(norm_name($4), name_norm) >= 0.5 or name_norm like '%' || norm_name($4) || '%')
       order by geo <-> st_setsrid(st_makepoint($2, $1), 4326)::geography
       limit 80`,
      [q.lat, q.lng, q.radius, q.q || null],
    );
    return rows;
  });

  // Place search without location (for people who deny location in the add flow). Both
  // place pickers list places waiting for approval too, so a second diner posts there
  // instead of adding the same place again.
  app.get('/restaurants', async (req, reply) => {
    const q = parse(
      z.object({ city: z.coerce.number().int().positive(), q: z.string().trim().max(60).default('') }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select id, name, locality, (status = 'pending') as pending from restaurants
       where city_id = $1 and status in ('active', 'pending')
         and ($2 = '' or word_similarity(norm_name($2), name_norm) >= 0.5 or name_norm like '%' || norm_name($2) || '%')
       order by case when $2 = '' then 0 else word_similarity(norm_name($2), name_norm) end desc, name
       limit 30`,
      [q.city, q.q],
    );
    return rows;
  });
}
