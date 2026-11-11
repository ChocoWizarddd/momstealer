import { describe, expect, it } from 'vitest'
import { detectVersion, extractIntent, rerankResults, scoreCandidate } from './search-ranker'

describe('detectVersion()', () => {
  it('detects original', () => {
    expect(detectVersion('Perfect')).toBe('original')
  })

  it('detects acoustic', () => {
    expect(detectVersion('Perfect (Acoustic)')).toBe('acoustic')
  })

  it('detects live', () => {
    expect(detectVersion('Perfect (Live)')).toBe('live')
  })

  it('detects remix', () => {
    expect(detectVersion('Perfect (Remix)')).toBe('remix')
  })

  it('detects instrumental', () => {
    expect(detectVersion('Perfect Instrumental')).toBe('instrumental')
  })

  it('detects piano', () => {
    expect(detectVersion('Perfect Piano Version')).toBe('piano')
  })

  it('detects cover', () => {
    expect(detectVersion('Perfect (Cover)')).toBe('cover')
  })

  it('detects sped up', () => {
    expect(detectVersion('Perfect (Sped Up)')).toBe('sped')
  })

  it('detects slowed', () => {
    expect(detectVersion('Perfect (Slowed)')).toBe('slowed')
  })

  it('detects karaoke', () => {
    expect(detectVersion('Perfect Karaoke')).toBe('karaoke')
  })
})

describe('extractIntent()', () => {
  it('extracts artist and title from "Ed Sheeran Perfect"', () => {
    const intent = extractIntent('Ed Sheeran Perfect')
    expect(intent.artist).toBe('ed sheeran')
    expect(intent.title).toBe('perfect')
    expect(intent.variant).toBeNull()
  })

  it('extracts variant from "Ed Sheeran Perfect acoustic"', () => {
    const intent = extractIntent('Ed Sheeran Perfect acoustic')
    expect(intent.artist).toBe('ed sheeran')
    expect(intent.title).toBe('perfect')
    expect(intent.variant).toBe('acoustic')
  })

  it('extracts variant from "Perfect live Ed Sheeran"', () => {
    const intent = extractIntent('Perfect live Ed Sheeran')
    // Heuristic: 3+ tokens after removing 'live' → first half = artist, second = title.
    // For reversed patterns the artist/title split may not be perfect,
    // but the variant is correctly detected.
    expect(intent.variant).toBe('live')
    expect(intent.title + intent.artist).toContain('perfect')
    expect(intent.title + intent.artist).toContain('sheeran')
  })

  it('extracts remix variant', () => {
    const intent = extractIntent('Ed Sheeran Perfect remix')
    expect(intent.variant).toBe('remix')
  })

  it('handles single word query', () => {
    const intent = extractIntent('Perfect')
    expect(intent.title).toBe('perfect')
    expect(intent.artist).toBe('')
    expect(intent.variant).toBeNull()
  })

  it('handles empty query', () => {
    const intent = extractIntent('')
    expect(intent.title).toBe('')
    expect(intent.artist).toBe('')
    expect(intent.variant).toBeNull()
  })

  it('removes variant keywords from title extraction', () => {
    const intent = extractIntent('Ed Sheeran Perfect acoustic')
    // "acoustic" should not be part of the title.
    expect(intent.title).not.toContain('acoustic')
  })
})

describe('scoreCandidate()', () => {
  const baseIntent = { artist: 'ed sheeran', title: 'perfect', variant: null, rawQuery: 'Ed Sheeran Perfect' }

  it('gives high score to exact original match', () => {
    const score = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    expect(score).toBeGreaterThanOrEqual(10)
  })

  it('penalises acoustic when user wants original', () => {
    const original = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const acoustic = scoreCandidate(baseIntent, 'Perfect (Acoustic)', ['Ed Sheeran'], 'acoustic')
    expect(original).toBeGreaterThan(acoustic)
  })

  it('penalises live when user wants original', () => {
    const original = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const live = scoreCandidate(baseIntent, 'Perfect (Live)', ['Ed Sheeran'], 'live')
    expect(original).toBeGreaterThan(live)
  })

  it('penalises remix when user wants original', () => {
    const original = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const remix = scoreCandidate(baseIntent, 'Perfect (Remix)', ['Ed Sheeran'], 'remix')
    expect(original).toBeGreaterThan(remix)
  })

  it('boosts acoustic when user explicitly requests it', () => {
    const acousticIntent = { ...baseIntent, variant: 'acoustic' as const }
    const original = scoreCandidate(acousticIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const acoustic = scoreCandidate(acousticIntent, 'Perfect (Acoustic)', ['Ed Sheeran'], 'acoustic')
    expect(acoustic).toBeGreaterThan(original)
  })

  it('boosts live when user explicitly requests it', () => {
    const liveIntent = { ...baseIntent, variant: 'live' as const }
    const original = scoreCandidate(liveIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const live = scoreCandidate(liveIntent, 'Perfect (Live)', ['Ed Sheeran'], 'live')
    expect(live).toBeGreaterThan(original)
  })

  it('penalises wrong artist', () => {
    const sameArtist = scoreCandidate(baseIntent, 'Perfect', ['Ed Sheeran'], 'original')
    const diffArtist = scoreCandidate(baseIntent, 'Perfect', ['Some Cover Artist'], 'original')
    expect(sameArtist).toBeGreaterThan(diffArtist)
  })
})

describe('rerankResults()', () => {
  const mockResults = [
    { name: 'Perfect (Acoustic)', artists: { primary: [{ name: 'Ed Sheeran' }] } },
    { name: 'Perfect', artists: { primary: [{ name: 'Ed Sheeran' }] } },
    { name: 'Perfect (Live)', artists: { primary: [{ name: 'Ed Sheeran' }] } },
    { name: 'Perfect (Remix)', artists: { primary: [{ name: 'DJ Remix' }] } },
    { name: 'Perfect Piano Version', artists: { primary: [{ name: 'Piano Covers' }] } }
  ]

  it('puts original first when user wants original', () => {
    const ranked = rerankResults(
      'Ed Sheeran Perfect',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    expect(ranked[0].name).toBe('Perfect')
  })

  it('puts acoustic first when user requests acoustic', () => {
    const ranked = rerankResults(
      'Ed Sheeran Perfect acoustic',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    expect(ranked[0].name).toBe('Perfect (Acoustic)')
  })

  it('puts live first when user requests live', () => {
    const ranked = rerankResults(
      'Perfect live Ed Sheeran',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    expect(ranked[0].name).toBe('Perfect (Live)')
  })

  it('puts remix last when user wants original', () => {
    const ranked = rerankResults(
      'Ed Sheeran Perfect',
      mockResults,
      (r) => r.name,
      (r) => [r.artists.primary[0].name],
      (r) => detectVersion(r.name)
    )
    const remixIndex = ranked.findIndex((r) => r.name.includes('Remix'))
    const originalIndex = ranked.findIndex((r) => r.name === 'Perfect')
    expect(remixIndex).toBeGreaterThan(originalIndex)
  })
})

describe('fusion ranking (provider score + playCount)', () => {
  // Mirrors the real retrieval-fusion scenario: the text pool returns
  // region-skewed namesakes while autocomplete resolution contributes the
  // global original with a provider score. No test hardcodes "Ed Sheeran"
  // as correct — the signals (exact match + original + plays + score) decide.
  interface FusionCandidate {
    name: string
    artist: string
    playCount: number | null
    providerScore: number | null
  }
  const pool: FusionCandidate[] = [
    {
      name: 'PERFECT (From "Sunny Sanskari Ki Tulsi Kumari")',
      artist: 'Guru Randhawa',
      playCount: 1833447,
      providerScore: 193034
    },
    { name: 'Perfect', artist: 'Gurinder Rai', playCount: 1401921, providerScore: null },
    { name: 'Perfect', artist: 'Jass Bajwa', playCount: 90908, providerScore: null },
    { name: 'Mr. Perfect', artist: 'Devi Sri Prasad', playCount: 23863490, providerScore: null },
    { name: 'Perfect', artist: 'Ed Sheeran', playCount: 2500000000, providerScore: 1293231 },
    { name: 'Perfect (Acoustic)', artist: 'Ed Sheeran', playCount: 400000000, providerScore: 800000 },
    { name: 'Perfect (Live)', artist: 'Ed Sheeran', playCount: 90000000, providerScore: 300000 },
    { name: 'Perfect (Remix)', artist: 'DJ Remix', playCount: 5000000, providerScore: null },
    { name: 'Perfect', artist: 'Piano Covers', playCount: 120000, providerScore: null }
  ]
  const rank = (query: string) =>
    rerankResults(
      query,
      pool,
      (r) => r.name,
      (r) => [r.artist],
      (r) => detectVersion(r.name),
      (r) => r.playCount,
      undefined,
      undefined,
      (r) => r.providerScore
    )

  it('"Perfect" prefers the global original over namesakes', () => {
    const ranked = rank('Perfect')
    expect(ranked[0].name).toBe('Perfect')
    expect(ranked[0].artist).toBe('Ed Sheeran')
  })

  it('"Ed Sheeran Perfect" prefers the global original', () => {
    const ranked = rank('Ed Sheeran Perfect')
    expect(ranked[0].artist).toBe('Ed Sheeran')
    expect(ranked[0].name).toBe('Perfect')
  })

  it('"Perfect Ed Sheeran" (reversed) still prefers the original', () => {
    const ranked = rank('Perfect Ed Sheeran')
    expect(ranked[0].artist).toBe('Ed Sheeran')
  })

  it('"Perfect acoustic" prefers the acoustic version', () => {
    const ranked = rank('Perfect acoustic')
    expect(ranked[0].name).toBe('Perfect (Acoustic)')
  })

  it('"Perfect live" prefers the live version', () => {
    const ranked = rank('Perfect live')
    expect(ranked[0].name).toBe('Perfect (Live)')
  })

  it('"Perfect remix" prefers the remix over the original', () => {
    const ranked = rank('Perfect remix')
    expect(ranked[0].name).toContain('Remix')
  })

  it('provider score breaks ties between identical titles', () => {
    const tied: FusionCandidate[] = [
      { name: 'Perfect', artist: 'Unknown A', playCount: null, providerScore: 100 },
      { name: 'Perfect', artist: 'Unknown B', playCount: null, providerScore: 900000 }
    ]
    const ranked = rerankResults(
      'Perfect',
      tied,
      (r) => r.name,
      (r) => [r.artist],
      (r) => detectVersion(r.name),
      (r) => r.playCount,
      undefined,
      undefined,
      (r) => r.providerScore
    )
    expect(ranked[0].artist).toBe('Unknown B')
  })

  it('without variant intent, variants rank below the original', () => {
    const ranked = rank('Perfect')
    const originalIdx = ranked.findIndex((r) => r.name === 'Perfect' && r.artist === 'Ed Sheeran')
    for (const name of ['Perfect (Acoustic)', 'Perfect (Live)', 'Perfect (Remix)']) {
      expect(ranked.findIndex((r) => r.name === name)).toBeGreaterThan(originalIdx)
    }
  })
})

describe('hybrid ranking (canonical confirmation + required queries)', () => {
  interface Candidate {
    name: string
    artist: string
    playCount: number | null
    providerScore: number | null
    confirmed: boolean
  }
  const rank = (query: string, pool: Candidate[]) =>
    rerankResults(
      query,
      pool,
      (r) => r.name,
      (r) => [r.artist],
      (r) => detectVersion(r.name),
      (r) => r.playCount,
      undefined,
      undefined,
      (r) => r.providerScore,
      (r) => (r.confirmed ? 2 : 0)
    )

  it('"Coldplay Clocks" prefers Clocks by Coldplay', () => {
    const pool: Candidate[] = [
      { name: 'Clocks', artist: 'Coldplay', playCount: 900000000, providerScore: 500000, confirmed: true },
      { name: 'Clocks', artist: 'Cover Band', playCount: 100000, providerScore: null, confirmed: false },
      { name: 'Clockwork', artist: 'Coldplay', playCount: 50000000, providerScore: null, confirmed: false }
    ]
    const ranked = rank('Coldplay Clocks', pool)
    expect(ranked[0].artist).toBe('Coldplay')
    expect(ranked[0].name).toBe('Clocks')
  })

  it('"Billie Eilish Birds of a Feather" prefers the original', () => {
    const pool: Candidate[] = [
      {
        name: 'Birds of a Feather',
        artist: 'Billie Eilish',
        playCount: 1500000000,
        providerScore: 900000,
        confirmed: true
      },
      {
        name: 'Birds of a Feather (Sped Up)',
        artist: 'Nightcore Lab',
        playCount: 8000000,
        providerScore: null,
        confirmed: false
      },
      { name: 'Birds of a Feather', artist: 'Tribute Stars', playCount: 200000, providerScore: null, confirmed: false }
    ]
    const ranked = rank('Billie Eilish Birds of a Feather', pool)
    expect(ranked[0].artist).toBe('Billie Eilish')
    expect(ranked[0].name).toBe('Birds of a Feather')
  })

  it('"Arijit Singh Tum Hi Ho" prefers Arijit, not an English lookalike', () => {
    const pool: Candidate[] = [
      {
        name: 'Tum Hi Ho',
        artist: 'Mithoon, Arijit Singh',
        playCount: 371000000,
        providerScore: 700000,
        confirmed: true
      },
      { name: 'Tum Hi Ho (Remix)', artist: 'DJ Shadow', playCount: 9000000, providerScore: null, confirmed: false },
      { name: 'Tum Hi Ho', artist: 'Karaoke Kings', playCount: 300000, providerScore: null, confirmed: false }
    ]
    const ranked = rank('Arijit Singh Tum Hi Ho', pool)
    expect(ranked[0].artist).toContain('Arijit Singh')
    expect(ranked[0].name).toBe('Tum Hi Ho')
  })

  it('canonical confirmation lifts a match but never dominates an exact intent mismatch', () => {
    const pool: Candidate[] = [
      { name: 'Perfect Symphony', artist: 'Ed Sheeran', playCount: 100000000, providerScore: 100000, confirmed: true },
      { name: 'Perfect', artist: 'Ed Sheeran', playCount: 2500000000, providerScore: 1293231, confirmed: true }
    ]
    const ranked = rank('Perfect', pool)
    expect(ranked[0].name).toBe('Perfect')
  })
})

describe('live-variant credit collision (Music Travel Love vs Ed Sheeran)', () => {
  // The MTL live recording credits Ed Sheeran as writer; performer identity
  // must come from billed/functional performer roles only.
  interface LiveCandidate {
    name: string
    performers: string[]
    playCount: number | null
    providerScore: number | null
  }
  const pool: LiveCandidate[] = [
    { name: 'Perfect', performers: ['Ed Sheeran'], playCount: 2500000000, providerScore: 1293231 },
    { name: 'Perfect (Live)', performers: ['Ed Sheeran'], playCount: 90000000, providerScore: 300000 },
    {
      name: 'Perfect (Live from Gasparilla Island)',
      performers: ['Music Travel Love'],
      playCount: 12000000,
      providerScore: 150000
    },
    { name: 'Perfect (Acoustic)', performers: ['Ed Sheeran'], playCount: 400000000, providerScore: 800000 },
    { name: 'Perfect (Remix)', performers: ['DJ Remix'], playCount: 5000000, providerScore: null }
  ]
  const rank = (query: string) =>
    rerankResults(
      query,
      pool,
      (r) => r.name,
      (r) => r.performers,
      (r) => detectVersion(r.name),
      (r) => r.playCount,
      undefined,
      undefined,
      (r) => r.providerScore
    )

  it('"perfect live" prefers a genuine live recording, not the credit-collision cover', () => {
    const ranked = rank('perfect live')
    expect(ranked[0].name).toBe('Perfect (Live)')
    expect(ranked[0].performers).toEqual(['Ed Sheeran'])
  })

  it('"perfect live ed sheeran" ranks Ed live above the cover', () => {
    const ranked = rank('perfect live ed sheeran')
    const edIdx = ranked.findIndex((r) => r.name === 'Perfect (Live)')
    const mtlIdx = ranked.findIndex((r) => r.name === 'Perfect (Live from Gasparilla Island)')
    expect(edIdx).toBe(0)
    expect(mtlIdx).toBeGreaterThan(edIdx)
  })

  it('cover gets no Ed performer bonus: direct score comparison', () => {
    const intent = extractIntent('perfect live ed sheeran')
    const ed = scoreCandidate(intent, 'Perfect (Live)', ['Ed Sheeran'], 'live', 90000000, 0, 300000)
    const mtl = scoreCandidate(
      intent,
      'Perfect (Live from Gasparilla Island)',
      ['Music Travel Love'],
      'live',
      12000000,
      0,
      150000
    )
    expect(ed).toBeGreaterThan(mtl)
  })

  it('"ed sheeran perfect" and "perfect ed sheeran" require the original', () => {
    for (const q of ['ed sheeran perfect', 'perfect ed sheeran']) {
      const ranked = rank(q)
      expect(ranked[0].name).toBe('Perfect')
      expect(ranked[0].performers).toEqual(['Ed Sheeran'])
    }
  })

  it('a buried genuine live recording beats a popular cover (version + confirm over fame)', () => {
    // Real retrieval shape: the genuine Ed live sits at #12 upstream with
    // 175 plays while the cover has 6651. First-page depth must include it,
    // then version intent + canonical confirmation must outrank fame.
    const pool: LiveCandidate[] = [
      { name: 'Perfect Live (Live)', performers: ['SpaceStation'], playCount: null, providerScore: null },
      {
        name: 'Perfect (Live from Gasparilla Island)',
        performers: ['Music Travel Love'],
        playCount: 6651,
        providerScore: null
      },
      { name: 'Perfect (Live)', performers: ['Ed Sheeran'], playCount: 175, providerScore: null }
    ]
    const ranked = rerankResults(
      'perfect live',
      pool,
      (r) => r.name,
      (r) => r.performers,
      (r) => detectVersion(r.name),
      (r) => r.playCount,
      undefined,
      undefined,
      (r) => r.providerScore,
      (r) => (r.performers.includes('Ed Sheeran') && r.name === 'Perfect (Live)' ? 2 : 0)
    )
    expect(ranked[0].name).toBe('Perfect (Live)')
    expect(ranked[0].performers).toEqual(['Ed Sheeran'])
  })
})

describe('relevance floor (unrelated mega-hits must not fill the page)', () => {
  interface Candidate {
    name: string
    artist: string
    playCount: number | null
    providerScore: number | null
  }
  const rank = (query: string, pool: Candidate[]) =>
    rerankResults(
      query,
      pool,
      (r) => r.name,
      (r) => [r.artist],
      (r) => detectVersion(r.name),
      (r) => r.playCount,
      undefined,
      undefined,
      (r) => r.providerScore
    )

  // Shape taken from the live "perfect" pool that shipped: six real matches
  // padded out to 24 rows with the most-played Bollywood tracks on the
  // service, which have no relationship to the query at all.
  const polluted: Candidate[] = [
    { name: 'Perfect', artist: 'Ed Sheeran', playCount: 2500000000, providerScore: 1293231 },
    { name: 'Perfect', artist: 'Gurinder Rai', playCount: 1403175, providerScore: null },
    { name: 'Perfect', artist: 'Jass Bajwa', playCount: 93238, providerScore: 84800 },
    { name: 'Mr. Perfect', artist: 'Devi Sri Prasad', playCount: 24025479, providerScore: 59153 },
    { name: 'Perfection', artist: 'Gur Sohal', playCount: 28563, providerScore: null },
    { name: 'Zara Sa (From "Jannat")', artist: 'Sayeed Quadri', playCount: 182721855, providerScore: null },
    { name: 'Dil Ibaadat (From "Tum Mile")', artist: 'Sayeed Quadri', playCount: 119349951, providerScore: null },
    { name: 'Tu Hi Haqeeqat (From "Tum Mile")', artist: 'Sayeed Quadri', playCount: 70809130, providerScore: null },
    { name: 'Iktara (From "Wake Up Sid")', artist: 'Amit Trivedi', playCount: 53883194, providerScore: null },
    { name: 'O Paalanhaare', artist: 'A.R. Rahman', playCount: 19090813, providerScore: null },
    { name: 'Badhulu Thochanai', artist: 'Devi Sri Prasad', playCount: 14430793, providerScore: null },
    { name: 'Chali Chaliga', artist: 'Devi Sri Prasad', playCount: 10265382, providerScore: null }
  ]

  it('drops every track with no textual link to the query', () => {
    const ranked = rank('perfect', polluted)
    const names = ranked.map((r) => r.name)
    expect(names).not.toContain('Zara Sa (From "Jannat")')
    expect(names).not.toContain('Dil Ibaadat (From "Tum Mile")')
    expect(names).not.toContain('O Paalanhaare')
    expect(names).not.toContain('Badhulu Thochanai')
    expect(names).not.toContain('Chali Chaliga')
    expect(names).not.toContain('Iktara (From "Wake Up Sid")')
    // Only genuine matches survive.
    expect(ranked).toHaveLength(5)
  })

  it('a 182M-play non-match can never outrank the exact recording', () => {
    const ranked = rank('perfect', polluted)
    expect(ranked[0].name).toBe('Perfect')
    expect(ranked[0].artist).toBe('Ed Sheeran')
    // The Bollywood track scored 11.31 purely on fame in production.
    const zara = scoreCandidate(
      extractIntent('perfect'),
      'Zara Sa (From "Jannat")',
      ['Sayeed Quadri'],
      'original',
      182721855,
      0,
      null
    )
    const ed = scoreCandidate(
      extractIntent('perfect'),
      'Perfect',
      ['Ed Sheeran'],
      'original',
      2500000000,
      0,
      1293231
    )
    expect(ed).toBeGreaterThan(zara)
  })

  it('exact title identity outranks containment even when the variant is famous', () => {
    const pool: Candidate[] = [
      { name: 'Perfect Symphony', artist: 'Ed Sheeran', playCount: 100000000, providerScore: 100000 },
      { name: 'Perfect', artist: 'Someone Else', playCount: 1000, providerScore: null }
    ]
    const ranked = rank('perfect', pool)
    expect(ranked[0].name).toBe('Perfect')
  })

  it('never returns an empty page: falls back to the full pool when nothing matches', () => {
    const pool: Candidate[] = [
      { name: 'Alpha', artist: 'One', playCount: 900000000, providerScore: null },
      { name: 'Beta', artist: 'Two', playCount: 10, providerScore: null }
    ]
    const ranked = rank('zzzzqqq', pool)
    expect(ranked).toHaveLength(2)
  })

  it('artist-only queries keep that artist and drop the rest', () => {
    const pool: Candidate[] = [
      { name: 'Tum Hi Ho', artist: 'Arijit Singh', playCount: 371000000, providerScore: 700000 },
      { name: 'Chaiyya Chaiyya', artist: 'Malaika Arora', playCount: 200000000, providerScore: null },
      { name: 'Some Other Song', artist: 'Random Band', playCount: 50, providerScore: null }
    ]
    const ranked = rank('Arijit Singh', pool)
    expect(ranked).toHaveLength(1)
    expect(ranked[0].artist).toBe('Arijit Singh')
  })
})
