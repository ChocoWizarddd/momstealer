import { Buffer } from 'node:buffer'
import process from 'node:process'
import * as mongo from '#common/database/mongo'
import { verifyGoogleIdToken } from '#common/identity/google-token.service'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FirebaseAuthService } from './firebase-auth.service'

// Verification is mocked: unit tests must not hit Google's JWKS. The
// signature/issuer/audience paths are covered by the end-to-end device test.
vi.mock('#common/identity/google-token.service', () => ({
  googleConfigured: vi.fn().mockReturnValue(true),
  verifyGoogleIdToken: vi.fn().mockResolvedValue({
    sub: 'g-sub-1',
    email: 'user@example.com',
    emailVerified: true
  })
}))

/**
 * Input-validation paths run without network. Signature verification paths
 * (401/403) depend on Google's public JWKS and are covered by the
 * end-to-end device test instead of unit tests.
 */
describe('FirebaseAuthService.exchange', () => {
  it('rejects missing/short tokens with 400 before any network call', async () => {
    const svc = new FirebaseAuthService()
    await expect(svc.exchange('')).rejects.toMatchObject({ status: 400 })
    await expect(svc.exchange('short')).rejects.toMatchObject({ status: 400 })
  })
})

/**
 * UID username persistence (§20): one Firebase account = one permanent name.
 * Mongo is mocked at the collection level; no network, no JWKS.
 */
describe('FirebaseAuthService username persistence', () => {
  const enc = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const googleToken = (sub: string, email: string, name?: string) =>
    `${enc({ alg: 'RS256' })}.${enc({
      iss: 'https://accounts.google.com',
      aud: 'web-client',
      sub,
      email,
      email_verified: true,
      ...(name ? { name, picture: 'https://pic/x.jpg' } : {})
    })}.signature`

  const usersCollection = {
    findOne: vi.fn(),
    insertOne: vi.fn(),
    updateOne: vi.fn()
  }

  const mockGoogle = (sub: string, email: string, name?: string) => {
    vi.mocked(verifyGoogleIdToken).mockResolvedValue({
      sub,
      email,
      emailVerified: true,
      name: name ?? null,
      picture: name ? 'https://pic/x.jpg' : null
    })
  }

  beforeEach(() => {
    vi.clearAllMocks()
    process.env.GOOGLE_WEB_CLIENT_ID = 'web-client'
    usersCollection.updateOne.mockResolvedValue({ modifiedCount: 1 })
    vi.spyOn(mongo, 'collection').mockReturnValue(usersCollection as never)
  })

  it('O: first login auto-creates a username from the display name', async () => {
    mockGoogle('s1', 'ashu@example.com', 'Ashu Pathak')
    usersCollection.findOne.mockResolvedValue(null)
    usersCollection.insertOne.mockResolvedValue({ insertedId: { toString: () => 'u1' } })
    const svc = new FirebaseAuthService()
    const result = await svc.exchange(googleToken('s1', 'ashu@example.com', 'Ashu Pathak'))
    expect(result.user.username).toBe('ashupathak')
    const doc = usersCollection.insertOne.mock.calls[0][0] as Record<string, unknown>
    expect(doc.username).toBe('ashupathak')
    expect(doc.displayName).toBe('Ashu Pathak')
    expect(doc.photoUrl).toBe('https://pic/x.jpg')
  })

  it('N: same UID re-login keeps the stored username (never re-derived)', async () => {
    mockGoogle('s1', 'ashu@example.com', 'Totally Different')
    usersCollection.findOne.mockImplementation(async (query: Record<string, unknown>) => {
      if (query.email) {
        return {
          _id: { toString: () => 'u1' },
          email: 'ashu@example.com',
          firebaseUid: 'google:s1',
          username: 'ashu_rocks',
          createdAt: new Date('2024-01-01')
        }
      }
      return null
    })
    const svc = new FirebaseAuthService()
    const result = await svc.exchange(googleToken('s1', 'ashu@example.com', 'Totally Different'))
    // Same UID (google:s1) → stored name wins over the new display name.
    expect(result.user.username).toBe('ashu_rocks')
  })

  it('P: collisions resolve deterministically (base, base2, …)', async () => {
    mockGoogle('s2', 'ashu2@example.com', 'Ashu')
    usersCollection.findOne.mockImplementation(async (query: Record<string, unknown>) => {
      if (query.email) return null
      if (query.username === 'ashu') return { _id: { toString: () => 'other' } }
      return null
    })
    usersCollection.insertOne.mockResolvedValue({ insertedId: { toString: () => 'u2' } })
    const svc = new FirebaseAuthService()
    const result = await svc.exchange(googleToken('s2', 'ashu2@example.com', 'Ashu'))
    expect(result.user.username).toBe('ashu2')
  })

  it('Q: different UIDs get isolated usernames', async () => {
    usersCollection.findOne.mockImplementation(async (query: Record<string, unknown>) => {
      if (query.email === 'a@example.com') {
        return { _id: { toString: () => 'ua' }, email: 'a@example.com', firebaseUid: 'google:a', username: 'alpha' }
      }
      if (query.email) return null
      return null
    })
    usersCollection.insertOne.mockResolvedValue({ insertedId: { toString: () => 'ub' } })
    const svc = new FirebaseAuthService()
    mockGoogle('a', 'a@example.com', 'Alpha')
    const again = await svc.exchange(googleToken('a', 'a@example.com', 'Alpha'))
    expect(again.user.username).toBe('alpha')
    mockGoogle('b', 'b@example.com', 'Beta')
    const fresh = await svc.exchange(googleToken('b', 'b@example.com', 'Beta'))
    expect(fresh.user.username).toBe('beta')
  })

  it('migrates old accounts without a username automatically', async () => {
    mockGoogle('old', 'old@example.com', 'Old Timer')
    usersCollection.findOne.mockImplementation(async (query: Record<string, unknown>) => {
      if (query.email) {
        return { _id: { toString: () => 'u9' }, email: 'old@example.com', firebaseUid: 'google:old' }
      }
      return null
    })
    const svc = new FirebaseAuthService()
    const result = await svc.exchange(googleToken('old', 'old@example.com', 'Old Timer'))
    expect(result.user.username).toBe('oldtimer')
    expect(usersCollection.updateOne).toHaveBeenCalled()
  })
})

/**
 * Regression: user creation must include tokenHash in the INSERT itself.
 *
 * The unique index on users.tokenHash was not sparse in existing
 * deployments; inserting a user without tokenHash indexes it as null, and
 * the second user ever created without one fails with E11000 -> HTTP 500.
 * That bug broke Google sign-in and verified-email exchange for every new
 * user once a single tokenHash-less document existed (e.g. from password
 * registration, which mints no session at register time).
 */
describe('FirebaseAuthService new-user insert', () => {
  const enc = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const googleToken = `${enc({ alg: 'RS256' })}.${enc({
    iss: 'https://accounts.google.com',
    aud: 'web-client',
    sub: 's1',
    email: 'user@example.com',
    email_verified: true,
    exp: 999999999999
  })}.signature`

  const usersCollection = {
    findOne: vi.fn(),
    insertOne: vi.fn(),
    updateOne: vi.fn()
  }

  beforeEach(() => {
    vi.clearAllMocks()
    process.env.GOOGLE_WEB_CLIENT_ID = 'web-client'
    usersCollection.findOne.mockResolvedValue(null)
    usersCollection.insertOne.mockResolvedValue({ insertedId: { toString: () => 'u1' } })
    usersCollection.updateOne.mockResolvedValue({ modifiedCount: 1 })
    vi.spyOn(mongo, 'collection').mockReturnValue(usersCollection as never)
  })

  it('inserts new users WITH a tokenHash (never null on the unique index)', async () => {
    const svc = new FirebaseAuthService()
    const result = await svc.exchange(googleToken)

    expect(usersCollection.insertOne).toHaveBeenCalledTimes(1)
    const doc = usersCollection.insertOne.mock.calls[0][0] as Record<string, unknown>
    expect(typeof doc.tokenHash).toBe('string')
    expect((doc.tokenHash as string).length).toBeGreaterThan(16)

    // The returned session token must be the one whose hash was stored.
    expect(result.token.length).toBeGreaterThan(16)
  })
})
