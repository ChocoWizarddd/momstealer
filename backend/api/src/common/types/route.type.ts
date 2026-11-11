import type { OpenAPIHono } from '@hono/zod-openapi'

export interface Routes {
  controller: OpenAPIHono
  initRoutes: () => void
  /** Optional mount prefix below /api, e.g. '/me' -> /api/me/... */
  pathPrefix?: string
}
