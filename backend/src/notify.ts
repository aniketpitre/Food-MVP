import type { Db } from './db.js';

export type NotifyKind = 'follow' | 'comment' | 'reply' | 'comment_like' | 'mention' | 'recommend' | 'agree';

/**
 * Adds an Activity item for [userId] about something [actorId] did. Never notifies people
 * about themselves, never across a block (either direction), and never twice for the same
 * thing (toggling a like off and on is one notification).
 */
export async function notify(
  db: Pick<Db, 'query'>,
  n: { userId: string | null | undefined; actorId: string; kind: NotifyKind; dishId?: string | null; postId?: string | null; commentId?: string | null },
): Promise<void> {
  if (!n.userId || n.userId === n.actorId) return;
  await db.query(
    `insert into notifications (user_id, actor_id, kind, dish_id, post_id, comment_id)
     select $1, $2, $3, $4, $5, $6
     where not exists (select 1 from blocks
                       where (blocker_id = $1 and blocked_id = $2) or (blocker_id = $2 and blocked_id = $1))
     on conflict do nothing`,
    [n.userId, n.actorId, n.kind, n.dishId ?? null, n.postId ?? null, n.commentId ?? null],
  );
}

/** Handles mentioned as @name in a text, lower-cased and de-duplicated. */
export function mentionedHandles(text: string): string[] {
  return [...new Set([...text.matchAll(/@([a-z0-9_]{3,20})/gi)].map((m) => m[1]!.toLowerCase()))].slice(0, 10);
}
