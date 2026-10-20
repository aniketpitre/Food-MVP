import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Db } from './db.js';
import { deletePostImages, storePostImage } from './media.js';
import * as data from './seed-data.js';

export const imagesDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../seed/images');

export interface DemoCredit { title: string; author: string; licence: string; licenceUrl?: string | null; source: string }

/** Who took each bundled demo photo (seed/images/credits.json), by dish key. */
export async function demoCredits(dir = imagesDir): Promise<Record<string, DemoCredit>> {
  return JSON.parse(await fs.readFile(path.join(dir, 'credits.json'), 'utf8').catch(() => '{}'));
}

/** The line shown under a demo photo. */
export const creditLine = (c: DemoCredit | undefined) =>
  c ? `Photo: ${c.author}, ${c.licence}, via Wikimedia Commons` : 'WorthAbite demo';

/**
 * Brings the dish vocabulary up to date on any server: categories added since it was set up
 * (matched by slug, existing ones keep their ids and order), and every spelling people type.
 * Then dishes posted without a category get one from their name. Runs on every start-up.
 */
export async function syncVocabulary(db: Db, log: (m: string) => void = () => {}): Promise<void> {
  const catIds = new Map<string, number>();
  for (const [i, c] of data.categories.entries()) {
    const { rows } = await db.query<{ id: number }>(
      `insert into dish_categories (slug, name, emoji, sort) values ($1, $2, $3, $4)
       on conflict (slug) do update set name = excluded.name, emoji = excluded.emoji
       returning id`,
      [c.slug, c.name, c.emoji, i],
    );
    catIds.set(c.slug, rows[0]!.id);
  }
  const values: string[] = [];
  const params: unknown[] = [];
  for (const [canonical, cat, spellings] of data.aliases) {
    for (const sp of spellings) {
      params.push(catIds.get(cat), canonical, sp);
      values.push(`($${params.length - 2}::int, $${params.length - 1}, norm_name($${params.length}))`);
    }
  }
  await db.query(
    `insert into dish_aliases (category_id, canonical, alias_norm) values ${values.join(', ')} on conflict do nothing`,
    params,
  );
  const n = await categoriseDishes(db);
  if (n > 0) log(`vocabulary: gave ${n} dishes a category from their name`);
}

/**
 * The category a dish name belongs to, from the spellings people type: an exact spelling, a
 * spelling inside the name ("Special Kolhapuri Misal"), or a close match. Null if unsure.
 */
export const CATEGORY_FOR_NAME_SQL = `
  (select a.category_id from dish_aliases a
   where a.category_id is not null
     and (a.alias_norm = norm_name($1)
          or (' ' || norm_name($1) || ' ') like ('% ' || a.alias_norm || ' %')
          or similarity(a.alias_norm, norm_name($1)) >= 0.55)
   order by (a.alias_norm = norm_name($1)) desc, length(a.alias_norm) desc, similarity(a.alias_norm, norm_name($1)) desc
   limit 1)`;

/** Fills in the category of dishes that have none, from their names. Returns how many. */
export async function categoriseDishes(db: Db): Promise<number> {
  const { rowCount } = await db.query(
    `update dishes d set category_id = c.category_id
     from (select d2.id, ${CATEGORY_FOR_NAME_SQL.replaceAll('$1', 'd2.name')} as category_id
           from dishes d2 where d2.category_id is null) c
     where d.id = c.id and c.category_id is not null`,
  );
  return rowCount ?? 0;
}

/** Inserts cities, categories, aliases and the demo restaurants/dishes/photos once. */
export async function seedCatalog(
  db: Db,
  mediaDir: string,
  log: (m: string) => void = () => {},
  opts: { demoPlaces?: boolean } = {},
): Promise<boolean> {
  const { rows } = await db.query<{ n: number }>('select count(*)::int as n from cities');
  if (rows[0]!.n > 0) return false;

  const cityIds = new Map<string, number>();
  for (const c of data.cities) {
    const r = await db.query<{ id: number }>(
      `insert into cities (slug, name, state, center, radius_km, is_active)
       values ($1, $2, $3, st_setsrid(st_makepoint($5, $4), 4326)::geography, $6, $7) returning id`,
      [c.slug, c.name, c.state, c.lat, c.lng, c.radiusKm, c.active],
    );
    cityIds.set(c.slug, r.rows[0]!.id);
  }

  const catIds = new Map<string, number>();
  for (const [i, c] of data.categories.entries()) {
    const r = await db.query<{ id: number }>(
      'insert into dish_categories (slug, name, emoji, sort) values ($1, $2, $3, $4) returning id',
      [c.slug, c.name, c.emoji, i],
    );
    catIds.set(c.slug, r.rows[0]!.id);
  }

  for (const [canonical, cat, spellings] of data.aliases) {
    for (const s of spellings) {
      await db.query(
        'insert into dish_aliases (category_id, canonical, alias_norm) values ($1, $2, norm_name($3)) on conflict do nothing',
        [catIds.get(cat), canonical, s],
      );
    }
  }

  // A real server starts with real places only (imported from Overture); the invented demo
  // places come only with DEMO_ACTIVITY on.
  if (opts.demoPlaces ?? true) {
    await insertDemoPlaces(db, mediaDir, cityIds.get('nashik')!, catIds, log);
    log(`seed: catalog inserted (${data.restaurants.length} demo places)`);
  } else {
    log('seed: cities, dish types and spellings inserted (no demo places)');
  }
  return true;
}

/** The invented demo places in Nashik, with their dishes and the illustrated demo photos. */
async function insertDemoPlaces(
  db: Db,
  mediaDir: string,
  nashik: number,
  catIds: Map<string, number>,
  log: (m: string) => void,
  skip: Set<string> = new Set(),
) {
  const credits = await demoCredits();
  for (const r of data.restaurants) {
    if (skip.has(r.name)) continue;
    const rr = await db.query<{ id: string }>(
      `insert into restaurants (city_id, name, name_norm, locality, geo, price_band, status, source)
       values ($1, $2, norm_name($2), $3, st_setsrid(st_makepoint($5, $4), 4326)::geography, $6, 'active', 'seed') returning id`,
      [nashik, r.name, r.locality, r.lat, r.lng, r.priceBand],
    );
    const restaurantId = rr.rows[0]!.id;
    for (const d of r.dishes) {
      const dr = await db.query<{ id: string }>(
        `insert into dishes (restaurant_id, city_id, category_id, name, name_norm, price_inr, is_veg, featured_rank)
         values ($1, $2, $3, $4, norm_name($4), $5, $6, $7) returning id`,
        [restaurantId, nashik, catIds.get(d.category), d.name, d.price, d.veg, d.featured ?? null],
      );
      const dishId = dr.rows[0]!.id;
      const file = path.join(imagesDir, `${d.key}.jpg`);
      const image = await fs.readFile(file).catch(() => null);
      if (!image) {
        log(`seed: no image for ${d.key}, dish will stay out of the feed until someone posts a photo`);
        continue;
      }
      const postId = crypto.randomUUID();
      const stored = await storePostImage(mediaDir, postId, image);
      await db.query(
        `insert into posts (id, dish_id, caption, image_path, thumb_path, placeholder_color, width, height,
                            source, photo_credit)
         values ($1, $2, $3, $4, $5, $6, $7, $8, 'seed', $9)`,
        [postId, dishId, d.caption, stored.imagePath, stored.thumbPath, stored.placeholderColor, stored.width, stored.height, creditLine(credits[d.key])],
      );
      await db.query('update dishes set cover_post_id = $2 where id = $1', [dishId, postId]);
    }
  }
}

/**
 * Puts the demo places, dishes and photos back (after `remove-demo --all`), if none are
 * there. Returns false when they already exist.
 */
export async function restoreDemoPlaces(db: Db, mediaDir: string, log: (m: string) => void = () => {}): Promise<boolean> {
  // Demo places still here (kept because real people posted there) aren't added twice.
  const { rows } = await db.query<{ name: string }>("select name from restaurants where source = 'seed'");
  const present = new Set(rows.map((r) => r.name));
  if (data.restaurants.every((r) => present.has(r.name))) return false;
  const { rows: city } = await db.query<{ id: number }>("select id from cities where slug = 'nashik'");
  if (!city[0]) return false;
  const { rows: cats } = await db.query<{ id: number; slug: string }>('select id, slug from dish_categories');
  await insertDemoPlaces(db, mediaDir, city[0].id, new Map(cats.map((c) => [c.slug, c.id])), log, present);
  log(`seed: demo places restored (${data.restaurants.length} places)`);
  return true;
}

/** Small deterministic random generator so demo data is the same on every machine. */
function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * DEMO ONLY. Creates 40 clearly labelled demo accounts (handles start with "demo_")
 * and gives them recommendations, saves and shares so the Top, Trending and Hidden
 * gems tabs have something to show. Never enable this for real users: it is exactly
 * the fake endorsement the plan forbids. Remove with removeDemoActivity().
 */
export async function seedDemoActivity(db: Db, log: (m: string) => void = () => {}): Promise<boolean> {
  if (await demoRetired(db)) return false;
  const { rows } = await db.query<{ n: number }>('select count(*)::int as n from profiles where is_demo');
  if (rows[0]!.n > 0) return false;

  const rand = rng(20261015);
  // Separate stream for the "not for me" votes, so the loves above stay exactly as before.
  const passRand = rng(424242);
  const users: string[] = [];
  const names = ['aarav', 'diya', 'kabir', 'isha', 'vihaan', 'anaya', 'reyansh', 'myra', 'arjun', 'saanvi'];
  for (let i = 0; i < 40; i++) {
    const handle = `demo_${names[i % names.length]}${Math.floor(i / names.length) + 1}`;
    const r = await db.query<{ id: string }>(
      `insert into profiles (email, password_hash, handle, display_name, is_demo, created_at)
       values ($1, 'disabled', $2, $3, true, now() - interval '60 days') returning id`,
      [`${handle}@demo.invalid`, handle, `Demo ${names[i % names.length]!.replace(/^./, (c) => c.toUpperCase())}`],
    );
    users.push(r.rows[0]!.id);
  }

  const keyToDish = new Map<string, string>();
  for (const r of data.restaurants) {
    for (const d of r.dishes) {
      const q = await db.query<{ id: string }>(
        'select d.id from dishes d join restaurants r on r.id = d.restaurant_id where r.name = $1 and d.name = $2',
        [r.name, d.name],
      );
      if (q.rows[0]) keyToDish.set(d.key, q.rows[0].id);
    }
  }

  for (const [key, pop] of Object.entries(data.demoPopularity)) {
    const dishId = keyToDish.get(key);
    if (!dishId) continue;
    for (const user of users) {
      if (rand() >= pop) {
        // A few people tried it and it wasn't for them, so ratings aren't all 100%.
        if (passRand() < 0.1) {
          for (const kind of ['tried', 'pass']) {
            await db.query(
              `insert into engagements (user_id, dish_id, kind, created_at)
               values ($1, $2, $3, now() - make_interval(days => $4)) on conflict do nothing`,
              [user, dishId, kind, Math.floor(passRand() * 30)],
            );
          }
        }
        continue;
      }
      const daysAgo = Math.floor(rand() * 30);
      const kinds = ['recommend'];
      if (rand() < 0.45) kinds.push('save');
      if (rand() < 0.3) kinds.push('tried');
      if (rand() < 0.12) kinds.push('share');
      for (const kind of kinds) {
        await db.query(
          `insert into engagements (user_id, dish_id, kind, created_at)
           values ($1, $2, $3, now() - make_interval(days => $4, hours => $5)) on conflict do nothing`,
          [user, dishId, kind, daysAgo, Math.floor(rand() * 20)],
        );
      }
    }
  }
  await db.query('select refresh_rankings_now()');
  log('seed: demo activity inserted (40 demo_ accounts)');
  return true;
}

/** Whether the demo was retired from the dashboard: then it is never seeded again on start-up. */
export async function demoRetired(db: Pick<Db, 'query'>): Promise<boolean> {
  const { rowCount } = await db.query("select 1 from app_flags where key = 'demo_retired'");
  return !!rowCount;
}

export async function setDemoRetired(db: Pick<Db, 'query'>, retired: boolean): Promise<void> {
  if (retired) {
    await db.query(
      `insert into app_flags (key, value) values ('demo_retired', 'true')
       on conflict (key) do update set value = 'true', updated_at = now()`,
    );
  } else {
    await db.query("delete from app_flags where key = 'demo_retired'");
  }
}

export async function removeDemoActivity(db: Db): Promise<number> {
  // Retired for good: a restart (with DEMO_ACTIVITY on) won't make the demo accounts again.
  await setDemoRetired(db, true);
  const { rowCount } = await db.query('delete from profiles where is_demo');
  await db.query('select refresh_rankings_now()');
  await db.query('select clean_orphans()');
  return rowCount ?? 0;
}

/**
 * Removes the invented demo places with their dishes and illustrated "DEMO IMAGE" photos
 * (everything seeded with source = 'seed'). Cities, dish types and spellings stay. Real
 * places (Overture, people's) are untouched, and so is a demo place where a real person
 * posted a photo: it is kept, and named in [kept], so no one's post is lost.
 */
export async function removeDemoCatalog(
  db: Db,
  mediaDir: string,
  opts: { everything?: boolean } = {},
): Promise<{ places: number; photos: number; kept: string[] }> {
  // everything: no exceptions, test posts people made at demo places go too.
  const realPost = opts.everything
    ? 'false'
    : `exists (select 1 from posts p join dishes d on d.id = p.dish_id join profiles a on a.id = p.author_id
               where d.restaurant_id = r.id and not a.is_demo)`;
  const { rows: kept } = await db.query<{ name: string }>(`select r.name from restaurants r where r.source = 'seed' and ${realPost} order by r.name`);
  const { rows: posts } = await db.query<{ id: string }>(
    `select p.id from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
     where r.source = 'seed' and not ${realPost}`,
  );
  const { rowCount } = await db.query(`delete from restaurants r where r.source = 'seed' and not ${realPost}`);
  for (const p of posts) await deletePostImages(mediaDir, p.id);
  await db.query('select refresh_rankings_now()');
  await db.query('select clean_orphans()');
  return { places: rowCount ?? 0, photos: posts.length, kept: kept.map((k) => k.name) };
}
