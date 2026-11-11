import { ApiError } from '#common/errors/api-error'
import { PLAY_EVENTS, PopularityRepository, SEARCH_EVENTS } from '#modules/stats/repositories/popularity.repository'
import {
  BASELINE_DAYS,
  meetsPlayThreshold,
  normalizeQuery,
  rankByVelocity,
  RECENT_WINDOW_HOURS
} from '#modules/stats/services/popularity-scoring'

/**
 * NightLight-owned popularity: search/play events in, most-searched /
 * most-played / trending-velocity out. All aggregates are ours — external
 * provider numbers never enter these surfaces (§14).
 */
export class PopularityService {
  private readonly repository = new PopularityRepository()

  /** Fire-and-forget search event (page-0 searches). Never throws. */
  async recordSearch(query: string, trackId?: string | null): Promise<void> {
    try {
      const normalized = normalizeQuery(query)
      if (!normalized) return
      await this.repository.recordSearch({ userId: null, query: normalized, trackId: trackId ?? null })
    } catch {
      // Analytics must never break search (degraded mode without Mongo).
    }
  }

  /**
   * Threshold-gated play event. Returns false when the threshold is not met
   * or a duplicate inside the suppression window (not an error).
   */
  async recordPlay(input: {
    userId: string | null
    trackId: string
    title: string
    artists: string[]
    positionMs: number
    durationMs: number
  }): Promise<{ counted: boolean }> {
    const trackKey = `${(input.title ?? '').toLowerCase().trim()}|||${input.artists
      .map((a) => (a ?? '').toLowerCase().trim())
      .filter(Boolean)
      .sort()
      .join(',')}`
    if (!trackKey || trackKey === '|||') throw ApiError.badRequest('Track identity is required', 'EMPTY_TRACK')
    if (!meetsPlayThreshold(input.positionMs, input.durationMs)) return { counted: false }
    const counted = await this.repository.recordPlay({
      userId: input.userId,
      trackKey,
      trackId: input.trackId,
      at: new Date()
    })
    return { counted }
  }

  mostSearched(days: number, limit: number): Promise<Array<{ query: string; count: number }>> {
    const since = new Date(Date.now() - days * 24 * 3600 * 1000)
    return this.repository.topSearches(since, limit)
  }

  mostPlayed(days: number, limit: number): Promise<Array<{ trackKey: string; trackId: string | null; count: number }>> {
    const since = new Date(Date.now() - days * 24 * 3600 * 1000)
    return this.repository.topPlays(since, limit)
  }

  /** Trending = recent velocity vs baseline, NOT total count (§13/§17). */
  async trendingSearches(limit: number): Promise<Array<{ query: string; recent: number; baseline: number }>> {
    const now = Date.now()
    const recentSince = new Date(now - RECENT_WINDOW_HOURS * 3600 * 1000)
    const baselineSince = new Date(now - BASELINE_DAYS * 24 * 3600 * 1000)
    const rows = await this.repository.velocity(SEARCH_EVENTS, 'query', recentSince, baselineSince, limit)
    return rankByVelocity(rows)
      .slice(0, limit)
      .map((r) => ({ query: r.key, recent: r.recent, baseline: r.baseline }))
  }

  async trendingPlays(limit: number): Promise<Array<{ trackKey: string; recent: number; baseline: number }>> {
    const now = Date.now()
    const recentSince = new Date(now - RECENT_WINDOW_HOURS * 3600 * 1000)
    const baselineSince = new Date(now - BASELINE_DAYS * 24 * 3600 * 1000)
    const rows = await this.repository.velocity(PLAY_EVENTS, 'trackKey', recentSince, baselineSince, limit)
    return rankByVelocity(rows)
      .slice(0, limit)
      .map((r) => ({ trackKey: r.key, recent: r.recent, baseline: r.baseline }))
  }
}
