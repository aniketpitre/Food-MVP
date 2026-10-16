import type { Db } from './db.js';

/**
 * Recomputes every dish's score on a timer so time decay and the 7-day window move
 * even when nobody acts. Individual actions refresh their own dish immediately.
 */
export function startRankingRefresh(db: Db, everySeconds: number, onError: (err: unknown) => void): () => void {
  if (everySeconds <= 0) return () => {};
  const timer = setInterval(() => {
    db.query('select refresh_dish_stats(); select refresh_people_pick();').catch(onError);
  }, everySeconds * 1000);
  timer.unref();
  return () => clearInterval(timer);
}
