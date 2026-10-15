import type { Db } from './db.js';

export interface DishCard {
  id: string;
  name: string;
  priceInr: number | null;
  isVeg: boolean | null;
  categoryId: number | null;
  createdAt: string;
  restaurant: {
    id: string;
    name: string;
    locality: string | null;
    status: string;
    lat: number;
    lng: number;
  };
  cover: {
    postId: string;
    imageUrl: string;
    thumbUrl: string;
    placeholderColor: string | null;
    isSponsored: boolean;
    width: number | null;
    height: number | null;
  } | null;
  stats: { recommenders: number; saves: number; tried: number; endorsers: number };
  me: { recommended: boolean; saved: boolean; tried: boolean };
  recentRecommenders: { handle: string; displayName: string | null }[];
  distanceM: number | null;
  lane?: string;
}

interface CardRow {
  id: string;
  name: string;
  price_inr: number | null;
  is_veg: boolean | null;
  category_id: number | null;
  created_at: Date;
  restaurant_id: string;
  restaurant_name: string;
  locality: string | null;
  restaurant_status: string;
  lat: number;
  lng: number;
  post_id: string | null;
  image_path: string | null;
  thumb_path: string | null;
  placeholder_color: string | null;
  is_sponsored: boolean | null;
  width: number | null;
  height: number | null;
  recommenders: number;
  saves: number;
  tried: number;
  endorsers: number;
  my_kinds: string[] | null;
  recent: { handle: string; display_name: string | null }[];
  distance_m: number | null;
}

/**
 * Loads card data for the given dish ids, preserving their order.
 * The cover is the dish's chosen cover if it is still live, otherwise the newest
 * live photo; photos by people the viewer blocked are never used.
 */
export async function loadDishCards(
  db: Db,
  ids: string[],
  viewer: string | null,
  lat?: number | null,
  lng?: number | null,
): Promise<DishCard[]> {
  if (ids.length === 0) return [];
  const { rows } = await db.query<CardRow>(
    `select d.id, d.name, d.price_inr, d.is_veg, d.category_id, d.created_at,
            r.id as restaurant_id, r.name as restaurant_name, r.locality, r.status as restaurant_status,
            st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
            cov.id as post_id, cov.image_path, cov.thumb_path, cov.placeholder_color, cov.is_sponsored,
            cov.width, cov.height,
            coalesce(s.recommenders, 0) as recommenders, coalesce(s.saves, 0) as saves,
            coalesce(s.tried, 0) as tried, coalesce(s.endorsers, 0) as endorsers,
            (select array_agg(e.kind) from engagements e where e.user_id = $2 and e.dish_id = d.id) as my_kinds,
            (select coalesce(json_agg(x), '[]'::json) from (
               select pr.handle, pr.display_name
               from engagements e join profiles pr on pr.id = e.user_id
               where e.dish_id = d.id and e.kind in ('recommend', 'tried')
               group by pr.id, pr.handle, pr.display_name
               order by max(e.created_at) desc limit 3) x) as recent,
            case when $3::float8 is not null and $4::float8 is not null
                 then st_distance(r.geo, st_setsrid(st_makepoint($4, $3), 4326)::geography) end as distance_m
     from unnest($1::uuid[]) with ordinality as ids(id, ord)
     join dishes d on d.id = ids.id
     join restaurants r on r.id = d.restaurant_id
     left join dish_stats s on s.dish_id = d.id
     left join lateral (
       select p.* from posts p
       where p.dish_id = d.id and p.status = 'live'
         and ($2::uuid is null or not exists
              (select 1 from blocks b where b.blocker_id = $2 and b.blocked_id = p.author_id))
       order by (p.id = d.cover_post_id) desc, p.created_at desc
       limit 1) cov on true
     order by ids.ord`,
    [ids, viewer, lat ?? null, lng ?? null],
  );
  return rows.map(toCard);
}

function toCard(r: CardRow): DishCard {
  const kinds = new Set(r.my_kinds ?? []);
  return {
    id: r.id,
    name: r.name,
    priceInr: r.price_inr,
    isVeg: r.is_veg,
    categoryId: r.category_id,
    createdAt: r.created_at.toISOString(),
    restaurant: {
      id: r.restaurant_id,
      name: r.restaurant_name,
      locality: r.locality,
      status: r.restaurant_status,
      lat: r.lat,
      lng: r.lng,
    },
    cover: r.post_id
      ? {
          postId: r.post_id,
          imageUrl: r.image_path!,
          thumbUrl: r.thumb_path!,
          placeholderColor: r.placeholder_color,
          isSponsored: r.is_sponsored ?? false,
          width: r.width,
          height: r.height,
        }
      : null,
    stats: {
      recommenders: Number(r.recommenders),
      saves: Number(r.saves),
      tried: Number(r.tried),
      endorsers: Number(r.endorsers),
    },
    me: { recommended: kinds.has('recommend'), saved: kinds.has('save'), tried: kinds.has('tried') },
    recentRecommenders: r.recent.map((x) => ({ handle: x.handle, displayName: x.display_name })),
    distanceM: r.distance_m === null ? null : Math.round(Number(r.distance_m)),
  };
}
