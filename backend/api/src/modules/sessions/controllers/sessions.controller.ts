import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { rateLimit } from '#common/middleware/rate-limit'
import { SessionsService } from '../services/sessions.service'
import type { Routes } from '#common/types'

const TrackSnapshotSchema = z.object({
  id: z.string().min(1).max(64),
  name: z.string().min(1).max(300),
  artists: z.string().max(300).optional().default(''),
  album: z.string().max(300).optional().default(''),
  imageUrl: z.string().max(600).optional().default(''),
  duration: z.number().optional().default(0),
  year: z.string().max(16).optional().default('')
})

const CodeParam = z.object({ code: z.string().min(4).max(8) })

/**
 * Public listen-together sessions: one device creates a code, friends join,
 * and the shared playback state (track + position + playing) is polled so
 * everyone hears the same song at the same time. Sessions expire after a few
 * hours of silence.
 */
export class SessionsController implements Routes {
  public controller: OpenAPIHono
  public pathPrefix = '/sessions'
  private sessionsService: SessionsService

  constructor() {
    this.controller = new OpenAPIHono()
    this.sessionsService = new SessionsService()
  }

  public initRoutes() {
    this.controller.use('/create', rateLimit({ limit: 20, windowMs: 60_000 }))
    this.controller.use('/join', rateLimit({ limit: 60, windowMs: 60_000 }))
    this.controller.use('/chat', rateLimit({ limit: 30, windowMs: 60_000 }))

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/create',
        tags: ['Sessions'],
        summary: 'Start a listen-together session for the current track',
        operationId: 'createSession',
        request: {
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  deviceId: z.string().min(4).max(128),
                  name: z.string().max(60).optional().default('Friend'),
                  track: TrackSnapshotSchema
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Session created with a share code' } }
      }),
      async (ctx) => {
        const { deviceId, name, track } = ctx.req.valid('json')
        return ctx.json({ success: true, data: await this.sessionsService.create(deviceId, name, track) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/join',
        tags: ['Sessions'],
        summary: 'Join an existing session by code',
        operationId: 'joinSession',
        request: {
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  code: z.string().min(4).max(8),
                  deviceId: z.string().min(4).max(128),
                  name: z.string().max(60).optional().default('Friend')
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Session joined with its current state' } }
      }),
      async (ctx) => {
        const { code, deviceId, name } = ctx.req.valid('json')
        return ctx.json({ success: true, data: await this.sessionsService.join(code, deviceId, name) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/:code',
        tags: ['Sessions'],
        summary: 'Read a session state',
        operationId: 'getSession',
        request: { params: CodeParam },
        responses: { 200: { description: 'Session state' } }
      }),
      async (ctx) => {
        const { code } = ctx.req.valid('param')
        return ctx.json({ success: true, data: await this.sessionsService.get(code) })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/:code/chat',
        tags: ['Sessions'],
        summary: 'Send a chat message to a listen-together session',
        operationId: 'sendSessionChat',
        request: {
          params: CodeParam,
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  deviceId: z.string().min(4).max(128),
                  name: z.string().max(60).optional().default('Listener'),
                  text: z.string().min(1).max(280)
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Chat message accepted' } }
      }),
      async (ctx) => {
        const { code } = ctx.req.valid('param')
        const { deviceId, name, text } = ctx.req.valid('json')
        const message = await this.sessionsService.sendMessage(code, deviceId, name, text)
        return ctx.json({ success: true, data: message })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/:code/chat',
        tags: ['Sessions'],
        summary: 'Read new listen-together chat messages',
        operationId: 'getSessionChat',
        request: {
          params: CodeParam,
          query: z.object({ after: z.coerce.number().min(0).optional().default(0) })
        },
        responses: { 200: { description: 'Chat messages' } }
      }),
      async (ctx) => {
        const { code } = ctx.req.valid('param')
        const { after } = ctx.req.valid('query')
        const messages = await this.sessionsService.getMessages(code, after)
        return ctx.json({ success: true, data: messages })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'put',
        path: '/:code/state',
        tags: ['Sessions'],
        summary: 'Host updates shared playback state',
        operationId: 'updateSessionState',
        request: {
          params: CodeParam,
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  deviceId: z.string().min(4).max(128),
                  track: TrackSnapshotSchema.nullable().optional(),
                  positionMs: z.number().min(0).optional(),
                  playing: z.boolean().optional()
                })
              }
            }
          }
        },
        responses: { 200: { description: 'Updated session state' } }
      }),
      async (ctx) => {
        const { code } = ctx.req.valid('param')
        const { deviceId, track, positionMs, playing } = ctx.req.valid('json')
        return ctx.json({
          success: true,
          data: await this.sessionsService.updateState(code, deviceId, { track, positionMs, playing })
        })
      }
    )
  }
}
