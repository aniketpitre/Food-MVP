# Real photos of cafes and restaurants

FoodFeed can show real photos of places from three sources. All three are legal to use,
each with different rules. Photos copied from Google Maps, Zomato, Swiggy or Instagram
are not used: their terms forbid copying, and every photo belongs to the person who took it.

| Source | What you get | Cost | Stored on our server? | Needs |
|---|---|---|---|---|
| **Your users** (posts, stories) | Photos of the actual dishes | Free | Yes | Nothing, already built |
| **Wikimedia Commons** | Photos of well-known places that OpenStreetMap links to (often older or famous places) | Free | Yes, with the author and licence shown | Nothing |
| **Mapillary** | Street-level photos taken near the place, facing it (its shopfront) | Free | Yes, with the author and CC BY-SA 4.0 shown | A free token |
| **Google Maps** (Places API) | The photos on the place's Google Maps listing | 1,000 photo loads a month free, then about $7 per 1,000 | **No.** Fetched live; only the place id is kept | A Google Cloud key with billing |

## 1. Wikimedia Commons and Mapillary (stored, free)

1. (Optional, for Mapillary) Get a token: sign in at https://www.mapillary.com/dashboard/developers,
   register an application and copy its **client token**. Put it in `.env`:
   ```
   MAPILLARY_TOKEN=MLY|...
   ```
   then `docker compose up -d` so the API picks it up.
2. Import the places first (if you haven't): `docker compose exec api node dist/cli.js import-places nashik`
3. Fetch photos: `docker compose exec api node dist/cli.js fetch-place-photos nashik`
   (add a number to limit how many places it checks, e.g. `fetch-place-photos nashik 200`).

Each place is checked once every 30 days. The photos are re-encoded like our own (WebP,
metadata removed) and show on the place page, on the map's place card and in the map's
card strip, always with their credit ("Photo: name, licence, via source").

How the Mapillary picker works: it asks for photos within about 55 m of the place, keeps
those taken 4 to 45 m away whose camera points at the place (within 40°), and prefers the
newest and closest. Coverage depends on the city: busy streets in big Indian cities are
often covered, small lanes may not be.

## 2. Google Maps photos (live, never stored)

Google's terms allow showing Places photos only if you fetch them through the API, show the
photographers' names, don't store the photos, and **don't show them on a non-Google map**.
So FoodFeed shows them on the **place page only** (not on our map), with "Google Maps" and
each photographer's name under the photo.

1. In https://console.cloud.google.com create a project, turn on billing, enable
   **Places API (New)**, and create an API key. Restrict the key to Places API (New) and,
   if you can, to your server's IP address.
2. In Google Cloud, set a quota for "Places API (New)" photo requests (APIs & Services →
   Places API (New) → Quotas) so you can never be billed more than you expect.
3. In `.env`:
   ```
   GOOGLE_PLACES_API_KEY=AIza...
   GOOGLE_PHOTOS_DAILY_LIMIT=30
   ```
   then `docker compose up -d`.

The server finds each place's Google id once with an id-only search, then loads up to three
photos when someone opens the place page. Results are kept in memory for an hour so repeat
views don't cost again. `GOOGLE_PHOTOS_DAILY_LIMIT` (default 30, about 900 a month) keeps
you inside the free 1,000 a month.

Never paste the key in chat or commit it; it only goes in `.env` on the server.

## 3. The demo dishes

The invented demo places (marked **DEMO** in the app) show real photos of each dish (a
burger is a burger). They were picked by hand from Wikimedia Commons, only files under
CC0, public domain, CC BY or CC BY-SA (free to use, including commercially, as long as the
author is credited), and they ship with the server in `backend/seed/images/` with their
authors and licences in `backend/seed/images/credits.json`. The app shows "Photo: author,
licence, via Wikimedia Commons" under each one. No internet access is needed on the server.

- New server: `seed` puts them in.
- Server seeded earlier (with the drawn demo pictures): run
  `docker compose exec api node dist/cli.js seed-demo`. It restores any removed demo places
  and demo accounts and swaps in the real photos. `demo-photos` does only the photo swap.
- Remove everything demo: `remove-demo --all`.

To change a photo: run the **Demo dish photos** workflow in GitHub Actions with mode
`candidates` (and the dish key), look at `backend/demo-candidates/<dish>.jpg`, put the
chosen file in `backend/scripts/demo-picks.json`, then run it with mode `pick`.

## 4. Owners and users

The best photos are still the ones people post. Each post shows on the dish and in the
feed, and a place's best dish photo becomes its picture on the map.
