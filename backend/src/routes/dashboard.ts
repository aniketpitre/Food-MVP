import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { fetchOsmPlaces, importOsmPlaces } from '../osm.js';
import { importOverturePlaces, overtureFileFor, readOvertureFile } from '../overture.js';
import { removeDemoActivity, removeDemoCatalog } from '../seed.js';
import { requireAdmin } from './admin.js';
import { DASHBOARD_HTML } from '../dashboard/page.js';

/**
 * Where OpenStreetMap places come from. Tests swap in a fake so they never reach the real
 * Overpass servers (slow, rate-limited, and not there in CI).
 */
export const osmSource = { fetch: fetchOsmPlaces };

/** A slow admin task (an import, a clean-up) and what it said while it ran. */
export interface AdminJob {
  id: number;
  kind: string;
  label: string;
  status: 'running' | 'done' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  log: string[];
  result: unknown;
}

const MAX_JOBS = 20;

/**
 * The admin dashboard: one page at GET /admin and the read-only JSON it shows. The page
 * itself holds no data (it signs in and calls the endpoints below), so it needs no sign-in
 * to load; every endpoint is admins only. Every list is limited so the page stays fast
 * with real data.
 */
export async function dashboardRoutes(app: FastifyInstance) {
  const { db } = app;
  // Kept in memory: a restart forgets them, which is fine for "how did the last import go".
  const jobs: AdminJob[] = [];
  let nextJob = 1;

  function startJob(kind: string, label: string): AdminJob {
    const job: AdminJob = { id: nextJob++, kind, label, status: 'running', startedAt: new Date().toISOString(), finishedAt: null, log: [], result: null };
    jobs.unshift(job);
    jobs.length = Math.min(jobs.length, MAX_JOBS);
    return job;
  }
  function finishJob(job: AdminJob, status: 'done' | 'failed', result: unknown) {
    job.status = status;
    job.result = result;
    job.finishedAt = new Date().toISOString();
  }
  const say = (job: AdminJob) => (m: string) => {
    if (job.log.length < 200) job.log.push(`${new Date().toISOString().slice(11, 19)} ${m}`);
  };

  app.get('/admin', async (_req, reply) => {
    reply.header('cache-control', 'no-store');
    // The page talks only to this server; nothing else may load into it.
    reply.header('content-security-policy', "default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'");
    reply.header('x-frame-options', 'DENY');
    return reply.type('text/html; charset=utf-8').send(DASHBOARD_HTML);
  });

  // ---------- Overview ----------

  app.get('/admin/overview', { preHandler: requireAdmin }, async () => {
    const { rows: [totals] } = await db.query(
      `select (select count(*)::int from profiles) as users,
              (select count(*)::int from profiles where is_demo) as "demoUsers",
              (select count(*)::int from profiles where created_at > now() - interval '7 days') as "newUsers7d",
              (select count(distinct user_id)::int from engagements
                where kind in ('recommend', 'pass') and created_at > now() - interval '7 days') as "activeVoters7d",
              (select count(*)::int from dishes where status = 'live') as "liveDishes",
              (select count(*)::int from dishes) as dishes,
              (select count(*)::int from posts where status = 'live') as "livePosts",
              (select count(*)::int from posts where status = 'pending') as "pendingPosts",
              (select count(*)::int from posts where status = 'hidden') as "hiddenPosts",
              (select count(*)::int from posts where status = 'live' and visit = 'verified') as "verifiedPhotos",
              (select count(*)::int from comments where status = 'live') as comments,
              (select count(*)::int from follows) as follows,
              (select count(*)::int from messages) as messages,
              (select count(*)::int from stories) as stories,
              (select count(*)::int from stories where expires_at > now()) as "liveStories",
              (select count(*)::int from engagements where kind = 'recommend') as loves,
              (select count(*)::int from engagements where kind = 'pass') as passes,
              (select count(*)::int from engagements where kind = 'save') as saves,
              (select count(*)::int from reports where status = 'open') as "openReports",
              (select count(*)::int from restaurants where status = 'pending') as "pendingPlaces",
              (select count(*)::int from sessions where revoked_at is null and last_seen_at > now() - interval '7 days') as "activeSessions7d"`,
    );
    const { rows: places } = await db.query(
      'select source, status, count(*)::int as n from restaurants group by source, status order by source, status',
    );
    const { rows: series } = await db.query(
      `with days as (select d::date as day from generate_series(current_date - 29, current_date, interval '1 day') d)
       select to_char(days.day, 'YYYY-MM-DD') as day,
              coalesce(s.n, 0) as signups, coalesce(p.n, 0) as posts,
              coalesce(v.loves, 0) as loves, coalesce(v.passes, 0) as passes
       from days
       left join (select created_at::date as day, count(*)::int as n from profiles
                  where created_at >= current_date - 29 group by 1) s on s.day = days.day
       left join (select created_at::date as day, count(*)::int as n from posts
                  where created_at >= current_date - 29 group by 1) p on p.day = days.day
       left join (select created_at::date as day,
                         count(*) filter (where kind = 'recommend')::int as loves,
                         count(*) filter (where kind = 'pass')::int as passes
                  from engagements where kind in ('recommend', 'pass') and created_at >= current_date - 29 group by 1) v
         on v.day = days.day
       order by days.day`,
    );
    const { rows: cities } = await db.query(
      `select c.id, c.slug, c.name, count(d.id)::int as dishes
       from cities c join dishes d on d.city_id = c.id and d.status = 'live'
       group by c.id order by count(d.id) desc limit 10`,
    );
    return { totals, places, series, cities };
  });

  // ---------- Recommendations explorer ----------

  /** A user by id or handle. */
  async function findUser(idOrHandle: string): Promise<string | null> {
    const { rows } = await db.query<{ id: string }>(
      'select id from profiles where id::text = $1 or handle = lower(ltrim($1, \'@\')) limit 1',
      [idOrHandle.trim()],
    );
    return rows[0]?.id ?? null;
  }

  // The numbers behind every card: People's Pick (loves vs "not for me", weighted) and
  // feed points (saves, shares, the last week's buzz), plus the place.
  const DISH_NUMBERS = `
    d.id, d.name, d.status, d.is_veg as "isVeg", cat.name as category,
    r.id as "placeId", r.name as place, r.locality, r.status as "placeStatus", r.source as "placeSource",
    coalesce(dr.loves, 0) as loves, coalesce(dr.passes, 0) as passes,
    round(coalesce(dr.w_loves, 0)::numeric, 3)::float8 as "wLoves", round(coalesce(dr.w_passes, 0)::numeric, 3)::float8 as "wPasses",
    round(coalesce(dr.rating, 0)::numeric, 4)::float8 as rating, round(coalesce(dr.confidence, 0)::numeric, 4)::float8 as confidence,
    ch.handle as champion,
    round(coalesce(ds.score, 0)::numeric, 3)::float8 as score, round(coalesce(ds.score_7d, 0)::numeric, 3)::float8 as "score7d",
    coalesce(ds.endorsers, 0) as endorsers, round(coalesce(ds.w_endorsers, 0)::numeric, 3)::float8 as "wEndorsers",
    (select count(*)::int from posts p where p.dish_id = d.id and p.status = 'live') as photos`;
  const DISH_JOINS = `
    join dishes d on d.id = f.dish_id
    join restaurants r on r.id = d.restaurant_id
    left join dish_categories cat on cat.id = d.category_id
    left join dish_rating dr on dr.dish_id = d.id
    left join dish_stats ds on ds.dish_id = d.id
    left join profiles ch on ch.id = dr.champion_id`;

  app.get('/admin/recs', { preHandler: requireAdmin }, async (req, reply) => {
    const q = parse(
      z.object({
        city: z.coerce.number().int().positive(),
        tab: z.enum(['top', 'trending', 'gems', 'nearby', 'foryou']).default('top'),
        lat: z.coerce.number().min(-90).max(90).optional(),
        lng: z.coerce.number().min(-180).max(180).optional(),
        user: z.string().max(60).optional(),
        limit: z.coerce.number().int().min(1).max(200).default(50),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows: cfg } = await db.query<{ key: string; value: number }>('select key, value::float8 as value from ranking_config');
    const settings = Object.fromEntries(cfg.map((c) => [c.key, c.value]));
    if (q.tab === 'foryou') {
      if (!q.user) return reply.code(400).send({ error: 'invalid_input', message: 'For You needs a user (handle or id).' });
      const uid = await findUser(q.user);
      if (!uid) return reply.code(404).send({ error: 'not_found', message: 'No such user.' });
      const { rows } = await db.query(
        `select f.ord::int as rank, 'foryou' as lane, ${DISH_NUMBERS},
                round(f.score::numeric, 4)::float8 as "forYouScore", bu.handle as "byUser", f.by_user as "byUserId",
                round(f.by_match::numeric, 3)::float8 as "byMatch", f.followed, f.people,
                fc.name as "forYouCategory"
         from for_you($1, $2, $3, 0) with ordinality f(dish_id, score, by_user, by_match, followed, people, category_id, ord)
         ${DISH_JOINS}
         left join profiles bu on bu.id = f.by_user
         left join dish_categories fc on fc.id = f.category_id
         order by f.ord`,
        [uid, q.city, q.limit],
      );
      return { tab: q.tab, userId: uid, settings, items: rows };
    }
    if (q.tab === 'nearby' && (q.lat === undefined || q.lng === undefined)) {
      return reply.code(400).send({ error: 'invalid_input', message: 'Nearby needs a location (lat and lng).' });
    }
    const { rows } = await db.query(
      `select f.ord::int as rank, f.lane, ${DISH_NUMBERS},
              case when $3::float8 is not null then round(st_distance(r.geo, st_setsrid(st_makepoint($4, $3), 4326)::geography))::int end as "distanceM"
       from get_feed($1, null, $2, $3, $4, $5, 0, '', null) with ordinality f(dish_id, lane, ord)
       ${DISH_JOINS}
       order by f.ord`,
      [q.city, q.tab, q.lat ?? null, q.lng ?? null, q.limit],
    );
    return { tab: q.tab, settings, items: rows };
  });

  // Everything behind one dish's place in the ranking: each vote and what it was worth.
  app.get('/admin/dishes/:id/explain', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    if (!p) return;
    const { rows: [dish] } = await db.query(
      `select ${DISH_NUMBERS}, d.city_id as "cityId", ci.name as city, d.created_at as "createdAt",
              ow.handle as "placeAddedBy", ow.id as "placeAddedById",
              (select into_id from dish_merges where from_id = d.id) as "mergedInto",
              (select count(*)::int from dish_merges where into_id = d.id) as "mergedFrom"
       from (select $1::uuid as dish_id) f ${DISH_JOINS}
       join cities ci on ci.id = d.city_id
       left join profiles ow on ow.id = r.created_by`,
      [p.id],
    );
    if (!dish) return reply.code(404).send({ error: 'not_found', message: 'Dish not found.' });

    // Mirrors refresh_people_pick: trust (by dish type, else overall, else the floor) x
    // standing x verified-visit bonus x fading; whoever added the place counts for nothing.
    const { rows: votes } = await db.query(
      `select e.user_id as "userId", pr.handle, pr.is_demo as "isDemo", e.kind, e.created_at as "createdAt",
              round(standing(pr.created_at, vi.twins)::numeric, 4)::float8 as standing,
              coalesce(vi.twins, 0) as twins,
              round(ts.trust::numeric, 4)::float8 as "trustCategory", round(tall.trust::numeric, 4)::float8 as "trustOverall",
              round(coalesce(ts.trust, tall.trust, cfg('trust_floor'))::numeric, 4)::float8 as trust,
              coalesce(ts.expert, false) as expert,
              (e.user_id = r.created_by) as "isOwner",
              v.verified as "verifiedVisit",
              round(decay(e.created_at, greatest(cfg('pp_half_life_days'), 0.01))::numeric, 4)::float8 as fade,
              case when e.kind not in ('recommend', 'pass') or e.user_id = r.created_by or d.status <> 'live' then 0
                   else round((coalesce(ts.trust, tall.trust, cfg('trust_floor')) * standing(pr.created_at, vi.twins)
                         * (case when v.verified then least(greatest(cfg('visit_weight'), 1), 2) else 1 end)
                         * decay(e.created_at, greatest(cfg('pp_half_life_days'), 0.01)))::numeric, 4)::float8
              end as weight
       from engagements e
       join dishes d on d.id = e.dish_id
       join restaurants r on r.id = d.restaurant_id
       join profiles pr on pr.id = e.user_id
       left join voter_independence vi on vi.user_id = e.user_id
       left join taster_stats ts on ts.user_id = e.user_id and ts.category_id = coalesce(d.category_id, -1)
       left join taster_stats tall on tall.user_id = e.user_id and tall.category_id = 0
       cross join lateral (select exists (select 1 from posts vp where vp.dish_id = e.dish_id and vp.author_id = e.user_id
                                          and vp.status = 'live' and vp.visit = 'verified') as verified) v
       where e.dish_id = $1
       order by (e.kind in ('recommend', 'pass')) desc, e.created_at desc
       limit 500`,
      [p.id],
    );
    // Mirrors refresh_dish_stats: each person's feed points, capped, then times standing.
    const { rows: feedPoints } = await db.query(
      `with act as (
         select e.user_id, e.created_at,
                case e.kind when 'recommend' then greatest(cfg('w_recommend'), 0) when 'save' then greatest(cfg('w_save'), 0)
                            when 'tried' then greatest(cfg('w_tried'), 0) when 'share' then greatest(cfg('w_share'), 0) end as base,
                standing(pr.created_at, vi.twins) as st,
                (e.user_id = r.created_by) as owner,
                (e.kind <> 'tried' or exists (select 1 from engagements x where x.user_id = e.user_id and x.dish_id = e.dish_id
                                                                         and x.kind = 'recommend')) as counts
         from engagements e
         join dishes d on d.id = e.dish_id join restaurants r on r.id = d.restaurant_id
         join profiles pr on pr.id = e.user_id
         left join voter_independence vi on vi.user_id = e.user_id
         where e.dish_id = $1 and e.kind <> 'pass'
       )
       select a.user_id as "userId", pr.handle,
              (select array_agg(k.kind order by k.kind) from engagements k
                where k.user_id = a.user_id and k.dish_id = $1 and k.kind <> 'pass') as kinds,
              bool_or(a.owner) as "isOwner",
              round((case when bool_or(a.owner) then 0
                          else least(greatest(cfg('person_cap'), 0), coalesce(sum(a.base * decay(a.created_at, greatest(cfg('half_life_days'), 0.01))) filter (where a.counts), 0))
                               * max(a.st) end)::numeric, 4)::float8 as points
       from act a join profiles pr on pr.id = a.user_id
       group by a.user_id, pr.handle
       order by points desc limit 200`,
      [p.id],
    );
    const { rows: photos } = await db.query(
      `select p.id, p.thumb_path as "thumbUrl", p.status, p.source, p.visit, p.capture, p.taken_at as "takenAt",
              p.created_at as "createdAt", a.handle as author, p.author_id as "authorId", p.copy_of as "copyOf",
              (p.status = 'pending' and p.copy_of is not null) as held, p.photo_credit as credit,
              (select cover_post_id from dishes where id = $1) = p.id as "isCover"
       from posts p left join profiles a on a.id = p.author_id
       where p.dish_id = $1 order by p.created_at desc limit 100`,
      [p.id],
    );
    // Where it sits in its city's Top right now, and what it takes to get in.
    const { rows: rank } = await db.query<{ rank: number; lane: string }>(
      `select f.ord::int as rank, f.lane from get_feed($1, null, 'top', null, null, 500, 0, '', null)
                with ordinality f(dish_id, lane, ord) where f.dish_id = $2`,
      [dish.cityId, p.id],
    );
    const { rows: cfg } = await db.query<{ key: string; value: number }>(
      `select key, value::float8 as value from ranking_config
       where key in ('pp_prior', 'pp_prior_weight', 'pp_z', 'pp_half_life_days', 'trust_floor', 'visit_weight',
                     'min_endorsers_top', 'min_endorsers_trending', 'person_cap', 'half_life_days', 'standing_ramp_days')`,
    );
    return {
      dish,
      topRank: rank[0] ?? null,
      settings: Object.fromEntries(cfg.map((c) => [c.key, c.value])),
      votes,
      feedPoints,
      photos,
    };
  });

  // ---------- People ----------

  const USER_COLUMNS = `
    p.id, p.handle, p.email, p.display_name as "displayName", p.role, p.is_demo as "isDemo", p.created_at as "createdAt",
    p.email_verified_at is not null as "emailVerified", p.two_factor as "twoFactor",
    p.locked_until as "lockedUntil", p.failed_logins as "failedLogins", hc.name as "homeCity",
    (select count(*)::int from posts x where x.author_id = p.id) as posts,
    (select count(*)::int from engagements x where x.user_id = p.id and x.kind in ('recommend', 'pass')) as votes,
    (select count(*)::int from follows x where x.followee_id = p.id) as followers,
    (select count(*)::int from follows x where x.follower_id = p.id) as following,
    round(standing(p.created_at, vi.twins)::numeric, 3)::float8 as standing, coalesce(vi.twins, 0) as twins,
    round(ts.trust::numeric, 3)::float8 as trust, coalesce(ts.expert, false) as expert,
    (select count(*)::int from sessions s where s.user_id = p.id and s.revoked_at is null) as sessions`;
  const USER_JOINS = `
    left join voter_independence vi on vi.user_id = p.id
    left join taster_stats ts on ts.user_id = p.id and ts.category_id = 0
    left join cities hc on hc.id = p.home_city_id`;

  app.get('/admin/users', { preHandler: requireAdmin }, async (req, reply) => {
    const q = parse(
      z.object({ q: z.string().trim().max(80).optional(), limit: z.coerce.number().int().min(1).max(200).default(50) }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select ${USER_COLUMNS} from profiles p ${USER_JOINS}
       where $1::text is null or p.handle ilike '%' || $1 || '%' or p.email ilike '%' || $1 || '%'
          or p.display_name ilike '%' || $1 || '%'
       order by (p.handle = lower($3) or p.email = lower($3)) desc, (p.handle ilike $1 || '%') desc, p.created_at desc
       limit $2`,
      [q.q ? q.q.replace(/[%_\\]/g, (c) => `\\${c}`) : null, q.limit, q.q ?? ''],
    );
    return rows;
  });

  // "Why does X see this": their taste, who matches it, who they follow, and their For You.
  app.get('/admin/users/:id', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().min(1).max(60) }), req.params, reply);
    const q = parse(z.object({ city: z.coerce.number().int().positive().optional() }), req.query, reply);
    if (!p || !q) return;
    const uid = await findUser(p.id);
    if (!uid) return reply.code(404).send({ error: 'not_found', message: 'No such user.' });
    const { rows: [user] } = await db.query(`select ${USER_COLUMNS}, p.home_city_id as "homeCityId" from profiles p ${USER_JOINS} where p.id = $1`, [uid]);
    const { rows: [taste] } = await db.query(
      `select t.diet, t.spice, t.updated_at as "updatedAt",
              array(select c.name from dish_categories c where c.id = any(t.categories) order by c.sort) as categories
       from taste_profiles t where t.user_id = $1`,
      [uid],
    );
    const { rows: affinity } = await db.query(
      `select c.name as category, round(a.score::numeric, 2)::float8 as score
       from taste_affinity a join dish_categories c on c.id = a.category_id
       where a.user_id = $1 order by a.score desc limit 12`,
      [uid],
    );
    const { rows: trust } = await db.query(
      `select coalesce(c.name, case ts.category_id when 0 then 'All dishes' else 'No type' end) as category,
              round(ts.picks::numeric, 2)::float8 as picks, ts.backers, round(ts.agreed::numeric, 2)::float8 as agreed,
              round(ts.trust::numeric, 3)::float8 as trust, ts.expert
       from taster_stats ts left join dish_categories c on c.id = ts.category_id
       where ts.user_id = $1 order by ts.category_id = 0 desc, ts.trust desc limit 30`,
      [uid],
    );
    const { rows: matches } = await db.query(
      `select m.other_id as "userId", pr.handle, round(m.match::numeric, 3)::float8 as match, m.co_votes as "coVotes",
              m.agreed, round(m.evidence::numeric, 2)::float8 as evidence
       from taste_matches($1) m join profiles pr on pr.id = m.other_id
       order by m.match desc, m.co_votes desc limit 10`,
      [uid],
    );
    const { rows: follows } = await db.query(
      `select pr.id as "userId", pr.handle, f.created_at as "createdAt"
       from follows f join profiles pr on pr.id = f.followee_id
       where f.follower_id = $1 order by f.created_at desc limit 100`,
      [uid],
    );
    const { rows: twins } = await db.query(
      'select pr.id as "userId", pr.handle from voter_twins t join profiles pr on pr.id = t.twin_id where t.user_id = $1 limit 50',
      [uid],
    );
    const { rows: recentVotes } = await db.query(
      `select e.kind, e.created_at as "createdAt", d.id as "dishId", d.name as dish, r.name as place,
              (e.user_id = r.created_by) as "isOwner"
       from engagements e join dishes d on d.id = e.dish_id join restaurants r on r.id = d.restaurant_id
       where e.user_id = $1 order by e.created_at desc limit 50`,
      [uid],
    );
    const { rows: sessions } = await db.query(
      `select device, created_at as "createdAt", last_seen_at as "lastSeenAt", revoked_at as "revokedAt"
       from sessions where user_id = $1 order by last_seen_at desc limit 20`,
      [uid],
    );
    // Their For You in the city asked for, else their home city, else where they last voted.
    const cityId: number | null =
      q.city ??
      (user as { homeCityId: number | null }).homeCityId ??
      (await db.query<{ city_id: number }>(
        `select d.city_id from engagements e join dishes d on d.id = e.dish_id where e.user_id = $1
         order by e.created_at desc limit 1`,
        [uid],
      )).rows[0]?.city_id ??
      (await db.query<{ city_id: number }>("select city_id from dishes where status = 'live' group by city_id order by count(*) desc limit 1")).rows[0]?.city_id ??
      null;
    const forYou = cityId
      ? (
          await db.query(
            `select f.ord::int as rank, d.id, d.name, r.name as place, round(f.score::numeric, 4)::float8 as score,
                    bu.handle as "byUser", f.by_user as "byUserId", round(f.by_match::numeric, 3)::float8 as "byMatch",
                    f.followed, f.people, c.name as category,
                    round(coalesce(dr.confidence, 0)::numeric, 3)::float8 as confidence
             from for_you($1, $2, 20, 0) with ordinality f(dish_id, score, by_user, by_match, followed, people, category_id, ord)
             join dishes d on d.id = f.dish_id join restaurants r on r.id = d.restaurant_id
             left join profiles bu on bu.id = f.by_user
             left join dish_categories c on c.id = f.category_id
             left join dish_rating dr on dr.dish_id = d.id
             order by f.ord`,
            [uid, cityId],
          )
        ).rows
      : [];
    return { user, taste: taste ?? null, affinity, trust, matches, follows, twins, recentVotes, sessions, forYouCity: cityId, forYou };
  });

  // ---------- Places ----------

  app.get('/admin/places', { preHandler: requireAdmin }, async (req, reply) => {
    const q = parse(
      z.object({
        q: z.string().trim().max(80).optional(),
        city: z.coerce.number().int().positive().optional(),
        status: z.enum(['active', 'pending', 'hidden']).optional(),
        source: z.enum(['user', 'osm', 'overture', 'seed']).optional(),
        limit: z.coerce.number().int().min(1).max(200).default(50),
      }),
      req.query,
      reply,
    );
    if (!q) return;
    const { rows } = await db.query(
      `select r.id, r.name, r.locality, r.status, r.source, r.osm_id as "osmId", r.cuisine, r.created_at as "createdAt",
              c.id as "cityId", c.name as city, pr.handle as "addedBy",
              st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
              (select count(*)::int from dishes d where d.restaurant_id = r.id and d.status = 'live') as dishes,
              (select count(*)::int from posts p join dishes d on d.id = p.dish_id
                where d.restaurant_id = r.id and p.status = 'live') as photos,
              (select count(*)::int from restaurant_photos rp where rp.restaurant_id = r.id) as "placePhotos"
       from restaurants r join cities c on c.id = r.city_id left join profiles pr on pr.id = r.created_by
       where ($1::text is null or r.name_norm like '%' || norm_name($1) || '%' or r.name ilike '%' || $1 || '%')
         and ($2::int is null or r.city_id = $2)
         and ($3::text is null or r.status = $3)
         and ($4::text is null or r.source = $4)
       order by (r.status = 'pending') desc, r.created_at desc limit $5`,
      [q.q || null, q.city ?? null, q.status ?? null, q.source ?? null, q.limit],
    );
    return rows;
  });

  // Likely duplicates: two live places in one city within 150 m with similar names.
  app.get('/admin/places/duplicates', { preHandler: requireAdmin }, async (req, reply) => {
    const q = parse(z.object({ city: z.coerce.number().int().positive() }), req.query, reply);
    if (!q) return;
    const { rows } = await db.query(
      `select a.id as "aId", a.name as "aName", a.source as "aSource",
              (select count(*)::int from dishes d where d.restaurant_id = a.id and d.status = 'live') as "aDishes",
              b.id as "bId", b.name as "bName", b.source as "bSource",
              (select count(*)::int from dishes d where d.restaurant_id = b.id and d.status = 'live') as "bDishes",
              round(st_distance(a.geo, b.geo))::int as "distanceM",
              round(similarity(a.name_norm, b.name_norm)::numeric, 2)::float8 as similarity
       from restaurants a
       join restaurants b on b.city_id = a.city_id and a.id < b.id and b.status <> 'hidden'
                         and st_dwithin(a.geo, b.geo, 150) and similarity(a.name_norm, b.name_norm) >= 0.4
       where a.city_id = $1 and a.status <> 'hidden'
       order by similarity(a.name_norm, b.name_norm) desc limit 50`,
      [q.city],
    );
    return rows;
  });

  // Real places from OpenStreetMap, fetched in the background: the Overpass servers can
  // take a minute or more, far longer than a request should wait.
  app.post('/admin/cities/:slug/import-osm', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ slug: z.string().max(60) }), req.params, reply);
    const b = parse(z.object({ radiusKm: z.number().min(0.5).max(60).optional() }).default({}), req.body ?? {}, reply);
    if (!p || !b) return;
    const { rows } = await db.query<{ id: number; name: string; lat: number; lng: number; radius_km: number }>(
      'select id, name, st_y(center::geometry) as lat, st_x(center::geometry) as lng, radius_km from cities where slug = $1',
      [p.slug],
    );
    const city = rows[0];
    if (!city) return reply.code(404).send({ error: 'not_found', message: 'No such city.' });
    if (jobs.some((j) => j.kind === 'import-osm' && j.status === 'running' && j.label.startsWith(`${city.name} `))) {
      return reply.code(409).send({ error: 'busy', message: `An import for ${city.name} is already running.` });
    }
    const radiusKm = b.radiusKm ?? Math.min(city.radius_km, 25);
    const job = startJob('import-osm', `${city.name} (${radiusKm} km)`);
    const log = say(job);
    void (async () => {
      try {
        log(`Asking OpenStreetMap for food places within ${radiusKm} km of ${city.name}...`);
        const places = await osmSource.fetch(city.lat, city.lng, radiusKm, log);
        log(`Got ${places.length} places; adding them.`);
        const r = await importOsmPlaces(db, city.id, places);
        log(`Added ${r.added}, linked ${r.linked} to places already here, skipped ${r.skipped} imported before.`);
        finishJob(job, 'done', { fetched: places.length, ...r });
      } catch (err) {
        log(`Failed: ${(err as Error).message}`);
        finishJob(job, 'failed', { error: (err as Error).message });
      }
    })();
    return reply.code(202).send({ started: true, jobId: job.id });
  });

  // Every food place from Overture Maps, from the file bundled for the city (seed/places/).
  app.post('/admin/cities/:slug/import-overture', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ slug: z.string().regex(/^[a-z0-9-]{1,60}$/) }), req.params, reply);
    if (!p) return;
    const { rows } = await db.query<{ id: number; name: string }>('select id, name from cities where slug = $1', [p.slug]);
    const city = rows[0];
    if (!city) return reply.code(404).send({ error: 'not_found', message: 'No such city.' });
    let file;
    try {
      file = await readOvertureFile(overtureFileFor(p.slug));
    } catch {
      return reply.code(404).send({
        error: 'no_file',
        message: `No Overture places file for ${city.name} yet. Make one with scripts/overture-places.py.`,
      });
    }
    if (jobs.some((j) => j.kind === 'import-overture' && j.status === 'running')) {
      return reply.code(409).send({ error: 'busy', message: 'An Overture import is already running.' });
    }
    const job = startJob('import-overture', `${city.name} (${file.places.length} places, release ${file.release})`);
    const log = say(job);
    void (async () => {
      try {
        log(`Adding ${file.places.length} Overture Maps places to ${city.name}...`);
        const r = await importOverturePlaces(db, city.id, file.places);
        log(`Added ${r.added}, linked ${r.linked} to places already here, skipped ${r.skipped} imported before.`);
        finishJob(job, 'done', { fetched: file.places.length, ...r });
      } catch (err) {
        log(`Failed: ${(err as Error).message}`);
        finishJob(job, 'failed', { error: (err as Error).message });
      }
    })();
    return reply.code(202).send({ started: true, jobId: job.id });
  });

  app.get('/admin/jobs', { preHandler: requireAdmin }, async () => jobs);

  // Takes the demo accounts' votes away, and with all=true the invented demo places too.
  app.post('/admin/demo/remove', { preHandler: requireAdmin }, async (req, reply) => {
    const b = parse(z.object({ all: z.boolean().default(false) }), req.body ?? {}, reply);
    if (!b) return;
    const job = startJob('remove-demo', b.all ? 'Demo accounts and demo places' : 'Demo accounts');
    const log = say(job);
    try {
      const accounts = await removeDemoActivity(db);
      log(`Removed ${accounts} demo accounts and their votes.`);
      let catalog = { places: 0, photos: 0 };
      if (b.all) {
        catalog = await removeDemoCatalog(db, app.config.mediaDir);
        log(`Removed ${catalog.places} demo places and ${catalog.photos} demo photos.`);
      }
      const result = { accounts, ...catalog };
      finishJob(job, 'done', result);
      return { ok: true, ...result };
    } catch (err) {
      log(`Failed: ${(err as Error).message}`);
      finishJob(job, 'failed', { error: (err as Error).message });
      throw err;
    }
  });

  // ---------- Activity ----------

  app.get('/admin/activity', { preHandler: requireAdmin }, async (req, reply) => {
    const q = parse(z.object({ limit: z.coerce.number().int().min(1).max(200).default(50) }), req.query, reply);
    if (!q) return;
    const [posts, comments, signups, reports, votes] = await Promise.all([
      db.query(
        `select p.id, p.created_at as "createdAt", p.status, p.visit, p.capture, p.source, p.thumb_path as "thumbUrl",
                p.copy_of is not null as "isCopy", d.id as "dishId", d.name as dish, r.name as place,
                a.id as "authorId", a.handle as author
         from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
         left join profiles a on a.id = p.author_id
         order by p.created_at desc limit $1`,
        [q.limit],
      ),
      db.query(
        `select c.id, c.created_at as "createdAt", c.status, left(c.body, 160) as body, a.id as "authorId", a.handle as author,
                d.id as "dishId", d.name as dish
         from comments c join profiles a on a.id = c.author_id join posts p on p.id = c.post_id join dishes d on d.id = p.dish_id
         order by c.created_at desc limit $1`,
        [q.limit],
      ),
      db.query(
        `select id, handle, email, created_at as "createdAt", is_demo as "isDemo", email_verified_at is not null as "emailVerified"
         from profiles order by created_at desc limit $1`,
        [q.limit],
      ),
      db.query(
        `select rp.id, rp.created_at as "createdAt", rp.target_type as "targetType", rp.target_id as "targetId",
                rp.reason, left(rp.note, 200) as note, rp.status, a.id as "reporterId", a.handle as reporter
         from reports rp join profiles a on a.id = rp.reporter_id
         order by rp.created_at desc limit $1`,
        [q.limit],
      ),
      db.query(
        `select e.kind, e.created_at as "createdAt", u.id as "userId", u.handle, d.id as "dishId", d.name as dish, r.name as place
         from engagements e join profiles u on u.id = e.user_id join dishes d on d.id = e.dish_id
         join restaurants r on r.id = d.restaurant_id
         where e.kind in ('recommend', 'pass')
         order by e.created_at desc limit $1`,
        [q.limit],
      ),
    ]);
    return { posts: posts.rows, comments: comments.rows, signups: signups.rows, reports: reports.rows, votes: votes.rows };
  });
}
