-- "Open now" for Explore's filter and place cards, from opening hours in the OpenStreetMap style
-- the map data and the app's hours picker use ("24/7", "Mo-Su 08:00-22:00", "Mo-Sa 09:00-21:00;
-- Su off", "Mo-Fr 11:00-15:00,19:00-23:30"), in India's time. A later rule for a day replaces an
-- earlier one (as in OpenStreetMap); hours past midnight run into the next day. Null when there
-- are no hours or none it can read: unknown, never a guess. The app reads the same hours the same
-- way (OpenHours.kt).
create or replace function is_open_now(p_hours text, p_at timestamptz default now()) returns boolean
language plpgsql stable as $$
declare
  local    timestamp := p_at at time zone 'Asia/Kolkata';
  today    int := extract(isodow from local)::int - 1;          -- 0 = Monday
  minute   int := extract(hour from local)::int * 60 + extract(minute from local)::int;
  names    text[] := array['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];
  per_day  text[] := array[null, null, null, null, null, null, null]::text[];
  rule     text;
  days_part text;
  times_part text;
  token    text;
  a int; b int; d int;
  span     text;
  f int; t int;
  parsed   boolean := false;
begin
  if p_hours is null or btrim(p_hours) = '' then return null; end if;
  if btrim(p_hours) = '24/7' then return true; end if;
  foreach rule in array string_to_array(p_hours, ';') loop
    rule := btrim(rule);
    continue when rule = '' or position(' ' in rule) = 0;
    days_part := split_part(rule, ' ', 1);
    times_part := btrim(substr(rule, length(days_part) + 2));
    continue when times_part <> 'off' and times_part !~ '^\d{1,2}:\d{2}-\d{1,2}:\d{2}(,\d{1,2}:\d{2}-\d{1,2}:\d{2})*$';
    foreach token in array string_to_array(days_part, ',') loop
      continue when token !~ '^(Mo|Tu|We|Th|Fr|Sa|Su)(-(Mo|Tu|We|Th|Fr|Sa|Su))?$';
      a := array_position(names, left(token, 2)) - 1;
      b := case when length(token) = 5 then array_position(names, right(token, 2)) - 1 else a end;
      d := a;
      loop
        per_day[d + 1] := times_part;
        parsed := true;
        exit when d = b;
        d := (d + 1) % 7;
      end loop;
    end loop;
  end loop;
  if not parsed then return null; end if;
  -- Open today?
  if per_day[today + 1] is not null and per_day[today + 1] <> 'off' then
    foreach span in array string_to_array(per_day[today + 1], ',') loop
      f := split_part(split_part(span, '-', 1), ':', 1)::int * 60 + split_part(split_part(span, '-', 1), ':', 2)::int;
      t := split_part(split_part(span, '-', 2), ':', 1)::int * 60 + split_part(split_part(span, '-', 2), ':', 2)::int;
      if t <= f then t := t + 1440; end if;
      if minute >= f and minute < t then return true; end if;
    end loop;
  end if;
  -- Still open from last night's late hours?
  d := (today + 6) % 7;
  if per_day[d + 1] is not null and per_day[d + 1] <> 'off' then
    foreach span in array string_to_array(per_day[d + 1], ',') loop
      f := split_part(split_part(span, '-', 1), ':', 1)::int * 60 + split_part(split_part(span, '-', 1), ':', 2)::int;
      t := split_part(split_part(span, '-', 2), ':', 1)::int * 60 + split_part(split_part(span, '-', 2), ':', 2)::int;
      if t <= f and minute < t then return true; end if;
    end loop;
  end if;
  return false;
end $$;
