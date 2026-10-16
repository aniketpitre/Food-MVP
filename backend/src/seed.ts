import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Db } from './db.js';
import { storePostImage } from './media.js';
import * as data from './seed-data.js';

const imagesDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../seed/images');

/** Inserts cities, categories, aliases and the demo restaurants/dishes/photos once. */
export async function seedCatalog(db: Db, mediaDir: string, log: (m: string) => void = () => {}): Promise<boolean> {
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

  const nashik = cityIds.get('nashik')!;
  for (const r of data.restaurants) {
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
         values ($1, $2, $3, $4, $5, $6, $7, $8, 'seed', 'FoodFeed demo')`,
        [postId, dishId, d.caption, stored.imagePath, stored.thumbPath, stored.placeholderColor, stored.width, stored.height],
      );
      await db.query('update dishes set cover_post_id = $2 where id = $1', [dishId, postId]);
    }
  }
  log(`seed: catalog inserted (${data.restaurants.length} places)`);
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
  await db.query('select refresh_dish_stats()');
  await db.query('select refresh_people_pick()');
  log('seed: demo activity inserted (40 demo_ accounts)');
  return true;
}

export async function removeDemoActivity(db: Db): Promise<number> {
  const { rowCount } = await db.query('delete from profiles where is_demo');
  await db.query('select refresh_dish_stats()');
  await db.query('select refresh_people_pick()');
  return rowCount ?? 0;
}
