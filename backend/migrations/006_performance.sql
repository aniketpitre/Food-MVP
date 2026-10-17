-- Faster at scale, same results.

-- A vote re-rates just its own dish straight away; everyone's trust (and so every dish's
-- rating) is still rebuilt on the ranking timer, as before. Rebuilding everything on each
-- tap made every like wait behind every other like.
drop function if exists refresh_people_pick();
create or replace function refresh_people_pick(p_dish uuid default null) returns void
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
  --    Only on a full refresh (the ranking timer); a single vote just re-rates its dish.
  if p_dish is null then
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

  end if;

  -- 2. Each dish: weighted loves vs weighted "not for me", pulled towards the prior when
  --    there are few votes, then ranked by a cautious lower estimate.
  delete from dish_rating where p_dish is null or dish_id = p_dish;
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
    where e.kind in ('recommend', 'pass') and (p_dish is null or e.dish_id = p_dish)
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

-- Polled every 30 s by every open app: new reactions and unread Activity since a moment.
create index if not exists message_reactions_created on message_reactions (created_at);
create index if not exists message_reactions_user on message_reactions (user_id);
create index if not exists notifications_unread on notifications (user_id, created_at) where read_at is null;

-- Foreign keys that are looked up when a story expires, a message is unsent, a post or an
-- account is deleted, and when checking whether someone has replied in a chat.
create index if not exists messages_story on messages (story_id) where story_id is not null;
create index if not exists messages_reply on messages (reply_to) where reply_to is not null;
create index if not exists messages_post on messages (post_id) where post_id is not null;
create index if not exists messages_conv_sender on messages (conversation_id, sender_id);
create index if not exists messages_sender on messages (sender_id);

-- Home's "fresh" section: the newest live dishes in a city.
create index if not exists dishes_city_fresh on dishes (city_id, created_at desc) where status = 'live';

-- The feed: the per-place counts behind "Hidden gems" are worked out only for that tab.
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
