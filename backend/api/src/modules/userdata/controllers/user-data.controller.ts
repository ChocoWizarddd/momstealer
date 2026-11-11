import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { ApiError } from '#common/errors/api-error'
import { authMiddleware, requireUser } from '#common/middleware/auth'
import { rateLimit } from '#common/middleware/rate-limit'
import { TrackSnapshotSchema } from '../models/track-snapshot.model'
import { UserDataService } from '../services/user-data.service'
import type { Routes } from '#common/types'

const TrackBody = z.object({ track: TrackSnapshotSchema })
const TrackIdParam = z.object({ trackId: z.string().min(1).max(64) })
const PlaylistIdParam = z.object({ id: z.string().min(1).max(64) })
const QueryParam = z.object({ query: z.string().min(1).max(200) })

export class UserDataController implements Routes {
  public controller: OpenAPIHono
  // Mounted under /api/me so these user-owned resources never collide with
  // the music-proxy module (which owns /api/playlists, /api/songs, ...).
  public pathPrefix = '/me'
  private userDataService: UserDataService

  constructor() {
    this.controller = new OpenAPIHono()
    this.userDataService = new UserDataService()
  }

  public initRoutes() {
    // Everything here is authenticated and write-heavy: rate limit per user.
    // Scope middleware to these prefixes so other /api routes stay open.
    const protectedPaths = ['/likes*', '/recently-played*', '/search-history*', '/playlists*', '/preferences*', '/profile']
    for (const path of protectedPaths) {
      this.controller.use(path, authMiddleware)
      this.controller.use(path, rateLimit({ limit: 120, windowMs: 60_000, key: (c) => c.get('user')?.id }))
    }

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/likes/ids',
        tags: ['User Data'],
        summary: 'All liked track IDs',
        operationId: 'getLikedIds',
        responses: { 200: { description: 'Liked track IDs' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        return ctx.json({ success: true, data: await this.userDataService.listLikedIds(user.id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/likes',
        tags: ['User Data'],
        summary: 'Paginated liked tracks',
        operationId: 'getLikes',
        request: {
          query: z.object({
            page: z.coerce.number().int().optional().default(0),
            limit: z.coerce.number().int().optional().default(20)
          })
        },
        responses: { 200: { description: 'Paginated liked tracks' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { page, limit } = ctx.req.valid('query')
        return ctx.json({ success: true, data: await this.userDataService.listLikes(user.id, page, limit) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'put',
        path: '/likes/{trackId}',
        tags: ['User Data'],
        summary: 'Like a track (idempotent)',
        operationId: 'likeTrack',
        request: {
          params: TrackIdParam,
          body: { content: { 'application/json': { schema: TrackBody } } }
        },
        responses: { 200: { description: 'Track liked' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { trackId } = ctx.req.valid('param')
        const { track } = ctx.req.valid('json')
        if (track.id !== trackId) throw ApiError.badRequest('Track ID mismatch', 'TRACK_ID_MISMATCH')
        return ctx.json({ success: true, data: await this.userDataService.likeTrack(user.id, track) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'delete',
        path: '/likes/{trackId}',
        tags: ['User Data'],
        summary: 'Unlike a track',
        operationId: 'unlikeTrack',
        request: { params: TrackIdParam },
        responses: { 200: { description: 'Track unliked' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { trackId } = ctx.req.valid('param')
        return ctx.json({ success: true, data: await this.userDataService.unlikeTrack(user.id, trackId) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/recently-played',
        tags: ['User Data'],
        summary: 'Recently played tracks',
        operationId: 'getRecentlyPlayed',
        request: {
          query: z.object({ limit: z.coerce.number().int().optional().default(20) })
        },
        responses: { 200: { description: 'Recently played tracks' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { limit } = ctx.req.valid('query')
        return ctx.json({ success: true, data: await this.userDataService.listRecentlyPlayed(user.id, limit) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/recently-played',
        tags: ['User Data'],
        summary: 'Record a play',
        operationId: 'recordPlay',
        request: {
          body: { content: { 'application/json': { schema: TrackBody } } }
        },
        responses: { 200: { description: 'Play recorded' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { track } = ctx.req.valid('json')
        return ctx.json({ success: true, data: await this.userDataService.recordPlay(user.id, track) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search-history',
        tags: ['User Data'],
        summary: 'Recent search history',
        operationId: 'getSearchHistory',
        request: {
          query: z.object({ limit: z.coerce.number().int().optional().default(20) })
        },
        responses: { 200: { description: 'Search history entries' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { limit } = ctx.req.valid('query')
        return ctx.json({ success: true, data: await this.userDataService.listSearchHistory(user.id, limit) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/search-history',
        tags: ['User Data'],
        summary: 'Add a search history entry',
        operationId: 'addSearchHistory',
        request: {
          body: { content: { 'application/json': { schema: QueryParam } } }
        },
        responses: { 200: { description: 'Entry added' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { query } = ctx.req.valid('json')
        return ctx.json({ success: true, data: await this.userDataService.addSearchHistory(user.id, query) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'delete',
        path: '/search-history/{id}',
        tags: ['User Data'],
        summary: 'Delete one history entry',
        operationId: 'deleteSearchHistoryItem',
        request: { params: PlaylistIdParam },
        responses: { 200: { description: 'Entry deleted' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { id } = ctx.req.valid('param')
        return ctx.json({ success: true, data: await this.userDataService.deleteSearchHistoryItem(user.id, id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'delete',
        path: '/search-history',
        tags: ['User Data'],
        summary: 'Clear all search history',
        operationId: 'clearSearchHistory',
        responses: { 200: { description: 'History cleared' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        return ctx.json({ success: true, data: await this.userDataService.clearSearchHistory(user.id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/playlists',
        tags: ['User Data'],
        summary: 'List user playlists',
        operationId: 'getPlaylists',
        responses: { 200: { description: 'Playlists' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        return ctx.json({ success: true, data: await this.userDataService.listPlaylists(user.id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/playlists',
        tags: ['User Data'],
        summary: 'Create a playlist',
        operationId: 'createPlaylist',
        request: {
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  name: z.string().min(1).max(100),
                  description: z.string().max(500).optional().default(''),
                  artworkUrl: z.string().max(2048).optional().default('')
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Playlist created' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const body = ctx.req.valid('json')
        return ctx.json({
          success: true,
          data: await this.userDataService.createPlaylist(user.id, body.name, body.description, body.artworkUrl)
        })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/playlists/{id}',
        tags: ['User Data'],
        summary: 'Playlist with tracks',
        operationId: 'getPlaylist',
        request: { params: PlaylistIdParam },
        responses: { 200: { description: 'Playlist detail' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { id } = ctx.req.valid('param')
        return ctx.json({ success: true, data: await this.userDataService.getPlaylist(user.id, id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'patch',
        path: '/playlists/{id}',
        tags: ['User Data'],
        summary: 'Rename / edit a playlist',
        operationId: 'updatePlaylist',
        request: {
          params: PlaylistIdParam,
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  name: z.string().min(1).max(100).optional(),
                  description: z.string().max(500).optional()
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Playlist updated' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { id } = ctx.req.valid('param')
        const body = ctx.req.valid('json')
        return ctx.json({ success: true, data: await this.userDataService.updatePlaylist(user.id, id, body) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'delete',
        path: '/playlists/{id}',
        tags: ['User Data'],
        summary: 'Delete a playlist',
        operationId: 'deletePlaylist',
        request: { params: PlaylistIdParam },
        responses: { 200: { description: 'Playlist deleted' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { id } = ctx.req.valid('param')
        return ctx.json({ success: true, data: await this.userDataService.deletePlaylist(user.id, id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/playlists/{id}/tracks',
        tags: ['User Data'],
        summary: 'Add a track to a playlist',
        operationId: 'addPlaylistTrack',
        request: {
          params: PlaylistIdParam,
          body: {
            content: {
              'application/json': {
                schema: TrackBody.extend({
                  position: z.number().int().min(0).nullable().optional().default(null)
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Track added; updated track list returned' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { id } = ctx.req.valid('param')
        const body = ctx.req.valid('json')
        return ctx.json({
          success: true,
          data: await this.userDataService.addTrack(user.id, id, body.track, body.position)
        })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'delete',
        path: '/playlists/{id}/tracks/{trackId}',
        tags: ['User Data'],
        summary: 'Remove a track from a playlist',
        operationId: 'removePlaylistTrack',
        request: {
          params: PlaylistIdParam.extend({ trackId: z.string().min(1).max(64) })
        },
        responses: { 200: { description: 'Track removed; updated track list returned' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { id, trackId } = ctx.req.valid('param')
        return ctx.json({
          success: true,
          data: await this.userDataService.removeTrack(user.id, id, trackId)
        })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'put',
        path: '/playlists/{id}/tracks/order',
        tags: ['User Data'],
        summary: 'Reorder playlist tracks',
        operationId: 'reorderPlaylistTracks',
        request: {
          params: PlaylistIdParam,
          body: {
            content: {
              'application/json': {
                schema: z.object({ trackIds: z.array(z.string().min(1).max(64)).max(500) })
              }
            }
          }
        },
        responses: { 200: { description: 'Tracks reordered' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { id } = ctx.req.valid('param')
        const body = ctx.req.valid('json')
        return ctx.json({
          success: true,
          data: await this.userDataService.reorderTracks(user.id, id, body.trackIds)
        })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/profile',
        tags: ['User Data'],
        summary: 'Identity profile (permanent username per Firebase UID)',
        operationId: 'getProfile',
        responses: { 200: { description: 'Profile with username' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        return ctx.json({ success: true, data: await this.userDataService.getProfile(user.id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/preferences',
        tags: ['User Data'],
        summary: 'Get user preferences',
        operationId: 'getPreferences',
        responses: { 200: { description: 'Preferences' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        return ctx.json({ success: true, data: await this.userDataService.getPreferences(user.id) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'put',
        path: '/preferences',
        tags: ['User Data'],
        summary: 'Update user preferences',
        operationId: 'setPreferences',
        request: {
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  repeatMode: z.number().int().min(0).max(2).optional(),
                  shuffle: z.boolean().optional()
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Updated preferences' } }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const body = ctx.req.valid('json')
        return ctx.json({ success: true, data: await this.userDataService.setPreferences(user.id, body) })
      }
    )
  }
}
