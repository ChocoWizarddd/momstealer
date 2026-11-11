import { describe, expect, it } from 'vitest'
import { canonical } from './import.service'

/**
 * Regression tests for the canonical() regex rewrite done for lint
 * compliance (regexp/optimal-quantifier-concatenation,
 * regexp/no-super-linear-backtracking, regexp/prefer-predefined-assertion).
 *
 * The rewrite MUST NOT change matching behavior. These cases pin down the
 * decoration-stripping contract used by playlist-import matching.
 */
describe('canonical() regex regression', () => {
  it('strips parenthetical decorations', () => {
    expect(canonical('Perfect (Acoustic)')).toBe('perfect')
    expect(canonical('Perfect (Remix)')).toBe('perfect')
    expect(canonical('Perfect (Live)')).toBe('perfect')
    expect(canonical('Perfect (Official Music Video)')).toBe('perfect')
    expect(canonical('Perfect (Slowed + Reverb)')).toBe('perfect')
    expect(canonical('Perfect (feat. Anne-Marie)')).toBe('perfect')
  })

  it('strips bracketed decorations', () => {
    expect(canonical('Perfect [Official Video]')).toBe('perfect')
    expect(canonical('Perfect [Acoustic Cover]')).toBe('perfect')
    expect(canonical('Perfect [Lyrics]')).toBe('perfect')
  })

  it('strips multiple bracketed sections', () => {
    expect(canonical('Song (feat. X) [Official Video]')).toBe('song')
    expect(canonical('Song (Remix) (feat. Y)')).toBe('song')
  })

  it('strips feat/ft/featuring segments', () => {
    expect(canonical('Song feat. Someone')).toBe('song')
    expect(canonical('Song ft. Someone')).toBe('song')
    expect(canonical('Song featuring Someone')).toBe('song')
    expect(canonical('Song (ft. Someone)')).toBe('song')
  })

  it('strips Apple-style trailing tags', () => {
    expect(canonical('Perfect - From “Aashiqui 2”')).toBe('perfect')
    expect(canonical('Perfect — Official Video')).toBe('perfect')
    expect(canonical('Kesariya - Brahmastra')).toBe('kesariya brahmastra')
  })

  it('strips version-parentheticals like the production matcher expects', () => {
    // canonical() strips ALL parenthetical content — including version tags
    // like (Taylor's Version) — because import matching is title+artist
    // fuzzy matching, not version-aware ranking.
    expect(canonical("Love (Taylor's Version)")).toBe('love')
  })

  it('normalises case, punctuation and whitespace', () => {
    expect(canonical('  TUM   HI   Ho  ')).toBe('tum hi ho')
    expect(canonical('Chaiyya, Chaiyya!')).toBe('chaiyya chaiyya')
  })

  it('is linear-time on adversarial inputs (no super-linear backtracking)', () => {
    // A pathological title that used to trigger polynomial backtracking on
    // /(.*)\s+(.*)/ style patterns must complete quickly.
    const evil = `x ${'x '.repeat(2000)}(feat. y)`
    const start = Date.now()
    canonical(evil)
    expect(Date.now() - start).toBeLessThan(500)
  })
})
