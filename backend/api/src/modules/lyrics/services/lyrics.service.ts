import process from 'node:process'

export interface LyricsLine {
  timeMs: number | null
  text: string
}

export interface LyricsResult {
  available: boolean
  instrumental: boolean
  timed: boolean
  lines: LyricsLine[]
}

interface LyricsArgs {
  title: string
  artist: string
  album: string
  durationMs: number
}

const UA = 'NightLightApp/1.0 (https://github.com/nightlight; contact: developer)'

interface LrcLibEntry {
  syncedLyrics?: string | null
  plainLyrics?: string | null
  instrumental?: boolean
  duration?: number
  trackName?: string
  artistName?: string
  albumName?: string
}

interface RapidApiSong {
  title?: string
  artist?: string
  lyrics?: string
  album?: string
}

/** In-memory lyrics cache keyed by normalized title+artist. */
const lyricsCache = new Map<string, LyricsResult>()
const CACHE_TTL_MS = 30 * 60 * 1000 // 30 minutes
const cacheTimestamps = new Map<string, number>()

/** Fetches synchronized lyrics from the free LRCLIB database. */
export class LyricsService {
  async fetchLyrics({ title, artist, album, durationMs }: LyricsArgs): Promise<LyricsResult> {
    // Check cache first (keyed by normalized title+artist).
    const cacheKey = cacheKeyFor(title, artist)
    const cached = lyricsCache.get(cacheKey)
    if (cached && Date.now() - (cacheTimestamps.get(cacheKey) ?? 0) < CACHE_TTL_MS) {
      return cached
    }

    const durationSec = durationMs ? Math.round(durationMs / 1000) : 0

    // Title variants: providers often fail on decorated JioSaavn titles like
    // 'Song (From "Movie" )' or 'Song - Version' — the base recording usually
    // exists in the provider under the cleaned title. Tried in order; first
    // hit wins. Feature strings ('feat. X') are a separate fallback because
    // the provider indexes the song under the primary artist only.
    const base = stripDecoration(title)
    const variants: string[] = [title]
    if (base && base !== title) {
      variants.push(base)
    }
    const featless = artist.split(/,|\bfeat\.?\b|\bft\.?\b/i)[0]?.trim() ?? ''
    const artistVariants = featless && featless !== artist ? [featless, artist] : [artist]

    let data: LrcLibEntry | null = null
    data = await this.fetchFromLrcLib(artistVariants, variants, album, durationSec)

    // LRCLIB failed: try RapidAPI as fallback.
    if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
      const rapidResult = await this.fetchRapidApiLyrics(title, artist)
      if (rapidResult) {
        lyricsCache.set(cacheKey, rapidResult)
        cacheTimestamps.set(cacheKey, Date.now())
        return rapidResult
      }
    }

    if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
      return { available: false, instrumental: false, timed: false, lines: [] }
    }

    const raw = data.syncedLyrics || data.plainLyrics || ''
    if (!raw.trim()) {
      return { available: false, instrumental: false, timed: false, lines: [] }
    }
    if (data.instrumental) {
      const result: LyricsResult = { available: true, instrumental: true, timed: false, lines: [] }
      lyricsCache.set(cacheKey, result)
      cacheTimestamps.set(cacheKey, Date.now())
      return result
    }

    const timed = Boolean(data.syncedLyrics)
    const lines = parseLrc(raw, timed)
    const result: LyricsResult = { available: true, instrumental: false, timed, lines }
    lyricsCache.set(cacheKey, result)
    cacheTimestamps.set(cacheKey, Date.now())
    return result
  }

  /**
   * Fetch lyrics from the RapidAPI lyrics provider.
   * Uses RAPIDAPI_KEY from environment. Matches by normalized title + artist.
   */
  private async fetchRapidApiLyrics(title: string, artist: string): Promise<LyricsResult | null> {
    const apiKey = process.env.RAPIDAPI_KEY
    if (!apiKey) return null

    // Build search query: title + primary artist (never just title alone).
    const primaryArtist = artist.split(/,|\bfeat\.?\b|\bft\.?\b/i)[0]?.trim() ?? ''
    const query = primaryArtist ? `${title} ${primaryArtist}` : title

    try {
      const r = await fetch(`https://community-lyricsnmusic.p.rapidapi.com/songs?q=${encodeURIComponent(query)}`, {
        headers: {
          'X-RapidAPI-Key': apiKey,
          'X-RapidAPI-Host': 'community-lyricsnmusic.p.rapidapi.com'
        },
        signal: AbortSignal.timeout(10_000)
      })
      if (!r.ok) return null

      const body = (await r.json()) as { result?: RapidApiSong[] }
      const songs = body?.result
      if (!Array.isArray(songs) || songs.length === 0) return null

      // Match: prefer exact normalized title + strong artist match.
      const wantTitle = canon(title)
      const wantArtist = canon(artist)
      let best: RapidApiSong | null = null
      let bestScore = -1

      for (const song of songs) {
        if (!song.lyrics || !song.lyrics.trim()) continue
        let score = 0
        const gotTitle = canon(song.title ?? '')
        const gotArtist = canon(song.artist ?? '')

        if (gotTitle === wantTitle) score += 5
        else if (gotTitle.includes(wantTitle) || wantTitle.includes(gotTitle)) score += 3

        if (gotArtist && wantArtist) {
          if (gotArtist === wantArtist) score += 3
          else if (gotArtist.includes(wantArtist) || wantArtist.includes(gotArtist)) score += 1
        }

        // Penalize variant titles (acoustic, live, remix, etc.) when user didn't request them.
        if (
          /\b(?:acoustic|live|remix|cover|instrumental|karaoke|piano|sped|slowed|reverb|demo|concert|orchestral|unplugged|nightcore)\b/i.test(
            gotTitle
          )
        ) {
          score -= 2
        }

        if (score > bestScore) {
          bestScore = score
          best = song
        }
      }

      if (bestScore < 4 || !best || !best.lyrics) return null

      const lines = best.lyrics
        .split('\n')
        .filter((l) => l.trim())
        .map((l) => ({ timeMs: null, text: l.trim() }))
      return { available: true, instrumental: false, timed: false, lines }
    } catch {
      return null
    }
  }

  /** LRCLIB search with fuzzy duration/album tolerance. */
  private async searchLrcLib(
    title: string,
    artist: string,
    album: string,
    durationSec: number | null
  ): Promise<LrcLibEntry | null> {
    const q = new URLSearchParams({
      track_name: title,
      artist_name: artist || '',
      duration: durationSec ? String(durationSec) : ''
    })
    try {
      const r = await fetch(`https://lrclib.net/api/search?${q.toString()}`, {
        headers: { 'User-Agent': UA },
        signal: AbortSignal.timeout(10_000)
      })
      if (!r.ok) return null
      const list = (await r.json()) as LrcLibEntry[]
      if (!Array.isArray(list) || list.length === 0) return null

      const wantTitle = canon(title)
      const wantArtist = canon(artist)
      let best: LrcLibEntry | null = null
      let bestScore = -1
      for (const e of list) {
        let score = 0
        const t = canon(e.trackName ?? '')
        const a = canon(e.artistName ?? '')
        if (t === wantTitle) score += 4
        else if (t && wantTitle && (t.includes(wantTitle) || wantTitle.includes(t))) score += 2
        if (a && wantArtist && (a === wantArtist || a.includes(wantArtist) || wantArtist.includes(a))) score += 1
        if (durationSec && typeof e.duration === 'number' && Math.abs(e.duration - durationSec) <= 6) score += 1
        if (album && e.albumName && canon(e.albumName) === canon(album)) score += 1
        // Prefer synchronized uploads so the UI can karaoke-highlight.
        if (e.syncedLyrics) score += 2
        if (!e.syncedLyrics && !e.plainLyrics) score -= 3
        if (score > bestScore) {
          bestScore = score
          best = e
        }
      }
      return bestScore >= 3 ? best : null
    } catch {
      return null
    }
  }

  /**
   * Tries every (artist, title-variant) combination against LRCLIB's exact
   * endpoint first, then its search endpoint, and returns the first entry
   * that actually carries lyrics.
   */
  private async fetchFromLrcLib(
    artistVariants: string[],
    titleVariants: string[],
    album: string,
    durationSec: number | null
  ): Promise<LrcLibEntry | null> {
    for (const artistName of artistVariants) {
      for (const t of titleVariants) {
        try {
          // Fast path: exact (album + duration) match.
          const q = new URLSearchParams({
            artist_name: artistName || '',
            track_name: t,
            album_name: album || '',
            duration: durationSec ? String(durationSec) : ''
          })
          const r = await fetch(`https://lrclib.net/api/get?${q.toString()}`, {
            headers: { 'User-Agent': UA },
            signal: AbortSignal.timeout(10_000)
          })
          if (r.ok) {
            const data = (await r.json()) as LrcLibEntry
            if (data && (data.syncedLyrics || data.plainLyrics)) return data
          }
        } catch {
          // try the next variant
        }

        // Fallback: search by title + artist and pick the closest entry.
        const searched = await this.searchLrcLib(t, artistName, album || '', durationSec)
        if (searched && (searched.syncedLyrics || searched.plainLyrics)) return searched
      }
    }
    return null
  }
}

/**
 * Strips provider-hostile decorations from a title: '(From ...)', '[...]',
 * trailing '- Version/Live/Remix' suffixes, and trailing feature credits.
 * Keeps the base recording title the lyrics providers index under.
 */
function stripDecoration(raw: string): string {
  return raw
    .replaceAll(/\s*\(\s*(from|feat|ft|with)[^)]*\)/gi, '')
    .replaceAll(/\([^)]*\)/g, ' ')
    .replaceAll(/\[[^\]]*\]/g, ' ')
    .replace(/\s+-\s[^-]*$/, ' ')
    .replaceAll(/,\s*(feat|ft)[^,]*$/gi, '')
    .replaceAll(/\s+/g, ' ')
    .trim()
}

function canon(raw: string): string {
  return raw
    .toLowerCase()
    .replaceAll(/\([^)]*\)/g, ' ')
    .replaceAll(/[-–—:;.&_+]/g, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim()
}

/** Removes non-timestamp metadata tags ([ti:], [ar:], [by:], [offset:] ...). */
function stripMetaTags(raw: string): string {
  return raw.replaceAll(/\[(?!\d{1,2}:\d{2})[^\]]*\]/g, '')
}

function cacheKeyFor(title: string, artist: string): string {
  return `${canon(title)}|||${canon(artist)}`
}

function parseLrc(raw: string, timed: boolean): LyricsLine[] {
  const out: LyricsLine[] = []
  for (const rawLine of raw.split('\n')) {
    const line = rawLine.trim()
    if (!line) continue
    const stamps: number[] = []
    // [mm:ss.xx] timestamps may appear multiple times per line.
    const re = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g
    let m: RegExpExecArray | null
    let text = line
    while ((m = re.exec(line)) !== null) {
      const min = Number(m[1])
      const sec = Number(m[2])
      const frac = m[3] ? Number(m[3].padEnd(3, '0')) : 0
      stamps.push((min * 60 + sec) * 1000 + frac)
      text = line.slice(re.lastIndex)
    }
    text = stripMetaTags(text)
      .replace(/^[\s:：\-–—|]+/, '')
      .replaceAll(/\s+/g, ' ')
      .trim()
    if (!text) continue
    if (timed && stamps.length) {
      for (const t of stamps) out.push({ timeMs: t, text })
    } else {
      out.push({ timeMs: null, text })
    }
  }

  if (timed) {
    out.sort((a, b) => (a.timeMs ?? 0) - (b.timeMs ?? 0))
  }

  return out
}
