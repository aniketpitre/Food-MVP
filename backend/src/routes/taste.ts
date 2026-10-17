import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, requireAuth, userId } from '../auth.js';
import { type DishCard, loadDishCards } from '../cards.js';
import type { Db } from '../db.js';

const coords = {
  lat: z.coerce.number().min(-90).max(90).optional(),
  lng: z.coerce.number().min(-180).max(180).optional(),
};

/** Why a dish is in For You: who loved it (and how close their taste is), or a dish type. */
export interface Because {
  handle: string | null;
  displayName: string | null;
  avatarUrl: string | null;
  match: number | null; // percent
  followed: boolean;
  people: number;
  category: string | null;
}

/** For You cards for [viewer]: see for_you() in migrations/008_taste.sql. */
export async function forYouCards(
  db: Db,
  viewer: string,
  city: number,
  limit: number,
  offset: number,
  lat?: number | null,
  lng?: number | null,
): Promise<{ items: (DishCard & { because: Because })[]; nextOffset: number | null }> {
  const { rows } = await db.query<{
    dish_id: string;
    by_match: number | null;
    followed: boolean;
    people: number;
    handle: string | null;
    display_name: string | null;
    avatar_path: string | null;
    category: string | null;
  }>(
    `select f.dish_id, f.by_match, f.followed, f.people, pr.handle, pr.display_name, pr.avatar_path, c.name as category
     from for_you($1, $2, $3, $4) with ordinality f
     left join profiles pr on pr.id = f.by_user
     left join dish_categories c on c.id = f.category_id
     order by f.ordinality`,
    [viewer, city, limit, offset],
  );
  const cards = await loadDishCards(db, rows.map((r) => r.dish_id), viewer, lat, lng);
  const items = cards.map((c, i) => {
    const r = rows[i]!;
    return {
      ...c,
      lane: 'foryou',
      because: {
        handle: r.handle,
        displayName: r.display_name,
        avatarUrl: r.avatar_path,
        match: r.by_match === null ? null : Math.round(100 * r.by_match),
        followed: r.followed,
        people: r.people,
        category: r.handle ? null : r.category,
      },
    };
  });
  return { items, nextOffset: rows.length < limit ? null : offset + rows.length };
}

/** Taste Match between [viewer] and [other], or null when there's nothing to go on yet. */
export async function tasteMatch(db: Db, viewer: string, other: string) {
  const { rows } = await db.query<{ match: number; co_votes: number; agreed: number }>(
    'select match, co_votes, agreed from taste_matches($1, $2)',
    [viewer, other],
  );
  const m = rows[0];
  if (!m) return null;
  // The dish types you both like most: what the number is mostly about.
  const { rows: shared } = await db.query<{ name: string; emoji: string | null }>(
    `select c.name, c.emoji from taste_affinity a join taste_affinity b on b.category_id = a.category_id
     join dish_categories c on c.id = a.category_id
     where a.user_id = $1 and b.user_id = $2 and a.score > 0 and b.score > 0
     order by least(a.score, b.score) desc, c.sort limit 3`,
    [viewer, other],
  );
  return { percent: Math.round(100 * m.match), coVotes: m.co_votes, agreed: m.agreed, shared };
}

interface Step {
  instruction: string;
  distanceM: number; // from this turn to the next one
  lng: number;
  lat: number;
}

interface Route {
  coordinates: [number, number][]; // [lng, lat]
  distanceM: number | null;
  durationS: number | null;
  source: 'osrm' | 'straight';
  steps?: Step[];
}

interface OsrmStep {
  name?: string;
  distance: number;
  maneuver: { type: string; modifier?: string; location: [number, number] };
}

/** A turn as a short sentence: "Turn left onto MG Road". */
export function stepText(s: OsrmStep): string {
  const { type, modifier } = s.maneuver;
  const onto = s.name ? ` onto ${s.name}` : '';
  const dir = modifier ? modifier.replace('uturn', 'U-turn') : '';
  switch (type) {
    case 'depart': return `Head out${onto}`;
    case 'arrive': return 'You have arrived';
    case 'roundabout':
    case 'rotary': return `At the roundabout, take the exit${onto}`;
    case 'merge': return `Merge${dir ? ' ' + dir : ''}${onto}`;
    case 'fork': return `Keep ${dir || 'straight'} at the fork${onto}`;
    case 'end of road': return `At the end of the road, turn ${dir || 'ahead'}${onto}`;
    case 'continue':
    case 'new name': return `Continue${dir && dir !== 'straight' ? ' ' + dir : ''}${onto}`;
    default:
      if (!dir || dir === 'straight') return `Go straight${onto}`;
      if (dir === 'U-turn') return `Make a U-turn${onto}`;
      return `Turn ${dir}${onto}`;
  }
}

/**
 * Driving route between two points, from an OSRM server, or a straight line without one.
 * [detailed] asks for the full line and the turns (for in-app directions).
 */
async function route(base: string, from: [number, number], to: [number, number], detailed = false): Promise<Route> {
  const straight: Route = {
    coordinates: [[from[1], from[0]], [to[1], to[0]]], distanceM: null, durationS: null, source: 'straight',
    ...(detailed ? { steps: [] } : {}),
  };
  if (!base) return straight;
  const key = [from, to].flat().map((n) => n.toFixed(4)).join(',') + (detailed ? ':d' : '');
  const cached = routeCache.get(key);
  if (cached) return cached;
  try {
    const opts = detailed ? 'overview=full&geometries=geojson&steps=true' : 'overview=simplified&geometries=geojson';
    const url = `${base.replace(/\/+$/, '')}/route/v1/driving/${from[1]},${from[0]};${to[1]},${to[0]}?${opts}`;
    const res = await fetch(url, { signal: AbortSignal.timeout(8000), headers: { 'user-agent': 'WorthAbite/1.0' } });
    if (!res.ok) return straight;
    const body = (await res.json()) as {
      routes?: { geometry: { coordinates: [number, number][] }; distance: number; duration: number; legs?: { steps?: OsrmStep[] }[] }[];
    };
    const r = body.routes?.[0];
    if (!r || r.geometry.coordinates.length < 2) return straight;
    const found: Route = { coordinates: r.geometry.coordinates, distanceM: Math.round(r.distance), durationS: Math.round(r.duration), source: 'osrm' };
    if (detailed) {
      found.steps = (r.legs ?? []).flatMap((l) => l.steps ?? []).map((s) => ({
        instruction: stepText(s),
        distanceM: Math.round(s.distance),
        lng: s.maneuver.location[0],
        lat: s.maneuver.location[1],
      }));
    }
    routeCache.set(key, found);
    if (routeCache.size > 200) routeCache.delete(routeCache.keys().next().value!);
    return found;
  } catch {
    return straight;
  }
}
const routeCache = new Map<string, Route>();

/**
 * People-based recommendation:
 *  - /me/taste: the taste profile (what you said, and what your votes taught us);
 *  - /people/taste: people whose taste matches yours, to follow;
 *  - /foryou: dishes from the people whose taste matches yours and the people you follow;
 *  - /route/stops: the best dishes along a drive;
 *  - /route: directions to a place, for the in-app Take me there.
 */
export async function tasteRoutes(app: FastifyInstance) {
  const { db, config } = app;

  app.get('/me/taste', { preHandler: requireAuth }, async (req) => {
    const me = userId(req)!;
    const { rows } = await db.query<{ categories: number[]; diet: string; spice: number }>(
      'select categories, diet, spice from taste_profiles where user_id = $1',
      [me],
    );
    const { rows: learned } = await db.query(
      `select c.id as "categoryId", c.name, c.emoji, a.score from taste_affinity a join dish_categories c on c.id = a.category_id
       where a.user_id = $1 and a.score > 0 order by a.score desc, c.sort limit 6`,
      [me],
    );
    const t = rows[0];
    return { set: !!t, categories: t?.categories ?? [], diet: t?.diet ?? 'nonveg', spice: t?.spice ?? 3, learned };
  });

  app.put('/me/taste', { preHandler: requireAuth }, async (req, reply) => {
    const b = parse(
      z.object({
        categories: z.array(z.number().int().positive()).max(20),
        diet: z.enum(['veg', 'egg', 'nonveg']),
        spice: z.number().int().min(1).max(5),
      }),
      req.body,
      reply,
    );
    if (!b) return;
    const me = userId(req)!;
    // Only real dish types are kept.
    await db.query(
      `insert into taste_profiles (user_id, categories, diet, spice)
       values ($1, array(select id from dish_categories where id = any($2::int[]) order by id), $3, $4)
       on conflict (user_id) do update set categories = excluded.categories, diet = excluded.diet,
                                           spice = excluded.spice, updated_at = now()`,
      [me, b.categories, b.diet, b.spice],
    );
    return { ok: true };
  });

  app.get('/people/taste', { preHandler: requireAuth }, async (req, reply) => {
    const q = parse(z.object({ limit: z.coerce.number().int().min(1).max(30).default(12) }), req.query, reply);
    if (!q) return;
    const me = userId(req)!;
    const { rows } = await db.query(
      `select p.handle, p.display_name as "displayName", p.avatar_path as "avatarUrl",
              round(100 * m.match)::int as match, m.co_votes as "coVotes",
              exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = p.id) as "isFollowing",
              (select c.name from taste_affinity a join dish_categories c on c.id = a.category_id
               where a.user_id = p.id and a.score > 0 order by a.score desc, c.sort limit 1) as "favourite"
       from taste_matches($1) m
       join profiles p on p.id = m.other_id
       left join voter_independence vi on vi.user_id = p.id
       where m.match >= 0.5
         and standing(p.created_at, vi.twins) >= 0.5
         and not exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = p.id)
         and not exists (select 1 from blocks b where (b.blocker_id = $1 and b.blocked_id = p.id) or (b.blocker_id = p.id and b.blocked_id = $1))
       -- Surest first: a close match on lots of evidence beats a perfect one on a little.
       order by m.match * least(1.0, m.evidence / 3.0) desc, m.co_votes desc, p.handle
       limit $2`,
      [me, q.limit],
    );
    return rows;
  });

  app.get('/foryou', { preHandler: requireAuth }, async (req, reply) => {
    const q = parse(
      z.object({
        city: z.coerce.number().int().positive(),
        limit: z.coerce.number().int().min(1).max(50).default(20),
        offset: z.coerce.number().int().min(0).max(5000).default(0),
        ...coords,
      }),
      req.query,
      reply,
    );
    if (!q) return;
    return forYouCards(db, userId(req)!, q.city, q.limit, q.offset, q.lat, q.lng);
  });

  // Directions for the in-app Take me there: the road line, its length and time, and the turns.
  app.get('/route', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const q = parse(
      z.object({
        fromLat: z.coerce.number().min(-90).max(90),
        fromLng: z.coerce.number().min(-180).max(180),
        toLat: z.coerce.number().min(-90).max(90),
        toLng: z.coerce.number().min(-180).max(180),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    return route(config.routingUrl, [q.fromLat, q.fromLng], [q.toLat, q.toLng], true);
  });

  // The best dish at each place along a drive, in the order you reach them.
  app.get('/route/stops', { preHandler: optionalAuth, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const q = parse(
      z.object({
        fromLat: z.coerce.number().min(-90).max(90),
        fromLng: z.coerce.number().min(-180).max(180),
        toLat: z.coerce.number().min(-90).max(90),
        toLng: z.coerce.number().min(-180).max(180),
        corridorKm: z.coerce.number().min(0.5).max(10).default(3),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const viewer = userId(req);
    const r = await route(config.routingUrl, [q.fromLat, q.fromLng], [q.toLat, q.toLng]);
    const line = JSON.stringify({ type: 'LineString', coordinates: r.coordinates });
    const { rows } = await db.query<{ dish_id: string; along: number; off_route_m: number }>(
      `with line as (select st_setsrid(st_geomfromgeojson($1), 4326) as g),
       places as (
         select r.id, st_linelocatepoint(line.g, r.geo::geometry) as along,
                st_distance(r.geo, line.g::geography) as off_route_m
         from restaurants r, line
         where r.status = 'active' and st_dwithin(r.geo, line.g::geography, $2)
       )
       select best.dish_id, p.along, p.off_route_m
       from places p
       cross join lateral (
         select d.id as dish_id from dishes d left join dish_rating dr on dr.dish_id = d.id
         where d.restaurant_id = p.id and d.status = 'live'
           and exists (select 1 from posts po where po.dish_id = d.id and po.status = 'live'
                       and ($3::uuid is null or not exists (select 1 from blocks b where b.blocker_id = $3 and b.blocked_id = po.author_id)))
         order by coalesce(dr.confidence, 0) desc, coalesce(dr.loves, 0) desc, d.id limit 1
       ) best
       order by p.along, p.id
       limit 60`,
      [line, q.corridorKm * 1000, viewer],
    );
    const cards = await loadDishCards(db, rows.map((x) => x.dish_id), viewer);
    return {
      route: r,
      stops: cards.map((c, i) => ({ card: c, along: Number(rows[i]!.along), offRouteM: Math.round(Number(rows[i]!.off_route_m)) })),
    };
  });
}
