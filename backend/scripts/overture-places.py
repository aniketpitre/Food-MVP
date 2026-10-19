#!/usr/bin/env python3
"""
Every food place in a city from Overture Maps (https://overturemaps.org), as a JSON file the
server imports (`node dist/cli.js import-overture <city-slug> <file>` or the admin dashboard).

Overture's places come from Meta, Microsoft, Foursquare, AllThePlaces and others, cleaned
and merged, under CDLA-Permissive-2.0: free to keep and use with credit. For Indian cities
it lists several times more restaurants, cafes and stalls than OpenStreetMap alone.

    pip install pyarrow
    python3 scripts/overture-places.py --city nashik --lat 20.0059 --lng 73.7898 --km 25 \
        --out seed/places/nashik.overture.json

Needs only HTTPS to Overture's public S3 bucket; reads just the parts of the files around
the city (a few MB), not the whole 11 GB.
"""
import argparse, json, math, os, re, sys, urllib.request
from datetime import date
from urllib.parse import urlparse

import pyarrow.compute as pc
import pyarrow.dataset as ds
import pyarrow.fs as pafs

BUCKET = 'overturemaps-us-west-2'

# Overture categories that are places to eat or drink (anything else ending in _restaurant
# or _bar is in too). Grocery, liquor, butchers and delivery-only kitchens are not.
FOOD = {
    'restaurant', 'cafe', 'coffee_shop', 'bakery', 'ice_cream_shop', 'dessert_shop', 'candy_store',
    'cupcake_shop', 'chocolatier', 'frozen_yogurt_shop', 'patisserie_cake_shop', 'diner', 'bar',
    'pub', 'brewery', 'food_truck', 'food_stand', 'food_truck_stand', 'food_court', 'juice_bar',
    'smoothie_juice_bar', 'tea_room', 'bubble_tea', 'sandwich_shop', 'donut_shop', 'casual_eatery',
    'fast_food_restaurant', 'non_alcoholic_beverage_venue', 'beer_garden', 'canteen', 'dhaba',
}
NOT_FOOD = {'internet_cafe', 'food_delivery_service', 'grocery_store', 'liquor_store', 'butcher_shop'}

# What the category says about the food, for cuisine search ("pizza_restaurant" -> "pizza").
CUISINE = {
    'cafe': 'cafe', 'coffee_shop': 'coffee shop', 'bakery': 'bakery', 'ice_cream_shop': 'ice cream',
    'dessert_shop': 'dessert', 'candy_store': 'sweets', 'cupcake_shop': 'cake', 'chocolatier': 'dessert',
    'frozen_yogurt_shop': 'dessert', 'patisserie_cake_shop': 'cake', 'bar': 'bar', 'pub': 'bar',
    'brewery': 'bar', 'juice_bar': 'juice', 'smoothie_juice_bar': 'juice', 'tea_room': 'tea',
    'bubble_tea': 'bubble tea', 'sandwich_shop': 'sandwich', 'fast_food_restaurant': 'fast food',
    'food_truck_stand': 'street food', 'food_truck': 'street food', 'food_stand': 'street food',
}


def s3():
    proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
    opts = {}
    if proxy:
        p = urlparse(proxy)
        opts['proxy_options'] = {'scheme': p.scheme, 'host': p.hostname, 'port': p.port}
    return pafs.S3FileSystem(anonymous=True, region='us-west-2', **opts)


def latest_release() -> str:
    url = f'https://{BUCKET}.s3.us-west-2.amazonaws.com/?list-type=2&prefix=release/&delimiter=/'
    xml = urllib.request.urlopen(url, timeout=30).read().decode()
    releases = sorted(re.findall(r'<Prefix>release/([^/<]+)/</Prefix>', xml))
    if not releases:
        sys.exit('No Overture release found')
    return releases[-1]


def km(lat1, lng1, lat2, lng2):
    x = (lng2 - lng1) * math.cos(math.radians((lat1 + lat2) / 2)) * 111.32
    y = (lat2 - lat1) * 110.57
    return math.hypot(x, y)


def read(fs, release, kind, cols, lat, lng, radius):
    dlat = radius / 110.57
    dlng = radius / (111.32 * math.cos(math.radians(lat)))
    f = ((pc.field('bbox', 'xmin') >= lng - dlng) & (pc.field('bbox', 'xmin') <= lng + dlng)
         & (pc.field('bbox', 'ymin') >= lat - dlat) & (pc.field('bbox', 'ymin') <= lat + dlat))
    d = ds.dataset(f'{BUCKET}/release/{release}/{kind}/', filesystem=fs, format='parquet')
    return d.to_table(columns=cols, filter=f).to_pylist()


def is_food(primary: str | None, hierarchy: list | None) -> bool:
    if not primary or primary in NOT_FOOD:
        return False
    if primary in FOOD or primary.endswith('_restaurant') or primary.endswith('_bar'):
        return True
    return False


def cuisine_of(primary: str) -> str | None:
    if primary in CUISINE:
        return CUISINE[primary]
    if primary.endswith('_restaurant') and primary not in ('fast_food_restaurant',):
        return primary[: -len('_restaurant')].replace('_', ' ')
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--city', required=True)
    ap.add_argument('--lat', type=float, required=True)
    ap.add_argument('--lng', type=float, required=True)
    ap.add_argument('--km', type=float, default=25)
    ap.add_argument('--min-confidence', type=float, default=0.4)
    ap.add_argument('--release', default=None)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    fs = s3()
    release = a.release or latest_release()
    print(f'Overture release {release}', file=sys.stderr)

    areas = []
    for r in read(fs, release, 'theme=divisions/type=division', ['names', 'subtype', 'bbox'], a.lat, a.lng, a.km + 3):
        name = (r['names'] or {}).get('primary')
        if name and r['subtype'] in ('neighborhood', 'macrohood', 'microhood', 'locality'):
            b = r['bbox']
            areas.append((r['subtype'], name, (b['ymin'] + b['ymax']) / 2, (b['xmin'] + b['xmax']) / 2))

    def area_of(lat, lng):
        best = None
        for subtype, name, alat, alng in areas:
            d = km(lat, lng, alat, alng)
            reach = 3.0 if subtype == 'locality' else 2.0
            # A neighbourhood beats a village/town of about the same distance.
            score = d + (1.0 if subtype == 'locality' else 0)
            if d <= reach and (best is None or score < best[0]):
                best = (score, name)
        return best[1] if best else None

    cols = ['id', 'names', 'confidence', 'taxonomy', 'basic_category', 'addresses', 'websites', 'phones',
            'operating_status', 'bbox']
    rows = read(fs, release, 'theme=places/type=place', cols, a.lat, a.lng, a.km)
    places, skipped = [], {'not food': 0, 'low confidence': 0, 'closed': 0, 'too far': 0, 'no name': 0}
    for r in rows:
        tax = r.get('taxonomy') or {}
        primary = tax.get('primary') or r.get('basic_category')
        if not is_food(primary, tax.get('hierarchy')):
            skipped['not food'] += 1
            continue
        name = ((r['names'] or {}).get('primary') or '').strip()
        if len(name) < 2:
            skipped['no name'] += 1
            continue
        if (r['confidence'] or 0) < a.min_confidence:
            skipped['low confidence'] += 1
            continue
        if r.get('operating_status') in ('permanently_closed', 'temporarily_closed'):
            skipped['closed'] += 1
            continue
        b = r['bbox']
        lat, lng = (b['ymin'] + b['ymax']) / 2, (b['xmin'] + b['xmax']) / 2
        if km(a.lat, a.lng, lat, lng) > a.km:
            skipped['too far'] += 1
            continue
        addr = (r['addresses'] or [{}])[0] or {}
        places.append({
            'id': r['id'],
            'name': name[:80],
            'lat': round(lat, 6),
            'lng': round(lng, 6),
            'kind': primary,
            'cuisine': cuisine_of(primary),
            'area': area_of(lat, lng),
            'address': (addr.get('freeform') or '')[:160] or None,
            'phone': (r['phones'] or [None])[0],
            'website': (r['websites'] or [None])[0],
            'confidence': round(r['confidence'] or 0, 2),
        })
    places.sort(key=lambda p: -p['confidence'])
    out = {
        'source': 'Overture Maps Foundation',
        'licence': 'CDLA-Permissive-2.0',
        'credit': '© Overture Maps Foundation contributors (Meta, Microsoft, Foursquare, OpenStreetMap and others)',
        'release': release,
        'generated': date.today().isoformat(),
        'city': a.city,
        'center': [a.lat, a.lng],
        'radiusKm': a.km,
        'places': places,
    }
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, 'w') as fh:
        json.dump(out, fh, ensure_ascii=False, indent=0)
    print(f'{len(places)} places written to {a.out}; skipped {skipped}', file=sys.stderr)


if __name__ == '__main__':
    main()
