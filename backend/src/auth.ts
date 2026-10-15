import crypto from 'node:crypto';
import { promisify } from 'node:util';
import type { FastifyReply, FastifyRequest } from 'fastify';

const scrypt = promisify(crypto.scrypt) as (pw: string, salt: Buffer, len: number) => Promise<Buffer>;

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(password, salt, 64);
  return `scrypt$${salt.toString('base64')}$${key.toString('base64')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [scheme, saltB64, keyB64] = stored.split('$');
  if (scheme !== 'scrypt' || !saltB64 || !keyB64) return false;
  const expected = Buffer.from(keyB64, 'base64');
  const actual = await scrypt(password, Buffer.from(saltB64, 'base64'), expected.length);
  return crypto.timingSafeEqual(actual, expected);
}

export interface TokenPayload {
  sub: string;
  role: string;
}

declare module '@fastify/jwt' {
  interface FastifyJWT {
    payload: TokenPayload;
    user: TokenPayload;
  }
}

/** Sets request.user when a valid bearer token is present; guests pass through. */
export async function optionalAuth(req: FastifyRequest): Promise<void> {
  if (!req.headers.authorization) return;
  try {
    await req.jwtVerify();
  } catch {
    // An invalid or expired token is treated as a guest; protected routes reject below.
    (req as { user?: unknown }).user = undefined;
  }
}

export async function requireAuth(req: FastifyRequest, reply: FastifyReply): Promise<void> {
  try {
    await req.jwtVerify();
  } catch {
    return reply.code(401).send({ error: 'sign_in_required', message: 'Please sign in to do this.' });
  }
}

export function userId(req: FastifyRequest): string | null {
  return (req.user as TokenPayload | undefined)?.sub ?? null;
}
