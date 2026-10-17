-- Changing the account's email: a code to the old address and one to the new; the new
-- address waits on the code row until both are confirmed.
alter table auth_codes drop constraint if exists auth_codes_purpose_check;
alter table auth_codes add constraint auth_codes_purpose_check
  check (purpose in ('login', 'enable', 'reset', 'verify', 'change_old', 'change_new'));
alter table auth_codes add column if not exists new_email text;
