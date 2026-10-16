import type { Db } from './db.js';
import { storePostImage } from './media.js';
import { commonsPhoto, type Fetch } from './photos.js';
import * as data from './seed-data.js';

/**
 * Real photos for the demo dishes, so a burger looks like a burger. Each dish gets the lead
 * photo of its Wikipedia article, taken only when that file lives on Wikimedia Commons
 * (free licences: CC BY, CC BY-SA or public domain), with its author and licence kept as
 * the photo credit. Never photos from Google, Zomato, Swiggy or Instagram: those belong to
 * the people who took them.
 */
export const demoDishArticles: Record<string, string> = {
  ananda_sabudana_khichdi: 'Sabudana khichdi',
  ananda_cold_coffee: 'Iced coffee',
  ananda_masala_dosa: 'Masala dosa',
  godavari_kolhapuri_misal: 'Misal pav',
  godavari_misal_pav: 'Misal pav',
  godavari_kanda_bhaji: 'Pakora',
  bhurji_egg_bhurji_pav: 'Egg bhurji',
  bhurji_anda_ghotala: 'Omelette',
  tandoor_chicken_biryani: 'Hyderabadi biryani',
  tandoor_paneer_tikka: 'Paneer tikka',
  annapurna_thali: 'Thali',
  annapurna_puran_poli: 'Puran poli',
  katta_cutting_chai: 'Masala chai',
  katta_bun_maska: 'Bun maska',
  katta_vada_pav: 'Vada pav',
  wok_schezwan_rice: 'Fried rice',
  wok_chilli_chicken: 'Chilli chicken',
  wok_manchurian: 'Manchurian (dish)',
  slice_margherita: 'Pizza Margherita',
  slice_penne: "Penne all'arrabbiata",
  sangam_jalebi: 'Jalebi',
  sangam_rabdi: 'Rabri',
  sangam_shrikhand: 'Shrikhand',
  barn_veg_burger: 'Veggie burger',
  barn_chicken_burger: 'Hamburger',
  udupi_rava_dosa: 'Dosa (food)',
  udupi_idli: 'Idli',
  udupi_filter_coffee: 'Indian filter coffee',
  pbp_pav_bhaji: 'Pav bhaji',
  pbp_tawa_pulao: 'Pulao',
};

const UA = { 'user-agent': 'FoodFeed/1.0 (self-hosted food app; photo attribution kept)' };

/** The lead image of an English Wikipedia article, as "File:Name.jpg", if it has one. */
export async function wikipediaLeadFile(article: string, f: Fetch = fetch): Promise<string | null> {
  const url = new URL('https://en.wikipedia.org/w/api.php');
  url.search = new URLSearchParams({
    action: 'query', format: 'json', formatversion: '2', redirects: '1',
    prop: 'pageimages', piprop: 'name', titles: article,
  }).toString();
  const res = await f(url, { headers: UA });
  if (!res.ok) return null;
  const json = (await res.json()) as { query?: { pages?: { pageimage?: string }[] } };
  const name = json.query?.pages?.[0]?.pageimage;
  return name ? `File:${name.replace(/_/g, ' ')}` : null;
}

/**
 * Replaces the illustrated demo photos with real, credited ones. Only touches demo posts
 * (source = 'seed') that still carry the illustration, unless [force]. The new picture is
 * written over the old files and its address gets a version, so phones fetch it afresh.
 */
export async function fetchDemoDishPhotos(
  db: Db,
  mediaDir: string,
  log: (m: string) => void = () => {},
  opts: { force?: boolean; f?: Fetch } = {},
): Promise<{ updated: number; missing: string[] }> {
  const f = opts.f ?? fetch;
  const keyOf = new Map<string, string>();
  for (const r of data.restaurants) for (const d of r.dishes) keyOf.set(`${r.name}|${d.name}`, d.key);

  const { rows } = await db.query<{ post_id: string; dish: string; place: string }>(
    `select p.id as post_id, d.name as dish, r.name as place
     from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
     where r.source = 'seed' and p.source = 'seed' and ($1 or p.photo_credit = 'FoodFeed demo')`,
    [!!opts.force],
  );
  let updated = 0;
  const missing: string[] = [];
  const found = new Map<string, Awaited<ReturnType<typeof commonsPhoto>>>();
  for (const row of rows) {
    const key = keyOf.get(`${row.place}|${row.dish}`);
    const article = key ? demoDishArticles[key] : undefined;
    if (!article) {
      missing.push(row.dish);
      continue;
    }
    try {
      if (!found.has(article)) {
        const file = await wikipediaLeadFile(article, f);
        found.set(article, file ? await commonsPhoto(file, f) : null);
      }
      const photo = found.get(article);
      if (!photo) {
        missing.push(row.dish);
        continue;
      }
      const res = await f(photo.url, { headers: UA });
      if (!res.ok) {
        missing.push(row.dish);
        continue;
      }
      const stored = await storePostImage(mediaDir, row.post_id, Buffer.from(await res.arrayBuffer()));
      const v = `?v=${Date.now().toString(36)}`;
      await db.query(
        `update posts set image_path = $2, thumb_path = $3, placeholder_color = $4, width = $5, height = $6, photo_credit = $7
         where id = $1`,
        [row.post_id, stored.imagePath + v, stored.thumbPath + v, stored.placeholderColor, stored.width, stored.height, photo.attribution],
      );
      updated++;
      log(`demo photo: ${row.dish} at ${row.place} ← ${photo.ref}`);
    } catch (e) {
      missing.push(row.dish);
      log(`demo photo: ${row.dish} failed (${(e as Error).message})`);
    }
    await new Promise((r) => setTimeout(r, opts.f ? 0 : 300)); // gentle on Wikimedia
  }
  return { updated, missing };
}
