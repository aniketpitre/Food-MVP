-- Instagram-style messages: share a place or a post, send a photo, reply to a message,
-- react with an emoji.
alter table messages drop constraint if exists messages_kind_check;
alter table messages add constraint messages_kind_check
  check (kind in ('text', 'dish', 'story_reply', 'story_like', 'place', 'post', 'photo'));
alter table messages add column if not exists restaurant_id uuid references restaurants(id) on delete set null;
alter table messages add column if not exists post_id uuid references posts(id) on delete set null;
alter table messages add column if not exists image_path text;
alter table messages add column if not exists thumb_path text;
alter table messages add column if not exists reply_to uuid references messages(id) on delete set null;

create table if not exists message_reactions (
  message_id uuid not null references messages(id) on delete cascade,
  user_id    uuid not null references profiles(id) on delete cascade,
  emoji      text not null check (char_length(emoji) between 1 and 16),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id)
);
