import type { Db } from './db.js';

export type MessageKind = 'text' | 'dish' | 'story_reply' | 'story_like';

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
  // The other person sees it under Requests unless they follow you, like Instagram.
  await db.query(
    `insert into conversation_members (conversation_id, user_id, folder)
     values ($1, $2, 'inbox'),
            ($1, $3, case when exists (select 1 from follows where follower_id = $3 and followee_id = $2)
                          then 'inbox' else 'request' end)
     on conflict do nothing`,
    [id, me, other],
  );
  return id;
}

/**
 * Whether [me] may write to [other]. Blocks always stop it. Otherwise the other person's
 * setting decides (everyone, only people they follow, or nobody), except that a conversation
 * they have already written in stays open, the way a chat you started yourself would.
 */
export async function canMessage(db: Pick<Db, 'query'>, me: string, other: string): Promise<boolean> {
  if (me === other) return false;
  if (await blockedEither(db, me, other)) return false;
  const { rows } = await db.query<{ policy: string; follows: boolean; replied: boolean }>(
    `select p.dm_policy as policy,
            exists (select 1 from follows where follower_id = $2 and followee_id = $1) as follows,
            exists (select 1 from conversations c join messages m on m.conversation_id = c.id
                    where c.pair_key = $3 and m.sender_id = $2) as replied
     from profiles p where p.id = $2`,
    [me, other, [me, other].sort().join(':')],
  );
  const r = rows[0];
  if (!r) return false;
  if (r.replied) return true;
  if (r.policy === 'off') return false;
  if (r.policy === 'following') return r.follows;
  return true;
}

/** Sends a message from [me] to [other], opening the conversation if needed. Returns its id. */
export async function sendMessage(
  db: Pick<Db, 'query'>,
  me: string,
  other: string,
  m: { kind: MessageKind; body?: string | null; dishId?: string | null; storyId?: string | null },
): Promise<{ conversationId: string; messageId: string }> {
  if (!(await canMessage(db, me, other))) {
    throw new MessageError(403, 'cannot_message', "You can't message this person.");
  }
  const conversationId = await conversationWith(db, me, other);
  const { rows } = await db.query<{ id: string }>(
    `insert into messages (conversation_id, sender_id, kind, body, dish_id, story_id)
     values ($1, $2, $3, $4, $5, $6) returning id`,
    [conversationId, me, m.kind, m.body ?? null, m.dishId ?? null, m.storyId ?? null],
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
