import crypto from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { requireAuth, userId } from '../auth.js';
import { deletePostImages, storePostImage } from '../media.js';

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

    const { rows: limit } = await db.query<{ n: number; max: string }>(
      `select (select count(*)::int from posts where author_id = $1 and created_at > now() - interval '24 hours') as n,
              cfg('posts_per_day') as max`,
      [me],
    );
    if (limit[0]!.n >= Number(limit[0]!.max)) {
      return reply.code(429).send({ error: 'daily_limit', message: 'You have reached today’s posting limit.' });
    }

    const postId = crypto.randomUUID();
    let stored;
    try {
      stored = await storePostImage(config.mediaDir, postId, image);
    } catch (err) {
      const msg = (err as Error).message === 'image_too_small' ? 'That photo is too small.' : 'That file is not a photo we can read.';
      return reply.code(400).send({ error: 'bad_image', message: msg });
    }

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
        const { rows: city } = await client.query<{ id: number }>(
          `select id from cities
           where st_dwithin(center, st_setsrid(st_makepoint($2, $1), 4326)::geography, radius_km * 1000)
           order by center <-> st_setsrid(st_makepoint($2, $1), 4326)::geography limit 1`,
          [f.newPlaceLat, f.newPlaceLng],
        );
        if (!city[0]) {
          throw Object.assign(new Error('That location is outside the cities we cover.'), { statusCode: 400, code: 'outside_city' });
        }
        cityId = city[0].id;
        const { rows } = await client.query<{ id: string }>(
          `insert into restaurants (city_id, name, name_norm, locality, geo, status, created_by)
           values ($1, $2, norm_name($2), $3, st_setsrid(st_makepoint($5, $4), 4326)::geography, 'pending', $6)
           returning id`,
          [cityId, f.newPlaceName, f.newPlaceLocality || null, f.newPlaceLat, f.newPlaceLng, me],
        );
        restaurantId = rows[0]!.id;
      }

      const { rows: dish } = await client.query<{ id: string }>(
        `insert into dishes (restaurant_id, city_id, category_id, name, name_norm, price_inr, is_veg, created_by)
         values ($1, $2, $3, $4, norm_name($4), $5, $6, $7)
         on conflict (restaurant_id, name_norm) do update
           set price_inr   = coalesce(dishes.price_inr, excluded.price_inr),
               is_veg      = coalesce(dishes.is_veg, excluded.is_veg),
               category_id = coalesce(dishes.category_id, excluded.category_id)
         returning id`,
        [restaurantId, cityId, f.categoryId ?? null, f.dishName, f.priceInr ?? null,
         f.isVeg === undefined ? null : f.isVeg === 'true', me],
      );
      const dishId = dish[0]!.id;

      await client.query(
        `insert into posts (id, dish_id, author_id, caption, image_path, thumb_path, placeholder_color,
                            width, height, source, is_sponsored, sponsor_note, instagram_url, idempotency_key)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'user', $10, $11, $12, $13)`,
        [postId, dishId, me, f.caption || null, stored.imagePath, stored.thumbPath, stored.placeholderColor,
         stored.width, stored.height, f.isSponsored === 'true', f.sponsorNote || null, f.instagramUrl || null,
         f.idempotencyKey],
      );
      await client.query('update dishes set cover_post_id = coalesce(cover_post_id, $2) where id = $1', [dishId, postId]);
      await client.query('commit');
    } catch (err) {
      await client.query('rollback');
      await deletePostImages(config.mediaDir, postId);
      throw err;
    } finally {
      client.release();
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
              p.thumb_path as "thumbUrl", p.status, p.is_sponsored as "isSponsored",
              r.id as "restaurantId", r.name as "restaurantName", r.status as "restaurantStatus"
       from posts p join dishes d on d.id = p.dish_id join restaurants r on r.id = d.restaurant_id
       where p.id = $1`,
      [postId],
    );
    return rows[0];
  }
}
