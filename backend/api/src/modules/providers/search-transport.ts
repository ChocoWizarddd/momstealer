import process from 'node:process'

import { useFetch } from '#common/helpers'
import { flaskProvider } from '#modules/providers/jiosaavn-flask-provider'
import type { Endpoints } from '#common/constants'

type SearchEndpoint = typeof Endpoints.search.songs | typeof Endpoints.search.all

export interface SearchFetchResult<T> {
  data: T
  ok: boolean
  via: 'direct' | 'proxy' | 'flask'
}

type FetchImpl = typeof fetch

let loggedConfig = false
let lastProxyWarning = ''

/**
 * Warn once per distinct failure shape per minute. A misconfigured proxy fails
 * on every single search, so an unthrottled warn would flood the deploy logs
 * and bury real errors; silence would hide a total outage. Throttle, never mute.
 */
function noteProxyRejection(detail: string): void {
  const now = Date.now()
  if (detail === lastProxyWarning && now - lastProxyWarnAt < PROXY_WARN_WINDOW_MS) return
  lastProxyWarning = detail
  lastProxyWarnAt = now
  console.warn(
    `[search] mumbai proxy REJECTED (${detail}); falling through to direct egress. ` +
      'Check SEARCH_PROXY_URL points at the forwarder endpoint (…/api/forward) and that ' +
      'FORWARD_SECRET on the proxy matches SEARCH_PROXY_SECRET.'
  )
}

/**
 * Proxy budget. The forwarder itself allows the upstream 8s
 * (`backend/search-proxy/api/forward.ts`), but measured end-to-end a warm
 * forwarder answers in ~1.2s while a cold/slow one can idle past 10s. Waiting
 * the full upstream budget before falling back cost an 11s search; falling back
 * eagerly threw away Mumbai-ranked results. So the proxy gets a short window to
 * win on its own, and the remaining tiers start in parallel behind it —
 * first *successful* answer wins, whichever egress produced it.
 */
const PROXY_HEDGE_DELAY_MS = 1_500
/** Hard cap on the proxy attempt, after which the fallback is forced. */
const PROXY_TIMEOUT_MS = 5_000
const PROXY_WARN_WINDOW_MS = 60_000
let lastProxyWarnAt = 0

const TRANSPORT_CACHE_TTL_MS = 30_000
const TRANSPORT_CACHE_CAP = 200
const inflight = new Map<string, Promise<SearchFetchResult<any>>>()
const transportCache = new Map<string, { at: number; result: SearchFetchResult<any> }>()

function transportKey(endpoint: string, params: Record<string, string | number>): string {
  const sorted = Object.keys(params)
    .sort()
    .map((k) => `${k}=${String(params[k]).toLowerCase().trim()}`)
    .join('&')
  return `${endpoint}::${sorted}`
}

function readTransportCache<T>(key: string): SearchFetchResult<T> | null {
  const e = transportCache.get(key)
  if (!e) return null
  if (Date.now() - e.at > TRANSPORT_CACHE_TTL_MS) {
    transportCache.delete(key)
    return null
  }
  return e.result as SearchFetchResult<T>
}

/**
 * Proxy origin (never the path/query) for logs. The secret lives in a header,
 * but a stray secret pasted into the URL would still leak, so strip the query
 * and fragment too.
 */
function safeProxyTarget(raw: string): string {
  try {
    const u = new URL(raw)
    return `${u.origin}${u.pathname}`
  } catch {
    return '<invalid url>'
  }
}

function writeTransportCache<T>(key: string, result: SearchFetchResult<T>): void {
  if (!result.ok) return
  if (transportCache.size >= TRANSPORT_CACHE_CAP) {
    const oldest = transportCache.keys().next()
    if (!oldest.done) transportCache.delete(oldest.value)
  }
  transportCache.set(key, { at: Date.now(), result })
}

export function clearTransportCache(): void {
  transportCache.clear()
  inflight.clear()
}

/** True when the Mumbai proxy is configured (URL + secret present, values never exposed). */
export function isSearchProxyConfigured(): boolean {
  return Boolean(process.env.SEARCH_PROXY_URL && process.env.SEARCH_PROXY_SECRET)
}

/** True when the Flask JioSaavn provider is configured. */
export function isFlaskProviderConfigured(): boolean {
  return flaskProvider.enabled()
}

/**
 * Search-rung transport with multiple egress options.
 *
 * Priority order:
 * 1. Mumbai proxy (SEARCH_PROXY_URL + SECRET) - best for ranking accuracy
 * 2. Flask JioSaavn Provider (JIOSAAVN_PROVIDER_URL) - hosted wrapper, good fallback
 * 3. Direct JioSaavn calls - always available as last resort
 *
 * A failure at any tier falls through to the next; a down provider must never break search.
 */
export async function searchFetch<T>(
  endpoint: SearchEndpoint,
  params: Record<string, string | number>,
  fetchImpl: FetchImpl = fetch
): Promise<SearchFetchResult<T>> {
  const proxyUrl = process.env.SEARCH_PROXY_URL
  const proxySecret = process.env.SEARCH_PROXY_SECRET
  const useFlask = flaskProvider.enabled()

  if (!loggedConfig) {
    loggedConfig = true
    const target = proxyUrl ? `, proxy target: ${safeProxyTarget(proxyUrl)}` : ''
    console.info(
      `[search] mumbai proxy configured: ${Boolean(proxyUrl && proxySecret)}, flask provider configured: ${useFlask}${target}`
    )
  }

  const key = transportKey(endpoint.toString(), params)
  const cached = readTransportCache<T>(key)
  if (cached) return cached

  const existing = inflight.get(key) as Promise<SearchFetchResult<T>> | undefined
  if (existing) return existing

  const promise = (async () => {
    // Tiers 2+3: Flask provider when enabled, else (or on failure) direct
    // JioSaavn. Always terminates, so it can safely act as the fallback winner.
    const runFallback = async (): Promise<SearchFetchResult<T>> => {
      if (useFlask) {
        try {
          // Map JioSaavn API calls to Flask endpoints
          const flaskResult = await callFlaskProvider(endpoint, params)
          if (flaskResult) {
            const res: SearchFetchResult<T> = { data: flaskResult as T, ok: true, via: 'flask' }
            writeTransportCache(key, res)
            return res
          }
        } catch (error) {
          console.warn(
            `[search] flask provider failed (${error instanceof Error ? error.message : String(error)}); going direct`
          )
        }
      }
      const direct = await useFetch<T>({ endpoint, params })
      const res: SearchFetchResult<T> = { ...(direct as SearchFetchResult<T>), via: 'direct' }
      writeTransportCache(key, res)
      return res
    }

    if (!proxyUrl || !proxySecret) return runFallback()

    // Hedged race: the proxy gets PROXY_HEDGE_DELAY_MS to win alone, then the
    // fallback runs alongside it. First success claims the result; the loser is
    // ignored (its result is still cached, so a repeat query is still warm).
    let settled = false
    let fallbackStarted = false
    let resolveWinner!: (result: SearchFetchResult<T>) => void
    const winner = new Promise<SearchFetchResult<T>>((resolve) => {
      resolveWinner = resolve
    })
    const claim = (result: SearchFetchResult<T>): boolean => {
      if (settled) return false
      settled = true
      resolveWinner(result)
      return true
    }
    const startFallback = (): void => {
      if (settled || fallbackStarted) return
      fallbackStarted = true
      runFallback()
        .then((result) => {
          claim(result)
        })
        .catch(() => {})
    }

    const timers: Array<ReturnType<typeof setTimeout>> = [
      setTimeout(startFallback, PROXY_HEDGE_DELAY_MS),
      // Safety net: if the proxy attempt somehow never settles, force the
      // fallback so search can never hang.
      setTimeout(startFallback, PROXY_TIMEOUT_MS),
    ]
    timers.forEach((t) => t.unref?.())

    const proxyAttempt = (async () => {
      try {
        const response = await fetchImpl(proxyUrl, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'x-search-proxy-secret': proxySecret
          },
          body: JSON.stringify({ call: endpoint.toString(), params }),
          signal: AbortSignal.timeout(PROXY_TIMEOUT_MS)
        })
        const body = (await response.json()) as { success?: boolean; data?: T }
        if (response.ok && body?.success) {
          claim({ data: body.data as T, ok: true, via: 'proxy' })
          return
        }
        // A silent fallthrough here is how a wrong SEARCH_PROXY_URL (e.g. the
        // provider app root instead of the /api/forward endpoint) shipped as
        // "search quietly runs from a US IP". Say so, loudly but rate-limited.
        noteProxyRejection(`${endpoint} -> HTTP ${response.status} success=${String(body?.success)}`)
      } catch (error) {
        noteProxyRejection(
          `${endpoint} -> ${error instanceof Error ? `${error.name}: ${error.message}` : String(error)}`
        )
      }
      // Proxy failed or was out-raced: do not wait for the hedge timer.
      startFallback()
    })()
    // The proxy attempt is fire-and-forget: it only ever calls claim() or
    // startFallback(), and both are safe after the race is decided.
    proxyAttempt.catch(() => startFallback())

    const result = await winner
    timers.forEach((t) => clearTimeout(t))
    if (result.via !== 'proxy') {
      console.info(`[search] ${endpoint} served by ${result.via} egress (proxy lost the race)`)
    }
    return result
  })()

  inflight.set(key, promise)
  try {
    return await promise
  } finally {
    inflight.delete(key)
  }
}

function callFlaskProvider(
  endpoint: SearchEndpoint,
  params: Record<string, string | number>
): Promise<any> | null {
  const call = endpoint.toString()
  const query = String(params.q ?? params.query ?? '')

  if (call === 'search.getResults') {
    // Primary song search
    return flaskProvider.searchSongs(query, false, true)
  }
  if (call === 'autocomplete.get') {
    // Autocomplete for enrichment
    return flaskProvider.searchSongs(query, false, false)
  }
  return null
}
