import {
  GetTrendingUseCase,
  SearchAlbumsUseCase,
  SearchAllUseCase,
  SearchArtistsUseCase,
  SearchPlaylistsUseCase,
  SearchSongsUseCase,
  type SearchAlbumsArgs,
  type SearchArtistsArgs,
  type SearchPlaylistsArgs,
  type SearchSongsArgs
} from '#modules/search/use-cases'
import { GetSongSuggestionsUseCase } from '#modules/songs/use-cases'

interface RadioSongsArgs {
  seedId?: string
  seedName?: string
  artists?: string
  album?: string
  limit?: number
}

/** Exact song shape produced by the search use case (SongModel). */
type SongResult = Awaited<ReturnType<SearchSongsUseCase['execute']>>['results'][number]

/** Lowercases and strips remix/cover/acoustic/live style suffixes. */
function canonicalTitle(raw: unknown): string {
  if (typeof raw !== 'string') return ''
  return raw
    .toLowerCase()
    .replaceAll(/\([^)]*\)/g, ' ')
    .replaceAll(/\[[^\]]*\]/g, ' ')
    .replaceAll(/[-–—:;./_+]/g, ' ')
    .replaceAll(
      /\b(feat|ft|from|official|lyrics|video|version|edit|extended|remix|acoustic|live|slowed|sped up|cover|instrumental|karaoke|reprise)\b.*$/g,
      ' '
    )
    .replaceAll(/\s+/g, ' ')
    .trim()
}

/** Lowercases to a plain token stream (punctuation -> spaces) for word-boundary matching. */
function normalizedText(raw: unknown): string {
  if (typeof raw !== 'string') return ''
  return raw
    .toLowerCase()
    .replaceAll(/[^a-z0-9\s]/g, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim()
}

/** True when `hay` contains `token` as a whole word/phrase. */
function hasToken(hay: string, token: string): boolean {
  return hay.includes(` ${token} `) || hay === token || hay.startsWith(`${token} `) || hay.endsWith(` ${token}`)
}

/** Derivative/version patterns that make a candidate unfit for radio. */
const BAD_VERSION_TOKENS = [
  '8 bit',
  '16 bit',
  '8bit',
  '16bit',
  'karaoke',
  'tribute',
  'instrumental',
  'emulation',
  'sped up',
  'slowed',
  'nightcore',
  'lullaby'
]
/** "X Version" styles that are derivative re-recordings (piano/ambient/rain/...). */
const DERIVATIVE_VERSION_ADJ = ['piano', 'ambient', 'orchestral', 'rain', 'sleep', 'sad', 'lofi', 'lo fi']

/**
 * Rejects derivative/garbage versions (8-bit, karaoke, covers, sped up, ...).
 * Token-aware: a whole-word match is required, so a legitimate song that merely
 * contains a similar word is not blindly rejected. "Remix" is only rejected
 * when the seed itself is not a remix.
 */
function isBadVersion(title: string, seedIsRemix: boolean): boolean {
  const t = normalizedText(title)
  if (!t) return false
  for (const bad of BAD_VERSION_TOKENS) {
    if (hasToken(t, bad)) return true
  }
  for (const adj of DERIVATIVE_VERSION_ADJ) {
    if (hasToken(t, `${adj} version`)) return true
  }
  if (!seedIsRemix && hasToken(t, 'remix')) return true
  return false
}

/** Significant-artist tokens (>=3 chars, stop-words removed) for relatedness checks. */
function artistTokens(artist: string): string[] {
  return normalizedText(artist)
    .split(' ')
    .filter((w) => w.length >= 3 && w !== 'the' && w !== 'and' && w !== 'band')
}

/** True when two artist strings plausibly refer to the same act. */
function artistsRelated(a: string, b: string): boolean {
  const ta = artistTokens(a)
  const tb = new Set(artistTokens(b))
  return ta.some((w) => tb.has(w))
}

/**
 * Rejects tribute/AI/cover-style metadata contamination:
 * a candidate whose title carries the seed's title or the seed artist's name
 * while the candidate's artist is unrelated to the seed artist.
 * Example: seed "Timeless — The Weeknd", candidate "Starboy The Weeknd" by
 * "Blue Pink" -> rejected. Seed-artist songs always pass this gate.
 */
function isMetadataContaminated(seedCanon: string, seedArtists: string[], title: string, artist: string): boolean {
  const t = normalizedText(title)
  const a = normalizedText(artist)
  if (!t || !a) return false
  const seedArtistNorm = seedArtists.map((s) => normalizedText(s)).filter(Boolean)
  const related = seedArtistNorm.some((sa) => artistsRelated(sa, a))
  if (related) return false
  if (seedCanon && t.includes(seedCanon)) return true
  return seedArtistNorm.some((sa) => sa.length >= 3 && t.includes(sa))
}

export class SearchService {
  private readonly searchAllUseCase: SearchAllUseCase
  private readonly searchSongsUseCase: SearchSongsUseCase
  private readonly searchAlbumsUseCase: SearchAlbumsUseCase
  private readonly searchArtistsUseCase: SearchArtistsUseCase
  private readonly searchPlaylistsUseCase: SearchPlaylistsUseCase
  private readonly songSuggestions: GetSongSuggestionsUseCase
  private readonly trendingUseCase: GetTrendingUseCase

  constructor() {
    this.searchAllUseCase = new SearchAllUseCase()
    this.searchSongsUseCase = new SearchSongsUseCase()
    this.searchAlbumsUseCase = new SearchAlbumsUseCase()
    this.searchArtistsUseCase = new SearchArtistsUseCase()
    this.searchPlaylistsUseCase = new SearchPlaylistsUseCase()
    this.songSuggestions = new GetSongSuggestionsUseCase()
    this.trendingUseCase = new GetTrendingUseCase()
  }

  searchAll = (query: string) => {
    return this.searchAllUseCase.execute(query)
  }

  searchSongs = (args: SearchSongsArgs) => {
    return this.searchSongsUseCase.execute(args)
  }

  searchSongsWithTrace = (args: SearchSongsArgs) => {
    return this.searchSongsUseCase.searchWithTrace(args)
  }

  /**
   * "Radio" seeding: given the track currently playing, find OTHER songs that
   * feel related. JioSaavn's own suggestion engine is preferred (it returns
   * genuinely similar music); the artist + album search is the fallback. No
   * two queued songs ever share the same title, and the seed never repeats,
   * so auto-next and shuffle keep delivering fresh songs of the same vibe.
   */
  radioSongs = async ({ seedId, seedName, artists, album, limit = 30 }: RadioSongsArgs) => {
    const wanted = Math.min(Math.max(limit, 1), 50)
    const primaryArtist = (artists ?? '')
      .split(',')
      .map((s) => s.trim())
      .find(Boolean)
    const albumName = album && album.trim() ? album.trim() : ''
    const seedCanon = canonicalTitle(seedName)
    const seedIsRemix = hasToken(normalizedText(seedName), 'remix')
    const seedArtists = artists ?? ''

    const artistNames = (s: SongResult): string => {
      const prim = s.artists?.primary
      if (Array.isArray(prim) && prim.length) {
        return prim
          .map((a) => a?.name)
          .filter(Boolean)
          .join(', ')
      }
      const all = s.artists?.all
      if (Array.isArray(all) && all.length) {
        return all
          .map((a) => a?.name)
          .filter(Boolean)
          .join(', ')
      }
      return ''
    }
    const songLanguage = (s: SongResult): string => {
      const lang = (s as { language?: unknown }).language
      return typeof lang === 'string' && lang ? lang.toLowerCase() : 'unknown'
    }

    // Incremental acceptance: each candidate is validated the moment it is
    // considered, so a garbage search rung can never outrun the quality gate.
    const accepted: SongResult[] = []
    const seenIds = new Set<string>()
    const seenTitles = new Set<string>()
    const albumCounts = new Map<string, number>()
    // Never surface the seed itself again under another recording id.
    if (seedCanon) seenTitles.add(seedCanon)

    const consider = (song: SongResult): void => {
      if (!song || typeof song.id !== 'string') return
      if (seedId && song.id === seedId) return
      if (seenIds.has(song.id)) return // dedup by stable track ID (primary key)
      const title = canonicalTitle(song.name)
      if (!title || seenTitles.has(title)) return // same title = same song feel
      const artist = artistNames(song)
      if (isBadVersion(song.name, seedIsRemix)) return
      if (isMetadataContaminated(seedCanon, [seedArtists], song.name, artist)) return
      // Album guard: at most 2 candidates per album so one compilation
      // cannot dominate the pool. Artist diversity is the app's job.
      const albumKey = normalizedText((song.album as { name?: unknown } | undefined)?.name)
      if (albumKey && (albumCounts.get(albumKey) ?? 0) >= 2) return
      seenIds.add(song.id)
      seenTitles.add(title)
      if (albumKey) albumCounts.set(albumKey, (albumCounts.get(albumKey) ?? 0) + 1)
      accepted.push(song)
    }

    // 1. JioSaavn's own suggestion engine — genuinely similar music.
    if (seedId) {
      try {
        const suggestions = await this.songSuggestions.execute({ songId: seedId, limit: Math.min(wanted + 10, 50) })
        if (suggestions?.length) {
          for (const s of suggestions) {
            if (accepted.length >= wanted) break
            consider(s)
          }
        }
      } catch {
        // JioSaavn suggestions unavailable for this seed; fall back below.
      }
    }

    // 2. Fallback ladder. Each rung only runs while the pool is still short.
    //    Quality > quantity: we never pad with low-quality candidates.
    const runSearch = async (query: string, rungLimit: number): Promise<SongResult[]> => {
      try {
        const page = await this.searchSongsUseCase.execute({ query, page: 0, limit: rungLimit })
        return page?.results ?? []
      } catch {
        return [] // One failing seed must not sink the whole radio response.
      }
    }

    if (accepted.length < wanted) {
      if (primaryArtist) {
        for (const s of await runSearch(primaryArtist, 30)) {
          if (accepted.length >= wanted) break
          consider(s)
        }
      } else if (seedName) {
        // No artist known: the seed title itself is the best relatedness query.
        for (const s of await runSearch(seedName, 20)) {
          if (accepted.length >= wanted) break
          consider(s)
        }
      }
    }
    if (accepted.length < wanted && albumName && albumName.toLowerCase() !== (primaryArtist ?? '').toLowerCase()) {
      for (const s of await runSearch(albumName, 20)) {
        if (accepted.length >= wanted) break
        consider(s)
      }
    }
    if (accepted.length < wanted && seedName && primaryArtist) {
      // Broader related search: the seed title (covers related-artist results
      // the suggestion engine missed). Validated by the same quality gate.
      for (const s of await runSearch(seedName, 20)) {
        if (accepted.length >= wanted) break
        consider(s)
      }
    }

    // 3. Language lock: when enough accepted candidates share one language,
    //    drop outliers (covers/tributes are frequently tagged "instrumental").
    //    Unknown language stays valid, and the lock is dropped entirely when it
    //    would gut the pool — unknown != invalid.
    const langVotes = new Map<string, number>()
    for (const s of accepted) {
      const l = songLanguage(s)
      if (l !== 'unknown') langVotes.set(l, (langVotes.get(l) ?? 0) + 1)
    }
    let dominant: string | null = null
    let dominantN = 0
    for (const [l, n] of langVotes) {
      if (n > dominantN) {
        dominant = l
        dominantN = n
      }
    }
    let out = accepted
    if (dominant && dominantN >= 3) {
      const locked = accepted.filter((s) => {
        const l = songLanguage(s)
        return l === dominant || l === 'unknown'
      })
      const floor = Math.min(6, Math.max(1, wanted / 4))
      if (locked.length >= floor) out = locked
    }

    return { total: out.length, start: 0, results: out }
  }

  searchAlbums = (args: SearchAlbumsArgs) => {
    return this.searchAlbumsUseCase.execute(args)
  }

  searchArtists = (args: SearchArtistsArgs) => {
    return this.searchArtistsUseCase.execute(args)
  }

  searchPlaylists = (args: SearchPlaylistsArgs) => {
    return this.searchPlaylistsUseCase.execute(args)
  }

  trendingSongs = () => {
    return this.trendingUseCase.execute()
  }
}
