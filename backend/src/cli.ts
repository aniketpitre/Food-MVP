// Admin commands, run inside the api container, e.g.
//   docker compose exec api node dist/cli.js seed
import { importOverturePlaces, overtureFileFor, readOvertureFile } from './overture.js';
import { loadConfig } from './config.js';
import { createPool, migrate } from './db.js';
import { removeDemoActivity, removeDemoCatalog, restoreDemoPlaces, seedCatalog, seedDemoActivity, setDemoRetired } from './seed.js';
import { refreshDemoDishPhotos } from './demo-photos.js';
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
      await setDemoRetired(db, false);
      if (!(await seedDemoActivity(db, log))) log('demo activity already present');
      break;
    case 'remove-demo': {
      // remove-demo        : the demo_ accounts and their votes, follows, stories
      // remove-demo --all  : also the invented demo places, dishes and DEMO IMAGE photos
      log(`removed ${await removeDemoActivity(db)} demo accounts and their actions`);
      if (args.includes('--all')) {
        const r = await removeDemoCatalog(db, config.mediaDir);
        log(`removed ${r.places} demo places with their dishes and ${r.photos} demo photos`);
        if (r.kept.length) log(`kept ${r.kept.length} demo places where real people posted: ${r.kept.join(', ')}`);
      }
      break;
    }
    case 'seed-demo': {
      // The demo again: the invented Nashik places and dishes (if removed), the demo_
      // accounts' activity, and the real, credited photos (bundled) for every demo dish.
      await migrate(db, log);
      // Asked for on purpose: un-retire the demo so it seeds (and keeps its stories going).
      await setDemoRetired(db, false);
      if (!(await seedCatalog(db, config.mediaDir, log)) && !(await restoreDemoPlaces(db, config.mediaDir, log))) {
        log('demo places already present');
      }
      if (!(await seedDemoActivity(db, log))) log('demo activity already present');
      const r = await refreshDemoDishPhotos(db, config.mediaDir, log);
      log(`real photos for ${r.updated} demo dishes` + (r.missing.length ? `; none found for: ${r.missing.join(', ')}` : ''));
      await db.query('select refresh_dish_stats()');
      await db.query('select refresh_people_pick()');
      break;
    }
    case 'demo-photos': {
      // Only the photos: swap old drawn demo pictures for the bundled real ones.
      const r = await refreshDemoDishPhotos(db, config.mediaDir, log);
      log(`real photos for ${r.updated} demo dishes` + (r.missing.length ? `; none found for: ${r.missing.join(', ')}` : ''));
      break;
    }
    case 'refresh':
      await db.query('select refresh_dish_stats()');
      await db.query('select refresh_people_pick()');
      log('ranking refreshed');
      break;
    case 'import-overture': {
      // Every food place from Overture Maps, from the bundled file (seed/places/<city>.overture.json)
      // or one made with scripts/overture-places.py, e.g. import-overture nashik
      const slug = args[0];
      if (!slug) throw new Error('usage: import-overture <city-slug> [file.json]');
      const { rows } = await db.query<{ id: number; name: string }>('select id, name from cities where slug = $1', [slug]);
      const city = rows[0];
      if (!city) throw new Error(`no city with slug "${slug}"`);
      const file = await readOvertureFile(args[1] ?? overtureFileFor(slug));
      const r = await importOverturePlaces(db, city.id, file.places);
      log(`${city.name}: ${file.places.length} Overture places (release ${file.release}), ${r.added} added, ${r.linked} matched to existing places, ${r.skipped} already imported`);
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
      console.error('usage: node dist/cli.js <migrate|seed|demo-activity|remove-demo|refresh|import-overture <city> [file]|fetch-place-photos <city> [max]>');
      process.exitCode = 1;
  }
} finally {
  await db.end();
}
