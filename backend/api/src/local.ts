import process from 'node:process'
import { serve } from '@hono/node-server'

import { connectMongo } from '#common/database/mongo'

import app from './server'

const port = Number(process.env.PORT || 8787)

async function main() {
  try {
    await connectMongo()
    console.info('[NightLight] MongoDB connected')
  } catch (error) {
    console.error(
      '[NightLight] MongoDB connection failed — user data endpoints will be unavailable:',
      (error as Error).message
    )
    console.error('[NightLight] Continuing in degraded mode: music search/proxy still works.')
  }

  serve(
    {
      fetch: app.fetch,
      port,
      hostname: '0.0.0.0'
    },
    (info) => {
      console.info(`[NightLight] API listening on port ${info.port}`)
    }
  )
}

main()
