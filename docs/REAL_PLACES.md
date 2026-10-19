# Real places for a city (Nashik first)

How to fill the map with every real restaurant, cafe and food stall, with accurate
locations, and then retire the demo.

## 1. Import every place from Overture Maps (the fullest list)

Admin dashboard → **Places** → *Import every place from Overture Maps* → **Nashik** → **Import**.
Or on the server: `docker compose exec api node dist/cli.js import-overture nashik`.

[Overture Maps](https://overturemaps.org) is open map data from Meta, Microsoft, Foursquare,
AllThePlaces and OpenStreetMap, cleaned and merged by the Overture Maps Foundation
(CDLA-Permissive-2.0: free to keep and use with credit). For Nashik (25 km) the server ships
**1,225 food places**: restaurants (Indian, vegetarian, pizza, Chinese, …), cafes, bakeries,
sweet and ice-cream shops, juice bars, tea rooms, bars and stalls, each with its
neighbourhood (Canada Corner, College Road, Gangapur, Nashik Road…), and the phone number or
website when known. Grocery, liquor and delivery-only kitchens are left out, and so are
places Overture itself is unsure of (confidence below 0.4) or knows are closed.

A place already here (added by someone, or from an earlier import) within 80 m with a similar
name is linked, not copied. Running the import again adds nothing twice.

To refresh it (Overture publishes monthly) or make one for another city:

```
pip install pyarrow
python3 backend/scripts/overture-places.py --city pune --lat 18.5204 --lng 73.8567 --km 30 \
    --out backend/seed/places/pune.overture.json
```

then commit the file and update the server. The script reads only the part of Overture's
files around the city (a few MB).

## 2. Places only from Overture (OpenStreetMap import retired)

WorthAbite no longer imports places from OpenStreetMap: Overture already includes
OpenStreetMap's places, merged with Meta's, Microsoft's and Foursquare's. If you ran the old
OpenStreetMap import, dashboard → **Places** → *Retire OpenStreetMap places* once, after the
Overture import: places Overture matched become Overture places, ones it doesn't know that
have no dishes are hidden (not deleted), and any with dishes stay.

The street map behind the pins is still drawn from OpenStreetMap map tiles (roads, parks,
landmark names), which is why the map keeps that credit.

Whatever Overture misses (a brand-new stall, a handcart) is added two ways:

1. **People add places** while posting (Add dish → pick on the map → *Add a new place at the pin*).
   New places wait for review unless the poster is at the spot.
2. **Review** in the dashboard: *Places → duplicates* to merge twins, *Moderation* for new
   places waiting for approval.

## Why not import Google Maps?

Google's Places API is accurate, but its terms forbid copying its places into your own
database (only the place id may be kept; coordinates at most 30 days). Building the map
from Google would break those terms and stop working when the key is billed or revoked.
WorthAbite uses Google only, optionally, to show live photos of a place, never stored.

## 3. Retire the demo

When the real places are in: dashboard → **Places** → *Remove demo content*:

- **Remove demo accounts** – the `demo_` people, their votes, follows and stories.
- **Remove everything demo** – also the invented demo places, their dishes and photos.

Or on the server: `docker compose exec api node dist/cli.js remove-demo --all`.

Real users' posts are never touched.

## 4. Launch checklist

1. Update the server (`git pull && docker compose --profile https --profile backup up -d --build`).
2. Import Nashik from Overture Maps (above), then *Retire OpenStreetMap places* if you ever
   imported them. Check the numbers in *Jobs*.
3. Open the map in the app and switch it to show all places (not only ones with dishes).
4. Remove the demo.
5. Ask a few friends to post their favourite dishes: each post makes a place show up for
   everyone with its best dishes.
