import { randomUUID } from 'node:crypto';
import type { Db } from './db.js';
import { storeImage } from './media.js';

/**
 * Real photos of real places, only from sources whose licence lets us keep and show them:
 *
 * - Wikimedia Commons: when OpenStreetMap links a place to a Commons photo (image,
 *   wikimedia_commons) or to Wikidata (whose P18 is a Commons photo). Free licences
 *   (CC BY / CC BY-SA / public domain); we show the author and licence.
 * - Mapillary: street-level photos people share under CC BY-SA 4.0. We pick a recent photo
 *   taken within ~45 m that faces the place, i.e. its shopfront. Needs a free MAPILLARY_TOKEN.
 *
 * Google Maps photos are a different case: Google's terms don't allow storing them, so
 * those are fetched live, on request, by googlePlacePhotos() below.
 */

export type Fetch = typeof fetch;
const UA = { 'user-agent': 'FoodFeed/1.0 (self-hosted food app; photo attribution kept)' };

export interface FoundPhoto {
  source: 'wikimedia' | 'mapillary';
  ref: string;
  url: string;
  attribution: string;
  sourceUrl: string;
}

const stripHtml = (s: string) => s.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();

/** The Commons file a set of OSM tags points to, if any ("File:Something.jpg"). */
export async function commonsFileFromTags(tags: Record<string, string>, f: Fetch = fetch): Promise<string | null> {
  const fromImage = tags.image?.match(/(?:commons\.wikimedia\.org\/wiki\/|^)(File:[^?#]+)/i)?.[1];
  if (fromImage) return decodeURIComponent(fromImage).replace(/_/g, ' ');
  const wc = tags.wikimedia_commons;
  if (wc?.startsWith('File:')) return wc;
  const qid = tags.wikidata ?? tags['brand:wikidata'];
  if (qid && /^Q\d+$/.test(qid) && tags.wikidata) {
    const res = await f(`https://www.wikidata.org/wiki/Special:EntityData/${qid}.json`, { headers: UA });
    if (res.ok) {
      const json = (await res.json()) as { entities?: Record<string, { claims?: { P18?: { mainsnak?: { datavalue?: { value?: string } } }[] } }> };
      const file = json.entities?.[qid]?.claims?.P18?.[0]?.mainsnak?.datavalue?.value;
      if (file) return `File:${file}`;
    }
  }
  return null;
}

/** Looks a Commons file up: a 1280 px rendition, its author and its licence. */
export async function commonsPhoto(file: string, f: Fetch = fetch): Promise<FoundPhoto | null> {
  const url = new URL('https://commons.wikimedia.org/w/api.php');
  url.search = new URLSearchParams({
    action: 'query', format: 'json', formatversion: '2', titles: file,
    prop: 'imageinfo', iiprop: 'url|extmetadata|mime', iiurlwidth: '1280',
  }).toString();
  const res = await f(url, { headers: UA });
  if (!res.ok) return null;
  const json = (await res.json()) as {
    query?: { pages?: { title: string; imageinfo?: { thumburl?: string; descriptionurl?: string; mime?: string; extmetadata?: Record<string, { value?: string }> }[] }[] };
  };
  const page = json.query?.pages?.[0];
  const info = page?.imageinfo?.[0];
  if (!page || !info?.thumburl || !info.mime?.startsWith('image/')) return null;
  const meta = info.extmetadata ?? {};
  const author = stripHtml(meta.Artist?.value ?? '') || 'Unknown author';
  const licence = stripHtml(meta.LicenseShortName?.value ?? '') || 'see source';
  return {
    source: 'wikimedia',
    ref: page.title,
    url: info.thumburl,
    attribution: `Photo: ${author.slice(0, 80)}, ${licence}, via Wikimedia Commons`,
    sourceUrl: info.descriptionurl ?? `https://commons.wikimedia.org/wiki/${encodeURIComponent(page.title)}`,
  };
}

function bearing(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const r = Math.PI / 180;
  const y = Math.sin((lng2 - lng1) * r) * Math.cos(lat2 * r);
  const x = Math.cos(lat1 * r) * Math.sin(lat2 * r) - Math.sin(lat1 * r) * Math.cos(lat2 * r) * Math.cos((lng2 - lng1) * r);
  return (Math.atan2(y, x) / r + 360) % 360;
}

function metres(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const r = Math.PI / 180;
  const x = (lng2 - lng1) * r * Math.cos(((lat1 + lat2) / 2) * r);
  const y = (lat2 - lat1) * r;
  return Math.sqrt(x * x + y * y) * 6371000;
}

interface MapillaryImage {
  id: string;
  thumb_1024_url?: string;
  compass_angle?: number;
  computed_compass_angle?: number;
  computed_geometry?: { coordinates: [number, number] };
  geometry?: { coordinates: [number, number] };
  captured_at?: number;
  creator?: { username?: string };
}

/** Picks Mapillary photos that look at the place: close, facing it, newest first. */
export function pickFacing(images: MapillaryImage[], lat: number, lng: number, max = 2): MapillaryImage[] {
  return images
    .map((img) => {
      const [x, y] = (img.computed_geometry ?? img.geometry)?.coordinates ?? [NaN, NaN];
      const angle = img.computed_compass_angle ?? img.compass_angle;
      if (!img.thumb_1024_url || Number.isNaN(x) || angle === undefined) return null;
      const d = metres(y, x, lat, lng);
      const off = Math.abs(((bearing(y, x, lat, lng) - angle + 540) % 360) - 180);
      return d >= 4 && d <= 45 && off <= 40 ? { img, score: off + d - (img.captured_at ?? 0) / 3e10 } : null;
    })
    .filter((x): x is { img: MapillaryImage; score: number } => x !== null)
    .sort((a, b) => a.score - b.score)
    .slice(0, max)
    .map((x) => x.img);
}

export async function mapillaryPhotos(lat: number, lng: number, token: string, f: Fetch = fetch): Promise<FoundPhoto[]> {
  const d = 0.0005; // ~55 m
  const url = new URL('https://graph.mapillary.com/images');
  url.search = new URLSearchParams({
    access_token: token,
    fields: 'id,thumb_1024_url,compass_angle,computed_compass_angle,computed_geometry,geometry,captured_at,creator',
    bbox: [lng - d, lat - d, lng + d, lat + d].map((n) => n.toFixed(6)).join(','),
    limit: '100',
  }).toString();
  const res = await f(url, { headers: UA });
  if (!res.ok) throw new Error(`Mapillary answered ${res.status}`);
  const json = (await res.json()) as { data?: MapillaryImage[] };
  return pickFacing(json.data ?? [], lat, lng).map((img) => ({
    source: 'mapillary' as const,
    ref: img.id,
    url: img.thumb_1024_url!,
    attribution: `Photo: ${img.creator?.username ?? 'a Mapillary contributor'}, CC BY-SA 4.0, via Mapillary`,
    sourceUrl: `https://www.mapillary.com/app/?pKey=${img.id}`,
  }));
}

/** Downloads a found photo and stores it like our own (WebP, metadata dropped). */
async function keep(db: Db, mediaDir: string, restaurantId: string, p: FoundPhoto, f: Fetch): Promise<boolean> {
  const { rowCount } = await db.query(
    'select 1 from restaurant_photos where restaurant_id = $1 and source = $2 and source_ref = $3',
    [restaurantId, p.source, p.ref],
  );
  if (rowCount) return false;
  const res = await f(p.url, { headers: UA });
  if (!res.ok) return false;
  const id = randomUUID();
  let stored;
  try {
    stored = await storeImage(mediaDir, 'places', id, Buffer.from(await res.arrayBuffer()));
  } catch {
    return false;
  }
  await db.query(
    `insert into restaurant_photos (id, restaurant_id, source, source_ref, image_path, thumb_path, placeholder_color, attribution, source_url)
     values ($1, $2, $3, $4, $5, $6, $7, $8, $9) on conflict do nothing`,
    [id, restaurantId, p.source, p.ref, stored.imagePath, stored.thumbPath, stored.placeholderColor, p.attribution, p.sourceUrl],
  );
  return true;
}

/**
 * Finds photos for a city's places that have none yet (or weren't checked in 30 days).
 * Sequential and gentle on the free APIs.
 */
export async function fetchPlacePhotos(
  db: Db,
  mediaDir: string,
  cityId: number,
  opts: { mapillaryToken?: string; limit?: number; log?: (m: string) => void; fetchImpl?: Fetch; pauseMs?: number } = {},
): Promise<{ checked: number; added: number }> {
  const f = opts.fetchImpl ?? fetch;
  const log = opts.log ?? (() => {});
  const { rows } = await db.query<{ id: string; name: string; lat: number; lng: number; tags: Record<string, string> | null }>(
    `select r.id, r.name, st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng, r.osm_tags as tags
     from restaurants r
     where r.city_id = $1 and r.status = 'active'
       and (r.photos_checked_at is null or r.photos_checked_at < now() - interval '30 days')
       and not exists (select 1 from restaurant_photos p where p.restaurant_id = r.id)
       and ($2 or r.osm_tags ?| array['image', 'wikimedia_commons', 'wikidata'])
     order by (select count(*) from dishes d where d.restaurant_id = r.id) desc, r.name
     limit $3`,
    [cityId, !!opts.mapillaryToken, opts.limit ?? 500],
  );
  let added = 0;
  for (const r of rows) {
    const found: FoundPhoto[] = [];
    try {
      const file = r.tags ? await commonsFileFromTags(r.tags, f) : null;
      const c = file ? await commonsPhoto(file, f) : null;
      if (c) found.push(c);
      if (opts.mapillaryToken) found.push(...(await mapillaryPhotos(r.lat, r.lng, opts.mapillaryToken, f)));
      for (const p of found) if (await keep(db, mediaDir, r.id, p, f)) added++;
      if (found.length) log(`  ${r.name}: ${found.length} photo(s)`);
    } catch (err) {
      log(`  ${r.name}: skipped (${(err as Error).message})`);
    }
    await db.query('update restaurants set photos_checked_at = now() where id = $1', [r.id]);
    if (opts.pauseMs) await new Promise((res) => setTimeout(res, opts.pauseMs));
  }
  return { checked: rows.length, added };
}

// ---------- Google Maps photos, live and never stored ----------

export interface GooglePhoto {
  url: string;
  authors: { name: string; uri: string | null }[];
}

interface GoogleState {
  day: string;
  used: number;
  cache: Map<string, { at: number; photos: GooglePhoto[] }>;
}
const google: GoogleState = { day: '', used: 0, cache: new Map() };

/**
 * Up to three Google Maps photos of a place, via the Places API (New), when the server has
 * GOOGLE_PLACES_API_KEY. Only the place id is stored (Google allows that); photo links are
 * kept in memory for an hour so repeat views don't cost again. A daily cap keeps the bill
 * inside Google's free monthly allowance. Google requires the photo authors to be shown,
 * and these photos must not be shown on a non-Google map, so the app uses them on the
 * place page only.
 */
export async function googlePlacePhotos(
  db: Db,
  place: { id: string; name: string; locality: string | null; city: string; lat: number; lng: number; googlePlaceId: string | null },
  opts: { apiKey: string; dailyLimit: number; fetchImpl?: Fetch },
): Promise<GooglePhoto[]> {
  const f = opts.fetchImpl ?? fetch;
  const hit = google.cache.get(place.id);
  if (hit && Date.now() - hit.at < 3600_000) return hit.photos;
  const today = new Date().toISOString().slice(0, 10);
  if (google.day !== today) {
    google.day = today;
    google.used = 0;
  }
  if (google.used >= opts.dailyLimit) return [];
  const headers = { 'X-Goog-Api-Key': opts.apiKey, 'content-type': 'application/json' };

  let placeId = place.googlePlaceId;
  if (!placeId) {
    // Text Search asking only for ids, which Google doesn't bill as a full search.
    const res = await f('https://places.googleapis.com/v1/places:searchText', {
      method: 'POST',
      headers: { ...headers, 'X-Goog-FieldMask': 'places.id' },
      body: JSON.stringify({
        textQuery: [place.name, place.locality, place.city].filter(Boolean).join(', '),
        locationBias: { circle: { center: { latitude: place.lat, longitude: place.lng }, radius: 150 } },
        pageSize: 1,
      }),
    });
    if (!res.ok) return [];
    placeId = ((await res.json()) as { places?: { id: string }[] }).places?.[0]?.id ?? null;
    if (!placeId) return [];
    await db.query('update restaurants set google_place_id = $2 where id = $1', [place.id, placeId]);
  }
  const details = await f(`https://places.googleapis.com/v1/places/${encodeURIComponent(placeId)}`, {
    headers: { ...headers, 'X-Goog-FieldMask': 'photos' },
  });
  if (!details.ok) return [];
  const list = ((await details.json()) as { photos?: { name: string; authorAttributions?: { displayName?: string; uri?: string }[] }[] }).photos ?? [];
  const photos: GooglePhoto[] = [];
  for (const p of list.slice(0, 3)) {
    if (google.used >= opts.dailyLimit) break;
    google.used++;
    const media = await f(
      `https://places.googleapis.com/v1/${p.name}/media?maxWidthPx=1080&skipHttpRedirect=true&key=${encodeURIComponent(opts.apiKey)}`,
    );
    if (!media.ok) continue;
    const uri = ((await media.json()) as { photoUri?: string }).photoUri;
    if (!uri) continue;
    photos.push({
      url: uri,
      authors: (p.authorAttributions ?? []).map((a) => ({ name: a.displayName ?? 'Google Maps user', uri: a.uri ?? null })),
    });
  }
  google.cache.set(place.id, { at: Date.now(), photos });
  return photos;
}

/** For tests. */
export function resetGoogleState() {
  google.day = '';
  google.used = 0;
  google.cache.clear();
}
