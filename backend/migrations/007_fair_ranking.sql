-- Fair ranking: votes that can't be faked cheaply, and trust that rewards good judgement.
--
--  * Standing: a vote counts by its account's standing. A new account grows to full weight
--    over standing_ramp_days (a one-hour-old account counts about 1/168), and accounts
--    that vote in lockstep (same dishes, same way, within hours of each other) share one
--    person's weight between them. Standing is applied after the per-person cap, so it
--    always bites (the old new-account factor was applied before the cap and did nothing).
--  * Lane minimums (Top, Trending, Hidden gems) count people by standing, not raw accounts.
--  * Trust credits a correct "not for me" as well as a correct love, and only credit beyond
--    what loving everything would earn. Agreement only counts from independent, established
--    voters, and a record starts at the floor (newcomers no longer outrank a poor record).
--  * A dish nobody has voted on starts at the prior instead of below disliked dishes.
--  * The Fresh lane is dishes with a new photo in the last fresh_days, newest first.
--  * Every setting is clamped where it's used, so no value can break a vote.

-- ============ Settings ============
delete from ranking_config where key in ('new_account_hours', 'new_account_factor');
insert into ranking_config (key, value, note) values
  ('standing_ramp_days',     7.0, 'a new account''s votes grow to full weight over this many days'),
  ('twin_window_hours',      2.0, 'two votes on the same dish, the same way, this close together are in lockstep'),
  ('twin_min_votes',         2.0, 'lockstep votes needed before two accounts count as one person'),
  ('twin_overlap',           0.8, 'share of the smaller account''s votes that must be in lockstep'),
  ('trust_prior_picks',      2.0, 'a record starts as this many picks nobody backed'),
  ('min_endorsers_trending', 2.0, 'people (by standing) needed to enter Trending'),
  ('expert_min_standing',    0.5, 'standing needed to be named an expert or a champion')
on conflict (key) do nothing;
update ranking_config set value = 1.05, note = 'trust needed to be called an expert'
 where key = 'expert_min_trust' and value = 1.1;
update ranking_config set note = 'weight of a voter with no record, or whose picks earned nothing'
 where key = 'trust_floor';
update ranking_config set note = 'extra weight for a voter whose every pick was backed (max = floor + span)'
 where key = 'trust_span';

-- ============ Helpers ============
-- How much a vote has faded: halves every half_life_days. Floating point, and floored far
-- below anything that matters, so no age or half-life can overflow or underflow.
create or replace function decay(p_at timestamptz, p_half_life_days numeric) returns double precision
language sql stable as $$
  select exp(greatest(-700.0, -ln(2.0)::float8 * extract(epoch from (now() - p_at))::float8
                              / 86400.0 / greatest(p_half_life_days, 0.01)::float8))
$$;

-- ============ Standing ============
create table if not exists voter_independence (
  user_id uuid primary key references profiles(id) on delete cascade,
  twins   int not null          -- accounts voting in lockstep with this one
);
-- Who those lockstep partners are: agreement from your own ring never builds your trust.
create table if not exists voter_twins (
  user_id uuid not null references profiles(id) on delete cascade,
  twin_id uuid not null references profiles(id) on delete cascade,
  primary key (user_id, twin_id)
);

-- 0..1: how much a person's vote counts, from account age and lockstep partners.
create or replace function standing(p_created timestamptz, p_twins int) returns double precision
language sql stable as $$
  select (least(1.0, greatest(0.0, extract(epoch from (now() - p_created)) / 86400.0
                                   / greatest(cfg('standing_ramp_days'), 0.001)))
          / (1 + greatest(coalesce(p_twins, 0), 0)))::float8
$$;

-- Votes on a dish in time order: the lockstep check looks at a few hours either side.
create index if not exists engagements_dish_kind_time on engagements (dish_id, kind, created_at);

-- Finds accounts voting in lockstep. Run on the ranking timer (and with the trust rebuild).
create or replace function refresh_voter_independence() returns void
language plpgsql as $$
declare
  win  numeric := greatest(cfg('twin_window_hours'), 0);
  minv numeric := greatest(cfg('twin_min_votes'), 1);
  ovl  numeric := least(greatest(cfg('twin_overlap'), 0), 1);
begin
  perform pg_advisory_xact_lock(727003);
  delete from voter_independence;
  delete from voter_twins;
  insert into voter_twins (user_id, twin_id)
  with v as (
    select user_id, dish_id, kind, created_at from engagements where kind in ('recommend', 'pass')
  ),
  n as (select user_id, count(*) as n from v group by user_id),
  pairs as (
    select a.user_id as u, b.user_id as w, count(*) as c
    from v a join v b on b.dish_id = a.dish_id and b.kind = a.kind and b.user_id <> a.user_id
                     and b.created_at between a.created_at - make_interval(secs => win * 3600)
                                          and a.created_at + make_interval(secs => win * 3600)
    group by a.user_id, b.user_id
  )
  select p.u, p.w
  from pairs p join n na on na.user_id = p.u join n nb on nb.user_id = p.w
  where p.c >= minv and p.c >= ovl * least(na.n, nb.n);
  insert into voter_independence (user_id, twins)
  select user_id, count(*)::int from voter_twins group by user_id;
end $$;

-- ============ Feed points ============
alter table dish_stats add column if not exists w_endorsers double precision not null default 0;

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
  perform pg_advisory_xact_lock(727001);

  delete from dish_stats where p_dish is null or dish_id = p_dish;

  insert into dish_stats
    (dish_id, endorsers, w_endorsers, recommenders, recommends, saves, shares, tried,
     score, score_7d, last_engaged_at, updated_at)
  with act as (
    select e.user_id, e.dish_id, e.kind, e.created_at,
           (case e.kind when 'recommend' then w_rec
                        when 'save'      then w_sav
                        when 'tried'     then w_tri
                        when 'share'     then w_sha end) as base,
           standing(p.created_at, vi.twins) as st
    from engagements e
    join dishes d   on d.id = e.dish_id and d.status = 'live'
    join profiles p on p.id = e.user_id
    left join voter_independence vi on vi.user_id = e.user_id
    where (p_dish is null or e.dish_id = p_dish)
      and e.kind <> 'pass'
      -- "Tried it, not for me" must never push a dish up.
      and not (e.kind = 'tried' and exists (
        select 1 from engagements x where x.user_id = e.user_id and x.dish_id = e.dish_id and x.kind = 'pass'))
  ),
  per_user as (
    -- Capped first, then weighted by standing, so a new account can't fill the cap.
    select user_id, dish_id, max(st) as st,
      least(cap, sum(base * decay(created_at, hl))) * max(st) as contrib,
      least(cap, coalesce(sum(base) filter (where created_at > now() - interval '7 days'), 0)) * max(st) as contrib_7d
    from act group by user_id, dish_id
  )
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
        from act group by dish_id) k using (dish_id);
end $$;

-- ============ People's Pick ============
alter table taster_stats alter column picks type double precision;
alter table taster_stats add column if not exists expert boolean not null default false;

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
begin
  perform pg_advisory_xact_lock(727002);

  -- 1. Trust (only on a full refresh; a single vote just re-rates its dish).
  --    Each love or "not for me" is a pick. Its credit is how far the people who voted
  --    after it agreed beyond what always voting that way would earn, counted by their
  --    standing; a pick that only throwaway or lockstep accounts followed barely counts.
  if p_dish is null then
  perform refresh_voter_independence();
  delete from taster_stats;
  insert into taster_stats (user_id, category_id, picks, backers, agreed, trust, expert)
  with st as (
    select p.id as user_id, standing(p.created_at, vi.twins) as st
    from profiles p left join voter_independence vi on vi.user_id = p.id
  ),
  picks as (
    select e.user_id, e.dish_id, e.kind, e.created_at, coalesce(d.category_id, -1) as cat
    from engagements e join dishes d on d.id = e.dish_id and d.status = 'live'
    where e.kind in ('recommend', 'pass')
  ),
  later as (
    select pk.user_id, pk.cat, pk.dish_id, pk.kind,
           sum(s.st) as w_later,
           coalesce(sum(s.st) filter (where v.kind = pk.kind), 0) as w_agree,
           count(*) filter (where v.kind = pk.kind) as n_agree
    from picks pk
    join engagements v on v.dish_id = pk.dish_id and v.user_id <> pk.user_id
                      and v.kind in ('recommend', 'pass') and v.created_at > pk.created_at
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

  -- 2. Each dish: weighted loves vs weighted "not for me" (trust x standing x age), pulled
  --    towards the prior when there are few votes, ranked by a cautious lower estimate.
  --    Every live dish gets a row, so a dish nobody voted on sits at the prior.
  delete from dish_rating where p_dish is null or dish_id = p_dish;
  insert into dish_rating (dish_id, loves, passes, w_loves, w_passes, rating, confidence, champion_id, updated_at)
  with votes as (
    select e.dish_id, e.user_id, e.kind, e.created_at,
           coalesce(ts.trust, tall.trust, t_floor) as trust,
           standing(p.created_at, vi.twins) as st
    from engagements e
    join dishes d   on d.id = e.dish_id and d.status = 'live'
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
           coalesce(sum(w) filter (where kind = 'pass'), 0)      as wp
    from (select *, trust * st * decay(created_at, hl) as w from votes) x
    group by dish_id
  ),
  champ as (
    select distinct on (dish_id) dish_id, user_id
    from votes where kind = 'recommend' and st >= ex_s
    order by dish_id, trust * st desc, created_at, user_id
  )
  select d.id, coalesce(a.loves, 0), coalesce(a.passes, 0), coalesce(a.wl, 0), coalesce(a.wp, 0), r.rating,
         greatest(0, r.rating - z * sqrt(r.rating * (1 - r.rating) / (coalesce(a.wl, 0) + coalesce(a.wp, 0) + m))),
         c.user_id, now()
  from dishes d
  left join agg a on a.dish_id = d.id
  cross join lateral (select (coalesce(a.wl, 0) + m * p0) / (coalesce(a.wl, 0) + coalesce(a.wp, 0) + m) as rating) r
  left join champ c on c.dish_id = d.id
  where d.status = 'live' and (p_dish is null or d.id = p_dish);
end $$;

-- ============ Feed ============
-- Lane minimums count people by standing. Fresh = a new photo in the last fresh_days,
-- newest first (the editorial order still leads on launch day, when everything is new).
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
           coalesce(s.score, 0)       as score,
           coalesce(s.score_7d, 0)    as score_7d,
           s.last_engaged_at,
           r.geo,
           cume_dist() over (order by coalesce(s.score, 0) desc) as top_frac,
           (select max(p.created_at) from posts p where p.dish_id = d.id and p.status = 'live') as newest_post
    from dishes d
    join restaurants r on r.id = d.restaurant_id and r.status = 'active'
    left join dish_stats s on s.dish_id = d.id
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
  top_lane as (
    select b.id, row_number() over
             (order by b.score desc, b.last_engaged_at desc nulls last, b.id) as k
    from base b
    where p_tab = 'top' and b.w_endorsers >= cfg('min_endorsers_top')
  ),
  fresh_lane as (
    select b.id, row_number() over
             (order by b.featured_rank nulls last, greatest(b.dish_created, b.newest_post) desc, b.id) as m
    from base b
    where p_tab = 'top'
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
    select b.id, 'nearby', row_number() over (order by b.dist nulls last, b.score desc, b.id)
    from base b where p_tab = 'nearby'
  ),
  deduped as (
    select distinct on (id) id, lane, slot from slotted order by id, slot
  )
  select id, lane from deduped order by slot, id offset p_offset limit p_limit;
$$;

select refresh_dish_stats();
select refresh_people_pick();
