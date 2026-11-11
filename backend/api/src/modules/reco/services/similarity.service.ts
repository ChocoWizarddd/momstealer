/**
 * External similarity adapters (candidate SOURCE only — NightLight ranking
 * stays authoritative).
 *
 * Verified against official docs before implementation:
 * - Last.fm track.getSimilar / artist.getSimilar: artist+track NAME in, similar
 *   tracks with `match` score out. Requires API key (LASTFM_API_KEY). No key =
 *   cleanly disabled, never an error path.
 * - ReccoBeats GET /v1/track/recommendation?seeds=&size= (+ optional audio
 *   feature targets) and GET /v1/audio-features?ids=. No key. Seeds MUST be
 *   ReccoBeats UUIDs or Spotify IDs — there is NO name search, so without an
 *   ID mapping this client is dormant (documented, not forced into runtime).
 *   429 honors Retry-After with a cooldown; 404/timeout/malformed → [].
 * - MusicBrainz /ws/2/recording: free metadata/ISRC lookup, 1 req/sec limit,
 *   NightLight User-Agent. Adapter-interface only until a runtime use needs it.
 *
 * Every method is fail-safe: external failure degrades to [], never throws
 * into playback paths. Results are TTL-cached in memory.
 */

export interface SimilarTrack {
  title: string
  artist: string
  /** Provider similarity score (Last.fm `match`, ReccoBeats order-implied). Lower = weaker. */
  match: number
  source: 'lastfm' | 'reccobeats'
}

export interface AudioFeatures {
  id: string
  acousticness: number | null
  danceability: number | null
  energy: number | null
  instrumentalness: number | null
  liveness: number | null
  loudness: number | null
  speechiness: number | null
  tempo: number | null
  valence: number | null
  key: number | null
}

type FetchImpl = (url: string, init?: RequestInit) => Promise<Response>

const DEFAULT_TIMEOUT_MS = 4000
const CACHE_TTL_MS = 60 * 60 * 1000

async function fetchJson(
  fetchImpl: FetchImpl,
  url: string,
  init: RequestInit | undefined,
  timeoutMs: number
): Promise<{ status: number; retryAfterMs: number; json: unknown }> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const res = await fetchImpl(url, { ...init, signal: ctrl.signal })
    const retryAfter = res.headers?.get?.('retry-after') ?? null
    let json: unknown = null
    try {
      json = await res.json()
    } catch {
      json = null
    }
    return { status: res.status, retryAfterMs: retryAfter ? Number(retryAfter) * 1000 : 0, json }
  } finally {
    clearTimeout(timer)
  }
}

function cacheGet<T>(cache: Map<string, { at: number; data: T }>, key: string): T | null {
  const hit = cache.get(key)
  if (!hit) return null
  if (Date.now() - hit.at > CACHE_TTL_MS) {
    cache.delete(key)
    return null
  }
  return hit.data
}

function cachePut<T>(cache: Map<string, { at: number; data: T }>, key: string, data: T): void {
  if (cache.size > 500) cache.clear()
  cache.set(key, { at: Date.now(), data })
}

export class LastFmClient {
  private cache = new Map<string, { at: number; data: SimilarTrack[] }>()
  private apiKey: string
  private fetchImpl: FetchImpl
  private timeoutMs: number

  constructor(apiKey?: string, fetchImpl?: FetchImpl, timeoutMs = DEFAULT_TIMEOUT_MS) {
    this.apiKey = apiKey ?? process.env.LASTFM_API_KEY ?? ''
    this.fetchImpl = fetchImpl ?? ((url, init) => fetch(url, init))
    this.timeoutMs = timeoutMs
  }

  /** False when no API key is configured: callers skip without error. */
  enabled(): boolean {
    return this.apiKey.trim().length > 0
  }

  async trackSimilar(artist: string, title: string, limit = 20): Promise<SimilarTrack[]> {
    if (!this.enabled() || !artist.trim() || !title.trim()) return []
    const key = `t:${artist.toLowerCase()}|${title.toLowerCase()}|${limit}`
    const hit = cacheGet(this.cache, key)
    if (hit) return hit
    const url =
      `https://ws.audioscrobbler.com/2.0/?method=track.getSimilar` +
      `&artist=${encodeURIComponent(artist)}&track=${encodeURIComponent(title)}` +
      `&api_key=${encodeURIComponent(this.apiKey)}&format=json&autocorrect=1&limit=${limit}`
    const out: SimilarTrack[] = []
    try {
      const { status, json } = await fetchJson(this.fetchImpl, url, undefined, this.timeoutMs)
      // 10 invalid key, 29 rate-limited, 11 offline, 16 temp error → degrade.
      if (status !== 200 || json == null || typeof json !== 'object') return []
      const tracks = (json as { similartracks?: { track?: unknown } }).similartracks?.track
      const list = Array.isArray(tracks) ? tracks : tracks ? [tracks] : []
      for (const raw of list) {
        if (typeof raw !== 'object' || raw === null) continue
        const rec = raw as { name?: unknown; match?: unknown; artist?: { name?: unknown } }
        const name = typeof rec.name === 'string' ? rec.name : ''
        const aname = rec.artist && typeof rec.artist.name === 'string' ? rec.artist.name : ''
        const match = Number(rec.match)
        if (!name) continue
        out.push({
          title: name,
          artist: aname,
          match: Number.isFinite(match) ? match : 0,
          source: 'lastfm'
        })
      }
    } catch {
      return []
    }
    cachePut(this.cache, key, out)
    return out
  }

  async artistSimilar(artist: string, limit = 10): Promise<{ name: string; match: number }[]> {
    if (!this.enabled() || !artist.trim()) return []
    const key = `a:${artist.toLowerCase()}|${limit}`
    const hit = cacheGet(this.cache, key) as unknown as { name: string; match: number }[] | null
    if (hit) return hit
    const url =
      `https://ws.audioscrobbler.com/2.0/?method=artist.getSimilar` +
      `&artist=${encodeURIComponent(artist)}` +
      `&api_key=${encodeURIComponent(this.apiKey)}&format=json&autocorrect=1&limit=${limit}`
    try {
      const { status, json } = await fetchJson(this.fetchImpl, url, undefined, this.timeoutMs)
      if (status !== 200 || json == null || typeof json !== 'object') return []
      const artists = (json as { similarartists?: { artist?: unknown } }).similarartists?.artist
      const list = Array.isArray(artists) ? artists : artists ? [artists] : []
      const out: { name: string; match: number }[] = []
      for (const raw of list) {
        if (typeof raw !== 'object' || raw === null) continue
        const rec = raw as { name?: unknown; match?: unknown }
        if (typeof rec.name !== 'string' || !rec.name) continue
        const match = Number(rec.match)
        out.push({ name: rec.name, match: Number.isFinite(match) ? match : 0 })
      }
      cachePut(this.cache, key, out as unknown as SimilarTrack[])
      return out
    } catch {
      return []
    }
  }
}

export class ReccoBeatsClient {
  private cache = new Map<string, { at: number; data: SimilarTrack[] }>()
  private featuresCache = new Map<string, { at: number; data: (AudioFeatures | null)[] }>()
  private cooldownUntil = 0
  private fetchImpl: FetchImpl
  private timeoutMs: number

  constructor(fetchImpl?: FetchImpl, timeoutMs = DEFAULT_TIMEOUT_MS) {
    this.fetchImpl = fetchImpl ?? ((url, init) => fetch(url, init))
    this.timeoutMs = timeoutMs
  }

  /**
   * Track recommendations from ReccoBeats/Spotify seed IDs. JioSaavn seeds
   * carry neither, so this stays dormant until an ID mapping exists — it is
   * built, cached and tested, not forced into runtime.
   */
  async recommend(seedIds: string[], size = 20): Promise<SimilarTrack[]> {
    const seeds = seedIds.map((s) => s.trim()).filter(Boolean)
    if (!seeds.length) return []
    if (Date.now() < this.cooldownUntil) return []
    const key = `r:${seeds.join(',')}|${size}`
    const hit = cacheGet(this.cache, key)
    if (hit) return hit
    const url =
      `https://api.reccobeats.com/v1/track/recommendation` +
      `?seeds=${encodeURIComponent(seeds.join(','))}&size=${size}`
    try {
      const { status, retryAfterMs, json } = await fetchJson(this.fetchImpl, url, undefined, this.timeoutMs)
      if (status === 429) {
        this.cooldownUntil = Date.now() + (retryAfterMs > 0 ? retryAfterMs : 60_000)
        return []
      }
      if (status !== 200 || json == null || typeof json !== 'object') return []
      const content = (json as { content?: unknown }).content
      const list = Array.isArray(content) ? content : []
      const out: SimilarTrack[] = []
      for (const raw of list) {
        if (typeof raw !== 'object' || raw === null) continue
        const rec = raw as {
          trackTitle?: unknown
          tracktitle?: unknown
          title?: unknown
          artists?: unknown
        }
        const title =
          (typeof rec.trackTitle === 'string' && rec.trackTitle) ||
          (typeof rec.tracktitle === 'string' && rec.tracktitle) ||
          (typeof rec.title === 'string' && rec.title) ||
          ''
        let artist = ''
        if (Array.isArray(rec.artists) && rec.artists.length) {
          const first = rec.artists[0] as { name?: unknown }
          artist = typeof first?.name === 'string' ? first.name : ''
        }
        if (!title) continue
        out.push({ title, artist, match: 0, source: 'reccobeats' })
      }
      cachePut(this.cache, key, out)
      return out
    } catch {
      return []
    }
  }

  /** Spotify-style audio features for ReccoBeats UUIDs (404/429/timeout → nulls). */
  async audioFeatures(ids: string[]): Promise<(AudioFeatures | null)[]> {
    const clean = ids.map((s) => s.trim()).filter(Boolean)
    if (!clean.length) return []
    if (Date.now() < this.cooldownUntil) return clean.map(() => null)
    const key = `f:${clean.join(',')}`
    const hit = cacheGet(this.featuresCache, key)
    if (hit) return hit
    const url = `https://api.reccobeats.com/v1/audio-features?ids=${encodeURIComponent(clean.join(','))}`
    const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)
    try {
      const { status, retryAfterMs, json } = await fetchJson(this.fetchImpl, url, undefined, this.timeoutMs)
      if (status === 429) {
        this.cooldownUntil = Date.now() + (retryAfterMs > 0 ? retryAfterMs : 60_000)
        return clean.map(() => null)
      }
      if (status !== 200 || json == null || typeof json !== 'object') {
        return clean.map(() => null)
      }
      const content = (json as { content?: unknown }).content
      const list = Array.isArray(content) ? content : []
      const out = list.map((raw): AudioFeatures | null => {
        if (typeof raw !== 'object' || raw === null) return null
        const r = raw as Record<string, unknown>
        return {
          id: typeof r.id === 'string' ? r.id : '',
          acousticness: num(r.acousticness),
          danceability: num(r.danceability),
          energy: num(r.energy),
          instrumentalness: num(r.instrumentalness),
          liveness: num(r.liveness),
          loudness: num(r.loudness),
          speechiness: num(r.speechiness),
          tempo: num(r.tempo),
          valence: num(r.valence),
          key: num(r.key)
        }
      })
      cachePut(this.featuresCache, key, out)
      return out
    } catch {
      return clean.map(() => null)
    }
  }
}

export class MusicBrainzClient {
  private cache = new Map<string, { at: number; data: { isrcs: string[]; tags: string[] } }>()
  private lastCallAt = 0
  private fetchImpl: FetchImpl
  private timeoutMs: number

  constructor(fetchImpl?: FetchImpl, timeoutMs = DEFAULT_TIMEOUT_MS) {
    this.fetchImpl = fetchImpl ?? ((url, init) => fetch(url, init))
    this.timeoutMs = timeoutMs
  }

  /**
   * Recording ISRCs + tags for title+artist. Adapter-interface only for now:
   * 1 req/sec self-throttle, cached, best-effort. No runtime caller yet —
   * wired when an enrichment path needs it, not before.
   */
  async recordingIdentity(title: string, artist: string): Promise<{ isrcs: string[]; tags: string[] }> {
    const empty = { isrcs: [], tags: [] }
    if (!title.trim() || !artist.trim()) return empty
    const key = `m:${artist.toLowerCase()}|${title.toLowerCase()}`
    const hit = cacheGet(this.cache, key)
    if (hit) return hit
    const wait = 1100 - (Date.now() - this.lastCallAt)
    if (wait > 0) await new Promise((r) => setTimeout(r, wait))
    this.lastCallAt = Date.now()
    const query = `recording:"${title}" AND artist:"${artist}"`
    const url = `https://musicbrainz.org/ws/2/recording/?query=${encodeURIComponent(query)}&fmt=json&limit=3`
    try {
      const { status, json } = await fetchJson(
        this.fetchImpl,
        url,
        { headers: { 'User-Agent': 'NightLight/1.0 (music-player)' } },
        this.timeoutMs
      )
      if (status !== 200 || json == null || typeof json !== 'object') return empty
      const recordings = (json as { recordings?: unknown }).recordings
      const list = Array.isArray(recordings) ? recordings : []
      const isrcs: string[] = []
      const tags: string[] = []
      for (const raw of list) {
        if (typeof raw !== 'object' || raw === null) continue
        const rec = raw as { isrcs?: unknown; tags?: unknown }
        if (Array.isArray(rec.isrcs)) {
          for (const v of rec.isrcs) if (typeof v === 'string' && v) isrcs.push(v)
        }
        if (Array.isArray(rec.tags)) {
          for (const t of rec.tags) {
            const tag = (t as { name?: unknown }).name
            if (typeof tag === 'string' && tag) tags.push(tag.toLowerCase())
          }
        }
      }
      const out = { isrcs: [...new Set(isrcs)], tags: [...new Set(tags)] }
      cachePut(this.cache, key, out)
      return out
    } catch {
      return empty
    }
  }
}
