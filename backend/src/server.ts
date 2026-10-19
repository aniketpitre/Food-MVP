import { buildApp } from './app.js';
import { loadConfig } from './config.js';
import { createPool, migrate } from './db.js';
import { startRankingRefresh } from './ranking.js';
import { ensureCities } from './geo.js';
import { seedCatalog, seedDemoActivity, syncVocabulary } from './seed.js';
import { cleanupStories, refreshDemoStories } from './routes/stories.js';

const config = loadConfig();
const db = createPool(config.databaseUrl);
const app = await buildApp(config, db);

await migrate(db, (m) => app.log.info(m));
if (config.seedOnStart) {
  await seedCatalog(db, config.mediaDir, (m) => app.log.info(m), { demoPlaces: config.demoActivity });
  if (config.demoActivity) await seedDemoActivity(db, (m) => app.log.info(m));
}
await ensureCities(db, (m) => app.log.info(m));
await syncVocabulary(db, (m) => app.log.info(m));
await db.query('select refresh_dish_stats()');
await db.query('select refresh_people_pick()');
const stopRefresh = startRankingRefresh(db, config.rankRefreshSeconds, (err) => app.log.error(err));

// Every 10 minutes: clear out ended stories, and keep the demo stories going on demo servers.
const storyJob = async () => {
  try {
    await cleanupStories(db, config.mediaDir);
    if (config.demoActivity) await refreshDemoStories(db);
  } catch (err) {
    app.log.error(err);
  }
};
await storyJob();
const storyTimer = setInterval(storyJob, 600_000);
storyTimer.unref();

await app.listen({ port: config.port, host: '0.0.0.0' });

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, async () => {
    stopRefresh();
    clearInterval(storyTimer);
    await app.close();
    await db.end();
    process.exit(0);
  });
}
