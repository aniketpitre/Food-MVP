import path from 'node:path';
import { type MailConfig, mailConfigFromEnv } from './mail.js';
import { type PushConfig, pushConfigFromEnv } from './push.js';

function required(name: string, fallback?: string): string {
  const v = process.env[name] ?? fallback;
  if (v === undefined || v === '') throw new Error(`Missing environment variable ${name}`);
  return v;
}

export interface Config {
  port: number;
  databaseUrl: string;
  jwtSecret: string;
  mediaDir: string;
  publicUrl: string;
  adminEmails: string[];
  rankRefreshSeconds: number;
  authRateLimitPerMinute: number;
  seedOnStart: boolean;
  demoActivity: boolean;
  appDownloadUrl: string;
  contactEmail: string;
  logLevel: string;
  mapillaryToken: string;
  googlePlacesApiKey: string;
  googlePhotosDailyLimit: number;
  routingUrl: string;
  /** Firebase Cloud Messaging, for instant notifications; null = phones check by themselves. */
  push?: PushConfig | null;
  /** SMTP for sign-in codes; null = two-step sign-in can't be turned on. */
  mail?: MailConfig | null;
  /** India-time hours [from, to] when the daily "turn on two-step sign-in" reminder may go out. */
  remindHours?: [number, number];
}

export function loadConfig(): Config {
  const jwtSecret = required('JWT_SECRET');
  if (jwtSecret.length < 32) throw new Error('JWT_SECRET must be at least 32 characters');
  return {
    port: Number(process.env.PORT ?? 8080),
    databaseUrl: required('DATABASE_URL'),
    jwtSecret,
    mediaDir: path.resolve(process.env.MEDIA_DIR ?? './data/media'),
    publicUrl: (process.env.PUBLIC_URL ?? 'http://localhost:8080').replace(/\/+$/, ''),
    adminEmails: (process.env.ADMIN_EMAILS ?? '')
      .split(',')
      .map((e) => e.trim().toLowerCase())
      .filter(Boolean),
    rankRefreshSeconds: Number(process.env.RANK_REFRESH_SECONDS ?? 300),
    // Per IP address. Phone networks put thousands of people behind one address, so this
    // is generous; each account and each email/username has its own tighter limit too.
    authRateLimitPerMinute: Number(process.env.AUTH_RATE_LIMIT_PER_MINUTE ?? 60),
    seedOnStart: process.env.SEED_ON_START === 'true',
    demoActivity: process.env.DEMO_ACTIVITY === 'true',
    appDownloadUrl: process.env.APP_DOWNLOAD_URL ?? 'https://github.com/aniketpitre/Food-MVP/releases/latest',
    contactEmail: process.env.CONTACT_EMAIL ?? 'contact@example.com',
    logLevel: process.env.LOG_LEVEL ?? 'info',
    mapillaryToken: process.env.MAPILLARY_TOKEN ?? '',
    googlePlacesApiKey: process.env.GOOGLE_PLACES_API_KEY ?? '',
    // 30 a day stays inside Google's 1,000 free photo loads a month.
    googlePhotosDailyLimit: Number(process.env.GOOGLE_PHOTOS_DAILY_LIMIT ?? 30),
    // Driving routes for "food stops along the way" (an OSRM server; empty = straight lines).
    routingUrl: process.env.ROUTING_URL ?? 'https://router.project-osrm.org',
    push: pushConfigFromEnv(),
    mail: mailConfigFromEnv(),
    remindHours: parseHours(process.env.REMIND_HOURS),
  };
}

/** "10-20" -> [10, 20] (India time); anything else -> the default. */
function parseHours(v: string | undefined): [number, number] {
  const m = v?.match(/^(\d{1,2})-(\d{1,2})$/);
  if (!m) return [10, 20];
  return [Math.min(23, Number(m[1])), Math.min(23, Number(m[2]))];
}
