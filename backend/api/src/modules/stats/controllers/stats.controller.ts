import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { authMiddleware, requireUser } from '#common/middleware/auth'
import { rateLimit } from '#common/middleware/rate-limit'
import { PopularityService } from '#modules/stats/services/popularity.service'
import type { Routes } from '#common/types'

const WINDOW_DAYS = 30
const RESULT_LIMIT = 20

/**
 * NightLight-owned popularity surfaces. Aggregates derive 100% from our own
 * search/play events — no provider numbers, no fabricated statistics.
 * Recording endpoints are authenticated; aggregate reads are public
 * (no PII in counts) and rate-limited.
 */
export class StatsController implements Routes {
  public controller: OpenAPIHono
  public pathPrefix = ''
  private readonly popularity = new PopularityService()

  constructor() {
    this.controller = new OpenAPIHono()
  }

  public initRoutes() {
    this.controller.use('/me/plays', authMiddleware)
    this.controller.use('/me/plays', rateLimit({ limit: 120, windowMs: 60_000, key: (c) => c.get('user')?.id }))
    this.controller.use('/stats*', rateLimit({ limit: 60, windowMs: 60_000 }))

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/me/plays',
        tags: ['Popularity'],
        summary: 'Record a threshold-crossed play',
        description:
          'Counts a play only when playback crossed min(30s, 50% of duration). ' +
          'Screen opens and bare playback starts never count. Repeats inside ' +
          '60 minutes for the same user+track count once.',
        operationId: 'recordPlayEvent',
        request: {
          body: {
            content: {
              'application/json': {
                schema: z.object({
                  trackId: z.string().min(1).max(64),
                  title: z.string().min(1).max(512),
                  artists: z.array(z.string().max(256)).max(10).default([]),
                  positionMs: z.number().int().min(0).max(86400000),
                  durationMs: z.number().int().min(0).max(86400000).default(0)
                })
              }
            }
          }
        },
        responses: {
          200: {
            description: 'Whether the play was counted',
            content: {
              'application/json': {
                schema: z.object({ success: z.boolean(), data: z.object({ counted: z.boolean() }) })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const body = ctx.req.valid('json')
        return ctx.json({
          success: true,
          data: await this.popularity.recordPlay({
            userId: user.id,
            trackId: body.trackId,
            title: body.title,
            artists: body.artists,
            positionMs: body.positionMs,
            durationMs: body.durationMs
          })
        })
      }
    )

    const aggregate = (
      path: '/stats/most-searched' | '/stats/most-played' | '/stats/trending-searches' | '/stats/trending-plays',
      summary: string,
      operationId: string,
      run: (limit: number) => Promise<unknown>
    ) =>
      this.controller.openapi(
        createRoute({
          method: 'get',
          path,
          tags: ['Popularity'],
          summary,
          operationId,
          request: {
            query: z.object({
              limit: z.string().pipe(z.coerce.number().int().min(1).max(50)).optional()
            })
          },
          responses: {
            200: {
              description: summary,
              content: {
                'application/json': {
                  schema: z.object({ success: z.boolean(), data: z.array(z.record(z.string(), z.unknown())) })
                }
              }
            }
          }
        }),
        async (ctx) => {
          const { limit } = ctx.req.valid('query')
          return ctx.json({ success: true, data: (await run(limit ?? RESULT_LIMIT)) as Record<string, unknown>[] })
        }
      )

    aggregate('/stats/most-searched', 'Most searched queries (NightLight-owned)', 'mostSearched', (limit) =>
      this.popularity.mostSearched(WINDOW_DAYS, limit)
    )
    aggregate('/stats/most-played', 'Most played tracks (NightLight-owned)', 'mostPlayed', (limit) =>
      this.popularity.mostPlayed(WINDOW_DAYS, limit)
    )
    aggregate('/stats/trending-searches', 'Trending searches by recent velocity', 'trendingSearches', (limit) =>
      this.popularity.trendingSearches(limit)
    )
    aggregate('/stats/trending-plays', 'Trending plays by recent velocity', 'trendingPlays', (limit) =>
      this.popularity.trendingPlays(limit)
    )
  }
}
