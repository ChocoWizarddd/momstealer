/**
 * NightLight-owned popularity math (pure functions — no DB, fully testable).
 *
 * GLOBAL EXTERNAL POPULARITY (provider playCount / scores) and NIGHTLIGHT
 * POPULARITY (our own events) are separate inputs. External signals only
 * ever act as bounded tie-breaks in the ranker; discovery surfaces below
 * are 100% NightLight-owned.
 *
 * Trending (velocity) model:
 *   recent   = events in the last RECENT_WINDOW_HOURS (24h)
 *   baseline = events in the preceding BASELINE_DAYS (7d), per recent-window
 *   score    = recent / (baselinePerWindow + SMOOTHING)
 * A track with 20 plays in the last hour outranks an old giant when its
 * recent velocity exceeds the giant's baseline rate. MIN_RECENT_ACTIVITY
 * keeps single-event noise off the trending surface.
 */

export const RECENT_WINDOW_HOURS = 24
export const BASELINE_DAYS = 7
const SMOOTHING = 2
const MIN_RECENT_ACTIVITY = 3

export interface VelocityInput {
  key: string
  recent: number
  baseline: number
}

export function velocityScore(recent: number, baseline: number): number {
  if (recent < MIN_RECENT_ACTIVITY) return 0
  const baselinePerWindow = baseline / ((BASELINE_DAYS * 24) / RECENT_WINDOW_HOURS)
  return recent / (baselinePerWindow + SMOOTHING)
}

export function rankByVelocity(items: VelocityInput[]): VelocityInput[] {
  return [...items]
    .map((item) => ({ ...item, score: velocityScore(item.recent, item.baseline) }))
    .filter((item) => (item as { score: number }).score > 0)
    .sort((a, b) => (b as { score: number }).score - (a as { score: number }).score)
    .map(({ key, recent, baseline }) => ({ key, recent, baseline }))
}

/** Normalizes a raw query for aggregation (case/punct-insensitive). */
export function normalizeQuery(raw: string): string {
  return (raw ?? '')
    .toLowerCase()
    .replaceAll(/[^a-z0-9\s]/g, ' ')
    .replaceAll(/\s+/g, ' ')
    .trim()
    .slice(0, 200)
}

/**
 * Play threshold policy (§12): a play event counts when playback crossed
 * min(30s, 50% of duration), with a 15s absolute floor against accidental
 * taps. Opening a screen or starting playback alone never counts.
 */
export const PLAY_THRESHOLD_MS = 30000
export const PLAY_THRESHOLD_RATIO = 0.5
export const PLAY_FLOOR_MS = 15000
/** Repeat suppression: same user+track within this window counts once. */
export const PLAY_DEDUPE_WINDOW_MS = 60 * 60 * 1000

export function meetsPlayThreshold(positionMs: number, durationMs: number): boolean {
  if (!Number.isFinite(positionMs) || positionMs < PLAY_FLOOR_MS) return false
  if (positionMs >= PLAY_THRESHOLD_MS) return true
  if (Number.isFinite(durationMs) && durationMs > 0) {
    return positionMs / durationMs >= PLAY_THRESHOLD_RATIO
  }
  return false
}
