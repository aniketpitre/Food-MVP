-- Details people asked for in the AI user test: a price on every dish, diet and taste tags
-- (Jain / no onion-garlic, healthy, spicy, mild), and opening hours where the map data has none.
-- All three come from people, so each follows the same trust rule as the plain numbers on a card
-- (vote_shown: accounts at least a day old that aren't voting in lockstep).

-- ============ Prices ============
-- Everyone who posts a dish (or taps "Add price") reports what they paid. The dish's price is the
-- median of the trusted reports, else of all reports, so one person can't set ₹1 or ₹9,999.
create table if not exists dish_prices (
  dish_id    uuid not null references dishes(id) on delete cascade,
  user_id    uuid not null references profiles(id) on delete cascade,
  price_inr  int  not null check (price_inr between 1 and 20000),
  created_at timestamptz not null default now(),
  primary key (dish_id, user_id)
);
alter table dishes add column if not exists price_reports int not null default 0;

-- The price already on a dish counts as its first report, from whoever added the dish.
insert into dish_prices (dish_id, user_id, price_inr)
select d.id, d.created_by, d.price_inr from dishes d
where d.price_inr is not null and d.created_by is not null
on conflict do nothing;

create or replace function refresh_dish_price(p_dish uuid) returns void
language sql as $$
  with rep as (
    select dp.price_inr, vote_shown(dp.user_id) as trusted from dish_prices dp where dp.dish_id = p_dish
  ),
  pick as (
    select case when exists (select 1 from rep where trusted)
                then (select percentile_cont(0.5) within group (order by price_inr) from rep where trusted)
                else (select percentile_cont(0.5) within group (order by price_inr) from rep) end as price,
           (select count(*) from rep)::int as n
  )
  update dishes d
  set price_inr = coalesce(round(pick.price)::int, d.price_inr), price_reports = pick.n
  from pick where d.id = p_dish
$$;

select refresh_dish_price(d.id) from dishes d where exists (select 1 from dish_prices dp where dp.dish_id = d.id);

-- ============ Tags ============
-- Anyone who tried a dish can tag it. A tag shows once someone the ranking trusts gave it; spicy
-- and mild exclude each other (the one more people gave wins).
create table if not exists dish_tag_votes (
  dish_id    uuid not null references dishes(id) on delete cascade,
  user_id    uuid not null references profiles(id) on delete cascade,
  tag        text not null check (tag in ('jain', 'healthy', 'spicy', 'mild')),
  created_at timestamptz not null default now(),
  primary key (dish_id, user_id, tag)
);
create index if not exists dish_tag_votes_dish on dish_tag_votes (dish_id);

create or replace function dish_tags(p_dish uuid) returns text[]
language sql stable as $$
  with t as (
    select tag, count(*) filter (where vote_shown(user_id)) as trusted
    from dish_tag_votes where dish_id = p_dish group by tag
  )
  select coalesce(array_agg(tag order by tag), '{}') from t
  where trusted >= 1
    and not (tag = 'spicy' and coalesce((select trusted from t where tag = 'mild'), 0) > trusted)
    and not (tag = 'mild' and coalesce((select trusted from t where tag = 'spicy'), 0) >= trusted)
$$;

-- ============ Opening hours ============
-- When a place has no hours from the map data, a trusted person can add them; later people can
-- correct hours that came from people, never the map data's. Every change is kept.
alter table restaurants add column if not exists hours_by uuid references profiles(id) on delete set null;
alter table restaurants add column if not exists hours_at timestamptz;
create table if not exists place_edits (
  id            bigint generated always as identity primary key,
  restaurant_id uuid not null references restaurants(id) on delete cascade,
  user_id       uuid references profiles(id) on delete set null,
  field         text not null,
  old_value     text,
  new_value     text,
  created_at    timestamptz not null default now()
);
create index if not exists place_edits_place on place_edits (restaurant_id, created_at desc);
