-- Small server-wide switches set from the dashboard, kept across restarts. First one:
-- 'demo_retired' once the demo was removed, so start-up never brings the demo back.
create table if not exists app_flags (
  key        text primary key,
  value      text not null,
  updated_at timestamptz not null default now()
);
