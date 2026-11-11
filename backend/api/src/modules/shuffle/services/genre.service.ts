/**
 * Normalized genre representation + genre similarity.
 *
 * Similarity is taxonomy-driven: same genre = 1, genres in
 * one family (pop / dance-pop / indie-pop / …) = 0.85,
 * explicit cross-family affinities (pop↔r&b = 0.6,
 * pop↔rock = 0.45, pop↔death-metal = 0.15) in between.
 * Nothing is forced: the similarity only tilts the
 * probability, genre changes still happen naturally.
 *
 * Missing or unknown genres are NEUTRAL (0.5) — incomplete
 * metadata gracefully falls toward random selection instead
 * of crashing or freezing the queue onto one genre.
 */

/** Genres grouped by musical family (space-separated, normalized). */
const FAMILIES: ReadonlyArray<ReadonlyArray<string>> = [
  [
    'pop',
    'dance pop',
    'indie pop',
    'synth pop',
    'synthpop',
    'electropop',
    'art pop',
    'teen pop',
    'bubblegum pop',
    'pop rock'
  ],
  ['r and b', 'rnb', 'soul', 'neo soul', 'funk', 'motown', 'contemporary r and b'],
  ['hip hop', 'rap', 'trap', 'grime', 'boom bap', 'pop rap'],
  [
    'rock',
    'indie rock',
    'alt rock',
    'alternative rock',
    'classic rock',
    'soft rock',
    'punk rock',
    'pop punk',
    'hard rock',
    'garage rock'
  ],
  ['metal', 'heavy metal', 'death metal', 'thrash metal', 'metalcore', 'doom metal', 'black metal', 'power metal'],
  [
    'electronic',
    'edm',
    'house',
    'techno',
    'trance',
    'dubstep',
    'drum and bass',
    'dnb',
    'ambient',
    'synthwave',
    'disco',
    'club',
    'dance'
  ],
  ['jazz', 'smooth jazz', 'bebop', 'jazz fusion', 'swing', 'big band'],
  ['folk', 'indie folk', 'acoustic', 'singer songwriter', 'country', 'americana', 'bluegrass', 'folk rock'],
  ['latin', 'reggaeton', 'latin pop', 'bachata', 'salsa', 'cumbia', 'latin rock'],
  ['reggae', 'dancehall', 'ska', 'roots reggae'],
  ['blues', 'delta blues', 'electric blues', 'rhythm and blues'],
  ['classical', 'orchestral', 'piano', 'chamber', 'opera'],
  ['world', 'afrobeats', 'afrobeat', 'arabic', 'turkish', 'bollywood', 'k pop', 'j pop', 'mandopop', 'cantopop']
]

/**
 * Cross-family affinities (0..1). Members resolve to their
 * family at construction; unknown members are skipped.
 */
const AFFINITY_PAIRS: ReadonlyArray<readonly [string, string, number]> = [
  ['hip hop', 'r and b', 0.65],
  ['pop', 'r and b', 0.6],
  ['blues', 'r and b', 0.6],
  ['blues', 'rock', 0.6],
  ['rock', 'metal', 0.7],
  ['electronic', 'pop', 0.55],
  ['folk', 'rock', 0.55],
  ['electronic', 'hip hop', 0.5],
  ['folk', 'pop', 0.5],
  ['hip hop', 'pop', 0.5],
  ['jazz', 'r and b', 0.5],
  ['latin', 'pop', 0.5],
  ['pop', 'rock', 0.45],
  ['world', 'pop', 0.45],
  ['jazz', 'folk', 0.45],
  ['reggae', 'pop', 0.4],
  ['classical', 'jazz', 0.4]
]

/** Same family (e.g. pop → dance-pop). */
const SAME_FAMILY_SIM = 0.85
/** Known but unrelated families (e.g. pop → death-metal). */
const DISTANT_SIM = 0.15
/** Missing/unknown metadata: neither reward nor punish. */
const UNKNOWN_SIM = 0.5

export class GenreService {
  private readonly familyOf = new Map<string, string>()
  private readonly affinity = new Map<string, number>()

  constructor() {
    for (const [i, FAMILY] of FAMILIES.entries()) {
      const family = `f${i}`
      for (const member of FAMILY) {
        this.familyOf.set(member, family)
      }
    }
    for (const [a, b, weight] of AFFINITY_PAIRS) {
      const fa = this.familyOf.get(a)
      const fb = this.familyOf.get(b)
      if (!fa || !fb || fa === fb) continue
      const key = fa < fb ? `${fa}|${fb}` : `${fb}|${fa}`
      this.affinity.set(key, weight)
    }
  }

  /**
   * Normalized genre form: lowercase, diacritics stripped,
   * "&" spelled out, punctuation → spaces. Idempotent.
   */
  normalizeGenre(raw: string): string {
    return raw
      .toLowerCase()
      .normalize('NFD')
      .replaceAll(/\p{M}/gu, '')
      .replaceAll('&', ' and ')
      .replaceAll(/[^\p{L}\p{N}\s]/gu, ' ')
      .replaceAll(/\s+/g, ' ')
      .trim()
  }

  /** 0..1 similarity between two raw genre labels. */
  genreSimilarity(rawA: string, rawB: string): number {
    const a = this.normalizeGenre(rawA)
    const b = this.normalizeGenre(rawB)
    if (!a || !b) return UNKNOWN_SIM
    if (a === b) return 1
    const fa = this.familyOf.get(a) ?? `u:${a}`
    const fb = this.familyOf.get(b) ?? `u:${b}`
    if (fa === fb) return SAME_FAMILY_SIM
    const key = fa < fb ? `${fa}|${fb}` : `${fb}|${fa}`
    return this.affinity.get(key) ?? DISTANT_SIM
  }

  /**
   * Similarity between two tracks' genre lists: the strongest
   * pair (a multi-genre track is as compatible as its most
   * compatible genre). Empty lists are neutral.
   */
  trackGenreSimilarity(genresA?: string[], genresB?: string[]): number {
    const a = (genresA ?? []).map((g) => this.normalizeGenre(g)).filter(Boolean)
    const b = (genresB ?? []).map((g) => this.normalizeGenre(g)).filter(Boolean)
    if (a.length === 0 || b.length === 0) return UNKNOWN_SIM
    let best = 0
    for (const ga of a) {
      for (const gb of b) {
        best = Math.max(best, this.genreSimilarity(ga, gb))
      }
    }
    return best
  }

  /**
   * Safe fallback classification from metadata the project
   * already carries (JioSaavn language / origin). Regional
   * tags act as their own families: they keep a regional
   * radio queue coherent without claiming a musical genre.
   */
  fallbackGenres(meta: { language?: unknown; origin?: unknown }): string[] {
    const out: string[] = []
    for (const raw of [meta.language, meta.origin]) {
      if (typeof raw !== 'string') continue
      const genre = this.normalizeGenre(raw)
      if (genre) out.push(genre)
    }
    return [...new Set(out)]
  }
}
