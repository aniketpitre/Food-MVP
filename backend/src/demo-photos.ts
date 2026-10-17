import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import type { Db } from './db.js';
import { storePostImage } from './media.js';
import * as data from './seed-data.js';
import { creditLine, demoCredits, imagesDir } from './seed.js';

/**
 * Real photos for the demo dishes, so a burger looks like a burger. The photos ship with
 * the server in seed/images (chosen by hand from Wikimedia Commons: CC0, public domain,
 * CC BY or CC BY-SA only), with their authors and licences in seed/images/credits.json.
 * Never photos from Google, Zomato, Swiggy or Instagram: those belong to the people who
 * took them.
 *
 * Servers seeded before the real photos existed (or before a photo was re-cropped) still
 * show the old picture; this swaps each demo post's picture for the bundled photo when the
 * photo or its credit differs. Needs no internet.
 */
export async function refreshDemoDishPhotos(
  db: Db,
  mediaDir: string,
  log: (m: string) => void = () => {},
  dir = imagesDir,
): Promise<{ updated: number; missing: string[] }> {
  const credits = await demoCredits(dir);
  const keyOf = new Map<string, string>();
  for (const r of data.restaurants) for (const d of r.dishes) keyOf.set(`${r.name}|${d.name}`, d.key);

  const { rows } = await db.query<{ post_id: string; dish: string; place: string; photo_credit: string | null; image_path: string }>(
    `select p.id as post_id, d.name as dish, r.name as place, p.photo_credit, p.image_path
     from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
     where r.source = 'seed' and p.source = 'seed'`,
  );
  let updated = 0;
  const missing: string[] = [];
  for (const row of rows) {
    const key = keyOf.get(`${row.place}|${row.dish}`);
    const credit = key ? credits[key] : undefined;
    if (!key || !credit) {
      missing.push(row.dish);
      continue;
    }
    const line = creditLine(credit);
    const image = await fs.readFile(path.join(dir, `${key}.jpg`)).catch(() => null);
    if (!image) {
      missing.push(row.dish);
      continue;
    }
    // The photo's own fingerprint goes in its address, so a re-cropped photo is noticed
    // (and phones fetch it afresh) even when its credit is the same.
    const v = `?v=${crypto.createHash('sha1').update(image).digest('hex').slice(0, 10)}`;
    if (row.photo_credit === line && row.image_path.endsWith(v)) continue;
    const stored = await storePostImage(mediaDir, row.post_id, image);
    await db.query(
      `update posts set image_path = $2, thumb_path = $3, placeholder_color = $4, width = $5, height = $6, photo_credit = $7
       where id = $1`,
      [row.post_id, stored.imagePath + v, stored.thumbPath + v, stored.placeholderColor, stored.width, stored.height, line],
    );
    updated++;
    log(`demo photo: ${row.dish} at ${row.place} <- ${credit.title}`);
  }
  return { updated, missing };
}
