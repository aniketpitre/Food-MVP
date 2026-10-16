# FoodFeed

A local food app where the unit is **a specific dish at a specific place**, ranked by how many real
people recommend it, whatever the restaurant's overall rating. The full idea, research and plan are in
[`docs/FoodFeed_Android_MVP_Plan.md`](docs/FoodFeed_Android_MVP_Plan.md).

The repository has two parts:

| Part | What | Tech |
|---|---|---|
| [`backend/`](backend) | Self-hosted API, database and photo storage | Node 22 + Fastify, Postgres 16 + PostGIS, Docker Compose |
| [`android/`](android) | The Android app (Android 8.0+) | Kotlin, Jetpack Compose, Material 3, Coil, Retrofit |

The look is a **street-food zine**: warm cream paper, tamarind ink and a saffron signature, with
loud spice accents used like stickers on a menu board (chili for "recommended", turmeric price
tags, mint for veg). Headlines are set in Bricolage Grotesque (OFL, bundled). The feed is a
full-screen vertical deck with a Reels-style action rail; lists are menu cards; places are paper
tokens with notches and a perforation.

## Screenshots

Rendered by the app's screenshot tests with the illustrated demo data (refresh with the **Screenshots** workflow in GitHub Actions).

| Feed | Hidden gems (dark) | Dish | Place |
|---|---|---|---|
| ![Feed](docs/screenshots/01_feed_top.jpg) | ![Hidden gem](docs/screenshots/02_feed_hidden_gem_dark.jpg) | ![Dish](docs/screenshots/05_dish_detail.jpg) | ![Place](docs/screenshots/06_restaurant.jpg) |
| **Search** | **Saved** | **Welcome** | **Comments** |
| ![Search](docs/screenshots/07_search.jpg) | ![Saved](docs/screenshots/08_saved.jpg) | ![Welcome](docs/screenshots/12_onboarding_city.jpg) | ![Comments](docs/screenshots/16_comments.jpg) |
| **Home: what are you craving?** | **People's Pick for pav bhaji** | **Profile** | **Activity** |
| ![Home](docs/screenshots/17_home.jpg) | ![Craving](docs/screenshots/18_craving_pav_bhaji.jpg) | ![Profile](docs/screenshots/10_profile.jpg) | ![Activity](docs/screenshots/19_activity.jpg) |
| **Map place card** | **Messages** | **Chat** | |
| ![Place card](docs/screenshots/20_map_place_card.jpg) | ![Messages](docs/screenshots/21_messages.jpg) | ![Chat](docs/screenshots/22_chat.jpg) | |

### On a real (emulated) phone

The **Device smoke test** workflow also runs the app on an Android emulator against a real
FoodFeed server and walks through it: Home and stories, a story, messages, the map with its
food logos, tapping a logo, the 3D place card, and Take me there (checked to open Google Maps
directions). Its screenshots land in [`docs/device`](docs/device).

## Motion

Recorded frame by frame from the real composables (`MotionTest`) and stitched into GIFs by the
Screenshots workflow.

| Card landing | Double-tap | Recommend |
|---|---|---|
| ![Card landing](docs/motion/card_landing.gif) | ![Double tap](docs/motion/double_tap.gif) | ![Recommend](docs/motion/recommend_tile.gif) |

| Liquid tab dock | Squiggle tabs |
|---|---|
| ![Dock](docs/motion/liquid_dock.gif) | ![Tabs](docs/motion/tab_squiggle.gif) |

The motion rules (all in `ui/theme/Motion.kt`):

- **Choreography, not dumping.** When a card lands, the photo settles from a push-in first, then
  the title rises out of a mask, the sticker slaps on with an overshoot and a swing, and the
  details and action rail cascade in 60-90 ms apart. One progress value drives the whole sequence,
  so it costs one animation and no recomposition.
- **Deal like a deck.** Swiping, the outgoing card holds back, shrinks and dims while the next card
  slides over it.
- **Celebrate the good stuff.** Recommend, save and tried squash, fill with their colour and burst
  with a ring and spice confetti; the double-tap heart appears under your finger.
- **Liquid, not sliding.** The tab dock's blob leads with a stiff spring and trails with a soft one,
  so it stretches and thins as it travels, then snaps back round. The feed tabs' squiggle rolls its
  wave as it moves and redraws itself.
- **Hero photos.** A dish photo morphs from wherever you tapped it (feed card, list row, polaroid,
  tile) into the dish page, and back.
- Everything that follows a finger is a spring, so it can be interrupted mid-flight; nothing loops
  except the loading shimmer.

## Features in this MVP

- **Home asks "What are you craving?"** Type pav bhaji (or tap a chip) and get the best pav bhaji in your city ranked by **People's Pick**: votes from people who ate it, weighted by how often a taster's picks are later agreed with. Each result shows its rating, "loved by N", and whose top pick it is. The algorithm is explained in [`docs/PEOPLES_PICK.md`](docs/PEOPLES_PICK.md).
- **Loved it / Not for me** votes on every dish, so a rating means something.
- **Explore** keeps the dish deck (Top, Trending, Hidden gems); **Nearby** is now an in-app map (MapLibre with free OpenFreeMap tiles, recoloured to the app's theme) that shows only places in FoodFeed, with a swipeable card strip.
- **Real places from OpenStreetMap:** `docker compose exec api node dist/cli.js import-places <city-slug> [km]` imports cafes and restaurants (name, cuisine, hours, location). Photos come from people who post there.
- **Instagram-style social:** profiles with photo, bio and username; followers/following; follow from anywhere; people search; an Activity tab (follows, comments, replies, likes, mentions, recommends, agreements); comment controls (everyone, people you follow, off); blocked accounts; change password; theme (light/dark/system).
- **Food-logo map:** each place on the map is a little badge with its food (🌶️ misal, ☕ cafe, 🍕 pizza…). Tap one and a card swings up in 3D with the photo, the best dish, the people's rating, hours, distance and **Take me there** (Google Maps directions).
- **Direct messages, Instagram style:** send posts, dishes, places and photos from your gallery; reply to a message (long-press → Reply), react with a double-tap ❤️ or any emoji, seen receipts, unsend, copy, message requests, block and report. Every post has a **Send** (paper-plane) button that shares it to friends in FoodFeed or to other apps; place pages can be sent too.
- **Notifications** for new messages, message requests, reactions and all Activity (follows, comments, replies, mentions, likes). No push service needed: the app checks the server every 30 seconds while open and every 15 minutes in the background. Tapping one opens the chat, dish or profile.
- **Home's headline changes with the time of day** (breakfast, lunch, chai time, dinner, late-night munchies) and picks a new line each time you open Home.
- **Stories:** 24-hour photo stories with a dish or place tag, a stories row on Home, a full-screen viewer (cube swipe between people, tap to step, hold to pause), likes and replies that arrive as messages, and who watched yours.
- **Real photos of places:** openly licensed photos from Wikimedia Commons and Mapillary street views (with credits), and optional Google Maps photos on place pages. See [`docs/REAL_PHOTOS.md`](docs/REAL_PHOTOS.md).
- **Lite mode** (automatic on low-RAM phones, or switch it in Settings) turns off blur, confetti and card choreography. Release builds are shrunk with R8 and ship only ARM and x86_64 code.
- Guest browsing (no sign-up wall); sign-in is asked for only on the first recommend, save or post, and the action completes right after sign-in.
- Works in every Indian city: location (precise or approximate, never stored) picks your city from ~900 cities with 50,000+ people, or search for one. New places are filed under the right city automatically.
- Dish feed with **Top**, **Trending** (last 7 days), **Hidden gems** and **Nearby** tabs, plus category filters.
- Ranking per the plan: recommend 1, save 1.5, tried 2, share 2.5; one person capped at 3 points per dish; 45-day half-life; new accounts count half for their first 24 hours; at least 3 people to enter Top; every 5th card is a new dish so fresh posts get seen. All weights live in the `ranking_config` table.
- "Recommended by N people" counts people who recommended or tried the dish (saves are shown separately). Zero shows "New", never "Recommended by 0".
- Dish page (photos, place, recommenders, Open in Maps, More at this place), place page ranked by recommendations, typo-tolerant search with local spellings ("wada pav" finds Vada Pav), saved list, profiles.
- Post a dish in under 30 seconds: camera or gallery photo, resized on the phone with location data removed, dish-name suggestions, nearby places or a new place (held for review), price, veg, caption, a sponsored/free-food label.
- Comments on posts, Instagram style: replies in threads, likes, @mentions, quick emoji; long-press to delete or report. Authors, the post's owner and admins can delete.
- Safety: report posts/dishes/comments/people, block people, auto-hide after 3 reports from established accounts, an admin moderation screen, rate limits, account deletion in the app and on the web.
- Share links with a web preview page (`/d/<dish>`), plus draft Privacy, Terms and Delete-account pages.

## Run the server (Docker)

Needs Docker with Compose v2.

```bash
git clone https://github.com/aniketpitre/Food-MVP.git && cd Food-MVP
cp .env.example .env
# Edit .env: set POSTGRES_PASSWORD and JWT_SECRET (openssl rand -hex 32), ADMIN_EMAILS, PUBLIC_URL
docker compose up -d --build
scripts/smoke-test.sh            # checks the whole API end to end
```

The API is on port `8080`. On first start it creates the schema, seeds the demo city (Nashik), 12
categories, 30 demo dishes at 12 invented places, and (with `DEMO_ACTIVITY=true`) 40 clearly labelled
`demo_` accounts so every tab has data.

**Before inviting real people:** set `DEMO_ACTIVITY=false`, run
`docker compose exec api node dist/cli.js remove-demo --all` (demo accounts plus the invented demo places and
their illustrated photos; real imported places stay), and fill in the bracketed parts of the policy pages.

| Task | Command |
|---|---|
| Logs | `docker compose logs -f api` |
| Nightly backups of the database and photos into `./backups` | `docker compose --profile backup up -d` |
| HTTPS with automatic certificates (set `DOMAIN` in `.env`, open ports 80/443) | `docker compose --profile https up -d` |
| Recompute rankings now | `docker compose exec api node dist/cli.js refresh` |
| Import real cafes and restaurants from OpenStreetMap | `docker compose exec api node dist/cli.js import-places nashik 12` |
| Fetch real photos of those places (Wikimedia, Mapillary) | `docker compose exec api node dist/cli.js fetch-place-photos nashik` |
| Restore a backup | `docker compose exec -T db pg_restore -U foodfeed -d foodfeed --clean < backups/db-<date>.dump` |
| Become admin | put your email in `ADMIN_EMAILS`, restart the api, sign up or sign in |

The database is not exposed outside Docker; only the API is. Photos are stored in the `media` volume.

## Install the Android app

Every push builds an APK in GitHub Actions (artifact **FoodFeed-apk**). To publish one on the
[Releases page](https://github.com/aniketpitre/Food-MVP/releases), either run **Actions → Android → Run
workflow** with a version such as `0.2.0`, or push a tag:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

On the phone: download the APK from Releases, allow "Install unknown apps", open FoodFeed and pick a city.
The server address is built into the app from `serverUrl` in `android/gradle.properties`
(currently `https://foodfeed-aniket.eastus.cloudapp.azure.com`). If the server moves, change that line and
publish a new release. Builds with an empty `serverUrl` ask for the address on first launch instead, which
is handy for testing against a local server (`http://10.0.2.2:8080` from the emulator).

Release APKs are signed with the owner's private key (SHA-256 fingerprint in
`android/keystore/RELEASE_KEY_FINGERPRINT.txt`), supplied to CI through the repository secrets
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.
CI refuses to publish a release without them and checks every release APK against that fingerprint.
The key is independent of where the backend runs; keep it backed up, because losing it means the app
can no longer be updated. Builds without the secrets (forks, local) fall back to the public test key in
`android/keystore/`.

## Develop

```bash
# Backend (needs a Postgres+PostGIS for tests)
docker run -d --name ff-testdb -e POSTGRES_PASSWORD=postgres -p 5433:5432 postgis/postgis:16-3.5
cd backend && npm ci && npm test        # 25 integration tests
npm run dev                             # needs DATABASE_URL and JWT_SECRET

# Android (Android Studio, or JDK 17 + Android SDK 35)
cd android
./gradlew testDebugUnitTest             # logic + screenshot tests
./gradlew recordRoborazziDebug          # writes app/screenshots/*.png
./gradlew assembleDebug                 # app-debug.apk, installs next to the release build
```

In the Android emulator the host machine is `http://10.0.2.2:8080`.

Regenerate the illustrated demo photos with `python3 scripts/make_demo_images.py` (needs Pillow).

## Repository map

```
backend/            API (src/), SQL schema (migrations/), demo seed (src/seed-data.ts, seed/images/), tests
android/            Android app: data/ (API, storage, location, image compression), ui/ (screens), tests
docs/               The plan this MVP implements
scripts/            smoke-test.sh, make_demo_images.py
docker-compose.yml  db + api (+ backup and https profiles)
DECISIONS.md        Choices made while building, and why
```
