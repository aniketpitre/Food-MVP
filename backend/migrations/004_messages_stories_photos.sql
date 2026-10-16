-- Direct messages (one to one, Instagram style with message requests), 24-hour stories,
-- and photos of places from openly licensed sources.

-- ============ Direct messages ============
alter table profiles add column if not exists dm_policy text not null default 'everyone'
  check (dm_policy in ('everyone', 'following', 'off'));

create table conversations (
  id              uuid primary key default gen_random_uuid(),
  pair_key        text not null unique,            -- the two member ids, sorted, joined by ':'
  created_at      timestamptz not null default now(),
  last_message_at timestamptz not null default now()
);

create table conversation_members (
  conversation_id uuid not null references conversations(id) on delete cascade,
  user_id         uuid not null references profiles(id) on delete cascade,
  -- 'request' until the person replies or accepts, when the sender isn't someone they follow.
  folder          text not null default 'inbox' check (folder in ('inbox', 'request')),
  last_read_at    timestamptz not null default 'epoch',
  -- Deleting a chat hides what came before; a new message brings it back.
  cleared_at      timestamptz not null default 'epoch',
  primary key (conversation_id, user_id)
);
create index conversation_members_user on conversation_members (user_id);

create table stories (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references profiles(id) on delete cascade,
  city_id       int references cities(id),
  image_path    text not null,
  thumb_path    text not null,
  placeholder_color text,
  owns_media    boolean not null default true,     -- demo stories borrow post photos
  caption       text check (char_length(caption) <= 200),
  dish_id       uuid references dishes(id) on delete set null,
  restaurant_id uuid references restaurants(id) on delete set null,
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null default now() + interval '24 hours'
);
create index stories_live on stories (user_id, expires_at);
create index stories_city on stories (city_id, expires_at);

create table story_views (
  story_id  uuid not null references stories(id) on delete cascade,
  viewer_id uuid not null references profiles(id) on delete cascade,
  liked     boolean not null default false,
  viewed_at timestamptz not null default now(),
  primary key (story_id, viewer_id)
);

create table messages (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references conversations(id) on delete cascade,
  sender_id       uuid not null references profiles(id) on delete cascade,
  kind            text not null default 'text' check (kind in ('text', 'dish', 'story_reply', 'story_like')),
  body            text check (char_length(body) <= 1000),
  dish_id         uuid references dishes(id) on delete set null,
  story_id        uuid references stories(id) on delete set null,
  created_at      timestamptz not null default now()
);
create index messages_conversation on messages (conversation_id, created_at desc);

-- ============ Photos of places ============
alter table restaurants add column if not exists osm_tags jsonb;
alter table restaurants add column if not exists google_place_id text;
alter table restaurants add column if not exists photos_checked_at timestamptz;

create table restaurant_photos (
  id            uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references restaurants(id) on delete cascade,
  source        text not null check (source in ('wikimedia', 'mapillary', 'owner')),
  source_ref    text not null,                     -- file title or image id at the source
  image_path    text not null,
  thumb_path    text not null,
  placeholder_color text,
  attribution   text not null,                     -- "Photo: <author>, <licence>, via <source>"
  source_url    text,
  created_at    timestamptz not null default now(),
  unique (restaurant_id, source, source_ref)
);

-- Messages and stories can be reported too.
alter table reports drop constraint if exists reports_target_type_check;
alter table reports add constraint reports_target_type_check
  check (target_type in ('post', 'dish', 'user', 'comment', 'message', 'story'));
