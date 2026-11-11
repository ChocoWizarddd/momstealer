import { randomBytes } from 'node:crypto'
import { collection, Collections } from '#common/database/mongo'
import { hashToken } from '#common/middleware/auth'
import { ApiError } from '#common/errors/api-error'
import { firebaseConfigured, verifyFirebaseIdToken } from './firebase.service'
import { googleConfigured, verifyGoogleIdToken } from '#common/identity/google-token.service'

import {
  isValidUsername,
  normalizeUsername,
  RESERVED_USERNAMES,
  USERNAME_MAX,
  USERNAME_MIN,
  usernameBase
} from './username.service'

/**
 * Verify Google Sign-In tokens with Google's public keys and Firebase ID tokens
 * with Firebase's public keys. Neither flow needs a server-side service-account
 * private key. Set GOOGLE_WEB_CLIENT_ID and/or FIREBASE_PROJECT_ID to enable
 * the matching token issuer.
 */

export interface FirebaseIdentity {
  uid: string
  email: string | null
  emailVerified: boolean
  name: string | null
  picture: string | null
}

export interface ExchangeResult {
  token: string
  user: {
    id: string
    email: string
    username: string
    displayName: string | null
    photoUrl: string | null
    createdAt: string
  }
}

/** Mongo duplicate-key errors carry code 11000. */
function isDuplicateKeyError(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && (err as { code?: unknown }).code === 11000
}

export class FirebaseAuthService {
  private users = () => collection(Collections.USERS)

  async exchange(idToken: string): Promise<ExchangeResult> {
    if (!idToken || typeof idToken !== 'string' || idToken.length < 32) {
      throw ApiError.badRequest('A sign-in token is required', 'FIREBASE_TOKEN_INVALID')
    }

    let decodedToken: any
    let uid: string
    if (!googleConfigured() && !firebaseConfigured()) {
      throw new ApiError(503, 'FIREBASE_AUTH_UNAVAILABLE', 'Authentication is not configured on this deployment.')
    }

    try {
      const googleIdentity = await verifyGoogleIdToken(idToken)
      decodedToken = googleIdentity
      // Keep Google identities namespaced so they cannot collide with Firebase UIDs.
      uid = `google:${googleIdentity.sub}`
    } catch (googleError) {
      try {
        decodedToken = await verifyFirebaseIdToken(idToken)
        uid = decodedToken.uid
      } catch (firebaseError) {
        console.warn(`[NightLight] Sign-in token verification failed: ${firebaseError ?? googleError}`)
        throw new ApiError(401, 'FIREBASE_TOKEN_INVALID', 'Sign-in could not be verified. Please sign in again.')
      }
    }

    const { email, name, picture } = decodedToken

    if (!email) {
      throw new ApiError(401, 'FIREBASE_TOKEN_INVALID', 'Sign-in token missing email.')
    }

    const emailLower = email.trim().toLowerCase()
    const now = new Date()

    // The session token is generated up-front and stored WITH the insert.
    // Users must never be created without a tokenHash: the unique index on
    // tokenHash treats a missing field as null, so every user inserted
    // without one would collide on the single null slot (E11000 -> 500).
    const rawToken = randomBytes(32).toString('base64url')
    const tokenHash = hashToken(rawToken)

    let user = (await this.users().findOne({ email: emailLower })) as {
      _id: { toString: () => string }
      email?: string
      firebaseUid?: string
      username?: string
      displayName?: string
      photoUrl?: string
      tokenHash?: string
      createdAt?: Date
    } | null
    let created = false
    // ONE Firebase account = ONE permanent username: derived once from the
    // verified display name, collision-resolved deterministically, persisted.
    // Later logins reload it — the client never prompts again.
    const base = usernameBase(name ?? null, emailLower)
    if (!user) {
      const username = await this.claimUsername(base, null)
      try {
        const inserted = await this.users().insertOne({
          email: emailLower,
          firebaseUid: uid,
          username,
          displayName: name ?? null,
          photoUrl: picture ?? null,
          tokenHash,
          platform: 'android',
          createdAt: now,
          lastSeenAt: now
        })
        user = {
          _id: inserted.insertedId,
          email: emailLower,
          firebaseUid: uid,
          username,
          displayName: name ?? undefined,
          photoUrl: picture ?? undefined,
          createdAt: now
        }
        created = true
      } catch (error) {
        // Concurrent first sign-in for the same verified email: re-read the
        // winner's document instead of failing the exchange.
        if (!isDuplicateKeyError(error)) throw error
        user = (await this.users().findOne({ email: emailLower })) as {
          _id: { toString: () => string }
          email?: string
          firebaseUid?: string
          username?: string
          displayName?: string
          photoUrl?: string
          tokenHash?: string
          createdAt?: Date
        } | null
        if (!user) throw error
      }
    }
    if (user.firebaseUid && user.firebaseUid !== uid) {
      // Different identity claiming the same verified email: the mailbox was
      // re-registered upstream (Firebase or Google). Re-point the account.
      await this.users().updateOne({ _id: user._id }, { $set: { firebaseUid: uid } })
    }
    // Automatic migration: old accounts without a username get one now.
    let username = user.username ?? null
    if (!username || !isValidUsername(username)) {
      username = await this.claimUsername(base, user._id.toString())
      await this.users().updateOne({ _id: user._id }, { $set: { username } })
    }
    const profileUpdate: Record<string, unknown> = { tokenHash, lastSeenAt: now, username }
    if (name) profileUpdate.displayName = name
    if (picture) profileUpdate.photoUrl = picture
    if (!created) {
      await this.users().updateOne({ _id: user._id }, { $set: profileUpdate })
    }

    return {
      token: rawToken,
      user: {
        id: user._id.toString(),
        email: emailLower,
        username,
        displayName: name ?? user.displayName ?? null,
        photoUrl: picture ?? user.photoUrl ?? null,
        createdAt: (user.createdAt ?? now).toISOString()
      }
    }
  }

  /**
   * Claims a deterministic unique username, excluding the caller's own
   * document (stable across re-logins). Same candidate order as
   * resolveCollision (base, base2, …) so unit behavior and DB behavior agree.
   */
  private async claimUsername(base: string, excludeId: string | null): Promise<string> {
    let candidate = normalizeUsername(base) || 'listener'
    if (RESERVED_USERNAMES.has(candidate)) candidate = `${candidate}_fan`
    const taken = async (name: string): Promise<boolean> => {
      const hit = (await this.users().findOne(
        { username: name },
        { projection: { _id: 1 } }
      )) as { _id: { toString: () => string } } | null
      return !!hit && hit._id.toString() !== excludeId
    }
    if (!(await taken(candidate))) return candidate
    for (let i = 2; ; i++) {
      const suffix = String(i)
      const next = `${candidate.slice(0, Math.max(1, USERNAME_MAX - suffix.length))}${suffix}`
      if (next.length >= USERNAME_MIN && !(await taken(next))) return next
    }
  }
}
