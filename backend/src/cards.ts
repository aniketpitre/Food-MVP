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
    /** One of the invented demo places, not a real business. */
    isDemo: boolean;
  };
  cover: {
    postId: string;
    imageUrl: string;
    thumbUrl: string;
    placeholderColor: string | null;
    isSponsored: boolean;
    width: number | null;
    height: number | null;
    commentCount: number;
    /** The photographer was at the place when they posted it. */
    verifiedVisit: boolean;
  } | null;
  /** How many different people posted photos of this dish. */
  photographers: number;
  stats: { recommenders: number; saves: number; tried: number; endorsers: number };
  me: { recommended: boolean; saved: boolean; tried: boolean; passed: boolean };
  recentRecommenders: { handle: string; displayName: string | null; avatarUrl: string | null }[];
  /** People's Pick: weighted loves vs "not for me" votes (see docs/PEOPLES_PICK.md). */
  rating: { loves: number; passes: number; percent: number | null; score: number };
  /** The most trusted person who loved this dish. */
  champion: { handle: string; displayName: string | null; avatarUrl: string | null; isExpert: boolean; backers: number } | null;
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
  restaurant_demo: boolean | null;
  lat: number;
  lng: number;
  post_id: string | null;
  image_path: string | null;
  thumb_path: string | null;
  placeholder_color: string | null;
  is_sponsored: boolean | null;
  width: number | null;
  height: number | null;
  comment_count: number | null;
  visit: string | null;
  photographers: number | null;
  recommenders: number;
  saves: number;
  tried: number;
  endorsers: number;
  my_kinds: string[] | null;
  recent: { handle: string; display_name: string | null; avatar_path: string | null }[];
  loves: number | null;
  passes: number | null;
  confidence: number | null;
  champ_handle: string | null;
  champ_name: string | null;
  champ_avatar: string | null;
  champ_trust: number | null;
  champ_expert: boolean | null;
  champ_backers: number | null;
  distance_m: number | null;
}

/**
 * Loads card data for the given dish ids, preserving their order.
 * The cover is the dish's best recent photo (see dish_cover); photos by people the
 * viewer blocked are never used.
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
            (r.source = 'seed') as restaurant_demo,
            st_y(r.geo::geometry) as lat, st_x(r.geo::geometry) as lng,
            cov.id as post_id, cov.image_path, cov.thumb_path, cov.placeholder_color, cov.is_sponsored,
            cov.width, cov.height, cov.visit,
            (select count(distinct ap.author_id)::int from posts ap where ap.dish_id = d.id and ap.status = 'live') as photographers,
            (select count(*)::int from comments cm where cm.post_id = cov.id and cm.status = 'live') as comment_count,
            coalesce(s.recommenders, 0) as recommenders, coalesce(s.saves, 0) as saves,
            coalesce(s.tried, 0) as tried, coalesce(s.endorsers, 0) as endorsers,
            (select array_agg(e.kind) from engagements e where e.user_id = $2 and e.dish_id = d.id) as my_kinds,
            -- The latest people who loved it ("tried" alone isn't a recommendation), never
            -- whoever added the place, nor anyone the viewer blocked.
            (select coalesce(json_agg(x), '[]'::json) from (
               select pr.handle, pr.display_name, pr.avatar_path
               from engagements e join profiles pr on pr.id = e.user_id
               where e.dish_id = d.id and e.kind = 'recommend'
                 and not exists (select 1 from place_claimants pc where pc.restaurant_id = r.id and pc.user_id = e.user_id)
                 and ($2::uuid is null or not exists (select 1 from blocks b where (b.blocker_id = $2 and b.blocked_id = e.user_id) or (b.blocker_id = e.user_id and b.blocked_id = $2)))
               order by e.created_at desc, pr.handle limit 3) x) as recent,
            case when $3::float8 is not null and $4::float8 is not null
                 then st_distance(r.geo, st_setsrid(st_makepoint($4, $3), 4326)::geography) end as distance_m,
            dr.loves, dr.passes, dr.confidence,
            ch.handle as champ_handle, ch.display_name as champ_name, ch.avatar_path as champ_avatar,
            ct.trust as champ_trust, ct.expert as champ_expert, ct.backers as champ_backers
     from unnest($1::uuid[]) with ordinality as ids(id, ord)
     join dishes d on d.id = ids.id
     join restaurants r on r.id = d.restaurant_id
     left join dish_stats s on s.dish_id = d.id
     left join dish_rating dr on dr.dish_id = d.id
     left join profiles ch on ch.id = dr.champion_id
       and ($2::uuid is null or not exists (select 1 from blocks b where (b.blocker_id = $2 and b.blocked_id = ch.id) or (b.blocker_id = ch.id and b.blocked_id = $2)))
     left join taster_stats ct on ct.user_id = dr.champion_id and ct.category_id = coalesce(d.category_id, -1)
     left join lateral (
       select p.* from posts p
       where p.id = dish_cover(d.id, $2::uuid)) cov on true
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
      isDemo: !!r.restaurant_demo,
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
          commentCount: Number(r.comment_count ?? 0),
          verifiedVisit: r.visit === 'verified',
        }
      : null,
    photographers: Number(r.photographers ?? 0),
    stats: {
      recommenders: Number(r.recommenders),
      saves: Number(r.saves),
      tried: Number(r.tried),
      endorsers: Number(r.endorsers),
    },
    me: { recommended: kinds.has('recommend'), saved: kinds.has('save'), tried: kinds.has('tried'), passed: kinds.has('pass') },
    recentRecommenders: r.recent.map((x) => ({ handle: x.handle, displayName: x.display_name, avatarUrl: x.avatar_path })),
    rating: ratingJson(r.loves, r.passes, r.confidence),
    champion: r.champ_handle
      ? {
          handle: r.champ_handle,
          displayName: r.champ_name,
          avatarUrl: r.champ_avatar,
          isExpert: !!r.champ_expert,
          backers: Number(r.champ_backers ?? 0),
        }
      : null,
    distanceM: r.distance_m === null ? null : Math.round(Number(r.distance_m)),
  };
}

export function ratingJson(loves: number | null, passes: number | null, confidence: number | null) {
  const l = Number(loves ?? 0);
  const p = Number(passes ?? 0);
  return { loves: l, passes: p, percent: l + p > 0 ? Math.round((100 * l) / (l + p)) : null, score: Number(confidence ?? 0) };
}

/** Picks are weighted by how independently they were backed; shown as whole picks. */
export function wholePicks(picks: number | string | null): number {
  return Math.round(Number(picks ?? 0));
}
