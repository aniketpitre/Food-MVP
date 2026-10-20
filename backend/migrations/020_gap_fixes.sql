-- The algorithm audit's gaps, fixed (docs/PEOPLES_PICK.md describes the result):
--
--  Launch with few votes
--   * A young city (fewer than young_city_votes loves and "not for me" in all) shows in Top
--     every dish loved by at least half a full-standing person's worth of accounts, best
--     first, instead of an empty Top until 3 full-standing people agree; Trending and Hidden
--     gems relax the same way. w_voters fades like the
--     rating, so a dish whose voters were all years ago leaves Top in an established city.
--   * Pending places go live by themselves once two people post there, or after a few days
--     with a photo and no similar live place within 150 m (auto_approve_places, on the timer).
--   * For You falls back to the city's best dishes, ranks taste matches by their evidence
--     (answers alone can't outrank people who voted like you), names only people who loved
--     the dish, honours blocks both ways and keeps meat away from vegetarians and egg-eaters
--     (dish_fits_diet).
--
--  Cheating
--   * Lockstep partners: a 24 h window and 3 shared votes; only the account whose votes are
--     mostly mirrored loses standing (a copycat can't sink the expert it copies), and
--     agreement inside a pair still never builds trust.
--   * A pick needs pick_full_backers people (by standing) after it to count fully.
--   * Undone or flipped loves and "not for me" stay in your pick history (pick_history), so
--     trust can't be polished by taking back the calls that went wrong; re-adding a vote
--     within revote_keep_days keeps its first date.
--   * Only votes at approved places count, for trust and for ratings.
--   * Owners: everyone who added a place, or a place merged into it, is a claimant whose
--     votes there count for nothing (place_claimants survives merges).
--   * One love or "not for me" per person per dish, enforced by the database.
--   * The verified-visit weight needs full standing; champions and experts need more than
--     the minimum standing (a lockstep pair sits exactly at it).
--
--  Correctness
--   * Fresh ignores photos from people the viewer blocked; the daily Top snapshot uses the
--     India date; Overture's own duplicate listings (same name within 30 m, no dishes) are
--     hidden, keeping the first.

-- ============ Settings ============
insert into ranking_config (key, value, note) values
  ('young_city_votes',          200, 'below this many loves and "not for me" in a city, Top, Trending and Hidden gems need only half a full-standing person'),
  ('pick_full_backers',           3, 'people (by standing) voting after a pick for it to count fully'),
  ('revote_keep_days',           30, 're-adding a vote within this many days keeps its first date'),
  ('place_auto_approve_posters',  2, 'a pending place goes live once this many people posted there'),
  ('place_auto_approve_days',     3, 'or after this many days with a photo and no similar live place nearby')
on conflict (key) do nothing;
update ranking_config set value = 24 where key = 'twin_window_hours' and value = 2;

-- ============ Owners ============
create table if not exists place_claimants (
  restaurant_id uuid not null references restaurants(id) on delete cascade,
  user_id       uuid not null references profiles(id) on delete cascade,
  primary key (restaurant_id, user_id)
);
create index if not exists place_claimants_user on place_claimants (user_id);
insert into place_claimants (restaurant_id, user_id)
select id, created_by from restaurants where created_by is not null
on conflict do nothing;
-- Whoever added a place merged into another one is a claimant of that one too.
insert into place_claimants (restaurant_id, user_id)
select pm.into_id, rf.created_by from place_merges pm join restaurants rf on rf.id = pm.from_id
where rf.created_by is not null
on conflict do nothing;

create or replace function place_claimant_touch() returns trigger language plpgsql as $$
begin
  if new.created_by is not null then
    insert into place_claimants (restaurant_id, user_id) values (new.id, new.created_by) on conflict do nothing;
  end if;
  return new;
end $$;
drop trigger if exists restaurants_claimant on restaurants;
create trigger restaurants_claimant after insert on restaurants for each row execute function place_claimant_touch();

-- ============ One verdict per person per dish ============
-- Keep the newer of a love and a "not for me" left by the same person (a race could store both).
delete from engagements e
using engagements o
where e.user_id = o.user_id and e.dish_id = o.dish_id
  and e.kind in ('recommend', 'pass') and o.kind in ('recommend', 'pass') and e.kind <> o.kind
  and (e.created_at < o.created_at or (e.created_at = o.created_at and e.kind = 'pass'));
create unique index if not exists engagements_one_verdict on engagements (user_id, dish_id)
  where kind in ('recommend', 'pass');

-- ============ Pick history ============
create table if not exists pick_history (
  user_id    uuid not null references profiles(id) on delete cascade,
  dish_id    uuid not null references dishes(id) on delete cascade,
  kind       text not null check (kind in ('recommend', 'pass')),
  created_at timestamptz not null,
  removed_at timestamptz not null default now(),
  primary key (user_id, dish_id, kind)
);

create or replace function pick_history_keep() returns trigger language plpgsql as $$
begin
  -- Not when the account or the dish itself is being deleted (they take their votes along),
  -- nor when an admin merges dishes (the votes move, they aren't taken back).
  insert into pick_history (user_id, dish_id, kind, created_at, removed_at)
  select old.user_id, old.dish_id, old.kind, old.created_at, now()
  where coalesce(current_setting('worthabite.moving_votes', true), '') <> 'on'
    and exists (select 1 from profiles where id = old.user_id)
    and exists (select 1 from dishes where id = old.dish_id)
  on conflict (user_id, dish_id, kind) do update
    set created_at = least(pick_history.created_at, excluded.created_at), removed_at = now();
  return old;
end $$;

-- Putting a vote back: it is current again (not counted twice) and, within the window,
-- keeps its first date, so toggling can't keep a vote looking new.
create or replace function pick_history_restore() returns trigger language plpgsql as $$
declare first_at timestamptz;
begin
  delete from pick_history h
  where h.user_id = new.user_id and h.dish_id = new.dish_id and h.kind = new.kind
  returning case when h.removed_at > now() - make_interval(days => greatest(cfg('revote_keep_days'), 0)::int)
                 then h.created_at end into first_at;
  if first_at is not null then new.created_at := first_at; end if;
  return new;
end $$;

drop trigger if exists engagements_pick_keep on engagements;
create trigger engagements_pick_keep after delete on engagements
  for each row when (old.kind in ('recommend', 'pass')) execute function pick_history_keep();
drop trigger if exists engagements_pick_restore on engagements;
create trigger engagements_pick_restore before insert on engagements
  for each row when (new.kind in ('recommend', 'pass')) execute function pick_history_restore();

-- ============ Diet ============
-- Whether a dish suits a diet ('veg', 'egg', 'nonveg'). Marked veg is veg; a dish whose name
-- says meat or fish never is; egg-eaters also get egg dishes without meat; an unmarked dish
-- of a type that is often non-veg (biryani, kebabs, rolls...) needs a veg word in its name.
create or replace function dish_fits_diet(p_diet text, p_is_veg boolean, p_cat int, p_name text) returns boolean
language sql stable as $$
  with f as (
    select coalesce((select slug from dish_categories where id = p_cat), '') as slug,
           coalesce(p_name, '') ~ '(^| )(chicken|mutton|fish|prawn|shrimp|lobster|squid|crab|kolambi|bombil|surmai|pomfret|bangda|rawas|tuna|kheema|keema|lamb|beef|pork|bacon|salami|sausage|pepperoni|tangdi|murg|gosht|haleem|nihari|galouti|bheja|kaleji|paya|nalli|mandi|kalimiri chicken)' as meat,
           coalesce(p_name, '') ~ '(^| )(kebab|kabab|seekh|shawarma|lollipop|wings|tikka|biryani|biriyani|pulao|roll|momos?|burger|sandwich|soup|noodles|fried rice|thali)' as ambiguous,
           coalesce(p_name, '') ~ '(^| )(veg|vegetable|paneer|aloo|gobi|mushroom|corn|soya|dal|chole|rajma|cheese|potato|kaju|subz|sabzi|chana|bhindi|palak|methi|hara bhara|tofu|jackfruit|kathal|matar)' as vegword,
           coalesce(p_name, '') ~ '(^| )(egg|eggs|anda|ande|omelette|omelet|omlette|bhurji)' as egg
  )
  select case
    when p_diet is null or p_diet = 'nonveg' then true
    when p_is_veg is true then true
    when f.meat then false
    when p_diet = 'egg' and f.egg then f.slug not in ('non-veg-curry', 'seafood')
    when p_is_veg is false then false
    when f.egg then false
    when f.slug in ('non-veg-curry', 'seafood', 'eggs') then false
    when f.ambiguous or f.slug in ('biryani', 'tandoor', 'rolls', 'momos', 'chinese', 'burgers', 'asian',
                                   'middle-eastern', 'continental', 'mexican', 'soups') then f.vegword
    else true
  end
  from f
$$;

-- ============ Lockstep ============
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
  create temporary table if not exists tmp_pairs (u uuid, w uuid, c bigint, nu bigint, nw bigint) on commit drop;
  truncate tmp_pairs;
  insert into tmp_pairs
  with v as (
    select user_id, dish_id, kind, created_at from engagements where kind in ('recommend', 'pass')
  ),
  n as (select user_id, count(*) as n from v group by user_id),
  pairs as (
    select a.user_id as u, b.user_id as w, count(distinct a.dish_id) as c
    from v a join v b on b.dish_id = a.dish_id and b.kind = a.kind and b.user_id <> a.user_id
                     and b.created_at between a.created_at - make_interval(secs => win * 3600)
                                          and a.created_at + make_interval(secs => win * 3600)
    group by a.user_id, b.user_id
  )
  select p.u, p.w, p.c, na.n, nb.n
  from pairs p join n na on na.user_id = p.u join n nb on nb.user_id = p.w
  where p.c >= minv and p.c >= ovl * least(na.n, nb.n);
  -- Pairs: agreement between them never builds either one's trust.
  insert into voter_twins (user_id, twin_id) select u, w from tmp_pairs;
  -- Standing: only the account whose own votes are mostly mirrored by the other loses
  -- weight. Two puppets mirror each other and both lose it; someone copying an expert's
  -- few picks loses it, the expert doesn't.
  insert into voter_independence (user_id, twins)
  select u, count(*)::int from tmp_pairs where c >= ovl * nu group by u;
end $$;

-- ============ Feed points (owners left out) ============
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
  if p_dish is null then
    if not pg_try_advisory_xact_lock(727001) then return; end if;
  else
    perform pg_advisory_xact_lock(727001, hashtext(p_dish::text));
  end if;

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
      -- Whoever added the place (or a place merged into it) doesn't vote for its dishes.
      and e.user_id is distinct from r.created_by
      and not exists (select 1 from place_claimants pc where pc.restaurant_id = r.id and pc.user_id = e.user_id)
      and (e.kind <> 'tried' or exists (
        select 1 from engagements x where x.user_id = e.user_id and x.dish_id = e.dish_id and x.kind = 'recommend'))
  ),
  per_user as (
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

-- ============ Trust and ratings ============
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
  full_backers numeric := greatest(cfg('pick_full_backers'), 1);
begin
  if p_dish is null then
    if not pg_try_advisory_xact_lock(727002) then return; end if;
  else
    perform pg_advisory_xact_lock(727002, hashtext(p_dish::text));
  end if;

  -- 1. Trust (full refresh only). Every love or "not for me" ever made is a pick, including
  --    ones later taken back or flipped (pick_history), at approved places only, never at a
  --    place you claim. A pick counts fully once pick_full_backers people (by standing)
  --    voted after it; its credit is how far they agreed beyond always voting that way.
  if p_dish is null then
  perform refresh_voter_independence();
  delete from taster_stats;
  insert into taster_stats (user_id, category_id, picks, backers, agreed, trust, expert)
  with st as (
    select p.id as user_id, standing(p.created_at, vi.twins) as st
    from profiles p left join voter_independence vi on vi.user_id = p.id
  ),
  allv as (
    select user_id, dish_id, kind, created_at from engagements where kind in ('recommend', 'pass')
    union all
    select user_id, dish_id, kind, created_at from pick_history
  ),
  v as (
    select e.user_id, e.dish_id, e.kind, e.created_at, coalesce(d.category_id, -1) as cat, s.st
    from allv e
    join dishes d on d.id = e.dish_id and d.status = 'live'
    join restaurants r on r.id = d.restaurant_id and r.status = 'active'
    join st s on s.user_id = e.user_id
    where e.user_id is distinct from r.created_by
      and not exists (select 1 from place_claimants pc where pc.restaurant_id = r.id and pc.user_id = e.user_id)
  ),
  w as (
    select v.*,
           sum(st) over dish_all - sum(st) over dish_upto                       as w_after,
           count(*) over dish_all - count(*) over dish_upto                     as n_after,
           sum(st) over kind_all - sum(st) over kind_upto                       as w_agree_after,
           count(*) over kind_all - count(*) over kind_upto                     as n_agree_after
    from v
    window dish_all  as (partition by dish_id),
           dish_upto as (partition by dish_id order by created_at range between unbounded preceding and current row),
           kind_all  as (partition by dish_id, kind),
           kind_upto as (partition by dish_id, kind order by created_at range between unbounded preceding and current row)
  ),
  tw as (
    select pk.user_id, pk.dish_id, pk.kind, sum(v2.st) as w, count(*) as n,
           coalesce(sum(v2.st) filter (where v2.kind = pk.kind), 0) as wa,
           count(*) filter (where v2.kind = pk.kind) as na
    from v pk
    join voter_twins t on t.user_id = pk.user_id
    join v v2 on v2.dish_id = pk.dish_id and v2.user_id = t.twin_id and v2.created_at > pk.created_at
    group by pk.user_id, pk.dish_id, pk.kind
  ),
  later as (
    select w.user_id, w.cat, w.dish_id, w.kind,
           greatest(w.w_after - coalesce(tw.w, 0), 0) as w_later,
           greatest(w.w_agree_after - coalesce(tw.wa, 0), 0) as w_agree,
           (w.n_agree_after - coalesce(tw.na, 0))::int as n_agree
    from w left join tw on tw.user_id = w.user_id and tw.dish_id = w.dish_id and tw.kind = w.kind
    where w.n_after - coalesce(tw.n, 0) > 0
  ),
  per_pick as (
    select user_id, cat, n_agree,
           least(1.0, w_later / full_backers) as weight,
           least(1.0, w_later / full_backers) * greatest(0.0,
             (least(1.0, w_agree / nullif(w_later, 0)) - (case kind when 'recommend' then p0 else 1 - p0 end))
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
         s.picks >= ex_p and tr.trust >= ex_t and coalesce(st.st, 0) > ex_s
  from (select * from per_cat union all select * from overall) s
  cross join lateral (select (t_floor + t_span * s.agreed / (s.picks + k))::float8 as trust) tr
  left join st on st.user_id = s.user_id;
  end if;

  -- 2. Each dish's rating, from current votes at approved places (claimants left out).
  --    The verified-visit weight needs full standing. w_voters fades like the votes.
  with votes as (
    select e.dish_id, e.user_id, e.kind, e.created_at,
           coalesce(ts.trust, tall.trust, t_floor) as trust,
           standing(p.created_at, vi.twins) as st
    from engagements e
    join dishes d   on d.id = e.dish_id and d.status = 'live'
    join restaurants r on r.id = d.restaurant_id and r.status = 'active' and e.user_id is distinct from r.created_by
    join profiles p on p.id = e.user_id
    left join voter_independence vi on vi.user_id = e.user_id
    left join taster_stats ts   on ts.user_id = e.user_id and ts.category_id = coalesce(d.category_id, -1)
    left join taster_stats tall on tall.user_id = e.user_id and tall.category_id = 0
    where e.kind in ('recommend', 'pass') and (p_dish is null or e.dish_id = p_dish)
      and not exists (select 1 from place_claimants pc where pc.restaurant_id = r.id and pc.user_id = e.user_id)
  ),
  weighed as (
    select v.*,
           (case when v.st >= 0.999 and exists (
                   select 1 from posts vp where vp.dish_id = v.dish_id and vp.author_id = v.user_id
                     and vp.status = 'live' and vp.visit = 'verified')
                 then visit_w else 1 end) as vw,
           decay(v.created_at, hl) as fade
    from votes v
  ),
  agg as (
    select dish_id,
           count(*) filter (where kind = 'recommend')::int as loves,
           count(*) filter (where kind = 'pass')::int      as passes,
           coalesce(sum(trust * st * vw * fade) filter (where kind = 'recommend'), 0) as wl,
           coalesce(sum(trust * st * vw * fade) filter (where kind = 'pass'), 0)      as wp,
           coalesce(sum(st * fade), 0)::float8                                         as wv
    from weighed group by dish_id
  ),
  champ as (
    select distinct on (dish_id) dish_id, user_id
    from votes where kind = 'recommend' and st > ex_s
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
  delete from dish_rating x
  where (p_dish is null or x.dish_id = p_dish)
    and not exists (select 1 from up where up.dish_id = x.dish_id);
end $$;

-- Rebuilds everything now, waiting for a rebuild already running instead of skipping
-- (after removing the demo, so no stale scores are left until the next timer).
create or replace function refresh_rankings_now() returns void
language plpgsql as $$
begin
  perform pg_advisory_xact_lock(727001);
  perform pg_advisory_xact_lock(727002);
  perform refresh_dish_stats();
  perform refresh_people_pick();
end $$;

-- ============ Young cities ============
create or replace function city_is_young(p_city int) returns boolean
language sql stable as $$
  select (select count(*) from engagements e join dishes d on d.id = e.dish_id
          where d.city_id = p_city and e.kind in ('recommend', 'pass')) < cfg('young_city_votes')
$$;

-- ============ Feed ============
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
  with young as (select city_is_young(p_city) as y),
  city_dishes as (
    select d.id, d.restaurant_id, d.category_id, d.created_at as dish_created, d.featured_rank,
           coalesce(s.endorsers, 0)   as endorsers,
           coalesce(s.w_endorsers, 0) as w_endorsers,
           coalesce(dr.w_voters, 0)   as w_voters,
           coalesce(dr.w_loves, 0)    as w_loves,
           coalesce(s.score, 0)       as score,
           coalesce(s.score_7d, 0)    as score_7d,
           coalesce(dr.confidence, 0) as confidence,
           s.last_engaged_at,
           r.geo,
           cume_dist() over (order by coalesce(s.score, 0) desc) as top_frac,
           -- Fresh follows photos from diners (not the place's own account, nor anyone the
           -- viewer blocked).
           (select max(p.created_at) from posts p
             where p.dish_id = d.id and p.status = 'live'
               and (r.created_by is null or p.author_id is distinct from r.created_by)
               and (p_user is null or not exists
                    (select 1 from blocks b where b.blocker_id = p_user and b.blocked_id = p.author_id))) as newest_post
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
           case when p_tab = 'gems' then
           (select count(*) from posts p join dishes d2 on d2.id = p.dish_id
             where d2.restaurant_id = r.id and p.status = 'live') end              as live_posts,
           case when p_tab = 'gems' then
           (select count(distinct e.user_id) from engagements e join dishes d3 on d3.id = e.dish_id
             where d3.restaurant_id = r.id
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
  -- Top: enough people who loved it or said "not for me" (by standing, fading with age),
  -- or, while the city is young, every dish people loved (at least half a full-standing
  -- person's worth, so a few accounts made a minute ago can't), best first.
  top_ranked as (
    select b.id, b.restaurant_id, row_number() over
             (order by b.confidence desc, b.score desc, b.last_engaged_at desc nulls last, b.id) as k0
    from base b
    where p_tab = 'top' and (b.w_voters >= cfg('min_endorsers_top') or ((select y from young) and b.w_voters >= 0.5 and b.w_loves > 0))
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
      and not exists (select 1 from top_lane t where t.id = b.id and t.k <= 40)
      and greatest(b.dish_created, b.newest_post) > now() - make_interval(days => greatest(cfg('fresh_days'), 0)::int)
  ),
  slotted as (
    select id, 'top'::text as lane, k + (k - 1) / 4 as slot from top_lane
    union all
    select id, 'fresh', 5 * m from fresh_lane
    union all
    select b.id, 'trending', row_number() over (order by b.score_7d desc, b.score desc, b.id)
    from base b where p_tab = 'trending' and b.score_7d > 0
      and (b.w_endorsers >= cfg('min_endorsers_trending') or ((select y from young) and b.w_endorsers >= 0.5))
    union all
    select b.id, 'gem', row_number() over (order by b.score desc, b.id)
    from base b
    where p_tab = 'gems'
      and (b.w_endorsers >= cfg('gem_min_endorsers') or ((select y from young) and b.w_endorsers >= 0.5))
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

-- India's date for the daily Top snapshot (the database clock is UTC).
create or replace function snapshot_top() returns void
language sql as $$
  insert into top_snapshots (city_id, day, dish_ids)
  select c.id, (now() at time zone 'Asia/Kolkata')::date,
         array(select f.dish_id
               from get_feed(c.id, null, 'top', null, null, 40, 0, '', null) with ordinality f(dish_id, lane, n)
               where f.lane = 'top' order by f.n limit 20)
  from cities c
  where not exists (select 1 from top_snapshots t where t.city_id = c.id and t.day = (now() at time zone 'Asia/Kolkata')::date)
  on conflict (city_id, day) do nothing
$$;

-- ============ Taste Match ============
-- Without a single dish you both voted on, a match says at most 75 %: people who voted like
-- you outrank a stranger who ticked the same boxes (or likes the same dish types). The stated-taste pass only
-- compares people with a dish type or diet in common.
create or replace function taste_matches(p_user uuid, p_other uuid default null)
returns table (other_id uuid, match double precision, co_votes int, agreed int, evidence double precision)
language sql stable as $$
  with votes as (
    select eb.user_id as other_id, count(*)::int as n, count(*) filter (where eb.kind = ea.kind)::int as a
    from engagements ea
    join engagements eb on eb.dish_id = ea.dish_id and eb.user_id <> ea.user_id and eb.kind in ('recommend', 'pass')
    where ea.user_id = p_user and ea.kind in ('recommend', 'pass') and (p_other is null or eb.user_id = p_other)
    group by eb.user_id
  ),
  aff_me as (select user_id, category_id, score from taste_affinity where user_id = p_user),
  me_norm as (select sqrt(sum(score * score)) as n, sum(abs(score)) as mass from aff_me),
  aff_o as (
    select a.user_id, a.category_id, a.score, nn.n, nn.mass
    from taste_affinity a
    cross join lateral (select sqrt(sum(b.score * b.score)) as n, sum(abs(b.score)) as mass
                        from taste_affinity b where b.user_id = p_other) nn
    where p_other is not null and a.user_id = p_other and p_other <> p_user
    union all
    select c.user_id, c.category_id, c.score, c.n, c.mass
    from taste_affinity_cache c
    where p_other is null and c.user_id <> p_user
      and c.category_id in (select category_id from aff_me)
  ),
  cos as (
    select o.user_id as other_id, sum(o.score * m.score) / nullif((select n from me_norm) * o.n, 0) as c,
           least(1.0, least((select mass from me_norm), o.mass) / 8.0) as certainty
    from aff_me m
    join aff_o o on o.category_id = m.category_id
    group by o.user_id, o.n, o.mass
  ),
  stated as (
    select tb.user_id as other_id,
           0.5 * coalesce(cardinality(array(select unnest(ta.categories) intersect select unnest(tb.categories)))::float8
                          / nullif(cardinality(array(select unnest(ta.categories) union select unnest(tb.categories))), 0), 0)
         + 0.3 * (case when ta.diet = tb.diet then 1.0
                       when 'egg' in (ta.diet, tb.diet) then 0.5 else 0.0 end)
         + 0.2 * (1.0 - abs(ta.spice - tb.spice) / 4.0) as s
    from taste_profiles ta join taste_profiles tb on tb.user_id <> ta.user_id
    where ta.user_id = p_user and (p_other is null or tb.user_id = p_other)
      and (p_other is not null or ta.categories && tb.categories or ta.diet = tb.diet)
  ),
  people as (
    select other_id from votes union select other_id from cos union select other_id from stated
  ),
  scored as (
    select p.other_id, coalesce(v.n, 0) as n, coalesce(v.a, 0) as a,
           least(coalesce(v.n, 0), 10) / 10.0 * 3 as wv, (coalesce(v.a, 0) + 1.0) / (coalesce(v.n, 0) + 2.0) as sv,
           case when c.c is not null then 1.5 * c.certainty else 0 end as wc, greatest(coalesce(c.c, 0), 0) as sc,
           case when s.s is not null then 1.0 else 0 end as ws, coalesce(s.s, 0) as ss
    from people p
    left join votes v on v.other_id = p.other_id
    left join cos c on c.other_id = p.other_id
    left join stated s on s.other_id = p.other_id
  )
  select other_id,
         (case when wv + wc = 0 then 0.25 + 0.5 * ss
               when wv = 0 then least(0.75, (wc * sc + ws * ss) / (wc + ws))
               else (wv * sv + wc * sc + ws * ss) / (wv + wc + ws) end)::float8,
         n, a, (wv + wc + ws)::float8
  from scored
  where wv + wc + ws >= 1
$$;

-- ============ For You ============
create or replace function for_you(p_user uuid, p_city int, p_limit int, p_offset int)
returns table (dish_id uuid, score double precision, by_user uuid, by_match double precision, followed boolean, people int, category_id int)
language sql stable as $$
  with me as (select coalesce((select diet from taste_profiles where user_id = p_user), 'nonveg') as diet),
  -- Closest taste matches, surest first: a match counts in full only with enough evidence,
  -- and answers alone don't make anyone a neighbour.
  nb as (
    select other_id, match from taste_matches(p_user)
    where match >= 0.55 and (co_votes >= 1 or evidence >= 1.5)
    order by match * least(1.0, evidence / 3.0) desc, co_votes desc, other_id limit 50
  ),
  fol as (select followee_id as other_id from follows where follower_id = p_user),
  people as (
    select coalesce(nb.other_id, fol.other_id) as other_id, nb.match, (fol.other_id is not null) as followed,
           case when nb.other_id is not null and fol.other_id is not null then least(1.0, nb.match + 0.25)
                when nb.other_id is not null then nb.match
                else 0.7 end as w
    from nb full join fol on fol.other_id = nb.other_id
    where not exists (select 1 from blocks b
                      where (b.blocker_id = p_user and b.blocked_id = coalesce(nb.other_id, fol.other_id))
                         or (b.blocked_id = p_user and b.blocker_id = coalesce(nb.other_id, fol.other_id)))
  ),
  candidates as (
    select d.id, d.category_id, d.is_veg, d.name_norm from dishes d
    join restaurants r on r.id = d.restaurant_id and r.status = 'active'
    where d.city_id = p_city and d.status = 'live'
      and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live'
                  and not exists (select 1 from blocks b where b.blocker_id = p_user and b.blocked_id = p.author_id))
      and not exists (select 1 from engagements x where x.user_id = p_user and x.dish_id = d.id and x.kind in ('recommend', 'pass', 'tried'))
      and dish_fits_diet((select diet from me), d.is_veg, d.category_id, d.name_norm)
  ),
  social_all as (
    select c.id, c.category_id,
           sum(pe.w * standing(pr.created_at, vi.twins) * decay(e.created_at, 90)
               * case e.kind when 'recommend' then 1.0 when 'save' then 0.5 else -1.0 end) as score,
           count(distinct e.user_id) filter (where e.kind <> 'pass')::int as people,
           -- Named only from people who loved it.
           (array_agg(e.user_id order by pe.w desc, e.created_at desc) filter (where e.kind = 'recommend'))[1] as by_user
    from candidates c
    join engagements e on e.dish_id = c.id and e.kind in ('recommend', 'save', 'pass')
    join people pe on pe.other_id = e.user_id
    join profiles pr on pr.id = e.user_id
    left join voter_independence vi on vi.user_id = e.user_id
    group by c.id, c.category_id
  ),
  social as (select * from social_all where score > 0),
  liked_types as (
    select category_id, score from taste_affinity where user_id = p_user and score > 0
  ),
  by_type as (
    select c.id, c.category_id, 0.05 * lt.score * coalesce(dr.confidence, 0.5) as score
    from candidates c join liked_types lt on lt.category_id = c.category_id
    left join dish_rating dr on dr.dish_id = c.id
    where not exists (select 1 from social_all s where s.id = c.id)
  ),
  -- Nothing to go on yet (no follows, matches or taste): the city's best dishes.
  best as (
    select c.id, c.category_id, 0.0001 * coalesce(dr.confidence, 0.5) as score
    from candidates c left join dish_rating dr on dr.dish_id = c.id
    where not exists (select 1 from social_all s where s.id = c.id)
      and not exists (select 1 from by_type b where b.id = c.id)
  ),
  ranked as (
    select s.id, s.score, s.by_user, s.people, s.category_id from social s
    union all
    select b.id, b.score, null, 0, b.category_id from by_type b
    union all
    select b.id, b.score, null, 0, b.category_id from best b
  )
  select r.id, r.score::float8, r.by_user, pe.match, coalesce(pe.followed, false), r.people, r.category_id
  from ranked r left join people pe on pe.other_id = r.by_user
  order by (r.by_user is not null) desc, r.score desc, r.id
  offset p_offset limit p_limit
$$;

-- ============ Places waiting for review ============
create or replace function auto_approve_places() returns int
language plpgsql as $$
declare n int;
begin
  -- Enough different people (each at least half-established) posted there, or it has waited
  -- place_auto_approve_days with a photo; either way only when no live place with a
  -- similar name is within 150 m (that one is probably it: a person decides).
  update restaurants r set status = 'active'
  where r.status = 'pending'
    and not exists (select 1 from restaurants o
                    where o.id <> r.id and o.status = 'active' and st_dwithin(o.geo, r.geo, 150)
                      and greatest(similarity(o.name_norm, r.name_norm), word_similarity(o.name_norm, r.name_norm),
                                   word_similarity(r.name_norm, o.name_norm)) >= 0.5)
    and (
      (select count(distinct p.author_id) from posts p join dishes d on d.id = p.dish_id
         join profiles pr on pr.id = p.author_id
         left join voter_independence vi on vi.user_id = pr.id
        where d.restaurant_id = r.id and p.status = 'live'
          and standing(pr.created_at, vi.twins) >= 0.5) >= cfg('place_auto_approve_posters')
      or (r.created_at < now() - make_interval(days => greatest(cfg('place_auto_approve_days'), 0)::int)
          and exists (select 1 from posts p join dishes d on d.id = p.dish_id
                      where d.restaurant_id = r.id and p.status = 'live')));
  get diagnostics n = row_count;
  return n;
end $$;

-- ============ Overture's own duplicates ============
-- The same name listed twice within 30 m with nothing posted: keep the first, hide the rest.
update restaurants r set status = 'hidden'
where r.source = 'overture' and r.status = 'active'
  and not exists (select 1 from dishes d where d.restaurant_id = r.id)
  and exists (select 1 from restaurants o
              where o.id <> r.id and o.status = 'active' and o.name_norm = r.name_norm
                and st_dwithin(o.geo, r.geo, 30) and (o.created_at, o.id) < (r.created_at, r.id));

select refresh_dish_stats();
select refresh_people_pick();

-- ============ Cleanup after removals ============
-- Reports about things that no longer exist, chats with fewer than two people left, and
-- deleted dishes in the daily Top copies. Run after the demo (or anything big) is removed.
create or replace function clean_orphans() returns void
language sql as $$
  delete from reports rp where not case rp.target_type
    when 'post'    then exists (select 1 from posts where id = rp.target_id)
    when 'dish'    then exists (select 1 from dishes where id = rp.target_id)
    when 'user'    then exists (select 1 from profiles where id = rp.target_id)
    when 'comment' then exists (select 1 from comments where id = rp.target_id)
    when 'message' then exists (select 1 from messages where id = rp.target_id)
    when 'story'   then exists (select 1 from stories where id = rp.target_id)
    else true end;
  delete from conversations c where (select count(*) from conversation_members m where m.conversation_id = c.id) < 2;
  update top_snapshots t set dish_ids = array(select x from unnest(t.dish_ids) with ordinality u(x, n)
                                              where exists (select 1 from dishes where id = u.x) order by u.n)
  where exists (select 1 from unnest(t.dish_ids) x where not exists (select 1 from dishes where id = x));
$$;
