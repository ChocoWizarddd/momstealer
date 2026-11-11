import process from 'node:process'

type FetchImpl = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

const FLASK_TIMEOUT_MS = 8000

export interface FlaskSearchResponse {
  success: boolean
  data?: any[]
  error?: string
}

export interface FlaskSongResponse {
  success: boolean
  data?: any
  error?: string
}

export interface FlaskLyricsResponse {
  success: boolean
  data?: { lyrics: string | null }
  error?: string
}

export class FlaskJioSaavnProvider {
  private fetchImpl: FetchImpl
  private timeoutMs: number

  constructor(fetchImpl?: FetchImpl, timeoutMs = FLASK_TIMEOUT_MS) {
    this.fetchImpl = fetchImpl ?? ((url, init) => fetch(url, init))
    this.timeoutMs = timeoutMs
  }

  private getBaseUrl(): string {
    return process.env.JIOSAAVN_PROVIDER_URL ?? ''
  }

  enabled(): boolean {
    return this.getBaseUrl().trim().length > 0
  }

  private async fetchJson<T>(
    path: string,
    queryParams: Record<string, string>
  ): Promise<{ status: number; json: T | null }> {
    const baseUrl = this.getBaseUrl()
    if (!baseUrl) return { status: 0, json: null }

    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), this.timeoutMs)
    try {
      const url = new URL(`${baseUrl}${path}`)
      Object.entries(queryParams).forEach(([key, value]) => {
        if (value) url.searchParams.append(key, value)
      })
      const response = await this.fetchImpl(url.toString(), { signal: ctrl.signal })
      let json: T | null = null
      try {
        json = await response.json()
      } catch {
        json = null
      }
      return { status: response.status, json }
    } finally {
      clearTimeout(timer)
    }
  }

  async searchSongs(query: string, lyrics = false, songdata = true): Promise<any[]> {
    if (!this.enabled() || !query.trim()) return []
    const { status, json } = await this.fetchJson<FlaskSearchResponse>('/song/', {
      query,
      lyrics: lyrics.toString(),
      songdata: songdata.toString()
    })
    if (status !== 200 || !json?.success || !json.data) return []
    return json.data
  }

  async getSong(songId: string, lyrics = false): Promise<any | null> {
    if (!this.enabled() || !songId.trim()) return null
    const { status, json } = await this.fetchJson<FlaskSongResponse>('/song/get/', {
      id: songId,
      lyrics: lyrics.toString()
    })
    if (status !== 200 || !json?.success || !json.data) return null
    return json.data
  }

  async getAlbum(albumUrl: string, lyrics = false): Promise<any | null> {
    if (!this.enabled() || !albumUrl.trim()) return null
    const { status, json } = await this.fetchJson<FlaskSongResponse>('/album/', {
      query: albumUrl,
      lyrics: lyrics.toString()
    })
    if (status !== 200 || !json?.success || !json.data) return null
    return json.data
  }

  async getPlaylist(playlistUrl: string, lyrics = false): Promise<any | null> {
    if (!this.enabled() || !playlistUrl.trim()) return null
    const { status, json } = await this.fetchJson<FlaskSongResponse>('/playlist/', {
      query: playlistUrl,
      lyrics: lyrics.toString()
    })
    if (status !== 200 || !json?.success || !json.data) return null
    return json.data
  }

  async getLyrics(query: string): Promise<string | null> {
    if (!this.enabled() || !query.trim()) return null
    const { status, json } = await this.fetchJson<FlaskLyricsResponse>('/lyrics/', {
      query
    })
    if (status !== 200 || !json?.success || !json.data) return null
    return json.data.lyrics ?? null
  }

  async resolveResult(query: string, lyrics = false): Promise<any | null> {
    if (!this.enabled() || !query.trim()) return null
    const { status, json } = await this.fetchJson<FlaskSongResponse>('/result/', {
      query,
      lyrics: lyrics.toString()
    })
    if (status !== 200 || !json?.success || !json.data) return null
    return json.data
  }
}

export const flaskProvider = new FlaskJioSaavnProvider()
