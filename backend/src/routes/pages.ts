import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

const esc = (s: unknown) =>
  String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/** The WorthAbite mark: a white frying pan with a bite out of it, a chef's hat and a yellow sizzle (it sits on charcoal). */
export const LOGO_MARK = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 20 156 130" width="40" height="33" aria-hidden="true">
<path d="M66,107 L28,98.5" stroke="#FFFFFF" stroke-width="13.5" stroke-linecap="round" fill="none"/>
<circle cx="33.0" cy="99.2" r="2.6" fill="#2B1B12"/>
<path d="M123.78,71.68 A40,40 0 1,0 144.85,103.51 A6.76,6.76 0 0,1 134.44,100.25 A6.76,6.76 0 0,1 126.53,92.75 A6.76,6.76 0 0,1 122.72,82.53 A6.76,6.76 0 0,1 123.78,71.68 Z" fill="#FFFFFF"/>
<path d="M99.27,74.5 A33,33 0 0,0 84.68,133 A46,46 0 0,1 99.27,74.5 Z" fill="#2B1B12"/>
<path d="M134.5,64 L141.6,46.5 M147,72 L162.6,56.6 M151.3,82.9 L169,80.3" stroke="#F5C518" stroke-width="4" stroke-linecap="round" fill="none"/>
<g transform="translate(80,67) rotate(-26) scale(1.0)">
<path d="M-13,-10 C-24,-11 -25,-28 -12,-28 C-10,-39 7,-41 9,-31 C15,-38 28,-32 22,-21 C28,-17 25,-10 13,-10 Z" fill="#FFFFFF"/>
<path d="M-12,-10 L12,-10 L12,-4 L-12,-4 Z" fill="#FFFFFF"/>
<path d="M-12,-4 L12,-4 L12,2 L-12,2 Z" fill="#EED3AE"/>
<path d="M-12,-5.6 L12,-5.6" stroke="#F0571E" stroke-width="2.4" fill="none"/>
</g>
</svg>`;

const FAVICON = `data:image/svg+xml,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><rect width="200" height="200" rx="44" fill="#2B1B12"/><g transform="translate(100 100) scale(1.2) translate(-100 -84)">${LOGO_MARK.replace(/<svg[^>]*>|<\/svg>/g, '')}</g></svg>`,
)}`;

function page(title: string, body: string, head = ''): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>${head}
<link rel="icon" href="${FAVICON}">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Poppins:wght@700&display=swap">
<meta name="theme-color" content="#151413">
<style>
  :root { --brand:#151413; --on-brand:#FBF7EF; --accent:#F5C518; --ink:#151413; --muted:#6A6259; --bg:#FBF7EF; --card:#fff; }
  @media (prefers-color-scheme: dark) { :root { --brand:#F5C518; --on-brand:#151413; --ink:#F6F1EA; --muted:#B8AFA4; --bg:#151413; --card:#221F1D; } }
  * { box-sizing: border-box; }
  body { margin:0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background:var(--bg); color:var(--ink); line-height:1.55; }
  main { max-width: 640px; margin: 0 auto; padding: 24px 16px 48px; }
  h1 { font-size: 1.6rem; margin: .2em 0; } h2 { font-size: 1.15rem; margin-top: 1.6em; }
  .brand { display:inline-flex; align-items:center; gap:8px; background:#2B1B12; color:#fff; padding:6px 14px 6px 8px; border-radius:16px; font-family: Poppins, system-ui, sans-serif; font-weight: 700; font-size: 1.3rem; text-decoration:none; }
  .brand b { color: var(--accent); font-weight: 700; }
  .card { background: var(--card); border-radius: 18px; overflow: hidden; box-shadow: 0 6px 24px rgba(0,0,0,.08); }
  .card img { width: 100%; aspect-ratio: 4/5; object-fit: cover; display: block; }
  .pad { padding: 16px; } .muted { color: var(--muted); }
  .btn { display:inline-block; background: var(--brand); color:var(--on-brand); padding: 12px 18px; border-radius: 12px; text-decoration:none; font-weight: 700; margin: 8px 8px 0 0; }
  .btn.alt { background: transparent; color: var(--brand); border: 2px solid var(--brand); }
  .note { background: #fff3cd; color:#5c4400; padding: 10px 12px; border-radius: 10px; font-size: .9rem; }
</style></head>
<body><main><a class="brand" href="/">${LOGO_MARK}<span>Worth<b>Abite</b></span></a>${body}</main></body></html>`;
}

const DRAFT_NOTE =
  '<p class="note">Draft template. Replace the bracketed parts and have it reviewed by a lawyer before inviting people outside your pilot.</p>';

export async function pageRoutes(app: FastifyInstance) {
  const { db, config } = app;

  app.get('/', async (_req, reply) =>
    reply.type('text/html').send(
      page(
        'WorthAbite',
        `<h1>Is it worth a bite?</h1>
         <p class="muted">Ask the people who tried it. Specific dishes at specific places, ranked by what real people decided.</p>
         <a class="btn" href="${esc(config.appDownloadUrl)}">Download the Android app</a>
         <p><a href="/privacy">Privacy</a> · <a href="/terms">Terms</a> · <a href="/delete-account">Delete your account</a></p>`,
      ),
    ),
  );

  // Share-link preview: opens in any browser, offers the app.
  app.get('/d/:id', async (req, reply) => {
    const p = z.object({ id: z.string().uuid() }).safeParse(req.params);
    if (!p.success) return reply.code(404).type('text/html').send(page('Not found', '<h1>Dish not found</h1>'));
    const { rows } = await db.query(
      `select d.name, r.name as place, r.locality, coalesce(s.recommenders, 0) as n,
              (select image_path from posts where id = dish_cover(d.id, null)) as img
       from dishes d join restaurants r on r.id = d.restaurant_id
       left join dish_stats s on s.dish_id = d.id
       where d.id = $1 and d.status = 'live' and r.status = 'active'`,
      [p.data.id],
    );
    const d = rows[0];
    if (!d) return reply.code(404).type('text/html').send(page('Not found', '<h1>Dish not found</h1>'));
    const img = d.img ? `${config.publicUrl}${d.img}` : '';
    const title = `${d.name} at ${d.place}`;
    const rec = Number(d.n) > 0 ? `Recommended by ${Number(d.n)} ${Number(d.n) === 1 ? 'person' : 'people'}` : 'New on WorthAbite';
    const head = `
      <meta property="og:title" content="${esc(title)}">
      <meta property="og:description" content="${esc(rec)}${d.locality ? ' · ' + esc(d.locality) : ''}">
      ${img ? `<meta property="og:image" content="${esc(img)}">` : ''}`;
    return reply.type('text/html').send(
      page(
        title,
        `<div class="card">${img ? `<img src="${esc(img)}" alt="${esc(d.name)}">` : ''}
          <div class="pad"><h1>${esc(d.name)}</h1>
          <div class="muted">${esc(d.place)}${d.locality ? ' · ' + esc(d.locality) : ''}</div>
          <p><strong>${esc(rec)}</strong></p>
          <a class="btn" href="worthabite://dish/${esc(p.data.id)}">Open in the app</a>
          <a class="btn alt" href="${esc(config.appDownloadUrl)}">Get the app</a></div></div>`,
        head,
      ),
    );
  });

  app.get('/privacy', async (_req, reply) =>
    reply.type('text/html').send(
      page(
        'Privacy Policy',
        `<h1>Privacy Policy</h1>${DRAFT_NOTE}
         <p>[Your name or company] runs WorthAbite. Contact: <a href="mailto:${esc(config.contactEmail)}">${esc(config.contactEmail)}</a>.</p>
         <h2>What we collect</h2>
         <ul><li>Account: email, a password (stored only as a salted hash), your handle and optional display name.</li>
         <li>What you post: dish photos (we remove photo location data on upload), dish names, captions, prices and places you add.</li>
         <li>What you do: recommendations, saves, "tried it", shares, reports and blocks.</li>
         <li>Approximate location, only while the app is open, to pick your city and show distances. It is not stored.</li>
         <li>A random device ID used to count how often dishes are viewed.</li></ul>
         <h2>Why</h2><p>To run the feed, rank dishes, keep the community safe and fix problems. We do not sell data and show no ads.</p>
         <h2>Where it is stored</h2><p>On servers we operate ([hosting location]). Nothing is shared with third parties.</p>
         <h2>How long</h2><p>Until you delete your account. Deleting it removes your profile, photos and actions immediately; backups roll off within [30] days.</p>
         <h2>Your choices</h2><p>You can withdraw location permission at any time in Android settings, and delete your account in the app (Profile → Settings → Delete account) or as described on <a href="/delete-account">this page</a>.</p>
         <h2>Age</h2><p>WorthAbite is for adults aged 18 or over. We don't knowingly collect data from anyone younger; if you believe a child has an account, write to us and we will delete it.</p>
         <h2>Grievance officer</h2><p>[Name], ${esc(config.contactEmail)}. We acknowledge complaints within 24 hours.</p>`,
      ),
    ),
  );

  app.get('/terms', async (_req, reply) =>
    reply.type('text/html').send(
      page(
        'Terms of Use',
        `<h1>Terms of Use</h1>${DRAFT_NOTE}
         <ul><li>You must be 18 or older.</li>
         <li>Post only photos you took or have permission to use. You keep ownership and give WorthAbite a licence to show them in the app and in share links.</li>
         <li>Mark a post as sponsored if you were paid or got the food or anything else for free.</li>
         <li>No fake recommendations, multiple accounts, spam, abuse, or photos of people without their consent.</li>
         <li>Allergen and veg/non-veg information is provided by users and is not verified. Check with the restaurant.</li>
         <li>Restaurants cannot pay to rank higher.</li>
         <li>We may remove content or accounts that break these rules. Complaints: ${esc(config.contactEmail)}.</li></ul>`,
      ),
    ),
  );

  app.get('/delete-account', async (_req, reply) =>
    reply.type('text/html').send(
      page(
        'Delete your WorthAbite account',
        `<h1>Delete your account</h1>
         <p>In the app: <strong>Profile → Settings → Delete my account</strong>. Deletion is immediate.</p>
         <p>If you can no longer open the app, email <a href="mailto:${esc(config.contactEmail)}">${esc(config.contactEmail)}</a> from the address you signed up with and we will delete it within 7 days.</p>
         <h2>What is removed</h2><p>Your profile, email, password hash, photos, recommendations, saves, reports and blocks.</p>
         <h2>What stays</h2><p>Dish and place names you added stay, because other people's photos may be attached to them. They no longer show your name.</p>`,
      ),
    ),
  );
}
