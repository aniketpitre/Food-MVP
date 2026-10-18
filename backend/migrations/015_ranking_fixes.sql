-- Ranking and posting fixes.
--
--  * Top needs people who actually voted: a dish gets in on the standing of the people who
--    loved it or said "not for me" (w_voters), not on saves and shares.
--  * Whoever added a place builds no trust from its dishes: their own votes there are not
--    picks, and they don't count as agreeing with anyone else's pick there.
--  * A verified visit makes a vote count more, but never makes someone a champion or an
--    expert: those checks use plain standing.
--  * Fresh follows photos from diners, not from the place's own account.
--  * Uploads are remembered on their own (post_uploads), so deleting a post doesn't give
--    back today's posting limits.
--  * Photo fingerprints are split into bands, so a copy check looks at a handful of
--    candidates instead of every photo ever posted.
--  * A single dish re-rates under its own lock, so a vote never waits for a full rebuild,
--    and a full rebuild that finds another one running simply leaves it to finish.

-- Edit distance, to catch a typo in a dish name ("Buter Chiken").
create extension if not exists fuzzystrmatch;

-- ============ Uploads ============
-- Every successful upload, kept even if the post is deleted later: the daily limits count
-- these, so deleting and re-posting doesn't free a slot.
create table if not exists post_uploads (
  author_id  uuid not null references profiles(id) on delete cascade,
  dish_id    uuid not null references dishes(id) on delete cascade,
  created_at timestamptz not null default now()
);
create index if not exists post_uploads_author_idx on post_uploads (author_id, created_at desc);
create index if not exists post_uploads_dish_idx on post_uploads (author_id, dish_id, created_at desc);

-- Today's posts so far count toward today's limits.
insert into post_uploads (author_id, dish_id, created_at)
select p.author_id, p.dish_id, p.created_at from posts p
where p.author_id is not null and p.created_at > now() - interval '24 hours'
  and not exists (select 1 from post_uploads u where u.author_id = p.author_id and u.dish_id = p.dish_id and u.created_at = p.created_at);

-- ============ Photo fingerprint bands ============
-- The 64-bit fingerprint as its 8 bytes, each tagged with its position (position * 256 +
-- byte). Two photos within copy_distance (6) bits differ in at most 6 bytes, so they share
-- at least 2 bands: "shares a band" (&&, on a GIN index) is a safe first filter before the
-- exact bit count.
create or replace function phash_bands(h bigint) returns int[]
language sql immutable as $$
  select case when h is null then null
              else array(select (i * 256 + ((h >> (8 * i)) & 255))::int from generate_series(0, 7) i) end
$$;

alter table posts add column if not exists ph_bands int[] generated always as (phash_bands(phash)) stored;
create index if not exists posts_ph_bands_idx on posts using gin (ph_bands) where ph_bands is not null;

-- Does this voter have a verified photo of the dish? Asked for every vote on a re-rate.
create index if not exists posts_verified_visit_idx on posts (dish_id, author_id) where status = 'live' and visit = 'verified';

-- ============ Ratings ============
-- The standing of the people who loved the dish or said "not for me" (the owner left
-- out): the Top lane's minimum counts these people, not people who only saved or shared.
alter table dish_rating add column if not exists w_voters double precision not null default 0;

create or replace function refresh_dish_stats(p_dish uuid default null) returns void
language plpgsql as $$
declare
  w_rec numeric := greatest(cfg('w_recommend'), 0);
  w_sav numeric := greatest(cfg('w_save'), 0);
  w_tri numeric := greatest(cfg('w_tried'), 0);
  w_sha numeric := greatest(cfg('w_share'), 0);
  cap   numeric := greatest(cfg('person_cap'), 0);
  hl    numeric := greatest(cfg('half_life_days'), 0.01);
begin
  -- A full rebuild already running will do this one's work: don't queue behind it. One
  -- dish only waits for another refresh of the same dish.
  if p_dish is null then
    if not pg_try_advisory_xact_lock(727001) then return; end if;
  else
    perform pg_advisory_xact_lock(727001, hashtext(p_dish::text));
  end if;

  -- Rows are upserted, then the dishes nobody acts on any more lose theirs, so a full
  -- rebuild and a single dish running side by side can't trip over each other's rows.
  with act as (
    select e.user_id, e.dish_id, e.kind, e.created_at,
           (case e.kind when 'recommend' then w_rec
                        when 'save'      then w_sav
                        when 'tried'     then w_tri
                        when 'share'     then w_sha end) as base,
           standing(p.created_at, vi.twins) as st
    from engagements e
    join dishes d   on d.id = e.dish_id and d.status = 'live'
    join restaurants r on r.id = d.restaurant_id
    join profiles p on p.id = e.user_id
    left join voter_independence vi on vi.user_id = e.user_id
    where (p_dish is null or e.dish_id = p_dish)
      and e.kind <> 'pass'
      -- Whoever added the place doesn't vote for its dishes.
      and e.user_id is distinct from r.created_by
      -- "Tried" only counts alongside a love: on its own (or with "not for me") it says
      -- nothing good about the dish.
      and (e.kind <> 'tried' or exists (
        select 1 from engagements x where x.user_id = e.user_id and x.dish_id = e.dish_id and x.kind = 'recommend'))
  ),
  per_user as (
    -- Capped first, then weighted by standing, so a new account can't fill the cap.
    select user_id, dish_id, max(st) as st,
      least(cap, sum(base * decay(created_at, hl))) * max(st) as contrib,
      least(cap, coalesce(sum(base) filter (where created_at > now() - interval '7 days'), 0)) * max(st) as contrib_7d
    from act group by user_id, dish_id
  ),
  up as (
    insert into dish_stats
      (dish_id, endorsers, w_endorsers, recommenders, recommends, saves, shares, tried,
       score, score_7d, last_engaged_at, updated_at)
    select a.dish_id, a.endorsers, a.w_endorsers, k.recommenders, k.recommends, k.saves, k.shares, k.tried,
           a.score, a.score_7d, k.last_engaged_at, now()
    from (select dish_id, count(*) as endorsers, sum(st)::float8 as w_endorsers,
                 sum(contrib)::float8 as score, sum(contrib_7d)::float8 as score_7d
          from per_user group by dish_id) a
    join (select dish_id,
                 count(distinct user_id) filter (where kind = 'recommend' or kind = 'tried') as recommenders,
                 count(*) filter (where kind = 'recommend') as recommends,
                 count(*) filter (where kind = 'save')      as saves,
                 count(*) filter (where kind = 'share')     as shares,
                 count(*) filter (where kind = 'tried')     as tried,
                 max(created_at)                            as last_engaged_at
          from act group by dish_id) k using (dish_id)
    on conflict (dish_id) do update set
      endorsers = excluded.endorsers, w_endorsers = excluded.w_endorsers,
      recommenders = excluded.recommenders, recommends = excluded.recommends,
      saves = excluded.saves, shares = excluded.shares, tried = excluded.tried,
      score = excluded.score, score_7d = excluded.score_7d,
      last_engaged_at = excluded.last_engaged_at, updated_at = excluded.updated_at
    returning dish_id
  )
  delete from dish_stats s
  where (p_dish is null or s.dish_id = p_dish)
    and not exists (select 1 from up where up.dish_id = s.dish_id);
end $$;

create or replace function refresh_people_pick(p_dish uuid default null) returns void
language plpgsql as $$
declare
  t_floor numeric := greatest(cfg('trust_floor'), 0);
  t_span  numeric := greatest(cfg('trust_span'), 0);
  k       numeric := greatest(cfg('trust_prior_picks'), 0.01);
  p0      numeric := least(greatest(cfg('pp_prior'), 0.01), 0.99);
  m       numeric := greatest(cfg('pp_prior_weight'), 0.1);
  z       numeric := greatest(cfg('pp_z'), 0);
  hl      numeric := greatest(cfg('pp_half_life_days'), 0.01);
  ex_p    numeric := cfg('expert_min_picks');
  ex_t    numeric := cfg('expert_min_trust');
  ex_s    numeric := cfg('expert_min_standing');
  visit_w numeric := least(greatest(cfg('visit_weight'), 1), 2);
begin
  -- A full rebuild already running will do this one's work: don't queue behind it. A single
  -- dish (after a vote) only waits for another re-rate of the same dish, never for a
  -- whole rebuild.
  if p_dish is null then
    if not pg_try_advisory_xact_lock(727002) then return; end if;
  else
    perform pg_advisory_xact_lock(727002, hashtext(p_dish::text));
  end if;

  -- 1. Trust (only on a full refresh; a single vote just re-rates its dish).
  --    Each love or "not for me" is a pick. Its credit is how far the people who voted
  --    after it agreed beyond what always voting that way would earn, counted by their
  --    standing; a pick that only throwaway or lockstep accounts followed barely counts.
  --    Votes at a place by whoever added it are neither picks nor agreement: an owner
  --    can't build trust from their own menu.
  if p_dish is null then
  perform refresh_voter_independence();
  delete from taster_stats;
  insert into taster_stats (user_id, category_id, picks, backers, agreed, trust, expert)
  with st as (
    select p.id as user_id, standing(p.created_at, vi.twins) as st
    from profiles p left join voter_independence vi on vi.user_id = p.id
  ),
  picks as (
    select e.user_id, e.dish_id, e.kind, e.created_at, coalesce(d.category_id, -1) as cat, r.created_by as owner
    from engagements e
    join dishes d on d.id = e.dish_id and d.status = 'live'
    join restaurants r on r.id = d.restaurant_id
    where e.kind in ('recommend', 'pass') and e.user_id is distinct from r.created_by
  ),
  later as (
    select pk.user_id, pk.cat, pk.dish_id, pk.kind,
           sum(s.st) as w_later,
           coalesce(sum(s.st) filter (where v.kind = pk.kind), 0) as w_agree,
           count(*) filter (where v.kind = pk.kind) as n_agree
    from picks pk
    join engagements v on v.dish_id = pk.dish_id and v.user_id <> pk.user_id
                      and v.kind in ('recommend', 'pass') and v.created_at > pk.created_at
                      and v.user_id is distinct from pk.owner
    join st s on s.user_id = v.user_id
    where not exists (select 1 from voter_twins t where t.user_id = pk.user_id and t.twin_id = v.user_id)
    group by pk.user_id, pk.cat, pk.dish_id, pk.kind
  ),
  per_pick as (
    select user_id, cat, n_agree,
           least(1.0, w_later) as weight,
           least(1.0, w_later) * greatest(0.0,
             (w_agree / nullif(w_later, 0) - (case kind when 'recommend' then p0 else 1 - p0 end))
             / (case kind when 'recommend' then 1 - p0 else p0 end)) as credit
    from later
  ),
  per_cat as (
    select user_id, cat, sum(weight)::float8 as picks, sum(n_agree)::int as backers,
           coalesce(sum(credit), 0)::float8 as agreed
    from per_pick group by user_id, cat
  ),
  overall as (
    select user_id, 0 as cat, sum(picks)::float8 as picks, sum(backers)::int as backers, sum(agreed)::float8 as agreed
    from per_cat group by user_id
  )
  select s.user_id, s.cat, s.picks, s.backers, s.agreed, tr.trust,
         s.picks >= ex_p and tr.trust >= ex_t and coalesce(st.st, 0) >= ex_s
  from (select * from per_cat union all select * from overall) s
  cross join lateral (select (t_floor + t_span * s.agreed / (s.picks + k))::float8 as trust) tr
  left join st on st.user_id = s.user_id;
  end if;

  -- 2. Each dish: weighted loves vs weighted "not for me" (trust x standing x visit x age),
  --    pulled towards the prior when there are few votes, ranked by a cautious lower
  --    estimate. Every live dish gets a row, so a dish nobody voted on sits at the prior.
  --    A verified visit only adds weight to the vote: the champion still needs full
  --    standing on its own.
  with votes as (
    select e.dish_id, e.user_id, e.kind, e.created_at,
           coalesce(ts.trust, tall.trust, t_floor) as trust,
           standing(p.created_at, vi.twins) as st,
           (case when exists (select 1 from posts vp where vp.dish_id = e.dish_id and vp.author_id = e.user_id
                               and vp.status = 'live' and vp.visit = 'verified')
                 then visit_w else 1 end) as vw
    from engagements e
    join dishes d   on d.id = e.dish_id and d.status = 'live'
    join restaurants r on r.id = d.restaurant_id and e.user_id is distinct from r.created_by
    join profiles p on p.id = e.user_id
    left join voter_independence vi on vi.user_id = e.user_id
    left join taster_stats ts   on ts.user_id = e.user_id and ts.category_id = coalesce(d.category_id, -1)
    left join taster_stats tall on tall.user_id = e.user_id and tall.category_id = 0
    where e.kind in ('recommend', 'pass') and (p_dish is null or e.dish_id = p_dish)
  ),
  agg as (
    select dish_id,
           count(*) filter (where kind = 'recommend')::int as loves,
           count(*) filter (where kind = 'pass')::int      as passes,
           coalesce(sum(w) filter (where kind = 'recommend'), 0) as wl,
           coalesce(sum(w) filter (where kind = 'pass'), 0)      as wp,
           coalesce(sum(st), 0)::float8                          as wv
    from (select *, trust * st * vw * decay(created_at, hl) as w from votes) x
    group by dish_id
  ),
  champ as (
    select distinct on (dish_id) dish_id, user_id
    from votes where kind = 'recommend' and st >= ex_s
    order by dish_id, trust * st desc, created_at, user_id
  ),
  up as (
    insert into dish_rating (dish_id, loves, passes, w_loves, w_passes, w_voters, rating, confidence, champion_id, updated_at)
    select d.id, coalesce(a.loves, 0), coalesce(a.passes, 0), coalesce(a.wl, 0), coalesce(a.wp, 0), coalesce(a.wv, 0), r.rating,
           greatest(0, r.rating - z * sqrt(r.rating * (1 - r.rating) / (coalesce(a.wl, 0) + coalesce(a.wp, 0) + m))),
           c.user_id, now()
    from dishes d
    left join agg a on a.dish_id = d.id
    cross join lateral (select (coalesce(a.wl, 0) + m * p0) / (coalesce(a.wl, 0) + coalesce(a.wp, 0) + m) as rating) r
    left join champ c on c.dish_id = d.id
    where d.status = 'live' and (p_dish is null or d.id = p_dish)
    on conflict (dish_id) do update set
      loves = excluded.loves, passes = excluded.passes, w_loves = excluded.w_loves, w_passes = excluded.w_passes,
      w_voters = excluded.w_voters, rating = excluded.rating, confidence = excluded.confidence,
      champion_id = excluded.champion_id, updated_at = excluded.updated_at
    returning dish_id
  )
  -- A dish that is no longer live loses its rating.
  delete from dish_rating x
  where (p_dish is null or x.dish_id = p_dish)
    and not exists (select 1 from up where up.dish_id = x.dish_id);
end $$;

create or replace function get_feed(
  p_city     int,
  p_category int,
  p_tab      text,
  p_lat      double precision,
  p_lng      double precision,
  p_limit    int,
  p_offset   int,
  p_seed     text,
  p_user     uuid
) returns table (dish_id uuid, lane text)
language sql stable as $$
  with city_dishes as (
    select d.id, d.restaurant_id, d.category_id, d.created_at as dish_created, d.featured_rank,
           coalesce(s.endorsers, 0)   as endorsers,
           coalesce(s.w_endorsers, 0) as w_endorsers,
           coalesce(dr.w_voters, 0)   as w_voters,
           coalesce(s.score, 0)       as score,
           coalesce(s.score_7d, 0)    as score_7d,
           coalesce(dr.confidence, 0) as confidence,
           s.last_engaged_at,
           r.geo,
           cume_dist() over (order by coalesce(s.score, 0) desc) as top_frac,
           -- Fresh follows photos from diners: the place's own account re-posting doesn't
           -- keep its dishes at the top of Fresh.
           (select max(p.created_at) from posts p
             where p.dish_id = d.id and p.status = 'live'
               and (r.created_by is null or p.author_id is distinct from r.created_by)) as newest_post
    from dishes d
    join restaurants r on r.id = d.restaurant_id and r.status = 'active'
    left join dish_stats s on s.dish_id = d.id
    left join dish_rating dr on dr.dish_id = d.id
    where d.city_id = p_city and d.status = 'live'
      and exists (
        select 1 from posts p
        where p.dish_id = d.id and p.status = 'live'
          and (p_user is null or not exists
               (select 1 from blocks b where b.blocker_id = p_user and b.blocked_id = p.author_id)))
  ),
  place as (
    select r.id as restaurant_id,
           -- Only the Hidden gems tab uses these two counts, so only it pays for them.
           case when p_tab = 'gems' then
           (select count(*) from posts p join dishes d2 on d2.id = p.dish_id
             where d2.restaurant_id = r.id and p.status = 'live') end              as live_posts,
           case when p_tab = 'gems' then
           (select count(distinct e.user_id) from engagements e join dishes d3 on d3.id = e.dish_id
             where d3.restaurant_id = r.id
               -- people who said "not for me" are not fans of the place
               and not exists (select 1 from engagements x
                               where x.user_id = e.user_id and x.dish_id = e.dish_id and x.kind = 'pass')) end as place_endorsers
    from restaurants r
    where r.city_id = p_city and r.status = 'active'
  ),
  base as (
    select c.*, pl.live_posts, pl.place_endorsers,
           case when p_lat is not null and p_lng is not null
                then st_distance(c.geo, st_setsrid(st_makepoint(p_lng, p_lat), 4326)::geography)
           end as dist
    from city_dishes c
    join place pl using (restaurant_id)
    where p_category is null or c.category_id = p_category
  ),
  -- Top: People's Pick first (loves against "not for me", by trust), feed points break ties.
  -- Getting in takes enough people who loved it or said "not for me" (by standing): saves
  -- and shares alone don't put a dish in Top.
  -- A place's third dish and beyond wait place_spread cards per extra dish, so one place
  -- can't fill the screen.
  top_ranked as (
    select b.id, b.restaurant_id, row_number() over
             (order by b.confidence desc, b.score desc, b.last_engaged_at desc nulls last, b.id) as k0
    from base b
    where p_tab = 'top' and b.w_voters >= cfg('min_endorsers_top')
  ),
  top_spread as (
    select t.*, row_number() over (partition by t.restaurant_id order by t.k0) as j from top_ranked t
  ),
  top_lane as (
    select t.id, row_number() over
             (order by t.k0 + greatest(t.j - cfg('place_max_in_row'), 0) * greatest(cfg('place_spread'), 0), t.k0) as k
    from top_spread t
  ),
  fresh_lane as (
    select b.id, row_number() over
             (order by b.featured_rank nulls last, greatest(b.dish_created, b.newest_post) desc, b.id) as m
    from base b
    where p_tab = 'top'
      -- A new dish that already earned an early Top place doesn't take a Fresh slot too.
      and not exists (select 1 from top_lane t where t.id = b.id and t.k <= 40)
      and greatest(b.dish_created, b.newest_post) > now() - make_interval(days => greatest(cfg('fresh_days'), 0)::int)
  ),
  slotted as (
    select id, 'top'::text as lane, k + (k - 1) / 4 as slot from top_lane
    union all
    select id, 'fresh', 5 * m from fresh_lane
    union all
    select b.id, 'trending', row_number() over (order by b.score_7d desc, b.score desc, b.id)
    from base b where p_tab = 'trending' and b.score_7d > 0 and b.w_endorsers >= cfg('min_endorsers_trending')
    union all
    select b.id, 'gem', row_number() over (order by b.score desc, b.id)
    from base b
    where p_tab = 'gems'
      and b.w_endorsers >= cfg('gem_min_endorsers')
      and b.top_frac   <= cfg('gem_top_fraction')
      and b.live_posts      < cfg('gem_max_posts')
      and b.place_endorsers < cfg('gem_max_place_endorsers')
    union all
    select b.id, 'nearby', row_number() over (order by b.dist nulls last, b.confidence desc, b.score desc, b.id)
    from base b where p_tab = 'nearby'
  ),
  deduped as (
    select distinct on (id) id, lane, slot from slotted order by id, slot
  )
  select id, lane from deduped order by slot, id offset p_offset limit p_limit;
$$;

-- Each city's Top 20, once a day. A city already kept today is skipped before its feed is
-- worked out (the ranking timer calls this many times a day). Fresh cards sit between the
-- Top cards, so the feed is asked for more than 20 and only the Top ones are kept.
create or replace function snapshot_top() returns void
language sql as $$
  insert into top_snapshots (city_id, day, dish_ids)
  select c.id, current_date,
         array(select f.dish_id
               from get_feed(c.id, null, 'top', null, null, 40, 0, '', null) with ordinality f(dish_id, lane, n)
               where f.lane = 'top' order by f.n limit 20)
  from cities c
  where not exists (select 1 from top_snapshots t where t.city_id = c.id and t.day = current_date)
  on conflict (city_id, day) do nothing
$$;

select refresh_dish_stats();
select refresh_people_pick();
