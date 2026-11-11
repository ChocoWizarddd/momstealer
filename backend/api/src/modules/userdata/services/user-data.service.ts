import { ApiError } from '#common/errors/api-error'
import { sanitizeTrackSnapshot, type TrackSnapshot } from '../models/track-snapshot.model'
import {
  UserDataRepository,
  type HistoryDoc,
  type Page,
  type PlaylistDoc,
  type PlaylistTrackDoc,
  type RecentDoc
} from '../repositories/user-data.repository'

import type { WithId } from 'mongodb'

const RECENTLY_PLAYED_CAP = 50

export class UserDataService {
  private repository = new UserDataRepository()

  // ---- Likes ----

  likeTrack(userId: string, trackInput: unknown) {
    const track = sanitizeTrackSnapshot(trackInput)
    return this.repository.putLike(userId, track)
  }

  async unlikeTrack(userId: string, trackId: string) {
    const ok = await this.repository.deleteLike(userId, trackId)
    if (!ok) throw ApiError.notFound('Track is not liked', 'TRACK_NOT_LIKED')
    return { success: true }
  }

  listLikes(userId: string, page: number, limit: number): Promise<Page<unknown>> {
    return this.repository.listLikes(userId, page, limit)
  }

  async listLikedIds(userId: string) {
    return { ids: await this.repository.listLikedIds(userId) }
  }

  /** Identity profile for the account page (username permanent per UID). */
  async getProfile(userId: string) {
    const doc = await this.repository.getProfile(userId)
    if (!doc) throw ApiError.notFound('Profile not found', 'PROFILE_NOT_FOUND')
    return {
      id: userId,
      email: doc.email ?? null,
      username: doc.username ?? null,
      displayName: doc.displayName ?? null,
      photoUrl: doc.photoUrl ?? null,
      createdAt: doc.createdAt instanceof Date ? doc.createdAt.toISOString() : null
    }
  }

  // ---- Recently played ----

  async recordPlay(userId: string, trackInput: unknown) {
    const track = sanitizeTrackSnapshot(trackInput)
    const entry = await this.repository.upsertRecentlyPlayed(userId, track)
    await this.repository.capRecentlyPlayed(userId, RECENTLY_PLAYED_CAP)
    return entry
  }

  async listRecentlyPlayed(userId: string, limit: number) {
    const items = await this.repository.listRecentlyPlayed(userId, limit)
    return { items: items.map(serializeRecent) }
  }

  // ---- Search history ----

  addSearchHistory(userId: string, query: string) {
    const trimmed = query.trim().slice(0, 200)
    if (!trimmed) throw ApiError.badRequest('Query must not be empty', 'EMPTY_QUERY')
    return this.repository.addSearchHistory(userId, trimmed)
  }

  async listSearchHistory(userId: string, limit: number) {
    const items = await this.repository.listSearchHistory(userId, limit)
    return { items: items.map(serializeHistory) }
  }

  async deleteSearchHistoryItem(userId: string, id: string) {
    const ok = await this.repository.deleteSearchHistoryItem(userId, id)
    if (!ok) throw ApiError.notFound('Search history entry not found', 'HISTORY_NOT_FOUND')
    return { success: true }
  }

  async clearSearchHistory(userId: string) {
    await this.repository.clearSearchHistory(userId)
    return { success: true }
  }

  // ---- Playlists ----

  async createPlaylist(userId: string, name: string, description: string, artworkUrl: string) {
    const trimmedName = name.trim().slice(0, 100)
    if (!trimmedName) throw ApiError.badRequest('Playlist name must not be empty', 'EMPTY_NAME')
    const doc = await this.repository.createPlaylist(
      userId,
      trimmedName,
      description.slice(0, 500),
      artworkUrl.slice(0, 2048)
    )
    return { playlist: serializePlaylist(doc) }
  }

  async listPlaylists(userId: string) {
    const docs = await this.repository.listPlaylists(userId)
    const counts = await this.countTracks(docs.map((d) => d._id.toString()))
    return { items: docs.map((d) => serializePlaylist(d, counts.get(d._id.toString()))) }
  }

  async getPlaylist(userId: string, playlistId: string) {
    const playlist = await this.repository.getPlaylist(userId, playlistId)
    if (!playlist) throw ApiError.notFound('Playlist not found', 'PLAYLIST_NOT_FOUND')
    const tracks = await this.repository.listPlaylistTracks(playlistId, userId, userId)
    return { playlist: serializePlaylist(playlist, tracks.length), tracks: tracks.map(serializePlaylistTrack) }
  }

  async updatePlaylist(userId: string, playlistId: string, patch: { name?: string; description?: string }) {
    const clean: { name?: string; description?: string } = {}
    if (patch.name !== undefined) {
      const name = patch.name.trim().slice(0, 100)
      if (!name) throw ApiError.badRequest('Playlist name must not be empty', 'EMPTY_NAME')
      clean.name = name
    }
    if (patch.description !== undefined) clean.description = patch.description.slice(0, 500)

    const doc = await this.repository.updatePlaylist(userId, playlistId, clean)
    if (!doc) throw ApiError.notFound('Playlist not found', 'PLAYLIST_NOT_FOUND')
    return { playlist: serializePlaylist(doc) }
  }

  async deletePlaylist(userId: string, playlistId: string) {
    const ok = await this.repository.deletePlaylist(userId, playlistId)
    if (!ok) throw ApiError.notFound('Playlist not found', 'PLAYLIST_NOT_FOUND')
    return { success: true }
  }

  async addTrack(userId: string, playlistId: string, trackInput: unknown, position: number | null) {
    await this.assertPlaylistOwner(userId, playlistId)
    const track = sanitizeTrackSnapshot(trackInput)
    const tracks = await this.repository.addTrackToPlaylist(userId, playlistId, track, position)
    return { tracks: tracks.map(serializePlaylistTrack) }
  }

  async removeTrack(userId: string, playlistId: string, trackId: string) {
    await this.assertPlaylistOwner(userId, playlistId)
    const tracks = await this.repository.removeTrackFromPlaylist(userId, playlistId, trackId)
    return { tracks: tracks.map(serializePlaylistTrack) }
  }

  async reorderTracks(userId: string, playlistId: string, trackIds: string[]) {
    await this.assertPlaylistOwner(userId, playlistId)
    const tracks = await this.repository.reorderPlaylist(userId, playlistId, trackIds)
    return { tracks: tracks.map(serializePlaylistTrack) }
  }

  private async assertPlaylistOwner(userId: string, playlistId: string) {
    const playlist = await this.repository.getPlaylist(userId, playlistId)
    if (!playlist) throw ApiError.notFound('Playlist not found', 'PLAYLIST_NOT_FOUND')
  }

  // ---- Preferences ----

  async getPreferences(userId: string) {
    const doc = await this.repository.getPreferences(userId)
    return { preferences: doc ? { repeatMode: doc.repeatMode ?? null, shuffle: doc.shuffle ?? null } : null }
  }

  async setPreferences(userId: string, patch: unknown) {
    const p = (patch ?? {}) as Record<string, unknown>
    const clean: Record<string, unknown> = {}
    if (p.repeatMode !== undefined) {
      const rm = Number(p.repeatMode)
      if (!Number.isInteger(rm) || rm < 0 || rm > 2) throw ApiError.badRequest('Invalid repeatMode', 'BAD_PREFERENCE')
      clean.repeatMode = rm
    }
    if (p.shuffle !== undefined) clean.shuffle = Boolean(p.shuffle)
    const doc = await this.repository.setPreferences(userId, clean)
    return { preferences: doc ? { repeatMode: doc.repeatMode ?? null, shuffle: doc.shuffle ?? null } : null }
  }

  private countTracks(playlistIds: string[]): Promise<Map<string, number>> {
    return this.repository.countTracksByPlaylistIds(playlistIds)
  }
}

function serializeRecent(doc: WithId<RecentDoc>) {
  return {
    trackId: doc.trackId,
    track: doc.track,
    playedAt: doc.playedAt.toISOString()
  }
}

function serializeHistory(doc: WithId<HistoryDoc>) {
  return {
    id: doc._id.toString(),
    query: doc.query,
    createdAt: doc.createdAt.toISOString()
  }
}

function serializePlaylist(doc: WithId<PlaylistDoc>, trackCount?: number) {
  return {
    id: doc._id.toString(),
    name: doc.name,
    description: doc.description ?? '',
    artworkUrl: doc.artworkUrl ?? '',
    trackCount: trackCount ?? 0,
    createdAt: doc.createdAt.toISOString(),
    updatedAt: doc.updatedAt.toISOString()
  }
}

function serializePlaylistTrack(doc: WithId<PlaylistTrackDoc>) {
  return {
    id: doc._id.toString(),
    trackId: doc.trackId,
    track: doc.track,
    position: doc.position,
    addedAt: doc.addedAt.toISOString()
  }
}

export type { TrackSnapshot }
