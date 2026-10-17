import fs from 'node:fs';
import zlib from 'node:zlib';
import Fastify, { type FastifyInstance, type FastifyReply } from 'fastify';
import compress from '@fastify/compress';
import cors from '@fastify/cors';
import jwt from '@fastify/jwt';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import fastifyStatic from '@fastify/static';
import type { z } from 'zod';
import type { Config } from './config.js';
import type { Db } from './db.js';
import { authRoutes } from './routes/auth.js';
import { browseRoutes } from './routes/browse.js';
import { actionRoutes } from './routes/actions.js';
import { postRoutes } from './routes/posts.js';
import { commentRoutes } from './routes/comments.js';
import { socialRoutes } from './routes/social.js';
import { discoverRoutes } from './routes/discover.js';
import { adminRoutes } from './routes/admin.js';
import { messageRoutes } from './routes/messages.js';
import { storyRoutes } from './routes/stories.js';
import { pageRoutes } from './routes/pages.js';
import { tasteRoutes } from './routes/taste.js';
import { exploreRoutes } from './routes/explore.js';

declare module 'fastify' {
  interface FastifyInstance {
    db: Db;
    config: Config;
  }
}

export async function buildApp(config: Config, db: Db, opts: { logger?: boolean } = {}): Promise<FastifyInstance> {
  const app = Fastify({
    trustProxy: true,
    bodyLimit: 1024 * 1024,
    logger:
      opts.logger === false
        ? false
        : {
            level: config.logLevel,
            // Never log query strings: feed and nearby requests carry the user's coordinates.
            serializers: {
              req: (req) => ({ method: req.method, url: req.url.split('?')[0], ip: req.ip }),
            },
          },
  });

  app.setErrorHandler((err: Error & { statusCode?: number; code?: string }, req, reply) => {
    if (err.statusCode && err.statusCode < 500) {
      return reply.code(err.statusCode).send({ error: err.code ?? 'bad_request', message: err.message });
    }
    req.log.error(err);
    return reply.code(500).send({ error: 'server_error', message: 'Something went wrong.' });
  });

  app.decorate('db', db);
  app.decorate('config', config);

  // JSON (feed, home, map) shrinks 5-10x with compression, which matters on mobile data.
  // Photos are already compressed WebP/JPEG, so they're sent as they are.
  await app.register(compress, {
    global: true,
    threshold: 1024,
    encodings: ['br', 'gzip'],
    brotliOptions: { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 4 } },
    customTypes: /^(application\/json|text\/(html|css|plain)|application\/javascript)/,
  });
  await app.register(cors, { origin: false });
  await app.register(jwt, { secret: config.jwtSecret, sign: { expiresIn: '30d' } });
  await app.register(multipart, { limits: { fileSize: 12 * 1024 * 1024, files: 1, fields: 20 } });
  await app.register(rateLimit, {
    max: 300,
    timeWindow: '1 minute',
    keyGenerator: (req) => {
      // Signed-in users are limited per account, guests per IP.
      const auth = req.headers.authorization;
      if (auth?.startsWith('Bearer ')) {
        try {
          const payload = app.jwt.decode<{ sub?: string }>(auth.slice(7));
          if (payload?.sub) return `u:${payload.sub}`;
        } catch {
          /* fall through to IP */
        }
      }
      return `ip:${req.ip}`;
    },
  });

  fs.mkdirSync(config.mediaDir, { recursive: true });
  await app.register(fastifyStatic, {
    root: config.mediaDir,
    prefix: '/media/',
    maxAge: '30d',
    immutable: true,
  });

  app.get('/health', { logLevel: 'warn' }, async () => {
    await db.query('select 1');
    return { ok: true };
  });

  await app.register(authRoutes);
  await app.register(browseRoutes);
  await app.register(actionRoutes);
  await app.register(postRoutes);
  await app.register(commentRoutes);
  await app.register(socialRoutes);
  await app.register(discoverRoutes);
  await app.register(tasteRoutes);
  await app.register(exploreRoutes);
  await app.register(messageRoutes);
  await app.register(storyRoutes);
  await app.register(adminRoutes);
  await app.register(pageRoutes);

  return app;
}

/** Parses input with a zod schema; on failure sends a 400 and returns undefined. */
export function parse<T extends z.ZodType>(schema: T, data: unknown, reply: FastifyReply): z.infer<T> | undefined {
  const result = schema.safeParse(data);
  if (!result.success) {
    const issue = result.error.issues[0];
    const where = issue?.path.length ? `${issue.path.join('.')}: ` : '';
    reply.code(400).send({ error: 'invalid_input', message: `${where}${issue?.message ?? 'invalid input'}` });
    return undefined;
  }
  return result.data;
}
