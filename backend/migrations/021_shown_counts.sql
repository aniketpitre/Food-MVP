-- The plain numbers on a card ("82% loved · 34 votes") count only the votes the ranking trusts.
--
-- The AI test users found that the ranking resisted a ring of fake accounts but the card's
-- "% loved" didn't: the dish the ring attacked showed 24% loved while ranking 3rd (93% once the
-- fakes are discounted), and the dish it pushed showed 72% while ranking last. Now loves and
-- "not for me" in dish_rating count only accounts at least shown_min_account_hours old (24) that
-- aren't voting in lockstep, besides leaving out whoever added the place (as before). The weighted
-- rating and the ranking are unchanged; everything that shows the counts (cards, Explore, the map,
-- the dashboard) reads these columns, and a place's "lovers" count follows the same rule.

insert into ranking_config (key, value, note) values
  ('shown_min_account_hours', 24, 'votes from accounts younger than this (or voting in lockstep) are left out of the "% loved" and vote count people see')
on conflict (key) do nothing;

-- Whether a person's votes show in the plain numbers (also used for a place's "lovers").
create or replace function vote_shown(p_user uuid) returns boolean
language sql stable as $$
  select coalesce((select vi.twins from voter_independence vi where vi.user_id = p_user), 0) = 0
     and (select p.created_at from profiles p where p.id = p_user)
         < now() - make_interval(hours => greatest(cfg('shown_min_account_hours'), 0)::int)
$$;

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
  shown_h numeric := greatest(cfg('shown_min_account_hours'), 0);
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
           standing(p.created_at, vi.twins) as st,
           -- Whether the vote shows in the plain numbers ("82% loved · 34 votes").
           (coalesce(vi.twins, 0) = 0 and p.created_at < now() - make_interval(hours => shown_h::int)) as shown
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
           count(*) filter (where kind = 'recommend' and shown)::int as loves,
           count(*) filter (where kind = 'pass' and shown)::int      as passes,
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

select refresh_voter_independence();
select refresh_people_pick();
