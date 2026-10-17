import { createSign } from 'node:crypto';
import type { Db } from './db.js';

/**
 * Push: a tiny wake-up message to people's phones (Firebase Cloud Messaging) when something
 * new is waiting for them, so notifications arrive in seconds even when the app is closed.
 * The message carries no content: the phone asks GET /updates what's new, exactly as it
 * does when it checks by itself (every 15 minutes in the background), and shows that.
 * Without Firebase settings this does nothing and phones keep checking by themselves.
 */
export interface PushConfig {
  projectId: string;
  clientEmail: string;
  privateKey: string;
  /** What the app needs to start Firebase (public values from the Firebase console). */
  app: { apiKey: string; appId: string; senderId: string } | null;
}

export function pushConfigFromEnv(env = process.env): PushConfig | null {
  const raw = env.FCM_SERVICE_ACCOUNT;
  if (!raw) return null;
  let sa: { project_id?: string; client_email?: string; private_key?: string };
  try {
    sa = JSON.parse(raw.trim().startsWith('{') ? raw : Buffer.from(raw, 'base64').toString('utf8'));
  } catch {
    throw new Error('FCM_SERVICE_ACCOUNT must be the service account JSON (or that JSON in base64).');
  }
  if (!sa.project_id || !sa.client_email || !sa.private_key) throw new Error('FCM_SERVICE_ACCOUNT is missing project_id, client_email or private_key.');
  const app = env.FCM_API_KEY && env.FCM_APP_ID && env.FCM_SENDER_ID
    ? { apiKey: env.FCM_API_KEY, appId: env.FCM_APP_ID, senderId: env.FCM_SENDER_ID }
    : null;
  return { projectId: sa.project_id, clientEmail: sa.client_email, privateKey: sa.private_key, app };
}

type Fetch = typeof fetch;

export class Push {
  private token: { value: string; expires: number } | null = null;
  /** Last wake-up per person, so a burst (ten likes in a second) wakes the phone once. */
  private lastSent = new Map<string, number>();
  private pending = new Map<string, ReturnType<typeof setTimeout>>();

  constructor(
    private db: Pick<Db, 'query'>,
    readonly config: PushConfig | null,
    private log: { warn: (o: object, m: string) => void } = console,
    private http: Fetch = fetch,
    private gapMs = 3000,
  ) {}

  get enabled() {
    return this.config !== null;
  }

  /** Wakes these people's phones (soon, at most once every few seconds each). */
  tickle(userIds: Iterable<string | null | undefined>) {
    if (!this.config) return;
    for (const id of userIds) {
      if (!id || this.pending.has(id)) continue;
      const wait = Math.max(0, (this.lastSent.get(id) ?? 0) + this.gapMs - Date.now());
      const t = setTimeout(() => {
        this.pending.delete(id);
        this.lastSent.set(id, Date.now());
        void this.sendTo(id).catch((err) => this.log.warn({ err }, 'push failed'));
      }, wait);
      t.unref?.();
      this.pending.set(id, t);
    }
    if (this.lastSent.size > 50_000) this.lastSent.clear();
  }

  /** Wakes everyone in a city (for new food nearby; each phone decides if it's near). */
  async tickleCity(cityId: number, except?: string) {
    if (!this.config) return;
    const { rows } = await this.db.query<{ user_id: string }>(
      'select distinct user_id from push_tokens where city_id = $1 and user_id is distinct from $2 limit 5000',
      [cityId, except ?? null],
    );
    this.tickle(rows.map((r) => r.user_id));
  }

  /** Wakes the followers of [userId] (a new story or post). */
  async tickleFollowers(userId: string) {
    if (!this.config) return;
    const { rows } = await this.db.query<{ follower_id: string }>(
      'select follower_id from follows where followee_id = $1 limit 5000',
      [userId],
    );
    this.tickle(rows.map((r) => r.follower_id));
  }

  /** Cancels waiting wake-ups (shutdown, tests). */
  stop() {
    for (const t of this.pending.values()) clearTimeout(t);
    this.pending.clear();
  }

  private async sendTo(userId: string) {
    const { rows } = await this.db.query<{ token: string }>('select token from push_tokens where user_id = $1 limit 10', [userId]);
    if (!rows.length) return;
    const access = await this.accessToken();
    const url = `https://fcm.googleapis.com/v1/projects/${this.config!.projectId}/messages:send`;
    for (const { token } of rows) {
      const res = await this.http(url, {
        method: 'POST',
        headers: { authorization: `Bearer ${access}`, 'content-type': 'application/json' },
        // High priority so the phone wakes now; collapse so a backlog is one wake-up.
        body: JSON.stringify({ message: { token, data: { t: 'updates' }, android: { priority: 'HIGH', collapse_key: 'updates', ttl: '3600s' } } }),
        signal: AbortSignal.timeout(10_000),
      });
      if (res.status === 404 || res.status === 400) {
        // The app was uninstalled or the token is stale: forget it.
        const body = await res.text();
        if (/UNREGISTERED|INVALID_ARGUMENT|NOT_FOUND/.test(body)) await this.db.query('delete from push_tokens where token = $1', [token]);
      } else if (!res.ok) {
        this.log.warn({ status: res.status }, 'push rejected');
      }
    }
  }

  /** An OAuth token for FCM from the service account, kept until shortly before it expires. */
  private async accessToken(): Promise<string> {
    if (this.token && this.token.expires > Date.now() + 60_000) return this.token.value;
    const c = this.config!;
    const res = await this.http('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: serviceJwt(c) }).toString(),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`Google sign-in for push failed (${res.status})`);
    const body = (await res.json()) as { access_token: string; expires_in: number };
    this.token = { value: body.access_token, expires: Date.now() + body.expires_in * 1000 };
    return body.access_token;
  }
}

/** The signed request a service account sends Google for an access token (RS256 JWT). */
export function serviceJwt(c: Pick<PushConfig, 'clientEmail' | 'privateKey'>, now = Math.floor(Date.now() / 1000)): string {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
    iss: c.clientEmail,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  })}`;
  const sig = createSign('RSA-SHA256').update(unsigned).sign(c.privateKey.replace(/\\n/g, '\n'), 'base64url');
  return `${unsigned}.${sig}`;
}
