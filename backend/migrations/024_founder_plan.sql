-- The launch plan: a focused app, a short list of signature dishes to fill first, and a
-- paid-only-for-results pilot for restaurants (visit codes).

-- Focus mode (on by default): the app hides the extras (Stories, the extra Explore modes,
-- in-app turn-by-turn, Food on the way, Tastemakers) until the core is proven. Admins turn
-- it off from the dashboard; no app release needed.
insert into app_flags (key, value) values ('focus_mode', 'on') on conflict (key) do nothing;

-- The dishes to fill first, one per line. Coverage is counted against these.
insert into app_flags (key, value) values ('signature_dishes', concat_ws(E'\n',
  'Misal Pav', 'Vada Pav', 'Pav Bhaji', 'Sabudana Khichdi', 'Kanda Bhaji',
  'Thalipeeth', 'Masala Dosa', 'Biryani', 'Maharashtrian Thali', 'Masala Chai'))
on conflict (key) do nothing;

-- A restaurant on the pilot: a fixed fee for each new customer the app brings, billed
-- monthly after the fact (no prepay). Optional quiet hours: codes work only then.
create table if not exists restaurant_pilots (
  restaurant_id uuid primary key references restaurants(id) on delete cascade,
  fee_inr       int not null check (fee_inr between 0 and 500),
  offer         text check (char_length(offer) <= 120),
  quiet_hours   text check (char_length(quiet_hours) <= 60),
  redeem_token  text not null unique,
  contact       text check (char_length(contact) <= 120),
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);

-- A code a diner shows at the counter. The owner enters it on their private page and says
-- whether this is a new customer; only new customers are billed.
create table if not exists visit_codes (
  code          text primary key,
  user_id       uuid not null references profiles(id) on delete cascade,
  restaurant_id uuid not null references restaurants(id) on delete cascade,
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,
  redeemed_at   timestamptz,
  new_customer  boolean,
  fee_inr       int
);
create index if not exists visit_codes_user_idx on visit_codes (user_id, restaurant_id, created_at desc);
create index if not exists visit_codes_place_idx on visit_codes (restaurant_id, redeemed_at);
