-- Places from Overture Maps (open data: Meta, Microsoft, Foursquare, OpenStreetMap and
-- others, CDLA-Permissive-2.0). Like OpenStreetMap places, they are real, live at once, and
-- show dishes only once people post them. A place found in both keeps one row with both ids.
alter table restaurants add column if not exists overture_id text;
create unique index if not exists restaurants_overture_id on restaurants (overture_id) where overture_id is not null;

alter table restaurants drop constraint if exists restaurants_source_check;
alter table restaurants add constraint restaurants_source_check check (source in ('user', 'seed', 'osm', 'overture'));
