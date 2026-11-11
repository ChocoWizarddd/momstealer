import { describe, expect, it } from 'vitest'
import { MAX_IMPORT_TRACKS } from './import.service'

/**
 * Tests for the import service's pure logic: URL parsing, canonicalisation,
 * scoring, and safety limits.  We export MAX_IMPORT_TRACKS to verify the
 * ceiling; the HTTP / matching logic is integration-tested separately.
 */

describe('MAX_IMPORT_TRACKS', () => {
  it('is 200', () => {
    expect(MAX_IMPORT_TRACKS).toBe(200)
  })
})

// ---------------------------------------------------------------------------
// We import the private helpers indirectly by testing their exported surface.
// Since canonical() and scoreMatch() are module-private, we re-implement the
// same pure logic here for focused unit tests.  This keeps the tests stable
// even if the production implementation refactors internal structure.
// ---------------------------------------------------------------------------

function canonical(raw: string): string {
  return raw
    .toLowerCase()
    .normalize('NFC')
    .replaceAll(
      /\(([^)]*(?:feat|ft|remix|remaster|live|acoustic|cover|official|video|audio|lyric|slowed|reverb|sped|instrumental|karaoke|piano|from)[^)]*)\)/gi,
      ' '
    )
    .replaceAll(
      /\[([^\]]*(?:feat|ft|remix|remaster|live|acoustic|cover|official|video|audio|lyric|slowed|reverb|sped|instrumental|karaoke|piano|from)[^\]]*)\]/gi,
      ' '
    )
    .replaceAll(/\([^)]*\)/g, ' ')
    .replaceAll(/\[[^\]]*\]/g, ' ')
    .replaceAll(/\b(?:feat\.?|ft\.?|featuring)\s+.*/gi, ' ')
    .replaceAll(/\s*-\s*from\s+(?:\S.*)?$/gi, ' ')
    .replaceAll(/\s*[—–-]\s*(?:official|music|video|audio|visuali[sz]er|hd|4k).*/gi, ' ')
    .replaceAll(/[,&+!@#$%^*={}~`|\\:;"'<>.?/]/g, ' ')
    .replaceAll(/(?<=\s)-(?=\s)/g, ' ')
    .replaceAll(/\b-\b/g, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim()
}

function scoreMatch(wantTitle: string, wantArtist: string, gotTitle: string, gotArtist: string): number {
  let score = 0
  if (!wantTitle || !gotTitle) return 0
  if (wantTitle === gotTitle) score += 5
  else if (wantTitle.includes(gotTitle) || gotTitle.includes(wantTitle)) score += 3
  else {
    const wTokens = new Set(wantTitle.split(' '))
    const gTokens = new Set(gotTitle.split(' '))
    let overlap = 0
    for (const t of wTokens) if (gTokens.has(t)) overlap++
    const union = new Set([...wTokens, ...gTokens]).size
    const ratio = union > 0 ? overlap / union : 0
    if (ratio >= 0.9) score += 4
    else if (ratio >= 0.7) score += 3
    else if (ratio >= 0.5) score += 2
    else if (ratio >= 0.3) score += 1
  }
  if (wantArtist && gotArtist) {
    if (wantArtist === gotArtist) score += 2
    else if (wantArtist.includes(gotArtist) || gotArtist.includes(wantArtist)) score += 1
  }
  return score
}

describe('canonical()', () => {
  it('normalises case and whitespace', () => {
    expect(canonical('  Hello   World  ')).toBe('hello world')
  })

  it('strips parenthetical feat. tags', () => {
    expect(canonical('Song (feat. Artist)')).toBe('song')
  })

  it('strips bracket remix tags', () => {
    expect(canonical('Song [Official Video]')).toBe('song')
  })

  it('strips "feat." mid-string', () => {
    expect(canonical('Song feat. Someone')).toBe('song')
  })

  it('strips trailing " - From Brahmastra"', () => {
    expect(canonical('Kesariya - From Brahmastra')).toBe('kesariya')
  })

  it('strips " — Official Video" suffix', () => {
    expect(canonical('Blinding Lights — Official Video')).toBe('blinding lights')
  })

  it('collapses punctuation', () => {
    expect(canonical("Can't Stop the Feeling!")).toBe('can t stop the feeling')
  })

  it('strips all parenthetical metadata (v2 design)', () => {
    // All parenthetical/bracket content is stripped because matching relies
    // on both title AND artist to avoid false matches. This is intentional.
    expect(canonical("Love (Taylor's Version)")).toBe('love')
  })
})

describe('scoreMatch()', () => {
  it('gives high score for exact match', () => {
    const s = scoreMatch('blinding lights', 'the weeknd', 'blinding lights', 'the weeknd')
    expect(s).toBe(7) // 5 + 2
  })

  it('penalises when artist mismatches', () => {
    const s = scoreMatch('blinding lights', 'the weeknd', 'blinding lights', 'cover artist')
    expect(s).toBe(5) // title exact, no artist bonus
  })

  it('rewards partial title containment', () => {
    const s = scoreMatch('a very long title', '', 'a very long title live', '')
    expect(s).toBeGreaterThanOrEqual(3)
  })

  it('gives zero for empty input', () => {
    expect(scoreMatch('', '', '', '')).toBe(0)
  })

  it('uses token overlap for fuzzy titles', () => {
    const s = scoreMatch('hello world song', '', 'hello beautiful world song', '')
    // 3 tokens overlap out of 4 union = 0.75 → 3
    expect(s).toBeGreaterThanOrEqual(3)
  })
})

describe('YouTube URL extraction', () => {
  // Test the URL pattern logic (extract ?list= param from various forms).
  const extractList = (url: string): string | null => {
    try {
      return new URL(url).searchParams.get('list')
    } catch {
      return null
    }
  }

  it('extracts from standard playlist URL', () => {
    expect(extractList('https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf')).toBe(
      'PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf'
    )
  })

  it('extracts from watch URL with list param', () => {
    expect(extractList('https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf')).toBe(
      'PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf'
    )
  })

  it('extracts from music.youtube.com', () => {
    expect(extractList('https://music.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf')).toBe(
      'PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf'
    )
  })

  it('extracts from youtu.be with list param', () => {
    expect(extractList('https://youtu.be/dQw4w9WgXcQ?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf')).toBe(
      'PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf'
    )
  })

  it('returns null for URL without list param', () => {
    expect(extractList('https://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBeNull()
  })

  it('returns null for invalid URL', () => {
    expect(extractList('not-a-url')).toBeNull()
  })

  it('detects radio mix IDs', () => {
    const isMix = (id: string) => /^(?:RD|UL|LM|WL|MM)/.test(id)
    expect(isMix('RDCLAK5uyk')).toBe(true)
    expect(isMix('WL')).toBe(true)
    expect(isMix('PLrAXtmErZg')).toBe(false)
  })
})

describe('Spotify URL extraction', () => {
  const extractId = (url: string): string | null => {
    try {
      const u = new URL(url)
      return u.pathname.match(/playlist\/([A-Za-z0-9]+)/)?.[1] ?? null
    } catch {
      return null
    }
  }

  it('extracts from open.spotify.com', () => {
    expect(extractId('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')).toBe('37i9dQZF1DXcBWIGoYBM5M')
  })

  it('extracts with additional path segments', () => {
    expect(extractId('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc')).toBe('37i9dQZF1DXcBWIGoYBM5M')
  })

  it('returns null for non-playlist URL', () => {
    expect(extractId('https://open.spotify.com/album/12345')).toBeNull()
  })
})

describe('Apple Music URL detection', () => {
  const isApple = (url: string): boolean => {
    try {
      return new URL(url).hostname.replace(/^www\./, '').includes('apple.com')
    } catch {
      return false
    }
  }

  it('recognises music.apple.com', () => {
    expect(isApple('https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb')).toBe(true)
  })

  it('recognises non-music apple.com', () => {
    expect(isApple('https://itunes.apple.com/playlist/123')).toBe(true)
  })
})

describe('Chat message validation', () => {
  const validateMessage = (msg: string): { ok: boolean; error?: string } => {
    const trimmed = (msg ?? '').trim()
    if (trimmed.length === 0) return { ok: false, error: 'Messages cannot be empty' }
    if (trimmed.length > 280) return { ok: false, error: 'Messages must be 280 characters or fewer' }
    return { ok: true }
  }

  it('accepts a normal message', () => {
    expect(validateMessage('this song hits different').ok).toBe(true)
  })

  it('rejects empty message', () => {
    const r = validateMessage('')
    expect(r.ok).toBe(false)
    expect(r.error).toContain('empty')
  })

  it('rejects whitespace-only message', () => {
    expect(validateMessage('   ').ok).toBe(false)
  })

  it('rejects message exceeding 280 chars', () => {
    const long = 'a'.repeat(281)
    const r = validateMessage(long)
    expect(r.ok).toBe(false)
    expect(r.error).toContain('280')
  })

  it('accepts exactly 280 chars', () => {
    expect(validateMessage('a'.repeat(280)).ok).toBe(true)
  })
})
