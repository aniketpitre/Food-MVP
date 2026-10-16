-- Social profiles, follows, notifications, an honest "not for me" vote, real places
-- from OpenStreetMap and the People's Pick rating (see docs/PEOPLES_PICK.md).

-- ============ Profiles ============
alter table profiles add column bio text check (char_length(bio) <= 160);
alter table profiles add column avatar_path text;
alter table profiles add column comment_policy text not null default 'everyone'
  check (comment_policy in ('everyone', 'followers', 'off'));
create index profiles_handle_trgm on profiles using gin (handle gin_trgm_ops);

create table follows (
  follower_id uuid not null references profiles(id) on delete cascade,
  followee_id uuid not null references profiles(id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);
create index follows_followee_idx on follows (followee_id, created_at desc);

-- ============ Notifications (the Activity tab) ============
create table notifications (
  id         bigint generated always as identity primary key,
  user_id    uuid not null references profiles(id) on delete cascade,
  actor_id   uuid references profiles(id) on delete cascade,
  kind       text not null check (kind in ('follow', 'comment', 'reply', 'comment_like', 'mention', 'recommend', 'agree')),
  dish_id    uuid references dishes(id) on delete cascade,
  post_id    uuid references posts(id) on delete cascade,
  comment_id uuid references comments(id) on delete cascade,
  read_at    timestamptz,
  created_at timestamptz not null default now()
);
create index notifications_user_idx on notifications (user_id, created_at desc);
-- Toggling a like or follow on and off never notifies twice.
create unique index notifications_once on notifications (
  user_id, coalesce(actor_id, '00000000-0000-0000-0000-000000000000'), kind,
  coalesce(dish_id, '00000000-0000-0000-0000-000000000000'),
  coalesce(comment_id, '00000000-0000-0000-0000-000000000000'));

-- ============ Votes ============
-- 'pass' = "tried it, not for me". Mutually exclusive with 'recommend' (the API enforces it).
alter table engagements drop constraint engagements_kind_check;
alter table engagements add constraint engagements_kind_check
  check (kind in ('recommend', 'save', 'tried', 'share', 'pass'));

-- ============ Real places ============
alter table restaurants add column source text not null default 'user' check (source in ('user', 'seed', 'osm'));
alter table restaurants add column osm_id text unique;
alter table restaurants add column cuisine text;
alter table restaurants add column opening_hours text;

-- ============ People's Pick ============
insert into ranking_config (key, value, note) values
  ('pp_prior',          0.75, 'People''s Pick: approval a dish starts from before votes arrive'),
  ('pp_prior_weight',   3.0,  'People''s Pick: how many votes that starting point is worth'),
  ('pp_z',              1.0,  'People''s Pick: caution for few votes (higher = more votes needed to rank high)'),
  ('pp_half_life_days', 180,  'People''s Pick: votes fade over this many days'),
  ('trust_floor',       0.6,  'weight of a voter whose picks nobody backed'),
  ('trust_span',        0.8,  'extra weight for a voter whose picks everyone backed (max = floor + span)'),
  ('expert_min_picks',  3.0,  'picks in a category before someone can be called its expert'),
  ('expert_min_trust',  1.1,  'trust needed to be called an expert');

-- Per person and dish type (category_id 0 = all dishes, -1 = dishes without a type).
create table taster_stats (
  user_id     uuid not null references profiles(id) on delete cascade,
  category_id int  not null,
  picks       int  not null,      -- dishes they loved that others voted on afterwards
  backers     int  not null,      -- people who loved those dishes after them
  agreed      double precision not null,  -- sum over picks of the later approval share
  trust       double precision not null,  -- vote weight: floor + span * (agreed + 1) / (picks + 2)
  primary key (user_id, category_id)
);

create table dish_rating (
  dish_id     uuid primary key references dishes(id) on delete cascade,
  loves       int not null default 0,
  passes      int not null default 0,
  w_loves     double precision not null default 0,
  w_passes    double precision not null default 0,
  rating      double precision not null default 0,  -- smoothed weighted approval, 0..1
  confidence  double precision not null default 0,  -- rating minus an uncertainty margin; used to rank
  champion_id uuid references profiles(id) on delete set null,
  updated_at  timestamptz not null default now()
);

-- Recomputes taster trust and every dish's People's Pick rating. Cheap at city scale;
-- runs after each vote and on the ranking timer.
create or replace function refresh_people_pick() returns void
language plpgsql as $$
declare
  t_floor numeric := cfg('trust_floor');
  t_span  numeric := cfg('trust_span');
  p0      numeric := cfg('pp_prior');
  m       numeric := cfg('pp_prior_weight');
  z       numeric := cfg('pp_z');
  hl      numeric := cfg('pp_half_life_days');
  new_h   numeric := cfg('new_account_hours');
  new_f   numeric := cfg('new_account_factor');
begin
  perform pg_advisory_xact_lock(727002);

  -- 1. Trust: how often a person's loves were backed by the people who voted after them.
  delete from taster_stats;
  insert into taster_stats (user_id, category_id, picks, backers, agreed, trust)
  with loves as (
    select e.user_id, e.dish_id, e.created_at, coalesce(d.category_id, -1) as cat
    from engagements e join dishes d on d.id = e.dish_id and d.status = 'live'
    where e.kind = 'recommend'
  ),
  later as (
    select l.user_id, l.cat, l.dish_id,
           count(*) filter (where v.kind = 'recommend') as later_loves,
           count(*) filter (where v.kind = 'pass')      as later_passes
    from loves l
    join engagements v on v.dish_id = l.dish_id and v.user_id <> l.user_id
                      and v.kind in ('recommend', 'pass') and v.created_at > l.created_at
    group by l.user_id, l.cat, l.dish_id
  ),
  per_cat as (
    select user_id, cat, count(*)::int as picks, sum(later_loves)::int as backers,
           sum(later_loves::float8 / (later_loves + later_passes)) as agreed
    from later group by user_id, cat
  ),
  overall as (
    select user_id, 0 as cat, sum(picks)::int as picks, sum(backers)::int as backers, sum(agreed) as agreed
    from per_cat group by user_id
  )
  select user_id, cat, picks, backers, agreed,
         t_floor + t_span * (agreed + 1) / (picks + 2)
  from (select * from per_cat union all select * from overall) s;

  -- 2. Each dish: weighted loves vs weighted "not for me", pulled towards the prior when
  --    there are few votes, then ranked by a cautious lower estimate.
  delete from dish_rating;
  insert into dish_rating (dish_id, loves, passes, w_loves, w_passes, rating, confidence, champion_id, updated_at)
  with votes as (
    select e.dish_id, e.user_id, e.kind, e.created_at,
           coalesce(ts.trust, tall.trust, t_floor + t_span * 0.5)
           * (case when e.created_at < p.created_at + make_interval(hours => new_h::int) then new_f else 1 end)
           * power(0.5, extract(epoch from (now() - e.created_at)) / 86400.0 / hl) as w,
           coalesce(ts.trust, tall.trust, t_floor + t_span * 0.5) as trust
    from engagements e
    join dishes d   on d.id = e.dish_id and d.status = 'live'
    join profiles p on p.id = e.user_id
    left join taster_stats ts   on ts.user_id = e.user_id and ts.category_id = coalesce(d.category_id, -1)
    left join taster_stats tall on tall.user_id = e.user_id and tall.category_id = 0
    where e.kind in ('recommend', 'pass')
  ),
  agg as (
    select dish_id,
           count(*) filter (where kind = 'recommend')::int as loves,
           count(*) filter (where kind = 'pass')::int      as passes,
           coalesce(sum(w) filter (where kind = 'recommend'), 0) as wl,
           coalesce(sum(w) filter (where kind = 'pass'), 0)      as wp
    from votes group by dish_id
  ),
  champ as (
    select distinct on (dish_id) dish_id, user_id
    from votes where kind = 'recommend'
    order by dish_id, trust desc, created_at
  )
  select a.dish_id, a.loves, a.passes, a.wl, a.wp, r.rating,
         greatest(0, r.rating - z * sqrt(r.rating * (1 - r.rating) / (a.wl + a.wp + m))),
         c.user_id, now()
  from agg a
  cross join lateral (select (a.wl + m * p0) / (a.wl + a.wp + m) as rating) r
  left join champ c using (dish_id);
end $$;

create or replace function refresh_dish_stats(p_dish uuid default null) returns void
language plpgsql as $$
declare
  w_rec numeric := cfg('w_recommend');
  w_sav numeric := cfg('w_save');
  w_tri numeric := cfg('w_tried');
  w_sha numeric := cfg('w_share');
  cap   numeric := cfg('person_cap');
  hl    numeric := cfg('half_life_days');
  new_h numeric := cfg('new_account_hours');
  new_f numeric := cfg('new_account_factor');
begin
  perform pg_advisory_xact_lock(727001);

  delete from dish_stats where p_dish is null or dish_id = p_dish;

  insert into dish_stats
    (dish_id, endorsers, recommenders, recommends, saves, shares, tried,
     score, score_7d, last_engaged_at, updated_at)
  with act as (
    select e.user_id, e.dish_id, e.kind, e.created_at,
           (case e.kind when 'recommend' then w_rec
                        when 'save'      then w_sav
                        when 'tried'     then w_tri
                        when 'share'     then w_sha end)
           * (case when e.created_at < p.created_at + make_interval(hours => new_h::int)
                   then new_f else 1 end) as base
    from engagements e
    join dishes d   on d.id = e.dish_id and d.status = 'live'
    join profiles p on p.id = e.user_id
    where (p_dish is null or e.dish_id = p_dish)
      and e.kind <> 'pass'
      -- "Tried it, not for me" must never push a dish up.
      and not (e.kind = 'tried' and exists (
        select 1 from engagements x where x.user_id = e.user_id and x.dish_id = e.dish_id and x.kind = 'pass'))
  ),
  per_user as (
    select user_id, dish_id,
      least(cap, sum(base * power(0.5,
          extract(epoch from (now() - created_at)) / 86400.0 / hl)))       as contrib,
      least(cap, coalesce(sum(base) filter
          (where created_at > now() - interval '7 days'), 0))              as contrib_7d
    from act group by user_id, dish_id
  )
  select a.dish_id, a.endorsers, k.recommenders, k.recommends, k.saves, k.shares, k.tried,
         a.score, a.score_7d, k.last_engaged_at, now()
  from (select dish_id, count(*) as endorsers,
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


-- ============ Feed ============
-- Returns ordered dish ids for one page; the API then loads card fields for them.
-- Top tab slot rule: every 5th slot is a fresh-lane dish, the rest are top-lane dishes.
--   top rank k   -> slot k + (k-1)/4      fresh rank m -> slot 5*m
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
           coalesce(s.endorsers, 0) as endorsers,
           coalesce(s.score, 0)     as score,
           coalesce(s.score_7d, 0)  as score_7d,
           s.last_engaged_at,
           r.geo,
           cume_dist() over (order by coalesce(s.score, 0) desc) as top_frac
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
           (select count(*) from posts p join dishes d2 on d2.id = p.dish_id
             where d2.restaurant_id = r.id and p.status = 'live')              as live_posts,
           (select count(distinct e.user_id) from engagements e join dishes d3 on d3.id = e.dish_id
             where d3.restaurant_id = r.id
               -- people who said "not for me" are not fans of the place
               and not exists (select 1 from engagements x
                               where x.user_id = e.user_id and x.dish_id = e.dish_id and x.kind = 'pass')) as place_endorsers
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
    where p_tab = 'top' and b.endorsers >= cfg('min_endorsers_top')
  ),
  fresh_lane as (
    select b.id, row_number() over
             (order by b.featured_rank nulls last, md5(b.id::text || coalesce(p_seed, ''))) as m
    from base b
    where p_tab = 'top'
      and (b.endorsers < cfg('min_endorsers_top')
           or b.dish_created > now() - make_interval(days => cfg('fresh_days')::int))
  ),
  slotted as (
    select id, 'top'::text as lane, k + (k - 1) / 4 as slot from top_lane
    union all
    select id, 'fresh', 5 * m from fresh_lane
    union all
    select b.id, 'trending', row_number() over (order by b.score_7d desc, b.score desc, b.id)
    from base b where p_tab = 'trending' and b.score_7d > 0
    union all
    select b.id, 'gem', row_number() over (order by b.score desc, b.id)
    from base b
    where p_tab = 'gems'
      and b.endorsers  >= cfg('gem_min_endorsers')
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

update restaurants set source = 'seed' where created_by is null and source = 'user';
select refresh_dish_stats();
select refresh_people_pick();
