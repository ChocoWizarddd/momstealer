/**
 * Normalized recommendation data contracts (§24).
 *
 * TrackIdentity: stable cross-provider identity used to map external
 * similarity (ReccoBeats/Last.fm/MusicBrainz) back to playable JioSaavn rows.
 * TrackFeatures: cached audio-feature snapshot; missing features stay null
 * (neutral/unknown handling downstream — never zero-filled).
 * RecommendationEvidence: per-candidate source trail returned to Android,
 * which remains authoritative for final ranking.
 */

export interface TrackIdentity {
  provider: 'jiosaavn' | 'itunes' | 'reccobeats' | 'lastfm' | 'musicbrainz'
  providerTrackId: string
  normalizedTitle: string
  normalizedArtist: string
  album: string | null
  language: string | null
  genres: string[]
  externalIds: {
    reccoBeatsId?: string | null
    spotifyId?: string | null
    mbid?: string | null
    isrcs?: string[]
  }
}

export interface TrackFeatures {
  trackId: string
  tempo: number | null
  energy: number | null
  danceability: number | null
  valence: number | null
  acousticness: number | null
  instrumentalness: number | null
  speechiness: number | null
  loudness: number | null
  key: number | null
  /** 0..1 confidence in this snapshot (source agreement/recency). */
  confidence: number
  source: 'reccobeats' | 'none'
  fetchedAt: number
}

export interface RecommendationEvidence {
  id: string | null
  title: string
  artist: string
  sources: string[]
  /** 0..1 combined evidence strength. */
  score: number
  /** Human-readable why (surfaced in Android SMART_RECO logs). */
  reason: string
}

/** Feature ranges for normalization before comparison (0..1 unless noted). */
export const FEATURE_RANGES = {
  tempo: { min: 0, max: 250 },
  loudness: { min: -60, max: 0 },
  key: { min: -1, max: 11 }
} as const
