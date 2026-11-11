import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { WeatherService } from '#modules/context/services'
import type { Routes } from '#common/types'

export class WeatherController implements Routes {
  public controller: OpenAPIHono
  private weatherService: WeatherService

  constructor() {
    this.controller = new OpenAPIHono()
    this.weatherService = new WeatherService()
  }

  public initRoutes() {
    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/context/weather',
        tags: ['Context'],
        summary: 'Current weather for music context',
        description:
          'Server-side weather for contextual discovery. Coordinates are optional ' +
          '(the app does not require location permission); when omitted the client IP ' +
          'is geolocated, falling back to a default city. Cached for 30 minutes.',
        operationId: 'currentWeather',
        request: {
          query: z.object({
            lat: z.string().pipe(z.coerce.number()).optional(),
            lon: z.string().pipe(z.coerce.number()).optional(),
            city: z.string().max(200).optional()
          })
        },
        responses: {
          200: {
            description: 'Normalized weather conditions',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    condition: z.string(),
                    label: z.string(),
                    tempC: z.number().nullable(),
                    isDay: z.boolean(),
                    city: z.string(),
                    fetchedAt: z.number()
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { lat, lon, city } = ctx.req.valid('query')
        const result = await this.weatherService.getWeather(lat, lon, city)
        return ctx.json({ success: true, data: result })
      }
    )
  }
}
