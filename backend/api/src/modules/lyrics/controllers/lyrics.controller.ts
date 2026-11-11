import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { rateLimit } from '#common/middleware/rate-limit'
import { LyricsService } from '../services/lyrics.service'
import type { Routes } from '#common/types'

const LineSchema = z.object({ timeMs: z.number().nullable(), text: z.string() })

/**
 * Synchronized (Spotify-style) lyrics for the current track. The music
 * provider's own lyrics endpoint now requires signed CDN tokens, so lyrics are
 * resolved through the public LRCLIB database and normalized to timed lines.
 */
export class LyricsController implements Routes {
  public controller: OpenAPIHono
  private lyricsService: LyricsService

  constructor() {
    this.controller = new OpenAPIHono()
    this.lyricsService = new LyricsService()
  }

  public initRoutes() {
    this.controller.use('/lyrics', rateLimit({ limit: 40, windowMs: 60_000 }))

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/lyrics',
        tags: ['Lyrics'],
        summary: 'Fetch lyrics for a song',
        operationId: 'getLyrics',
        request: {
          query: z.object({
            title: z.string().min(1).max(200),
            artist: z.string().max(300).optional().default(''),
            album: z.string().max(300).optional().default(''),
            durationMs: z.string().pipe(z.coerce.number()).optional().default('0')
          })
        },
        responses: {
          200: {
            description: 'Lyrics (timed or plain)',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    available: z.boolean(),
                    instrumental: z.boolean(),
                    timed: z.boolean(),
                    lines: z.array(LineSchema)
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { title, artist, album, durationMs } = ctx.req.valid('query')
        const result = await this.lyricsService.fetchLyrics({ title, artist, album, durationMs })
        return ctx.json({ success: true, data: result })
      }
    )
  }
}
