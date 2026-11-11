import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { rateLimit } from '#common/middleware/rate-limit'
import { HTTPException } from 'hono/http-exception'
import { ImportService, type ImportProgress } from '../services/import.service'
import type { Routes } from '#common/types'

const ImportBody = z.object({
  url: z.string().url().max(2048).openapi({
    description: 'Public Spotify, Apple Music or YouTube/YouTube Music playlist URL',
    example: 'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'
  }),
  limit: z.number().int().min(1).max(200).optional().default(200)
})

/**
 * Converts a public Spotify / YouTube playlist into matched JioSaavn songs.
 * Track titles are matched server-side so the app only ever calls one endpoint.
 */
export class ImportController implements Routes {
  public controller: OpenAPIHono
  private importService: ImportService

  constructor() {
    this.controller = new OpenAPIHono()
    this.importService = new ImportService()
  }

  public initRoutes() {
    // Import does many upstream searches: keep it per-IP throttled.
    this.controller.use('/import*', rateLimit({ limit: 6, windowMs: 60_000 }))

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/import/playlist',
        tags: ['Import'],
        summary: 'Import a Spotify/Apple Music/YouTube playlist',
        description:
          'Resolves a public playlist URL, follows pagination where available, and matches every track against the ' +
          'music library, and returns the playable songs ready to store locally.',
        operationId: 'importPlaylist',
        request: { body: { content: { 'application/json': { schema: ImportBody } } } },
        responses: {
          200: {
            description: 'Matched playlist',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    source: z.string(),
                    playlistName: z.string(),
                    totalTracks: z.number(),
                    sourceTotal: z.number().optional(),
                    matched: z.number(),
                    unmatched: z.array(z.string()),
                    results: z.array(z.record(z.string(), z.unknown()))
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { url, limit } = ctx.req.valid('json')
        try {
          const data = await this.importService.importPlaylist(url, limit)
          return ctx.json({ success: true, data })
        } catch (error) {
          const message = error instanceof Error ? error.message : 'Import failed'
          throw new HTTPException(422, { message })
        }
      }
    )

    // NDJSON progress stream: one JSON line per processed track so clients can
    // show live "matched X of Y" progress during long imports.
    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/import/playlist/stream',
        tags: ['Import'],
        summary: 'Import a playlist with live progress events',
        description:
          'Streams newline-delimited JSON: {type:"start"}, {type:"progress",done,total,currentTitle} per track, ' +
          'then {type:"done",data:<same shape as POST /import/playlist>}. On failure emits {type:"error",message}.',
        operationId: 'importPlaylistStream',
        request: { body: { content: { 'application/json': { schema: ImportBody } } } },
        responses: {
          200: {
            description: 'NDJSON progress stream',
            content: { 'text/plain': { schema: z.string() } }
          }
        }
      }),
      (ctx) => {
        const { url, limit } = ctx.req.valid('json')
        ctx.header('Content-Type', 'application/x-ndjson; charset=utf-8')
        ctx.header('Cache-Control', 'no-store')
        ctx.header('X-Accel-Buffering', 'no')

        const importService = this.importService
        const stream = new ReadableStream<Uint8Array>({
          async start(streamController) {
            const encoder = new TextEncoder()
            const send = (obj: Record<string, unknown>) =>
              streamController.enqueue(encoder.encode(`${JSON.stringify(obj)}\n`))
            const onProgress = ((done: number, total: number, currentTitle: string) =>
              send({ type: 'progress', done, total, currentTitle })) satisfies ImportProgress
            try {
              const data = await importService.importPlaylist(url, limit, onProgress)
              send({ type: 'done', data })
            } catch (error) {
              send({ type: 'error', message: error instanceof Error ? error.message : 'Import failed' })
            } finally {
              streamController.close()
            }
          }
        })
        // NDJSON body — asserted to the declared text/plain 200 response.
        return ctx.newResponse(stream) as never
      }
    )
  }
}
