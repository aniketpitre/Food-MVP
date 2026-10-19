# Real places for a city (Nashik first)

How to fill the map with every real restaurant, cafe and food stall, with accurate
locations, and then retire the demo.

## 1. Import from OpenStreetMap (free, allowed to keep)

Admin dashboard (`https://<your server>/admin`) → **Places** → *Import real places from
OpenStreetMap* → city **Nashik**, leave the radius empty (the city's own 25 km) → **Import**.

- Takes every named restaurant, cafe, fast-food stall, food court, bakery, sweet shop,
  ice-cream parlour, bar, pub, tea and coffee shop, deli.
- Location is the OpenStreetMap pin (usually right on the building).
- Area: the address's suburb, else the nearest named neighbourhood within 2.5 km
  (College Road, Gangapur, Panchavati…), else the street.
- Run it again any time: places already imported are refreshed, new ones added, and places
  people created in the app are linked to their OpenStreetMap twin instead of duplicated.
- Data © OpenStreetMap contributors (ODbL): keep the credit on the map (it's there).

Coverage: OpenStreetMap knows most well-known Nashik places but not every small stall. The
gap closes three ways:

1. **People add places** while posting (Add dish → pick on the map → *Create a new place*).
   New places wait for review unless the poster is at the spot.
2. **You add or fix places** on openstreetmap.org (free account), then re-import. Fixes
   there help everyone and come back into WorthAbite.
3. **Review** in the dashboard: *Places → duplicates* to merge twins, *Moderation* for new
   places waiting for approval.

## Why not import Google Maps?

Google's Places API is accurate, but its terms forbid copying its places into your own
database (only the place id may be kept; coordinates at most 30 days). Building the map
from Google would break those terms and stop working when the key is billed or revoked.
WorthAbite uses Google only, optionally, to show live photos of a place, never stored.

## 2. Retire the demo

When the real places are in: dashboard → **Places** → *Remove demo content*:

- **Remove demo accounts** – the `demo_` people, their votes, follows and stories.
- **Remove everything demo** – also the invented demo places, their dishes and photos.

Or on the server: `docker compose exec api node dist/cli.js remove-demo --all`.

Real users' posts are never touched.

## 3. Launch checklist

1. Update the server (`git pull && docker compose --profile https --profile backup up -d --build`).
2. Import Nashik from OpenStreetMap (above). Check the number added in *Jobs*.
3. Open the map in the app and switch it to show all places (not only ones with dishes).
4. Remove the demo.
5. Ask a few friends to post their favourite dishes: each post makes a place show up for
   everyone with its best dishes.
