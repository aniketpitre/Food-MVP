<p align="center"><img src="docs/brand/worthabite-logo-1024.png" width="160" alt="WorthAbite logo"></p>

<h1 align="center">WorthAbite</h1>

<p align="center"><b>Which dish is worth a bite, and where?</b><br>
A local food app that ranks <b>one dish at one place</b> by what real people who ate it decided.</p>

<p align="center">
<a href="https://github.com/aniketpitre/Food-MVP/releases/latest"><b>Download the Android app</b></a> ·
<a href="docs/PEOPLES_PICK.md">How the ranking works</a> ·
<a href="docs/REAL_PLACES.md">Real places</a> ·
<a href="docs/WorthAbite_Android_MVP_Plan.md">Product plan</a>
</p>

---

## Contents

1. [The idea](#the-idea)
2. [What's in the app](#whats-in-the-app)
3. [How the algorithm works](#how-the-algorithm-works)
4. [Real places and the demo](#real-places-and-the-demo)
5. [How it's built](#how-its-built)
6. [Run the server](#run-the-server)
7. [The admin dashboard](#the-admin-dashboard)
8. [The Android app: install, release, sign](#the-android-app-install-release-sign)
9. [Develop and test](#develop-and-test)
10. [Repository map](#repository-map)
11. [Credits and licences](#credits-and-licences)

---

## The idea

Star ratings answer the wrong question. A 4.2★ restaurant can serve a forgettable biryani and a
legendary misal, and anyone can leave a star. When you're hungry you want to know: **"I want pav
bhaji. Where should I go?"**

WorthAbite answers that by ranking **dishes, not restaurants**:

- **Real people vote on a dish they ate**: *Loved it* or *Not for me*.
- **Not every vote counts the same.** People whose picks others later agree with earn trust for
  that kind of food. New accounts and accounts voting in lockstep count for little. Owners can't
  vote up their own menu.
- **Photos come from diners**, with checks that make faking or copying them costly.
- **The places are real**: every restaurant, cafe and stall in the city, from open map data.

It starts in **Nashik** and works in any Indian city (about 900 cities with 50,000+ people are built in).

---

## What's in the app

### Discover

| | |
|---|---|
| **Home: "What are you craving?"** | Type *pav bhaji* (any spelling: paav bhaji, pavbhaji) or tap a chip and get the best pav bhaji in your city ranked by **People's Pick**, with "82 % loved it · 34 votes" and the most trusted person who loved it. The headline changes with the time of day (breakfast, lunch, chai time, dinner, late-night munchies). |
| **For You** | Dishes loved by people with your taste and the people you follow, each saying why ("Loved by @diya · 92 % taste match"). Vegetarians never see non-veg dishes. |
| **Explore deck** | A full-screen card deck you swipe through like Reels: **Top**, **Trending** (this week), **Hidden gems**, **For You**, **Following** (photos from people you follow, newest first) and **Nearby**. Double-tap a photo to love it. |
| **Places** | Every place in the city with filters (veg, area, cuisine, dish type, distance), sorted by most loved, nearest, most dishes or newest. |
| **Search with autocomplete** | Suggestions as you type: dishes people posted, cuisines, dish types, places, areas (College Road, Gangapur…) and people, with the typed part in bold. Before you type: recent searches, popular now, **20 cuisines** (Maharashtrian, North Indian & Punjabi, South Indian, Street Food, Mughlai & Biryani, Italian, Mexican, Thai/Japanese/Korean, Cafe…) and **33 dish types** to browse. |

### Map

- **Google Maps** in WorthAbite's own colours: cream ground, saffron roads and soft green parks by day; espresso and ember at night. Google's own shop icons are hidden, so the only places on it are WorthAbite's.
- **A food logo per place** (🌶️ misal, ☕ cafe, 🍕 pizza…). Places with dishes always show; the rest appear when you zoom into a neighbourhood. Tap a logo and a card swings up in 3D with the photo, the best dish, the rating, hours, distance and **Take me there**.
- **Friends' picks / Taste match** filters show only the places your friends or your taste matches loved.
- **On the way**: pick a city (or long-press the map) and see the best dish at every place within 3 km of the drive, in the order you reach them.
- **In-app directions**: the route on the map, the next turn, time and distance left, a new route if you go off it, and a "you're here" card with the place's best dishes.
- Without a Google Maps key, or on phones without Google Play services, the maps use an open MapLibre map in the same colours. If Google's map doesn't load within 10 seconds, the app switches over by itself.

### Dishes and places

- **Dish page**: photos from different people, the place, the rating, who loved it, comments, *Open in Maps*, and more dishes at the same place.
- **Place page**: its dishes in People's Pick order ("Best dishes here"), cuisine, hours, phone, website, photos and directions.
- **Loved it / Not for me / Save / Tried / Send** on every dish.

### Post a dish (under 30 seconds)

- Camera or gallery photo, shrunk on the phone with location data removed.
- **Pick the place on a map**: places near you show as dots with names. Tap one, or put the pin on the spot to add a new place.
- Dish-name suggestions, with *Did you mean "Butter Chicken"?* so votes don't split across spellings.
- Price, veg / non-veg, caption, a "sponsored / got it free" label, then **Worth a bite?** (Loved it / Not for me).
- **Verified visit** badge when you post at the place with a fresh photo.

### Social

- **Profiles**: photo, bio, username, posts, loved dishes, **Taste Match %**, followers / following, and a story ring.
- **Follow from anywhere**. It updates at once everywhere, and new followers see your older posts too.
- **Comments**: threaded replies, likes, @mentions, quick emoji, and controls for who can comment.
- **Activity tab**: follows, comments, replies, mentions, likes, loves and agreements. Tapping a comment opens the thread.
- **Stories**: 24-hour photos tagged with a dish or place. A Home row, a full-screen viewer (cube swipe, tap to step, hold to pause), likes, and "Seen by" updating live.
- **Share dishes and posts** with your followers and the people you follow. The chat holds shared dishes and posts, with reactions (double-tap ❤️), seen receipts and unsend; there's no free typing, which keeps it about food. People who turned sharing off show greyed out with the reason. Anything can also be shared to other apps.
- **Your taste**: dish types you love, veg / egg / non-veg and spice level, asked once and learned from your votes. **People with your taste** suggestions on Home.
- **Safety**: report posts, dishes, comments and people; block; auto-hide after 3 reports from established accounts; admin moderation.

### Account

- Guest browsing with no sign-up wall. Sign-in is asked for on your first love, save or post, and the action completes right after.
- **Two-step sign-in** with an email code, signed-in devices (sign out elsewhere), change email (codes to both addresses), forgot username, reset password, change password, and delete account (in the app and on the web).
- Profile changes (photo, name, username) show for everyone at once: comments, chats, lists, the feed.

### Everything else

- **Notifications** for messages, follows, comments, stories, new dishes nearby and more. They're instant with Firebase (optional); otherwise the app checks every 30 seconds while open and every 15 minutes in the background.
- **In-app updates**: the app checks GitHub Releases and offers the new version.
- **Light / dark / system theme**, and **Lite mode** (automatic on low-RAM phones) that drops blur and heavy animation.
- **Motion with meaning**: cards land in a choreographed sequence; love bursts with spice confetti and floating hearts and a light vibration; the tab dock moves like liquid; photos morph from card to page.

| Feed | Hidden gems (dark) | Dish | Place |
|---|---|---|---|
| ![Feed](docs/screenshots/01_feed_top.jpg) | ![Hidden gem](docs/screenshots/02_feed_hidden_gem_dark.jpg) | ![Dish](docs/screenshots/05_dish_detail.jpg) | ![Place](docs/screenshots/06_restaurant.jpg) |
| **Home** | **People's Pick: pav bhaji** | **Profile** | **Activity** |
| ![Home](docs/screenshots/17_home.jpg) | ![Craving](docs/screenshots/18_craving_pav_bhaji.jpg) | ![Profile](docs/screenshots/10_profile.jpg) | ![Activity](docs/screenshots/19_activity.jpg) |
| **Search suggestions** | **A cuisine** | **For You** | **Places** |
| ![Autocomplete](docs/device/29b_search_autocomplete.jpg) | ![Cuisine](docs/device/33b_search_cuisine.jpg) | ![For you](docs/device/51_home_for_you.jpg) | ![Places](docs/device/61_explore_places.jpg) |
| **Map place card** | **Food on the way** | **In-app directions** | **Share** |
| ![Map](docs/device/05_map_popup.jpg) | ![On the way](docs/device/58_map_food_on_the_way.jpg) | ![Navigate](docs/device/65_navigate.jpg) | ![Share](docs/device/40_share_sheet.jpg) |

The `docs/device/` screenshots are taken on an Android emulator running against a real server by the
**Device smoke test** workflow. The `docs/screenshots/` ones come from the app's screenshot tests.

| Card landing | Double-tap love | Love button | Tab dock |
|---|---|---|---|
| ![Card landing](docs/motion/card_landing.gif) | ![Double tap](docs/motion/double_tap.gif) | ![Recommend](docs/motion/recommend_tile.gif) | ![Dock](docs/motion/liquid_dock.gif) |

---

## How the algorithm works

The full explanation, with every formula and setting, is in **[docs/PEOPLES_PICK.md](docs/PEOPLES_PICK.md)**.
In short:

### 1. Votes

**Loved it** means "go here for this". **Not for me** means "I tried it and wouldn't send you". Posting
a photo asks *Worth a bite?* and counts the same way. Saves and shares only help the feed (Trending,
tie-breaks), never the rating. Whoever added a place gets no say on its dishes.

### 2. Standing: one real person, one vote

```
standing = min(1, account age / 7 days) / (1 + lockstep partners)
```

A new account grows to full weight over a week. Accounts that keep voting on the same dishes, the
same way, within 24 hours of each other are **lockstep partners**: a ring of six counts as one person.
The penalty falls on the copycat, not on the expert whose picks they copy.

### 3. Trust: whose taste has a track record

Every vote is a *pick*. People who vote on the dish **after** you test it. You earn credit only for
agreement beyond what always voting that way would get, so being **early and right** is what counts.
Trust is kept per dish type (misal, biryani, chai…) and overall:

```
trust = 0.6 + 0.8 × credit / (picks + 2)
```

Everyone starts at 0.6. At least 3 picks of a type with trust ≥ 1.05 earns an **expert** badge.
Taking a vote back or flipping it doesn't erase it: the original call stays in your record. Only
votes at approved places count, and whoever added a place never rates it (even after a merge).

### 4. The rating

Each vote is weighted by **trust × standing**, fading with a 180-day half-life, and **× 1.25 for a
verified visit** from a full-standing account:

```
rating     = (weighted loves + 3 × 0.75) / (weighted loves + weighted passes + 3)
confidence = rating − sqrt(rating × (1 − rating) / (weighted votes + 3))
```

Dishes are ranked by **confidence**, a cautious estimate, so a dish needs both a high share of loves
**and** enough votes: 5 loves and no passes beats 28 loves and 6 passes, but 1 love doesn't beat 30.
People see the plain numbers ("82 % loved it · 34 votes") and the **champion**, the most trusted
person who loved it.

### 5. The feed

| Tab | Order |
|---|---|
| Top | People's Pick (at least 3 real people). No place gets more than 2 dishes in any 10 cards. |
| Trending | This week's activity (loves, saves, shares). |
| Hidden gems | Highly loved dishes few people have found yet. |
| For You | Weighted loves from your taste matches and the people you follow (below). |
| Following | Photos from people you follow, newest first. |
| Nearby | Nearest first, People's Pick between equally near dishes. |

Every 5th card is a fresh dish, so new posts get seen. While a city is **young** (under 200 votes
from real people), Top, Trending and Hidden gems need only half a full-standing person, so they
aren't empty on launch day. Scrolling never repeats or skips a dish, even as votes come in.

### 6. People like you

**Taste Match** (0–100 %) blends votes on the same dishes, the dish types you both like, and what you
both said about your taste. **For You** scores dishes by what your closest matches and the people
you follow loved (+1 love, +0.5 save, −1 not for me), each weighted by your match and fading over 90 days.
Without a dish you both voted on, a match says at most 75 %. Vegetarians and egg-eaters never see meat
(a dish's name and type decide when it isn't marked), and when there's nobody to go on yet, For You
shows the dish types you like, then the city's best.

### 7. Genuine photos

- **A fingerprint per photo.** Your own photo posted again is refused; someone else's photo is held for an admin to compare.
- **Verified visit.** A camera (or fresh) photo taken within 300 m of the place.
- **Limits.** One photo per dish per person per day, and one vote per dish however many photos you post.
- **Spelling merges.** "Did you mean…?", typo merging, and admin merges keep votes on one dish.
- **New places** wait for approval but take posts and show in the place pickers; they go live with a
  verified visit from someone established, two half-established posters, or three days with a photo,
  never while a similar place is live within 150 m.
- **Reports** add up by the reporters' standing; a whole dish is never hidden by reports alone.
- **Craving search** also lists places that may serve the dish (by name or cuisine), photos or not.

### Tuning and checking

Every number lives in the `ranking_config` table and can be changed from the dashboard without shipping
the app. The dashboard's **Algorithm health** and **prediction check** show whether the ranking predicts
real votes better than chance, with and without trust. The backend tests include a **test village**:
honest diners against a ring of fake accounts and the owner, and the honest ranking must win.

---

## Real places and the demo

**Places come from [Overture Maps](https://overturemaps.org)**: open map data from Meta, Microsoft,
Foursquare and OpenStreetMap, cleaned by the Overture Maps Foundation (CDLA-Permissive-2.0, free to
keep). For Nashik the server ships **1,225 real food places** (restaurants, cafes, bakeries, sweet and
ice-cream shops, bars and stalls), each with its neighbourhood, phone and website when known.

- **Import:** admin dashboard → Places → *Import every place from Overture Maps*, or
  `docker compose exec api node dist/cli.js import-overture nashik`.
- **Another city:** `python3 backend/scripts/overture-places.py --city pune --lat 18.52 --lng 73.86 --km 30 --out backend/seed/places/pune.overture.json`
  (needs `pip install pyarrow`), then commit the file and import it.
- **Missing places** get added by people while posting, and reviewed in the dashboard.

Full guide: [docs/REAL_PLACES.md](docs/REAL_PLACES.md).

**The demo** is 40 clearly marked `demo_` users and 12 invented Nashik places with 30 dishes (real,
credited Wikimedia photos), so a new server isn't empty. Places, cards and profiles from the demo
are labelled **DEMO**.

| | |
|---|---|
| Remove all of it | Dashboard → Places → **Remove everything demo**, or `docker compose exec api node dist/cli.js remove-demo --everything`. It stays removed after restarts and updates. |
| Bring it back | `docker compose exec api node dist/cli.js seed-demo` |
| New server without the demo | `DEMO_ACTIVITY=false` in `.env`: cities, dish types and spellings only. |

---

## How it's built

```
 Android app (Kotlin, Jetpack Compose)                       Server (Docker Compose)
 ┌──────────────────────────────────┐   HTTPS + JSON   ┌──────────────────────────────────────┐
 │ Home · Explore · Map · Post ·    │ ───────────────▶ │ Caddy (automatic HTTPS)              │
 │ Activity · Profile · Messages    │                  │   └▶ API: Node 22 + Fastify          │
 │ Google Maps SDK / MapLibre       │ ◀─────────────── │        ├▶ Postgres 16 + PostGIS      │
 │ Coil · Retrofit · DataStore      │   photos, push   │        │   (ranking in SQL, pg_trgm) │
 └──────────────────────────────────┘                  │        ├▶ media volume (photos)      │
                                                       │        └▶ admin dashboard (/admin)   │
                                                       │ Backup: nightly db + photos          │
                                                       └──────────────────────────────────────┘
 Optional: Firebase (instant push), SMTP (two-step sign-in codes), Google Places (live place photos),
           Mapillary / Wikimedia (place photos), OSRM (driving routes)
```

| Part | What | Tech |
|---|---|---|
| [`backend/`](backend) | API, ranking, photo storage, admin dashboard | Node 22, Fastify 5, zod, Postgres 16 + PostGIS + pg_trgm, sharp, Vitest |
| [`android/`](android) | The app (Android 8.0+) | Kotlin, Jetpack Compose, Material 3, Google Maps SDK + MapLibre, Coil, Retrofit, kotlinx.serialization, Firebase Messaging, Roborazzi |
| [`docker-compose.yml`](docker-compose.yml) | Production | db, api, backup and https (Caddy) services |

The ranking runs **inside Postgres** (the `migrations/` SQL). A vote re-rates its own dish at once;
trust, standing and taste are rebuilt in the background on a timer.

---

## Run the server

Needs a Linux server with Docker and Compose v2 (an Azure Standard B2s / D2as is plenty).

```bash
git clone https://github.com/aniketpitre/Food-MVP.git && cd Food-MVP
cp .env.example .env
# Edit .env: POSTGRES_PASSWORD and JWT_SECRET (openssl rand -hex 32), ADMIN_EMAILS, PUBLIC_URL, DOMAIN
docker compose --profile https --profile backup up -d --build
scripts/smoke-test.sh            # checks the whole API end to end
```

**Update to the latest version** (do this after every release):

```bash
cd ~/Food-MVP && git pull && docker compose --profile https --profile backup up -d --build
```

On start the server applies any new database migrations, adds new cities, dish types and
spellings, and refreshes the rankings.

### Settings (`.env`)

| Setting | What it does |
|---|---|
| `POSTGRES_PASSWORD`, `JWT_SECRET` | Required secrets (`openssl rand -hex 32`). |
| `PUBLIC_URL`, `DOMAIN` | The address phones use; `DOMAIN` turns on automatic HTTPS (ports 80 and 443 open). |
| `ADMIN_EMAILS` | Accounts with these emails are admins (dashboard and moderation). |
| `SEED_ON_START`, `DEMO_ACTIVITY` | Seed cities and dish types on first start; `DEMO_ACTIVITY=true` adds the demo. |
| `RANK_REFRESH_SECONDS` | How often trust and taste are fully rebuilt (default 300). |
| `GOOGLE_PLACES_API_KEY` | Optional: live Google photos on place pages (a server key, never stored, capped by `GOOGLE_PHOTOS_DAILY_LIMIT`). |
| `MAPILLARY_TOKEN` | Optional: street photos of shopfronts (`fetch-place-photos`). |
| `FCM_*` | Optional: instant push notifications through Firebase. |
| `SMTP_*`, `MAIL_FROM` | Email for two-step sign-in codes and email changes. |
| `ROUTING_URL` | Optional: an OSRM server for driving routes ("On the way", directions). |
| `BACKUP_KEEP_DAYS` | How long nightly backups are kept (`./backups`). |

### Commands

All run as `docker compose exec api node dist/cli.js <command>`:

| Command | Does |
|---|---|
| `import-overture <city> [file]` | Import every food place from Overture Maps (the bundled file or one you made). |
| `fetch-place-photos <city> [max]` | Openly licensed photos of places (Wikimedia, Mapillary). |
| `refresh` | Rebuild all rankings now. |
| `remove-demo --everything` | Remove all demo users, places, dishes and photos (for good). |
| `seed-demo` | Bring the demo back. |
| `migrate` | Apply database migrations (also done on every start). |

| Task | How |
|---|---|
| Logs | `docker compose logs -f api` |
| Restore a backup | `docker compose exec -T db pg_restore -U worthabite -d worthabite --clean < backups/db-<date>.dump` |
| Become admin | Put your email in `ADMIN_EMAILS`, restart, sign in |

The database is never exposed outside Docker; only the API (behind Caddy) is. Photos live in the `media` volume.

---

## The admin dashboard

Open **`https://<your server>/admin`** and sign in with an admin account. It also works on a phone.

| Tab | What you see and do |
|---|---|
| Overview | Users, posts, votes and places over time; demo vs real. |
| Algorithm health | Votes per day, verified photos, lockstep accounts, copies held, the prediction check, and why any dish ranks where it does. |
| Recommendations | What For You shows any user, and why. |
| Ranking settings | Every `ranking_config` number, with safe limits; changes re-rank at once. |
| Users | Search people and see their account, posts and votes. |
| Places | Search by source and status, merge duplicates, import from Overture, retire old OpenStreetMap places, remove the demo, recent jobs. |
| Moderation | Reports, held photos (side by side with the original), new places waiting for review. |
| Activity | The latest posts, comments, sign-ups, reports and votes. |

---

## The Android app: install, release, sign

**Install:** download the latest APK from [Releases](https://github.com/aniketpitre/Food-MVP/releases/latest),
allow "Install unknown apps", and open it. The app checks for new releases itself and offers to update.

**Publish a release:** GitHub → Actions → **Android** → *Run workflow* with a version (for example `0.28.0`)
and release notes separated by `;`. The release appears on the Releases page with the APK.

**Server address:** built in from `serverUrl` in `android/gradle.properties`
(`https://foodfeed-aniket.eastus.cloudapp.azure.com`). Change it and release again if the server moves.
An empty `serverUrl` asks for the address on first launch (handy for `http://10.0.2.2:8080` in the emulator).

**Repository secrets** (GitHub → Settings → Secrets and variables → Actions):

| Secret | Why |
|---|---|
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | Sign releases with the private key. CI refuses to publish without them, and checks every APK against `android/keystore/RELEASE_KEY_FINGERPRINT.txt`. Keep the key backed up: losing it means the app can't be updated. |
| `MAPS_API_KEY` | Google Maps in the app: a key with only **Maps SDK for Android** enabled, restricted to Android apps with package `app.worthabite` and SHA-1 `42:54:9C:5B:41:29:98:1C:41:8A:0B:9B:2E:0B:D0:C1:BC:0C:EC:CA`. Never use the server's `GOOGLE_PLACES_API_KEY` (it's IP-locked, and anything in an APK is public). |

The map colours are in `android/app/src/main/assets/map/` (`google_light.json` and `google_dark.json`
for Google Maps; `light.json` and `dark.json` for MapLibre).

---

## Develop and test

```bash
# Backend: tests need a Postgres + PostGIS
docker run -d --name ff-testdb -e POSTGRES_PASSWORD=postgres -p 5433:5432 postgis/postgis:16-3.5
cd backend && npm ci
TEST_DATABASE_URL=postgres://postgres:postgres@localhost:5433/postgres npm test   # 152 integration tests
npm run dev                                                                       # needs DATABASE_URL and JWT_SECRET

# Android (Android Studio, or JDK 17 + Android SDK 35)
cd android
./gradlew testDebugUnitTest        # logic and screenshot tests
./gradlew recordRoborazziDebug     # writes app/screenshots/*.png
./gradlew assembleDebug            # app-debug.apk (installs next to the release app)
./gradlew assembleRelease -PmapsApiKey=...   # optional local release with Google Maps
```

**Continuous integration** (GitHub Actions, on every push):

| Workflow | Checks |
|---|---|
| Backend | Typecheck and all backend tests on a real Postgres + PostGIS. |
| Android | Unit and screenshot tests, then a signed release APK (on demand, published to Releases). |
| Device smoke test | Builds the app and runs it on an Android emulator against a real server: a smoke test, plus a long journey through every screen (feed, search, map, posting, social, messages, stories, account security). Screenshots land in `docs/device/`. |
| Screenshots | Re-renders `docs/screenshots/` and the motion GIFs. |

---

## Repository map

```
backend/
  src/                 API: routes/ (one file per area), ranking, overture.ts, seed, cli.ts, dashboard/
  migrations/          The database, including the ranking and taste SQL (001 → 019)
  seed/                Cities, demo photos, places/<city>.overture.json
  scripts/             overture-places.py (make a city's places file)
  test/                Integration tests (api, social, auth, ranking, admin dashboard)
android/
  app/src/main/java/app/worthabite/
    data/              API client, models, storage, location, photo compression, push
    ui/                home, feed, map, search, dish, restaurant, add, profile, messages,
                       stories, activity, comments, taste, admin, theme (colours, motion)
  app/src/main/assets/ map styles, fonts
docs/                  PEOPLES_PICK.md (algorithm), REAL_PLACES.md, REAL_PHOTOS.md, the product plan,
                       brand, screenshots, device screenshots, motion GIFs
scripts/               smoke-test.sh, device tests, rename-to-worthabite.sh, demo images
docker-compose.yml     db + api (+ backup and https profiles)
DECISIONS.md           Choices made while building, and why
```

---

## Credits and licences

- Places © [Overture Maps Foundation](https://overturemaps.org) contributors (Meta, Microsoft, Foursquare, OpenStreetMap and others), CDLA-Permissive-2.0.
- Map tiles for the open map © [OpenMapTiles](https://openmaptiles.org) © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, via [OpenFreeMap](https://openfreemap.org). Google Maps under Google's terms.
- Demo dish photos from [Wikimedia Commons](https://commons.wikimedia.org), credited on each photo; place photos from Wikimedia Commons and [Mapillary](https://www.mapillary.com) (CC BY-SA) with credits.
- Indian cities from GeoNames (see `backend/seed/india-cities.NOTICE.md`).
- Headline font Bricolage Grotesque (SIL Open Font License), bundled.
