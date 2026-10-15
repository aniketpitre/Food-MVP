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
