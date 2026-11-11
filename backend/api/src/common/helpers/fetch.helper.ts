import { userAgents, type Endpoints } from '#common/constants'
import type { ApiContextEnum } from '#common/enums'
import { flaskProvider } from '#modules/providers/jiosaavn-flask-provider'

type EndpointValue = (typeof Endpoints)[keyof typeof Endpoints]

interface FetchParams {
  endpoint: EndpointValue
  params: Record<string, string | number>
  context?: ApiContextEnum
}

interface FetchResponse<T> {
  data: T
  ok: Response['ok']
}

const FLASK_SUPPORTED_ENDPOINTS = new Set([
  'song.getDetails',
  'content.getAlbumDetails',
  'playlist.getDetails',
  'lyrics.getLyrics'
])

async function tryFlaskProvider<T>(endpoint: EndpointValue, params: Record<string, string | number>): Promise<FetchResponse<T> | null> {
  if (!flaskProvider.enabled()) return null

  const call = endpoint.toString()
  if (!FLASK_SUPPORTED_ENDPOINTS.has(call)) return null

  try {
    let data: any = null

    if (call === 'song.getDetails') {
      const id = String(params.pids ?? params.id ?? '')
      if (!id) return null
      data = await flaskProvider.getSong(id, false)
    } else if (call === 'content.getAlbumDetails') {
      const albumId = String(params.albumid ?? '')
      if (!albumId) return null
      // Flask expects a URL, construct one from the album ID
      const albumUrl = `https://www.jiosaavn.com/album/_/${albumId}`
      data = await flaskProvider.getAlbum(albumUrl, false)
    } else if (call === 'playlist.getDetails') {
      const listId = String(params.listid ?? '')
      if (!listId) return null
      const playlistUrl = `https://www.jiosaavn.com/s/playlist/_/${listId}`
      data = await flaskProvider.getPlaylist(playlistUrl, false)
    } else if (call === 'lyrics.getLyrics') {
      const lyricsId = String(params.lyrics_id ?? params.id ?? '')
      if (!lyricsId) return null
      const lyrics = await flaskProvider.getLyrics(lyricsId)
      data = { lyrics }
    }

    if (data) {
      return { data: data as T, ok: true }
    }
  } catch {
    // Fall through to direct call
  }

  return null
}

export const useFetch = async <T>({ endpoint, params, context }: FetchParams): Promise<FetchResponse<T>> => {
  // Try Flask provider first for supported endpoints
  const flaskResult = await tryFlaskProvider<T>(endpoint, params)
  if (flaskResult) return flaskResult

  // Fall back to direct JioSaavn API call
  const url = new URL('https://www.jiosaavn.com/api.php')

  url.searchParams.append('__call', endpoint.toString())
  url.searchParams.append('_format', 'json')
  url.searchParams.append('_marker', '0')
  url.searchParams.append('api_version', '4')
  url.searchParams.append('ctx', context || 'web6dot0')

  Object.keys(params).forEach((key) => url.searchParams.append(key, String(params[key])))

  const randomUserAgent = userAgents[Math.floor(Math.random() * userAgents.length)]

  const response = await fetch(url.toString(), {
    headers: { 'Content-Type': 'application/json', 'User-Agent': randomUserAgent },
    signal: AbortSignal.timeout(4000)
  })

  const data = await response.json()

  return { data: data as T, ok: response.ok }
}