import { Endpoints } from '#common/constants'
import { useFetch } from '#common/helpers'
import { canonicalKey, performerArtistsOf, sameRecording } from '#modules/providers/identity'
import { detectVersion } from '#modules/search/services/search-ranker'
import { createSongPayload } from '#modules/songs/helpers'
import { GetSongByIdUseCase } from '#modules/songs/use-cases'
import type { PlaybackProvider, SongPayload } from '#modules/providers/music-providers'
import type { SearchSongAPIResponseModel } from '#modules/search/models'
import type { z } from 'zod'

/**
 * JioSaavn playback resolution — the only full-track path (§8).
 * resolveById: direct details lookup. resolveByMetadata: bounded catalog
 * search + canonical/version ranking, then details. Returns null (never a
 * wrong track) when nothing matches.
 */
export class JioSaavnPlaybackProvider implements PlaybackProvider {
  private readonly songById = new GetSongByIdUseCase()

  async resolveById(id: string): Promise<SongPayload | null> {
    if (!id) return null
    try {
      const songs = await this.songById.execute({ songIds: id })
      const song = songs.find((s) => s.id === id) ?? songs[0]
      return song?.downloadUrl?.length ? song : null
    } catch {
      return null
    }
  }

  async resolveByMetadata(input: {
    title: string
    artists: string[]
    album?: string
    version?: string
  }): Promise<SongPayload | null> {
    const title = (input.title ?? '').trim()
    if (!title) return null
    const artistQuery = (input.artists ?? []).filter(Boolean).slice(0, 2).join(' ')
    const queries = artistQuery ? [`${title} ${artistQuery}`, title] : [title]
    const wantedVersion = (input.version ?? 'original').toLowerCase()

    for (const query of queries) {
      try {
        const { data } = await useFetch<z.infer<typeof SearchSongAPIResponseModel>>({
          endpoint: Endpoints.search.songs,
          params: { q: query, p: 0, n: 15 }
        })
        const pool = data.results?.map(createSongPayload) ?? []
        const match = this.pickCanonical(pool, {
          title,
          artists: input.artists ?? [],
          album: input.album ?? '',
          version: wantedVersion
        })
        if (match) {
          const resolved = await this.resolveById(match.id)
          if (resolved) return resolved
        }
      } catch {
        // Next rung / null.
      }
    }
    return null
  }

  private pickCanonical(
    pool: SongPayload[],
    want: { title: string; artists: string[]; album: string; version: string }
  ): SongPayload | null {
    // Exact canonical identity first (title+artists+version via sameRecording).
    for (const song of pool) {
      if (
        sameRecording(
          { title: want.title, artists: want.artists, version: want.version, album: want.album },
          {
            title: song.name,
            artists: performerArtistsOf(song),
            version: detectVersion(song.name),
            album: song.album?.name ?? '',
            durationMs: typeof song.duration === 'number' ? song.duration * 1000 : 0
          }
        ) &&
        song.downloadUrl?.length
      ) {
        return song
      }
    }
    // Fallback: same canonical key (title+artists) with an original version.
    const wantKey = canonicalKey(want.title, want.artists)
    for (const song of pool) {
      if (
        canonicalKey(song.name, performerArtistsOf(song)) === wantKey &&
        detectVersion(song.name) === 'original' &&
        song.downloadUrl?.length
      ) {
        return song
      }
    }
    return null
  }
}
