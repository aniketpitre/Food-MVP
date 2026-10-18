-- Scaling the people side of ranking, and an honest diet filter.
--
--  * Trust: for each vote, the standing of everyone who voted on the dish after it is now
--    read from running totals (one pass per dish) instead of comparing every vote with every
--    other one, which grew with the square of a dish's votes. Same numbers, minus your own
--    lockstep ring as before.
--  * Taste Match: everyone's taste per dish type is kept in a table rebuilt on the ranking
--    timer, with each person's totals alongside, so matching you against everyone reads one
--    indexed table instead of re-adding every vote in the database on each request. Your own
--    taste is still read live.
--  * For You no longer shows vegetarians dishes of unknown type that are clearly meat, fish
--    or egg by name or category.

-- ============ Taste per dish type, kept ============
create table if not exists taste_affinity_cache (
  user_id     uuid not null references profiles(id) on delete cascade,
  category_id int  not null,
  score       double precision not null,
  n           double precision not null,   -- the length of this person's whole taste vector
  mass        double precision not null,   -- how much evidence it rests on
  primary key (user_id, category_id)
);
create index if not exists taste_affinity_cache_cat on taste_affinity_cache (category_id);

-- Everyone (on the ranking timer), or one person straight after they vote or change their
-- taste, so the table is never behind on anyone who just acted.
create or replace function refresh_taste_affinity(p_user uuid default null) returns void
language plpgsql as $$
begin
  if p_user is null then
    if not pg_try_advisory_xact_lock(727004) then return; end if;
  else
    perform pg_advisory_xact_lock(727004, hashtext(p_user::text));
  end if;
  delete from taste_affinity_cache where p_user is null or user_id = p_user;
  insert into taste_affinity_cache (user_id, category_id, score, n, mass)
  select a.user_id, a.category_id, a.score,
         sqrt(sum(a.score * a.score) over (partition by a.user_id)),
         sum(abs(a.score)) over (partition by a.user_id)
  from taste_affinity a
  where p_user is null or a.user_id = p_user
  on conflict (user_id, category_id) do update set score = excluded.score, n = excluded.n, mass = excluded.mass;
end $$;

-- Kept current by the database itself: whenever someone's votes or stated taste change (by
-- the app, an admin merge, an import), their rows are rebuilt in the same transaction.
create or replace function taste_affinity_touch() returns trigger
language plpgsql as $$
declare u uuid;
begin
  if tg_op in ('INSERT', 'UPDATE') then
    for u in select distinct user_id from new_rows loop perform refresh_taste_affinity(u); end loop;
  end if;
  if tg_op in ('DELETE', 'UPDATE') then
    for u in select distinct user_id from old_rows loop perform refresh_taste_affinity(u); end loop;
  end if;
  return null;
end $$;

drop trigger if exists engagements_taste_ins on engagements;
drop trigger if exists engagements_taste_del on engagements;
drop trigger if exists engagements_taste_upd on engagements;
create trigger engagements_taste_ins after insert on engagements
  referencing new table as new_rows for each statement execute function taste_affinity_touch();
create trigger engagements_taste_del after delete on engagements
  referencing old table as old_rows for each statement execute function taste_affinity_touch();
create trigger engagements_taste_upd after update on engagements
  referencing new table as new_rows old table as old_rows for each statement execute function taste_affinity_touch();

drop trigger if exists taste_profiles_ins on taste_profiles;
drop trigger if exists taste_profiles_del on taste_profiles;
drop trigger if exists taste_profiles_upd on taste_profiles;
create trigger taste_profiles_ins after insert on taste_profiles
  referencing new table as new_rows for each statement execute function taste_affinity_touch();
create trigger taste_profiles_del after delete on taste_profiles
  referencing old table as old_rows for each statement execute function taste_affinity_touch();
create trigger taste_profiles_upd after update on taste_profiles
  referencing new table as new_rows old table as old_rows for each statement execute function taste_affinity_touch();

-- ============ Taste Match ============
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
  -- Your own taste, live (so your latest votes count at once); everyone else's from the
  -- table rebuilt on the ranking timer (live too when it's just one other person).
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
  select other_id, ((wv * sv + wc * sc + ws * ss) / (wv + wc + ws))::float8, n, a, (wv + wc + ws)::float8
  from scored
  where wv + wc + ws >= 1
$$;

-- ============ For You ============
create or replace function for_you(p_user uuid, p_city int, p_limit int, p_offset int)
returns table (dish_id uuid, score double precision, by_user uuid, by_match double precision, followed boolean, people int, category_id int)
language sql stable as $$
  with me as (select coalesce((select diet from taste_profiles where user_id = p_user), 'nonveg') as diet),
  nb as (
    select other_id, match from taste_matches(p_user) where match >= 0.55
    order by match desc, co_votes desc, other_id limit 50
  ),
  fol as (select followee_id as other_id from follows where follower_id = p_user),
  people as (
    select coalesce(nb.other_id, fol.other_id) as other_id, nb.match, (fol.other_id is not null) as followed,
           case when nb.other_id is not null and fol.other_id is not null then least(1.0, nb.match + 0.25)
                when nb.other_id is not null then nb.match
                else 0.7 end as w
    from nb full join fol on fol.other_id = nb.other_id
    where not exists (select 1 from blocks b where b.blocker_id = p_user and b.blocked_id = coalesce(nb.other_id, fol.other_id))
  ),
  candidates as (
    select d.id, d.category_id, d.is_veg, d.name_norm from dishes d
    join restaurants r on r.id = d.restaurant_id and r.status = 'active'
    where d.city_id = p_city and d.status = 'live'
      and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live'
                  and not exists (select 1 from blocks b where b.blocker_id = p_user and b.blocked_id = p.author_id))
      and not exists (select 1 from engagements x where x.user_id = p_user and x.dish_id = d.id and x.kind in ('recommend', 'pass', 'tried'))
      -- Vegetarians: dishes marked veg, or unmarked ones that don't look like meat, fish or egg.
      and ((select diet from me) = 'nonveg'
           or d.is_veg is true
           or (d.is_veg is null
               and not coalesce(d.category_id in (select id from dish_categories where slug in ('non-veg-curry', 'seafood')), false)
               and d.name_norm !~ '(^| )(chicken|mutton|fish|prawns?|prawn|kheema|keema|surmai|pomfret|bombil|egg|eggs|anda|omelette|omelet|bhurji|lamb|beef|pork|crab|kolambi|tangdi|murgh|gosht)( |$)')
           or ((select diet from me) = 'egg' and d.is_veg is not true and d.name_norm ~ '(^| )(egg|eggs|anda|omelette|omelet|bhurji)( |$)'))
  ),
  social_all as (
    select c.id, c.category_id,
           sum(pe.w * standing(pr.created_at, vi.twins) * decay(e.created_at, 90)
               * case e.kind when 'recommend' then 1.0 when 'save' then 0.5 else -1.0 end) as score,
           count(distinct e.user_id) filter (where e.kind <> 'pass')::int as people,
           (array_agg(e.user_id order by (e.kind = 'recommend') desc, pe.w desc, e.created_at desc))[1] as by_user
    from candidates c
    join engagements e on e.dish_id = c.id and e.kind in ('recommend', 'save', 'pass')
    join people pe on pe.other_id = e.user_id
    join profiles pr on pr.id = e.user_id
    left join voter_independence vi on vi.user_id = e.user_id
    group by c.id, c.category_id
  ),
  -- On balance loved by them (a dish they mostly passed on is left out entirely).
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
  ranked as (
    select s.id, s.score, s.by_user, s.people, s.category_id from social s
    union all
    select b.id, b.score, null, 0, b.category_id from by_type b
  )
  select r.id, r.score::float8, r.by_user, pe.match, coalesce(pe.followed, false), r.people, r.category_id
  from ranked r left join people pe on pe.other_id = r.by_user
  order by (r.by_user is not null) desc, r.score desc, r.id
  offset p_offset limit p_limit
$$;


-- ============ Trust ============
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
  -- Every counted vote (never the place owner's), with its voter's standing.
  v as (
    select e.user_id, e.dish_id, e.kind, e.created_at, coalesce(d.category_id, -1) as cat, s.st
    from engagements e
    join dishes d on d.id = e.dish_id and d.status = 'live'
    join restaurants r on r.id = d.restaurant_id
    join st s on s.user_id = e.user_id
    where e.kind in ('recommend', 'pass') and e.user_id is distinct from r.created_by
  ),
  -- For each vote, the standing of everyone who voted on the dish after it (and of those
  -- who voted the same way), as dish totals minus running totals: one pass per dish
  -- instead of comparing every vote with every other.
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
  -- Agreement from your own lockstep ring never builds your trust: take your twins back out.
  tw as (
    select pk.user_id, pk.dish_id, sum(v2.st) as w, count(*) as n,
           coalesce(sum(v2.st) filter (where v2.kind = pk.kind), 0) as wa,
           count(*) filter (where v2.kind = pk.kind) as na
    from v pk
    join voter_twins t on t.user_id = pk.user_id
    join v v2 on v2.dish_id = pk.dish_id and v2.user_id = t.twin_id and v2.created_at > pk.created_at
    group by pk.user_id, pk.dish_id
  ),
  later as (
    select w.user_id, w.cat, w.dish_id, w.kind,
           greatest(w.w_after - coalesce(tw.w, 0), 0) as w_later,
           greatest(w.w_agree_after - coalesce(tw.wa, 0), 0) as w_agree,
           (w.n_agree_after - coalesce(tw.na, 0))::int as n_agree
    from w left join tw on tw.user_id = w.user_id and tw.dish_id = w.dish_id
    where w.n_after - coalesce(tw.n, 0) > 0
  ),
  per_pick as (
    select user_id, cat, n_agree,
           least(1.0, w_later) as weight,
           least(1.0, w_later) * greatest(0.0,
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

select refresh_taste_affinity();
select refresh_people_pick();
