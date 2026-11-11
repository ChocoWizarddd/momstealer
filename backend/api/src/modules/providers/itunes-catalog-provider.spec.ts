import { describe, expect, it } from 'vitest'

import { clearITunesCache, ITunesCatalogProvider } from './itunes-catalog-provider'

/** Shape captured from the live iTunes Search API (stable contract). */
const ITUNES_FIXTURE = {
  resultCount: 2,
  results: [
    {
      trackId: 1193701400,
      trackName: 'Perfect',
      artistName: 'Ed Sheeran',
      collectionName: '÷ (Deluxe)',
      artworkUrl100: 'https://example.com/100x100.jpg',
      artworkUrl600: 'https://example.com/600x600.jpg',
      trackTimeMillis: 263400,
      primaryGenreName: 'Pop',
      trackExplicitness: 'notExplicit',
      releaseDate: '2017-03-03T08:00:00Z'
    },
    {
      artistName: 'No ID Artist'
    }
  ]
}

describe('ITunesCatalogProvider', () => {
  it('maps iTunes tracks to neutral candidates (never playable)', async () => {
    clearITunesCache()
    const provider = new ITunesCatalogProvider(() => Promise.resolve(ITUNES_FIXTURE))
    const tracks = await provider.searchTracks('Perfect', { limit: 10 })
    expect(tracks).toHaveLength(1)
    const [track] = tracks
    expect(track.provider).toBe('itunes')
    expect(track.providerId).toBe('1193701400')
    expect(track.title).toBe('Perfect')
    expect(track.artists).toEqual(['Ed Sheeran'])
    expect(track.album).toBe('÷ (Deluxe)')
    expect(track.durationMs).toBe(263400)
    expect(track.explicit).toBe(false)
    expect(track.year).toBe('2017')
    expect(track.playable).toBeNull()
    expect(track.popularityHint).toBeNull()
    expect(track.providerScore).toBeNull()
  })

  it('bounds limit, blanks and caches within TTL', async () => {
    clearITunesCache()
    let calls = 0
    const provider = new ITunesCatalogProvider(() => {
      calls++
      return Promise.resolve(ITUNES_FIXTURE)
    })
    expect(await provider.searchTracks('   ', { limit: 10 })).toEqual([])
    expect(calls).toBe(0)
    await provider.searchTracks('Perfect', { limit: 99 })
    await provider.searchTracks('Perfect', { limit: 99 })
    expect(calls).toBe(1)
  })

  it('degrades to empty (never throws) when iTunes is down', async () => {
    clearITunesCache()
    const failing = new ITunesCatalogProvider(() => Promise.reject(new Error('boom')))
    await expect(failing.searchTracks('Perfect', { limit: 5 })).resolves.toEqual([])
  })
})
