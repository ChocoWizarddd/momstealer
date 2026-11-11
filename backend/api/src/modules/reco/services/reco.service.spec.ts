import { describe, expect, it, vi } from 'vitest'
import { RecoService } from './reco.service'

function song(id: string, name: string, artist: string) {
  return { id, name, artists: { primary: [{ name: artist }] }, album: { name: `${name} Album` } }
}

describe('RecoService.smartqueue', () => {
  it('merges radio + re-resolved similar with evidence, dedupes seed family', async () => {
    const search = {
      radioSongs: vi.fn(async () => [song('r1', 'Photograph', 'Ed Sheeran'), song('r2', 'Shape of You', 'Ed Sheeran')]),
      searchSongs: vi.fn(async ({ query }: { query: string }) => ({
        results: query.includes('Castle') ? [song('s1', 'Castle on the Hill', 'Ed Sheeran')] : [],
        total: 1
      }))
    }
    const lastfm = {
      enabled: () => true,
      trackSimilar: vi.fn(async () => [
        { title: 'Castle on the Hill', artist: 'Ed Sheeran', match: 8.5, source: 'lastfm' as const },
        { title: 'Perfect', artist: 'Ed Sheeran', match: 9, source: 'lastfm' as const }
      ])
    }
    const svc = new RecoService(search as never, lastfm as never, { recommend: vi.fn(async () => []) } as never)
    const { tracks, evidence } = await svc.smartqueue({
      seedId: 'seed1',
      seedName: 'Perfect',
      artists: 'Ed Sheeran',
      count: 10
    })
    const ids = (tracks as { id: string }[]).map((t) => t.id)
    expect(ids).toContain('r1')
    expect(ids).toContain('s1')
    // Seed family excluded even though Last.fm suggested the seed title.
    expect(ids).not.toContain('seed1')
    expect(evidence.find((e) => e.id === 's1')?.sources).toContain('lastfm')
    expect(evidence.find((e) => e.id === 'r1')?.sources).toContain('radio')
  })

  it('never throws: radio failure yields supplement-only or empty batch', async () => {
    const search = {
      radioSongs: vi.fn(async () => {
        throw new Error('provider down')
      }),
      searchSongs: vi.fn(async () => ({ results: [], total: 0 }))
    }
    const svc = new RecoService(
      search as never,
      { enabled: () => false, trackSimilar: vi.fn() } as never,
      {
        recommend: vi.fn(async () => [])
      } as never
    )
    const { tracks, evidence } = await svc.smartqueue({ seedName: 'Perfect', count: 10 })
    expect(tracks).toEqual([])
    expect(evidence).toEqual([])
  })

  it('drops unresolvable similar tracks and caps count', async () => {
    const radio = Array.from({ length: 30 }, (_, i) => song(`r${i}`, `Radio Song ${i}`, `Artist ${i}`))
    const search = {
      radioSongs: vi.fn(async () => radio),
      searchSongs: vi.fn(async () => ({ results: [], total: 0 }))
    }
    const lastfm = {
      enabled: () => true,
      trackSimilar: vi.fn(async () => [{ title: 'Ghost', artist: 'Nope', match: 5, source: 'lastfm' as const }])
    }
    const svc = new RecoService(search as never, lastfm as never, { recommend: vi.fn(async () => []) } as never)
    const { tracks } = await svc.smartqueue({ seedName: 'X', count: 10 })
    expect((tracks as unknown[]).length).toBeLessThanOrEqual(10)
  })
})
