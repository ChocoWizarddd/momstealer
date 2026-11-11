import { collection } from '#common/database/mongo'
import { normalizeQuery, PLAY_DEDUPE_WINDOW_MS } from '#modules/stats/services/popularity-scoring'

/**
 * NightLight-owned event store. Minimal rows only:
 *   search_events { query, trackId?, createdAt }          (+ optional userId)
 *   play_events   { userId?, trackKey, trackId?, createdAt }
 * No provider payloads, no stream URLs, no PII beyond an optional user id.
 */

export const SEARCH_EVENTS = 'searchEvents'
export const PLAY_EVENTS = 'playEvents'

export interface SearchEvent {
  userId?: string | null
  query: string
  trackId?: string | null
  createdAt: Date
}

export interface PlayEvent {
  userId?: string | null
  trackKey: string
  trackId?: string | null
  createdAt: Date
}

export class PopularityRepository {
  async recordSearch(input: { userId?: string | null; query: string; trackId?: string | null }): Promise<void> {
    const query = normalizeQuery(input.query)
    if (!query) return
    await collection<SearchEvent>(SEARCH_EVENTS).insertOne({
      userId: input.userId ?? null,
      query,
      trackId: input.trackId ?? null,
      createdAt: new Date()
    })
  }

  async recordPlay(input: {
    userId?: string | null
    trackKey: string
    trackId?: string | null
    at?: Date
  }): Promise<boolean> {
    if (!input.trackKey) return false
    const at = input.at ?? new Date()
    // Repeat suppression (§12/§26): same user+track inside the dedupe window
    // counts once (kills ticker duplicates and single-song looping inflation).
    const since = new Date(at.getTime() - PLAY_DEDUPE_WINDOW_MS)
    const existing = await collection<PlayEvent>(PLAY_EVENTS).findOne(
      { userId: input.userId ?? null, trackKey: input.trackKey, createdAt: { $gte: since } },
      { projection: { _id: 1 } }
    )
    if (existing) return false
    await collection<PlayEvent>(PLAY_EVENTS).insertOne({
      userId: input.userId ?? null,
      trackKey: input.trackKey,
      trackId: input.trackId ?? null,
      createdAt: at
    })
    return true
  }

  /** Long-window aggregates: most searched / most played. */
  async topSearches(since: Date, limit: number): Promise<Array<{ query: string; count: number }>> {
    const rows = await collection<SearchEvent>(SEARCH_EVENTS)
      .aggregate<{ _id: string; count: number }>([
        { $match: { createdAt: { $gte: since } } },
        { $group: { _id: '$query', count: { $sum: 1 } } },
        { $sort: { count: -1 } },
        { $limit: limit }
      ])
      .toArray()
    return rows.map((r) => ({ query: r._id, count: r.count }))
  }

  async topPlays(
    since: Date,
    limit: number
  ): Promise<Array<{ trackKey: string; trackId: string | null; count: number }>> {
    const rows = await collection<PlayEvent>(PLAY_EVENTS)
      .aggregate<{ _id: string; trackId: string | null; count: number }>([
        { $match: { createdAt: { $gte: since } } },
        { $group: { _id: '$trackKey', trackId: { $first: '$trackId' }, count: { $sum: 1 } } },
        { $sort: { count: -1 } },
        { $limit: limit }
      ])
      .toArray()
    return rows.map((r) => ({ trackKey: r._id, trackId: r.trackId ?? null, count: r.count }))
  }

  /**
   * Velocity input per key: events in [recentSince, now) vs [baselineSince,
   * recentSince). One method serves both search and play trending.
   */
  async velocity(
    collectionName: string,
    keyField: string,
    recentSince: Date,
    baselineSince: Date,
    limit: number
  ): Promise<Array<{ key: string; recent: number; baseline: number }>> {
    const rows = await collection(collectionName)
      .aggregate<{ _id: string; recent: number; baseline: number }>([
        { $match: { createdAt: { $gte: baselineSince } } },
        {
          $group: {
            _id: `$${keyField}`,
            recent: { $sum: { $cond: [{ $gte: ['$createdAt', recentSince] }, 1, 0] } },
            baseline: { $sum: { $cond: [{ $lt: ['$createdAt', recentSince] }, 1, 0] } }
          }
        },
        { $match: { recent: { $gte: 1 } } },
        { $sort: { recent: -1 } },
        { $limit: limit * 4 }
      ])
      .toArray()
    return rows.map((r) => ({ key: r._id, recent: r.recent, baseline: r.baseline }))
  }
}
