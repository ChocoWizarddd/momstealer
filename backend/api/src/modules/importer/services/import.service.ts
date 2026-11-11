import { canonical } from '#common/helpers/text.helper'
import { GetPlaylistByLinkUseCase } from '#modules/playlists/use-cases/get-playlist-by-link'
import { SearchSongsUseCase } from '#modules/search/use-cases'

export { canonical }

/** Hard ceiling on how many source tracks one import may process. */
export const MAX_IMPORT_TRACKS = 200

/** Safety: max continuation hops for YouTube to prevent infinite loops. */
const YT_MAX_CONTINUATION_HOPS = 30

interface PlaylistTrack {
  title: string
  artist?: string
  /** Upstream identity used to deduplicate (e.g. YouTube video id, Spotify URI). */
  key?: string
}

interface FetchedPlaylist {
  source: string
  name: string
  items: PlaylistTrack[]
  /** Total tracks the playlist reports upstream, when known (may exceed fetched). */
  sourceTotal?: number
}

export interface ImportedPlaylist {
  source: string
  playlistName: string
  totalTracks: number
  sourceTotal?: number
  matched: number
  unmatched: string[]
  results: Record<string, unknown>[]
}

/** Optional progress reporter invoked once per processed source track. */
export type ImportProgress = (done: number, total: number, currentTitle: string) => void

/**
 * Resolves public playlist URLs (Spotify / YouTube / Apple Music) and matches
 * each track to a playable song in the music library. No API keys: Spotify's
 * public embed payload and YouTube's own web-client data (INNERTUBE) are used.
 */
export class ImportService {
  private readonly searchSongsUseCase = new SearchSongsUseCase()

  async importPlaylist(
    rawUrl: string,
    limit = MAX_IMPORT_TRACKS,
    onProgress?: ImportProgress
  ): Promise<ImportedPlaylist> {
    const url = this.parseUrl(rawUrl)
    const host = url.hostname
      .replace(/^www\./, '')
      .replace(/^music\./, '')
      .toLowerCase()
    const wanted = Math.min(Math.max(Math.floor(limit) || MAX_IMPORT_TRACKS, 1), MAX_IMPORT_TRACKS)

    let fetched: FetchedPlaylist

    if (host.includes('spotify.com')) {
      fetched = await this.fetchSpotify(url)
    } else if (host.includes('youtube.com') || host.includes('youtu.be')) {
      fetched = await this.fetchYouTube(url, wanted)
    } else if (host.includes('apple.com')) {
      fetched = await this.fetchApple(url)
    } else if (host.includes('jiosaavn.com') || host.includes('saavn.com')) {
      return this.fetchJioSaavn(url, wanted)
    } else {
      throw new Error('Only Spotify, YouTube, Apple Music and JioSaavn playlist links are supported')
    }

    const { source, name, items, sourceTotal } = fetched

    if (items.length === 0) {
      if (source === 'apple-music') {
        throw new Error('Could not read this Apple Music playlist (it may be private or unavailable)')
      }
      throw new Error('No public tracks found — the playlist may be private or unavailable')
    }

    // Trim to ceiling.
    const toMatch = items.slice(0, wanted)

    // Match each source track to a playable library song.
    const results: Record<string, unknown>[] = []
    const unmatched: string[] = []
    const seenIds = new Set<string>()

    let done = 0
    for (const item of toMatch) {
      const best = await this.bestMatch(item.title, item.artist)
      if (best) {
        const songId = String((best as { id: string }).id)
        if (!seenIds.has(songId)) {
          seenIds.add(songId)
          results.push(best as Record<string, unknown>)
        } else {
          // Duplicate library match — report as unmatched to avoid double-counting.
          const label = item.title.trim().slice(0, 120)
          if (!unmatched.includes(label)) unmatched.push(label)
        }
      } else {
        const label = item.title.trim().slice(0, 120)
        if (!unmatched.includes(label)) unmatched.push(label)
      }
      done++
      onProgress?.(done, toMatch.length, item.title)
    }

    return {
      source,
      playlistName: name || `${source} import`,
      totalTracks: toMatch.length,
      sourceTotal,
      matched: results.length,
      unmatched,
      results
    }
  }

  // ---------------------------------------------------------------------------
  // Spotify
  // ---------------------------------------------------------------------------

  private async fetchSpotify(url: URL): Promise<FetchedPlaylist> {
    const id = url.pathname.match(/playlist\/([A-Za-z0-9]+)/)?.[1]
    if (!id) throw new Error('Not a Spotify playlist link')

    const candidates = [`https://open.spotify.com/embed/playlist/${id}`, `https://open.spotify.com/playlist/${id}`]

    // Retry across both page shapes with backoff.
    let html: string | null = null
    for (let attempt = 0; attempt < 4 && html === null; attempt++) {
      if (attempt > 0) await sleep(1500 * attempt)
      try {
        const page = await this.httpGet(candidates[attempt % candidates.length])
        if (page.includes('__NEXT_DATA__')) html = page
      } catch {
        // Upstream error or challenge page — retry.
      }
    }

    if (html === null) {
      throw new Error('Spotify did not return playlist data — try again later')
    }

    const m = html.match(/<script id="__NEXT_DATA__" type="application\/json"[^>]*>(.*?)<\/script>/s)
    if (!m) throw new Error('Spotify page did not contain expected playlist data')

    let data: Record<string, unknown>
    try {
      data = JSON.parse(m[1])
    } catch {
      throw new Error('Spotify returned malformed playlist data')
    }

    const props = data?.props as Record<string, unknown> | undefined
    const pageProps = props?.pageProps as Record<string, unknown> | undefined
    const state = pageProps?.state as Record<string, unknown> | undefined
    const stateData = state?.data as Record<string, unknown> | undefined
    const entity = stateData?.entity as Record<string, unknown> | undefined

    if (!entity) {
      throw new Error('Spotify playlist structure not recognized — the page may have changed')
    }

    const trackList = (Array.isArray(entity.trackList) ? entity.trackList : []) as Record<string, unknown>[]
    const items: PlaylistTrack[] = []
    for (const t of trackList) {
      if (t && typeof t.title === 'string' && t.isPlayable !== false) {
        items.push({
          title: t.title,
          artist: typeof t.subtitle === 'string' ? t.subtitle : undefined,
          key: typeof t.uri === 'string' ? t.uri : undefined
        })
      }
    }

    // Source total from upstream metadata (may exceed what we fetched).
    const sourceTotal =
      typeof entity.totalTracks === 'number'
        ? entity.totalTracks
        : typeof entity.trackCount === 'number'
          ? entity.trackCount
          : undefined

    const name = typeof entity.name === 'string' ? entity.name : 'Spotify playlist'

    return { source: 'spotify', name, items, sourceTotal }
  }

  // ---------------------------------------------------------------------------
  // YouTube / YouTube Music
  // ---------------------------------------------------------------------------

  private async fetchYouTube(url: URL, wanted: number): Promise<FetchedPlaylist> {
    const list = url.searchParams.get('list')
    if (!list) throw new Error('Not a YouTube playlist link — the URL must contain ?list=')

    // Reject radio mixes / watch-later (not real playlists).
    if (/^(?:RD|UL|LM|WL|MM)/.test(list)) {
      throw new Error('This YouTube link is a mix or radio — use a saved playlist link instead')
    }

    // Fetch the playlist page. YouTube serves ytInitialData as inline JSON.
    const html = await this.httpGet(`https://www.youtube.com/playlist?list=${encodeURIComponent(list)}`)
    const key = html.match(/"INNERTUBE_API_KEY":"([^"]+)"/)?.[1]
    const clientVersion = html.match(/"INNERTUBE_CLIENT_VERSION":"([^"]+)"/)?.[1] ?? '2.20241001.00.00'

    const initialRaw = html.match(/var ytInitialData = (\{.*?\});<\/script>/s)?.[1]
    const initial = safeJson(initialRaw)

    if (!initial) throw new Error('YouTube did not return playlist data')

    const items = collectYtItems(initial)
    let name = findYtPlaylistTitle(initial) ?? parseTitleFromHtml(html) ?? 'YouTube playlist'
    let continuation = findYtContinuation(initial)

    // Parse item count from header.
    const sourceTotal = findYtItemCount(initial)

    // If HTML gave items directly, great — otherwise fall through to INNERTUBE browse.
    if (items.length === 0 && key) {
      const browseResp = await this.ytBrowse(key, clientVersion, `VL${list}`)
      items.push(...collectYtItems(browseResp))
      continuation = findYtContinuation(browseResp)
      if (!name || name === 'YouTube playlist') {
        name = findYtPlaylistTitle(browseResp) ?? name
      }
    }

    // Paginate via continuation tokens.
    const seenVideoIds = new Set<string>()
    for (const item of items) {
      if (item.key) seenVideoIds.add(item.key)
    }
    const visitedTokens = new Set<string>()

    let hop = 0
    while (continuation && items.length < wanted && hop < YT_MAX_CONTINUATION_HOPS) {
      if (visitedTokens.has(continuation)) break // safety: already visited
      visitedTokens.add(continuation)
      hop++

      const page = await this.ytBrowse(key!, clientVersion, undefined, continuation)
      const batch = collectYtItems(page)
      let added = 0
      for (const item of batch) {
        if (items.length >= wanted) break
        if (item.key && seenVideoIds.has(item.key)) continue
        if (item.key) seenVideoIds.add(item.key)
        items.push(item)
        added++
      }
      if (added === 0) break // no new items — playlist exhausted
      continuation = findYtContinuation(page)
    }

    return { source: 'youtube', name, items, sourceTotal }
  }

  private ytBrowse(apiKey: string, clientVersion: string, browseId?: string, continuation?: string): Promise<unknown> {
    const body: Record<string, unknown> = {
      context: { client: { clientName: 'WEB', clientVersion, hl: 'en' } },
      racyCheckOk: true
    }
    if (browseId) body.browseId = browseId
    if (continuation) body.continuation = continuation
    return this.httpJson(`https://www.youtube.com/youtubei/v1/browse?key=${encodeURIComponent(apiKey)}`, body)
  }

  // ---------------------------------------------------------------------------
  // Apple Music
  // ---------------------------------------------------------------------------

  private async fetchApple(url: URL): Promise<FetchedPlaylist> {
    const html = await this.httpGet(url.toString())

    // Check for explicit 404 / privacy page.
    if (
      /<title[^>]*>\s*Not Found\s*<\/title>/i.test(html) ||
      /<h1[^>]*>.*?404.*?<\/h1>/i.test(html) ||
      /class="[^"]*error[^"]*"[^>]*>.*?not found/i.test(html)
    ) {
      throw new Error('This Apple Music playlist is private or unavailable')
    }

    // Strategy 1: schema.org JSON-LD (MusicPlaylist).
    const ldBlocks = html.matchAll(/<script type="application\/ld\+json">(.*?)<\/script>/gs)
    for (const b of ldBlocks) {
      try {
        const doc = JSON.parse(b[1].trim())
        const playlist = Array.isArray(doc)
          ? doc.find((x: Record<string, unknown>) => x?.['@type'] === 'MusicPlaylist')
          : doc
        if (!playlist || playlist['@type'] !== 'MusicPlaylist') continue

        const rawTracks = (Array.isArray(playlist.track) ? playlist.track : []) as Array<{
          name?: string
          byArtist?: { name?: string }[] | { name?: string }
        }>
        const items: PlaylistTrack[] = []
        for (const t of rawTracks) {
          if (typeof t?.name !== 'string' || !t.name.trim()) continue
          let artist: string | undefined
          if (Array.isArray(t.byArtist)) {
            artist = t.byArtist[0]?.name
          } else if (t.byArtist && typeof t.byArtist === 'object') {
            artist = (t.byArtist as { name?: string }).name
          }
          if (typeof artist !== 'string') artist = undefined
          items.push({ title: t.name, artist })
        }
        if (items.length) {
          return {
            source: 'apple-music',
            name: String(playlist.name ?? 'Apple Music playlist'),
            items,
            sourceTotal: items.length
          }
        }
      } catch {
        // Try the next JSON-LD block.
      }
    }

    // Strategy 2: embedded serialized-server-data JSON.
    const ssdMatch = html.match(/<script type="application\/json" id="serialized-server-data"[^>]*>(.*?)<\/script>/s)
    if (ssdMatch) {
      try {
        const parsed = JSON.parse(ssdMatch[1].trim())
        const items = extractAppleFromSerialized(parsed)
        if (items.length) {
          return { source: 'apple-music', name: 'Apple Music playlist', items, sourceTotal: items.length }
        }
      } catch {
        // Fall through.
      }
    }

    throw new Error('Could not read this Apple Music playlist (it may be private or unavailable)')
  }

  // ---------------------------------------------------------------------------
  // JioSaavn (already in library — no re-matching)
  // ---------------------------------------------------------------------------

  private async fetchJioSaavn(url: URL, wanted: number): Promise<ImportedPlaylist> {
    const token = url.pathname.split('/').findLast(Boolean)
    if (!token) throw new Error('Not a JioSaavn playlist link')

    const useCase = new GetPlaylistByLinkUseCase()
    const items: Record<string, unknown>[] = []
    let name = 'JioSaavn playlist'

    for (let page = 0; page < 4 && items.length < wanted; page++) {
      const batch = await useCase.execute({ token, limit: Math.min(wanted, 50), page })
      const songs = (batch?.songs ?? []) as Record<string, unknown>[]
      if (batch?.name) name = String(batch.name)
      if (songs.length === 0) break
      items.push(...songs)
    }

    if (items.length === 0) throw new Error('JioSaavn returned no songs for this playlist')

    const sliced = items.slice(0, wanted)
    return {
      source: 'jiosaavn',
      playlistName: name,
      totalTracks: sliced.length,
      matched: sliced.length,
      unmatched: [],
      results: sliced
    }
  }

  // ---------------------------------------------------------------------------
  // Matching
  // ---------------------------------------------------------------------------

  private async bestMatch(title: string, artist?: string): Promise<unknown | null> {
    const wantTitle = canonical(title)
    const wantArtist = canonical(artist ?? '')
    if (!wantTitle) return null

    const pick = (songs: unknown[]): { best: unknown | null; score: number } => {
      let best: unknown | null = null
      let bestScore = 0
      for (const s of songs) {
        const song = s as {
          name?: string
          artists?: { primary?: { name?: string }[] }
        }
        const gotTitle = canonical(song.name ?? '')
        const gotArtist = canonical(song.artists?.primary?.[0]?.name ?? '')
        const sc = scoreMatch(wantTitle, wantArtist, gotTitle, gotArtist)
        if (sc > bestScore) {
          bestScore = sc
          best = s
        }
      }
      return { best, score: bestScore }
    }

    // Pass 1: combined title + artist query against a broader pool.
    const query1 = [title, artist].filter(Boolean).join(' ').trim().slice(0, 120)
    if (query1) {
      try {
        const res = await this.searchSongsUseCase.execute({ query: query1, page: 0, limit: 15 })
        const songs = res?.results ?? []
        if (songs.length > 0) {
          const { best, score } = pick(songs)
          if (score >= 4) return best
        }
      } catch {
        // Fall through to pass 2.
      }
    }

    // Pass 2: title-only query. Require artist agreement when artist is known.
    try {
      const query2 = wantTitle.slice(0, 120)
      const res = await this.searchSongsUseCase.execute({ query: query2, page: 0, limit: 15 })
      const songs = res?.results ?? []
      if (songs.length === 0) return null
      const { best, score } = pick(songs)
      if (score < 4) return null
      // Enforce artist agreement when source artist is known.
      if (wantArtist && best) {
        const gotArtist = canonical(
          (best as { artists?: { primary?: { name?: string }[] } }).artists?.primary?.[0]?.name ?? ''
        )
        if (gotArtist && !artistAgrees(wantArtist, gotArtist)) return null
      }
      return best
    } catch {
      return null
    }
  }

  // ---------------------------------------------------------------------------
  // URL parsing
  // ---------------------------------------------------------------------------

  private parseUrl(rawUrl: string): URL {
    try {
      return new URL(rawUrl)
    } catch {
      throw new Error('Invalid URL — please paste a valid playlist link')
    }
  }

  // ---------------------------------------------------------------------------
  // HTTP helpers
  // ---------------------------------------------------------------------------

  private httpGet(url: string): Promise<string> {
    return new Promise((resolve, reject) => {
      fetch(url, {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36',
          'Accept-Language': 'en'
        },
        signal: AbortSignal.timeout(25_000)
      })
        .then((r) => (r.ok ? r.text() : Promise.reject(new Error(`Upstream HTTP ${r.status}`))))
        .then(resolve)
        .catch(reject)
    })
  }

  private httpJson(url: string, body: unknown): Promise<unknown> {
    return new Promise((resolve, reject) => {
      fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/126'
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(25_000)
      })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`YouTube HTTP ${r.status}`))))
        .then(resolve)
        .catch(reject)
    })
  }
}

// =============================================================================
// Canonicalisation and scoring
// =============================================================================

/** Title + artist match score (higher = better). Max ~7. */
function scoreMatch(wantTitle: string, wantArtist: string, gotTitle: string, gotArtist: string): number {
  let score = 0
  if (!wantTitle || !gotTitle) return 0

  if (wantTitle === gotTitle) score += 5
  else if (wantTitle.includes(gotTitle) || gotTitle.includes(wantTitle)) score += 3
  else {
    // Token overlap (Jaccard-like).
    const wTokens = new Set(wantTitle.split(' '))
    const gTokens = new Set(gotTitle.split(' '))
    let overlap = 0
    for (const t of wTokens) if (gTokens.has(t)) overlap++
    const union = new Set([...wTokens, ...gTokens]).size
    const ratio = union > 0 ? overlap / union : 0
    if (ratio >= 0.9) score += 4
    else if (ratio >= 0.7) score += 3
    else if (ratio >= 0.5) score += 2
    else if (ratio >= 0.3) score += 1
  }

  if (wantArtist && gotArtist) {
    if (wantArtist === gotArtist) score += 2
    else if (wantArtist.includes(gotArtist) || gotArtist.includes(wantArtist)) score += 1
    else if (artistAgrees(wantArtist, gotArtist)) score += 1
  }

  return score
}

/** Check if two canonicalised artist strings share meaningful overlap. */
function artistAgrees(a: string, b: string): boolean {
  if (!a || !b) return true // unknown artist is permissive
  const tokensA = new Set(
    a
      .split(/\s*[&,/]\s*/)
      .flatMap((s) => s.split(' '))
      .filter(Boolean)
  )
  const tokensB = new Set(
    b
      .split(/\s*[&,/]\s*/)
      .flatMap((s) => s.split(' '))
      .filter(Boolean)
  )
  let overlap = 0
  for (const t of tokensA) if (tokensB.has(t)) overlap++
  // Require at least one significant word in common.
  return overlap > 0
}

// =============================================================================
// YouTube helpers
// =============================================================================

function safeJson(raw?: string): unknown {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/** Collect playlistVideoRenderer items recursively. */
function collectYtItems(o: unknown): PlaylistTrack[] {
  const out: PlaylistTrack[] = []
  const walk = (node: unknown): void => {
    if (Array.isArray(node)) {
      for (const n of node) walk(n)
      return
    }
    if (!node || typeof node !== 'object') return
    const rec = node as Record<string, unknown>
    const pvr = rec.playlistVideoRenderer as Record<string, unknown> | undefined
    if (pvr && typeof pvr.videoId === 'string') {
      const runs = ((pvr.title as Record<string, unknown>)?.runs as { text?: string }[]) ?? []
      const title = runs.map((r) => r.text ?? '').join('')
      const by = ((pvr.shortBylineText as Record<string, unknown>)?.runs as { text?: string }[]) ?? []
      const channel = by.map((r) => r.text ?? '').join('')
      if (title) out.push({ title, artist: channel, key: pvr.videoId })
    }
    for (const v of Object.values(rec)) walk(v)
  }
  walk(o)
  return out
}

/** Find continuation token in ytInitialData / browse response. */
function findYtContinuation(o: unknown): string | null {
  if (Array.isArray(o)) {
    for (const n of o) {
      const t = findYtContinuation(n)
      if (t) return t
    }
    return null
  }
  if (!o || typeof o !== 'object') return null
  const rec = o as Record<string, unknown>
  const cmd = rec.continuationCommand as Record<string, unknown> | undefined
  if (cmd && typeof cmd.token === 'string') return cmd.token
  const token = rec.token as string | undefined
  if (typeof token === 'string' && rec.context) return token
  for (const v of Object.values(rec)) {
    const t = findYtContinuation(v)
    if (t) return t
  }
  return null
}

/** Extract playlist title from ytInitialData. */
function findYtPlaylistTitle(o: unknown): string | null {
  if (!o || typeof o !== 'object') return null
  const rec = o as Record<string, unknown>
  // Try: metadata.playlistMetadataRenderer.title
  const meta = rec.metadata as Record<string, unknown> | undefined
  const pmr = meta?.playlistMetadataRenderer as Record<string, unknown> | undefined
  if (typeof pmr?.title === 'string') return pmr.title
  // Try: header.playlistHeaderRenderer.title.simpleText / runs
  const hdr = rec.header as Record<string, unknown> | undefined
  const phr = hdr?.playlistHeaderRenderer as Record<string, unknown> | undefined
  if (typeof phr?.title === 'string') return phr.title
  const titleRuns = phr?.title as Record<string, unknown> | undefined
  if (Array.isArray(titleRuns?.runs)) {
    return (titleRuns!.runs as { text?: string }[]).map((r) => r.text ?? '').join('')
  }
  return null
}

/** Try to extract item count from ytInitialData. */
function findYtItemCount(o: unknown): number | undefined {
  if (!o || typeof o !== 'object') return undefined
  const rec = o as Record<string, unknown>
  const hdr = rec.header as Record<string, unknown> | undefined
  const phr = hdr?.playlistHeaderRenderer as Record<string, unknown> | undefined
  if (!phr) return undefined
  // numVideosText.runs[0].text like "100 videos"
  const nvt = phr.numVideosText as Record<string, unknown> | undefined
  if (nvt?.runs && Array.isArray(nvt.runs)) {
    const text = (nvt.runs as { text?: string }[])[0]?.text ?? ''
    const match = text.match(/([\d,]+)/)
    if (match) return Number.parseInt(match[1].replaceAll(',', ''), 10)
  }
  // Also check stats array.
  const stats = phr.stats as Array<{ runs?: { text?: string }[] }> | undefined
  if (Array.isArray(stats)) {
    for (const s of stats) {
      const text = s?.runs?.[0]?.text ?? ''
      const match = text.match(/([\d,]+)\s*(?:videos|tracks)/i)
      if (match) return Number.parseInt(match[1].replaceAll(',', ''), 10)
    }
  }
  return undefined
}

function parseTitleFromHtml(html: string): string | null {
  const m = html.match(/<title>(.*?)<\/title>/s)
  if (!m) return null
  return (
    m[1]
      .replace(/\s*[-–]\s*YouTube\s*$/i, '')
      .trim()
      .slice(0, 200) || null
  )
}

// =============================================================================
// Apple Music helpers
// =============================================================================

function extractAppleFromSerialized(data: unknown): PlaylistTrack[] {
  const items: PlaylistTrack[] = []
  const seen = new Set<string>()
  const walk = (node: unknown): void => {
    if (Array.isArray(node)) {
      for (const n of node) walk(n)
      return
    }
    if (!node || typeof node !== 'object') return
    const rec = node as Record<string, unknown>
    // Apple's serialized-server-data uses { attributes: { name, artistName } } for tracks.
    const attrs = rec.attributes as Record<string, unknown> | undefined
    if (attrs && typeof attrs.name === 'string' && typeof attrs.artistName === 'string') {
      const key = `${attrs.name}|||${attrs.artistName}`
      if (!seen.has(key)) {
        seen.add(key)
        items.push({ title: attrs.name, artist: attrs.artistName })
      }
    }
    for (const v of Object.values(rec)) walk(v)
  }
  walk(data)
  return items
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
