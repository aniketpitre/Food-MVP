import { buildApp } from './app.js';
import { loadConfig } from './config.js';
import { createPool, migrate } from './db.js';
import { startRankingRefresh } from './ranking.js';
import { ensureCities } from './geo.js';
import { seedCatalog, seedDemoActivity } from './seed.js';

const config = loadConfig();
const db = createPool(config.databaseUrl);
const app = await buildApp(config, db);

await migrate(db, (m) => app.log.info(m));
if (config.seedOnStart) {
  await seedCatalog(db, config.mediaDir, (m) => app.log.info(m));
  if (config.demoActivity) await seedDemoActivity(db, (m) => app.log.info(m));
}
await ensureCities(db, (m) => app.log.info(m));
await db.query('select refresh_dish_stats()');
const stopRefresh = startRankingRefresh(db, config.rankRefreshSeconds, (err) => app.log.error(err));

await app.listen({ port: config.port, host: '0.0.0.0' });

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, async () => {
    stopRefresh();
    await app.close();
    await db.end();
    process.exit(0);
  });
}
