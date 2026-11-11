import { describe, expect, it } from 'vitest'
import { detectVersion } from './search-ranker'

/**
 * Regression: the version-detection regexes were rewritten from capturing
 * groups (...) to non-capturing groups (?:...) for lint compliance.
 * Matching behavior MUST be identical.
 */
describe('detectVersion regex regression (capturing -> non-capturing)', () => {
  const cases: Array<[string, string]> = [
    ['Perfect (Acoustic)', 'acoustic'],
    ['Perfect - Unplugged', 'acoustic'],
    ['Perfect (Live)', 'live'],
    ['Perfect (Remix)', 'remix'],
    ['Perfect (Instrumental)', 'instrumental'],
    ['Perfect (Piano Version)', 'piano'],
    ['Perfect (Cover)', 'cover'],
    ['Perfect (Sped Up)', 'sped'],
    ['Perfect (Slowed + Reverb)', 'slowed'],
    ['Perfect Reverb', 'reverb'],
    ['Perfect (Radio Edit)', 'edit'],
    ['Perfect (Edit)', 'edit'],
    ['Perfect (Demo)', 'demo'],
    ['Perfect (Orchestral)', 'orchestral'],
    ['Symphony No. 5', 'orchestral'],
    ['Perfect (Karaoke)', 'karaoke'],
    ['Perfect (Nightcore)', 'nightcore'],
    ['Perfect', 'original'],
    ['Perfect Love Song', 'original'],
    ['The Mastered Version', 'original']
  ]
  for (const [title, expected] of cases) {
    it(`"${title}" -> ${expected}`, () => {
      expect(detectVersion(title)).toBe(expected)
    })
  }
})
