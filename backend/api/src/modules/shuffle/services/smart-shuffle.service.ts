import { DEFAULT_SHUFFLE_WEIGHTS, type ShuffleInput, type ShuffleOptions, type ShuffleWeights } from '../models'
import { GenreService } from './genre.service'
import { TitleGuardService } from './title-guard.service'

/** Precomputed, immutable per-track metadata. */
interface TrackMeta {
  id: string
  artistKey: string
  titleKey: string
  genres: string[]
  popularity: number | null
}

/** Everything one candidate's score depends on at one pick. */
interface PickContext {
  current: TrackMeta | null
  artistHistory: string[]
  titleHistory: string[]
  recentRank: Map<string, number>
  weights: ShuffleWeights
  rng: () => number
  artistWindow: number
  titleWindow: number
  recentWindow: number
  /** 0..1 — share of OTHER remaining candidates in the
   *  candidate's canonical title family. */
  titleFamilyShare: number
  /** Same for the canonical artist family. */
  artistFamilyShare: number
}

/**
 * Genre-Aware Smart Shuffle.
 *
 * Replaces plain random shuffle with weighted random
 * selection: for every next-track slot, each remaining
 * candidate is scored
 *
 *   score = genreSimilarity * 0.45
 *         + randomness        * 0.25
 *         + artistDiversity   * 0.20
 *         + popularityBalance * 0.10
 *         + familyUrgency     (crowding bonus)
 *         - sameArtist / sameTitle / recentlyPlayed
 *           penalties (window penalties shrink as the
 *           family crowds the remaining pool)
 *
 * and one candidate is drawn proportionally to its score
 * (roulette wheel). A draw that would repeat the previous
 * track's title family or artist is redrawn from the
 * non-repeating candidates whenever any exist. The queue
 * is therefore probabilistic, never a deterministic sort,
 * and never a plain Fisher–Yates (which survives only as
 * the low-level utility that trims oversized pools).
 *
 * Identical track ids are hard-excluded (a generated queue
 * never repeats an id); identical titles and repeated
 * artists are strongly penalized, not banned; missing
 * genre metadata is neutral, so incomplete data degrades
 * gracefully toward random. Small and single-artist
 * playlists always terminate because every score is
 * floored at 0.01.
 *
 * Pure in-memory, no network, no ML. Metadata is
 * normalized once per call; selection is O(k·n) with
 * k = queue length (capped) and n = pool size.
 */
export class SmartShuffleService {
  readonly genres = new GenreService()
  readonly titles = new TitleGuardService()

  /**
   * Generates a genre-aware shuffled queue.
   *
   * @param tracks   candidate pool (any type)
   * @param describe projection from T to ShuffleInput
   * @param options  continuity context, history, tuning
   * @returns the original T objects in the generated order
   */
  shuffle<T>(tracks: T[], describe: (track: T) => ShuffleInput, options: ShuffleOptions = {}): T[] {
    const rng = options.rng ?? Math.random
    const weights: ShuffleWeights = { ...DEFAULT_SHUFFLE_WEIGHTS, ...options.weights }
    const artistWindow = options.artistWindow ?? 2
    const titleWindow = options.titleWindow ?? 2
    const recentWindow = options.recentWindow ?? 10
    const maxQueue = Math.min(Math.max(options.maxQueue ?? 50, 1), 50)

    // Requirement: dedupe by stable track id — the same id
    // never appears twice in one generated queue. First
    // occurrence wins; same title + different id stays.
    const byId = new Map<string, T>()
    const metas: TrackMeta[] = []
    for (const track of tracks) {
      let input: ShuffleInput
      try {
        input = describe(track)
      } catch {
        continue // a malformed row never breaks the queue
      }
      if (!input || typeof input.id !== 'string' || !input.id) continue
      if (byId.has(input.id)) continue
      byId.set(input.id, track)
      metas.push(this.metaOf(input))
    }

    // Pool indices; oversized pools are trimmed with a
    // partial Fisher–Yates (low-level utility only — the
    // final ORDER is decided by weighted selection below).
    let pool: number[] = metas.map((_, i) => i)
    if (pool.length > maxQueue) {
      for (let i = 0; i < maxQueue; i++) {
        const j = i + Math.floor(rng() * (pool.length - i))
        ;[pool[i], pool[j]] = [pool[j], pool[i]]
      }
      pool = pool.slice(0, maxQueue)
    }

    // Playback history: id → recency rank (0 = most recent).
    const recentRank = new Map<string, number>()
    const recent = options.recentIds ?? []
    for (let i = 0; i < Math.min(recent.length, recentWindow); i++) {
      const id = recent[i]
      if (id && !recentRank.has(id)) recentRank.set(id, i)
    }

    // Continuity context: the track currently playing.
    // Its artist/title count as "just played".
    const artistHistory: string[] = []
    const titleHistory: string[] = []
    let current: TrackMeta | null = null
    if (options.previous) {
      current = this.metaOf(options.previous)
      artistHistory.unshift(current.artistKey)
      titleHistory.unshift(current.titleKey)
    }
    const historyCap = Math.max(artistWindow, titleWindow)

    const queue: T[] = []
    const queueMetas: TrackMeta[] = []
    while (queue.length < maxQueue && pool.length > 0) {
      // Family census of the remaining pool. Candidates
      // from a title family that still crowds the pool
      // get an urgency bonus and a SHRUNKEN window
      // penalty: fully suppressing an over-represented
      // family sinks every member to the tail together,
      // where they are forced to be adjacent. Spreading
      // them steadily through the queue is what keeps
      // a run of same-title variants from forming.
      const titleRemaining = new Map<string, number>()
      const artistRemaining = new Map<string, number>()
      for (const poolIndex of pool) {
        const meta = metas[poolIndex]
        titleRemaining.set(meta.titleKey, (titleRemaining.get(meta.titleKey) ?? 0) + 1)
        artistRemaining.set(meta.artistKey, (artistRemaining.get(meta.artistKey) ?? 0) + 1)
      }
      const others = Math.max(pool.length - 1, 1)

      const scored: Array<{ poolIndex: number; value: number }> = []
      for (const poolIndex of pool) {
        const meta = metas[poolIndex]
        const ctx: PickContext = {
          current,
          artistHistory,
          titleHistory,
          recentRank,
          weights,
          rng,
          artistWindow,
          titleWindow,
          recentWindow,
          titleFamilyShare: Math.min((titleRemaining.get(meta.titleKey) ?? 1) - 1, others) / others,
          artistFamilyShare: Math.min((artistRemaining.get(meta.artistKey) ?? 1) - 1, others) / others
        }
        scored.push({ poolIndex, value: this.scoreMeta(meta, ctx) })
      }

      // Roulette wheel: draw proportional to score.
      // Every caller passes a non-empty array, so the
      // draw loop below always returns (roll ends at
      // total × (rng() − 1) ≤ 0); `tail` only exists
      // to satisfy the return type.
      const draw = (candidates: Array<{ poolIndex: number; value: number }>): { poolIndex: number; value: number } => {
        let candidateTotal = 0
        let tail = candidates[0]
        for (const entry of candidates) {
          candidateTotal += entry.value
          tail = entry
        }
        let roll = rng() * candidateTotal
        for (const entry of candidates) {
          roll -= entry.value
          if (roll <= 0) return entry
        }
        return tail
      }

      let chosen = draw(scored)
      // Adjacency avoidance: a winner sharing the previous
      // track's title family or artist is redrawn from the
      // non-repeating candidates whenever any exist — the
      // strongest form of the repetition penalty. Pure
      // subtractive penalties alone make every repeating
      // variant sink to the tail of the queue together,
      // where they are forced to be adjacent. When every
      // remaining candidate repeats (end-game), the
      // penalized draw stands.
      if (current) {
        const context = current
        if (this.repeatsContext(metas[chosen.poolIndex], context)) {
          const fresh = scored.filter((entry) => !this.repeatsContext(metas[entry.poolIndex], context))
          if (fresh.length > 0) chosen = draw(fresh)
        }
      }

      const meta = metas[chosen.poolIndex]
      const track = byId.get(meta.id)
      if (track !== undefined) {
        queue.push(track)
        queueMetas.push(meta)
      }
      artistHistory.unshift(meta.artistKey)
      titleHistory.unshift(meta.titleKey)
      if (artistHistory.length > historyCap) artistHistory.length = historyCap
      if (titleHistory.length > historyCap) titleHistory.length = historyCap
      current = meta
      pool = pool.filter((i) => i !== chosen.poolIndex)
    }

    // Repair pass: weighted selection can still leave
    // same-family neighbours when the tail of the pool
    // is one family (every non-family track was drawn
    // first). Reinsert each offending track at the
    // nearest conflict-free slot. The probabilistic
    // order is preserved — only unavoidable adjacency
    // is fixed, and when no slot exists (the family
    // dominates the queue) the order is restored.
    let i = 1
    let steps = 0
    const maxSteps = queue.length * queue.length
    while (i < queue.length && steps < maxSteps) {
      steps++
      if (!this.repeatsContext(queueMetas[i], queueMetas[i - 1])) {
        i++
        continue
      }
      const track = queue.splice(i, 1)[0]
      const meta = queueMetas.splice(i, 1)[0]
      let slot = -1
      for (let d = 1; d <= queue.length && slot === -1; d++) {
        for (const k of [i + d, i - d]) {
          if (k < 0 || k > queue.length) continue
          const before = k > 0 ? queueMetas[k - 1] : null
          const after = k < queue.length ? queueMetas[k] : null
          if (before && this.repeatsContext(meta, before)) continue
          if (after && this.repeatsContext(after, meta)) continue
          slot = k
          break
        }
      }
      if (slot === -1) {
        queue.splice(i, 0, track)
        queueMetas.splice(i, 0, meta)
        i++
      } else {
        queue.splice(slot, 0, track)
        queueMetas.splice(slot, 0, meta)
        i = Math.max(1, Math.min(i, slot) - 1)
      }
    }
    return queue
  }

  /**
   * Score of one candidate against the current context.
   * Exposed for tests: with a constant rng the score is
   * fully deterministic, so score relationships
   * (genre compatibility, penalties) can be asserted
   * directly instead of through queue order.
   *
   * Family shares are 0 here — the live picker derives
   * them from the remaining pool.
   */
  scoreCandidate(candidate: ShuffleInput, previous: ShuffleInput | null, options: ShuffleOptions = {}): number {
    const rng = options.rng ?? (() => 0.5)
    const weights: ShuffleWeights = { ...DEFAULT_SHUFFLE_WEIGHTS, ...options.weights }
    const artistWindow = options.artistWindow ?? 2
    const titleWindow = options.titleWindow ?? 2
    const recentWindow = options.recentWindow ?? 10
    const meta = this.metaOf(candidate)
    const current = previous ? this.metaOf(previous) : null
    const artistHistory = current ? [current.artistKey] : []
    const titleHistory = current ? [current.titleKey] : []
    const recentRank = new Map<string, number>()
    const recent = options.recentIds ?? []
    for (let i = 0; i < Math.min(recent.length, recentWindow); i++) {
      const id = recent[i]
      if (id && !recentRank.has(id)) recentRank.set(id, i)
    }
    return this.scoreMeta(meta, {
      current,
      artistHistory,
      titleHistory,
      recentRank,
      weights,
      rng,
      artistWindow,
      titleWindow,
      recentWindow,
      titleFamilyShare: 0,
      artistFamilyShare: 0
    })
  }

  private scoreMeta(meta: TrackMeta, ctx: PickContext): number {
    const { current, weights, rng } = ctx
    // Genre continuity: similarity to the previous track.
    // No context (first pick) or missing metadata → neutral.
    const genreSimilarity = current ? this.genres.trackGenreSimilarity(current.genres, meta.genres) : 0.5
    const randomness = rng()

    // Artist diversity: 1 = fresh artist, 0 = same artist
    // as the track that just played.
    let artistDiversity = 1
    let sameArtist = false
    let recentArtist = false
    if (current && current.artistKey && meta.artistKey && current.artistKey === meta.artistKey) {
      sameArtist = true
      artistDiversity = 0
    } else if (this.inWindow(meta.artistKey, ctx.artistHistory, ctx.artistWindow)) {
      recentArtist = true
      artistDiversity = 0.4
    }

    // Popularity balance: reward mixing hits and deep cuts —
    // a candidate far from the previous track's popularity
    // scores higher than a clone of it. Unknown → neutral.
    let popularityBalance = 0.5
    if (current && current.popularity != null && meta.popularity != null) {
      popularityBalance = Math.min(1, Math.max(0, 0.25 + Math.abs(current.popularity - meta.popularity) * 2))
    }

    // Family urgency: a candidate whose title family still
    // crowds the remaining pool is boosted, so the group is
    // consumed steadily instead of sinking to the tail.
    const urgency = weights.familyUrgency * ctx.titleFamilyShare

    let score =
      weights.genre * genreSimilarity +
      weights.randomness * randomness +
      weights.artistDiversity * artistDiversity +
      weights.popularityBalance * popularityBalance +
      urgency

    // Window penalties shrink as the family crowds the
    // remaining pool (share 0..1 → scale 1..0): suppressing
    // an over-represented family is exactly what forces its
    // members to be adjacent at the tail.
    const artistWindowScale = Math.max(1 - ctx.artistFamilyShare, 0)
    const titleWindowScale = Math.max(1 - ctx.titleFamilyShare, 0)

    let penalties = 0
    if (sameArtist) {
      penalties += weights.sameArtistPenalty
    } else if (recentArtist) {
      penalties += weights.recentArtistPenalty * artistWindowScale
    }

    if (current) {
      const sameKey = !!meta.titleKey && !!current.titleKey && meta.titleKey === current.titleKey
      if (sameKey) {
        // Exact same canonical title as the previous track.
        penalties += weights.sameTitlePenalty
      } else if (this.titles.isSameTitleFamily(current.titleKey, meta.titleKey)) {
        // Near-identical ("Perfect" vs "Perfect 2", "Perfect - Live").
        penalties += weights.nearTitlePenalty
      } else if (this.titleInWindow(meta.titleKey, ctx.titleHistory, ctx.titleWindow)) {
        penalties += weights.recentTitlePenalty * titleWindowScale
      }
    }

    // Recently played: strongest for the most recent play,
    // decaying across the history window. No history → no
    // penalty (history is optional, never required).
    const rank = ctx.recentRank.get(meta.id)
    if (rank != null) {
      penalties += weights.recentPlayPenalty * (1 - rank / Math.max(ctx.recentWindow, 1))
    }

    score -= penalties
    // Never zero: tiny, single-artist or single-genre
    // playlists must still terminate with every track playable.
    return Math.min(1, Math.max(0.01, score))
  }

  /** True when a candidate repeats the previous pick's
   *  title family or artist (adjacency to avoid). */
  private repeatsContext(meta: TrackMeta, current: TrackMeta): boolean {
    if (current.artistKey && meta.artistKey && current.artistKey === meta.artistKey) {
      return true
    }
    return this.titles.isSameTitleFamily(current.titleKey, meta.titleKey)
  }

  /** Exact key or same title family within the window. */
  private titleInWindow(key: string, history: string[], window: number): boolean {
    if (!key) return false
    const limit = Math.min(window, history.length)
    for (let i = 0; i < limit; i++) {
      if (key === history[i] || this.titles.isSameTitleFamily(key, history[i])) return true
    }
    return false
  }

  private inWindow(key: string, history: string[], window: number): boolean {
    if (!key) return false
    const limit = Math.min(window, history.length)
    for (let i = 0; i < limit; i++) {
      if (history[i] === key) return true
    }
    return false
  }

  private metaOf(input: ShuffleInput): TrackMeta {
    return {
      id: input.id,
      artistKey: canonicalKey(input.artists ?? ''),
      titleKey: this.titles.titleKey(input.title ?? ''),
      genres: (input.genres ?? []).map((g) => this.genres.normalizeGenre(g)).filter(Boolean),
      popularity:
        typeof input.popularity === 'number' && Number.isFinite(input.popularity)
          ? Math.min(1, Math.max(0, input.popularity))
          : null
    }
  }
}

function canonicalKey(raw: string): string {
  return raw
    .toLowerCase()
    .normalize('NFD')
    .replaceAll(/\p{M}/gu, '')
    .replaceAll(/[^\p{L}\p{N}\s]/gu, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim()
}
