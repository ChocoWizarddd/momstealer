import { canonical } from '#common/helpers/text.helper'

/**
 * Normalized title comparison for repetition avoidance.
 *
 * Reuses the importer's `canonical` (lowercase, decoration
 * parentheticals, feat./ft. removal, punctuation collapse)
 * and adds diacritic stripping plus trailing version-suffix
 * removal, so "Perfect", "perfect!", "Perfect - Live" and
 * "Perfect (Acoustic)" share one title family — while
 * remaining separate tracks that are PENALIZED for
 * proximity, never deleted or deduplicated.
 */

/**
 * Qualifier tokens stripped from the END of a canonical
 * title (longest first so "radio edit" wins over "edit").
 */
const VERSION_SUFFIXES: ReadonlyArray<string> = [
  'official video',
  'official audio',
  'music video',
  'bonus track',
  'radio edit',
  'extended mix',
  'a cappella',
  'sped up',
  'anniversary',
  'orchestral',
  'symphonic',
  'remastered',
  'instrumental',
  'unplugged',
  'karaoke',
  'reprise',
  'session',
  'stripped',
  'acoustic',
  'remix',
  'cover',
  'version',
  'nightcore',
  'slowed',
  'deluxe',
  'demo',
  'master',
  'mix',
  'edit',
  'clean',
  'explicit',
  'live',
  'official'
]

/** Minimum canonical length before containment counts as "near-identical". */
const CONTAINMENT_MIN = 4

export class TitleGuardService {
  /**
   * Canonical title family key: case-, punctuation-,
   * diacritic- and version-qualifier-insensitive.
   */
  canonicalTitle(raw: string): string {
    const base = canonical(raw)
      .normalize('NFD')
      .replaceAll(/\p{M}/gu, '')
      .replaceAll(/[^\p{L}\p{N}\s]/gu, ' ')
      .replaceAll(/\s+/g, ' ')
      .trim()
    // Strip trailing version qualifiers until stable
    // ("Perfect (Acoustic)" → "perfect acoustic" → "perfect").
    let out = base
    let changed = true
    while (changed) {
      changed = false
      for (const suffix of VERSION_SUFFIXES) {
        const pattern = new RegExp(`(?:^|\\s)${suffix.replaceAll(/\s+/g, String.raw`\s+`)}\\s*$`, 'i')
        const next = out.replace(pattern, ' ').replaceAll(/\s+/g, ' ').trim()
        if (next !== out) {
          out = next
          changed = true
        }
      }
    }
    return out
  }

  /** Alias — the canonical key IS the title family. */
  titleKey(title: string): string {
    return this.canonicalTitle(title)
  }

  /**
   * True when two titles belong to the same repetition
   * family: identical canonical keys, or one canonical
   * title contains the other ("Perfect" vs "Perfect 2").
   */
  isSameTitleFamily(a: string, b: string): boolean {
    const ka = this.canonicalTitle(a)
    const kb = this.canonicalTitle(b)
    if (!ka || !kb) return false
    if (ka === kb) return true
    const shorter = ka.length <= kb.length ? ka : kb
    const longer = ka.length <= kb.length ? kb : ka
    return shorter.length >= CONTAINMENT_MIN && longer.includes(shorter)
  }
}
