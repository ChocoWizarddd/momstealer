import type { SongModel } from '#modules/songs/models'
import type { z } from 'zod'

export type SongPayload = z.infer<typeof SongModel>

/**
 * Provider-neutral catalog candidate. Android never sees this shape —
 * providers produce it, NightLight ranking consumes it, and only the
 * normalized SongModel crosses the API boundary.
 */
export interface CatalogTrack {
  provider: 'jiosaavn' | 'itunes'
  /** Stable id within the provider (JioSaavn pid / iTunes trackId). */
  providerId: string
  title: string
  artists: string[]
  album: string
  artwork: string
  /** Milliseconds; 0 when unknown. */
  durationMs: number
  language: string
  explicit: boolean
  year: string
  /**
   * Normalized 0..1 provider-side popularity hint, only when the provider
   * supplies a legitimate signal. Null means "no signal", never fabricated.
   */
  popularityHint: number | null
  /** Retrieval score where the provider supplies one (e.g. autocomplete). */
  providerScore: number | null
  /** Resolved playable payload when available (JioSaavn). Null until resolved. */
  playable: SongPayload | null
}

export interface CatalogSearchOptions {
  /** Max candidates to retrieve from this provider (bounded fanout). */
  limit: number
}

/** SEARCH + METADATA side. Implementations must bound work and never throw transport details. */
export interface MusicCatalogProvider {
  readonly name: 'jiosaavn' | 'itunes'
  searchTracks: (query: string, options: CatalogSearchOptions) => Promise<CatalogTrack[]>
}

/** PLAYBACK side. Only ever yields fully playable NightLight payloads. */
export interface PlaybackProvider {
  resolveById: (id: string) => Promise<SongPayload | null>
  /**
   * Resolves the equivalent playable track for canonical metadata
   * (title + artists + album/version). Returns null — never a wrong track.
   */
  resolveByMetadata: (input: {
    title: string
    artists: string[]
    album?: string
    version?: string
  }) => Promise<SongPayload | null>
}
