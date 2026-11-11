import { createHash } from 'node:crypto'
import { collection, Collections } from '../database/mongo'
import { ApiError } from '../errors/api-error'
import type { Context, Next } from 'hono'

export interface AuthUser {
  id: string
  deviceId: string
  createdAt: string
}

/** Hash tokens at rest so a database leak does not expose usable credentials. */
export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex')
}

/**
 * Validates the `Authorization: Bearer <token>` header and attaches the
 * authenticated user to the context. Rejects missing/invalid tokens.
 */
export async function requireUser(c: Context): Promise<AuthUser> {
  const header = c.req.header('authorization')
  if (!header?.startsWith('Bearer ')) {
    throw ApiError.unauthorized()
  }

  const token = header.slice('Bearer '.length).trim()
  if (!token || token.length < 16) {
    throw ApiError.unauthorized()
  }

  const users = collection(Collections.USERS)
  const user = await users.findOne({ tokenHash: hashToken(token) }, { projection: { deviceId: 1, createdAt: 1 } })

  if (!user) {
    throw ApiError.unauthorized()
  }

  return {
    id: user._id.toString(),
    deviceId: user.deviceId as string,
    createdAt: (user.createdAt as Date).toISOString()
  }
}

export async function authMiddleware(c: Context, next: Next) {
  const user = await requireUser(c)
  c.set('user', user)
  await next()
}
