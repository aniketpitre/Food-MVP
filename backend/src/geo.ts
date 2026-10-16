import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type pg from 'pg';
import type { Db } from './db.js';

/** A pool or a client inside a transaction. */
type Queryable = { query: (text: string, values?: unknown[]) => Promise<pg.QueryResult<any>> };

const indiaFile = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../seed/india-cities.json');

/** How far a city reaches, from its population: 10 km for a town, up to 40 km for a metro. */
export function radiusFor(population: number): number {
  return Math.max(10, Math.min(40, Math.round(6 + Math.sqrt(population) / 40)));
}

export function slugify(s: string): string {
  return s
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

/**
 * Makes sure every Indian city in seed/india-cities.json exists and is open. Safe to run
 * on every start: existing cities keep their id, centre and radius (only a missing
 * population is filled in), new ones are added.
 */
export async function ensureCities(db: Db, log: (m: string) => void = () => {}): Promise<number> {
  const list = JSON.parse(await fs.readFile(indiaFile, 'utf8')) as [string, string | null, number, number, number][];
  const nameCount = new Map<string, number>();
  for (const [name] of list) nameCount.set(slugify(name), (nameCount.get(slugify(name)) ?? 0) + 1);
  const seen = new Set<string>();
  const rows = list
    .map(([name, state, lat, lng, pop]) => {
      const base = slugify(name);
      // The biggest place keeps the plain slug; namesakes in other states get the state appended.
      let slug = base;
      if ((nameCount.get(base) ?? 0) > 1 && seen.has(base)) slug = `${base}-${slugify(state ?? 'india')}`;
      seen.add(base);
      return { slug, name, state, lat, lng, pop, radius: radiusFor(pop) };
    })
    .filter((r, i, all) => all.findIndex((x) => x.slug === r.slug) === i);

  const { rowCount } = await db.query(
    `insert into cities (slug, name, state, center, radius_km, is_active, population)
     select s, n, st, st_setsrid(st_makepoint(lng, lat), 4326)::geography, rad, true, pop
     from unnest($1::text[], $2::text[], $3::text[], $4::float8[], $5::float8[], $6::int[], $7::int[])
          as t(s, n, st, lat, lng, rad, pop)
     on conflict (slug) do update set population = excluded.population where cities.population is null`,
    [
      rows.map((r) => r.slug), rows.map((r) => r.name), rows.map((r) => r.state),
      rows.map((r) => r.lat), rows.map((r) => r.lng), rows.map((r) => r.radius), rows.map((r) => r.pop),
    ],
  );
  await db.query('update cities set is_active = true where not is_active');
  log(`cities ready (${rows.length} in the list, ${rowCount ?? 0} added or updated)`);
  return rows.length;
}

export interface NearCity {
  id: number;
  slug: string;
  name: string;
  state: string | null;
  radiusKm: number;
  distanceKm: number;
}

/**
 * The city a point belongs to: among cities within 150 km, the one the point is "most
 * inside" (distance divided by the city's reach), so a suburb joins its metro rather than
 * a small town that happens to be a little closer. Null when nothing is within 150 km.
 */
export async function nearestCity(db: Queryable, lat: number, lng: number): Promise<NearCity | null> {
  const { rows } = await db.query(
    `with pt as (select st_setsrid(st_makepoint($2, $1), 4326)::geography as g)
     select c.id, c.slug, c.name, c.state, c.radius_km as "radiusKm",
            round(st_distance(c.center, pt.g) / 1000)::int as "distanceKm"
     from cities c, pt
     where c.is_active and st_dwithin(c.center, pt.g, 150000)
     order by st_distance(c.center, pt.g) / (c.radius_km * 1000.0), c.population desc nulls last
     limit 1`,
    [lat, lng],
  );
  return (rows[0] as NearCity | undefined) ?? null;
}

/** Whether a point counts as being in the city (its reach plus a margin for outskirts). */
export function inCity(c: NearCity): boolean {
  return c.distanceKm <= c.radiusKm + 15;
}
