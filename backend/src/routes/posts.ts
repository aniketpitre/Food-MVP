import { notifyFollowers } from '../notify.js';
import crypto from 'node:crypto';
import { nearestCity } from '../geo.js';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';
import { deletePostImages, photoFingerprint, storePostImage } from '../media.js';
import { scheduleTrustRefresh } from '../ranking.js';
import { CATEGORY_FOR_NAME_SQL } from '../seed.js';

const postFields = z
  .object({
    idempotencyKey: z.string().min(8).max(64),
    dishName: z.string().trim().min(2).max(60),
    categoryId: z.coerce.number().int().positive().optional(),
    restaurantId: z.string().uuid().optional(),
    newPlaceName: z.string().trim().min(2).max(80).optional(),
    newPlaceLocality: z.string().trim().max(60).optional(),
    newPlaceLat: z.coerce.number().min(-90).max(90).optional(),
    newPlaceLng: z.coerce.number().min(-180).max(180).optional(),
    caption: z.string().trim().max(280).optional(),
    priceInr: z.coerce.number().int().min(1).max(20000).optional(),
    isVeg: z.enum(['true', 'false']).optional(),
    isSponsored: z.enum(['true', 'false']).default('false'),
    sponsorNote: z.string().trim().max(120).optional(),
    instagramUrl: z
      .string()
      .trim()
      .regex(/^https:\/\/(www\.)?instagram\.com\/(p|reel)\/[A-Za-z0-9_-]+\/?(\?.*)?$/, 'must be an Instagram post link')
      .optional(),
    // "Worth a bite?": posting is also your vote on the dish.
    verdict: z.enum(['loved', 'pass']).optional(),
    // How the photo was taken, when (as the phone's photo says), and where the phone was.
    capture: z.enum(['camera', 'gallery']).optional(),
    takenAt: z.string().datetime({ offset: true }).optional(),
    lat: z.coerce.number().min(-90).max(90).optional(),
    lng: z.coerce.number().min(-180).max(180).optional(),
  })
  .refine((f) => f.restaurantId || (f.newPlaceName && f.newPlaceLat !== undefined && f.newPlaceLng !== undefined), {
    message: 'pick a place or add a new one with its location',
  });

export async function postRoutes(app: FastifyInstance) {
  const { db, config } = app;

  // Create a post: one photo of one dish at one place. The dish is created if it does
  // not exist yet at that place (matched by normalised name). A brand-new place starts
  // as "pending" and appears in the feed once an admin approves it.
  app.post('/posts', { preHandler: requireAuth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const me = userId(req)!;
    const fields: Record<string, string> = {};
    let image: Buffer | undefined;
    for await (const part of req.parts()) {
      if (part.type === 'file') {
        if (part.fieldname !== 'image') {
          part.file.resume();
          continue;
        }
        image = await part.toBuffer();
      } else {
        fields[part.fieldname] = String(part.value);
      }
    }
    const f = parse(postFields, fields, reply);
    if (!f) return;

    const existing = await db.query('select id from posts where author_id = $1 and idempotency_key = $2', [me, f.idempotencyKey]);
    if (existing.rows[0]) return reply.code(200).send(await postJson(existing.rows[0].id));

    if (!image) return reply.code(400).send({ error: 'invalid_input', message: 'Add a photo of the dish.' });

    // Uploads are counted, not posts, so deleting a post doesn't give back a slot.
    const { rows: limit } = await db.query<{ n: number; max: string }>(
      `select (select count(*)::int from post_uploads where author_id = $1 and created_at > now() - interval '24 hours') as n,
              cfg('posts_per_day') as max`,
      [me],
    );
    if (limit[0]!.n >= Number(limit[0]!.max)) {
      return reply.code(429).send({ error: 'daily_limit', message: 'You have reached today’s posting limit.' });
    }

    const postId = crypto.randomUUID();
    let stored;
    let fingerprint: string | null;
    try {
      stored = await storePostImage(config.mediaDir, postId, image);
      fingerprint = await photoFingerprint(image);
    } catch (err) {
      await deletePostImages(config.mediaDir, postId);
      const msg = (err as Error).message === 'image_too_small' ? 'That photo is too small.' : 'That file is not a photo we can read.';
      return reply.code(400).send({ error: 'bad_image', message: msg });
    }

    // The same photo twice: yours is turned away; someone else's waits for a person to look.
    // Only photos sharing a fingerprint band can be that close (see phash_bands), so the
    // index narrows it to a few candidates before the exact bit count. A photo with no
    // author (an imported one) is never "yours".
    const { rows: copies } = await db.query<{ id: string; author_id: string | null; mine: boolean }>(
      `select p.id, p.author_id, p.author_id is not distinct from $2 as mine from posts p
       where $1::bigint is not null and p.ph_bands && $3::int[] and p.status <> 'hidden'
         and phash_distance(p.phash, $1::bigint) <= cfg('copy_distance')
       order by p.author_id is not distinct from $2 desc, p.created_at limit 1`,
      [fingerprint, me, fingerprint === null ? [] : phashBands(fingerprint)],
    );
    if (copies[0]?.mine) {
      await deletePostImages(config.mediaDir, postId);
      return reply.code(409).send({ error: 'duplicate_photo', message: "You've already posted this photo." });
    }
    const copyOf = copies[0]?.id ?? null;

    let published: { dishId: string; cityId: number; restaurantId: string; held: boolean } | null = null;
    const client = await db.connect();
    try {
      await client.query('begin');
      let restaurantId = f.restaurantId;
      let cityId: number;
      if (restaurantId) {
        const { rows } = await client.query<{ city_id: number }>(
          "select city_id from restaurants where id = $1 and (status = 'active' or created_by = $2)",
          [restaurantId, me],
        );
        if (!rows[0]) throw Object.assign(new Error('That place is not available.'), { statusCode: 404, code: 'not_found' });
        cityId = rows[0].city_id;
      } else {
        const city = await nearestCity(client, f.newPlaceLat!, f.newPlaceLng!);
        if (!city) {
          throw Object.assign(new Error('That location is outside the cities we cover.'), { statusCode: 400, code: 'outside_city' });
        }
        cityId = city.id;
        const { rows } = await client.query<{ id: string }>(
          `insert into restaurants (city_id, name, name_norm, locality, geo, status, created_by)
           values ($1, $2, norm_name($2), $3, st_setsrid(st_makepoint($5, $4), 4326)::geography, 'pending', $6)
           returning id`,
          [cityId, f.newPlaceName, f.newPlaceLocality || null, f.newPlaceLat, f.newPlaceLng, me],
        );
        restaurantId = rows[0]!.id;
      }

      // A verified visit: the phone was at the place, and the photo was taken there and then
      // (with the camera, or a gallery photo from the last few days). Both come from the
      // phone, so they're a strong hint, not proof; copies and reports catch the rest.
      const { rows: vis } = await client.query<{ near: boolean | null; fresh: boolean }>(
        `select case when $2::float8 is null or $3::float8 is null then null
                     else st_dwithin(r.geo, st_setsrid(st_makepoint($3, $2), 4326)::geography, cfg('visit_radius_m')::float8) end as near,
                ($4 = 'camera' or ($5::timestamptz is not null
                   and $5::timestamptz between now() - make_interval(days => cfg('photo_fresh_days')::int) and now() + interval '1 hour')) as fresh
         from restaurants r where r.id = $1`,
        [restaurantId, f.lat ?? null, f.lng ?? null, f.capture ?? null, f.takenAt ?? null],
      );
      const visit = vis[0]?.near ? (vis[0].fresh ? 'verified' : 'nearby') : null;

      // A new place goes live at once when someone established (a week old) posts from it with
      // a verified visit, and nothing with a similar name is already there; otherwise it waits
      // for a person to look. Either way the post shows to followers straight away.
      if (!f.restaurantId && visit === 'verified') {
        await client.query(
          `update restaurants r set status = 'active'
           where r.id = $1
             and (select created_at < now() - make_interval(days => greatest(cfg('standing_ramp_days'), 0)::int) from profiles where id = $2)
             and not exists (select 1 from restaurants o where o.id <> r.id and o.status <> 'hidden'
                               and st_dwithin(o.geo, r.geo, 150) and similarity(o.name_norm, r.name_norm) >= 0.35)`,
          [restaurantId, me],
        );
      }

      // A typo of a dish already here ("Buter Chiken" for "Butter Chicken") joins that dish
      // instead of splitting its votes, when no dish has exactly this name. Trigram
      // similarity alone can't tell a typo from a longer name ("Paneer Tikka Masala" is
      // closer to "Paneer Tikka" than "Buter Chiken" is to "Butter Chicken"), so it also
      // takes a small edit distance: about one letter in six.
      const { rows: typo } = await client.query<{ id: string }>(
        `select d.id from dishes d, (select norm_name($2) as t) n
         where d.restaurant_id = $1 and d.status = 'live'
           and not exists (select 1 from dishes x where x.restaurant_id = $1 and x.name_norm = n.t)
           and similarity(d.name_norm, n.t) >= 0.5
           and levenshtein(d.name_norm, n.t) <= greatest(1, length(n.t) / 6)
         order by levenshtein(d.name_norm, n.t), similarity(d.name_norm, n.t) desc, d.created_at
         limit 1`,
        [restaurantId, f.dishName],
      );
      const { rows: dish } = typo[0] ? { rows: typo } : await client.query<{ id: string }>(
        // No category picked: the name tells us ("Kolhapuri Misal" is Misal).
        `insert into dishes (restaurant_id, city_id, category_id, name, name_norm, price_inr, is_veg, created_by)
         values ($1, $2, coalesce($3::int, ${CATEGORY_FOR_NAME_SQL.replaceAll('$1', '$4')}), $4, norm_name($4), $5, $6, $7)
         on conflict (restaurant_id, name_norm) do update
           set price_inr   = coalesce(dishes.price_inr, excluded.price_inr),
               is_veg      = coalesce(dishes.is_veg, excluded.is_veg),
               category_id = coalesce(dishes.category_id, excluded.category_id)
         returning id`,
        [restaurantId, cityId, f.categoryId ?? null, f.dishName, f.priceInr ?? null,
         f.isVeg === undefined ? null : f.isVeg === 'true', me],
      );
      // A name merged into another dish posts to that dish. A dish an admin hid (or reports
      // took down) stays down: posting it again doesn't bring it back.
      const { rows: target } = await client.query<{ id: string; status: string }>(
        `select d.id, d.status from dishes d
         where d.id = coalesce((select into_id from dish_merges where from_id = $1), $1)`,
        [dish[0]!.id],
      );
      if (!target[0] || target[0].status === 'hidden') {
        throw Object.assign(new Error('That dish is not available at this place.'), { statusCode: 409, code: 'dish_unavailable' });
      }
      const dishId = target[0].id;

      // One photo of a dish per person per day: many photos of one plate are not many votes.
      // Counted from uploads (deleting a post doesn't free the slot), on the dish it lands
      // on, including names merged into it.
      const { rows: today } = await client.query<{ n: number; max: string }>(
        `select count(*)::int as n, cfg('posts_per_dish_day') as max
         from post_uploads u
         where u.author_id = $1 and u.created_at > now() - interval '24 hours'
           and (u.dish_id = $2 or u.dish_id in (select from_id from dish_merges where into_id = $2))`,
        [me, dishId],
      );
      if (today[0]!.n >= Number(today[0]!.max)) {
        throw Object.assign(new Error('You already posted this dish today. Come back tomorrow for another photo.'), {
          statusCode: 429, code: 'dish_daily_limit',
        });
      }

      await client.query(
        `insert into posts (id, dish_id, author_id, caption, image_path, thumb_path, placeholder_color,
                            width, height, source, is_sponsored, sponsor_note, instagram_url, idempotency_key,
                            status, phash, capture, taken_at, visit, copy_of)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'user', $10, $11, $12, $13, $14, $15, $16, $17, $18, $19)`,
        [postId, dishId, me, f.caption || null, stored.imagePath, stored.thumbPath, stored.placeholderColor,
         stored.width, stored.height, f.isSponsored === 'true', f.sponsorNote || null, f.instagramUrl || null,
         f.idempotencyKey, copyOf ? 'pending' : 'live', fingerprint, f.capture ?? null, f.takenAt ?? null, visit, copyOf],
      );
      await client.query('insert into post_uploads (author_id, dish_id) values ($1, $2)', [me, dishId]);
      if (!copyOf) await client.query('update dishes set cover_post_id = coalesce(cover_post_id, $2) where id = $1', [dishId, postId]);
      // Your verdict is a vote like the buttons on the dish (a "not for me" also means you
      // tried it). A held photo (a copy waiting for review) gets no credit, so its verdict
      // isn't counted: the buttons on the dish are still there.
      const verdict = copyOf ? undefined : f.verdict;
      if (verdict === 'loved') {
        await client.query("delete from engagements where user_id = $1 and dish_id = $2 and kind = 'pass'", [me, dishId]);
        await client.query("insert into engagements (user_id, dish_id, kind) values ($1, $2, 'recommend') on conflict do nothing", [me, dishId]);
      } else if (verdict === 'pass') {
        await client.query("delete from engagements where user_id = $1 and dish_id = $2 and kind = 'recommend'", [me, dishId]);
        await client.query("insert into engagements (user_id, dish_id, kind) values ($1, $2, 'pass') on conflict do nothing", [me, dishId]);
        await client.query("insert into engagements (user_id, dish_id, kind) values ($1, $2, 'tried') on conflict do nothing", [me, dishId]);
      }
      await client.query('commit');
      published = { dishId, cityId, restaurantId: restaurantId!, held: copyOf !== null };
    } catch (err) {
      await client.query('rollback');
      await deletePostImages(config.mediaDir, postId);
      throw err;
    } finally {
      client.release();
    }
    // Followers hear about it at once (they see it in Following too); phones in the city
    // check for new food near them only once the place is live.
    if (published) {
      await db.query('select refresh_dish_stats($1)', [published.dishId]);
      await db.query('select refresh_people_pick($1)', [published.dishId]);
      scheduleTrustRefresh(db, 3000, (err) => app.log.error(err));
    }
    if (published && !published.held) {
      await notifyFollowers(db, { actorId: me, kind: 'post', postId, dishId: published.dishId });
      const live = await db.query("select 1 from restaurants where id = $1 and status = 'active'", [published.restaurantId]);
      if (live.rows[0]) await app.push.tickleCity(published.cityId, me);
    }
    return reply.code(201).send(await postJson(postId));
  });

  app.delete('/posts/:id', { preHandler: requireAuth }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    if (!p) return;
    const { rows } = await db.query<{ dish_id: string }>(
      'delete from posts where id = $1 and author_id = $2 returning dish_id',
      [p.id, userId(req)],
    );
    if (!rows[0]) return reply.code(404).send({ error: 'not_found', message: 'Post not found.' });
    await deletePostImages(config.mediaDir, p.id);
    return { ok: true };
  });

  async function postJson(postId: string) {
    const { rows } = await db.query(
      `select p.id, p.dish_id as "dishId", d.name as "dishName", p.image_path as "imageUrl",
              p.thumb_path as "thumbUrl", p.status, p.is_sponsored as "isSponsored", p.visit,
              (p.copy_of is not null and p.status = 'pending') as held,
              r.id as "restaurantId", r.name as "restaurantName", r.status as "restaurantStatus"
       from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
       where p.id = $1`,
      [postId],
    );
    return rows[0];
  }
}

/**
 * The fingerprint's 8 bytes, each tagged with its position (position * 256 + byte), the
 * same as phash_bands() in the database. Two photos within a few bits of each other share
 * at least one band, so "shares a band" finds every possible copy through an index.
 */
export function phashBands(fingerprint: string): number[] {
  const h = BigInt.asUintN(64, BigInt(fingerprint));
  return Array.from({ length: 8 }, (_, i) => i * 256 + Number((h >> BigInt(8 * i)) & 255n));
}
