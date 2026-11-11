import { handle } from '@hono/node-server/vercel'
import { Hono } from 'hono'
import app from './src/server.ts'

export default handle(app)