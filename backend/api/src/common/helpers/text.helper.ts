/**
 * Shared text normalization for title/artist matching.
 *
 * Moved verbatim from the importer service so lightweight
 * modules (shuffle, search, reco) can reuse it without
 * importing the import use-case dependency graph.
 */

/**
 * Canonical form of a free-text title/query: lowercase,
 * decoration parentheticals/brackets and feat./ft. segments
 * removed, punctuation collapsed to spaces. Two titles that
 * mean the same thing (case, punctuation, "(Official Video)")
 * canonicalize identically.
 */
export function canonical(raw: string): string {
  return (
    raw
      .toLowerCase()
      .normalize('NFC')
      // Strip parenthetical / bracket content containing decoration keywords.
      .replaceAll(
        /\(([^)]*(?:feat|ft|remix|remaster|live|acoustic|cover|official|video|audio|lyric|slowed|reverb|sped|instrumental|karaoke|piano|from)[^)]*)\)/gi,
        ' '
      )
      .replaceAll(
        /\[([^\]]*(?:feat|ft|remix|remaster|live|acoustic|cover|official|video|audio|lyric|slowed|reverb|sped|instrumental|karaoke|piano|from)[^)]*)\]/gi,
        ' '
      )
      // Strip remaining parens/brackets (other metadata).
      .replaceAll(/\([^)]*\)/g, ' ')
      .replaceAll(/\[[^\]]*\]/g, ' ')
      // Remove feat./ft./featuring segments.
      .replaceAll(/\b(?:feat\.?|ft\.?|featuring)\s+.*/gi, ' ')
      // Remove trailing " - From ..." (Apple Music movie tags).
      .replaceAll(/\s*-\s*from\s+(?:\S.*)?$/gi, ' ')
      // Remove trailing " — Official Video" / similar.
      .replaceAll(/\s*[—–-]\s*(?:official|music|video|audio|visuali[sz]er|hd|4k).*/gi, ' ')
      // Collapse punctuation to spaces.
      .replaceAll(/[,&+!@#$%^*={}~`|\\:;"'<>.?/]/g, ' ')
      // Remove dashes used as separators (between words).
      .replaceAll(/(?<=\s)-(?=\s)/g, ' ')
      .replaceAll(/\b-\b/g, ' ')
      // Collapse whitespace.
      .replaceAll(/\s+/g, ' ')
      .trim()
  )
}
