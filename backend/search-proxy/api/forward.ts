import type { VercelRequest, VercelResponse } from '@vercel/node'

/**
 * NightLight search forwarder — Vercel serverless, pinned to bom1 (Mumbai).
 *
 * WHY: JioSaavn search ranking follows server-IP geography. From US egress,
 * global hits (Ed Sheeran's "Perfect") vanish from results entirely; from
 * Mumbai egress they rank #1. NightLight's backend runs in the US, so the
 * two ranking-sensitive rungs (search.getResults, autocomplete.get) route
 * here. Everything else (details, radio, trending, lyrics) stays direct.
 *
 * SECURITY: not an open proxy.
 *   - Only POST, only allowlisted __call values (see ALLOWED_CALLS).
 *   - Only allowlisted params (see ALLOWED_PARAMS) with length caps.
 *   - Requires x-search-proxy-secret == FORWARD_SECRET env.
 *   - Crude per-instance rate limit (120/min/IP); Vercel-level abuse
 *     protection applies on top.
 * No request bodies, queries, or URLs are logged.
 */

export const ALLOWED_CALLS = new Set(['search.getResults', 'autocomplete.get'])

/** Param allowlist per call (API shape, not values). */
export const ALLOWED_PARAMS: Record<string, Set<string>> = {
  'search.getResults': new Set(['q', 'p', 'n']),
  'autocomplete.get': new Set(['query'])
}

const MAX_PARAM_LENGTH = 200
const UPSTREAM_TIMEOUT_MS = 8000
const RATE_LIMIT = 120
const RATE_WINDOW_MS = 60000

const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15',
  'Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0'
]

/** Minimal req/res shape (Vercel Node runtime + node:test compatible). */
export interface ForwardRequest {
  method?: string
  headers: Record<string, string | string[] | undefined>
  body?: unknown
}

export interface ForwardResponse {
  status: (code: number) => ForwardResponse
  json: (payload: unknown) => void
  setHeader: (name: string, value: string) => void
}

export function headerValue(headers: Record<string, string | string[] | undefined>, name: string): string {
  const wanted = name.toLowerCase()
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === wanted) {
      if (Array.isArray(value)) return value[0] ?? ''
      return value ?? ''
    }
  }
  return ''
}

/** Builds the upstream URL or returns an error string. Pure — unit tested. */
export function buildUpstreamUrl(
  call: unknown,
  params: unknown
): { url?: string; error?: string } {
  if (typeof call !== 'string' || !ALLOWED_CALLS.has(call)) {
    return { error: 'call not allowed' }
  }
  if (typeof params !== 'object' || params === null || Array.isArray(params)) {
    return { error: 'params must be an object' }
  }
  const allowed = ALLOWED_PARAMS[call]
  const url = new URL('https://www.jiosaavn.com/api.php')
  url.searchParams.append('__call', call)
  url.searchParams.append('_format', 'json')
  url.searchParams.append('_marker', '0')
  url.searchParams.append('api_version', '4')
  url.searchParams.append('ctx', 'web6dot0')
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (!allowed.has(key)) return { error: `param not allowed: ${key}` }
    const text = String(value ?? '')
    if (text.length > MAX_PARAM_LENGTH) return { error: `param too long: ${key}` }
    url.searchParams.append(key, text)
  }
  return { url: url.toString() }
}

// In-memory bucket per isolate (Vercel may run many; this is a backstop,
// the shared secret is the real gate).
const buckets = new Map<string, number[]>()

export function isRateLimited(ip: string, now: number): boolean {
  const hits = (buckets.get(ip) ?? []).filter((t) => now - t < RATE_WINDOW_MS)
  hits.push(now)
  buckets.set(ip, hits)
  return hits.length > RATE_LIMIT
}

export function clearRateLimitBuckets(): void {
  buckets.clear()
}

/** Build marker: proves exactly which code serves production (see GET evidence). */
export const FORWARDER_BUILD = '2026-09-09.4'

export default async function handler(req: VercelRequest, res: VercelResponse): Promise<void> {
  try {
    await handleRequest(req, res)
  } catch {
    // The runtime must never see a throw: FUNCTION_INVOCATION_FAILED otherwise.
    try {
      res.status(500).json({ success: false, message: 'internal error', build: FORWARDER_BUILD })
    } catch {
      // Response already committed; nothing left to do.
    }
  }
}

async function handleRequest(req: VercelRequest, res: VercelResponse): Promise<void> {
  if ((req.method ?? 'GET').toUpperCase() !== 'POST') {
    res.status(405).json({ success: false, message: 'POST only', build: FORWARDER_BUILD, runtime: process.version })
    return
  }
  const secret = process.env.FORWARD_SECRET ?? ''
  if (!secret || headerValue(req.headers, 'x-search-proxy-secret') !== secret) {
    res.status(403).json({ success: false, message: 'forbidden' })
    return
  }
  const forwardedFor = headerValue(req.headers, 'x-forwarded-for')
  const ip = forwardedFor.split(',')[0].trim() || 'unknown'
  if (isRateLimited(ip, Date.now())) {
    res.status(429).json({ success: false, message: 'too many requests' })
    return
  }
  const body = (req.body ?? {}) as { call?: unknown; params?: unknown }
  const built = buildUpstreamUrl(body.call, body.params)
  if (!built.url) {
    res.status(400).json({ success: false, message: built.error ?? 'bad request' })
    return
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS)
  try {
    const userAgent = USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)]
    const upstream = await fetch(built.url, {
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json', 'User-Agent': userAgent }
    })
    const data = await upstream.json()
    res.setHeader('Cache-Control', 'public, s-maxage=60, stale-while-revalidate=120')
    res.status(upstream.ok ? 200 : 502).json({ success: upstream.ok, data })
  } catch {
    res.status(504).json({ success: false, message: 'upstream timeout' })
  } finally {
    clearTimeout(timer)
  }
}
