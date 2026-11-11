import { Buffer } from 'node:buffer'
import { createDecipheriv } from 'node:crypto'

import { GaanaError } from './gaana-errors'

/**
 * Gaana provider adapter (TypeScript port of the GaanaAPI mechanism).
 *
 * STATUS — NOT production-ready (decision C, verified live 2026-09):
 *   - Gaana offers NO search mechanism (README-admitted; the /search page
 *     renders zero server results and robots.txt disallows /search/*).
 *   - The 2019 page-embedded playback JSON still exists, but the AES
 *     KEY/IV documented in 2019 no longer decrypt current payloads (key
 *     rotation; verified against a live capture).
 *   - Streams are signed (expiryTime ≈ 5.5h) and region-gated (pages render
 *     "not available in your region" off Indian egress).
 *   - robots.txt disallows /api/*, /napi/*, /apiv2/* automation paths.
 *
 * What this adapter DOES provide today:
 *   - getTrack(): canonical metadata from the LD+JSON MusicRecording block.
 *   - resolvePlayback(): metadata + bitrate selection + expiry check +
 *     decrypt attempt. Currently fails with CRYPTO_FAILED until valid
 *     crypto material is provisioned — that failure is explicit, typed,
 *     and safe (never half-plays a wrong stream).
 *
 * Deliberately NOT implemented: searchTrack(), lyrics (2019 `div.seelyrics`
 * scraper is gone from 2026 pages; NightLight keeps LRCLIB).
 *
 * SECURITY: crypto constants live here, server-side only. This module is
 * NOT wired into any route and NOT imported by Android-facing code paths.
 * Playback URLs are signed/temporary: resolve on demand, never persist.
 */

export interface GaanaMetadata {
  trackId: string
  title: string
  artist: string
  album: string
  language: string
  durationMs: number
  artwork: string
  released: string
  source: 'gaana'
}

export interface GaanaPlaybackSource extends GaanaMetadata {
  playbackUrl: string
  bitrate: string
  expiresAt: number
}

interface FetchResult {
  status: number
  html: string
}

interface GaanaProviderDeps {
  fetchHtml?: (url: string, signal: AbortSignal) => Promise<FetchResult>
  /** Override for tests / future key rotation. Defaults are the (currently invalid) 2019 values. */
  cryptoKey?: string
  cryptoIv?: string
  now?: () => number
}

/** 2019 documented values. Server-side only. Verified INVALID for 2026 payloads. */
const DEFAULT_KEY = 'g@1n!(f1#r.0$)&%'
const DEFAULT_IV = 'asd!@#!@#@!12312'

const SONG_URL = /^https:\/\/gaana\.com\/song\/([a-z0-9-]+)\/?(?:[?#].*)?$/i
const FETCH_TIMEOUT_MS = 10000
const REGION_MARKERS = ['not available in your region', 'not-available', 'regionBlock']
const BITRATE_PREFERENCE = ['high', 'extreme', 'auto', 'medium', 'normal']

export class GaanaProvider {
  private readonly fetchHtml: (url: string, signal: AbortSignal) => Promise<FetchResult>
  private readonly cryptoKey: string
  private readonly cryptoIv: string
  private readonly now: () => number

  constructor(deps: GaanaProviderDeps = {}) {
    this.cryptoKey = deps.cryptoKey ?? DEFAULT_KEY
    this.cryptoIv = deps.cryptoIv ?? DEFAULT_IV
    this.now = deps.now ?? Date.now
    this.fetchHtml =
      deps.fetchHtml ??
      (async (url, signal) => {
        const response = await fetch(url, {
          signal,
          headers: {
            'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/119.0'
          }
        })
        return { status: response.status, html: await response.text() }
      })
  }

  /** Gaana exposes no search mechanism — always throws. */
  searchTrack(): never {
    throw new GaanaError('NOT_SUPPORTED', 'Gaana provides no search mechanism; use the NightLight search pipeline', 501)
  }

  /** The 2019 lyrics scraper target is gone from 2026 pages — always throws. */
  getLyrics(): never {
    throw new GaanaError('NOT_SUPPORTED', 'Gaana lyrics scraping is unsupported; NightLight keeps LRCLIB', 501)
  }

  /** Canonical metadata for a Gaana song URL. */
  async getTrack(gaanaUrl: string): Promise<GaanaMetadata> {
    const slug = this.slugOf(gaanaUrl)
    const html = await this.loadPage(gaanaUrl)
    return this.extractMetadata(html, slug)
  }

  /**
   * Full playback resolution: Gaana URL → metadata → best valid bitrate →
   * decrypted stream URL. Throws typed errors (REGION_BLOCKED, URL_EXPIRED,
   * CRYPTO_FAILED, …) — never returns a wrong stream.
   */
  async resolvePlayback(gaanaUrl: string): Promise<GaanaPlaybackSource> {
    const slug = this.slugOf(gaanaUrl)
    const html = await this.loadPage(gaanaUrl)
    const metadata = this.extractMetadata(html, slug)
    const track = this.findTrackObject(html, slug)
    if (!track) {
      throw new GaanaError('FORMAT_CHANGED', 'No embedded track stream data found on Gaana page', 502)
    }
    const selected = this.selectBitrate(track)
    if (!selected) {
      throw new GaanaError('NO_PLAYABLE_BITRATE', 'Gaana track exposes no playable bitrate variant', 502)
    }
    if (selected.expiryTime * 1000 <= this.now()) {
      throw new GaanaError('URL_EXPIRED', 'Gaana signed stream URL already expired', 502)
    }
    const playbackUrl = this.decrypt(selected.message)
    return {
      ...metadata,
      playbackUrl,
      bitrate: selected.bitRate,
      expiresAt: selected.expiryTime * 1000
    }
  }

  // ---- internals (public for unit tests, not for routes) ----

  slugOf(gaanaUrl: string): string {
    const match = SONG_URL.exec((gaanaUrl ?? '').trim())
    if (!match) throw new GaanaError('INVALID_URL', 'Expected a Gaana song URL (https://gaana.com/song/<slug>)', 400)
    return match[1].toLowerCase()
  }

  async loadPage(gaanaUrl: string): Promise<string> {
    let result: FetchResult
    try {
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS)
      try {
        result = await this.fetchHtml(gaanaUrl, controller.signal)
      } finally {
        clearTimeout(timer)
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new GaanaError('TIMEOUT', 'Gaana page fetch timed out', 504)
      }
      throw new GaanaError('UPSTREAM_ERROR', 'Gaana page fetch failed', 502)
    }
    if (result.status === 404) throw new GaanaError('NOT_FOUND', 'Gaana song page not found', 404)
    if (result.status === 403 || result.status === 451) {
      throw new GaanaError('REGION_BLOCKED', 'Gaana refused the request (region restriction)', 502)
    }
    if (result.status === 429) throw new GaanaError('RATE_LIMITED', 'Gaana rate-limited the request', 502)
    if (result.status < 200 || result.status >= 300 || !result.html) {
      throw new GaanaError('UPSTREAM_ERROR', `Gaana responded with HTTP ${result.status}`, 502)
    }
    const lowered = result.html.toLowerCase()
    if (REGION_MARKERS.some((marker) => lowered.includes(marker.toLowerCase()))) {
      throw new GaanaError('REGION_BLOCKED', 'Track is not available in this region', 502)
    }
    return result.html
  }

  /** Metadata from the LD+JSON MusicRecording block (stable SEO contract). */
  extractMetadata(html: string, slug: string): GaanaMetadata {
    const blocks = [...html.matchAll(/<script type="application\/ld\+json">(.*?)<\/script>/gs)]
    for (const block of blocks) {
      let json: unknown
      try {
        json = JSON.parse(block[1])
      } catch {
        continue
      }
      const rec = (Array.isArray(json) ? json : [json]).find(
        (entry) =>
          typeof entry === 'object' && entry !== null && (entry as { '@type'?: string })['@type'] === 'MusicRecording'
      ) as
        | {
            name?: string
            inAlbum?: { name?: string }
            image?: string
            inLanguage?: string
            duration?: string
            byArtist?: Array<{ name?: string }>
            releasedEvent?: { name?: string }
          }
        | undefined
      if (!rec) continue
      const artists = (rec.byArtist ?? []).map((a) => a?.name ?? '').filter(Boolean)
      return {
        trackId: slug,
        title: rec.name ?? '',
        artist: artists.join(', '),
        album: rec.inAlbum?.name ?? '',
        language: rec.inLanguage ?? '',
        durationMs: parseIsoDuration(rec.duration ?? ''),
        artwork: rec.image ?? '',
        released: rec.releasedEvent?.name ?? '',
        source: 'gaana'
      }
    }
    throw new GaanaError('FORMAT_CHANGED', 'Gaana page carries no MusicRecording metadata block', 502)
  }

  /**
   * Finds the embedded track stream object for `slug` by balanced-brace
   * scanning for objects containing "track_title". Prefers seokey match,
   * falls back to the first stream-bearing object.
   */
  findTrackObject(html: string, slug: string): Record<string, unknown> | null {
    const objects = extractJsonObjects(html, '"track_title"')
    if (objects.length === 0) return null
    const withUrls = objects.filter((o) => typeof o.urls === 'object' && o.urls !== null)
    const pool = withUrls.length > 0 ? withUrls : objects
    const bySlug = pool.find((o) => typeof o.seokey === 'string' && (o.seokey as string).toLowerCase() === slug)
    return bySlug ?? pool[0] ?? null
  }

  /** Highest valid bitrate variant (high → extreme → auto → medium → normal). */
  selectBitrate(track: Record<string, unknown>): { message: string; bitRate: string; expiryTime: number } | null {
    const { urls } = track
    if (typeof urls !== 'object' || urls === null) return null
    const variants = urls as Record<string, { message?: unknown; bitRate?: unknown; expiryTime?: unknown }>
    for (const level of BITRATE_PREFERENCE) {
      const entry = variants[level]
      if (entry && typeof entry.message === 'string' && entry.message.length > 0) {
        return {
          message: entry.message,
          bitRate: typeof entry.bitRate === 'string' ? entry.bitRate : 'unknown',
          expiryTime: typeof entry.expiryTime === 'number' ? entry.expiryTime : 0
        }
      }
    }
    return null
  }

  /** AES-128-CBC decrypt of the embedded message. Throws CRYPTO_FAILED on any failure. */
  decrypt(message: string): string {
    try {
      const decipher = createDecipheriv(
        'aes-128-cbc',
        Buffer.from(this.cryptoKey, 'utf8'),
        Buffer.from(this.cryptoIv, 'utf8')
      )
      const plaintext = Buffer.concat([decipher.update(Buffer.from(message, 'base64')), decipher.final()]).toString(
        'utf8'
      )
      // Strip PKCS#7 padding defensively (node already removes it on final()).
      const pad = plaintext.charCodeAt(plaintext.length - 1)
      const clean = pad >= 1 && pad <= 16 ? plaintext.slice(0, -pad) : plaintext
      if (!/^https?:\/\//.test(clean)) {
        throw new GaanaError('CRYPTO_FAILED', 'Gaana stream payload did not decrypt to a URL (crypto rotated?)', 502)
      }
      return clean
    } catch (error) {
      if (error instanceof GaanaError) throw error
      throw new GaanaError('CRYPTO_FAILED', 'Gaana stream payload decryption failed (crypto rotated?)', 502)
    }
  }
}

/** Parses ISO-8601 durations (PT04M23S) to milliseconds; 0 when unparseable. */
export function parseIsoDuration(input: string): number {
  const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec((input ?? '').trim())
  if (!match) return 0
  const hours = Number(match[1] ?? 0)
  const minutes = Number(match[2] ?? 0)
  const seconds = Number(match[3] ?? 0)
  if (!Number.isFinite(hours + minutes + seconds)) return 0
  return Math.round((hours * 3600 + minutes * 60 + seconds) * 1000)
}

/**
 * Balanced-brace scan returning every top-level {...} object whose raw text
 * contains `marker`. Handles strings and escapes; returns parsed objects
 * (unparseable candidates are skipped).
 */
export function extractJsonObjects(html: string, marker: string): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = []
  let i = 0
  while (i < html.length) {
    const start = html.indexOf('{', i)
    if (start === -1) break
    let depth = 0
    let inString = false
    let escaped = false
    let end = -1
    for (let j = start; j < html.length; j++) {
      const ch = html[j]
      if (inString) {
        if (escaped) escaped = false
        else if (ch === '\\') escaped = true
        else if (ch === '"') inString = false
      } else if (ch === '"') {
        inString = true
      } else if (ch === '{') {
        depth++
      } else if (ch === '}') {
        depth--
        if (depth === 0) {
          end = j
          break
        }
      }
    }
    if (end === -1) break
    const raw = html.slice(start, end + 1)
    i = end + 1
    if (!raw.includes(marker)) continue
    try {
      const parsed: unknown = JSON.parse(raw)
      if (typeof parsed === 'object' && parsed !== null) out.push(parsed as Record<string, unknown>)
    } catch {
      // Ignore truncated/partial blobs.
    }
  }
  return out
}
