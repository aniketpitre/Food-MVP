-- Sign-in sessions you can end (sign out on the server, sign out everywhere, a list of
-- devices), account recovery codes (forgot password, verify email), and a lock against
-- password guessing on one account.

create table if not exists sessions (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references profiles(id) on delete cascade,
  device       text check (char_length(device) <= 80),
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at   timestamptz
);
create index if not exists sessions_user on sessions (user_id, revoked_at);

-- Tokens from before sessions existed stay valid until this moment is set (password
-- reset, "sign out everywhere"); then they stop working too.
alter table profiles add column if not exists tokens_valid_after timestamptz;
-- Too many wrong passwords in a row lock the account for a while.
alter table profiles add column if not exists failed_logins int not null default 0;
alter table profiles add column if not exists locked_until timestamptz;

alter table auth_codes drop constraint if exists auth_codes_purpose_check;
alter table auth_codes add constraint auth_codes_purpose_check check (purpose in ('login', 'enable', 'reset', 'verify'));
