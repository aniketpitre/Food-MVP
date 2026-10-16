// Admin commands, run inside the api container, e.g.
//   docker compose exec api node dist/cli.js seed
import { loadConfig } from './config.js';
import { createPool, migrate } from './db.js';
import { removeDemoActivity, seedCatalog, seedDemoActivity } from './seed.js';
import { fetchOsmPlaces, importOsmPlaces } from './osm.js';
import { fetchPlacePhotos } from './photos.js';

const [command, ...args] = process.argv.slice(2);
const config = loadConfig();
const db = createPool(config.databaseUrl);
const log = (m: string) => console.log(m);

try {
  switch (command) {
    case 'migrate':
      await migrate(db, log);
      break;
    case 'seed':
      await migrate(db, log);
      if (!(await seedCatalog(db, config.mediaDir, log))) log('catalog already present, nothing to do');
      break;
    case 'demo-activity':
      if (!(await seedDemoActivity(db, log))) log('demo activity already present');
      break;
    case 'remove-demo':
      log(`removed ${await removeDemoActivity(db)} demo accounts and their actions`);
      break;
    case 'refresh':
      await db.query('select refresh_dish_stats()');
      await db.query('select refresh_people_pick()');
      log('ranking refreshed');
      break;
    case 'import-places': {
      // Real restaurants, cafes and bakeries from OpenStreetMap, e.g. import-places nashik 12
      const slug = args[0];
      if (!slug) throw new Error('usage: import-places <city-slug> [radius-km]');
      const { rows } = await db.query<{ id: number; name: string; lat: number; lng: number; radius_km: number }>(
        'select id, name, st_y(center::geometry) as lat, st_x(center::geometry) as lng, radius_km from cities where slug = $1',
        [slug],
      );
      const city = rows[0];
      if (!city) throw new Error(`no city with slug "${slug}" (see GET /cities?q=...)`);
      const radius = Number(args[1] ?? city.radius_km);
      log(`asking OpenStreetMap for food places within ${radius} km of ${city.name}...`);
      const places = await fetchOsmPlaces(city.lat, city.lng, radius);
      const r = await importOsmPlaces(db, city.id, places);
      log(`${city.name}: ${places.length} found, ${r.added} added, ${r.linked} matched to existing places, ${r.skipped} already imported`);
      break;
    }
    case 'fetch-place-photos': {
      // Openly licensed photos of a city's places: Wikimedia Commons, plus Mapillary street
      // photos of the shopfront when MAPILLARY_TOKEN is set. e.g. fetch-place-photos nashik 200
      const slug = args[0];
      if (!slug) throw new Error('usage: fetch-place-photos <city-slug> [max-places]');
      const { rows } = await db.query<{ id: number; name: string }>('select id, name from cities where slug = $1', [slug]);
      const city = rows[0];
      if (!city) throw new Error(`no city with slug "${slug}"`);
      if (!config.mapillaryToken) log('MAPILLARY_TOKEN is not set: only places linked to Wikimedia Commons will get photos.');
      const r = await fetchPlacePhotos(db, config.mediaDir, city.id, {
        mapillaryToken: config.mapillaryToken || undefined,
        limit: Number(args[1] ?? 500),
        log,
        pauseMs: 250,
      });
      log(`${city.name}: checked ${r.checked} places, added ${r.added} photos`);
      break;
    }
    default:
      console.error('usage: node dist/cli.js <migrate|seed|demo-activity|remove-demo|refresh|import-places <city> [km]|fetch-place-photos <city> [max]>');
      process.exitCode = 1;
  }
} finally {
  await db.end();
}
