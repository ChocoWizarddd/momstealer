import { Endpoints } from '#common/constants'
import { ApiContextEnum } from '#common/enums'
import { useFetch } from '#common/helpers'
import { createSongPayload } from '#modules/songs/helpers'
import type { SongAPIResponseModel } from '#modules/songs/models'
import type { z } from 'zod'

/** Minimal shapes from JioSaavn's webapi.getLaunchData (charts / trending). */
interface LaunchItem {
  id?: string
  title?: string
  subtitle?: string
  type?: string
  perma_url?: string
  image?: string
  language?: string
  more_info?: {
    listid?: string
    song_count?: string
    release_date?: string
  }
}

interface LaunchData {
  charts?: LaunchItem[]
  new_trending?: LaunchItem[]
  top_playlists?: LaunchItem[]
}

interface ChartPlaylistResponse {
  id?: string
  title?: string
  list?: z.infer<typeof SongAPIResponseModel>[]
}

const LANGS = 'hindi,english'

type SongPayload = ReturnType<typeof createSongPayload>

export interface TrendingResult {
  /** Real chart songs from JioSaavn's top chart (e.g. "Most Searched Songs"). */
  songs: SongPayload[]
  /** Newly trending albums from the JioSaavn launch screen. */
  albums: Array<{
    id: string
    title: string
    subtitle: string
    image: string
    url: string
    language: string
    year: string
  }>
  /** The available charts themselves (title/id/url/image). */
  charts: Array<{ id: string; title: string; image: string; url: string }>
  chartTitle: string
  fetchedAt: number
}

/**
 * Real trending data straight from JioSaavn's own launch screen: the current
 * charts plus the songs inside the top chart. Never fabricated locally.
 */
export class GetTrendingUseCase {
  private static cache: { data: TrendingResult; at: number } | null = null
  private static readonly TTL_MS = 15 * 60 * 1000

  async execute(): Promise<TrendingResult> {
    const now = Date.now()
    if (GetTrendingUseCase.cache && now - GetTrendingUseCase.cache.at < GetTrendingUseCase.TTL_MS) {
      return GetTrendingUseCase.cache.data
    }

    const { data } = await useFetch<LaunchData>({
      endpoint: Endpoints.launchData,
      params: { lang: LANGS },
      context: ApiContextEnum.ANDROID
    })

    const charts = (data.charts ?? [])
      .filter((c) => c.id)
      .slice(0, 8)
      .map((c) => ({
        id: c.id as string,
        title: c.title || 'Chart',
        image: c.image || '',
        url: c.perma_url || ''
      }))

    const albums = (data.new_trending ?? [])
      .filter((a) => a.type === 'album')
      .slice(0, 10)
      .map((a) => ({
        id: a.id || '',
        title: a.title || '',
        subtitle: a.subtitle || '',
        image: a.image || '',
        url: a.perma_url || '',
        language: a.language || '',
        year: a.more_info?.release_date?.slice(0, 4) || ''
      }))

    // Songs: resolve the first chart so "Trending now" is actual playable music.
    const songs: SongPayload[] = []
    let chartTitle = ''
    const top = charts[0]
    if (top) {
      try {
        const { data: playlist } = await useFetch<ChartPlaylistResponse>({
          endpoint: Endpoints.playlists.id,
          params: { listid: top.id, n: 40, p: 0 }
        })
        chartTitle = playlist?.title || top.title
        const seen = new Set<string>()
        for (const raw of playlist?.list ?? []) {
          if (!raw || typeof raw.id !== 'string' || seen.has(raw.id)) continue
          seen.add(raw.id)
          const song = createSongPayload(raw)
          if (song) songs.push(song)
          if (songs.length >= 40) break
        }
      } catch {
        // Chart resolution failed: songs stay empty, albums/charts still serve.
      }
    }

    const result: TrendingResult = {
      songs,
      albums,
      charts,
      chartTitle,
      fetchedAt: Date.now()
    }
    GetTrendingUseCase.cache = { data: result, at: now }
    return result
  }
}
