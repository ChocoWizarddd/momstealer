import { canonical } from '#modules/importer/services/import.service'
import { SearchService } from '#modules/search/services'
import { SmartShuffleService } from '#modules/shuffle/services'
import { LastFmClient, ReccoBeatsClient, type SimilarTrack } from './similarity.service'
import type { ShuffleInput } from '#modules/shuffle/models'
import type { SongModel } from '#modules/songs/models'
import type { z } from 'zod'

export interface RecoEvidence {
  /** Stable provider id when the candidate resolved to a playable track. */
  id: string | null
  title: string
  artist: string
  sources: string[]
  /** 0..1 combined evidence strength (lastfm match normalized, radio order). */
  score: number
  reason: string
}

export interface SmartQueueArgs {
  seedId?: string
  seedName?: string
  artists?: string
  album?: string
  /** Recently played track ids, most-recent-first (penalized). */
  recent?: string[]
  count?: number
}

type SongLike = {
  id?: unknown
  name?: unknown
  artists?: unknown
  album?: unknown
  language?: unknown
  more_info?: { origin?: unknown }
}

/** Normalized SongModel row produced by the search use cases. */
type TrackPayload = z.infer<typeof SongModel>

/** Public batch shape: normalized, playable SongModel rows with evidence. */
type RecoBatch = { tracks: TrackPayload[]; evidence: RecoEvidence[] }

/**
 * Server-side smart-queue assembly (§23): JioSaavn radio (primary, trusted)
 * + Last.fm listening-similarity re-resolved to playable JioSaavn tracks
 * (supplement, env-keyed) + cached similarity. NEVER throws: every external
 * source degrades independently, worst case is radio-only.
 *
 * Android stays authoritative for final ranking; this endpoint
 * returns candidates with evidence, ordered by the genre-aware
 * smart shuffle (weighted random selection: genre continuity +
 * artist/title diversity + recently-played penalties), never a
 * plain provider order.
 */
export class RecoService {
  private search: Pick<SearchService, 'radioSongs' | 'searchSongs'>
  private lastfm: Pick<LastFmClient, 'enabled' | 'trackSimilar'>
  private reccobeats: Pick<ReccoBeatsClient, 'recommend'>
  private shuffle: SmartShuffleService
  private cache = new Map<string, { at: number; data: RecoBatch }>()

  constructor(
    search?: Pick<SearchService, 'radioSongs' | 'searchSongs'>,
    lastfm?: Pick<LastFmClient, 'enabled' | 'trackSimilar'>,
    reccobeats?: Pick<ReccoBeatsClient, 'recommend'>,
    shuffle?: SmartShuffleService
  ) {
    this.search = search ?? new SearchService()
    this.lastfm = lastfm ?? new LastFmClient()
    this.reccobeats = reccobeats ?? new ReccoBeatsClient()
    this.shuffle = shuffle ?? new SmartShuffleService()
  }

  async smartqueue(args: SmartQueueArgs): Promise<RecoBatch> {
    const wanted = Math.min(Math.max(args.count ?? 20, 1), 50)
    const recentIds = (args.recent ?? [])
      .map((id) => id.trim())
      .filter(Boolean)
      .slice(0, 20)
    const cacheKey = `${args.seedId ?? ''}|${args.seedName ?? ''}|${args.artists ?? ''}|${wanted}${
      recentIds.length ? `|${recentIds.join(',')}` : ''
    }`
    const cached = this.cache.get(cacheKey)
    if (cached && Date.now() - cached.at < 5 * 60 * 1000) return cached.data

    const tracks: SongLike[] = []
    const evidence: RecoEvidence[] = []
    const seenIds = new Set<string>()
    const seenTitles = new Set<string>()
    const seedCanon = canonical(args.seedName ?? '')

    const consider = (song: SongLike, sources: string[], score: number, reason: string): void => {
      if (!song || typeof song.id !== 'string') return
      if (args.seedId && song.id === args.seedId) return
      if (seenIds.has(song.id)) {
        const prior = evidence.find((e) => e.id === song.id)
        if (prior) {
          for (const s of sources) if (!prior.sources.includes(s)) prior.sources.push(s)
        }
        return
      }
      const title = canonical(typeof song.name === 'string' ? song.name : '')
      if (!title) return
      if (seedCanon && title === seedCanon) return // never the seed under another id
      if (seenTitles.has(title)) return // same title = same song feel
      seenIds.add(song.id)
      seenTitles.add(title)
      tracks.push(song)
      evidence.push({ id: song.id, title: String(song.name ?? ''), artist: artistNames(song), sources, score, reason })
    }

    // 1. Radio first (trusted, primary). Never throws the batch.
    try {
      const radio = await this.search.radioSongs({
        seedId: args.seedId,
        seedName: args.seedName,
        artists: args.artists,
        album: args.album,
        limit: Math.min(wanted + 10, 50)
      })
      const list = Array.isArray(radio) ? radio : []
      for (const song of list) {
        if (tracks.length >= wanted) break
        consider(song as SongLike, ['radio'], 0.8, 'provider radio related to seed')
      }
    } catch {
      // radio failure → supplement-only batch (or empty); never throws.
    }

    // 2. Last.fm listening-similarity, re-resolved to playable JioSaavn rows.
    if (tracks.length < wanted) {
      try {
        const primaryArtist =
          (args.artists ?? '')
            .split(',')
            .map((s) => s.trim())
            .find(Boolean) ?? ''
        const similar: SimilarTrack[] =
          this.lastfm.enabled() && primaryArtist && args.seedName
            ? await this.lastfm.trackSimilar(primaryArtist, args.seedName, 15)
            : []
        for (const sim of similar) {
          if (tracks.length >= wanted) break
          if (!sim.title) continue
          const resolved = await this.resolvePlayable(sim.title, sim.artist)
          if (!resolved) continue
          consider(
            resolved,
            ['lastfm'],
            Math.max(0, Math.min(1, sim.match / 10)),
            `listening-similar (match ${sim.match})`
          )
        }
      } catch {
        // supplement failure → radio-only batch stands.
      }
    }

    // Genre-aware smart shuffle: weighted random ordering
    // (genre continuity + artist/title diversity +
    // recently-played penalty) instead of raw provider
    // order. Pure in-memory; on any failure the provider
    // order stands — ordering is best-effort, never a
    // hard dependency of the response.
    let ordered: SongLike[] = tracks
    let orderedEvidence = evidence
    try {
      const seed: ShuffleInput | null =
        args.seedId || args.seedName
          ? {
              id: args.seedId ?? '',
              title: args.seedName ?? '',
              artists: args.artists ?? '',
              genres: this.shuffle.genres.fallbackGenres({})
            }
          : null
      ordered = this.shuffle.shuffle(
        tracks,
        (song): ShuffleInput => ({
          id: String(song.id ?? ''),
          title: String(song.name ?? ''),
          artists: artistNames(song),
          genres: this.shuffle.genres.fallbackGenres({
            language: song.language,
            origin: song.more_info?.origin
          })
        }),
        { previous: seed, recentIds }
      )
      const evidenceById = new Map<string, RecoEvidence>()
      for (const e of evidence) {
        if (e.id) evidenceById.set(e.id, e)
      }
      orderedEvidence = ordered
        .map((song) => evidenceById.get(String(song.id ?? '')))
        .filter((e): e is RecoEvidence => e != null)
    } catch {
      // Shuffle failure → provider order; never fails the batch.
    }

    const data: RecoBatch = { tracks: ordered as TrackPayload[], evidence: orderedEvidence }
    if (this.cache.size > 200) this.cache.clear()
    this.cache.set(cacheKey, { at: Date.now(), data })
    return data
  }

  /**
   * ReccoBeats by ID (dormant until an ID mapping exists): resolves each
   * recommendation back to a playable JioSaavn row. Tested, cached, unused
   * at runtime — kept out of the hot path deliberately.
   */
  async reccobeatsByIds(seedIds: string[], count = 20): Promise<RecoBatch> {
    const tracks: SongLike[] = []
    const evidence: RecoEvidence[] = []
    try {
      const recs = await this.reccobeats.recommend(seedIds, count)
      for (const rec of recs) {
        if (tracks.length >= count) break
        const resolved = await this.resolvePlayable(rec.title, rec.artist)
        if (!resolved) continue
        const song = resolved as SongLike
        if (typeof song.id !== 'string' || tracks.some((t) => t.id === song.id)) continue
        tracks.push(song)
        evidence.push({
          id: song.id,
          title: String(song.name ?? ''),
          artist: artistNames(song),
          sources: ['reccobeats'],
          score: 0.5,
          reason: 'audio-feature similar'
        })
      }
    } catch {
      // dormant path failure → empty, never throws.
    }
    return { tracks: tracks as TrackPayload[], evidence }
  }

  /** Best-effort playable resolution: top search hit with a sane match. */
  private async resolvePlayable(title: string, artist: string): Promise<SongLike | null> {
    try {
      const query = artist ? `${title} ${artist}` : title
      const res = (await this.search.searchSongs({ query, page: 0, limit: 5 })) as {
        results?: SongLike[]
      }
      const results = Array.isArray(res?.results) ? res.results : []
      const wantTitle = canonical(title)
      const wantArtist = canonical(artist)
      let best: SongLike | null = null
      let bestScore = -1
      for (const song of results) {
        const gotTitle = canonical(typeof song.name === 'string' ? song.name : '')
        const gotArtist = canonical(artistNames(song))
        let score = 0
        if (gotTitle && gotTitle === wantTitle) score += 5
        else if (gotTitle && wantTitle && (gotTitle.includes(wantTitle) || wantTitle.includes(gotTitle))) score += 3
        if (
          wantArtist &&
          gotArtist &&
          (gotArtist === wantArtist || gotArtist.includes(wantArtist) || wantArtist.includes(gotArtist))
        )
          score += 2
        if (score > bestScore) {
          bestScore = score
          best = song
        }
      }
      return bestScore >= 5 ? best : null
    } catch {
      return null
    }
  }
}

function artistNames(song: SongLike): string {
  const artists = song.artists as { primary?: { name?: unknown }[]; all?: { name?: unknown }[] } | undefined
  const prim = artists?.primary
  if (Array.isArray(prim) && prim.length) {
    return prim
      .map((a) => a?.name)
      .filter(Boolean)
      .join(', ')
  }
  const all = artists?.all
  if (Array.isArray(all) && all.length) {
    return all
      .map((a) => (a as { name?: unknown })?.name)
      .filter(Boolean)
      .join(', ')
  }
  return ''
}
