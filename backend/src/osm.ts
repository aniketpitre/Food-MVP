import type { Db } from './db.js';

/** A food place from OpenStreetMap (© OpenStreetMap contributors, ODbL). */
export interface OsmPlace {
  osmId: string;
  name: string;
  lat: number;
  lng: number;
  kind: string;
  cuisine: string | null;
  locality: string | null;
  openingHours: string | null;
  address: string | null;
  /** The tags that point to photos or contact details (image, wikimedia_commons, wikidata, website, phone). */
  tags: Record<string, string>;
}

const KEPT_TAGS = ['image', 'wikimedia_commons', 'wikidata', 'website', 'contact:website', 'phone', 'contact:phone', 'brand:wikidata'];

interface OverpassElement {
  type: string;
  id: number;
  lat?: number;
  lon?: number;
  center?: { lat: number; lon: number };
  tags?: Record<string, string>;
}

const FOOD_AMENITIES = ['restaurant', 'cafe', 'fast_food', 'food_court', 'ice_cream'];
const FOOD_SHOPS = ['bakery', 'confectionery', 'pastry'];

/** The Overpass query for every named food place within [radiusKm] of a point. */
export function overpassQuery(lat: number, lng: number, radiusKm: number): string {
  const r = Math.round(radiusKm * 1000);
  return `[out:json][timeout:90];
(
  nwr["amenity"~"^(${FOOD_AMENITIES.join('|')})$"]["name"](around:${r},${lat},${lng});
  nwr["shop"~"^(${FOOD_SHOPS.join('|')})$"]["name"](around:${r},${lat},${lng});
);
out center tags;`;
}

/** Turns an Overpass response into places, dropping unnamed, closed or duplicate entries. */
export function parseOverpass(json: { elements?: OverpassElement[] }): OsmPlace[] {
  const out = new Map<string, OsmPlace>();
  for (const e of json.elements ?? []) {
    const t = e.tags ?? {};
    const name = (t['name:en'] || t.name || '').trim();
    const lat = e.lat ?? e.center?.lat;
    const lng = e.lon ?? e.center?.lon;
    if (!name || lat === undefined || lng === undefined) continue;
    if (t['disused:amenity'] || t.disused === 'yes' || t['abandoned:amenity']) continue;
    const osmId = `${e.type[0]}${e.id}`;
    out.set(osmId, {
      osmId,
      name: name.slice(0, 80),
      lat,
      lng,
      kind: t.amenity ?? t.shop ?? 'restaurant',
      cuisine: t.cuisine ? t.cuisine.split(';').map((c) => c.trim().replace(/_/g, ' ')).join(', ').slice(0, 80) : null,
      locality: (t['addr:suburb'] || t['addr:neighbourhood'] || t['addr:street'] || t['addr:city'] || '').slice(0, 60) || null,
      openingHours: t.opening_hours?.slice(0, 120) ?? null,
      address: [t['addr:housenumber'], t['addr:street'], t['addr:suburb']].filter(Boolean).join(', ').slice(0, 160) || null,
      tags: Object.fromEntries(KEPT_TAGS.filter((k) => t[k]).map((k) => [k, t[k]!.slice(0, 300)])),
    });
  }
  return [...out.values()];
}

// The main Overpass server is often busy (502/504/429), so try the public mirrors too.
const OVERPASS_SERVERS = [
  'https://overpass-api.de/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter',
  'https://overpass.private.coffee/api/interpreter',
  'https://maps.mail.ru/osm/tools/overpass/api/interpreter',
];

export async function fetchOsmPlaces(lat: number, lng: number, radiusKm: number, log: (m: string) => void = () => {}): Promise<OsmPlace[]> {
  const body = new URLSearchParams({ data: overpassQuery(lat, lng, radiusKm) });
  let last = '';
  for (let round = 0; round < 2; round++) {
    for (const url of OVERPASS_SERVERS) {
      try {
        const res = await fetch(url, {
          method: 'POST',
          headers: { 'content-type': 'application/x-www-form-urlencoded', 'user-agent': 'FoodFeed/1.0 (self-hosted food app)' },
          body,
          signal: AbortSignal.timeout(120_000),
        });
        if (res.ok) return parseOverpass((await res.json()) as { elements?: OverpassElement[] });
        last = `${new URL(url).host} answered ${res.status}`;
      } catch (err) {
        last = `${new URL(url).host}: ${(err as Error).message}`;
      }
      log(`  ${last}, trying another server...`);
    }
    await new Promise((r) => setTimeout(r, 10_000));
  }
  throw new Error(`OpenStreetMap servers are busy (${last}). Try again in a few minutes, or with a smaller radius.`);
}

/**
 * Adds real places to a city. A place already in FoodFeed (same name within 80 m) is
 * linked instead of duplicated; places imported before are skipped. They go live at once:
 * they are real, and they only show dishes once people post them.
 */
export async function importOsmPlaces(db: Db, cityId: number, places: OsmPlace[]): Promise<{ added: number; linked: number; skipped: number }> {
  let added = 0;
  let linked = 0;
  let skipped = 0;
  for (const p of places) {
    const { rowCount: known } = await db.query(
      'update restaurants set osm_tags = $2, address = coalesce(address, $3) where osm_id = $1',
      [p.osmId, JSON.stringify(p.tags), p.address],
    );
    if (known) {
      skipped++;
      continue;
    }
    const { rows: twin } = await db.query<{ id: string }>(
      `select id from restaurants
       where osm_id is null and st_dwithin(geo, st_setsrid(st_makepoint($2, $1), 4326)::geography, 80)
         and word_similarity(norm_name($3), name_norm) >= 0.6
       limit 1`,
      [p.lat, p.lng, p.name],
    );
    if (twin[0]) {
      await db.query(
        `update restaurants set osm_id = $2, cuisine = coalesce(cuisine, $3), opening_hours = coalesce(opening_hours, $4),
                                osm_tags = $5, address = coalesce(address, $6) where id = $1`,
        [twin[0].id, p.osmId, p.cuisine, p.openingHours, JSON.stringify(p.tags), p.address],
      );
      linked++;
      continue;
    }
    await db.query(
      `insert into restaurants (city_id, name, name_norm, locality, geo, status, source, osm_id, cuisine, opening_hours, osm_tags, address)
       values ($1, $2, norm_name($2), $3, st_setsrid(st_makepoint($5, $4), 4326)::geography, 'active', 'osm', $6, $7, $8, $9, $10)`,
      [cityId, p.name, p.locality, p.lat, p.lng, p.osmId, p.cuisine, p.openingHours, JSON.stringify(p.tags), p.address],
    );
    added++;
  }
  return { added, linked, skipped };
}
