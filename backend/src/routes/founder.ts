import crypto from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parse } from '../app.js';
import { optionalAuth, requireAuth, userId } from '../auth.js';
import { requireAdmin } from './admin.js';
import { esc, page } from './pages.js';

/**
 * The launch plan, server side:
 *  - focus mode and the signature dishes (app config, set from the dashboard);
 *  - coverage of the signature dishes (are they ready for outsiders?);
 *  - visit codes: restaurants pay a fixed fee only for new customers the app brings,
 *    billed monthly after the fact; the owner confirms each one on a private page;
 *  - "My food diary": everything you ate, voted on and paid;
 *  - /best: a public page of a craving's top list, for sharing on WhatsApp.
 */

/** What focus mode hides in the app, until the core is proven. */
export const FOCUS_HIDES = ['stories', 'exploreModes', 'inAppNavigation', 'foodOnTheWay', 'tastemakers'];

/** The coverage bar: a signature dish is ready with this many places with a real photo and enough votes. */
export const READY_PLACES = 5;
export const READY_VOTES = 10;

// The opening-hours style the app writes ("Mo-Fr 15:00-18:00; Sa,Su off", "24/7").
const DAY = '(?:Mo|Tu|We|Th|Fr|Sa|Su)';
const DAYS = `${DAY}(?:-${DAY})?(?:,${DAY}(?:-${DAY})?)*`;
const T = '(?:[01]\\d|2[0-4]):[0-5]\\d-(?:[01]\\d|2[0-4]):[0-5]\\d';
const TIMES = `${T}(?:,${T})?`;
const HOURS = new RegExp(`^(?:24/7|${DAYS} (?:${TIMES}|off)(?:; ${DAYS} (?:${TIMES}|off))*)$`);

// No 0/O or 1/I/L, so a code read out at a counter can't be misheard.
const CODE_CHARS = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const CODE_HOURS = 3;
const CODES_PER_DAY = 3;

function newCode(): string {
  const bytes = crypto.randomBytes(6);
  return Array.from(bytes, (b) => CODE_CHARS[b % CODE_CHARS.length]).join('');
}

async function flag(app: FastifyInstance, key: string): Promise<string | null> {
  const { rows } = await app.db.query<{ value: string }>('select value from app_flags where key = $1', [key]);
  return rows[0]?.value ?? null;
}

export async function signatureDishes(app: FastifyInstance): Promise<string[]> {
  return (await flag(app, 'signature_dishes') ?? '')
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s.length >= 2)
    .slice(0, 30);
}

/** Bills by month for a pilot place: codes given out, codes used, new customers, the amount. */
const BILL_SQL = `
  select to_char(date_trunc('month', coalesce(v.redeemed_at, v.created_at) at time zone 'Asia/Kolkata'), 'YYYY-MM') as month,
         count(*)::int as issued,
         count(*) filter (where v.redeemed_at is not null)::int as redeemed,
         count(*) filter (where v.new_customer)::int as "newCustomers",
         coalesce(sum(v.fee_inr) filter (where v.new_customer), 0)::int as "amountInr"
  from visit_codes v where v.restaurant_id = $1
  group by 1 order by 1 desc limit $2`;

export async function founderRoutes(app: FastifyInstance) {
  const { db, config } = app;

  // ---------- app config ----------
  app.get('/app/config', async () => {
    const focus = (await flag(app, 'focus_mode')) !== 'off';
    return { focusMode: focus, hidden: focus ? FOCUS_HIDES : [], signatureDishes: await signatureDishes(app) };
  });

  app.get('/admin/flags', { preHandler: requireAdmin }, async () => ({
    focusMode: (await flag(app, 'focus_mode')) !== 'off',
    focusHides: FOCUS_HIDES,
    signatureDishes: await signatureDishes(app),
  }));

  app.put('/admin/flags/:key', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ key: z.enum(['focus_mode', 'signature_dishes']) }), req.params, reply);
    const b = parse(z.object({ value: z.string().max(2000) }), req.body, reply);
    if (!p || !b) return;
    let value = b.value.trim();
    if (p.key === 'focus_mode') {
      if (value !== 'on' && value !== 'off') return reply.code(400).send({ error: 'bad_value', message: 'Use on or off.' });
    } else {
      const names = [...new Set(value.split(/[\n,]/).map((s) => s.trim().replace(/\s+/g, ' ')).filter((s) => s.length >= 2 && s.length <= 60))];
      if (names.length === 0 || names.length > 30) {
        return reply.code(400).send({ error: 'bad_value', message: 'List 1 to 30 dishes, one per line.' });
      }
      value = names.join('\n');
    }
    await db.query(
      `insert into app_flags (key, value) values ($1, $2)
       on conflict (key) do update set value = excluded.value, updated_at = now()`,
      [p.key, value],
    );
    return { ok: true };
  });

  // ---------- coverage of the signature dishes ----------
  // For each: the places serving it with a real photo (from a real person, not the demo or
  // seed), and of those, the places where it has enough votes shown in plain numbers.
  app.get('/admin/coverage', { preHandler: requireAdmin }, async (req, reply) => {
    const q = parse(z.object({ city: z.coerce.number().int().positive().optional() }), req.query, reply);
    if (!q) return;
    const city =
      q.city ?? (await db.query<{ id: number }>("select id from cities where slug = 'nashik'")).rows[0]?.id ?? null;
    const names = await signatureDishes(app);
    const { rows } = await db.query(
      `with sig as (select n, ord from unnest($1::text[]) with ordinality s(n, ord)),
       matched as (
         select sig.n, sig.ord, d.id as dish_id, d.restaurant_id,
                coalesce(dr.loves, 0) + coalesce(dr.passes, 0) as votes,
                coalesce(dr.loves, 0) as loves,
                exists (select 1 from posts p join profiles a on a.id = p.author_id and not a.is_demo
                        where p.dish_id = d.id and p.status = 'live' and p.source = 'user') as real_photo
         from sig
         join dishes d on d.status = 'live' and ($2::int is null or d.city_id = $2)
           and (d.name_norm = norm_name(sig.n) or d.name_norm like '%' || norm_name(sig.n) || '%'
                or exists (select 1 from dish_aliases a where norm_name(a.canonical) = norm_name(sig.n) and a.alias_norm = d.name_norm))
         join restaurants r on r.id = d.restaurant_id and r.status = 'active'
         left join dish_rating dr on dr.dish_id = d.id
       )
       select sig.n as dish, sig.ord,
              count(distinct m.restaurant_id) filter (where m.real_photo)::int as "placesWithPhotos",
              count(distinct m.restaurant_id) filter (where m.votes >= $3)::int as "placesWithVotes",
              count(distinct m.restaurant_id) filter (where m.real_photo and m.votes >= $3)::int as "placesCovered",
              coalesce(sum(m.votes), 0)::int as votes,
              coalesce(sum(m.loves), 0)::int as loves
       from sig left join matched m on m.n = sig.n
       group by sig.n, sig.ord order by sig.ord`,
      [names, city, READY_VOTES],
    );
    const dishes = rows.map(({ ord: _ord, ...r }) => ({ ...r, ready: r.placesCovered >= READY_PLACES }));
    return {
      city,
      bar: { places: READY_PLACES, votes: READY_VOTES },
      ready: dishes.filter((d) => d.ready).length,
      dishes,
    };
  });

  // ---------- visit codes: the diner side ----------
  app.post('/restaurants/:id/visit-code', { preHandler: requireAuth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    if (!p) return;
    const me = userId(req)!;
    const { rows: pilots } = await db.query(
      `select rp.offer, rp.quiet_hours as "quietHours", r.name,
              (rp.quiet_hours is null or coalesce(is_open_now(rp.quiet_hours), false)) as "openNow",
              (r.created_by = $2 or exists (select 1 from place_claimants c where c.restaurant_id = r.id and c.user_id = $2)) as mine
       from restaurant_pilots rp join restaurants r on r.id = rp.restaurant_id and r.status = 'active'
       where rp.restaurant_id = $1 and rp.active`,
      [p.id, me],
    );
    const pilot = pilots[0];
    if (!pilot) return reply.code(404).send({ error: 'no_offer', message: 'This place has no WorthAbite offer right now.' });
    if (pilot.mine) return reply.code(403).send({ error: 'own_place', message: 'You added this place, so codes are not for you here.' });
    if (!pilot.openNow) {
      return reply.code(409).send({ error: 'not_now', message: `Codes for this place work during ${pilot.quietHours}.` });
    }
    // The code you already have for this place today, if it's still good.
    const { rows: had } = await db.query(
      `select code, expires_at as "expiresAt", redeemed_at as "redeemedAt" from visit_codes
       where user_id = $1 and restaurant_id = $2
         and (created_at at time zone 'Asia/Kolkata')::date = (now() at time zone 'Asia/Kolkata')::date
       order by created_at desc limit 1`,
      [me, p.id],
    );
    if (had[0] && !had[0].redeemedAt && new Date(had[0].expiresAt) > new Date()) {
      return { code: had[0].code, expiresAt: had[0].expiresAt, offer: pilot.offer, place: pilot.name };
    }
    if (had[0]?.redeemedAt) {
      return reply.code(409).send({ error: 'used_today', message: 'You already used a code here today. Come back tomorrow.' });
    }
    const { rows: today } = await db.query<{ n: number }>(
      `select count(*)::int as n from visit_codes
       where user_id = $1 and created_at > now() - interval '24 hours'`,
      [me],
    );
    if (today[0]!.n >= CODES_PER_DAY) {
      return reply.code(429).send({ error: 'too_many', message: `You can get ${CODES_PER_DAY} codes a day. Try again tomorrow.` });
    }
    for (let attempt = 0; attempt < 5; attempt++) {
      try {
        const { rows } = await db.query(
          `insert into visit_codes (code, user_id, restaurant_id, expires_at)
           values ($1, $2, $3, now() + make_interval(hours => $4)) returning code, expires_at as "expiresAt"`,
          [newCode(), me, p.id, CODE_HOURS],
        );
        return { code: rows[0].code, expiresAt: rows[0].expiresAt, offer: pilot.offer, place: pilot.name };
      } catch (e) {
        if ((e as { code?: string }).code !== '23505') throw e;
      }
    }
    return reply.code(503).send({ error: 'busy', message: 'Please try again.' });
  });

  // ---------- visit codes: the owner's private page ----------
  // A plain form (no app, no sign-in): the secret link is the key. Works on any phone.
  await app.register(async (owner) => {
    owner.addContentTypeParser('application/x-www-form-urlencoded', { parseAs: 'string' }, (_req, body, done) => {
      done(null, Object.fromEntries(new URLSearchParams(String(body))));
    });

    const pilotFor = async (token: string) => {
      if (!/^[A-Za-z0-9_-]{16,64}$/.test(token)) return null;
      const { rows } = await db.query(
        `select rp.restaurant_id as id, rp.fee_inr as fee, rp.offer, rp.quiet_hours as "quietHours", rp.active, r.name
         from restaurant_pilots rp join restaurants r on r.id = rp.restaurant_id where rp.redeem_token = $1`,
        [token],
      );
      return rows[0] ?? null;
    };

    const ownerPage = async (pilot: { id: string; name: string; fee: number; offer: string | null; quietHours: string | null; active: boolean }, token: string, message = '', ok = true) => {
      const { rows: bills } = await db.query(BILL_SQL, [pilot.id, 3]);
      const rowsHtml = bills
        .map((b) => `<tr><td>${esc(b.month)}</td><td>${b.issued}</td><td>${b.redeemed}</td><td>${b.newCustomers}</td><td>₹${b.amountInr}</td></tr>`)
        .join('');
      return page(
        `${pilot.name} · WorthAbite visits`,
        `<h1>${esc(pilot.name)}</h1>
         <p class="muted">Enter the code a customer shows you. You pay ₹${pilot.fee} only for a <b>new customer</b> WorthAbite brought you, billed at the end of the month. Regulars cost nothing.</p>
         ${pilot.offer ? `<p><b>Your offer:</b> ${esc(pilot.offer)}</p>` : ''}
         ${pilot.quietHours ? `<p><b>Codes work:</b> ${esc(pilot.quietHours)}</p>` : ''}
         ${message ? `<p class="note" style="${ok ? 'background:#d8f3dc;color:#1b4332' : ''}">${esc(message)}</p>` : ''}
         ${pilot.active ? `<form method="post" action="/r/${esc(token)}" class="card pad" style="margin-top:12px">
           <label>Code<br><input name="code" required maxlength="8" autocomplete="off" autocapitalize="characters"
             style="font-size:1.6rem;letter-spacing:.2em;width:100%;padding:10px;border-radius:10px;border:1px solid #ccc;text-transform:uppercase"></label>
           <p><label><input type="checkbox" name="new_customer" value="yes"> This is their first time here (new customer)</label></p>
           <button class="btn" type="submit">Check the code</button>
         </form>` : '<p class="note">This pilot is paused. Contact WorthAbite to restart it.</p>'}
         <h2>Your visits</h2>
         <table style="width:100%;border-collapse:collapse" cellpadding="6">
           <tr style="text-align:left"><th>Month</th><th>Codes given</th><th>Used</th><th>New customers</th><th>To pay</th></tr>
           ${rowsHtml || '<tr><td colspan="5" class="muted">No visits yet.</td></tr>'}
         </table>
         <p class="muted">Keep this link private: anyone with it can check codes for your place.</p>`,
        '<meta name="robots" content="noindex">',
      );
    };

    owner.get('/r/:token', async (req, reply) => {
      const { token } = req.params as { token: string };
      const pilot = await pilotFor(token);
      if (!pilot) return reply.code(404).type('text/html').send(page('Not found', '<h1>This link does not work</h1>'));
      return reply.type('text/html').header('cache-control', 'no-store').send(await ownerPage(pilot, token));
    });

    owner.post('/r/:token', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
      const { token } = req.params as { token: string };
      const pilot = await pilotFor(token);
      if (!pilot) return reply.code(404).type('text/html').send(page('Not found', '<h1>This link does not work</h1>'));
      const body = (req.body ?? {}) as Record<string, unknown>;
      const code = String(body.code ?? '').trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
      const isNew = body.new_customer === 'yes' || body.new_customer === true || body.newCustomer === true;
      const send = async (msg: string, ok: boolean, status = 200) =>
        reply.code(status).type('text/html').header('cache-control', 'no-store').send(await ownerPage(pilot, token, msg, ok));
      if (!pilot.active) return send('This pilot is paused.', false, 409);
      const { rows } = await db.query(
        `update visit_codes set redeemed_at = now(), new_customer = $3, fee_inr = case when $3 then $4::int else 0 end
         where code = $1 and restaurant_id = $2 and redeemed_at is null and expires_at > now()
         returning code`,
        [code, pilot.id, isNew, pilot.fee],
      );
      if (rows[0]) {
        return send(isNew ? `Code ${code} is good. New customer: ₹${pilot.fee} on this month's bill.` : `Code ${code} is good. A returning customer: nothing to pay.`, true);
      }
      const { rows: why } = await db.query(
        'select redeemed_at as "redeemedAt", expires_at as "expiresAt" from visit_codes where code = $1 and restaurant_id = $2',
        [code, pilot.id],
      );
      const w = why[0];
      return send(!w ? `Code ${code || '(empty)'} is not for this place.` : w.redeemedAt ? `Code ${code} was already used.` : `Code ${code} has expired.`, false, 409);
    });
  });

  // ---------- visit codes: admin ----------
  app.get('/admin/pilots', { preHandler: requireAdmin }, async () => {
    const { rows } = await db.query(
      `select rp.restaurant_id as "restaurantId", r.name, r.locality, rp.fee_inr as "feeInr", rp.offer,
              rp.quiet_hours as "quietHours", rp.contact, rp.active, rp.redeem_token as token, rp.created_at as "createdAt"
       from restaurant_pilots rp join restaurants r on r.id = rp.restaurant_id
       order by rp.active desc, r.name`,
    );
    const pilots = [];
    for (const r of rows) {
      const { rows: bills } = await db.query(BILL_SQL, [r.restaurantId, 3]);
      const { token, ...rest } = r;
      pilots.push({ ...rest, ownerLink: `${config.publicUrl}/r/${token}`, months: bills });
    }
    return { pilots };
  });

  app.put('/admin/pilots/:id', { preHandler: requireAdmin }, async (req, reply) => {
    const p = parse(z.object({ id: z.string().uuid() }), req.params, reply);
    const b = parse(
      z.object({
        feeInr: z.number().int().min(0).max(500),
        offer: z.string().trim().max(120).optional().nullable(),
        quietHours: z.string().trim().max(60).regex(HOURS, 'Write hours like "Mo-Fr 15:00-18:00".').optional().nullable().or(z.literal('')),
        contact: z.string().trim().max(120).optional().nullable(),
        active: z.boolean().default(true),
      }),
      req.body,
      reply,
    );
    if (!p || !b) return;
    const { rowCount } = await db.query("select 1 from restaurants where id = $1 and status = 'active'", [p.id]);
    if (!rowCount) return reply.code(404).send({ error: 'not_found', message: 'No such place.' });
    const { rows } = await db.query(
      `insert into restaurant_pilots (restaurant_id, fee_inr, offer, quiet_hours, contact, active, redeem_token)
       values ($1, $2, nullif($3, ''), nullif($4, ''), nullif($5, ''), $6, $7)
       on conflict (restaurant_id) do update set fee_inr = excluded.fee_inr, offer = excluded.offer,
         quiet_hours = excluded.quiet_hours, contact = excluded.contact, active = excluded.active
       returning redeem_token as token`,
      [p.id, b.feeInr, b.offer ?? '', b.quietHours ?? '', b.contact ?? '', b.active, crypto.randomBytes(18).toString('base64url')],
    );
    return { ok: true, ownerLink: `${config.publicUrl}/r/${rows[0].token}` };
  });

  // ---------- My food diary ----------
  // Every dish you posted or voted on, newest first, with what you paid; a short summary on top.
  app.get('/me/diary', { preHandler: requireAuth }, async (req, reply) => {
    const q = parse(z.object({ limit: z.coerce.number().int().min(1).max(100).default(50), offset: z.coerce.number().int().min(0).default(0) }), req.query, reply);
    if (!q) return;
    const me = userId(req)!;
    const base = `
      with mine as (
        select dish_id, max(created_at) as at from (
          select dish_id, created_at from engagements where user_id = $1 and kind in ('recommend', 'pass')
          union all select dish_id, created_at from posts where author_id = $1 and status = 'live'
        ) x group by dish_id
      )`;
    const { rows } = await db.query(
      `${base}
       select d.id as "dishId", d.name as dish, r.id as "placeId", r.name as place, r.locality, m.at,
              case when exists (select 1 from engagements e where e.user_id = $1 and e.dish_id = d.id and e.kind = 'recommend') then 'loved'
                   when exists (select 1 from engagements e where e.user_id = $1 and e.dish_id = d.id and e.kind = 'pass') then 'not_for_me' end as verdict,
              (select price_inr from dish_prices dp where dp.dish_id = d.id and dp.user_id = $1) as "pricePaid",
              (select thumb_path from posts p where p.dish_id = d.id and p.author_id = $1 and p.status = 'live' order by p.created_at desc limit 1) as "photoUrl",
              (select count(*)::int from posts p where p.dish_id = d.id and p.author_id = $1 and p.status = 'live') as photos,
              d.is_veg as "isVeg"
       from mine m join dishes d on d.id = m.dish_id join restaurants r on r.id = d.restaurant_id
       where d.status <> 'hidden'
       order by m.at desc, d.id limit $2 offset $3`,
      [me, q.limit + 1, q.offset],
    );
    const { rows: sum } = await db.query(
      `${base}
       select count(*)::int as dishes, count(distinct d.restaurant_id)::int as places,
              count(*) filter (where exists (select 1 from engagements e where e.user_id = $1 and e.dish_id = d.id and e.kind = 'recommend'))::int as loved,
              (select coalesce(sum(price_inr), 0)::int from dish_prices where user_id = $1) as "spentInr",
              (select count(*)::int from posts where author_id = $1 and status = 'live') as photos
       from mine m join dishes d on d.id = m.dish_id where d.status <> 'hidden'`,
      [me],
    );
    const more = rows.length > q.limit;
    return { summary: sum[0], entries: rows.slice(0, q.limit), nextOffset: more ? q.offset + q.limit : null };
  });

  // ---------- /best: a craving's top list, for sharing ----------
  app.get('/best', { preHandler: optionalAuth }, async (req, reply) => {
    const q = z.object({ q: z.string().trim().max(60).optional(), city: z.string().trim().max(60).optional() }).safeParse(req.query);
    const term = q.success ? q.data.q ?? '' : '';
    const cityKey = (q.success ? q.data.city : '') || 'nashik';
    const { rows: cities } = await db.query<{ id: number; name: string; slug: string }>(
      'select id, name, slug from cities where slug = $1 or id::text = $1 limit 1',
      [cityKey.toLowerCase()],
    );
    const city = cities[0];
    if (!city) return reply.code(404).type('text/html').send(page('Not found', '<h1>City not found</h1>'));
    const download = `<a class="btn" href="${esc(config.appDownloadUrl)}">Get the app</a>`;
    if (term.length < 2) {
      const links = (await signatureDishes(app))
        .map((n) => `<a class="btn alt" href="/best?city=${esc(city.slug)}&amp;q=${encodeURIComponent(n)}">${esc(n)}</a>`)
        .join('');
      return reply.type('text/html').send(
        page(`Best food in ${city.name} · WorthAbite`, `<h1>Best in ${esc(city.name)}</h1><p class="muted">Ranked by people who ate it.</p><div>${links}</div><p>${download}</p>`),
      );
    }
    const res = await app.inject({ method: 'GET', url: `/craving?q=${encodeURIComponent(term)}&city=${city.id}` });
    const data = res.statusCode === 200 ? res.json() : { label: term, results: [] };
    type Card = { id: string; name: string; restaurant: { name: string; locality: string | null }; rating: { percent: number | null; loves: number; passes: number }; priceInr: number | null; cover: { thumbUrl: string } | null };
    const top: Card[] = (data.results as Card[]).slice(0, 10);
    const label = String(data.label ?? term);
    const title = `Best ${label} in ${city.name}`;
    const list = top
      .map((c, i) => {
        const votes = c.rating.loves + c.rating.passes;
        const line = c.rating.percent !== null ? `${c.rating.percent}% loved · ${votes} ${votes === 1 ? 'vote' : 'votes'}` : 'New, no votes yet';
        const img = c.cover ? `<img src="${esc(config.publicUrl + c.cover.thumbUrl)}" alt="" style="width:64px;height:64px;object-fit:cover;border-radius:12px">` : '';
        return `<a href="/d/${esc(c.id)}" class="card pad" style="display:flex;gap:12px;align-items:center;margin:10px 0;color:inherit;text-decoration:none">
          <b style="font-size:1.3rem;width:28px">${i + 1}</b>${img}
          <div><b>${esc(c.name)}</b><div class="muted">${esc(c.restaurant.name)}${c.restaurant.locality ? ' · ' + esc(c.restaurant.locality) : ''}${c.priceInr ? ' · ₹' + c.priceInr : ''}</div>
          <div>${esc(line)}</div></div></a>`;
      })
      .join('');
    const desc = top[0] ? `#1 ${top[0].name} at ${top[0].restaurant.name}. Ranked by people who ate it.` : 'Ranked by people who ate it.';
    const head = `<meta property="og:title" content="${esc(title)}"><meta property="og:description" content="${esc(desc)}">${
      top[0]?.cover ? `<meta property="og:image" content="${esc(config.publicUrl + top[0].cover.thumbUrl)}">` : ''
    }`;
    return reply.type('text/html').send(
      page(
        `${title} · WorthAbite`,
        `<h1>${esc(title)}</h1>
         <p class="muted">Ranked by people who ate it. No paid placement, ever.</p>
         ${list || `<p class="note">Nobody has posted ${esc(label)} in ${esc(city.name)} yet. Be the first: post it from the app.</p>`}
         <a class="btn" href="worthabite://craving?q=${encodeURIComponent(term)}">Open in the app</a>${download}`,
        head,
      ),
    );
  });
}
