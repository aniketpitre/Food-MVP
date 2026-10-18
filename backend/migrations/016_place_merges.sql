-- When an admin merges two listings of one place, it's recorded (old links and the audit
-- trail both need it), like dish_merges.
create table if not exists place_merges (
  from_id    uuid primary key,
  into_id    uuid not null references restaurants(id) on delete cascade,
  merged_by  uuid references profiles(id) on delete set null,
  merged_at  timestamptz not null default now()
);
