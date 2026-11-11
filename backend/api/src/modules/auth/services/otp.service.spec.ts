import { createHash } from 'node:crypto'
import process from 'node:process'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OtpService } from './otp.service'

/**
 * In-memory MongoDB stand-in so the OTP security contract can be verified
 * deterministically without a live database. Implements exactly the query
 * shapes the OTP service uses.
 */

type Doc = Record<string, any>

function makeStore() {
  const tables = new Map<string, Doc[]>()
  let seq = 1

  const collection = (name: string) => {
    if (!tables.has(name)) tables.set(name, [])
    const rows = tables.get(name)!

    const matches = (doc: Doc, filter: Record<string, any>) =>
      Object.entries(filter).every(([key, want]) => {
        if (want && typeof want === 'object' && !Array.isArray(want) && '$gte' in want) {
          return doc[key] >= want.$gte
        }
        return doc[key] === want
      })

    return {
      insertOne: (doc: Doc) => {
        const withId = { _id: seq++, ...doc }
        rows.push(withId)
        return Promise.resolve({ insertedId: withId._id })
      },
      findOne: (filter: Record<string, any>, opts?: { sort?: Record<string, 1 | -1> }) => {
        const found = rows.filter((r) => matches(r, filter))
        if (found.length === 0) return Promise.resolve(null)
        if (opts?.sort) {
          const [key, dir] = Object.entries(opts.sort)[0]
          found.sort((a, b) => (a[key] < b[key] ? -dir : a[key] > b[key] ? dir : 0))
        }
        return Promise.resolve(found[0])
      },
      countDocuments: (filter: Record<string, any>) => Promise.resolve(rows.filter((r) => matches(r, filter)).length),
      updateMany: (filter: Record<string, any>, update: { $set: Doc }) => {
        for (const r of rows) if (matches(r, filter)) Object.assign(r, update.$set)
        return Promise.resolve({ modifiedCount: rows.length })
      },
      updateOne: (filter: Record<string, any>, update: { $set: Doc }, opts?: { upsert?: boolean }) => {
        const hit = rows.find((r) => matches(r, filter))
        if (hit) {
          Object.assign(hit, update.$set)
          return Promise.resolve({ modifiedCount: 1, upsertedId: null })
        }
        if (opts?.upsert) {
          const withId = { _id: seq++, ...filter, ...update.$set }
          rows.push(withId)
          return Promise.resolve({ modifiedCount: 0, upsertedId: withId._id })
        }
        return Promise.resolve({ modifiedCount: 0, upsertedId: null })
      }
    }
  }

  return {
    collection,
    getDb: () => ({ collection }),
    tables
  }
}

const store = makeStore()

vi.mock('#common/database/mongo', () => ({
  Collections: { OTPS: 'otps', USERS: 'users', PREFERENCES: 'preferences' },
  collection: (name: string) => store.collection(name),
  getDb: () => store.getDb()
}))

const sha256 = (v: string) => createHash('sha256').update(v).digest('hex')

describe('OtpService', () => {
  let service: OtpService
  let logSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    store.tables.clear()
    process.env.NODE_ENV = 'development'
    delete process.env.RESEND_API_KEY
    logSpy = vi.spyOn(console, 'info').mockImplementation(() => {})
    service = new OtpService()
  })

  afterEach(() => {
    logSpy.mockRestore()
    vi.restoreAllMocks()
  })

  const devCode = () => {
    const lines = logSpy.mock.calls.flat().filter((c: unknown) => typeof c === 'string' && c.includes('[dev] OTP for'))
    return String(lines.at(-1)).match(/OTP for [^:]+: (\d{6})/)?.[1] ?? ''
  }

  it('stores only a hash of the code, never the plaintext', async () => {
    const res = await service.requestOtp('user@example.com')
    expect(res.emailSent).toBe(true)
    expect(res.devDelivery).toBe(true)
    const code = devCode()
    expect(code).toMatch(/^\d{6}$/)
    const stored = store.tables.get('otps')![0]
    expect(stored.codeHash).not.toBe(code)
    expect(stored.codeHash).toBe(sha256(code))
    expect(JSON.stringify(stored)).not.toContain(code)
  })

  it('enforces the 60s resend cooldown', async () => {
    await service.requestOtp('user@example.com')
    await expect(service.requestOtp('user@example.com')).rejects.toMatchObject({
      code: 'RESEND_COOLDOWN'
    })
  })

  it('rejects an invalid email', async () => {
    await expect(service.requestOtp('not-an-email')).rejects.toMatchObject({
      code: 'INVALID_EMAIL'
    })
  })

  it('caps verification attempts at 5 then locks the code', async () => {
    await service.requestOtp('user@example.com')
    for (let i = 0; i < 5; i++) {
      await expect(service.verifyOtp('user@example.com', '000000')).rejects.toMatchObject({
        code: 'OTP_INVALID'
      })
    }
    await expect(service.verifyOtp('user@example.com', '000000')).rejects.toMatchObject({
      code: 'OTP_TOO_MANY_ATTEMPTS'
    })
  })

  it('issues a session token on the correct code and creates the email user', async () => {
    await service.requestOtp('user@example.com')
    const code = devCode()
    const result = await service.verifyOtp('user@example.com', code)
    expect(result.token.length).toBeGreaterThanOrEqual(32)
    expect(result.user.email).toBe('user@example.com')
    expect(result.preferences).toBeNull()
    // Token is stored hashed.
    const user = store.tables.get('users')![0]
    expect(user.tokenHash).toBe(sha256(result.token))
    expect(user.email).toBe('user@example.com')
  })

  it('never lets a code be used twice', async () => {
    await service.requestOtp('user@example.com')
    const code = devCode()
    await service.verifyOtp('user@example.com', code)
    await expect(service.verifyOtp('user@example.com', code)).rejects.toMatchObject({
      code: 'OTP_EXPIRED'
    })
  })

  it('rejects an expired code', async () => {
    await service.requestOtp('user@example.com')
    const code = devCode()
    store.tables.get('otps')![0].expiresAt = new Date(Date.now() - 1000)
    await expect(service.verifyOtp('user@example.com', code)).rejects.toMatchObject({
      code: 'OTP_EXPIRED'
    })
  })

  it('a new code invalidates the previous one', async () => {
    await service.requestOtp('user@example.com')
    const first = devCode()
    store.tables.get('otps')![0].createdAt = new Date(Date.now() - 120_000)
    await service.requestOtp('user@example.com')
    const second = devCode()
    expect(second).not.toBe(first)
    // The old code must be rejected once a new one exists (latest wins).
    await expect(service.verifyOtp('user@example.com', first)).rejects.toMatchObject({
      code: expect.stringMatching(/^OTP_(INVALID|EXPIRED)$/)
    })
    expect((await service.verifyOtp('user@example.com', second)).user.email).toBe('user@example.com')
  })

  it('saves onboarding preferences upsert-style', async () => {
    await service.requestOtp('user@example.com')
    const code = devCode()
    const { token } = await service.verifyOtp('user@example.com', code)
    const user = store.tables.get('users')![0]
    await service.savePreferences(user._id.toString(), ['Hindi', 'English', 'English'], ['Love'])
    const saved = store.tables.get('preferences')![0]
    expect(saved.languages).toEqual(['Hindi', 'English'])
    expect(saved.categories).toEqual(['Love'])
    expect(saved.userId).toBe(user._id.toString())
    expect(token.length).toBeGreaterThan(0)
  })

  it('returns a graceful EMAIL_SERVICE_NOT_CONFIGURED in production without a key', async () => {
    process.env.NODE_ENV = 'production'
    delete process.env.RESEND_API_KEY
    await expect(service.requestOtp('user@example.com')).rejects.toMatchObject({
      code: 'EMAIL_SERVICE_NOT_CONFIGURED'
    })
  })
})
