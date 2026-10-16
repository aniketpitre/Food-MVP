-- Every Indian city is open (the list is loaded by ensureCities at start-up) and
-- people can comment on posts, Instagram style: comments, one level of replies, likes.

alter table cities add column if not exists population int;

create table comments (
  id          uuid primary key default gen_random_uuid(),
  post_id     uuid not null references posts(id) on delete cascade,
  author_id   uuid not null references profiles(id) on delete cascade,
  parent_id   uuid references comments(id) on delete cascade,
  body        text not null check (char_length(body) between 1 and 500),
  status      text not null default 'live' check (status in ('live', 'hidden', 'removed')),
  created_at  timestamptz not null default now()
);
create index comments_post_idx   on comments (post_id, created_at);
create index comments_parent_idx on comments (parent_id) where parent_id is not null;
create index comments_author_idx on comments (author_id, created_at desc);

create table comment_likes (
  comment_id uuid not null references comments(id) on delete cascade,
  user_id    uuid not null references profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (comment_id, user_id)
);

alter table reports drop constraint if exists reports_target_type_check;
alter table reports add constraint reports_target_type_check
  check (target_type in ('post', 'dish', 'user', 'comment'));
