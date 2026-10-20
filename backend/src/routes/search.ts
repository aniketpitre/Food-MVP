import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, userId } from '../auth.js';
import { cuisines, type Cuisine } from '../seed-data.js';

/**
 * Whether dish `d` (at place `r`) belongs to a cuisine: its type is one of the cuisine's
 * types, or its name or its place's cuisine mentions one of the cuisine's words.
 * [cats] and [terms] are the numbers of the query parameters holding those lists.
 */
export function dishInCuisineSql(cats: number, terms: number): string {
  return `(d.category_id in (select id from dish_categories where slug = any($${cats}::text[]))
           or exists (select 1 from unnest($${terms}::text[]) t
                      where coalesce(r.cuisine, '') ~* ('\\m' || t || '\\M') or d.name_norm ~ ('\\m' || norm_name(t) || '\\M')))`;
}

export function cuisineBySlug(slug: string): Cuisine | undefined {
  return cuisines.find((c) => c.slug === slug);
}

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9 ]/g, '').replace(/\s+/g, ' ').trim();

/** The cuisine a search names ("italian", "south indian food", "street food"), if any. */
export function cuisineForQuery(q: string): Cuisine | undefined {
  const raw = norm(q);
  const bare = raw.replace(/\b(cuisine|restaurants?|places?)\b/g, '').replace(/\bfood$/, '').trim();
  if (bare.length < 3) return undefined;
  return cuisines.find((c) => {
    const names = [norm(c.name), ...c.name.split(/ & |, /).map(norm)];
    return names.includes(raw) || names.includes(bare) || c.slug === bare;
  });
}

/** How well [text] answers a typed [t]: whole prefix, a word's prefix, or just contains it. */
function matchRank(text: string, t: string): number {
  const n = norm(text);
  if (n === t) return 4;
  if (n.startsWith(t)) return 3;
  if (n.split(' ').some((w) => w.startsWith(t))) return 2;
  return n.includes(t) ? 1 : 0;
}

interface Suggestion {
  type: 'dish' | 'cuisine' | 'category' | 'place' | 'area' | 'person';
  text: string;
  sub: string | null;
  /** The place id, the category id, the cuisine slug or the person's handle. */
  id: string | null;
  emoji: string | null;
  /** Internal: how good the match is, then how popular. */
  rank: number;
  pop: number;
}

export async function searchRoutes(app: FastifyInstance) {
  const { db } = app;

  /**
   * Autocomplete while typing: dish names people posted, cuisines, dish types, places,
   * areas and people, best matches first. Every suggestion leads somewhere with results.
   * With an empty q, what's popular in the city right now.
   */
  app.get('/search/suggest', { preHandler: optionalAuth }, async (req, reply) => {
    const q = parse(z.object({ q: z.string().trim().max(60).default(''), city: z.coerce.number().int().positive() }), req.query, reply);
    if (!q) return;
    const viewer = userId(req);
    const t = norm(q.q);
    if (t.length === 0) {
      const { rows } = await db.query<{ name: string; n: number }>(
        `select min(d.name) as name, count(*)::int as n
         from dishes d left join dish_stats s on s.dish_id = d.id
         join restaurants r on r.id = d.restaurant_id and r.status = 'active'
         where d.city_id = $1 and d.status = 'live' and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
         group by d.name_norm order by sum(coalesce(s.score, 0)) desc limit 8`,
        [q.city],
      );
      return { items: rows.map((r) => ({ type: 'dish', text: r.name, sub: r.n > 1 ? `${r.n} places` : '1 place', id: null, emoji: null })) };
    }
    const out: Suggestion[] = [];
    const [dishes, cats, places, areas, people] = await Promise.all([
      db.query<{ name: string; n: number; pop: number }>(
        `with term as (select norm_name($1) as t)
         select min(d.name) as name, count(*)::int as n, sum(coalesce(s.score, 0))::float as pop
         from term, dishes d
         join restaurants r on r.id = d.restaurant_id and r.status = 'active'
         left join dish_stats s on s.dish_id = d.id
         where d.city_id = $2 and d.status = 'live'
           and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')
           and (d.name_norm like term.t || '%' or d.name_norm like '% ' || term.t || '%' or word_similarity(term.t, d.name_norm) >= 0.55)
         group by d.name_norm order by max(word_similarity(term.t, d.name_norm)) desc, pop desc limit 12`,
        [q.q, q.city],
      ),
      db.query<{ id: number; name: string; emoji: string | null; n: number }>(
        `select c.id, c.name, c.emoji,
                (select count(*)::int from dishes d join restaurants r on r.id = d.restaurant_id and r.status = 'active'
                 where d.category_id = c.id and d.city_id = $2 and d.status = 'live'
                   and exists (select 1 from posts p where p.dish_id = d.id and p.status = 'live')) as n
         from dish_categories c, (select norm_name($1) as t) term
         where norm_name(c.name) like '%' || term.t || '%' or word_similarity(term.t, norm_name(c.name)) >= 0.6
         limit 6`,
        [q.q, q.city],
      ),
      db.query<{ id: string; name: string; locality: string | null; dishes: number }>(
        `select r.id, r.name, r.locality,
                (select count(*)::int from dishes d where d.restaurant_id = r.id and d.status = 'live') as dishes
         from restaurants r, (select norm_name($1) as t) term
         where r.city_id = $2 and r.status = 'active'
           and (r.name_norm like term.t || '%' or r.name_norm like '% ' || term.t || '%' or word_similarity(term.t, r.name_norm) >= 0.6)
         order by word_similarity(term.t, r.name_norm) desc, dishes desc limit 8`,
        [q.q, q.city],
      ),
      db.query<{ locality: string; n: number }>(
        `select min(r.locality) as locality, count(*)::int as n
         from restaurants r
         where r.city_id = $2 and r.status = 'active' and r.locality is not null
           and (lower(r.locality) like $1 || '%' or lower(r.locality) like '% ' || $1 || '%')
         group by lower(r.locality) order by n desc limit 4`,
        [t, q.city],
      ),
      t.length >= 2
        ? db.query<{ handle: string; displayName: string | null; followers: number }>(
          `select p.handle, p.display_name as "displayName", (select count(*)::int from follows where followee_id = p.id) as followers
           from profiles p
           where ($2::uuid is null or (p.id <> $2 and not exists (select 1 from blocks b where (b.blocker_id = $2 and b.blocked_id = p.id)
                                                                      or (b.blocker_id = p.id and b.blocked_id = $2))))
             and (p.handle like $1 || '%' or lower(coalesce(p.display_name, '')) like $3 || '%'
                  or lower(coalesce(p.display_name, '')) like '% ' || $3 || '%')
           order by (p.handle = $1) desc, followers desc limit 4`,
          // Handles have no spaces; names do ("ravi k" finds Ravi Kulkarni).
          [t.replace(/^@/, '').replace(/ /g, ''), viewer, t.replace(/^@/, '')],
        )
        : Promise.resolve({ rows: [] as { handle: string; displayName: string | null; followers: number }[] }),
    ]);
    for (const d of dishes.rows) {
      out.push({ type: 'dish', text: d.name, sub: d.n > 1 ? `Dish · ${d.n} places` : 'Dish', id: null, emoji: null, rank: Math.max(matchRank(d.name, t), 1), pop: d.pop });
    }
    for (const c of cuisines) {
      const r = Math.max(matchRank(c.name, t), ...c.terms.map((x) => (matchRank(x, t) >= 3 ? 2 : 0)));
      if (r > 0) out.push({ type: 'cuisine', text: c.name, sub: 'Cuisine', id: c.slug, emoji: c.emoji, rank: r + 0.5, pop: 0 });
    }
    for (const c of cats.rows) {
      if (c.n > 0) out.push({ type: 'category', text: c.name, sub: `Dish type · ${c.n} dishes`, id: String(c.id), emoji: c.emoji, rank: matchRank(c.name, t) + 0.3, pop: c.n });
    }
    for (const p of places.rows) {
      out.push({ type: 'place', text: p.name, sub: [p.locality, p.dishes > 0 ? `${p.dishes} dishes` : null].filter(Boolean).join(' · ') || 'Place', id: p.id, emoji: null, rank: Math.max(matchRank(p.name, t), 1), pop: p.dishes });
    }
    for (const a of areas.rows) {
      out.push({ type: 'area', text: a.locality, sub: `Area · ${a.n} places`, id: null, emoji: null, rank: matchRank(a.locality, t), pop: a.n });
    }
    for (const p of people.rows) {
      out.push({ type: 'person', text: p.displayName ?? p.handle, sub: `@${p.handle}`, id: p.handle, emoji: null, rank: Math.max(matchRank(p.handle, t), matchRank(p.displayName ?? '', t)), pop: p.followers });
    }
    // Same words twice (a dish type and a dish called the same) keep only the better one.
    const seen = new Set<string>();
    const items = out
      .sort((a, b) => b.rank - a.rank || b.pop - a.pop)
      .filter((s) => {
        const key = `${s.type === 'person' || s.type === 'place' ? s.type : 'text'}:${norm(s.text)}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      })
      .slice(0, 10)
      .map(({ rank: _r, pop: _p, ...s }) => s);
    return { items };
  });

  /** Cuisines to browse, with how many dishes and places each has in the city. */
  app.get('/cuisines', async (req, reply) => {
    const q = parse(z.object({ city: z.coerce.number().int().positive() }), req.query, reply);
    if (!q) return;
    const counts = await Promise.all(cuisines.map((c) =>
      db.query<{ dishes: number; places: number }>(
        `select count(distinct d.id)::int as dishes, count(distinct r.id)::int as places
         from restaurants r left join dishes d on d.restaurant_id = r.id and d.status = 'live'
         where r.city_id = $1 and r.status = 'active'
           and ((d.id is not null and ${dishInCuisineSql(2, 3)})
                or exists (select 1 from unnest($3::text[]) t where coalesce(r.cuisine, '') ~* ('\\m' || t || '\\M')))`,
        [q.city, c.categories, c.terms],
      ).then((r) => r.rows[0]!)));
    return cuisines
      .map((c, i) => ({ slug: c.slug, name: c.name, emoji: c.emoji, dishes: counts[i]!.dishes, places: counts[i]!.places }))
      .sort((a, b) => b.dishes - a.dishes || b.places - a.places);
  });
}
