import { describe, expect, it, vi } from 'vitest'
import { LastFmClient, MusicBrainzClient, ReccoBeatsClient } from './similarity.service'

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return {
    status,
    headers: { get: (k: string) => headers[k.toLowerCase()] ?? null },
    json: async () => body
  } as unknown as Response
}

describe('LastFmClient', () => {
  it('is disabled without an API key and never calls fetch', async () => {
    const fetchImpl = vi.fn()
    const client = new LastFmClient('', fetchImpl)
    expect(client.enabled()).toBe(false)
    expect(await client.trackSimilar('Cher', 'Believe')).toEqual([])
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('parses track.getSimilar with match scores', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(200, {
        similartracks: {
          track: [
            { name: 'Ray of Light', match: '10.95', artist: { name: 'Madonna' } },
            { name: 'Hung Up', match: '7.2', artist: { name: 'Madonna' } }
          ]
        }
      })
    )
    const client = new LastFmClient('key', fetchImpl)
    expect(client.enabled()).toBe(true)
    const out = await client.trackSimilar('Cher', 'Believe', 2)
    expect(out).toHaveLength(2)
    expect(out[0]).toMatchObject({ title: 'Ray of Light', artist: 'Madonna', source: 'lastfm' })
    expect(out[0].match).toBeCloseTo(10.95)
  })

  it('degrades on invalid key, rate limit, timeout and malformed bodies', async () => {
    const badKey = new LastFmClient(
      'bad',
      vi.fn(async () =>
        jsonResponse(200, {
          error: 10,
          message: 'Invalid API key'
        })
      )
    )
    expect(await badKey.trackSimilar('a', 'b')).toEqual([])

    const limited = new LastFmClient(
      'k',
      vi.fn(async () =>
        jsonResponse(200, {
          error: 29,
          message: 'Rate limit exceeded'
        })
      )
    )
    expect(await limited.trackSimilar('a', 'b')).toEqual([])

    const failing = new LastFmClient(
      'k',
      vi.fn(async () => {
        throw new Error('down')
      })
    )
    expect(await failing.trackSimilar('a', 'b')).toEqual([])

    const garbage = new LastFmClient(
      'k',
      vi.fn(async () => jsonResponse(200, { nope: 1 }))
    )
    expect(await garbage.trackSimilar('a', 'b')).toEqual([])
  })

  it('caches repeat queries', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { similartracks: { track: [] } }))
    const client = new LastFmClient('k', fetchImpl)
    await client.trackSimilar('a', 'b')
    await client.trackSimilar('a', 'b')
    expect(fetchImpl).toHaveBeenCalledTimes(1)
  })
})

describe('ReccoBeatsClient', () => {
  it('returns [] without seed ids and never calls fetch', async () => {
    const fetchImpl = vi.fn()
    expect(await new ReccoBeatsClient(fetchImpl).recommend([], 10)).toEqual([])
    expect(fetchImpl).not.toHaveBeenCalled()
  })

  it('maps recommendation tracks and honors 429 cooldown', async () => {
    let calls = 0
    const fetchImpl = vi.fn(async () => {
      calls++
      if (calls === 1) {
        return jsonResponse(200, {
          content: [{ trackTitle: 'Sunflower', artists: [{ name: 'Post Malone' }] }]
        })
      }
      return jsonResponse(429, {}, { 'retry-after': '1' })
    })
    const client = new ReccoBeatsClient(fetchImpl)
    const out = await client.recommend(['00vJzaoxM3Eja1doBUhX0P'], 10)
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ title: 'Sunflower', artist: 'Post Malone' })
    // 429 path cools down instead of throwing.
    const limited = new ReccoBeatsClient(fetchImpl)
    expect(await limited.recommend(['x'], 10)).toEqual([])
  })

  it('audio-features maps nulls on 404/timeout', async () => {
    const notFound = new ReccoBeatsClient(vi.fn(async () => jsonResponse(404, {})))
    expect(await notFound.audioFeatures(['abc'])).toEqual([null])
    const down = new ReccoBeatsClient(
      vi.fn(async () => {
        throw new Error('x')
      })
    )
    expect(await down.audioFeatures(['abc'])).toEqual([null])
  })

  it('audio-features parses Spotify-style fields', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(200, {
        content: [{ id: 'abc', energy: 0.8, tempo: 120, valence: 0.5, key: 5 }]
      })
    )
    const out = await new ReccoBeatsClient(fetchImpl).audioFeatures(['abc'])
    expect(out[0]).toMatchObject({ id: 'abc', energy: 0.8, tempo: 120, key: 5 })
    expect(out[0]?.danceability).toBeNull()
  })
})

describe('MusicBrainzClient', () => {
  it('returns ISRCs and tags, empty on failure', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(200, {
        recordings: [{ isrcs: ['USX1'], tags: [{ name: 'Pop' }, { name: 'Dance' }] }]
      })
    )
    const out = await new MusicBrainzClient(fetchImpl).recordingIdentity('Believe', 'Cher')
    expect(out.isrcs).toEqual(['USX1'])
    expect(out.tags).toContain('pop')
    const down = new MusicBrainzClient(
      vi.fn(async () => {
        throw new Error('x')
      })
    )
    expect(await down.recordingIdentity('a', 'b')).toEqual({ isrcs: [], tags: [] })
  })
})
