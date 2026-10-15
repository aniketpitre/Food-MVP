// Admin commands, run inside the api container, e.g.
//   docker compose exec api node dist/cli.js seed
import { loadConfig } from './config.js';
import { createPool, migrate } from './db.js';
import { removeDemoActivity, seedCatalog, seedDemoActivity } from './seed.js';

const [command] = process.argv.slice(2);
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
      log('ranking refreshed');
      break;
    default:
      console.error('usage: node dist/cli.js <migrate|seed|demo-activity|remove-demo|refresh>');
      process.exitCode = 1;
  }
} finally {
  await db.end();
}
