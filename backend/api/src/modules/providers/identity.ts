/**
 * Cross-provider identity: normalized matching so the same recording found
 * via two providers links up, while distinct versions stay distinct.
 *
 * Never deduplicates by title alone — artist evidence is required, and a
 * version mismatch (original vs acoustic/live/...) always vetoes.
 */

const STOP_WORDS = new Set(['the', 'and', 'band', 'feat', 'ft', 'official'])

function normText(raw: string): string {
  return (raw ?? '')
    .toLowerCase()
    .replaceAll(/\(.*?\)/g, ' ')
    .replaceAll(/\[.*?\]/g, ' ')
    .replaceAll(/[^a-z0-9\s]/g, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim()
}

function significantTokens(raw: string): Set<string> {
  const out = new Set<string>()
  for (const token of normText(raw).split(' ')) {
    if (token.length >= 3 && !STOP_WORDS.has(token)) out.add(token)
  }
  return out
}

/** Primary artist names from a song payload shape (tolerates partial shapes). */
export function primaryArtistsOf(song: {
  artists?: { primary?: Array<{ name?: string } | null> | null } | null
}): string[] {
  const primary = song.artists?.primary
  if (Array.isArray(primary)) return primary.map((a) => a?.name ?? '').filter(Boolean)
  return []
}

/**
 * Functional roles that mean "performed this recording". Upstream `artists[]`
 * carries these per credit (singer/music/lyricist/...), while the
 * `primary_artists` bucket echoes the bucket name as its role value.
 */
const PERFORMER_ROLES = new Set(['singer', 'performer', 'vocalist', 'vocals', 'artist'])

/**
 * Performer identity for ranking: billed primary artists + featured artists
 * + `all` entries with a performer role. Credits like music/lyricist/
 * composer/songwriter/producer NEVER count — a cover must not inherit the
 * original writer's identity (e.g. Ed Sheeran credited on Music Travel
 * Love's live recording must not make it Ed's).
 */
export function performerArtistsOf(song: {
  artists?: {
    primary?: Array<{ name?: string; role?: string } | null> | null
    featured?: Array<{ name?: string; role?: string } | null> | null
    all?: Array<{ name?: string; role?: string } | null> | null
  } | null
}): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  const push = (name?: string) => {
    const clean = (name ?? '').trim()
    if (clean && !seen.has(clean.toLowerCase())) {
      seen.add(clean.toLowerCase())
      out.push(clean)
    }
  }
  // Billed performers: the primary/featured buckets ARE the performer claim.
  for (const a of song.artists?.primary ?? []) push(a?.name)
  for (const a of song.artists?.featured ?? []) push(a?.name)
  // Full credits: only functional performer roles.
  for (const a of song.artists?.all ?? []) {
    const role = (a?.role ?? '').toLowerCase().trim()
    if (PERFORMER_ROLES.has(role)) push(a?.name)
  }
  return out
}

/** Canonical identity key: normalized title + sorted artists. */
export function canonicalKey(title: string, artists: string[]): string {
  const artist = artists
    .map((a) => normText(a))
    .filter(Boolean)
    .sort()
    .join(',')
  return `${normText(title)}|||${artist}`
}

function artistOverlap(a: string[], b: string[]): boolean {
  const left = new Set<string>()
  for (const name of a) for (const token of significantTokens(name)) left.add(token)
  if (left.size === 0) return false
  for (const name of b) {
    for (const token of significantTokens(name)) {
      if (left.has(token)) return true
    }
  }
  return false
}

/**
 * True when two candidates plausibly denote the same recording:
 * same canonical title + shared artist evidence + same version class.
 * Album and duration are corroborating (not required — providers differ).
 */
export function sameRecording(
  a: { title: string; artists: string[]; version: string; album?: string; durationMs?: number },
  b: { title: string; artists: string[]; version: string; album?: string; durationMs?: number }
): boolean {
  if (normText(a.title) !== normText(b.title)) return false
  if (!artistOverlap(a.artists, b.artists)) return false
  if (a.version !== b.version) return false
  if (a.album && b.album) {
    const albumA = normText(a.album)
    const albumB = normText(b.album)
    if (albumA && albumB && albumA !== albumB) {
      // Different albums usually mean different releases — but labels
      // repackage the same recording on compilations constantly, so closely
      // agreeing durations override the album mismatch. Unknown durations
      // (either side) keep the veto.
      const durationA = a.durationMs ?? 0
      const durationB = b.durationMs ?? 0
      const agree = durationA > 0 && durationB > 0 && Math.abs(durationA - durationB) <= 5000
      if (!agree) return false
    }
  }
  const durationA = a.durationMs ?? 0
  const durationB = b.durationMs ?? 0
  if (durationA > 0 && durationB > 0 && Math.abs(durationA - durationB) > 15000) return false
  return true
}
