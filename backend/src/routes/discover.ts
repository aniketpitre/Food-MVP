import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, userId } from '../auth.js';
import { loadDishCards, wholePicks } from '../cards.js';
import { forYouCards } from './taste.js';

const coords = {
  lat: z.coerce.number().min(-90).max(90).optional(),
  lng: z.coerce.number().min(-180).max(180).optional(),
};

/**
 * People whose picks for these dishes the community backed, most trusted first. Each
 * person is judged on one record: their best dish type among these dishes (their trust,
 * picks and backers all come from that same record, never mixed across types).
 */
async function tastersFor(app: FastifyInstance, dishIds: string[], viewer: string | null, limit: number) {
  if (dishIds.length === 0) return [];
  const { rows } = await app.db.query(
    `with loved as (
       select e.user_id, d.id as dish_id, d.name, coalesce(d.category_id, -1) as cat, coalesce(dr.confidence, 0) as conf
       from engagements e
       join dishes d on d.id = e.dish_id
       left join dish_rating dr on dr.dish_id = d.id
       where e.dish_id = any($1) and e.kind = 'recommend'
     ),
     best as (
       select distinct on (l.user_id) l.user_id, ts.trust, ts.picks, ts.backers, ts.expert
       from loved l join taster_stats ts on ts.user_id = l.user_id and ts.category_id = l.cat
       where ts.picks >= 0.5
       order by l.user_id, ts.trust desc, ts.backers desc, ts.category_id
     )
     select pr.handle, pr.display_name as "displayName", pr.avatar_path as "avatarUrl",
            b.trust, b.picks, b.backers, b.expert,
            (select l.name from loved l where l.user_id = b.user_id order by l.conf desc, l.dish_id limit 1) as "topPick"
     from best b join profiles pr on pr.id = b.user_id
     where ($2::uuid is null or not exists (select 1 from blocks bl where bl.blocker_id = $2 and bl.blocked_id = pr.id))
     order by b.trust desc, b.backers desc, pr.handle
     limit $3`,
    [dishIds, viewer, limit],
  );
  return rows.map((r) => ({
    handle: r.handle,
    displayName: r.displayName,
    avatarUrl: r.avatarUrl,
    picks: wholePicks(r.picks),
    backers: r.backers,
    topPick: r.topPick,
    isExpert: r.expert,
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
    // What the person means: known dishes (any spelling), a dish type, or free text. An
    // exact spelling wins; otherwise every equally close dish counts ("chicken" means
    // chicken biryani, chilli chicken and chicken burger alike, not whichever came first).
    const { rows: meaning } = await db.query<{ canonical: string }>(
      `with term as (select norm_name($1) as t),
       scored as (
         select a.canonical, (a.alias_norm = term.t) as exact, word_similarity(term.t, a.alias_norm) as sim
         from term, dish_aliases a
         where a.alias_norm = term.t or word_similarity(term.t, a.alias_norm) >= 0.6
       )
       select distinct canonical from scored, (select bool_or(exact) as any_exact, max(sim) as best from scored) b
       where case when b.any_exact then exact else sim >= b.best - 0.05 end
       order by canonical`,
      [q.q],
    );
    const { rows: cat } = await db.query<{ id: number; name: string }>(
      `select id, name from dish_categories, (select norm_name($1) as t) term
       where norm_name(name) = term.t or norm_name(slug) = term.t or word_similarity(term.t, norm_name(name)) >= 0.7
       order by (norm_name(name) = term.t or norm_name(slug) = term.t) desc, word_similarity(term.t, norm_name(name)) desc, id
       limit 1`,
      [q.q],
    );
    const canonicals = meaning.map((m) => m.canonical);
    const categoryId = cat[0]?.id ?? null;
    const label =
      (canonicals.length === 1 ? canonicals[0] : null) ?? cat[0]?.name ?? q.q.replace(/\b\w/g, (c) => c.toUpperCase());

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
           -- Known dishes: each under any spelling (and close typos), not its neighbours
           -- ("pav bhaji" must not bring up "kanda bhaji").
           exists (select 1 from unnest($3::text[]) c(name)
                   where d.name_norm = norm_name(c.name) or d.name_norm like '%' || norm_name(c.name) || '%'
                      or similarity(norm_name(c.name), d.name_norm) >= 0.75)
           -- A dish type: everything of that type.
           or ($4::int is not null and d.category_id = $4)
           -- The word itself in a dish's name ("pav" finds Pav Bhaji and Misal Pav too).
           or d.name_norm ~ ('(^| )' || term.t || '( |$)')
           -- Nothing known: fuzzy match on the dish name.
           or (cardinality($3::text[]) = 0 and $4::int is null
               and (word_similarity(term.t, d.name_norm) >= 0.6 or d.name_norm like '%' || term.t || '%')))
       order by coalesce(dr.confidence, 0) desc, coalesce(dr.loves, 0) desc, coalesce(s.score, 0) desc,
                word_similarity(term.t, d.name_norm) desc, d.id
       limit 30`,
      [q.q, q.city, canonicals, categoryId, viewer],
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

    // The sections are independent, so they're fetched side by side, then all their cards
    // are built in one go (a dish in several sections is loaded once).
    const ids = (sql: string, params: unknown[] = [q.city, viewer]) => db.query<{ id: string }>(sql, params).then((r) => r.rows);
    const [picks, trending, fresh, following, nearby, cravingsRes, cityDishes] = await Promise.all([
      ids(`select d.id from dishes d join dish_rating dr on dr.dish_id = d.id
           where ${visible} and dr.loves > 0
           order by dr.confidence desc, dr.loves desc, d.id limit 10`),
      ids(`select d.id from dishes d join dish_stats s on s.dish_id = d.id
           where ${visible} and s.score_7d > 0 and s.w_endorsers >= cfg('min_endorsers_trending')
           order by s.score_7d desc, s.score desc, d.id limit 10`),
      ids(`select d.id from dishes d where ${visible} order by d.created_at desc, d.id limit 10`),
      viewer
        ? db.query<{ id: string; handle: string }>(
            `select distinct on (d.id) d.id, pr.handle, e.created_at from engagements e
             join follows f on f.followee_id = e.user_id and f.follower_id = $2
             join dishes d on d.id = e.dish_id join profiles pr on pr.id = e.user_id
             where ${visible} and e.kind = 'recommend' and e.created_at > now() - interval '60 days'
             order by d.id, e.created_at desc`,
            [q.city, viewer],
          ).then((r) => r.rows)
        : Promise.resolve([] as { id: string; handle: string }[]),
      q.lat !== undefined && q.lng !== undefined
        ? ids(
            `select d.id from dishes d join restaurants r on r.id = d.restaurant_id
             left join dish_rating dr on dr.dish_id = d.id
             where ${visible} and st_dwithin(r.geo, st_setsrid(st_makepoint($4, $3), 4326)::geography, 5000)
             order by coalesce(dr.confidence, 0) desc, d.id limit 10`,
            [q.city, viewer, q.lat, q.lng],
          )
        : Promise.resolve([] as { id: string }[]),
      // Cravings to tap: dish types people here actually post, most loved first.
      db.query(
        `select c.name as label, c.emoji, count(distinct d.id)::int as dishes
         from dish_categories c join dishes d on d.category_id = c.id
         left join dish_rating dr on dr.dish_id = d.id
         where ${visible}
         group by c.id, c.name, c.emoji order by coalesce(sum(dr.loves), 0) desc, c.sort, c.id
         limit 12`,
        [q.city, viewer],
      ),
      // People to trust come from the city's best 50 dishes, not every dish in it.
      ids(`select d.id from dishes d left join dish_rating dr on dr.dish_id = d.id
           where ${visible} order by coalesce(dr.confidence, 0) desc, d.id limit 50`),
    ]);
    const followedRows = following.slice(0, 10);
    const sections = [picks, nearby, followedRows, trending, fresh];
    const unique = [...new Set(sections.flatMap((rows) => rows.map((r) => r.id)))];
    const [all, tasters, forYou] = await Promise.all([
      loadDishCards(db, unique, viewer, q.lat, q.lng),
      tastersFor(app, cityDishes.map((d) => d.id), viewer, 10),
      viewer ? forYouCards(db, viewer, q.city, 10, 0, q.lat, q.lng).then((r) => r.items) : Promise.resolve([]),
    ]);
    const byId = new Map(all.map((c) => [c.id, c]));
    const cards = (rows: { id: string }[]) => rows.map((r) => byId.get(r.id)!).filter(Boolean);
    const followedCards = cards(followedRows);
    return {
      cravings: cravingsRes.rows,
      forYou,
      peoplesPicks: cards(picks),
      nearby: cards(nearby),
      following: followedCards.map((c) => ({ card: c, lovedBy: followedRows.find((r) => r.id === c.id)!.handle })),
      trending: cards(trending),
      fresh: cards(fresh),
      tasters,
    };
  });

  // Places for the map: the ones with dishes on WorthAbite, each with its best dish, a food
  // logo (the best dish's category, else a guess from the cuisine) and what the popup shows.
  // With all=true, also real places nobody has posted from yet (imported from OpenStreetMap).
  app.get('/map', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(
      z.object({
        city: z.coerce.number().int().positive(),
        all: z.enum(['true', 'false']).default('false'),
        filter: z.enum(['all', 'friends', 'taste']).default('all'),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const viewer = userId(req);
    if (q.filter !== 'all') {
      if (!viewer) return reply.code(401).send({ error: 'sign_in', message: 'Sign in to see your friends\' and taste matches\' picks.' });
      return { places: await pickedPlaces(q.city, viewer, q.filter) };
    }
    const { rows } = await db.query(
      `select r.id, r.name, r.locality, r.cuisine, r.address, r.opening_hours as "openingHours",
              (r.source = 'seed') as demo,
              st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              count(d.id)::int as "dishCount",
              (select count(distinct e.user_id)::int from engagements e join dishes de on de.id = e.dish_id
               where de.restaurant_id = r.id and de.status = 'live' and e.kind = 'recommend'
                 and e.user_id is distinct from r.created_by) as lovers,
              (select coalesce(sum(dr.w_loves), 0)::float8 from dishes dl join dish_rating dr on dr.dish_id = dl.id
               where dl.restaurant_id = r.id and dl.status = 'live') as "lovedWeight",
              (select rp.thumb_path from restaurant_photos rp where rp.restaurant_id = r.id
               order by (rp.source = 'owner') desc, rp.created_at limit 1) as "photoUrl",
              (select json_build_object('dishId', d2.id, 'name', d2.name, 'loves', coalesce(dr.loves, 0),
                        'percent', case when coalesce(dr.loves, 0) + coalesce(dr.passes, 0) > 0
                                        then round(100.0 * dr.loves / (dr.loves + dr.passes)) end,
                        'priceInr', d2.price_inr, 'isVeg', d2.is_veg, 'emoji', c.emoji,
                        'thumbUrl', (select thumb_path from posts where id = dish_cover(d2.id, null)))
               from dishes d2 left join dish_rating dr on dr.dish_id = d2.id
               left join dish_categories c on c.id = d2.category_id
               where d2.restaurant_id = r.id and d2.status = 'live'
                 and exists (select 1 from posts p where p.dish_id = d2.id and p.status = 'live')
               order by coalesce(dr.confidence, 0) desc, coalesce(dr.loves, 0) desc, d2.id limit 1) as "topDish"
       from restaurants r
       left join dishes d on d.restaurant_id = r.id and d.status = 'live'
         and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
       where r.city_id = $1 and r.status = 'active'
       group by r.id
       having $2 or count(d.id) > 0
       -- Most loved first (by the People's Pick weight of the loves on dishes still up).
       order by "lovedWeight" desc, count(d.id) desc, r.id
       limit 3000`,
      [q.city, q.all === 'true'],
    );
    return { places: rows.map((r) => ({ ...r, emoji: r.topDish?.emoji ?? foodLogo(r.cuisine, r.name) })) };
  });

  /**
   * The map filtered to people: places where someone you follow (friends) or someone whose
   * taste matches yours (taste) loved a dish. Each place shows that dish and who loved it.
   */
  async function pickedPlaces(city: number, viewer: string, filter: 'friends' | 'taste') {
    const people =
      filter === 'friends'
        ? `select followee_id as id, null::float8 as match from follows where follower_id = $2`
        : `select other_id as id, match from taste_matches($2) where match >= 0.55 order by match desc limit 50`;
    const { rows } = await db.query(
      `with people as (${people}),
       loved as (
         select distinct on (r.id) r.id as place_id, d.id as dish_id, e.user_id, pe.match
         from engagements e
         join people pe on pe.id = e.user_id
         join dishes d on d.id = e.dish_id and d.status = 'live' and d.city_id = $1
         join restaurants r on r.id = d.restaurant_id and r.status = 'active'
         left join dish_rating dr on dr.dish_id = d.id
         where e.kind = 'recommend'
           and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
           and not exists (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = e.user_id)
         order by r.id, pe.match desc nulls last, coalesce(dr.confidence, 0) desc, e.created_at desc, d.id
       )
       select r.id, r.name, r.locality, r.cuisine, r.address, r.opening_hours as "openingHours",
              (r.source = 'seed') as demo, st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              (select count(*)::int from dishes dd where dd.restaurant_id = r.id and dd.status = 'live') as "dishCount",
              (select count(distinct e2.user_id)::int from engagements e2 join dishes de on de.id = e2.dish_id
               where de.restaurant_id = r.id and de.status = 'live' and e2.kind = 'recommend'
                 and e2.user_id is distinct from r.created_by) as lovers,
              (select rp.thumb_path from restaurant_photos rp where rp.restaurant_id = r.id
               order by (rp.source = 'owner') desc, rp.created_at limit 1) as "photoUrl",
              json_build_object('dishId', d.id, 'name', d.name, 'loves', coalesce(dr.loves, 0),
                'percent', case when coalesce(dr.loves, 0) + coalesce(dr.passes, 0) > 0
                                then round(100.0 * dr.loves / (dr.loves + dr.passes)) end,
                'priceInr', d.price_inr, 'isVeg', d.is_veg, 'emoji', c.emoji,
                'thumbUrl', (select thumb_path from posts where id = dish_cover(d.id, null))) as "topDish",
              json_build_object('handle', pr.handle, 'displayName', pr.display_name, 'avatarUrl', pr.avatar_path,
                'match', case when l.match is not null then round(100 * l.match)::int end) as "lovedBy"
       from loved l
       join restaurants r on r.id = l.place_id
       join dishes d on d.id = l.dish_id
       join profiles pr on pr.id = l.user_id
       left join dish_rating dr on dr.dish_id = d.id
       left join dish_categories c on c.id = d.category_id
       order by r.id`,
      [city, viewer],
    );
    return rows.map((r) => ({ ...r, emoji: r.topDish?.emoji ?? foodLogo(r.cuisine, r.name) }));
  }
}

const LOGOS: [RegExp, string][] = [
  [/pizza/, '🍕'],
  [/burger/, '🍔'],
  [/ice.?cream|gelato|kulfi/, '🍦'],
  [/cake|bakery|pastry|patisserie|dessert|sweet|mithai/, '🧁'],
  [/donut|doughnut/, '🍩'],
  [/coffee|cafe|café/, '☕'],
  [/tea|chai/, '🍵'],
  [/juice|shake|smoothie|bubble/, '🥤'],
  [/chinese|noodle|asian|thai|momo|ramen/, '🍜'],
  [/sushi|japanese/, '🍣'],
  [/sandwich|sub\b|bread/, '🥪'],
  [/roll|wrap|shawarma|kebab|kabab|frankie/, '🌯'],
  [/chicken|grill|bbq|barbecue|tandoor/, '🍗'],
  [/biryani|indian|thali|curry|punjabi|maharashtrian|south|dosa|idli|veg/, '🍛'],
];

/** A food logo for a place nobody has posted from yet, guessed from its cuisine or name. */
export function foodLogo(cuisine: string | null, name: string): string {
  const text = `${cuisine ?? ''} ${name}`.toLowerCase();
  return LOGOS.find(([re]) => re.test(text))?.[1] ?? '🍽️';
}
