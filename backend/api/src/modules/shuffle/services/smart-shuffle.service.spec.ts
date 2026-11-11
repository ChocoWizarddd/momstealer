import { describe, expect, it } from 'vitest'
import type { ShuffleInput } from '../models'
import { SmartShuffleService } from './smart-shuffle.service'

const svc = new SmartShuffleService()

/** Deterministic PRNG so statistical tests are reproducible. */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = Math.trunc(a)
    a = Math.trunc(a + 0x6d2b79f5)
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const track = (
  id: string,
  title: string,
  artists: string,
  genres: string[] = ['pop'],
  popularity = 0.5
): ShuffleInput => ({ id, title, artists, genres, popularity })

describe('SmartShuffleService', () => {
  it('never repeats a track id (input deduped by id)', () => {
    const tracks = [
      track('1', 'A', 'X'),
      track('1', 'A', 'X'), // same id → same track
      track('2', 'B', 'Y')
    ]
    for (let i = 0; i < 50; i++) {
      const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(i) })
      const ids = out.map((t) => t.id)
      expect(new Set(ids).size).toBe(ids.length)
      expect(ids.length).toBe(2)
    }
  })

  it('same title + different id are DIFFERENT tracks (not deduped)', () => {
    const tracks = [track('ed-1', 'Perfect', 'Ed Sheeran'), track('1d-1', 'Perfect', 'One Direction')]
    const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(1) })
    expect(out.length).toBe(2)
  })

  it('strongly discourages consecutive identical titles', () => {
    const tracks = [
      track('p1', 'Perfect', 'Ed Sheeran'),
      track('p2', 'Perfect', 'One Direction'),
      track('p3', 'Perfect', 'Acoustic Collective'),
      track('ph', 'Photograph', 'Ed Sheeran'),
      track('nc', 'Night Changes', 'One Direction'),
      track('uf', 'Until I Found You', 'Stephen Sanchez'),
      track('da', 'Dandelions', 'Ruth B.')
    ]
    let sameTitleAdjacent = 0
    const runs = 500
    for (let i = 0; i < runs; i++) {
      const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(1000 + i) })
      for (let j = 1; j < out.length; j++) {
        if (svc.titles.isSameTitleFamily(out[j - 1].title, out[j].title)) {
          sameTitleAdjacent++
        }
      }
    }
    // Plain Fisher–Yates expectation: 6 adjacent pairs ×
    // (3/7 × 2/6) ≈ 0.86 same-title adjacencies per queue.
    const mean = sameTitleAdjacent / runs
    expect(mean).toBeLessThan(0.35)
  })

  it('strongly discourages consecutive same-artist tracks', () => {
    const tracks = [
      track('a1', 'Song One', 'Artist A'),
      track('a2', 'Song Two', 'Artist A'),
      track('a3', 'Song Three', 'Artist A'),
      track('a4', 'Song Four', 'Artist A'),
      track('b1', 'Song Five', 'Artist B'),
      track('b2', 'Song Six', 'Artist B'),
      track('b3', 'Song Seven', 'Artist B'),
      track('b4', 'Song Eight', 'Artist B'),
      track('c1', 'Song Nine', 'Artist C'),
      track('c2', 'Song Ten', 'Artist C')
    ]
    let sameArtistAdjacent = 0
    const runs = 500
    for (let i = 0; i < runs; i++) {
      const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(2000 + i) })
      for (let j = 1; j < out.length; j++) {
        if (out[j - 1].artists === out[j].artists) sameArtistAdjacent++
      }
    }
    // Plain shuffle expectation ≈ 9 × 0.289 ≈ 2.6 per queue.
    expect(sameArtistAdjacent / runs).toBeLessThan(1)
  })

  it('scores genre-compatible candidates higher (fixed randomness)', () => {
    const previous = track('s', 'Seed', 'X', ['pop'])
    const score = (genres: string[]) =>
      svc.scoreCandidate(track('c', 'Track', 'Y', genres), previous, {
        rng: () => 0.5
      })
    const dancePop = score(['dance-pop'])
    const indiePop = score(['indie-pop'])
    const rnb = score(['r&b'])
    const rock = score(['rock'])
    const metal = score(['death-metal'])

    expect(dancePop).toBeGreaterThan(rnb)
    expect(indiePop).toBeGreaterThan(rnb)
    expect(rnb).toBeGreaterThan(rock)
    expect(rock).toBeGreaterThan(metal)
  })

  it('first pick continues the previous track genre more often than chance', () => {
    const previous = track('s', 'Seed', 'X', ['rock'])
    const tracks = [
      track('r1', 'Rock One', 'A', ['rock']),
      track('r2', 'Rock Two', 'B', ['rock']),
      track('p1', 'Pop One', 'C', ['pop']),
      track('p2', 'Pop Two', 'D', ['pop']),
      track('j1', 'Jazz One', 'E', ['jazz'])
    ]
    let firstIsRock = 0
    const runs = 400
    for (let i = 0; i < runs; i++) {
      const out = svc.shuffle(tracks, (t) => t, {
        previous,
        rng: mulberry32(10000 + i)
      })
      if (out[0].genres?.[0] === 'rock') firstIsRock++
    }
    // 2 of 5 candidates are rock (40% by chance); genre
    // continuity must beat that.
    expect(firstIsRock / runs).toBeGreaterThan(0.4)
  })

  it('keeps selection probabilistic — every track can lead', () => {
    const tracks = Array.from({ length: 6 }, (_, i) => track(`t${i}`, `Title ${i}`, `Artist ${i}`))
    const first = new Map<string, number>()
    const orders = new Set<string>()
    for (let i = 0; i < 300; i++) {
      const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(3000 + i) })
      first.set(out[0].id, (first.get(out[0].id) ?? 0) + 1)
      orders.add(out.map((t) => t.id).join('>'))
    }
    expect(first.size).toBe(6)
    expect(Math.max(...first.values())).toBeLessThan(300 * 0.5)
    // Not a deterministic sort: many distinct orders occur.
    expect(orders.size).toBeGreaterThan(10)
  })

  it('missing genre metadata does not crash and keeps all tracks', () => {
    const tracks = [
      { id: '1', title: 'A', artists: 'X' },
      { id: '2', title: 'B', artists: 'Y' },
      { id: '3', title: 'C', artists: 'Z' }
    ]
    for (let i = 0; i < 20; i++) {
      const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(5000 + i) })
      expect(out.map((t) => t.id).sort()).toEqual(['1', '2', '3'])
    }
  })

  it('handles 1, 2, 3 and 5-track playlists', () => {
    for (const size of [1, 2, 3, 5]) {
      const tracks = Array.from({ length: size }, (_, i) => track(`s${i}`, `T${i}`, `A${i % 2}`))
      for (let i = 0; i < 10; i++) {
        const out = svc.shuffle(tracks, (t) => t, {
          rng: mulberry32(6000 + size * 100 + i)
        })
        expect(out.length).toBe(size)
        expect(new Set(out.map((t) => t.id)).size).toBe(size)
      }
    }
  })

  it('single-artist and single-genre playlists still terminate', () => {
    const tracks = Array.from({ length: 8 }, (_, i) => track(`u${i}`, `Song ${i}`, 'Solo Artist', ['pop']))
    const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(7000) })
    expect(out.length).toBe(8)
    expect(new Set(out.map((t) => t.id)).size).toBe(8)
  })

  it('penalizes recently played tracks when history exists', () => {
    const previous = track('s', 'Seed', 'X')
    const fresh = track('fresh', 'Fresh Song', 'Y')
    const recent = track('recent', 'Old Song', 'Z')
    const opts = { recentIds: ['recent'] }
    expect(svc.scoreCandidate(recent, previous, opts)).toBeLessThan(svc.scoreCandidate(fresh, previous, opts))
  })

  it('most-recent history is penalized more than older history', () => {
    const previous = track('s', 'Seed', 'X')
    const candidate = track('a', 'Song A', 'Y')
    // Softer penalty so the two recency ranks stay
    // distinguishable above the 0.01 floor.
    const opts = { weights: { recentPlayPenalty: 0.5 } }
    const noHistory = svc.scoreCandidate(candidate, previous, opts)
    const older = svc.scoreCandidate(candidate, previous, {
      ...opts,
      recentIds: ['b', 'a']
    })
    const newer = svc.scoreCandidate(candidate, previous, {
      ...opts,
      recentIds: ['a', 'b']
    })
    expect(newer).toBeLessThan(older)
    expect(older).toBeLessThan(noHistory)
  })

  it('no history → no penalty', () => {
    const previous = track('s', 'Seed', 'X')
    const candidate = track('t', 'Song', 'Y')
    expect(svc.scoreCandidate(candidate, previous, {})).toBe(svc.scoreCandidate(candidate, previous, { recentIds: [] }))
  })

  it('repeated and near-identical titles score below unrelated ones', () => {
    const previous = track('s', 'Perfect', 'X')
    const exact = track('1', 'Perfect', 'Y')
    const near = track('2', 'Perfect 2', 'Y')
    const unrelated = track('3', 'Photograph', 'Y')
    expect(svc.scoreCandidate(exact, previous, {})).toBeLessThan(svc.scoreCandidate(unrelated, previous, {}))
    expect(svc.scoreCandidate(near, previous, {})).toBeLessThan(svc.scoreCandidate(unrelated, previous, {}))
  })

  it('caps oversized pools at maxQueue', () => {
    const tracks = Array.from({ length: 120 }, (_, i) => track(`x${i}`, `Song ${i}`, `Artist ${i % 30}`))
    const out = svc.shuffle(tracks, (t) => t, {
      rng: mulberry32(9000),
      maxQueue: 20
    })
    expect(out.length).toBe(20)
  })

  it('separates a playlist of multiple "Perfect" variants (spec example)', () => {
    const tracks = [
      track('e1', 'Perfect', 'Ed Sheeran'),
      track('o1', 'Perfect', 'One Direction'),
      track('a1', 'Perfect', 'Acoustic Collective'),
      track('e2', 'Photograph', 'Ed Sheeran'),
      track('o2', 'Night Changes', 'One Direction'),
      track('s1', 'Until I Found You', 'Stephen Sanchez'),
      track('r1', 'Dandelions', 'Ruth B.')
    ]
    let variantPairs = 0
    const runs = 400
    for (let i = 0; i < runs; i++) {
      const out = svc.shuffle(tracks, (t) => t, { rng: mulberry32(8000 + i) })
      for (let j = 1; j < out.length; j++) {
        if (svc.titles.isSameTitleFamily(out[j - 1].title, out[j].title)) {
          variantPairs++
        }
      }
    }
    // Plain shuffle expects ≈ 0.86 "Perfect"-"Perfect"
    // adjacencies per queue; the smart shuffle keeps the
    // variants apart far more often.
    expect(variantPairs / runs).toBeLessThan(0.35)
  })
})
