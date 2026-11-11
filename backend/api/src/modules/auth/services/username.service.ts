/**
 * Deterministic username derivation for Firebase UID identities (§20).
 *
 * ONE Firebase account = ONE permanent username: derived once from the
 * verified display name (never from raw email alone when a name exists),
 * collision-resolved with a deterministic numeric suffix, then persisted.
 * Pure functions — fully unit-tested without Mongo.
 */

export const USERNAME_MIN = 3
export const USERNAME_MAX = 20
const USERNAME_RE = /^[A-Za-z0-9_.-]+$/

/** Names never issued bare (deterministic suffix instead). */
export const RESERVED_USERNAMES = new Set([
  'you',
  'friend',
  'admin',
  'nightlight',
  'null',
  'undefined'
])

/** Normalizes a display name (or email prefix) into a username candidate. */
export function normalizeUsername(raw: string | null | undefined): string {
  let s = (raw ?? '').trim()
  // Email input: use the mailbox prefix, never the domain.
  const at = s.indexOf('@')
  if (at > 0) s = s.slice(0, at)
  s = s
    .toLowerCase()
    .replaceAll(/[^a-z0-9_.-]+/g, '')
    .replaceAll(/^[._-]+|[._-]+$/g, '')
  if (s.length > USERNAME_MAX) s = s.slice(0, USERNAME_MAX)
  if (s.length < USERNAME_MIN || !USERNAME_RE.test(s)) return ''
  return s
}

/** Display-name-first base: name wins, email prefix is the fallback. */
export function usernameBase(displayName: string | null | undefined, email: string | null | undefined): string {
  return normalizeUsername(displayName) || normalizeUsername(email) || 'listener'
}

/**
 * Deterministic collision resolution: base, base2, base3, … First free name
 * wins. `taken` is checked in order, so the result is stable for a stable
 * database state. Reserved/generic names are suffixed, never issued bare.
 */
export function resolveCollision(
  base: string,
  taken: (name: string) => boolean,
  reserved: Set<string> = RESERVED_USERNAMES
): string {
  const clean = normalizeUsername(base) || 'listener'
  const stem = reserved.has(clean) ? `${clean}_fan` : clean
  if (!taken(stem)) return stem
  // Suffix loop reserves digit room so long stems cannot loop forever.
  for (let i = 2; ; i++) {
    const suffix = String(i)
    const candidate = `${stem.slice(0, Math.max(1, USERNAME_MAX - suffix.length))}${suffix}`
    if (candidate.length >= USERNAME_MIN && !taken(candidate)) return candidate
  }
}

/** Mirrors the Android username rules (letters/numbers/_/-/. no spaces). */
export function isValidUsername(raw: string | null | undefined): boolean {
  if (raw == null) return false
  const s = raw.trim()
  return s.length >= USERNAME_MIN && s.length <= USERNAME_MAX && USERNAME_RE.test(s)
}
