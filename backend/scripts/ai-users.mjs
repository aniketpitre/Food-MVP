// AI test users: each one is a person from Nashik with their own tastes and habits, driven by a
// language model (NVIDIA build / NIM, OpenAI-compatible). They use WorthAbite screen by screen
// through the real API (Home, cravings, search, dishes, places, Explore, posting a photo, loving,
// passing, saving, commenting, following), and every action is checked: anything that breaks is a
// bug, not an opinion. At the end each one writes a short review, and the reviews are summarised.
//
// TEST server only (it creates accounts and posts):
//   NVIDIA_API_KEY=... BASE=http://localhost:8099 node scripts/ai-users.mjs
// Options: AGENTS (20), STEPS (12), MODEL, FALLBACK_MODEL, LLM_CONCURRENCY (6), OUT_DIR (.)
// They see the app's screens as text built from the same server data the app shows, so they
// judge content and flows, not visual design.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
const require = createRequire(import.meta.url);
const sharp = require('sharp');

const BASE = process.env.BASE ?? 'http://localhost:8099';
const KEY = process.env.NVIDIA_API_KEY;
const LLM_URL = process.env.LLM_URL ?? 'https://integrate.api.nvidia.com/v1/chat/completions';
const MODEL = process.env.MODEL ?? 'minimaxai/minimax-m3.1-flash';
const FALLBACK = process.env.FALLBACK_MODEL ?? 'meta/llama-3.2-90b-vision-instruct';
const AGENTS = Number(process.env.AGENTS ?? 20);
const STEPS = Number(process.env.STEPS ?? 12);
const LLM_CONCURRENCY = Number(process.env.LLM_CONCURRENCY ?? 6);
const OUT_DIR = process.env.OUT_DIR ?? '.';
if (!KEY) {
  console.error('Set NVIDIA_API_KEY (a key from build.nvidia.com).');
  process.exit(2);
}

// ---------- the people ----------
const AREAS = {
  'College Road': [20.0063, 73.7608], 'Gangapur Road': [20.0110, 73.7450], Panchavati: [20.0110, 73.7920],
  'Nashik Road': [19.9550, 73.8360], 'Indira Nagar': [19.9800, 73.7900], CIDCO: [19.9750, 73.7550],
};
const PERSONAS = [
  { name: 'Priya', goal: 'find the best spicy misal near College Road for lunch under ₹150', age: 21, diet: 'veg', area: 'College Road', budget: 150, likes: ['misal', 'street food', 'chaat'], style: 'adventurous college student, uses apps all day, spicy food lover' },
  { name: 'Rohan', goal: 'decide where to get biryani tonight, fast', age: 28, diet: 'nonveg', area: 'Gangapur Road', budget: 400, likes: ['biryani', 'kebab', 'chicken'], style: 'IT office worker, orders often, impatient with slow apps' },
  { name: 'Sushila', goal: 'find a simple thali place close to home, without getting lost in the app', age: 67, diet: 'veg', area: 'Panchavati', budget: 200, likes: ['thali', 'puran poli', 'sabudana'], style: 'retired teacher, new to smartphones, reads slowly, cautious about what she taps' },
  { name: 'Aarav', goal: 'find the cheapest good snack near CIDCO after tuition', age: 17, diet: 'egg', area: 'CIDCO', budget: 100, likes: ['vada pav', 'momos', 'egg'], style: 'school student on a small budget, wants the cheapest good snack' },
  { name: 'Meera', goal: 'pick a clean family place for Sunday dosa and check what real people say', age: 34, diet: 'veg', area: 'Indira Nagar', budget: 600, likes: ['south indian', 'cafe', 'dessert'], style: 'mother of two, looks for clean family places, trusts reviews from real people only' },
  { name: 'Imran', goal: 'check whether these ratings are genuine or fake before trusting them', age: 40, diet: 'nonveg', area: 'Nashik Road', budget: 500, likes: ['mutton', 'biryani', 'seafood'], style: 'small business owner, skeptical of online reviews, thinks most are fake' },
  { name: 'Kavya', goal: 'post a photo of today\'s cafe visit and see if people notice', age: 25, diet: 'veg', area: 'College Road', budget: 350, likes: ['cafe', 'pizza', 'coffee'], style: 'food blogger with 8k Instagram followers, wants to post photos and be noticed' },
  { name: 'Sanjay', goal: 'find food without onion and garlic and check if the app can tell', age: 52, diet: 'veg', area: 'Panchavati', budget: 250, likes: ['misal', 'thali', 'chai'], style: 'Jain, avoids onion and garlic, very strict about what he eats' },
  { name: 'Neha', goal: 'find something to eat near Gangapur Road late at night', age: 23, diet: 'nonveg', area: 'Gangapur Road', budget: 300, likes: ['chinese', 'momos', 'shawarma'], style: 'nurse on night shifts, wants food open late near her' },
  { name: 'Vikram', goal: 'find high-protein dishes and judge if the app helps with that', age: 31, diet: 'nonveg', area: 'Indira Nagar', budget: 800, likes: ['continental', 'burger', 'steak'], style: 'gym-goer and fitness tracker, wants high-protein options' },
  { name: 'Pooja', goal: 'explore Nashik\'s food as a newcomer and follow people with good taste', age: 29, diet: 'veg', area: 'CIDCO', budget: 200, likes: ['pav bhaji', 'dosa', 'juice'], style: 'new to Nashik from Pune, knows no places yet, wants to explore' },
  { name: 'Arjun', goal: 'find a new vada pav place, love it and share it with friends', age: 19, diet: 'nonveg', area: 'College Road', budget: 150, likes: ['vada pav', 'chicken', 'rolls'], style: 'engineering student, social, follows friends, loves sharing finds' },
  { name: 'Lata', goal: 'check the ratings of Maharashtrian dishes she knows well and say \'not for me\' to bad ones', age: 45, diet: 'veg', area: 'Nashik Road', budget: 300, likes: ['maharashtrian', 'thalipeeth', 'bhakri'], style: 'home cook who is very critical of restaurant food, gives honest "not for me"s' },
  { name: 'Farhan', goal: 'find the single best biryani in Nashik for his one evening in town', age: 36, diet: 'nonveg', area: 'Panchavati', budget: 450, likes: ['biryani', 'kebab', 'tea'], style: 'travels for work, visits Nashik monthly, wants the single best place for a craving' },
  { name: 'Sneha', goal: 'find a weekend brunch or bakery spot and save a few for later', age: 27, diet: 'egg', area: 'Indira Nagar', budget: 400, likes: ['bakery', 'cake', 'breakfast'], style: 'loves desserts and weekend brunch, uses maps a lot' },
  { name: 'Ganesh', goal: 'find a misal or poha place for after his morning walk', age: 58, diet: 'veg', area: 'Gangapur Road', budget: 200, likes: ['misal', 'poha', 'chai'], style: 'morning walker, wants breakfast spots near the river, Marathi speaker with basic English' },
  { name: 'Tanvi', goal: 'find a healthy option and read details carefully before deciding', age: 22, diet: 'veg', area: 'College Road', budget: 250, likes: ['healthy', 'salad', 'smoothie'], style: 'health-conscious dance student, reads every detail before choosing' },
  { name: 'Rahul', goal: 'find a good fish thali and judge the seafood options strictly', age: 33, diet: 'nonveg', area: 'CIDCO', budget: 500, likes: ['fish', 'seafood', 'thali'], style: 'from coastal Konkan, misses home food, very picky about fish' },
  { name: 'Shekhar', goal: 'make his own cafe near College Road look like the best in town (he will try to game the app)', age: 44, diet: 'nonveg', area: 'College Road', budget: 1000, likes: ['cafe', 'pizza'], style: 'owns a cafe near College Road and quietly wants his own cafe to rank first (tries to game the app)' },
  { name: 'Bunty', goal: 'mess around: silly searches, joke posts and rude comments, to see what breaks', age: 26, diet: 'nonveg', area: 'Nashik Road', budget: 100, likes: ['anything'], style: 'troll who posts silly or rude things and taps everything to see what breaks' },
];

// ---------- helpers ----------
let seed = 4242;
const rand = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const bugs = [];
const friction = [];
const bug = (agent, what, detail) => { bugs.push({ agent, what, detail }); console.log(`  BUG  ${agent}: ${what} ${detail ?? ''}`); };

async function api(method, p, { token, body, form } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['content-type'] = 'application/json'; payload = JSON.stringify(body); }
  try {
    const res = await fetch(BASE + p, { method, headers, body: payload });
    const text = await res.text();
    let json; try { json = JSON.parse(text); } catch { json = text; }
    return { status: res.status, json };
  } catch (e) {
    return { status: 0, json: { message: e.message } };
  }
}

// A small queue so only LLM_CONCURRENCY model calls run at once (the free tier rate-limits).
let running = 0;
const waiting = [];
async function slot(fn) {
  if (running >= LLM_CONCURRENCY) await new Promise((r) => waiting.push(r));
  running++;
  try { return await fn(); } finally { running--; waiting.shift()?.(); }
}
let llmCalls = 0;
async function llm(messages, maxTokens = 1500) {
  for (const model of [MODEL, FALLBACK]) {
    for (let attempt = 0; attempt < 3; attempt++) {
      const out = await slot(async () => {
        llmCalls++;
        try {
          const r = await fetch(LLM_URL, {
            method: 'POST',
            headers: { 'content-type': 'application/json', authorization: `Bearer ${KEY}` },
            body: JSON.stringify({ model, messages, temperature: 0.8, max_tokens: maxTokens }),
          });
          const t = await r.text();
          if (r.status === 429 || r.status >= 500) return { retry: true, status: r.status };
          if (r.status !== 200) return { fail: true, status: r.status, t };
          const content = JSON.parse(t).choices?.[0]?.message?.content ?? '';
          return { content };
        } catch (e) {
          return { retry: true, status: e.message };
        }
      });
      if (out.content) return out.content;
      if (out.fail) break;
      await sleep(2000 * 2 ** attempt);
    }
  }
  return null;
}
function jsonFrom(text) {
  if (!text) return null;
  const whole = text.match(/\{[\s\S]*\}/);
  if (whole) { try { return JSON.parse(whole[0]); } catch { /* fall through */ } }
  // Cut off mid-answer (the model ran out of room): keep the fields that made it.
  const part = text.match(/\{[\s\S]*/)?.[0];
  if (!part) return null;
  const out = {};
  for (const k of ['action', 'n', 'text', 'tab', 'handle', 'dish', 'verdict', 'caption', 'price', 'thought']) {
    const m = part.match(new RegExp(`"${k}"\\s*:\\s*(?:"([^"]*)|(-?\\d+(?:\\.\\d+)?))`));
    if (m) out[k] = m[1] !== undefined ? m[1] : Number(m[2]);
  }
  return out.action ? out : null;
}
async function photo() {
  const raw = Buffer.from(Array.from({ length: 16 * 12 * 3 }, () => Math.floor(rand() * 256)));
  return sharp(raw, { raw: { width: 16, height: 12, channels: 3 } }).resize(800, 600).jpeg().toBuffer();
}
const MEAT = /chicken|mutton|fish|prawn|egg|kheema|keema|beef|pork|shawarma|kebab|seekh|crab|lamb/i;
const pct = (r) => (r?.percent != null ? `${r.percent}% loved, ${r.votes ?? (r.loves ?? 0) + (r.passes ?? 0)} votes` : 'no verdicts yet');

// ---------- screens: what the person sees, as text ----------
function dishLine(c) {
  return `${c.name} at ${c.restaurant?.name ?? '?'}${c.restaurant?.locality ? ` (${c.restaurant.locality})` : ''} — ${pct(c.rating)}${c.priceInr ? `, ₹${c.priceInr}` : ''}${c.isVeg === true ? ', veg' : c.isVeg === false ? ', non-veg' : ''}${c.champion ? `, loved by @${c.champion.handle}` : ''}`;
}
async function show(agent, screen, arg) {
  const t = agent.token;
  const items = [];
  const lines = [];
  const add = (type, id, label) => { items.push({ type, id, label }); lines.push(`[${items.length}] ${label}`); };
  let title = screen;
  if (screen === 'home') {
    const r = await api('GET', `/home?city=${agent.city}&lat=${agent.lat}&lng=${agent.lng}`, { token: t });
    if (r.status !== 200) bug(agent.name, 'Home did not load', r.status);
    const h = r.json ?? {};
    title = 'Home';
    if (h.cravings?.length) lines.push(`Craving chips: ${h.cravings.map((c) => c.label).join(', ')}`);
    if (h.forYou?.length) {
      lines.push('For you:');
      for (const c of h.forYou.slice(0, 5)) {
        add('dish', c.id, dishLine(c) + (c.because?.handle ? ` (because @${c.because.handle} loved it)` : c.because?.category ? ` (because you like ${c.because.category})` : ''));
        if (agent.diet !== 'nonveg' && MEAT.test(c.name) && !(agent.diet === 'egg' && /egg/i.test(c.name))) bug(agent.name, `${agent.diet} user was shown meat in For You`, c.name);
      }
    }
    lines.push("People's picks:");
    for (const c of (h.peoplesPicks ?? []).slice(0, 5)) add('dish', c.id, dishLine(c));
    if (h.nearby?.length) { lines.push('Near you:'); for (const c of h.nearby.slice(0, 3)) add('dish', c.id, dishLine(c)); }
    if (h.following?.length) { lines.push('From people you follow:'); for (const f of h.following.slice(0, 3)) add('dish', f.card.id, `${dishLine(f.card)} (loved by @${f.lovedBy})`); }
  } else if (screen === 'craving' || screen === 'search') {
    const r = screen === 'craving'
      ? await api('GET', `/craving?q=${encodeURIComponent(arg)}&city=${agent.city}&lat=${agent.lat}&lng=${agent.lng}`, { token: t })
      : await api('GET', `/search?q=${encodeURIComponent(arg)}&city=${agent.city}&lat=${agent.lat}&lng=${agent.lng}`, { token: t });
    if (r.status >= 500 || r.status === 0) bug(agent.name, `${screen} failed`, `"${arg}" -> ${r.status}`);
    if (r.status === 400) friction.push({ agent: agent.name, what: `${screen} refused "${arg}"`, message: r.json?.message });
    const j = r.json ?? {};
    title = screen === 'craving' ? `Best "${j.label ?? arg}" in Nashik` : `Search results for "${arg}"`;
    const dishes = j.results ?? j.dishes ?? [];
    if (!dishes.length) lines.push('No dishes with photos yet.');
    for (const c of dishes.slice(0, 8)) add('dish', c.id, dishLine(c));
    const places = j.places ?? j.restaurants ?? [];
    if (places.length) { lines.push('Places that may serve it:'); for (const p of places.slice(0, 5)) add('place', p.id, `${p.name}${p.locality ? `, ${p.locality}` : ''}${p.distanceM != null ? `, ${(p.distanceM / 1000).toFixed(1)} km` : ''}`); }
    if (j.tasters?.length) lines.push(`People to trust for this: ${j.tasters.map((x) => '@' + x.handle).join(', ')}`);
  } else if (screen === 'feed') {
    const r = await api('GET', `/feed?city=${agent.city}&tab=${arg}&limit=10&seed=${agent.handle}&lat=${agent.lat}&lng=${agent.lng}`, { token: t });
    if (r.status >= 500) bug(agent.name, `feed ${arg} failed`, r.status);
    title = `Feed: ${arg}`;
    const cards = r.json?.items ?? [];
    if (!cards.length) lines.push('Nothing here yet.');
    for (const c of cards) {
      add('dish', c.id, dishLine(c));
      if (arg === 'foryou' && agent.diet !== 'nonveg' && MEAT.test(c.name) && !(agent.diet === 'egg' && /egg/i.test(c.name))) bug(agent.name, `${agent.diet} user was shown meat in For You`, c.name);
    }
  } else if (screen === 'explore') {
    const r = await api('GET', `/places?city=${agent.city}&sort=nearest&lat=${agent.lat}&lng=${agent.lng}&limit=10`, { token: t });
    if (r.status >= 500) bug(agent.name, 'Explore failed', r.status);
    title = 'Explore: places near you';
    for (const p of r.json?.items ?? []) add('place', p.id, `${p.name}${p.locality ? `, ${p.locality}` : ''}, ${((p.distanceM ?? 0) / 1000).toFixed(1)} km${p.topDish ? `, best: ${p.topDish.name}` : ', no dishes posted yet'}${p.cuisine ? `, ${p.cuisine}` : ''}`);
  } else if (screen === 'dish') {
    const r = await api('GET', `/dishes/${arg}`, { token: t });
    if (r.status !== 200) { bug(agent.name, 'dish page did not open', `${arg} -> ${r.status}`); return show(agent, 'home'); }
    const d = r.json;
    agent.dish = d;
    title = `Dish: ${d.name}`;
    lines.push(`${d.name} at ${d.restaurant?.name} — ${pct(d.rating)}${d.priceInr ? `, ₹${d.priceInr}` : ''}${d.isVeg === true ? ', veg' : d.isVeg === false ? ', non-veg' : ''}`);
    if (d.champion) lines.push(`Champion: @${d.champion.handle}${d.champion.isExpert ? ' (expert)' : ''}`);
    if (d.recommenders?.length) lines.push(`Loved by: ${d.recommenders.map((x) => '@' + x.handle).join(', ')}`);
    lines.push(`${d.posts?.length ?? 0} photos.${d.posts?.[0]?.caption ? ` Latest caption: "${d.posts[0].caption}"` : ''}`);
    if (d.me) lines.push(`You: ${d.me.recommended ? 'loved it' : d.me.passed ? 'not for me' : 'no verdict'}${d.me.saved ? ', saved' : ''}`);
    for (const m of (d.moreAtPlace ?? []).slice(0, 3)) add('dish', m.id, `Also here: ${dishLine(m)}`);
  } else if (screen === 'place') {
    const r = await api('GET', `/restaurants/${arg}?lat=${agent.lat}&lng=${agent.lng}`, { token: t });
    if (r.status !== 200) { bug(agent.name, 'place page did not open', `${arg} -> ${r.status}`); return show(agent, 'home'); }
    const p = r.json;
    agent.place = p;
    title = `Place: ${p.name}`;
    lines.push(`${p.name}${p.locality ? `, ${p.locality}` : ''}${p.cuisine ? ` · ${p.cuisine}` : ''}${p.distanceM != null ? ` · ${(p.distanceM / 1000).toFixed(1)} km` : ''}${p.openingHours ? ` · hours: ${p.openingHours}` : ''}`);
    if (!p.dishes?.length) lines.push('No dishes posted here yet. You could be the first.');
    for (const c of (p.dishes ?? []).slice(0, 8)) add('dish', c.id, dishLine(c));
  } else if (screen === 'post') {
    const r = await api('GET', `/restaurants/nearby?lat=${agent.lat}&lng=${agent.lng}&radius=3000`, { token: t });
    title = 'Post a dish: pick the place you are at';
    for (const p of (r.json ?? []).slice(0, 10)) add('place', p.id, `${p.name}${p.locality ? `, ${p.locality}` : ''}, ${p.distanceM} m${p.pending ? ' (waiting for approval)' : ''}`);
  } else if (screen === 'me') {
    const r = await api('GET', `/users/${agent.handle}`, { token: t });
    title = 'Your profile';
    lines.push(JSON.stringify({ handle: r.json?.handle, followers: r.json?.followers, following: r.json?.following, loved: r.json?.lovedCount ?? r.json?.stats }).slice(0, 300));
  }
  agent.screen = { name: screen, arg, title, items };
  return `SCREEN: ${title}\n${lines.join('\n') || '(empty)'}`;
}

const ACTIONS = `Actions (reply with ONE as JSON):
  {"action":"open","n":<number>}            open item [n] (a dish or a place)
  {"action":"craving","text":"<dish>"}        "what should I eat": best places for a dish
  {"action":"search","text":"<words>"}        search dishes and places
  {"action":"feed","tab":"top|trending|gems|foryou"}
  {"action":"explore"}  {"action":"home"}  {"action":"post"}  {"action":"me"}
  On a dish: {"action":"love"} {"action":"not_for_me"} {"action":"save"} {"action":"share"}
             {"action":"comment","text":"<comment>"} {"action":"follow","handle":"<handle>"} {"action":"open_place"}
  On "Post a dish": {"action":"submit","n":<place number>,"dish":"<dish name>","verdict":"loved|pass","caption":"<short caption>","price":<rupees or null>}
  {"action":"quit"}  when you would close the app
Always add "thought": what you honestly think of this screen right now (max 20 words).`;

async function act(agent, a) {
  const t = agent.token;
  const sc = agent.screen;
  const item = (n) => sc.items[Number(n) - 1];
  switch (a.action) {
    case 'open': {
      const it = item(a.n);
      if (!it) return show(agent, sc.name, sc.arg);
      return show(agent, it.type === 'place' ? 'place' : 'dish', it.id);
    }
    case 'craving': return show(agent, 'craving', String(a.text ?? '').slice(0, 60) || 'misal');
    case 'search': {
      const q = String(a.text ?? '').slice(0, 60);
      return q.length >= 2 ? show(agent, 'search', q) : show(agent, sc.name, sc.arg);
    }
    case 'feed': return show(agent, 'feed', ['top', 'trending', 'gems', 'foryou'].includes(a.tab) ? a.tab : 'top');
    case 'explore': return show(agent, 'explore');
    case 'post': return show(agent, 'post');
    case 'me': return show(agent, 'me');
    case 'open_place': return agent.dish ? show(agent, 'place', agent.dish.restaurant.id) : show(agent, 'home');
    case 'love': case 'not_for_me': case 'save': {
      if (sc.name !== 'dish') return `(You can only do that on a dish.)\n` + await show(agent, sc.name, sc.arg);
      const kind = { love: 'recommend', not_for_me: 'pass', save: 'save' }[a.action];
      const r = await api('PUT', `/dishes/${sc.arg}/engagements/${kind}`, { token: t });
      if (r.status !== 200) bug(agent.name, `${a.action} failed`, `${r.status} ${JSON.stringify(r.json).slice(0, 120)}`);
      else if (a.action === 'love' && !r.json.me?.recommended) bug(agent.name, 'love did not stick', sc.arg);
      else if (a.action === 'not_for_me' && (!r.json.me?.passed || r.json.me?.recommended)) bug(agent.name, '"not for me" did not stick', sc.arg);
      agent.did.push(`${a.action} ${agent.dish?.name}`);
      return `(Done: ${a.action.replace(/_/g, ' ')}. Now ${pct(r.json?.rating)}.)\n` + await show(agent, 'dish', sc.arg);
    }
    case 'share': {
      if (sc.name !== 'dish') return show(agent, sc.name, sc.arg);
      const r = await api('POST', `/dishes/${sc.arg}/share`, { token: t });
      if (!r.json?.url) bug(agent.name, 'share gave no link', r.status);
      agent.did.push(`share ${agent.dish?.name}`);
      return `(Share link copied: ${r.json?.url})\n` + await show(agent, 'dish', sc.arg);
    }
    case 'comment': {
      const postId = agent.dish?.posts?.[0]?.id;
      if (sc.name !== 'dish' || !postId) return show(agent, sc.name, sc.arg);
      const text = String(a.text ?? '').slice(0, 300) || 'Nice';
      const r = await api('POST', `/posts/${postId}/comments`, { token: t, body: { body: text } });
      if (r.status >= 500) bug(agent.name, 'comment failed', r.status);
      else if (r.status >= 400) friction.push({ agent: agent.name, what: 'comment refused', message: r.json?.message });
      else {
        const list = await api('GET', `/posts/${postId}/comments`, { token: t });
        if (!JSON.stringify(list.json).includes(text.slice(0, 20).replace(/"/g, '\\"'))) bug(agent.name, 'comment not shown after posting', text.slice(0, 40));
      }
      agent.did.push(`comment "${text.slice(0, 50)}"`);
      return `(Comment posted.)\n` + await show(agent, 'dish', sc.arg);
    }
    case 'follow': {
      const h = String(a.handle ?? '').replace(/^@/, '');
      if (!h) return show(agent, sc.name, sc.arg);
      const r = await api('PUT', `/users/${encodeURIComponent(h)}/follow`, { token: t });
      if (r.status >= 500) bug(agent.name, 'follow failed', r.status);
      agent.did.push(`follow @${h}`);
      return `(${r.status === 200 ? 'Following' : 'Could not follow'} @${h}.)\n` + await show(agent, sc.name, sc.arg);
    }
    case 'submit': {
      if (sc.name !== 'post') return show(agent, 'post');
      const place = item(a.n) ?? sc.items[0];
      if (!place) return `(No places near you to pick.)\n` + await show(agent, 'home');
      const pl = (await api('GET', `/restaurants/${place.id}`, { token: t })).json ?? {};
      const f = new FormData();
      f.set('idempotencyKey', 'ai' + Math.random().toString(36).slice(2, 12));
      f.set('restaurantId', place.id);
      f.set('dishName', String(a.dish ?? 'Special').slice(0, 60));
      if (a.caption) f.set('caption', String(a.caption).slice(0, 200));
      if (['loved', 'pass'].includes(a.verdict)) f.set('verdict', a.verdict);
      if (Number.isFinite(Number(a.price)) && Number(a.price) > 0) f.set('priceInr', String(Math.round(Number(a.price))));
      f.set('lat', String((pl.lat ?? agent.lat) + 0.0003)); f.set('lng', String(pl.lng ?? agent.lng)); f.set('capture', 'camera');
      f.set('image', new Blob([await photo()], { type: 'image/jpeg' }), 'dish.jpg');
      const r = await api('POST', '/posts', { token: t, form: f });
      if (r.status >= 500 || r.status === 0) { bug(agent.name, 'posting a dish failed', `${r.status} ${JSON.stringify(r.json).slice(0, 120)}`); return show(agent, 'home'); }
      if (r.status >= 400) { friction.push({ agent: agent.name, what: `post "${a.dish}" refused`, message: r.json?.message }); return `(The app said: ${r.json?.message})\n` + await show(agent, 'home'); }
      const back = await api('GET', `/dishes/${r.json.dishId}`, { token: t });
      if (back.status !== 200 || !back.json.posts?.some((p) => p.id === r.json.id)) bug(agent.name, 'posted photo not on the dish page', r.json.dishId);
      agent.did.push(`posted "${r.json.dishName}" at ${pl.name} (${a.verdict ?? 'no verdict'}, visit ${r.json.visit})`);
      return `(Posted! It went onto "${r.json.dishName}"${r.json.dishName !== a.dish ? ` — the app matched your "${a.dish}" to an existing dish` : ''}.)\n` + await show(agent, 'dish', r.json.dishId);
    }
    default:
      return `(Not an action here.)\n` + await show(agent, sc.name, sc.arg);
  }
}

// ---------- one person's session ----------
async function runAgent(p, i, city, cats) {
  const handle = `ai_${p.name.toLowerCase()}_${i}`.slice(0, 20);
  const reg = await api('POST', '/auth/register', { body: { email: `${handle}@ai.test`, password: 'correct horse battery', handle, acceptTerms: true } });
  if (reg.status !== 201) { bug(p.name, 'sign-up failed', `${reg.status} ${JSON.stringify(reg.json).slice(0, 100)}`); return null; }
  const [lat, lng] = AREAS[p.area];
  const agent = { ...p, handle, token: reg.json.token, city, lat: lat + (rand() - 0.5) * 0.01, lng: lng + (rand() - 0.5) * 0.01, did: [], thoughts: [] };
  // The taste questions, answered the way this person would.
  const picked = cats.filter((c) => p.likes.some((l) => c.name.toLowerCase().includes(l) || c.slug.includes(l.split(' ')[0]))).map((c) => c.id).slice(0, 6);
  const tr = await api('PUT', '/me/taste', { token: agent.token, body: { categories: picked, diet: p.diet, spice: 1 + Math.floor(rand() * 5) } });
  if (tr.status !== 200) bug(p.name, 'taste questions failed', tr.status);

  const system = `You are ${p.name}, ${p.age}, living near ${p.area} in Nashik, India. Diet: ${p.diet === 'veg' ? 'vegetarian' : p.diet === 'egg' ? 'vegetarian who eats eggs' : 'eat everything'}. Budget per meal: about ₹${p.budget}. Favourite food: ${p.likes.join(', ')}. You are: ${p.style}.
Today you opened WorthAbite, a new app that ranks each dish in Nashik by what real people loved, because you want to: ${p.goal}.
Use it the way you really would, with your own patience and habits. You are a real, demanding user, not a polite one: when you love or pass on a dish, imagine you have eaten it and judge strictly by your taste, diet and budget (people say "not for me" to about half of what they try). In "thought", say plainly what is confusing, missing, slow or annoying, as well as what is good. Quit when you would really close the app. Reply with JSON only.`;
  let screen = await show(agent, 'home');
  const history = [];
  for (let step = 0; step < STEPS; step++) {
    const msg = `${screen}\n\n${ACTIONS}`;
    const reply = await llm([{ role: 'system', content: system }, ...history.slice(-4), { role: 'user', content: msg }]);
    const a = jsonFrom(reply);
    if (!a?.action) { agent.thoughts.push('(model gave no usable answer)'); continue; }
    if (a.thought) agent.thoughts.push(`[${agent.screen.title}] ${a.thought}`);
    history.push({ role: 'user', content: msg.slice(0, 1500) }, { role: 'assistant', content: JSON.stringify(a) });
    if (a.action === 'quit') { agent.did.push(`quit after ${step + 1} steps`); break; }
    // Taps on a dish and posts are logged by act() with their details.
    if (!['love', 'not_for_me', 'save', 'share', 'comment', 'follow', 'submit'].includes(a.action)) {
      agent.did.push(`${a.action}${a.text ? ` "${a.text}"` : a.tab ? ` ${a.tab}` : a.n ? ` #${a.n}` : ''}`);
    }
    screen = await act(agent, a);
  }
  const reviewText = await llm([
    { role: 'system', content: system },
    { role: 'user', content: `You just used WorthAbite for a few minutes. What you did: ${agent.did.join('; ')}.\nYour thoughts along the way: ${agent.thoughts.join(' | ')}\n\nWrite your honest review as a real, demanding user, not a polite one (most new apps get 2 to 4 stars; name concrete problems). JSON: {"stars":1-5,"liked":["..."],"confusing":["..."],"missing":["..."],"trust_the_ratings":"yes|no|unsure","would_use_again":"yes|no","quote":"<one sentence in your own voice>"}` },
  ], 2000);
  agent.review = jsonFrom(reviewText) ?? { quote: '(no review)' };
  console.log(`  ${p.name.padEnd(8)} ${agent.review.stars ?? '?'}★  ${agent.did.length} actions  "${String(agent.review.quote ?? '').slice(0, 90)}"`);
  return { name: p.name, age: p.age, diet: p.diet, area: p.area, style: p.style, handle, did: agent.did, thoughts: agent.thoughts, review: agent.review };
}

// ---------- run ----------
const T0 = Date.now();
const near = await api('GET', '/cities/nearest?lat=20.0059&lng=73.7898');
if (near.status !== 200) { console.error(`Server not reachable at ${BASE}`); process.exit(2); }
const city = near.json.city.id;
const cats = (await api('GET', '/categories')).json;
const people = Array.from({ length: AGENTS }, (_, i) => PERSONAS[i % PERSONAS.length]);
console.log(`${people.length} AI users, up to ${STEPS} steps each, model ${MODEL}`);
const results = (await Promise.all(people.map((p, i) => runAgent(p, i, city, cats)))).filter(Boolean);

// The reviews, summarised.
const reviews = results.map((r) => ({ who: `${r.name}, ${r.age}, ${r.style}`, ...r.review }));
const summary = await llm([
  { role: 'system', content: 'You are a product researcher. Be concrete and brief. Plain text, no markdown tables.' },
  { role: 'user', content: `These are reviews from people who tried WorthAbite, a dish-ranking food app for Nashik:\n${JSON.stringify(reviews).slice(0, 14000)}\n\nWrite: 1) the 5 things people liked most, 2) the 5 biggest confusions or complaints, 3) the 5 most wanted missing features, 4) which kinds of people were least happy and why. Each point one line, and say how many people mentioned it.` },
], 3000);

const stars = results.map((r) => Number(r.review.stars)).filter((n) => n >= 1 && n <= 5);
const avg = stars.length ? (stars.reduce((a, b) => a + b, 0) / stars.length).toFixed(1) : '?';
const again = results.filter((r) => r.review.would_use_again === 'yes').length;
const trust = results.filter((r) => r.review.trust_the_ratings === 'yes').length;
const md = [
  `# AI test users: ${results.length} people, ${((Date.now() - T0) / 60000).toFixed(1)} min, ${llmCalls} model calls (${MODEL})`,
  '',
  `**Average rating ${avg}★** · would use again ${again}/${results.length} · trust the ratings ${trust}/${results.length}`,
  '',
  `## Bugs found by the checks (${bugs.length})`,
  ...(bugs.length ? bugs.map((b) => `- **${b.agent}**: ${b.what} ${b.detail ?? ''}`) : ['- None: every action did what it should.']),
  '',
  `## Friction: the app said no (${friction.length})`,
  ...friction.map((f) => `- ${f.agent}: ${f.what} — "${f.message}"`),
  '',
  '## What they said (summary)',
  summary ?? '(no summary)',
  '',
  '## Each person',
  ...results.map((r) => `### ${r.name}, ${r.age} (${r.diet}, ${r.area}): ${r.review.stars ?? '?'}★\n_${r.style}_\n\n> ${r.review.quote ?? ''}\n\n- Did: ${r.did.join(' → ')}\n- Liked: ${(r.review.liked ?? []).join('; ')}\n- Confusing: ${(r.review.confusing ?? []).join('; ')}\n- Missing: ${(r.review.missing ?? []).join('; ')}\n`),
].join('\n');
fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(path.join(OUT_DIR, 'ai-users-report.md'), md);
fs.writeFileSync(path.join(OUT_DIR, 'ai-users-traces.json'), JSON.stringify({ bugs, friction, results }, null, 1));
console.log(`\nAverage ${avg}★, would use again ${again}/${results.length}, bugs ${bugs.length}, friction ${friction.length}, ${llmCalls} model calls`);
console.log(`Report: ${path.join(OUT_DIR, 'ai-users-report.md')}`);
process.exit(bugs.length ? 1 : 0);
