import type { Context, Next } from 'hono'

interface Bucket {
  hits: number[]
}

const buckets = new Map<string, Bucket>()

// Periodic cleanup of expired buckets so memory does not grow unboundedly.
const CLEANUP_INTERVAL_MS = 60_000
const cleanup = setInterval(() => {
  const now = Date.now()
  for (const [key, bucket] of buckets) {
    bucket.hits = bucket.hits.filter((t) => now - t < 60_000)
    if (bucket.hits.length === 0) buckets.delete(key)
  }
}, CLEANUP_INTERVAL_MS)
cleanup.unref?.()

export interface RateLimitOptions {
  /** Number of requests allowed per window. */
  limit: number
  /** Window length in milliseconds. */
  windowMs?: number
  /** Optional per-user key (e.g. userId) that is more stable than IP. */
  key?: (c: Context) => string | undefined
}

/**
 * Sliding-window rate limiter. In-memory and per-instance: suitable for a
 * single-instance deployment. For multi-instance deployments, swap this for a
 * shared store (e.g. MongoDB TTL counters).
 */
export function rateLimit({ limit, windowMs = 60_000, key }: RateLimitOptions) {
  return async (c: Context, next: Next) => {
    const ip = c.req.header('x-forwarded-for')?.split(',')[0]?.trim() || c.env?.ip || 'unknown'
    const userKey = key?.(c)
    const bucketKey = `${userKey ? `u:${userKey}` : `ip:${ip}`}`

    const now = Date.now()
    let bucket = buckets.get(bucketKey)
    if (!bucket) {
      bucket = { hits: [] }
      buckets.set(bucketKey, bucket)
    }
    bucket.hits = bucket.hits.filter((t) => now - t < windowMs)
    bucket.hits.push(now)

    if (bucket.hits.length > limit) {
      const retryAfter = Math.ceil((bucket.hits[0] + windowMs - now) / 1000)
      return c.json(
        {
          success: false,
          message: 'Too many requests, please slow down.',
          code: 'RATE_LIMITED'
        },
        429,
        { 'Retry-After': String(Math.max(1, retryAfter)) }
      )
    }

    await next()
  }
}
