import { Endpoints } from '#common/constants'
import { canonicalKey, performerArtistsOf } from '#modules/providers/identity'
import { searchFetch } from '#modules/providers/search-transport'
import { createSongPayload } from '#modules/songs/helpers'
import { GetSongByIdUseCase } from '#modules/songs/use-cases'
import type {
  CatalogSearchOptions,
  CatalogTrack,
  MusicCatalogProvider,
  SongPayload
} from '#modules/providers/music-providers'
import type { SearchSongAPIResponseModel } from '#modules/search/models'
import type { z } from 'zod'

/** Minimal autocomplete entry (only fields retrieval needs). */
interface AutocompleteEntry {
  id?: string
  title?: string
  type?: string
  more_info?: {
    score?: string
    ctr?: number
  }
}

interface AutocompleteResponse {
  songs?: { data?: AutocompleteEntry[] }
  topquery?: { data?: AutocompleteEntry[] }
}

export interface JioSaavnPool {
  tracks: SongPayload[]
  /** Provider retrieval score by song id (autocomplete `score`/`ctr`). */
  scores: Map<string, number>
  /** Upstream total for pagination. */
  total: number
  start: number
  /** Which egress served the primary rung (observability). */
  via: 'direct' | 'proxy' | 'flask'
  /** Origin position per track id (diagnostics, never exposed by default). */
  ranks: Map<string, { source: 'primary' | 'enriched'; rank: number }>
}

/** Upstream page-size cap (also bounds client `limit`). */
export const MAX_JIOSAAVN_LIMIT = 50
/**
 * First-page fetch depth. Upstream ranks by popularity, so the correct
 * recording can sit far below the top 10 (a genuine low-play live version
 * buried at #12 while covers top the list). One deeper page is still a
 * single bounded call; final slicing happens after ranking.
 */
export const FIRST_PAGE_FETCH_DEPTH = 25
const AUTOCOMPLETE_HITS = 10
/** Enrichment budget: retrieval must never stall the request on a slow rung. */
const ENRICH_TIMEOUT_MS = 900
const POOL_CACHE_TTL_MS = 60_000
const POOL_CACHE_CAP = 200

interface PoolCacheEntry {
  at: number
  pool: JioSaavnPool
}

const poolCache = new Map<string, PoolCacheEntry>()

function poolCacheKey(query: string, limit: number, page: number): string {
  return `${query.trim().toLowerCase()}::${limit}::${page}`
}

function readPoolCache(key: string): JioSaavnPool | null {
  const e = poolCache.get(key)
  if (!e) return null
  if (Date.now() - e.at > POOL_CACHE_TTL_MS) {
    poolCache.delete(key)
    return null
  }
  // LRU touch
  poolCache.delete(key)
  poolCache.set(key, e)
  return e.pool
}

function writePoolCache(key: string, pool: JioSaavnPool): void {
  if (poolCache.size >= POOL_CACHE_CAP) {
    const oldest = poolCache.keys().next()
    if (!oldest.done) poolCache.delete(oldest.value)
  }
  poolCache.set(key, { at: Date.now(), pool })
}

export function clearPoolCache(): void {
  poolCache.clear()
}

function isPoolSufficient(tracks: SongPayload[], query: string): boolean {
  if (tracks.length < 8) return false
  const q = query.trim().toLowerCase()
  if (q.length <= 2) return false
  // If any track is an exact normalized title match (original), primary is sufficient
  for (const t of tracks) {
    if (t.name && t.name.trim().toLowerCase() === q) return true
  }
  // No exact hit. Row count alone is NOT evidence of quality: upstream ranks by
  // plays, so a page of 25 can be 6 real matches padded with 19 unrelated
  // mega-hits, and counting those as "sufficient" permanently skips the
  // autocomplete rung that would have supplied provider scores and the missing
  // recordings. Require actual query evidence before declaring the pool good.
  let evidence = 0
  for (const t of tracks) {
    const title = (t.name || '').toLowerCase()
    if (title.includes(q) || q.includes(title)) evidence++
    if (evidence >= 3) return true
  }
  return false
}

export function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error('enrichment timeout')), ms)
  })
  return Promise.race([promise, timeout]).finally(() => {
    if (timer) clearTimeout(timer)
  })
}

/** Provider relevance first (`score`), click-through fallback (`ctr`). */
function hitScore(entry: AutocompleteEntry): number {
  const score = Number(entry.more_info?.score)
  if (Number.isFinite(score) && score > 0) return score
  const ctr = Number(entry.more_info?.ctr)
  if (Number.isFinite(ctr) && ctr > 0) return ctr
  return 0
}

/**
 * JioSaavn catalog retrieval: classic text pool fused with autocomplete
 * resolution. Autocomplete surfaces globally relevant hits (with a provider
 * score) the text pool can miss entirely on region-skewed indexes.
 * Retrieval only — ranking stays with NightLightRanker.
 */
export class JioSaavnCatalogProvider implements MusicCatalogProvider {
  readonly name = 'jiosaavn' as const
  private readonly songById = new GetSongByIdUseCase()

  async searchPool(query: string, limit: number, page: number): Promise<JioSaavnPool> {
    const safeLimit = Math.min(Math.max(Math.floor(limit) || 10, 1), MAX_JIOSAAVN_LIMIT)
    const safePage = Math.max(Math.floor(page) || 0, 0)
    const fetchN =
      safePage === 0 ? Math.min(Math.max(safeLimit, FIRST_PAGE_FETCH_DEPTH), MAX_JIOSAAVN_LIMIT) : safeLimit

    const cacheKey = poolCacheKey(query, safeLimit, safePage)
    const cached = readPoolCache(cacheKey)
    if (cached) {
      // Return cloned reference but preserve via for observability
      return cached
    }

    const primary = await searchFetch<z.infer<typeof SearchSongAPIResponseModel>>(Endpoints.search.songs, {
      q: query,
      p: safePage,
      n: fetchN
    })
    const data = primary.data
    const via = primary.via
    const primaryTracks: SongPayload[] = data.results?.map(createSongPayload).slice(0, fetchN) || []

    let scores = new Map<string, number>()
    let enriched: SongPayload[] = []
    if (safePage === 0 && !isPoolSufficient(primaryTracks, query)) {
      try {
        const found = await withTimeout(this.fetchAutocompleteSongs(query), ENRICH_TIMEOUT_MS)
        enriched = found.songs
        scores = found.scores
      } catch {
        enriched = []
        scores = new Map()
      }
    }

    // Canonical dedupe within AND across pools: upstream hands the same
    // recording different ids (mini vs full objects). First wins.
    const seenIds = new Set<string>()
    const seenKeys = new Set<string>()
    const tracks: SongPayload[] = []
    const ranks = new Map<string, { source: 'primary' | 'enriched'; rank: number }>()
    let primaryRank = 0
    let enrichedRank = 0
    const consider = (song: SongPayload, source: 'primary' | 'enriched') => {
      const key = canonicalKey(song.name, performerArtistsOf(song))
      if (seenIds.has(song.id) || seenKeys.has(key)) return
      seenIds.add(song.id)
      seenKeys.add(key)
      tracks.push(song)
      ranks.set(song.id, { source, rank: source === 'primary' ? primaryRank++ : enrichedRank++ })
    }
    for (const song of primaryTracks) consider(song, 'primary')
    for (const song of enriched) consider(song, 'enriched')

    const pool: JioSaavnPool = { tracks, scores, total: data.total, start: data.start, via, ranks }
    writePoolCache(cacheKey, pool)
    return pool
  }

  async searchTracks(query: string, options: CatalogSearchOptions): Promise<CatalogTrack[]> {
    const pool = await this.searchPool(query, options.limit, 0)
    return pool.tracks.map((song) => ({
      provider: 'jiosaavn' as const,
      providerId: song.id,
      title: song.name,
      artists: performerArtistsOf(song),
      album: song.album?.name ?? '',
      artwork: '',
      durationMs: typeof song.duration === 'number' ? song.duration * 1000 : 0,
      language: song.language ?? '',
      explicit: song.explicitContent,
      year: song.year ?? '',
      popularityHint: null,
      providerScore: pool.scores.get(song.id) ?? null,
      playable: song
    }))
  }

  /**
   * Resolves autocomplete song hits to full playable payloads.
   * Empty on any failure — enrichment is strictly optional.
   */
  private async fetchAutocompleteSongs(query: string): Promise<{ songs: SongPayload[]; scores: Map<string, number> }> {
    const empty = { songs: [], scores: new Map<string, number>() }
    const { data, ok } = await searchFetch<AutocompleteResponse>(Endpoints.search.all, { query })
    if (!ok || !data) return empty

    const hits: { id: string; score: number }[] = []
    const seen = new Set<string>()
    const collect = (entries?: AutocompleteEntry[]) => {
      for (const entry of entries ?? []) {
        if (entry?.type !== 'song' || !entry.id || seen.has(entry.id)) continue
        seen.add(entry.id)
        hits.push({ id: entry.id, score: hitScore(entry) })
        if (hits.length >= AUTOCOMPLETE_HITS) break
      }
    }
    // topquery first: the provider's single best guess for the query.
    collect(data.topquery?.data)
    collect(data.songs?.data)
    if (hits.length === 0) return empty

    const scores = new Map(hits.map((h) => [h.id, h.score] as [string, number]))
    const songs = await this.songById.execute({ songIds: hits.map((h) => h.id).join(',') })
    return { songs, scores }
  }
}
