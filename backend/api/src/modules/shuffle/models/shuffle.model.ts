/**
 * Genre-Aware Smart Shuffle — shared types.
 *
 * The final queue is produced by weighted random selection,
 * NOT by a deterministic sort and NOT by a plain Fisher–Yates:
 * every next-track candidate is scored (genre continuity +
 * randomness + artist diversity + popularity balance, minus
 * repetition and recently-played penalties) and one candidate
 * is drawn proportionally to its score. Fisher–Yates survives
 * only as a low-level utility (large-pool trimming).
 */

/** A track as seen by the shuffle engine. */
export interface ShuffleInput {
  /** Stable provider id — the ONLY dedup identity. Two tracks
   *  with the same title but different ids are different tracks;
   *  the same id may never appear twice in one queue. */
  id: string
  title: string
  /** Artist names, comma-separated. */
  artists?: string
  /** Zero or more genres — a track may have several
   *  (e.g. ["pop", "dance-pop"]). Missing genres are neutral. */
  genres?: string[]
  /** 0..1 popularity when known; unknown stays neutral. */
  popularity?: number
}

/**
 * Tunable weights. The defaults are the starting values from
 * the feature spec; every component is overridable in tests
 * or per-call without touching the algorithm.
 */
export interface ShuffleWeights {
  genre: number
  randomness: number
  artistDiversity: number
  popularityBalance: number
  sameArtistPenalty: number
  recentArtistPenalty: number
  sameTitlePenalty: number
  nearTitlePenalty: number
  recentTitlePenalty: number
  recentPlayPenalty: number
  /** Bonus for candidates whose title family still has
   *  several members left in the pool — spreads a large
   *  same-title group through the queue instead of
   *  letting penalties sink it to the tail. */
  familyUrgency: number
}

export const DEFAULT_SHUFFLE_WEIGHTS: ShuffleWeights = {
  genre: 0.45,
  randomness: 0.25,
  artistDiversity: 0.2,
  popularityBalance: 0.1,
  sameArtistPenalty: 0.95,
  recentArtistPenalty: 0.5,
  sameTitlePenalty: 0.95,
  nearTitlePenalty: 0.8,
  recentTitlePenalty: 0.3,
  recentPlayPenalty: 0.9,
  familyUrgency: 0.3
}

export interface ShuffleOptions {
  /** Track currently playing — the continuity context for the
   *  first pick. Its artist/title count as "just played". */
  previous?: ShuffleInput | null
  /** Playback history ids, most-recent-first. Recently played
   *  tracks are penalized proportionally to their recency. */
  recentIds?: string[]
  /** Maximum queue length (default 50 — the provider cap). */
  maxQueue?: number
  /** Injectable RNG (deterministic tests). Defaults to Math.random. */
  rng?: () => number
  weights?: Partial<ShuffleWeights>
  /** How many picks back artist repetition is still penalized. */
  artistWindow?: number
  /** How many picks back title repetition is still penalized. */
  titleWindow?: number
  /** How much of recentIds still carries a penalty. */
  recentWindow?: number
}

/** Full score breakdown for one candidate (tests + debugging). */
export interface CandidateScore {
  track: ShuffleInput
  score: number
  genreSimilarity: number
  randomness: number
  artistDiversity: number
  popularityBalance: number
  penalties: number
}
