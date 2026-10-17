-- Two-step sign-in by email: when it's on, signing in needs a 6-digit code sent to the
-- account's email. Turning it on proves the email works (and marks it verified).
alter table profiles add column if not exists two_factor boolean not null default false;
alter table profiles add column if not exists email_verified_at timestamptz;
-- The day (India time) we last reminded someone to turn it on.
alter table profiles add column if not exists security_reminded_on date;

create table if not exists auth_codes (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references profiles(id) on delete cascade,
  purpose    text not null check (purpose in ('login', 'enable')),
  code_hash  text not null,
  attempts   int not null default 0,
  sent_at    timestamptz not null default now(),
  expires_at timestamptz not null,
  used_at    timestamptz
);
create index if not exists auth_codes_user on auth_codes (user_id, purpose, sent_at desc);
