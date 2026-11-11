import { describe, expect, it } from 'vitest'
import { GenreService } from './genre.service'

const svc = new GenreService()

describe('GenreService', () => {
  it('normalizes genre labels to a canonical form', () => {
    expect(svc.normalizeGenre('Dance-Pop')).toBe('dance pop')
    expect(svc.normalizeGenre('R&B')).toBe('r and b')
    expect(svc.normalizeGenre('  Hip  Hop ')).toBe('hip hop')
    expect(svc.normalizeGenre('Indie-Pop!')).toBe('indie pop')
  })

  it('ranks similarity: sibling > medium > medium-low > very low', () => {
    const popDance = svc.genreSimilarity('pop', 'dance-pop')
    const popIndie = svc.genreSimilarity('pop', 'indie-pop')
    const popRnb = svc.genreSimilarity('pop', 'r&b')
    const popRock = svc.genreSimilarity('pop', 'rock')
    const popMetal = svc.genreSimilarity('pop', 'death-metal')

    expect(popDance).toBeGreaterThan(popRnb) // high > medium
    expect(popIndie).toBeGreaterThan(popRnb) // high > medium
    expect(popRnb).toBeGreaterThan(popRock) // medium > medium/low
    expect(popRock).toBeGreaterThan(popMetal) // medium/low > very low
    expect(popDance).toBe(0.85)
    expect(popMetal).toBe(0.15)
  })

  it('identical genres score 1; missing metadata is neutral', () => {
    expect(svc.genreSimilarity('pop', 'Pop')).toBe(1)
    expect(svc.genreSimilarity('', 'pop')).toBe(0.5)
    expect(svc.genreSimilarity('pop', '')).toBe(0.5)
  })

  it('unknown genres only match themselves', () => {
    expect(svc.genreSimilarity('vaporwave', 'vaporwave')).toBe(1)
    expect(svc.genreSimilarity('vaporwave', 'pop')).toBe(0.15)
  })

  it('multi-genre tracks match on their strongest genre', () => {
    expect(svc.trackGenreSimilarity(['rock', 'pop'], ['metal'])).toBe(0.7)
    expect(svc.trackGenreSimilarity(['pop'], ['metal'])).toBe(0.15)
    // electronic↔house is same-family (0.85) even
    // though the track's other genre (pop) is only
    // cross-family similar to house (0.55).
    expect(svc.trackGenreSimilarity(['electronic', 'pop'], ['house'])).toBe(0.85)
  })

  it('missing genre lists are neutral, never zero', () => {
    expect(svc.trackGenreSimilarity([], ['pop'])).toBe(0.5)
    expect(svc.trackGenreSimilarity(undefined, undefined)).toBe(0.5)
  })

  it('fallback classification derives genres from existing metadata', () => {
    expect(svc.fallbackGenres({ language: 'Hindi' })).toEqual(['hindi'])
    expect(svc.fallbackGenres({ language: 'ENGLISH', origin: 'IN' })).toEqual(['english', 'in'])
    expect(svc.fallbackGenres({})).toEqual([])
  })
})
