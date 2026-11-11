import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { SongModel } from '#modules/songs/models'
import { RecoService } from '../services/reco.service'
import type { Routes } from '#common/types'

/**
 * Recommendation candidates with evidence (§23). Android owns final ranking;
 * these endpoints assemble multi-source candidates and NEVER fail hard:
 * worst case is radio-only. No internal provider APIs leak beyond shaped
 * song rows + evidence strings.
 */
export class RecoController implements Routes {
  public controller: OpenAPIHono
  private reco: RecoService

  constructor(reco?: RecoService) {
    this.controller = new OpenAPIHono()
    this.reco = reco ?? new RecoService()
  }

  public initRoutes() {
    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/reco/smartqueue',
        tags: ['Reco'],
        summary: 'Smart-queue candidates with evidence',
        description:
          'Radio (primary) + listening-similarity re-resolved to playable tracks, ' +
          'deduplicated with per-candidate evidence. Falls back to radio-only; never 500s on source failure.',
        operationId: 'recoSmartQueue',
        request: {
          query: z.object({
            seedId: z.string().max(64).optional(),
            seedName: z.string().max(200).optional(),
            artists: z.string().max(300).optional(),
            album: z.string().max(300).optional(),
            recent: z.string().max(600).optional().openapi({
              description:
                'Recently played track ids, comma-separated, most-recent-first — penalized by the smart shuffle.'
            }),
            count: z.string().pipe(z.coerce.number()).optional().openapi({ default: '20' })
          })
        },
        responses: {
          200: {
            description: 'Candidate tracks with evidence',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    tracks: z.array(SongModel),
                    evidence: z.array(
                      z.object({
                        id: z.string().nullable(),
                        title: z.string(),
                        artist: z.string(),
                        sources: z.array(z.string()),
                        score: z.number(),
                        reason: z.string()
                      })
                    )
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { seedId, seedName, artists, album, recent, count } = ctx.req.valid('query')
        const data = await this.reco.smartqueue({
          seedId,
          seedName,
          artists,
          album,
          recent: recent
            ? recent
                .split(',')
                .map((id) => id.trim())
                .filter(Boolean)
            : undefined,
          count: count ?? 20
        })
        return ctx.json({ success: true, data })
      }
    )
  }
}
