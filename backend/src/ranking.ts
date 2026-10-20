import type { Db } from './db.js';

/**
 * Recomputes every dish's score on a timer so time decay and the 7-day window move
 * even when nobody acts. Individual actions refresh their own dish immediately.
 */
export function startRankingRefresh(db: Db, everySeconds: number, onError: (err: unknown) => void): () => void {
  if (everySeconds <= 0) return () => {};
  const timer = setInterval(() => {
    // The first rebuild of each day also keeps a copy of each city's Top, to see how it moves.
    // The taste table is kept current per person; a full rebuild catches dish types changing.
    // One at a time, each on its own: one failing doesn't stop the others. Pending places
    // that earned it go live first, so the rebuild already counts them.
    void (async () => {
      for (const sql of ['select auto_approve_places()', 'select refresh_dish_stats()', 'select refresh_people_pick()',
        'select snapshot_top()', 'select refresh_taste_affinity()']) {
        await db.query(sql).catch(onError);
      }
    })();
  }, everySeconds * 1000);
  timer.unref();
  return () => clearInterval(timer);
}

/**
 * After a vote, everyone's trust (who to trust for what) is rebuilt once things go quiet
 * for a few seconds: a burst of votes shares one rebuild instead of each vote waiting for
 * its own. Under a steady stream of votes the ranking timer still rebuilds it regularly.
 */
export function scheduleTrustRefresh(db: Db, delayMs = 3000, onError: (err: unknown) => void = () => {}): void {
  lastVote = Date.now();
  if (pendingTrust) return;
  const fire = () => {
    const quietFor = Date.now() - lastVote;
    if (quietFor < delayMs) {
      pendingTrust = setTimeout(fire, delayMs - quietFor);
      pendingTrust.unref();
      return;
    }
    pendingTrust = null;
    db.query('select refresh_people_pick()').catch(onError);
  };
  pendingTrust = setTimeout(fire, delayMs);
  pendingTrust.unref();
}

let pendingTrust: NodeJS.Timeout | null = null;
let lastVote = 0;
