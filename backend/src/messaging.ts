import type { Db } from './db.js';

export type MessageKind = 'text' | 'dish' | 'story_reply' | 'story_like' | 'place' | 'post' | 'photo';

export class MessageError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

/** True when either person blocked the other. */
export async function blockedEither(db: Pick<Db, 'query'>, a: string, b: string): Promise<boolean> {
  const { rowCount } = await db.query(
    'select 1 from blocks where (blocker_id = $1 and blocked_id = $2) or (blocker_id = $2 and blocked_id = $1)',
    [a, b],
  );
  return !!rowCount;
}

/** The one-to-one conversation between two people, created on first use. */
export async function conversationWith(db: Pick<Db, 'query'>, me: string, other: string): Promise<string> {
  const key = [me, other].sort().join(':');
  const { rows } = await db.query<{ id: string }>(
    `insert into conversations (pair_key) values ($1)
     on conflict (pair_key) do update set pair_key = excluded.pair_key returning id`,
    [key],
  );
  const id = rows[0]!.id;
  // Shares only ever go between friends, so they land straight in the inbox.
  await db.query(
    `insert into conversation_members (conversation_id, user_id, folder)
     values ($1, $2, 'inbox'), ($1, $3, 'inbox')
     on conflict (conversation_id, user_id) do update set folder = 'inbox'`,
    [id, me, other],
  );
  return id;
}

/** What can be shared in messages for now: a dish, or a post (a photo of a dish). */
export const SHARE_KINDS = ['dish', 'post'] as const;

/**
 * Whether [me] may share dishes with [other]: only friends, meaning you follow each other.
 * Blocks always stop it, and so does the other person turning sharing off.
 */
export async function canMessage(db: Pick<Db, 'query'>, me: string, other: string): Promise<boolean> {
  if (me === other) return false;
  if (await blockedEither(db, me, other)) return false;
  const { rows } = await db.query<{ policy: string; mutual: boolean }>(
    `select p.dm_policy as policy,
            exists (select 1 from follows where follower_id = $1 and followee_id = $2)
              and exists (select 1 from follows where follower_id = $2 and followee_id = $1) as mutual
     from profiles p where p.id = $2`,
    [me, other],
  );
  const r = rows[0];
  if (!r) return false;
  return r.mutual && r.policy !== 'off';
}

/** Sends a message from [me] to [other], opening the conversation if needed. Returns its id. */
export async function sendMessage(
  db: Pick<Db, 'query'>,
  me: string,
  other: string,
  m: {
    kind: MessageKind;
    body?: string | null;
    dishId?: string | null;
    storyId?: string | null;
    restaurantId?: string | null;
    postId?: string | null;
    imagePath?: string | null;
    thumbPath?: string | null;
    replyTo?: string | null;
  },
): Promise<{ conversationId: string; messageId: string }> {
  if (!(SHARE_KINDS as readonly string[]).includes(m.kind)) {
    throw new MessageError(400, 'share_only', 'Only dishes can be shared for now.');
  }
  if (!(await canMessage(db, me, other))) {
    throw new MessageError(403, 'cannot_message', 'You can share dishes only with friends: people you follow who follow you back.');
  }
  const conversationId = await conversationWith(db, me, other);
  const { rows } = await db.query<{ id: string }>(
    `insert into messages (conversation_id, sender_id, kind, body, dish_id, story_id, restaurant_id, post_id,
                           image_path, thumb_path, reply_to)
     values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10,
             (select id from messages where id = $11 and conversation_id = $1)) returning id`,
    [conversationId, me, m.kind, m.body ?? null, m.dishId ?? null, m.storyId ?? null, m.restaurantId ?? null,
      m.postId ?? null, m.imagePath ?? null, m.thumbPath ?? null, m.replyTo ?? null],
  );
  await db.query('update conversations set last_message_at = now() where id = $1', [conversationId]);
  // Writing in a request accepts it; your own message counts as read.
  await db.query(
    "update conversation_members set folder = 'inbox', last_read_at = now() where conversation_id = $1 and user_id = $2",
    [conversationId, me],
  );
  // If they follow you now, it belongs in their inbox even if it started as a request.
  await db.query(
    `update conversation_members set folder = 'inbox'
     where conversation_id = $1 and user_id = $2 and folder = 'request'
       and exists (select 1 from follows where follower_id = $2 and followee_id = $3)`,
    [conversationId, other, me],
  );
  return { conversationId, messageId: rows[0]!.id };
}
