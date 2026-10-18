-- Profile changes reach every phone: when someone changes their photo, name or username,
-- profile_updated_at moves, and /updates hands the new look to everyone's app (which swaps
-- it in everywhere that person appears: comments, chats, lists, the feed). A changed
-- username is remembered, so screens still showing the old one can be updated too.
alter table profiles add column if not exists profile_updated_at timestamptz not null default now();
create index if not exists profiles_updated_idx on profiles (profile_updated_at);

create table if not exists handle_history (
  user_id    uuid not null references profiles(id) on delete cascade,
  old_handle text not null,
  changed_at timestamptz not null default now(),
  primary key (user_id, old_handle)
);

create or replace function profiles_touch() returns trigger
language plpgsql as $$
begin
  if new.handle is distinct from old.handle
     or new.display_name is distinct from old.display_name
     or new.avatar_path is distinct from old.avatar_path then
    new.profile_updated_at := now();
    if new.handle is distinct from old.handle then
      insert into handle_history (user_id, old_handle, changed_at) values (old.id, old.handle, now())
      on conflict (user_id, old_handle) do update set changed_at = now();
    end if;
  end if;
  return new;
end $$;

drop trigger if exists profiles_touch on profiles;
create trigger profiles_touch before update on profiles
  for each row execute function profiles_touch();
