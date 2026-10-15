import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

const esc = (s: unknown) =>
  String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

function page(title: string, body: string, head = ''): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>${head}
<style>
  :root { --brand:#E8430C; --ink:#1B1B1F; --muted:#6B6B73; --bg:#FFF8F4; --card:#fff; }
  @media (prefers-color-scheme: dark) { :root { --ink:#F2F0EE; --muted:#A8A6A3; --bg:#141210; --card:#1F1C1A; } }
  * { box-sizing: border-box; }
  body { margin:0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background:var(--bg); color:var(--ink); line-height:1.55; }
  main { max-width: 640px; margin: 0 auto; padding: 24px 16px 48px; }
  h1 { font-size: 1.6rem; margin: .2em 0; } h2 { font-size: 1.15rem; margin-top: 1.6em; }
  .brand { color: var(--brand); font-weight: 800; letter-spacing: -.02em; text-decoration:none; }
  .card { background: var(--card); border-radius: 18px; overflow: hidden; box-shadow: 0 6px 24px rgba(0,0,0,.08); }
  .card img { width: 100%; aspect-ratio: 4/5; object-fit: cover; display: block; }
  .pad { padding: 16px; } .muted { color: var(--muted); }
  .btn { display:inline-block; background: var(--brand); color:#fff; padding: 12px 18px; border-radius: 12px; text-decoration:none; font-weight: 700; margin: 8px 8px 0 0; }
  .btn.alt { background: transparent; color: var(--brand); border: 2px solid var(--brand); }
  .note { background: #fff3cd; color:#5c4400; padding: 10px 12px; border-radius: 10px; font-size: .9rem; }
</style></head>
<body><main><a class="brand" href="/">FoodFeed</a>${body}</main></body></html>`;
}

const DRAFT_NOTE =
  '<p class="note">Draft template. Replace the bracketed parts and have it reviewed by a lawyer before inviting people outside your pilot.</p>';

export async function pageRoutes(app: FastifyInstance) {
  const { db, config } = app;

  app.get('/', async (_req, reply) =>
    reply.type('text/html').send(
      page(
        'FoodFeed',
        `<h1>The dishes your city recommends</h1>
         <p class="muted">A local feed of specific dishes at specific places, ranked by how many real people recommend them.</p>
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
              (select image_path from posts p where p.dish_id = d.id and p.status = 'live'
               order by (p.id = d.cover_post_id) desc, p.created_at desc limit 1) as img
       from dishes d join restaurants r on r.id = d.restaurant_id
       left join dish_stats s on s.dish_id = d.id
       where d.id = $1 and d.status = 'live' and r.status = 'active'`,
      [p.data.id],
    );
    const d = rows[0];
    if (!d) return reply.code(404).type('text/html').send(page('Not found', '<h1>Dish not found</h1>'));
    const img = d.img ? `${config.publicUrl}${d.img}` : '';
    const title = `${d.name} at ${d.place}`;
    const rec = Number(d.n) > 0 ? `Recommended by ${Number(d.n)} ${Number(d.n) === 1 ? 'person' : 'people'}` : 'New on FoodFeed';
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
          <a class="btn" href="foodfeed://dish/${esc(p.data.id)}">Open in the app</a>
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
         <p>[Your name or company] runs FoodFeed. Contact: <a href="mailto:${esc(config.contactEmail)}">${esc(config.contactEmail)}</a>.</p>
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
         <h2>Age</h2><p>FoodFeed is for people aged 18 or over.</p>
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
         <li>Post only photos you took or have permission to use. You keep ownership and give FoodFeed a licence to show them in the app and in share links.</li>
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
        'Delete your FoodFeed account',
        `<h1>Delete your account</h1>
         <p>In the app: <strong>Profile → Settings → Delete my account</strong>. Deletion is immediate.</p>
         <p>If you can no longer open the app, email <a href="mailto:${esc(config.contactEmail)}">${esc(config.contactEmail)}</a> from the address you signed up with and we will delete it within 7 days.</p>
         <h2>What is removed</h2><p>Your profile, email, password hash, photos, recommendations, saves, reports and blocks.</p>
         <h2>What stays</h2><p>Dish and place names you added stay, because other people's photos may be attached to them. They no longer show your name.</p>`,
      ),
    ),
  );
}
