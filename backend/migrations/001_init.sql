-- FoodFeed schema v1 (self-hosted Postgres 16 + PostGIS).
-- Derived from Appendix A of docs/FoodFeed_Android_MVP_Plan.md, with the review fixes:
--   * the database is never exposed to clients; only the API talks to it, so every
--     permission check lives in the API instead of Supabase row-level security
--   * a hidden cover photo falls back to the newest live photo
--   * new accounts' actions are weighted down permanently (not just for 24 hours)
--   * the public "Recommended by N" counts only recommend + tried, not saves
--   * seeded dishes can carry an editorial order for the launch period

create extension if not exists postgis;
create extension if not exists pg_trgm;
create extension if not exists pgcrypto;

-- ============ Config (tune without shipping a new app) ============
create table ranking_config (
  key   text primary key,
  value numeric not null,
  note  text
);
insert into ranking_config (key, value, note) values
  ('w_recommend',             1.0, 'weight of a recommend'),
  ('w_save',                  1.5, 'weight of a save'),
  ('w_tried',                 2.0, 'weight of a tried-it'),
  ('w_share',                 2.5, 'weight of a share'),
  ('person_cap',              3.0, 'max contribution of one person to one dish'),
  ('half_life_days',         45.0, 'decay half-life in days'),
  ('min_endorsers_top',       3.0, 'distinct endorsers needed to enter the Top lane'),
  ('fresh_days',             14.0, 'age window for the fresh lane'),
  ('new_account_hours',      24.0, 'actions made this soon after sign-up count less'),
  ('new_account_factor',      0.5, 'weight multiplier for those actions'),
  ('gem_min_endorsers',       5.0, 'hidden gem: distinct endorsers needed'),
  ('gem_top_fraction',        0.3, 'hidden gem: must be in this top fraction of the city'),
  ('gem_max_posts',          15.0, 'hidden gem: place has fewer live photos than this'),
  ('gem_max_place_endorsers',20.0, 'hidden gem: place has fewer distinct endorsers than this'),
  ('report_hide_threshold',   3.0, 'distinct established reporters that auto-hide a post'),
  ('report_min_account_hours',24.0,'reports from accounts younger than this only go to the queue'),
  ('posts_per_day',          10.0, 'max posts per user per 24 hours');

-- ============ Geography ============
create table cities (
  id        serial primary key,
  slug      text unique not null,
  name      text not null,
  state     text,
  center    geography(point, 4326) not null,
  radius_km int not null default 25,
  is_active boolean not null default false
);

-- ============ People ============
create table profiles (
  id            uuid primary key default gen_random_uuid(),
  email         text unique not null check (email = lower(email)),
  password_hash text not null,
  handle        text unique not null check (handle ~ '^[a-z0-9_]{3,20}$'),
  display_name  text check (char_length(display_name) <= 40),
  home_city_id  int references cities(id),
  role          text not null default 'user' check (role in ('user','creator','admin')),
  is_demo       boolean not null default false,
  created_at    timestamptz not null default now()
);

-- ============ Places ============
create table restaurants (
  id               uuid primary key default gen_random_uuid(),
  city_id          int not null references cities(id),
  name             text not null check (char_length(name) between 2 and 80),
  name_norm        text not null,
  locality         text check (char_length(locality) <= 60),
  address          text,
  geo              geography(point, 4326) not null,
  maps_url         text,
  instagram_handle text,
  price_band       smallint check (price_band between 1 and 4),
  status           text not null default 'active' check (status in ('active','pending','hidden')),
  created_by       uuid references profiles(id) on delete set null,
  created_at       timestamptz not null default now()
);
create index restaurants_geo_idx   on restaurants using gist (geo);
create index restaurants_name_trgm on restaurants using gin (name_norm gin_trgm_ops);
create index restaurants_city_idx  on restaurants (city_id, status);

-- ============ Dish vocabulary ============
create table dish_categories (
  id    serial primary key,
  slug  text unique not null,
  name  text not null,
  emoji text,
  sort  int not null default 0
);
create table dish_aliases (
  id          serial primary key,
  category_id int references dish_categories(id),
  canonical   text not null,
  alias_norm  text not null unique
);
create index dish_aliases_trgm on dish_aliases using gin (alias_norm gin_trgm_ops);

-- ============ Dishes (one dish at one restaurant) ============
create table dishes (
  id            uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references restaurants(id) on delete cascade,
  city_id       int  not null references cities(id),
  category_id   int references dish_categories(id),
  name          text not null check (char_length(name) between 2 and 60),
  name_norm     text not null,
  description   text,
  price_inr     int check (price_inr is null or price_inr between 1 and 20000),
  is_veg        boolean,
  cover_post_id uuid,
  featured_rank int,           -- editorial order used before real endorsements exist
  status        text not null default 'live' check (status in ('live','pending','hidden')),
  created_by    uuid references profiles(id) on delete set null,
  created_at    timestamptz not null default now(),
  unique (restaurant_id, name_norm)
);
create index dishes_city_idx  on dishes (city_id, status, category_id);
create index dishes_name_trgm on dishes using gin (name_norm gin_trgm_ops);

-- ============ Posts (photos of a dish) ============
create table posts (
  id                uuid primary key default gen_random_uuid(),
  dish_id           uuid not null references dishes(id) on delete cascade,
  author_id         uuid references profiles(id) on delete cascade,
  caption           text check (char_length(caption) <= 280),
  image_path        text not null,
  thumb_path        text not null,
  placeholder_color text,
  width             int,
  height            int,
  source            text not null default 'user' check (source in ('seed','user','creator')),
  photo_credit      text,
  is_sponsored      boolean not null default false,
  sponsor_note      text,
  instagram_url     text,
  idempotency_key   text,
  status            text not null default 'live' check (status in ('live','pending','hidden')),
  created_at        timestamptz not null default now(),
  unique (author_id, idempotency_key)
);
create index posts_dish_idx   on posts (dish_id, status, created_at desc);
create index posts_author_idx on posts (author_id, created_at desc);

alter table dishes add constraint dishes_cover_fk
  foreign key (cover_post_id) references posts(id) on delete set null;

-- ============ Engagement ============
create table engagements (
  user_id    uuid not null references profiles(id) on delete cascade,
  dish_id    uuid not null references dishes(id)   on delete cascade,
  kind       text not null check (kind in ('recommend','save','tried','share')),
  created_at timestamptz not null default now(),
  primary key (user_id, dish_id, kind)
);
create index engagements_dish_idx on engagements (dish_id, created_at desc);
create index engagements_user_idx on engagements (user_id, kind, created_at desc);

-- ============ Impressions (for a later rate-based score) ============
create table dish_views (
  id         bigint generated always as identity primary key,
  dish_id    uuid not null references dishes(id) on delete cascade,
  user_id    uuid references profiles(id) on delete set null,
  device_id  text check (char_length(device_id) <= 64),
  viewed_at  timestamptz not null default now()
);
create index dish_views_dish_idx on dish_views (dish_id, viewed_at desc);

-- ============ Reports and blocks ============
create table reports (
  id          bigint generated always as identity primary key,
  reporter_id uuid not null references profiles(id) on delete cascade,
  target_type text not null check (target_type in ('post','dish','user')),
  target_id   uuid not null,
  reason      text not null check (reason in
                ('wrong_place','not_food','inappropriate','spam','other')),
  note        text check (char_length(note) <= 500),
  status      text not null default 'open' check (status in ('open','resolved')),
  created_at  timestamptz not null default now(),
  unique (reporter_id, target_type, target_id)
);
create table blocks (
  blocker_id uuid not null references profiles(id) on delete cascade,
  blocked_id uuid not null references profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);

-- ============ Derived stats ============
create table dish_stats (
  dish_id         uuid primary key references dishes(id) on delete cascade,
  endorsers       int  not null default 0,   -- distinct people with any action (ranking)
  recommenders    int  not null default 0,   -- distinct people who recommended or tried (shown)
  recommends      int  not null default 0,
  saves           int  not null default 0,
  shares          int  not null default 0,
  tried           int  not null default 0,
  score           double precision not null default 0,
  score_7d        double precision not null default 0,
  last_engaged_at timestamptz,
  updated_at      timestamptz not null default now()
);

-- ============ Helpers ============
create or replace function norm_name(t text) returns text
language sql immutable as $$
  select regexp_replace(
           trim(regexp_replace(lower(coalesce(t,'')), '[^[:alnum:] ]+', ' ', 'g')),
           '\s+', ' ', 'g')
$$;

create or replace function cfg(k text) returns numeric
language sql stable as $$ select value from ranking_config where key = k $$;

-- ============ Ranking refresh ============
-- p_dish null = recompute every dish; otherwise just that one (called after each action).
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
    where p_dish is null or e.dish_id = p_dish
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
               count(distinct user_id) filter (where kind in ('recommend','tried')) as recommenders,
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
             where d3.restaurant_id = r.id)                                    as place_endorsers
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
