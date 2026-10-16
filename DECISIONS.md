# Decisions

One line per decision and why (plan section 11.6). Newest at the bottom.

| Date | Decision | Why |
|---|---|---|
| 2026-10-15 | Self-hosted in Docker: Postgres + PostGIS + a small Node/Fastify API, photos on a local volume | You asked to run the database and services in your own environment; the plan's schema is plain Postgres, so nothing is lost by leaving Supabase. |
| 2026-10-15 | The API is the only way to reach the database (no PostgREST/RLS) | One place for every permission check; the review found RLS gaps in the plan's SQL (unprotected tables, spoofable impressions, public ranking refresh). |
| 2026-10-15 | Email + password sign-in instead of Google Sign-In | Works on a self-hosted box with no Google Cloud setup. Weaker against fake accounts, so new accounts count half for 24 hours and reports from new accounts never auto-hide. Google Sign-In can be added later. |
| 2026-10-15 | Native Kotlin + Jetpack Compose instead of React Native | Android-only for now and "snappy like Instagram" was the main requirement (plan D8 allows this); no JS bridge, smaller build toolchain. |
| 2026-10-15 | "Recommended by N" counts recommend or tried only; saves shown separately | A save means "want to try", not "I recommend"; counting it would contradict the honesty principle. Saves still add to the ranking score. |
| 2026-10-15 | A share counts once per person and cannot be undone; guests' shares are not counted | It is recorded when the share sheet opens, so it is the easiest signal to fake. |
| 2026-10-15 | Launch-day feed uses an editorial order (`featured_rank`) for new dishes | With honest seeding nobody has endorsed anything yet, so the Top lane is empty and the feed would otherwise be random. |
| 2026-10-15 | Auto-hide needs 3 reports from accounts older than 24 hours | Stops a rival hiding a dish with throwaway accounts; all reports still reach the admin queue. |
| 2026-10-15 | Account deletion removes the person's photos and actions; dishes and places they added stay without their name | Photos are personal data; other people's posts may hang off the dish. |
| 2026-10-15 | Photos are resized on the phone (1600 px JPEG, metadata dropped) and re-encoded on the server (1080 px + 360 px WebP) | Privacy (no GPS leaves the phone), small uploads on mobile data, consistent sizes. |
| 2026-10-15 | Demo content is drawn illustrations, labelled "DEMO IMAGE", and demo activity comes from `demo_` accounts behind a flag | No copyright questions; the flag and `remove-demo` command keep fake endorsements out of a real pilot. |
| 2026-10-15 | Pilot APKs are signed with a committed public test key | Updates install over each other from GitHub Releases; must be replaced by a private key before Google Play. |
| 2026-10-15 | Cleartext HTTP allowed in the app | Home/LAN pilots usually have no certificate. Turn it off once the server runs the `https` profile. |
| 2026-10-15 | Releases signed with a private release key (fingerprint BE:1C:81:BE…:9E:FD), held in GitHub secrets and the owner's backups; CI verifies the fingerprint | Required for Android developer verification; the public test key must never be registered. One-time key, independent of where the backend is hosted. |
| 2026-10-16 | Redesign to a "street-food zine" look (Bricolage Grotesque, saffron/turmeric/chili, stickers, tickets) with choreographed motion and hero transitions | The minimal ink look read as generic; the app needed its own personality. Motion is recorded as GIFs in CI so it can be reviewed without a phone. |
| 2026-10-16 | Every Indian city with 50k+ people is open, from a bundled GeoNames list (`seed/india-cities.json`); a point belongs to the city it is most inside (distance / city reach), within 150 km | Location only worked in Nashik; detection and new places failed everywhere else. Bundled data keeps locations on our server (no geocoding service). |
| 2026-10-16 | Location asks for precise or approximate (Android lets people choose); GPS is used only when precise is allowed | Approximate is enough for the city, but a new place's map pin needs to be accurate. Clear messages when location is off or denied. |
| 2026-10-16 | Comments belong to posts (photos), with one level of replies and likes; the feed opens the cover photo's comments | Matches Instagram, which people already know; deleted or hidden comments take their replies with them. |
| 2026-10-16 | Home is a "What are you craving?" search ranked by **People's Pick** (loved/not-for-me votes, weighted by taster trust, lower-bound ranking) instead of a reel feed | The real question is "what should we eat?"; a vote from someone whose picks others keep agreeing with should count more, and a dish with 3 votes shouldn't beat one with 300. See docs/PEOPLES_PICK.md. |
| 2026-10-16 | Real places come from OpenStreetMap (`import-places`); dish photos come only from users | Copying photos from Google Maps, Zomato or Swiggy breaks their terms and the photographers' copyright. Google Places API photos are possible later with an API key and a paid quota. |
| 2026-10-16 | Minimum age is 13 (under-18s need a parent's or guardian's permission), not unrestricted | Google Play's families policy and India's DPDP Act make an app open to young children much harder to run; comments and follows make it social. |
| 2026-10-16 | Map is MapLibre with OpenFreeMap tiles and a bundled, recoloured style | No API key or bill, matches the app's palette, shows only FoodFeed places. |
| 2026-10-16 | R8 shrinking, three ABIs, and an automatic Lite mode for low-RAM phones | Keeps the APK small and the app smooth on cheap phones without hurting flagships. |
| 2026-10-16 | No direct messages or stories yet | Both need serious moderation and child-safety work (13+ users); revisit after the pilot. |
