import process from 'node:process'

import { OpenAPIHono } from '@hono/zod-openapi'
import { apiReference } from '@scalar/hono-api-reference'
import { getDb } from '#common/database/mongo'
import { ApiError } from '#common/errors/api-error'
import { rateLimit } from '#common/middleware/rate-limit'
import { isSearchProxyConfigured } from '#modules/providers/search-transport'
import { cors } from 'hono/cors'
import { HTTPException } from 'hono/http-exception'
import { logger } from 'hono/logger'
import { prettyJSON } from 'hono/pretty-json'
import { ZodError } from 'zod'
import { Home } from './pages/home'
import ListenBridge from './pages/listen-bridge'
import type { Routes } from '#common/types'

export class App {
  private app: OpenAPIHono

  constructor(routes: Routes[]) {
    this.app = new OpenAPIHono()

    this.initializeGlobalMiddlewares()
    this.initializeRoutes(routes)
    this.initializeSwaggerUI()
    this.initializeRouteFallback()
    this.initializeErrorHandler()
  }

  private initializeRoutes(routes: Routes[]) {
    routes.forEach((route) => {
      route.initRoutes()
      const base = `/api${route.pathPrefix ?? ''}`
      this.app.route(base, route.controller)
    })

    this.app.get('/api/health', async (ctx) => {
      let dbOk = false
      try {
        await getDb().command({ ping: 1 })
        dbOk = true
      } catch {
        dbOk = false
      }
      return ctx.json({
        success: true,
        data: {
          status: 'ok',
          database: dbOk ? 'connected' : 'unavailable',
          // Render injects RENDER_GIT_COMMIT; tells clients exactly which
          // backend build is live (deploy verification + stale-cache diagnosis).
          commit: process.env.RENDER_GIT_COMMIT ?? 'local',
          // Presence only (never values): tells whether Mumbai-egress
          // search is active without exposing configuration.
          searchProxy: isSearchProxyConfigured() ? 'configured' : 'missing'
        }
      })
    })

    this.app.route('/', Home)
    this.app.route('/', ListenBridge)

    // Serve assetlinks.json for Android App Links verification.
    this.app.get('/.well-known/assetlinks.json', async (ctx) => {
      const { readFile } = await import('node:fs/promises')
      try {
        const content = await readFile(new URL('../.well-known/assetlinks.json', import.meta.url))
        return new Response(content, {
          headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=3600' }
        })
      } catch {
        return ctx.json({ success: false, message: 'Not found' }, 404)
      }
    })

    // Share-link bridge: lets a listener tap an HTTPS link from messaging apps.
    // The page attempts the native NightLight scheme immediately and provides
    // a visible fallback if the browser blocks custom-scheme navigation.
    this.app.get('/l/:code', (ctx) => {
      const code = (ctx.req.param('code') || '').trim().toUpperCase()
      if (!/^[A-Z0-9]{4,8}$/.test(code)) {
        return ctx.text('Invalid NightLight session link', 400)
      }
      const deepLink = `nightlight://listen/${code}`
      const escaped = JSON.stringify(deepLink)
      return ctx.html(
        `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Join NightLight</title>` +
          `<meta http-equiv="refresh" content="1;url=${deepLink}">` +
          `<style>body{margin:0;background:#07090e;color:#f5eadc;font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:100vh}main{text-align:center;padding:32px}a{display:inline-block;margin-top:18px;padding:13px 20px;border-radius:14px;background:#e6c47a;color:#14100a;text-decoration:none;font-weight:700}p{color:#a9a0b5}</style></head>` +
          `<body><main><h1>Opening NightLight…</h1><p>Session ${code}</p><a href="${deepLink}">Open NightLight</a>` +
          `<script>setTimeout(function(){location.href=${escaped}},120)</script></main></body></html>`
      )
    })

    // NightLight branding: serve a local favicon instead of the upstream one.
    // The icon lives next to the compiled dist output, so it resolves whether
    // the server runs from src/ or dist/.
    this.app.get('/favicon.ico', async () => {
      const { readFile } = await import('node:fs/promises')
      const icon = await readFile(new URL('favicon.png', import.meta.url))
      return new Response(icon, {
        headers: { 'Content-Type': 'image/png', 'Cache-Control': 'public, max-age=86400' }
      })
    })
  }

  private initializeGlobalMiddlewares() {
    this.app.use(logger())
    this.app.use(prettyJSON())
    this.app.use(cors())
    // The music-proxy search endpoints are external and costly: rate limit them.
    // NOTE: Hono matches middleware paths per-segment, so a single
    // '/api/search*' pattern matches NOTHING (verified against Hono's router):
    // it neither hits '/api/search' nor '/api/search/songs'. Both the bare
    // route and the sub-tree must be listed explicitly or the limiter and the
    // cache header below are silently dead code.
    const searchPaths: string[] = ['/api/search', '/api/search/*']
    searchPaths.forEach((path) => {
      this.app.use(path, rateLimit({ limit: 60, windowMs: 60_000 }))
    })
    // Search results are public and change slowly: let OkHttp serve repeats
    // instantly from the on-device HTTP cache (repeat queries become ~0 ms).
    searchPaths.forEach((path) => {
      this.app.use(path, async (ctx, next) => {
        await next()
        if (ctx.res.status >= 200 && ctx.res.status < 400) {
          ctx.header('Cache-Control', 'public, max-age=30')
        }
      })
    })
  }

  private initializeSwaggerUI() {
    this.app.doc31('/swagger', (c) => {
      const { protocol: urlProtocol, hostname, port } = new URL(c.req.url)
      const protocol = c.req.header('x-forwarded-proto') ? `${c.req.header('x-forwarded-proto')}:` : urlProtocol

      return {
        openapi: '3.1.0',

        info: {
          version: '1.0.0',
          title: 'JioSaavn API',
          description: `# Introduction 
        \nJioSaavn API, accessible at [saavn.dev](https://saavn.dev), is an unofficial API that allows users to download high-quality songs from [JioSaavn](https://jiosaavn.com). 
        It offers a fast, reliable, and easy-to-use API for developers. \n`
        },
        servers: [{ url: `${protocol}//${hostname}${port ? `:${port}` : ''}`, description: 'Current environment' }]
      }
    })

    this.app.get(
      '/docs',
      apiReference({
        pageTitle: 'JioSaavn API Documentation',
        theme: 'deepSpace',
        isEditable: false,
        layout: 'modern',
        darkMode: true,
        metaData: {
          applicationName: 'JioSaavn API',
          author: 'Sumit Kolhe',
          creator: 'Sumit Kolhe',
          publisher: 'Sumit Kolhe',
          robots: 'index, follow',
          description:
            'JioSaavn API is an unofficial wrapper written in TypeScript for jiosaavn.com providing programmatic access to a vast library of songs, albums, artists, playlists, and more.'
        },
        url: '/swagger'
      })
    )
  }

  private initializeRouteFallback() {
    this.app.notFound((ctx) => {
      return ctx.json({ success: false, message: 'route not found, check docs at https://saavn.dev/docs' }, 404)
    })
  }

  private initializeErrorHandler() {
    this.app.onError((err, ctx) => {
      if (err instanceof ApiError) {
        return ctx.json({ success: false, message: err.message, code: err.code }, err.status)
      }
      if (err instanceof HTTPException) {
        return ctx.json({ success: false, message: err.message }, err.status || 500)
      }
      if (err instanceof ZodError) {
        return ctx.json({ success: false, message: 'Invalid request', code: 'BAD_REQUEST' }, 400)
      }
      // Never leak internals to clients; log server-side only.
      console.error('[NightLight] unhandled error:', err)
      return ctx.json({ success: false, message: 'Something went wrong', code: 'INTERNAL_ERROR' }, 500)
    })
  }

  public getApp() {
    return this.app
  }
}
