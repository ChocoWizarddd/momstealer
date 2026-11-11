import process from 'node:process'
import { afterEach, describe, expect, it } from 'vitest'

import { clearTransportCache, isFlaskProviderConfigured, searchFetch } from './search-transport'

const FAKE_PROXY = 'https://proxy.example/forward'

afterEach(() => {
  delete process.env.SEARCH_PROXY_URL
  delete process.env.SEARCH_PROXY_SECRET
  delete process.env.JIOSAAVN_PROVIDER_URL
  clearTransportCache()
})

describe('searchFetch', () => {
  it('goes direct when no proxy is configured', async () => {
    const result = await searchFetch<{ total?: number }>('search.getResults' as never, { q: 'perfect', p: 0, n: 1 })
    expect(result.via).toBe('direct')
    expect(result.ok).toBe(true)
  })

  it('uses the proxy when configured', async () => {
    process.env.SEARCH_PROXY_URL = FAKE_PROXY
    process.env.SEARCH_PROXY_SECRET = 's3cret'
    const payload = { total: 1 }
    const okFetch = (() =>
      Promise.resolve(
        new Response(JSON.stringify({ success: true, data: payload }), { status: 200 })
      )) as unknown as typeof fetch
    const result = await searchFetch('search.getResults' as never, { q: 'perfect' }, okFetch)
    expect(result.via).toBe('proxy')
    expect(result.data).toEqual(payload)
  })

  it('falls back to direct when the proxy fails', async () => {
    process.env.SEARCH_PROXY_URL = FAKE_PROXY
    process.env.SEARCH_PROXY_SECRET = 's3cret'
    const badFetch = (() => Promise.resolve(new Response('nope', { status: 500 }))) as unknown as typeof fetch
    const result = await searchFetch<{ total?: number }>(
      'search.getResults' as never,
      { q: 'perfect', p: 0, n: 1 },
      badFetch
    )
    expect(result.via).toBe('direct')
  })

  it('a slow proxy does not stall the search: the direct rung wins the race', async () => {
    process.env.SEARCH_PROXY_URL = FAKE_PROXY
    process.env.SEARCH_PROXY_SECRET = 's3cret'
    const started = Date.now()
    // Proxy never answers (hangs past the hard cap). The hedge must let the
    // direct rung finish, so this resolves fast instead of after ~5s+.
    const hangingFetch = (() => new Promise<Response>(() => {})) as unknown as typeof fetch
    const result = await searchFetch<{ total?: number }>(
      'search.getResults' as never,
      { q: 'hedge-me', p: 0, n: 1 },
      hangingFetch
    )
    expect(result.via).toBe('direct')
    expect(Date.now() - started).toBeLessThan(5_000)
  })

  it('a fast proxy still wins even with the fallback armed', async () => {
    process.env.SEARCH_PROXY_URL = FAKE_PROXY
    process.env.SEARCH_PROXY_SECRET = 's3cret'
    const payload = { total: 7 }
    const okFetch = (() =>
      Promise.resolve(
        new Response(JSON.stringify({ success: true, data: payload }), { status: 200 })
      )) as unknown as typeof fetch
    const result = await searchFetch('search.getResults' as never, { q: 'fast-wins' }, okFetch)
    expect(result.via).toBe('proxy')
    expect(result.data).toEqual(payload)
  })

  it('a proxy that answers after the hedge loses to the already-settled direct rung', async () => {
    process.env.SEARCH_PROXY_URL = FAKE_PROXY
    process.env.SEARCH_PROXY_SECRET = 's3cret'
    const slowFetch = (() =>
      new Promise<Response>((resolve) => {
        setTimeout(
          () => resolve(new Response(JSON.stringify({ success: true, data: { total: 99 } }))),
          // Lands after PROXY_HEDGE_DELAY_MS (1.5s) but before the hard cap,
          // so the direct rung has already settled and claimed the race.
          2_500
        )
      })
    ) as unknown as typeof fetch
    const result = await searchFetch<{ total?: number }>(
      'search.getResults' as never,
      { q: 'slow-loses', p: 0, n: 1 },
      slowFetch
    )
    expect(result.via).toBe('direct')
  })
})

describe('isFlaskProviderConfigured', () => {
  it('returns false when JIOSAAVN_PROVIDER_URL is not set', () => {
    delete process.env.JIOSAAVN_PROVIDER_URL
    expect(isFlaskProviderConfigured()).toBe(false)
  })

  it('returns true when JIOSAAVN_PROVIDER_URL is set', () => {
    process.env.JIOSAAVN_PROVIDER_URL = 'https://test.example'
    expect(isFlaskProviderConfigured()).toBe(true)
  })
})
