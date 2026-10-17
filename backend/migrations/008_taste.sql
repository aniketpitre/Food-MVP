-- People-based recommendation: a taste profile, Taste Match between two people, and a
-- For You lane ranked by the people whose taste matches yours and the people you follow.

-- ============ Taste profile ============
-- What someone tells us (a short onboarding, editable any time). What they love, save and
-- pass on teaches the rest (taste_affinity below).
create table if not exists taste_profiles (
  user_id    uuid primary key references profiles(id) on delete cascade,
  categories int[] not null default '{}',   -- dish types they love (dish_categories ids)
  diet       text not null default 'nonveg' check (diet in ('veg', 'egg', 'nonveg')),
  spice      smallint not null default 3 check (spice between 1 and 5),
  updated_at timestamptz not null default now()
);

-- How much someone likes each dish type: a love +1, a save +0.5, a "not for me" -1, and
-- +1 for each type they picked in their profile.
create or replace view taste_affinity as
select user_id, category_id, sum(w)::float8 as score
from (
  select e.user_id, d.category_id,
         case e.kind when 'recommend' then 1.0 when 'save' then 0.5 when 'pass' then -1.0 end as w
  from engagements e join dishes d on d.id = e.dish_id
  where d.category_id is not null and e.kind in ('recommend', 'save', 'pass')
  union all
  select t.user_id, c, 1.0 from taste_profiles t, unnest(t.categories) c
) x
group by user_id, category_id;

create index if not exists engagements_user_dish on engagements (user_id, dish_id);

-- ============ Taste Match ============
-- How alike p_user's taste is to everyone else's (or just p_other's), 0..1, from three
-- signals, each counted only when there's something to go on:
--   * votes on the same dishes: how often you both loved or both passed (weight grows
--     with the number of shared dishes, up to 3);
--   * the dish types you like (cosine of the two taste_affinity vectors; weight up to 1.5,
--     growing as both of you vote more, so one shared love isn't a perfect match);
--   * what you both said in the onboarding (types, diet, spice; weight 1).
-- People with less than one unit of evidence get no score.
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
  aff as (select * from taste_affinity),
  norms as (select user_id, sqrt(sum(score * score)) as n, sum(abs(score)) as mass from aff group by user_id),
  cos as (
    select o.user_id as other_id, sum(o.score * m.score) / nullif(nm.n * no.n, 0) as c,
           least(1.0, least(nm.mass, no.mass) / 8.0) as certainty
    from aff m
    join aff o on o.category_id = m.category_id and o.user_id <> m.user_id
    join norms nm on nm.user_id = m.user_id
    join norms no on no.user_id = o.user_id
    where m.user_id = p_user and (p_other is null or o.user_id = p_other)
    group by o.user_id, nm.n, no.n, nm.mass, no.mass
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
-- Dishes for p_user: what the people whose taste matches theirs (match >= 0.55, up to 50
-- of them) and the people they follow loved, counted by how close their taste is, their
-- standing and how recent the vote is. A "not for me" from them counts against. Dishes
-- p_user has already voted on are left out; a vegetarian never sees non-veg dishes.
-- With nobody to go on yet, the best-rated dishes of the types they like fill in.
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
      and ((select diet from me) = 'nonveg' or d.is_veg is not false
           or ((select diet from me) = 'egg' and d.name_norm ~ '(^| )(egg|eggs|anda|omelette|omelet|bhurji)( |$)'))
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
