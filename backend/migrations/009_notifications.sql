-- More notifications: stories and dish posts from people you follow, likes on your story,
-- new food near you, and push (a wake-up signal to the phone, no content). Nothing here
-- touches ranking.

-- New Activity kinds.
alter table notifications drop constraint if exists notifications_kind_check;
alter table notifications add constraint notifications_kind_check
  check (kind in ('follow', 'comment', 'reply', 'comment_like', 'mention', 'recommend', 'agree',
                  'story', 'post', 'story_like'));
alter table notifications add column if not exists story_id uuid references stories(id) on delete cascade;

-- Each story and each post is its own notification (the old key would have kept only the
-- first story or post someone ever made). Toggling a like still notifies once.
drop index if exists notifications_once;
create unique index notifications_once on notifications (
  user_id, coalesce(actor_id, '00000000-0000-0000-0000-000000000000'), kind,
  coalesce(dish_id, '00000000-0000-0000-0000-000000000000'),
  coalesce(comment_id, '00000000-0000-0000-0000-000000000000'),
  coalesce(post_id, '00000000-0000-0000-0000-000000000000'),
  coalesce(story_id, '00000000-0000-0000-0000-000000000000'));

-- When a place first went live on WorthAbite (new places wait for review), for "new near you".
alter table restaurants add column if not exists listed_at timestamptz;
update restaurants set listed_at = created_at where status = 'active' and listed_at is null;
create or replace function restaurants_listed() returns trigger language plpgsql as $$
begin
  if new.status = 'active' and (tg_op = 'INSERT' or old.status is distinct from 'active') then
    new.listed_at := coalesce(new.listed_at, now());
  end if;
  return new;
end $$;
drop trigger if exists restaurants_listed on restaurants;
create trigger restaurants_listed before insert or update of status on restaurants
  for each row execute function restaurants_listed();
create index if not exists restaurants_listed_idx on restaurants (city_id, listed_at) where status = 'active';
create index if not exists dishes_new_idx on dishes (city_id, created_at) where status = 'live';

-- Phones to wake when something new arrives (Firebase Cloud Messaging tokens).
create table if not exists push_tokens (
  token      text primary key check (char_length(token) <= 4096),
  user_id    uuid not null references profiles(id) on delete cascade,
  city_id    int references cities(id) on delete set null,
  updated_at timestamptz not null default now()
);
create index if not exists push_tokens_user on push_tokens (user_id);
create index if not exists push_tokens_city on push_tokens (city_id);

-- Every new Activity item, shared dish and reaction wakes the person's phone. The server
-- LISTENs on 'wake' and sends the push (see src/push.ts); without push nobody listens.
create or replace function wake_notification() returns trigger language plpgsql as $$
begin
  perform pg_notify('wake', new.user_id::text);
  return null;
end $$;
drop trigger if exists wake_notification on notifications;
create trigger wake_notification after insert on notifications
  for each row execute function wake_notification();

create or replace function wake_message() returns trigger language plpgsql as $$
begin
  perform pg_notify('wake', m.user_id::text)
  from conversation_members m
  where m.conversation_id = new.conversation_id and m.user_id <> new.sender_id;
  return null;
end $$;
drop trigger if exists wake_message on messages;
create trigger wake_message after insert on messages
  for each row execute function wake_message();

create or replace function wake_reaction() returns trigger language plpgsql as $$
begin
  perform pg_notify('wake', m.sender_id::text)
  from messages m where m.id = new.message_id and m.sender_id <> new.user_id;
  return null;
end $$;
drop trigger if exists wake_reaction on message_reactions;
create trigger wake_reaction after insert or update of emoji on message_reactions
  for each row execute function wake_reaction();
