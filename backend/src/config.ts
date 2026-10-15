import path from 'node:path';

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
    authRateLimitPerMinute: Number(process.env.AUTH_RATE_LIMIT_PER_MINUTE ?? 10),
    seedOnStart: process.env.SEED_ON_START === 'true',
    demoActivity: process.env.DEMO_ACTIVITY === 'true',
    appDownloadUrl: process.env.APP_DOWNLOAD_URL ?? 'https://github.com/aniketpitre/Food-MVP/releases/latest',
    contactEmail: process.env.CONTACT_EMAIL ?? 'contact@example.com',
    logLevel: process.env.LOG_LEVEL ?? 'info',
  };
}
