import type { CatalogSearchOptions, CatalogTrack, MusicCatalogProvider } from '#modules/providers/music-providers'

/**
 * iTunes Search API as SECONDARY catalog/metadata provider (keyless).
 *
 * Role: canonical title/artist/album verification, stable external track
 * identity (trackId), and fallback metadata when JioSaavn retrieval is
 * weak. NEVER a playback source (30s previews only — never returned as
 * playable). NEVER the dominant rank signal (no popularity data).
 *
 * Measured live: "perfect" → Ed Sheeran #1, "perfect acoustic" → Ed
 * Sheeran acoustic #1, "tum hi ho" → Mithoon & Arijit Singh #1.
 * No key, ~20 req/min/IP budget: bounded fanout + short metadata cache.
 */

interface ITunesTrack {
  trackId?: number
  trackName?: string
  artistName?: string
  collectionName?: string
  artworkUrl100?: string
  artworkUrl600?: string
  trackTimeMillis?: number
  primaryGenreName?: string
  trackExplicitness?: string
  releaseDate?: string
}

interface ITunesResponse {
  resultCount?: number
  results?: ITunesTrack[]
}

interface CacheEntry {
  at: number
  tracks: CatalogTrack[]
}

const ITUNES_SEARCH_URL = 'https://itunes.apple.com/search'
const ITUNES_TIMEOUT_MS = 5000
const CACHE_TTL_MS = 5 * 60 * 1000
const CACHE_CAP = 200
const STORE_FRONT = 'US'

/** Bounded in-memory metadata cache (module scope, per process). */
const cache = new Map<string, CacheEntry>()

function readCache(key: string): CatalogTrack[] | null {
  const entry = cache.get(key)
  if (!entry) return null
  if (Date.now() - entry.at > CACHE_TTL_MS) {
    cache.delete(key)
    return null
  }
  return entry.tracks
}

function writeCache(key: string, tracks: CatalogTrack[]): void {
  if (cache.size >= CACHE_CAP) {
    const oldest = cache.keys().next()
    if (!oldest.done) cache.delete(oldest.value)
  }
  cache.set(key, { at: Date.now(), tracks })
}

function mapTrack(raw: ITunesTrack): CatalogTrack | null {
  if (!raw.trackId || !raw.trackName) return null
  return {
    provider: 'itunes',
    providerId: String(raw.trackId),
    title: raw.trackName,
    artists: raw.artistName ? [raw.artistName] : [],
    album: raw.collectionName ?? '',
    artwork: raw.artworkUrl600 ?? raw.artworkUrl100 ?? '',
    durationMs: typeof raw.trackTimeMillis === 'number' ? raw.trackTimeMillis : 0,
    language: '',
    explicit: raw.trackExplicitness === 'explicit',
    year: typeof raw.releaseDate === 'string' ? raw.releaseDate.slice(0, 4) : '',
    popularityHint: null,
    providerScore: null,
    playable: null
  }
}

export class ITunesCatalogProvider implements MusicCatalogProvider {
  readonly name = 'itunes' as const
  private readonly fetchJson: (url: string, signal: AbortSignal) => Promise<ITunesResponse>

  constructor(
    fetchJson: (url: string, signal: AbortSignal) => Promise<ITunesResponse> = async (url, signal) => {
      const response = await fetch(url, { signal, headers: { 'User-Agent': 'NightLight/2.0 (catalog-enrichment)' } })
      if (!response.ok) throw new Error(`iTunes responded with HTTP ${response.status}`)
      return (await response.json()) as ITunesResponse
    }
  ) {
    this.fetchJson = fetchJson
  }

  async searchTracks(query: string, options: CatalogSearchOptions): Promise<CatalogTrack[]> {
    const trimmed = (query ?? '').trim().slice(0, 200)
    if (!trimmed) return []
    const limit = Math.min(Math.max(Math.floor(options.limit) || 10, 1), 25)
    const cacheKey = `${STORE_FRONT}::${trimmed.toLowerCase()}::${limit}`
    const cached = readCache(cacheKey)
    if (cached) return cached.slice(0, limit)

    const url =
      `${ITUNES_SEARCH_URL}?term=${encodeURIComponent(trimmed)}` + `&entity=song&limit=${limit}&country=${STORE_FRONT}`
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), ITUNES_TIMEOUT_MS)
    try {
      const data = await this.fetchJson(url, controller.signal)
      const tracks = (data.results ?? []).map(mapTrack).filter((t): t is CatalogTrack => t !== null)
      writeCache(cacheKey, tracks)
      return tracks.slice(0, limit)
    } catch {
      // Secondary provider: failure degrades to JioSaavn-only, never fails search.
      return cached ?? []
    } finally {
      clearTimeout(timer)
    }
  }
}

/** Test hook: reset module cache. */
export function clearITunesCache(): void {
  cache.clear()
}
