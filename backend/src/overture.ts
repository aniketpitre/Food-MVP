import { readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Db } from './db.js';

/** A food place from Overture Maps (© Overture Maps Foundation contributors, CDLA-Permissive-2.0). */
export interface OverturePlace {
  id: string;
  name: string;
  lat: number;
  lng: number;
  kind: string;
  cuisine: string | null;
  area: string | null;
  address: string | null;
  phone: string | null;
  website: string | null;
  confidence: number;
}

export interface OvertureFile {
  release: string;
  city: string;
  places: OverturePlace[];
}

/** Where the bundled file for a city lives (made by scripts/overture-places.py). */
export function overtureFileFor(citySlug: string): string {
  return join(dirname(fileURLToPath(import.meta.url)), '..', 'seed', 'places', `${citySlug}.overture.json`);
}

/** The file `scripts/overture-places.py` writes (bundled ones live in seed/places/). */
export async function readOvertureFile(path: string): Promise<OvertureFile> {
  const data = JSON.parse(await readFile(path, 'utf8')) as OvertureFile;
  if (!Array.isArray(data.places)) throw new Error(`${path} has no places`);
  return data;
}

/**
 * Adds Overture's places to a city. One already here (imported from OpenStreetMap, added by
 * someone, or imported before) within 80 m with a similar name is linked and only gets the
 * details it lacks (a place waiting for approval goes live: Overture confirms it's real); the
 * rest are added, live at once, except Overture listing one place twice (the same name within
 * 30 m). Running it again changes nothing new.
 */
export async function importOverturePlaces(
  db: Db,
  cityId: number,
  places: OverturePlace[],
): Promise<{ added: number; linked: number; skipped: number }> {
  let added = 0;
  let linked = 0;
  let skipped = 0;
  for (const p of places) {
    if (!p.name || !Number.isFinite(p.lat) || !Number.isFinite(p.lng)) continue;
    const contact = JSON.stringify(Object.fromEntries(
      [['phone', p.phone], ['website', p.website]].filter(([, v]) => v).map(([k, v]) => [k, String(v).slice(0, 300)]),
    ));
    const fill = `locality = coalesce(locality, $2), address = coalesce(address, $3), cuisine = coalesce(cuisine, $4),
                  osm_tags = $5::jsonb || coalesce(osm_tags, '{}'::jsonb)`;
    const details = [p.area?.slice(0, 60) ?? null, p.address?.slice(0, 160) ?? null, p.cuisine?.slice(0, 80) ?? null, contact];
    const { rowCount: known } = await db.query(`update restaurants set ${fill} where overture_id = $1`, [p.id, ...details]);
    if (known) {
      skipped++;
      continue;
    }
    const { rows: twin } = await db.query<{ id: string }>(
      `select id from restaurants
       where overture_id is null and city_id = $4 and status <> 'hidden'
         and st_dwithin(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography, 80)
         and (word_similarity(norm_name($3), name_norm) >= 0.6 or word_similarity(name_norm, norm_name($3)) >= 0.6)
       order by geo <-> st_setsrid(st_makepoint($2, $1), 4326)::geography
       limit 1`,
      [p.lat, p.lng, p.name, cityId],
    );
    if (twin[0]) {
      await db.query(
        `update restaurants set overture_id = $6, status = case when status = 'pending' then 'active' else status end, ${fill}
         where id = $1`,
        [twin[0].id, ...details, p.id],
      );
      linked++;
      continue;
    }
    const { rowCount: again } = await db.query(
      `select 1 from restaurants where city_id = $4 and source = 'overture' and name_norm = norm_name($3)
         and st_dwithin(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography, 30)`,
      [p.lat, p.lng, p.name, cityId],
    );
    if (again) {
      skipped++;
      continue;
    }
    await db.query(
      `insert into restaurants (city_id, name, name_norm, locality, address, cuisine, osm_tags, geo, status, source, overture_id)
       values ($1, $2, norm_name($2), $3, $4, $5, $6::jsonb, st_setsrid(st_makepoint($8, $7), 4326)::geography, 'active', 'overture', $9)`,
      [cityId, p.name.slice(0, 80), ...details, p.lat, p.lng, p.id],
    );
    added++;
  }
  return { added, linked, skipped };
}
