import { describe, it } from 'node:test'
import assert from 'node:assert/strict'

import type { VercelRequest, VercelResponse } from '@vercel/node'

import handler, {
  buildUpstreamUrl,
  clearRateLimitBuckets,
  headerValue,
  isRateLimited
} from '../api/forward.ts'
import type { ForwardResponse } from '../api/forward.ts'

function makeRes(): { state: { code: number; payload: unknown; headers: Record<string, string> }; res: ForwardResponse } {
  const state = { code: 0, payload: null as unknown, headers: {} as Record<string, string> }
  return {
    state,
    res: {
      status(code: number) {
        state.code = code
        return this
      },
      json(payload: unknown) {
        state.payload = payload
      },
      setHeader(name: string, value: string) {
        state.headers[name] = value
      }
    }
  }
}

describe('buildUpstreamUrl', () => {
  it('builds allowed search calls', () => {
    const built = buildUpstreamUrl('search.getResults', { q: 'perfect', p: 0, n: 10 })
    assert.ok(built.url?.includes('__call=search.getResults'))
    assert.ok(built.url?.includes('q=perfect'))
    assert.equal(built.error, undefined)
  })

  it('rejects anything else', () => {
    assert.equal(buildUpstreamUrl('song.getDetails', { pids: 'x' }).error, 'call not allowed')
    assert.equal(buildUpstreamUrl('search.getResults', { q: 'x', evil: '1' }).error, 'param not allowed: evil')
    assert.equal(buildUpstreamUrl('search.getResults', { q: 'x'.repeat(201) }).error, 'param too long: q')
    assert.equal(buildUpstreamUrl('search.getResults', 'nope').error, 'params must be an object')
  })
})

describe('handler gating', () => {
  it('rejects non-POST', async () => {
    const { state, res } = makeRes()
    await handler({ method: 'GET', headers: {} } as unknown as VercelRequest, res as unknown as VercelResponse)
    assert.equal(state.code, 405)
  })

  it('requires the shared secret', async () => {
    const saved = process.env.FORWARD_SECRET
    process.env.FORWARD_SECRET = 's3cret'
    try {
      const { state, res } = makeRes()
      await handler({ method: 'POST', headers: {}, body: { call: 'search.getResults', params: { q: 'x' } } } as unknown as VercelRequest, res as unknown as VercelResponse)
      assert.equal(state.code, 403)
      const ok = makeRes()
      // Wrong secret must also fail (no network: secret check runs first).
      await handler(
        { method: 'POST', headers: { 'x-search-proxy-secret': 'wrong' }, body: { call: 'nope', params: {} } } as unknown as VercelRequest,
        ok.res as unknown as VercelResponse
      )
      assert.equal(ok.state.code, 403)
    } finally {
      if (saved === undefined) delete process.env.FORWARD_SECRET
      else process.env.FORWARD_SECRET = saved
    }
  })

  it('missing FORWARD_SECRET fails closed, never forwards', async () => {
    const saved = process.env.FORWARD_SECRET
    delete process.env.FORWARD_SECRET
    try {
      const { state, res } = makeRes()
      // Even the correct-looking secret must fail when none is configured.
      await handler(
        { method: 'POST', headers: { 'x-search-proxy-secret': 'anything' }, body: { call: 'search.getResults', params: { q: 'x' } } } as unknown as VercelRequest,
        res as unknown as VercelResponse
      )
      assert.equal(state.code, 403)
    } finally {
      if (saved === undefined) delete process.env.FORWARD_SECRET
      else process.env.FORWARD_SECRET = saved
    }
  })

  it('rate-limits burst traffic', () => {
    clearRateLimitBuckets()
    let limited = 0
    for (let i = 0; i < 130; i++) {
      if (isRateLimited('1.2.3.4', 1000 + i * 10)) limited++
    }
    assert.ok(limited > 0)
  })

  it('maps upstream failure to controlled 502/504, never throws', async () => {
    const savedSecret = process.env.FORWARD_SECRET
    const savedFetch = globalThis.fetch
    process.env.FORWARD_SECRET = 's3cret'
    try {
      const authed = { 'x-search-proxy-secret': 's3cret' }
      const goodBody = { call: 'search.getResults', params: { q: 'x' } }

      globalThis.fetch = (async () =>
        new Response(JSON.stringify({ error: 'bad gateway' }), { status: 502 })) as unknown as typeof fetch
      const bad = makeRes()
      await handler(
        { method: 'POST', headers: authed, body: goodBody } as unknown as VercelRequest,
        bad.res as unknown as VercelResponse
      )
      assert.equal(bad.state.code, 502)

      globalThis.fetch = (async () => {
        throw new Error('socket hangup')
      }) as unknown as typeof fetch
      const down = makeRes()
      await handler(
        { method: 'POST', headers: authed, body: goodBody } as unknown as VercelRequest,
        down.res as unknown as VercelResponse
      )
      assert.equal(down.state.code, 504)
    } finally {
      globalThis.fetch = savedFetch
      if (savedSecret === undefined) delete process.env.FORWARD_SECRET
      else process.env.FORWARD_SECRET = savedSecret
    }
  })

  it('reads headers case-insensitively', () => {
    assert.equal(headerValue({ 'X-Search-Proxy-Secret': 'a' }, 'x-search-proxy-secret'), 'a')
    assert.equal(headerValue({}, 'x-search-proxy-secret'), '')
  })
})
