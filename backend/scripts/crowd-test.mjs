// Run it against a TEST server and database only (it creates ~250 accounts and rewrites account ages):
//   backend/scripts/crowd-test.sh 0 crowd_launchday     # everyone votes on launch day
//   backend/scripts/crowd-test.sh 1 crowd_spread        # honest votes spread over weeks
// A simulated crowd on the real WorthAbite server: honest diners with a hidden "true taste",
// a ring of fake accounts, throwaway accounts, place owners and troublemakers. Then it checks
// that the ranking recovered the truth, the cheaters didn't win, nothing broke, and how fast it was.
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
const require = createRequire(import.meta.url); // run from backend/ so `sharp` resolves
const sharp = require('sharp');

const BASE = process.env.BASE ?? 'http://localhost:8099';
const DB = process.env.DB ?? 'crowd';
// SPREAD: 0 = everyone votes on launch day, 1 = honest votes spread over weeks,
// week = launch week: every honest account is new that week, 60 % only browse, 30 % vote,
// 10 % also post photos, and a third of the voting happens in the first hour.
const MODE = process.env.SPREAD ?? '0';
const SPREAD = MODE === '1';
const WEEK = MODE === 'week';
const N = { honest: Number(process.env.HONEST ?? 150), ring: 40, throwaway: 40, owners: 6, chaos: 10 };
const OUT = process.env.OUT ?? './crowd-report.json';
const PASS = 'correct horse battery';

let seed = 12345;
const rand = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296);
const pick = (a) => a[Math.floor(rand() * a.length)];
const shuffle = (a) => { const b = [...a]; for (let i = b.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [b[i], b[j]] = [b[j], b[i]]; } return b; };

// How to reach the test database: PSQL overrides it (default: the ff-testdb docker container).
const PSQL = process.env.PSQL ?? `docker exec -i ff-testdb psql -U postgres -d ${DB} -tA -F '|'`;
const sql = (q) => execSync(PSQL, { input: q }).toString().trim();

// ---------- HTTP with timing ----------
const lat = new Map(); // endpoint -> [ms]
const statuses = new Map();
const errors5xx = [];
function bucket(method, path) {
  return method + ' ' + path.split('?')[0].replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, ':id').replace(/\/users\/[^/]+/, '/users/:h');
}
async function api(method, path, { token, body, form, ip } = {}) {
  const headers = { 'x-forwarded-for': ip ?? '10.1.' + Math.floor(rand() * 250) + '.' + Math.floor(rand() * 250) };
  if (token) headers.authorization = `Bearer ${token}`;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['content-type'] = 'application/json'; payload = JSON.stringify(body); }
  const t0 = performance.now();
  let res, text;
  try {
    res = await fetch(BASE + path, { method, headers, body: payload });
    text = await res.text();
  } catch (e) {
    errors5xx.push(`${method} ${path}: ${e.message}`);
    return { status: 0, json: null };
  }
  const ms = performance.now() - t0;
  const b = bucket(method, path);
  if (!lat.has(b)) lat.set(b, []);
  lat.get(b).push(ms);
  statuses.set(res.status, (statuses.get(res.status) ?? 0) + 1);
  if (res.status >= 500) errors5xx.push(`${method} ${path}: ${res.status} ${text.slice(0, 120)}`);
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: res.status, json };
}
async function pool(items, n, fn) {
  const out = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: n }, async () => {
    while (i < items.length) { const k = i++; out[k] = await fn(items[k], k); }
  }));
  return out;
}
async function photo() {
  const raw = Buffer.from(Array.from({ length: 16 * 12 * 3 }, () => Math.floor(rand() * 256)));
  return sharp(raw, { raw: { width: 16, height: 12, channels: 3 } }).resize(800, 600).jpeg().toBuffer();
}
async function postDish(token, fields) {
  const f = new FormData();
  f.set('idempotencyKey', 'k' + Math.random().toString(36).slice(2, 12));
  for (const [k, v] of Object.entries(fields)) f.set(k, v);
  f.set('image', new Blob([await photo()], { type: 'image/jpeg' }), 'dish.jpg');
  return api('POST', '/posts', { token, form: f });
}
const p95 = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length * 0.95)] ?? 0;
const p50 = (a) => [...a].sort((x, y) => x - y)[Math.floor(a.length * 0.5)] ?? 0;
function spearman(xs, ys) {
  const rank = (a) => { const s = a.map((v, i) => [v, i]).sort((p, q) => p[0] - q[0]); const r = []; s.forEach(([, i], k) => (r[i] = k)); return r; };
  const rx = rank(xs), ry = rank(ys), n = xs.length;
  const d2 = rx.reduce((s, v, i) => s + (v - ry[i]) ** 2, 0);
  return 1 - (6 * d2) / (n * (n * n - 1));
}

const report = {
  scenario: WEEK ? 'launch week: new accounts, mostly browsing, a busy first hour'
    : SPREAD ? 'honest votes spread over weeks' : 'launch day: everyone votes the same day',
  checks: [],
};
const check = (name, ok, detail = '') => { report.checks.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };
const T0 = Date.now();

// ---------- 1. the crowd signs up ----------
const people = [];
const mk = (kind, n) => Array.from({ length: n }, (_, i) => ({ kind, handle: `sim_${kind[0]}${String(i + 1).padStart(3, '0')}`.slice(0, 20) }));
const all = [...mk('honest', N.honest), ...mk('ring', N.ring), ...mk('throwaway', N.throwaway), ...mk('owners', N.owners), ...mk('chaos', N.chaos)];
await pool(all, 20, async (p) => {
  const r = await api('POST', '/auth/register', { body: { email: `${p.handle}@sim.test`, password: PASS, handle: p.handle, acceptTerms: true } });
  if (r.status !== 201) { errors5xx.push(`register ${p.handle}: ${r.status} ${JSON.stringify(r.json).slice(0, 100)}`); return; }
  p.token = r.json.token; p.id = r.json.user.id;
  people.push(p);
});
check('Signed up the whole crowd', people.length === all.length, `${people.length}/${all.length} accounts`);
// Age the accounts: honest 10–60 days (in launch week: joined during the week, half in its first
// six hours), ring and owners 30 days, throwaways and chaos brand new.
sql(WEEK
  ? `update profiles set created_at = now() - interval '7 days' + case when random() < 0.5 then random() * interval '6 hours'
                                                                       else random() * interval '6 days' end where handle like 'sim_h%';`
  : `update profiles set created_at = now() - (10 + random() * 50) * interval '1 day' where handle like 'sim_h%';`);
sql(`
     update profiles set created_at = now() - interval '30 days' where handle like 'sim_r%' or handle like 'sim_o%';`);
const by = (k) => people.filter((p) => p.kind === k);
const honest = by('honest'), ring = by('ring'), thr = by('throwaway'), owners = by('owners'), chaos = by('chaos');
// What each honest person does: in launch week most only look around.
honest.forEach((h, i) => (h.role = !WEEK ? 'voter' : i % 10 < 6 ? 'lurker' : i % 10 < 9 ? 'voter' : 'poster'));

// ---------- 2. the menu: real places, dishes with a hidden true quality ----------
const mapRes = await api('GET', '/cities/nearest?lat=20.0059&lng=73.7898');
const city = mapRes.json.city.id;
const places = (await api('GET', `/map?city=${city}&all=true`)).json.places.filter((p) => !p.demo && p.dishCount === 0).slice(0, 12);
const DISHES = ['Kolhapuri Misal', 'Sabudana Khichdi', 'Vada Pav', 'Pav Bhaji', 'Masala Dosa', 'Idli Sambar', 'Paneer Tikka', 'Veg Biryani', 'Chicken Biryani', 'Mutton Thali', 'Butter Chicken', 'Chole Bhature', 'Poha', 'Thalipeeth', 'Puran Poli', 'Kanda Bhaji', 'Egg Bhurji', 'Fish Curry', 'Sev Puri', 'Pani Puri', 'Masala Chai', 'Cold Coffee', 'Gulab Jamun', 'Jalebi', 'Veg Pizza', 'Pasta Alfredo', 'Veg Burger', 'Momos', 'Hakka Noodles', 'Shawarma'];
const menu = DISHES.map((name, i) => ({ name, q: 0.15 + 0.8 * rand(), place: places[i % places.length], kind: 'real' }));
const X = { name: 'Famous Fake Thali', q: 0.08, place: places[0], kind: 'cheat-target' }; // bad dish the ring pushes
const Y = { name: 'Hidden Gem Misal', q: 0.95, place: places[1], kind: 'rival' };          // great dish the ring sinks
menu.push(X, Y);
await pool(menu, 8, async (d, i) => {
  const poster = honest[i % honest.length];
  const r = await postDish(poster.token, { dishName: d.name, restaurantId: d.place.id });
  if (r.status === 201 || r.status === 200) d.id = r.json.dishId; else errors5xx.push(`post ${d.name}: ${r.status} ${JSON.stringify(r.json).slice(0, 100)}`);
});
// An owner's own place (made active) with an owner's dish nobody honest likes.
const ownerPlaces = [];
for (const [i, o] of owners.entries()) {
  const r = await postDish(o.token, { dishName: `Owner Special ${i + 1}`, newPlaceName: `Owner Kitchen ${i + 1}`, newPlaceLat: String(20.0 + 0.01 * i), newPlaceLng: '73.76' });
  if (r.status === 201) ownerPlaces.push({ owner: o, dish: { name: `Owner Special ${i + 1}`, id: r.json.dishId, q: 0.1, kind: 'owner' }, place: r.json.restaurantId });
}
sql(`update restaurants set status = 'active' where created_by in (select id from profiles where handle like 'sim_o%')`);
const live = menu.filter((d) => d.id);
check('Posted the menu through the real app flow', live.length === menu.length, `${live.length}/${menu.length} dishes, ${ownerPlaces.length} owner places`);

// ---------- 3. the voting: honest people vote by their taste, cheaters cheat ----------
const allDishes = [...live, ...ownerPlaces.map((o) => o.dish)];
const votes = { honest: 0 };
await pool(honest, 25, async (h) => {
  if (h.role === 'lurker') {
    // Looks around: Home, the feed tabs, a craving, a dish and a place, Explore.
    await api('GET', `/home?city=${city}`, { token: h.token });
    for (const tab of ['top', 'trending', 'gems']) await api('GET', `/feed?city=${city}&tab=${tab}&limit=20&seed=${h.handle}`, { token: h.token });
    await api('GET', `/craving?q=${encodeURIComponent(pick(['misal', 'pizza', 'chai', 'biryani', 'dosa', 'vada pav']))}&city=${city}`, { token: h.token });
    const d = pick(allDishes);
    await api('GET', `/dishes/${d.id}`, { token: h.token });
    await api('GET', `/places?city=${city}&sort=loved&limit=20`, { token: h.token });
    return;
  }
  const myDishes = shuffle(allDishes).slice(0, WEEK ? 8 + Math.floor(rand() * 8) : 12);
  if (h.role === 'poster') {
    // Also posts a photo of a dish they loved, standing at the place.
    const fav = myDishes.find((d) => d.kind === 'real' && rand() < d.q + 0.2) ?? myDishes[0];
    const r = await postDish(h.token, { dishName: fav.name, restaurantId: fav.place?.id ?? '', verdict: 'loved', lat: String(fav.place?.lat ?? 20), lng: String(fav.place?.lng ?? 73.79), capture: 'camera' });
    if (![200, 201, 409, 429].includes(r.status)) errors5xx.push(`poster ${h.handle}: ${r.status} ${JSON.stringify(r.json).slice(0, 100)}`);
  }
  for (const d of myDishes) {
    const love = rand() < d.q;
    await api('PUT', `/dishes/${d.id}/engagements/${love ? 'recommend' : 'pass'}`, { token: h.token });
    votes.honest++;
    if (rand() < 0.3) await api('PUT', `/dishes/${d.id}/engagements/save`, { token: h.token });
  }
  // They also browse like people do.
  await api('GET', `/home?city=${city}`, { token: h.token });
  await api('GET', `/feed?city=${city}&tab=top&limit=20&seed=${h.handle}`, { token: h.token });
  await api('GET', `/search?q=${encodeURIComponent(pick(['misal', 'pizza', 'chai', 'biryani', 'dosa']))}&city=${city}`, { token: h.token });
  await api('GET', `/dishes/${pick(allDishes).id}`, { token: h.token });
});
// A few follow each other and comment.
const postIds = sql(`select id from posts where status = 'live'`).split('\n');
await pool(honest.slice(0, 40), 20, async (h, i) => {
  await api('PUT', `/users/${honest[(i + 1) % honest.length].handle}/follow`, { token: h.token });
  await api('POST', `/posts/${pick(postIds)}/comments`, { token: h.token, body: { body: 'Looks good' } });
});
// The ring: 40 old accounts, together within minutes: love the fake thali, sink the gem.
await pool(ring, 20, async (r) => {
  await api('PUT', `/dishes/${X.id}/engagements/recommend`, { token: r.token });
  await api('PUT', `/dishes/${Y.id}/engagements/pass`, { token: r.token });
});
// Throwaway accounts made a moment ago do the same, plus saves and shares.
await pool(thr, 20, async (t) => {
  for (const k of ['recommend', 'save', 'tried']) await api('PUT', `/dishes/${X.id}/engagements/${k}`, { token: t.token });
  await api('POST', `/dishes/${X.id}/share`, { token: t.token });
  await api('PUT', `/dishes/${Y.id}/engagements/pass`, { token: t.token });
});
// Owners love their own dishes.
await pool(ownerPlaces, 6, async (o) => { await api('PUT', `/dishes/${o.dish.id}/engagements/recommend`, { token: o.owner.token }); });
// Troublemakers.
const ghost = '00000000-0000-0000-0000-000000000000';
await pool(chaos, 10, async (c) => {
  const flips = await Promise.all(Array.from({ length: 16 }, (_, i) => api('PUT', `/dishes/${pick(live).id}/engagements/${i % 2 ? 'pass' : 'recommend'}`, { token: c.token })));
  for (const f of flips) if (![200, 429].includes(f.status)) errors5xx.push(`flip race: ${f.status}`);
  const bad = [
    api('PUT', `/dishes/not-a-uuid/engagements/recommend`, { token: c.token }),
    api('PUT', `/dishes/${ghost}/engagements/recommend`, { token: c.token }),
    api('GET', `/search?q=${encodeURIComponent("'; drop table dishes; --")}&city=${city}`),
    api('GET', `/search?q=${'a'.repeat(5000)}&city=${city}`),
    api('GET', `/craving?q=%00%00&city=${city}`),
    api('GET', `/feed?city=99999&tab=top`),
    api('POST', `/reports`, { token: c.token, body: { targetType: 'dish', targetId: Y.id, reason: 'spam' } }),
    api('POST', `/reports`, { token: c.token, body: { targetType: 'post', targetId: ghost, reason: 'spam' } }),
    api('POST', `/blocks`, { token: c.token, body: { handle: c.handle } }),
    api('GET', `/places?city=${city}&sort=nearest`),
    api('GET', `/restaurants/similar?name=x&lat=999&lng=0`),
  ];
  const rs = await Promise.all(bad);
  for (const r of rs) if (r.status >= 500 || r.status === 0) errors5xx.push(`bad input -> ${r.status}`);
});
// Everyone mass-reports the gem.
await pool([...ring, ...thr], 20, async (r) => {
  await api('POST', '/reports', { token: r.token, body: { targetType: 'dish', targetId: Y.id, reason: 'spam' } });
});
const gemPost = sql(`select id from posts where dish_id = '${Y.id}' limit 1`);
await pool([...ring.slice(0, 10), ...thr.slice(0, 10)], 20, async (r) => {
  await api('POST', '/reports', { token: r.token, body: { targetType: 'post', targetId: gemPost, reason: 'spam' } });
});

// Honest votes spread over weeks (real life), over launch week (a third in the first hour,
// never before the account existed), or all right now (launch day).
if (SPREAD) sql(`update engagements set created_at = now() - random() * interval '40 days' where user_id in (select id from profiles where handle like 'sim_h%')`);
if (WEEK) {
  sql(`update engagements e set created_at = least(now(), greatest(p.created_at + interval '10 minutes',
         now() - interval '7 days' + case when random() < 0.33 then random() * interval '1 hour' else random() * interval '7 days' end))
       from profiles p where p.id = e.user_id and p.handle like 'sim_h%'`);
}
await new Promise((r) => setTimeout(r, 6000));
sql('select refresh_rankings_now()');

// ---------- 4. what the app now shows ----------
const rows = sql(`select d.name, d.id, coalesce(dr.loves,0), coalesce(dr.passes,0), coalesce(dr.w_loves,0), coalesce(dr.w_voters,0), coalesce(dr.confidence,0), d.status
                  from dishes d left join dish_rating dr on dr.dish_id = d.id where d.id = any('{${allDishes.map((d) => d.id).join(',')}}'::uuid[])`)
  .split('\n').map((l) => l.split('|')).map(([name, id, loves, passes, wl, wv, conf, status]) => ({ name, id, loves: +loves, passes: +passes, wl: +wl, wv: +wv, conf: +conf, status }));
const byId = new Map(rows.map((r) => [r.id, r]));
// Feed order for a guest.
const feed = [];
for (let off = 0; off < 200; off += 50) {
  const r = await api('GET', `/feed?city=${city}&tab=top&limit=50&offset=${off}&seed=guest`);
  feed.push(...(r.json.items ?? []));
  if (!r.json.nextOffset) break;
}
const pos = (id) => feed.findIndex((c) => c.id === id);
const realWithVotes = live.filter((d) => d.kind === 'real' && (byId.get(d.id)?.loves + byId.get(d.id)?.passes) >= 4);
const rho = spearman(realWithVotes.map((d) => d.q), realWithVotes.map((d) => byId.get(d.id).conf));
if (WEEK) {
  const topCards = feed.filter((c) => c.lane === 'top').length;
  check('Top isn\'t empty in launch week (every account is under a week old)', topCards >= 10, `${topCards} dishes in Top`);
  const roles = Object.fromEntries(['lurker', 'voter', 'poster'].map((r) => [r, honest.filter((h) => h.role === r).length]));
  report.roles = roles;
  console.log('Honest roles:', JSON.stringify(roles));
}
check('Ranking recovers the honest crowd\'s true taste', rho > 0.6, `Spearman ${rho.toFixed(2)} over ${realWithVotes.length} dishes (1.0 = perfect)`);
const confs = rows.filter((r) => r.status === 'live').map((r) => r.conf).sort((a, b) => b - a);
const rankOf = (id) => confs.findIndex((c) => c <= byId.get(id).conf) + 1;
check('The ring\'s fake dish did NOT win', rankOf(X.id) > Math.floor(confs.length / 2), `fake thali ranks ${rankOf(X.id)} of ${confs.length} by confidence; feed position ${pos(X.id) < 0 ? 'not in Top' : pos(X.id) + 1}`);
check('The sunk gem stays near the top', rankOf(Y.id) <= 5, `gem ranks ${rankOf(Y.id)} of ${confs.length}; feed position ${pos(Y.id) < 0 ? 'not in Top' : pos(Y.id) + 1}`);
// The numbers people read ("% loved · votes") must tell the same story as the ranking.
const shownPct = (r) => (r.loves + r.passes ? Math.round((100 * r.loves) / (r.loves + r.passes)) : null);
const xs = byId.get(X.id), ys = byId.get(Y.id);
check('The "% loved" people read agrees with the ranking', (shownPct(ys) ?? 0) > (shownPct(xs) ?? 0) && (shownPct(xs) ?? 0) < 50,
  `gem shows ${shownPct(ys)}% of ${ys.loves + ys.passes} votes, fake thali ${shownPct(xs)}% of ${xs.loves + xs.passes} votes`);
const twins = Object.fromEntries(sql(`select p.handle, coalesce(vi.twins,0) from profiles p left join voter_independence vi on vi.user_id = p.id where p.handle like 'sim_%'`).split('\n').map((l) => l.split('|')).map(([h, t]) => [h, +t]));
const flagged = (arr) => arr.filter((p) => twins[p.handle] > 0).length;
check('Ring accounts were found voting in lockstep', flagged(ring) >= ring.length * 0.9, `${flagged(ring)}/${ring.length} flagged`);
const fp = flagged(honest);
check('Honest diners were not wrongly flagged', fp <= honest.length * 0.05, `${fp}/${honest.length} honest accounts flagged (${(100 * fp / honest.length).toFixed(1)}%)`);
const cheatStanding = +sql(`select coalesce(sum(standing(p.created_at, vi.twins)), 0) from profiles p left join voter_independence vi on vi.user_id = p.id
                             where p.handle like 'sim_r%' or p.handle like 'sim_t%'`);
check('80 cheating accounts together carry less than 3 people\'s weight', cheatStanding < 3, `${cheatStanding.toFixed(2)} full people's worth of standing between ${ring.length + thr.length} fake accounts`);
// Owner dishes: the app should count only the honest diners' loves, never the owner's own.
const ownerDishIds = ownerPlaces.map((o) => o.dish.id);
const expected = +sql(`select count(*) from engagements e join dishes d on d.id = e.dish_id join restaurants r on r.id = d.restaurant_id
                       where e.dish_id = any('{${ownerDishIds.join(',')}}'::uuid[]) and e.kind = 'recommend' and e.user_id is distinct from r.created_by`);
const counted = ownerPlaces.map((o) => byId.get(o.dish.id).loves).reduce((a, b) => a + b, 0);
const ownersOwn = +sql(`select count(*) from engagements e join dishes d on d.id = e.dish_id join restaurants r on r.id = d.restaurant_id
                        where e.dish_id = any('{${ownerDishIds.join(',')}}'::uuid[]) and e.kind = 'recommend' and e.user_id = r.created_by`);
check('Owners can\'t vote for their own dishes', counted === expected && ownersOwn >= ownerPlaces.length, `${ownersOwn} owner loves made, app counted ${counted} loves (honest ones: ${expected})`);
const gemRow = sql(`select status from dishes where id = '${Y.id}'`);
const gemPostStatus = sql(`select status from posts where id = '${gemPost}'`);
check('Mass reports didn\'t take down the gem', gemRow === 'live' && gemPostStatus === 'live', `dish ${gemRow}, post ${gemPostStatus}`);
const dupes = sql(`select count(*) from (select user_id, dish_id from engagements where kind in ('recommend','pass') group by 1,2 having count(*) > 1) x`);
check('Never two verdicts for one person and dish', dupes === '0', `${dupes} duplicates`);
// Vegetarians never get meat in For You.
const vegs = honest.slice(0, 15);
let meat = 0, fyChecked = 0;
await pool(vegs, 10, async (v) => {
  await api('PUT', '/me/taste', { token: v.token, body: { categories: [], diet: 'veg', spice: 3 } });
  const r = await api('GET', `/feed?city=${city}&tab=foryou&limit=50`, { token: v.token });
  for (const c of r.json.items ?? []) { fyChecked++; if (/chicken|mutton|fish|egg|kheema|prawn|shawarma/i.test(c.name)) meat++; }
});
check('Vegetarians never saw meat in For You', meat === 0 && fyChecked > 0, `${fyChecked} cards checked, ${meat} meat`);
// Pages open for guests under load.
const pg = await Promise.all([api('GET', `/home?city=${city}`), api('GET', `/feed?city=${city}&tab=trending&limit=20`), api('GET', `/craving?q=misal&city=${city}`), api('GET', `/places?city=${city}&limit=20`)]);
check('Home, Trending, Craving and Explore all load after the crowd', pg.every((r) => r.status === 200), pg.map((r) => r.status).join(','));
check('No server errors from 800+ requests incl. troublemakers', errors5xx.length === 0, errors5xx.length ? errors5xx.slice(0, 5).join(' | ') : `${[...statuses.values()].reduce((a, b) => a + b, 0)} requests`);

// ---------- 5. speed ----------
const table = [...lat.entries()].filter(([, v]) => v.length >= 8).map(([k, v]) => ({ endpoint: k, n: v.length, p50: Math.round(p50(v)), p95: Math.round(p95(v)) })).sort((a, b) => b.p95 - a.p95);
console.log('\nSlowest endpoints (ms):');
for (const t of table.slice(0, 8)) console.log(`  ${String(t.p95).padStart(5)} p95  ${String(t.p50).padStart(5)} p50  n=${String(t.n).padStart(4)}  ${t.endpoint}`);
const slow = table.filter((t) => t.p95 > 2000);
check('Every endpoint answered within 2 s at the 95th percentile', slow.length === 0, slow.length ? slow.map((t) => `${t.endpoint} ${t.p95}ms`).join(', ') : `slowest ${table[0]?.endpoint} ${table[0]?.p95}ms`);
console.log('\nStatus codes:', Object.fromEntries([...statuses.entries()].sort()));
report.latency = table; report.minutes = ((Date.now() - T0) / 60000).toFixed(1);
report.passed = report.checks.filter((c) => c.ok).length; report.failed = report.checks.length - report.passed;
fs.writeFileSync(OUT, JSON.stringify(report, null, 1));
console.log(`\n${report.passed} passed, ${report.failed} failed  (${report.minutes} min, ${people.length} simulated people)`);
process.exit(report.failed ? 1 : 0);
