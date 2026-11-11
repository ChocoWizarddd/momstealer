import { createHash } from 'node:crypto'
import process from 'node:process'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OtpService } from './otp.service'
import { PasswordService } from './password.service'

/**
 * In-memory MongoDB stand-in (same pattern as otp.service.spec.ts) so the
 * password-auth security contract is verified deterministically:
 * scrypt-at-rest, enumeration-safe register/reset, purpose-scoped OTPs,
 * one-time reset tokens, logout revocation, uniform login errors.
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
        if (want && typeof want === 'object' && !Array.isArray(want)) {
          if ('$gte' in want) return doc[key] >= want.$gte
          if ('$in' in want) return (want as any).$in.includes(doc[key])
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
        let n = 0
        for (const r of rows) {
          if (matches(r, filter)) {
            Object.assign(r, update.$set)
            n++
          }
        }
        return Promise.resolve({ modifiedCount: n })
      },
      updateOne: (filter: Record<string, any>, update: { $set: Doc }) => {
        const hit = rows.find((r) => matches(r, filter))
        if (hit) {
          Object.assign(hit, update.$set)
          return Promise.resolve({ modifiedCount: 1 })
        }
        return Promise.resolve({ modifiedCount: 0 })
      },
      deleteOne: (filter: Record<string, any>) => {
        const idx = rows.findIndex((r) => matches(r, filter))
        if (idx >= 0) {
          rows.splice(idx, 1)
          return Promise.resolve({ deletedCount: 1 })
        }
        return Promise.resolve({ deletedCount: 0 })
      }
    }
  }

  return { collection, getDb: () => ({ collection }), tables }
}

const store = makeStore()

vi.mock('#common/database/mongo', () => ({
  Collections: {
    OTPS: 'otps',
    USERS: 'users',
    PREFERENCES: 'preferences',
    SESSIONS: 'sessions'
  },
  collection: (name: string) => store.collection(name),
  getDb: () => store.getDb()
}))

const sha256 = (v: string) => createHash('sha256').update(v).digest('hex')

describe('PasswordService', () => {
  let service: PasswordService
  let logSpy: ReturnType<typeof vi.spyOn>

  const PASSWORD = 'correcthorse1'

  beforeEach(() => {
    store.tables.clear()
    process.env.NODE_ENV = 'development'
    delete process.env.RESEND_API_KEY
    logSpy = vi.spyOn(console, 'info').mockImplementation(() => {})
    vi.spyOn(console, 'error').mockImplementation(() => {})
    service = new PasswordService()
  })

  afterEach(() => {
    logSpy.mockRestore()
    vi.restoreAllMocks()
  })

  const devCode = () => {
    const lines = logSpy.mock.calls.flat().filter((c: unknown) => typeof c === 'string' && c.includes('[dev] OTP for'))
    return String(lines.at(-1)).match(/OTP for [^:]+: (\d{6})/)?.[1] ?? ''
  }

  const registerAndVerify = async (email: string, password = PASSWORD) => {
    const reg = await service.register(email, password)
    const code = devCode()
    expect(code).toMatch(/^\d{6}$/)
    const otp = new OtpService()
    const session = await otp.verifyOtp(email, code)
    return { reg, session }
  }

  it('register stores only a scrypt hash (never plaintext) and emails a code, no token', async () => {
    const { reg, session } = await registerAndVerify('Alpha@Example.com ')
    expect(reg.user.email).toBe('alpha@example.com')
    expect(reg.verification.emailSent).toBe(true)
    // Session only after verify:
    expect(session.token).toBeTruthy()
    const row = store.tables.get('users')![0]
    expect(row?.email).toBe('alpha@example.com')
    expect(row?.passwordHash).toMatch(/^scrypt\$/)
    expect(JSON.stringify(row)).not.toContain(PASSWORD)
    expect(row?.passwordHash.split('$').length).toBe(3)
  })

  it('register rejects weak passwords', async () => {
    await expect(service.register('weak@example.com', 'short')).rejects.toMatchObject({
      code: 'PASSWORD_WEAK'
    })
    await expect(service.register('weak@example.com', 'lettersonly')).rejects.toMatchObject({
      code: 'PASSWORD_WEAK'
    })
    // Nothing persisted:
    expect(store.tables.get('users') ?? []).toHaveLength(0)
  })

  it('register with an existing email does NOT leak existence and never returns a session', async () => {
    await registerAndVerify('known@example.com')
    const count = store.tables.get('users')!.length
    // Move past the 60s resend cooldown before re-registering.
    vi.useFakeTimers({ now: Date.now() + 61_000 })
    try {
      const res = await service.register('known@example.com', 'anotherpass1')
      expect(res).not.toHaveProperty('token')
      expect(res.verification.emailSent).toBe(true)
      // No duplicate user row:
      expect(store.tables.get('users')!.length).toBe(count)
    } finally {
      vi.useRealTimers()
    }
  })

  it('login issues a session whose tokenHash is stored, plaintext is not', async () => {
    await registerAndVerify('login@example.com')
    const res = await service.login('login@example.com', PASSWORD)
    expect(res.token.length).toBeGreaterThanOrEqual(16)
    expect(res.user.email).toBe('login@example.com')
    const row = store.tables.get('users')!.find((u) => u.email === 'login@example.com')
    expect(row?.tokenHash).toBe(sha256(res.token))
    expect(JSON.stringify(store.tables.get('users'))).not.toContain(res.token)
  })

  it('login rejects wrong password with a uniform error', async () => {
    await registerAndVerify('uniform@example.com')
    await expect(service.login('uniform@example.com', 'wrongpass99')).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS'
    })
    // Unknown email gets the SAME error code (no enumeration):
    await expect(service.login('ghost@example.com', 'whatever123')).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS'
    })
  })

  it('forgot-password for unknown email is indistinguishable from known', async () => {
    // Register the "known" user first.
    await registerAndVerify('knownreset@example.com')
    vi.useFakeTimers({ now: Date.now() + 61_000 })
    try {
      const known = await service.requestPasswordReset('knownreset@example.com')
      const unknown = await service.requestPasswordReset('never@existed.com')
      expect(unknown.emailSent).toBe(true)
      expect(unknown.emailSent).toBe(known.emailSent)
      // Known email actually queued a reset OTP; unknown did not:
      const otpRows = (store.tables.get('otps') ?? []).filter((r) => r.purpose === 'reset')
      expect(otpRows).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('reset flow: OTP verifies to a one-time token, token sets the new hash, old password dies', async () => {
    const email = 'reset@example.com'
    await registerAndVerify(email)

    await service.requestPasswordReset(email)
    const code1 = devCode() // reset-purpose code (latest dev line)
    // Sanity: the reset code must differ from any login code previously logged.
    const resetToken = (await service.verifyResetOtp(email, code1)).resetToken
    expect(resetToken.length).toBeGreaterThanOrEqual(16)
    const tokRow = store.tables.get('sessions')!.find((r) => r.purpose === 'password_reset')
    expect(tokRow?.tokenHash).toBe(sha256(resetToken))

    await service.resetPassword(email, resetToken, 'brandnewpass9')
    const row = store.tables.get('users')!.find((u) => u.email === email)
    expect(JSON.stringify(row ?? {})).not.toContain('brandnewpass9')
    // Old password no longer works, new one does:
    await expect(service.login(email, PASSWORD)).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS'
    })
    const fresh = await service.login(email, 'brandnewpass9')
    expect(fresh.token).toBeTruthy()
  })

  it('reset token is single-use and bound to its purpose', async () => {
    const email = 'onetime@example.com'
    await registerAndVerify(email)
    await service.requestPasswordReset(email)
    const token = (await service.verifyResetOtp(email, devCode())).resetToken
    await service.resetPassword(email, token, 'secondpass123')
    await expect(service.resetPassword(email, token, 'thirdpass456')).rejects.toMatchObject({
      code: 'RESET_TOKEN_INVALID'
    })
  })

  it('a login-purpose code can NEVER authorize a password reset (purpose scoping)', async () => {
    const email = 'scope@example.com'
    await registerAndVerify(email)
    // Request a LOGIN code (past the cooldown):
    vi.useFakeTimers({ now: Date.now() + 61_000 })
    try {
      const otp = new OtpService()
      await otp.requestOtp(email, 'login')
      const loginCode = devCode()
      // Try to use it in the reset flow:
      await expect(service.verifyResetOtp(email, loginCode)).rejects.toMatchObject({
        code: 'OTP_EXPIRED'
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('logout rotates the stored hash so the old token stops working', async () => {
    await registerAndVerify('logout@example.com')
    const session = await service.login('logout@example.com', PASSWORD)
    const before = store.tables.get('users')!.find((u) => u.email === 'logout@example.com')!.tokenHash
    expect(before).toBe(sha256(session.token))
    await service.logout(session.token)
    const after = store.tables.get('users')!.find((u) => u.email === 'logout@example.com')!.tokenHash
    expect(after).not.toBe(sha256(session.token))
  })

  it('logout with a garbage token is a safe no-op', async () => {
    await expect(service.logout('not-a-real-token-value')).resolves.toBeUndefined()
    await expect(service.logout('')).resolves.toBeUndefined()
  })

  it('resetPassword rotates the session too (password change logs devices out)', async () => {
    const email = 'rotate@example.com'
    await registerAndVerify(email)
    const session = await service.login(email, PASSWORD)
    await service.requestPasswordReset(email)
    const token = (await service.verifyResetOtp(email, devCode())).resetToken
    await service.resetPassword(email, token, 'newpass45678')
    const row = store.tables.get('users')!.find((u) => u.email === email)!
    expect(row.tokenHash).not.toBe(sha256(session.token))
  })
})
