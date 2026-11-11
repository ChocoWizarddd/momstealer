import { describe, expect, it } from 'vitest'

import { meetsPlayThreshold, normalizeQuery, PLAY_DEDUPE_WINDOW_MS, rankByVelocity } from './popularity-scoring'
import { PopularityService } from './popularity.service'

describe('normalizeQuery', () => {
  it('lowercases and strips punctuation for aggregation', () => {
    expect(normalizeQuery('  Perfect! ')).toBe('perfect')
    expect(normalizeQuery('Ed   Sheeran Perfect')).toBe('ed sheeran perfect')
    expect(normalizeQuery('')).toBe('')
  })
})

describe('most-searched ordering (§25)', () => {
  it('ranks A(100) > B(50) > C(10) by count', () => {
    const counts = [
      { key: 'a', recent: 100, baseline: 700 },
      { key: 'b', recent: 50, baseline: 350 },
      { key: 'c', recent: 10, baseline: 70 }
    ]
    // Same velocity profile → stable order preserved by volume-independent scoring.
    const ranked = rankByVelocity(counts)
    expect(ranked.map((r) => r.key)).toEqual(['a', 'b', 'c'])
  })

  it('recent velocity lets C outrank older giants (documented formula)', () => {
    const ranked = rankByVelocity([
      { key: 'giant', recent: 30, baseline: 7000 },
      { key: 'c', recent: 20, baseline: 10 }
    ])
    expect(ranked[0].key).toBe('c')
    // giant: 30 / (1000 + 2) ≈ 0.03 ; c: 20 / (1.43 + 2) ≈ 5.8
    expect(ranked[1].key).toBe('giant')
  })

  it('single-event noise never trends', () => {
    expect(rankByVelocity([{ key: 'noise', recent: 1, baseline: 0 }])).toEqual([])
    expect(rankByVelocity([{ key: 'noise', recent: 2, baseline: 0 }])).toEqual([])
  })
})

describe('meetsPlayThreshold (§12/§26)', () => {
  it('screen opens and bare starts never count', () => {
    expect(meetsPlayThreshold(0, 263000)).toBe(false)
    expect(meetsPlayThreshold(5000, 263000)).toBe(false)
    expect(meetsPlayThreshold(14999, 263000)).toBe(false)
  })

  it('30 seconds counts for normal tracks', () => {
    expect(meetsPlayThreshold(30000, 263000)).toBe(true)
    expect(meetsPlayThreshold(120000, 263000)).toBe(true)
  })

  it('50% counts for short tracks under 30s', () => {
    expect(meetsPlayThreshold(20000, 40000)).toBe(true)
    expect(meetsPlayThreshold(16000, 60000)).toBe(false)
  })

  it('garbage input never counts', () => {
    expect(meetsPlayThreshold(Number.NaN, 263000)).toBe(false)
    expect(meetsPlayThreshold(-5, 263000)).toBe(false)
  })
})

describe('PopularityService validation', () => {
  it('rejects empty track identity without touching the database', async () => {
    const service = new PopularityService()
    await expect(
      service.recordPlay({
        userId: 'u1',
        trackId: 'x',
        title: '  ',
        artists: [],
        positionMs: 60000,
        durationMs: 200000
      })
    ).rejects.toThrow()
  })

  it('returns counted:false below threshold without a database write', async () => {
    const service = new PopularityService()
    await expect(
      service.recordPlay({
        userId: 'u1',
        trackId: 'x',
        title: 'Perfect',
        artists: ['Ed Sheeran'],
        positionMs: 5000,
        durationMs: 263000
      })
    ).resolves.toEqual({ counted: false })
  })

  it('exposes the documented dedupe window', () => {
    expect(PLAY_DEDUPE_WINDOW_MS).toBe(60 * 60 * 1000)
  })
})
