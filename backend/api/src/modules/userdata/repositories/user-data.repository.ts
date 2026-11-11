import { collection, Collections } from '#common/database/mongo'
import { ApiError } from '#common/errors/api-error'
import { ObjectId, type WithId } from 'mongodb'
import type { TrackSnapshot } from '../models/track-snapshot.model'

export type LikeDoc = { userId: string; trackId: string; track: TrackSnapshot; createdAt: Date }
export type RecentDoc = { userId: string; trackId: string; track: TrackSnapshot; playedAt: Date }
export type HistoryDoc = { userId: string; query: string; createdAt: Date }
export type PlaylistDoc = {
  userId: string
  name: string
  description: string
  artworkUrl: string
  createdAt: Date
  updatedAt: Date
}
export type PlaylistTrackDoc = {
  playlistId: string
  trackId: string
  track: TrackSnapshot
  position: number
  addedAt: Date
}
export type PrefsDoc = {
  userId: string
  repeatMode?: number | null
  shuffle?: boolean | null
  updatedAt: Date
}

/** Public identity subset of the users collection (never tokens). */
export type UserProfileDoc = {
  email?: string
  username?: string
  displayName?: string
  photoUrl?: string
  createdAt?: Date
}

export interface Page<T> {
  items: T[]
  total: number
  page: number
  limit: number
}

const BOUND = { MAX_LIMIT: 50, MIN_PAGE: 0, MAX_PAGE: 100_000 } as const

export function clampPagination(page: number, limit: number) {
  return {
    page: Math.max(BOUND.MIN_PAGE, Math.min(BOUND.MAX_PAGE, Math.trunc(page) || 0)),
    limit: Math.max(1, Math.min(BOUND.MAX_LIMIT, Math.trunc(limit) || 20))
  }
}

export function toObjectId(id: string): ObjectId {
  try {
    return new ObjectId(id)
  } catch {
    throw ApiError.badRequest('Invalid identifier', 'BAD_ID')
  }
}

/**
 * All MongoDB access for NightLight user data lives here (Controller → Service
 * → Repository → MongoDB). No route handler touches the database directly.
 */
export class UserDataRepository {
  private users = () => collection<UserProfileDoc>(Collections.USERS)
  private likes = () => collection<LikeDoc>(Collections.LIKES)
  private recent = () => collection<RecentDoc>(Collections.RECENTLY_PLAYED)
  private history = () => collection<HistoryDoc>(Collections.SEARCH_HISTORY)
  private playlists = () => collection<PlaylistDoc>(Collections.PLAYLISTS)
  private playlistTracks = () => collection<PlaylistTrackDoc>(Collections.PLAYLIST_TRACKS)
  private preferences = () => collection<PrefsDoc>(Collections.PREFERENCES)

  // ---- Likes ----

  async putLike(userId: string, track: TrackSnapshot): Promise<{ trackId: string; createdAt: string }> {
    const now = new Date()
    await this.likes().updateOne(
      { userId, trackId: track.id },
      { $set: { userId, trackId: track.id, track, createdAt: now } },
      { upsert: true }
    )
    return { trackId: track.id, createdAt: now.toISOString() }
  }

  async deleteLike(userId: string, trackId: string): Promise<boolean> {
    const res = await this.likes().deleteOne({ userId, trackId })
    return res.deletedCount > 0
  }

  async listLikes(userId: string, page: number, limit: number): Promise<Page<WithId<LikeDoc>>> {
    const { page: p, limit: l } = clampPagination(page, limit)
    const [items, total] = await Promise.all([
      this.likes()
        .find({ userId }, { projection: { userId: 0 } })
        .sort({ createdAt: -1 })
        .skip(p * l)
        .limit(l)
        .toArray(),
      this.likes().countDocuments({ userId })
    ])
    return { items, total, page: p, limit: l }
  }

  async listLikedIds(userId: string): Promise<string[]> {
    const docs = await this.likes()
      .find({ userId }, { projection: { trackId: 1, _id: 0 } })
      .toArray()
    return docs.map((d) => d.trackId)
  }

  // ---- Recently played ----

  /**
   * Upserts a play so a track never appears twice; repeats move it to the top
   * instead of duplicating history.
   */
  async upsertRecentlyPlayed(userId: string, track: TrackSnapshot): Promise<{ trackId: string; playedAt: string }> {
    const now = new Date()
    await this.recent().updateOne(
      { userId, trackId: track.id },
      { $set: { userId, trackId: track.id, track, playedAt: now } },
      { upsert: true }
    )
    return { trackId: track.id, playedAt: now.toISOString() }
  }

  listRecentlyPlayed(userId: string, limit: number): Promise<WithId<RecentDoc>[]> {
    const l = Math.max(1, Math.min(50, Math.trunc(limit) || 20))
    return this.recent()
      .find({ userId }, { projection: { userId: 0 } })
      .sort({ playedAt: -1 })
      .limit(l)
      .toArray()
  }

  async capRecentlyPlayed(userId: string, max: number): Promise<void> {
    const docs = await this.recent()
      .find({ userId }, { projection: { _id: 1 } })
      .sort({ playedAt: -1 })
      .skip(max)
      .limit(100)
      .toArray()
    if (docs.length > 0) {
      await this.recent().deleteMany({ _id: { $in: docs.map((d) => d._id) } })
    }
  }

  // ---- Search history ----

  async addSearchHistory(userId: string, query: string): Promise<{ id: string; query: string; createdAt: string }> {
    const now = new Date()
    // Avoid stacking the same query back-to-back.
    const latest = await this.history().findOne({ userId }, { sort: { createdAt: -1 } })
    if (latest && latest.query.toLowerCase() === query.toLowerCase()) {
      return { id: latest._id.toString(), query, createdAt: latest.createdAt.toISOString() }
    }

    const res = await this.history().insertOne({ userId, query, createdAt: now })
    await this.capSearchHistory(userId, 30)
    return { id: res.insertedId.toString(), query, createdAt: now.toISOString() }
  }

  listSearchHistory(userId: string, limit: number): Promise<WithId<HistoryDoc>[]> {
    const l = Math.max(1, Math.min(50, Math.trunc(limit) || 20))
    return this.history()
      .find({ userId }, { projection: { userId: 0 } })
      .sort({ createdAt: -1 })
      .limit(l)
      .toArray()
  }

  async deleteSearchHistoryItem(userId: string, id: string): Promise<boolean> {
    const res = await this.history().deleteOne({ _id: toObjectId(id), userId })
    return res.deletedCount > 0
  }

  async clearSearchHistory(userId: string): Promise<void> {
    await this.history().deleteMany({ userId })
  }

  private async capSearchHistory(userId: string, max: number): Promise<void> {
    const docs = await this.history()
      .find({ userId }, { projection: { _id: 1 } })
      .sort({ createdAt: -1 })
      .skip(max)
      .limit(100)
      .toArray()
    if (docs.length > 0) {
      await this.history().deleteMany({ _id: { $in: docs.map((d) => d._id) } })
    }
  }

  // ---- Playlists ----

  async createPlaylist(
    userId: string,
    name: string,
    description: string,
    artworkUrl: string
  ): Promise<WithId<PlaylistDoc>> {
    const now = new Date()
    const res = await this.playlists().insertOne({
      userId,
      name,
      description,
      artworkUrl,
      createdAt: now,
      updatedAt: now
    })
    return (await this.playlists().findOne({ _id: res.insertedId }))!
  }

  listPlaylists(userId: string): Promise<WithId<PlaylistDoc>[]> {
    return this.playlists()
      .find({ userId }, { projection: { userId: 0 } })
      .sort({ createdAt: -1 })
      .toArray()
  }

  async countTracksByPlaylistIds(playlistIds: string[]): Promise<Map<string, number>> {
    if (playlistIds.length === 0) return new Map()
    const rows = await this.playlistTracks()
      .aggregate<{ _id: string; count: number }>([
        { $match: { playlistId: { $in: playlistIds } } },
        { $group: { _id: '$playlistId', count: { $sum: 1 } } }
      ])
      .toArray()
    return new Map(rows.map((r) => [r._id, r.count]))
  }

  getPlaylist(userId: string, playlistId: string): Promise<WithId<PlaylistDoc> | null> {
    return this.playlists().findOne({ _id: toObjectId(playlistId), userId }, { projection: { userId: 0 } })
  }

  async updatePlaylist(
    userId: string,
    playlistId: string,
    patch: { name?: string; description?: string; artworkUrl?: string }
  ): Promise<WithId<PlaylistDoc> | null> {
    const update: Partial<Pick<PlaylistDoc, 'name' | 'description' | 'artworkUrl' | 'updatedAt'>> = {
      updatedAt: new Date()
    }
    if (patch.name !== undefined) update.name = patch.name
    if (patch.description !== undefined) update.description = patch.description
    if (patch.artworkUrl !== undefined) update.artworkUrl = patch.artworkUrl
    await this.playlists().updateOne({ _id: toObjectId(playlistId), userId }, { $set: update })
    return this.getPlaylist(userId, playlistId)
  }

  async deletePlaylist(userId: string, playlistId: string): Promise<boolean> {
    const id = toObjectId(playlistId)
    const res = await this.playlists().deleteOne({ _id: id, userId })
    if (res.deletedCount > 0) {
      await this.playlistTracks().deleteMany({ playlistId: id.toString() })
    }
    return res.deletedCount > 0
  }

  listPlaylistTracks(playlistId: string, playlistOwnerId: string, userId: string): Promise<WithId<PlaylistTrackDoc>[]> {
    if (playlistOwnerId !== userId) return Promise.resolve([])
    return this.playlistTracks()
      .find({ playlistId: toObjectId(playlistId).toString() }, { projection: { playlistId: 0 } })
      .sort({ position: 1 })
      .toArray()
  }

  /**
   * Appends (or inserts at `position`) a track, shifting later tracks so
   * positions stay contiguous and unique.
   */
  async addTrackToPlaylist(
    userId: string,
    playlistId: string,
    track: TrackSnapshot,
    position: number | null
  ): Promise<WithId<PlaylistTrackDoc>[]> {
    const pid = toObjectId(playlistId).toString()
    const existing = await this.playlistTracks().findOne({ playlistId: pid, trackId: track.id })
    if (existing) return this.listPlaylistTracks(pid, userId, userId)

    const total = await this.playlistTracks().countDocuments({ playlistId: pid })
    const insertAt = position === null ? total : Math.max(0, Math.min(position, total))

    if (insertAt < total) {
      await this.playlistTracks().updateMany(
        { playlistId: pid, position: { $gte: insertAt } },
        { $inc: { position: 1 } }
      )
    }
    await this.playlistTracks().insertOne({
      playlistId: pid,
      trackId: track.id,
      track,
      position: insertAt,
      addedAt: new Date()
    })
    await this.playlists().updateOne({ _id: toObjectId(playlistId) }, { $set: { updatedAt: new Date() } })
    return this.listPlaylistTracks(pid, userId, userId)
  }

  async removeTrackFromPlaylist(
    userId: string,
    playlistId: string,
    trackId: string
  ): Promise<WithId<PlaylistTrackDoc>[]> {
    const pid = toObjectId(playlistId).toString()
    const removed = await this.playlistTracks().findOneAndDelete({ playlistId: pid, trackId })
    if (removed) {
      await this.playlistTracks().updateMany(
        { playlistId: pid, position: { $gt: removed.position } },
        { $inc: { position: -1 } }
      )
      await this.playlists().updateOne({ _id: toObjectId(playlistId) }, { $set: { updatedAt: new Date() } })
    }
    return this.listPlaylistTracks(pid, userId, userId)
  }

  async clearPlaylist(userId: string, playlistId: string): Promise<WithId<PlaylistTrackDoc>[]> {
    const pid = toObjectId(playlistId).toString()
    await this.playlistTracks().deleteMany({ playlistId: pid })
    await this.playlists().updateOne({ _id: toObjectId(playlistId) }, { $set: { updatedAt: new Date() } })
    return this.listPlaylistTracks(pid, userId, userId)
  }

  async reorderPlaylist(userId: string, playlistId: string, trackIds: string[]): Promise<WithId<PlaylistTrackDoc>[]> {
    const pid = toObjectId(playlistId).toString()
    const validIds = trackIds.slice(0, 500)
    for (const [i, validId] of validIds.entries()) {
      await this.playlistTracks().updateOne({ playlistId: pid, trackId: validId }, { $set: { position: i } })
    }
    await this.playlists().updateOne({ _id: toObjectId(playlistId) }, { $set: { updatedAt: new Date() } })
    return this.listPlaylistTracks(pid, userId, userId)
  }

  // ---- Preferences ----

  getPreferences(userId: string): Promise<WithId<PrefsDoc> | null> {
    return this.preferences().findOne({ userId }, { projection: { userId: 0 } })
  }

  /** Public identity profile (username/display/photo/email/created). Never tokens. */
  async getProfile(userId: string): Promise<WithId<UserProfileDoc> | null> {
    return this.users().findOne(
      { _id: toObjectId(userId) },
      {
        projection: {
          email: 1,
          username: 1,
          displayName: 1,
          photoUrl: 1,
          createdAt: 1
        }
      }
    )
  }

  async setPreferences(
    userId: string,
    patch: Partial<Pick<PrefsDoc, 'repeatMode' | 'shuffle'>>
  ): Promise<WithId<PrefsDoc> | null> {
    const update: Partial<Pick<PrefsDoc, 'repeatMode' | 'shuffle' | 'updatedAt'>> = { updatedAt: new Date() }
    if (patch.repeatMode !== undefined) update.repeatMode = patch.repeatMode
    if (patch.shuffle !== undefined) update.shuffle = patch.shuffle
    await this.preferences().updateOne({ userId }, { $set: update }, { upsert: true })
    return this.preferences().findOne({ userId }, { projection: { userId: 0 } })
  }
}
