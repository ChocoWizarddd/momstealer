import { Buffer } from 'node:buffer'
import { createCipheriv } from 'node:crypto'
import { describe, expect, it } from 'vitest'

import { GaanaError } from './gaana-errors'
import { extractJsonObjects, GaanaProvider, parseIsoDuration } from './gaana-provider'

const SONG_URL = 'https://gaana.com/song/perfect-410'

/** Real LD+JSON MusicRecording block shape (captured 2026-09, stable SEO contract). */
const LD_JSON_PAGE = `<html><head><script type="application/ld+json">{"@context":"https://schema.org","@type":"MusicRecording","name":"Perfect","url":"https://gaana.com/song/perfect-410","@id":"https://gaana.com/song/perfect-410","inAlbum":{"@type":"MusicAlbum","name":"\u00F7 (Deluxe)"},"image":"https://a10.gaanacdn.com/gn_img/albums/dwN39y83DP/wN39dDJ8KD/size_m.webp","inLanguage":"English","duration":"PT04M23S","byArtist":[{"@type":"Person","name":"Ed Sheeran"}],"releasedEvent":{"@type":"PublicationEvent","name":"2017-03-03"}}</script></head><body></body></html>`

/** Real captured 2026 high-bitrate message (320 chars). 2019 key/IV no longer decrypt it. */
const REAL_CAPTURED_MESSAGE =
  '3ORVXXddzueOqBzm8q1Rw+1Z0virKElRE9F5qdulBRWdPLXae4LZVcbUjBi6PeSN6fvERfWn9/+xaHdHzhtWJ2e7/m30eSBFigZtjTmHIzRXIYa/PU5UM4oHxxT+/vSCQmgzHHwbYtLVq48iDZWAqqOyRjgE873ZbuO6qMkujtOMqfKiL/POREGRmPQLK6aFRtZ+ZZGOQYBSXKbPguYEeP8ErCjcvTutR2MUhLfKTC3Pvz1n7waAf9C/S5P4aXW4/qVcTpm/tSKUvk/A+2nbmQ2/1/FzjRzlEUnqWFSFjMl77lf8DTvPFBaBnyKtiA='

const FUTURE = Math.floor(Date.now() / 1000) + 5 * 3600

function trackPage(
  urls: Record<string, { message: string; bitRate: string; expiryTime: number }>,
  seokey = 'perfect-410'
) {
  const track = JSON.stringify({
    track_id: '21342455',
    seokey,
    track_title: 'Perfect',
    urls
  })
  return `<html><head>${LD_JSON_PAGE}<script>var tracks=[${track}];</script></head></html>`
}

function encryptSelf(plaintext: string): string {
  const cipher = createCipheriv(
    'aes-128-cbc',
    Buffer.from('g@1n!(f1#r.0$)&%', 'utf8'),
    Buffer.from('asd!@#!@#@!12312', 'utf8')
  )
  return Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]).toString('base64')
}

const okFetch = (html: string) => () => Promise.resolve({ status: 200, html })

describe('GaanaProvider', () => {
  it('searchTrack() is unsupported (no Gaana search mechanism)', () => {
    expect(() => new GaanaProvider().searchTrack()).toThrowError(GaanaError)
    try {
      new GaanaProvider().searchTrack()
    } catch (error) {
      expect((error as GaanaError).code).toBe('NOT_SUPPORTED')
    }
  })

  it('getLyrics() is unsupported (2019 scraper target is gone)', () => {
    expect(() => new GaanaProvider().getLyrics()).toThrowError(GaanaError)
  })

  it('rejects non-song URLs', async () => {
    await expect(
      new GaanaProvider({ fetchHtml: okFetch('') }).getTrack('https://gaana.com/album/x')
    ).rejects.toMatchObject({
      code: 'INVALID_URL'
    })
  })

  it('extracts canonical metadata from the LD+JSON block', async () => {
    const provider = new GaanaProvider({ fetchHtml: okFetch(LD_JSON_PAGE) })
    const meta = await provider.getTrack(SONG_URL)
    expect(meta.title).toBe('Perfect')
    expect(meta.artist).toBe('Ed Sheeran')
    expect(meta.album).toBe('÷ (Deluxe)')
    expect(meta.language).toBe('English')
    expect(meta.durationMs).toBe(263000)
    expect(meta.released).toBe('2017-03-03')
    expect(meta.artwork).toContain('gaanacdn.com')
    expect(meta.source).toBe('gaana')
  })

  it('maps HTTP statuses to typed errors', async () => {
    const at = (status: number) => new GaanaProvider({ fetchHtml: () => Promise.resolve({ status, html: '' }) })
    await expect(at(404).getTrack(SONG_URL)).rejects.toMatchObject({ code: 'NOT_FOUND' })
    await expect(at(403).getTrack(SONG_URL)).rejects.toMatchObject({ code: 'REGION_BLOCKED' })
    await expect(at(429).getTrack(SONG_URL)).rejects.toMatchObject({ code: 'RATE_LIMITED' })
  })

  it('detects region-blocked pages by marker text', async () => {
    const provider = new GaanaProvider({
      fetchHtml: okFetch('<html>Requested tracks are not available in your region</html>')
    })
    await expect(provider.getTrack(SONG_URL)).rejects.toMatchObject({ code: 'REGION_BLOCKED' })
  })

  it('rejects pages without metadata as FORMAT_CHANGED', async () => {
    const provider = new GaanaProvider({ fetchHtml: okFetch('<html><body>hello</body></html>') })
    await expect(provider.getTrack(SONG_URL)).rejects.toMatchObject({ code: 'FORMAT_CHANGED' })
  })

  it('selects the highest valid bitrate variant', () => {
    const provider = new GaanaProvider()
    const selected = provider.selectBitrate({
      urls: {
        medium: { message: 'm', bitRate: '64', expiryTime: FUTURE },
        high: { message: 'h', bitRate: '128', expiryTime: FUTURE }
      }
    })
    expect(selected?.bitRate).toBe('128')
    expect(provider.selectBitrate({ urls: {} })).toBeNull()
    expect(provider.selectBitrate({})).toBeNull()
  })

  it('rejects expired signed URLs without decrypting', async () => {
    const html = trackPage({ high: { message: REAL_CAPTURED_MESSAGE, bitRate: '128', expiryTime: 1000 } })
    const provider = new GaanaProvider({ fetchHtml: okFetch(html) })
    await expect(provider.resolvePlayback(SONG_URL)).rejects.toMatchObject({ code: 'URL_EXPIRED' })
  })

  it('fails closed with CRYPTO_FAILED on the real rotated payload', async () => {
    const html = trackPage({ high: { message: REAL_CAPTURED_MESSAGE, bitRate: '128', expiryTime: FUTURE } })
    const provider = new GaanaProvider({ fetchHtml: okFetch(html) })
    await expect(provider.resolvePlayback(SONG_URL)).rejects.toMatchObject({ code: 'CRYPTO_FAILED' })
  })

  it('decrypt mechanism round-trips with valid crypto material', async () => {
    const streamUrl = 'https://example-cdn.net/audio/perfect-128.mp4/master.m3u8?token=abc'
    const html = trackPage({ high: { message: encryptSelf(streamUrl), bitRate: '128', expiryTime: FUTURE } })
    const provider = new GaanaProvider({ fetchHtml: okFetch(html) })
    const resolved = await provider.resolvePlayback(SONG_URL)
    expect(resolved.playbackUrl).toBe(streamUrl)
    expect(resolved.bitrate).toBe('128')
    expect(resolved.expiresAt).toBe(FUTURE * 1000)
    expect(resolved.title).toBe('Perfect')
  })

  it('matches the main song by seokey, else first stream object', () => {
    const provider = new GaanaProvider()
    const html = `<div>${JSON.stringify({ track_title: 'Other', seokey: 'other-1', urls: {} })}${JSON.stringify({ track_title: 'Perfect', seokey: 'perfect-410', urls: {} })}</div>`
    expect(provider.findTrackObject(html, 'perfect-410')?.seokey as string).toBe('perfect-410')
    expect(provider.findTrackObject('<div>no json here</div>', 'perfect-410')).toBeNull()
  })
})

describe('parseIsoDuration', () => {
  it('parses PT04M23S', () => {
    expect(parseIsoDuration('PT04M23S')).toBe(263000)
  })

  it('handles hours and garbage', () => {
    expect(parseIsoDuration('PT1H02M03S')).toBe(3723000)
    expect(parseIsoDuration('nonsense')).toBe(0)
    expect(parseIsoDuration('')).toBe(0)
  })
})

describe('extractJsonObjects', () => {
  it('finds brace-balanced objects by marker', () => {
    const html = `x {"a":1,"track_title":"Perfect","urls":{}} y {"b":{"c":[1,2]}} z`
    const found = extractJsonObjects(html, '"track_title"')
    expect(found).toHaveLength(1)
    expect(found[0].track_title).toBe('Perfect')
  })
})
