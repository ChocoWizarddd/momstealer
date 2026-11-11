import { randomBytes } from 'node:crypto'
import { collection, Collections, getDb } from '#common/database/mongo'
import { hashToken } from '#common/middleware/auth'
import type { ObjectId } from 'mongodb'

export interface AuthUserRecord {
  _id: ObjectId
  deviceId: string
  tokenHash: string
  createdAt: Date
}

export interface RegisterResult {
  token: string
  user: { id: string; deviceId: string; createdAt: string }
}

/**
 * Repository for user/session records. Tokens are stored hashed (SHA-256);
 * the plaintext token is returned exactly once at registration.
 */
export class AuthRepository {
  private users = () => collection(Collections.USERS)

  findUserByDeviceId(deviceId: string) {
    return this.users().findOne({ deviceId })
  }

  /**
   * Registers a device. If the device already exists, its token is rotated so
   * a lost/stolen token stops working while the user's data is preserved.
   */
  async register(deviceId: string, platform: string, appVersion: string): Promise<RegisterResult> {
    const token = randomBytes(32).toString('base64url')
    const tokenHash = hashToken(token)

    const existing = await this.users().findOne({ deviceId })
    const now = new Date()

    let userId: string
    if (existing) {
      await this.users().updateOne(
        { _id: existing._id },
        {
          $set: {
            tokenHash,
            platform,
            appVersion,
            lastSeenAt: now
          }
        }
      )
      userId = existing._id.toString()
    } else {
      const result = await this.users().insertOne({
        deviceId,
        tokenHash,
        platform,
        appVersion,
        createdAt: now,
        lastSeenAt: now
      })
      userId = result.insertedId.toString()
    }

    return { token, user: { id: userId, deviceId, createdAt: now.toISOString() } }
  }
}

export class AuthService {
  private repository = new AuthRepository()

  register(deviceId: string, platform: string, appVersion: string) {
    return this.repository.register(deviceId, platform, appVersion)
  }

  async getProfile(userId: string) {
    const db = getDb()
    const [likes, playlists, prefs] = await Promise.all([
      db.collection(Collections.LIKES).countDocuments({ userId }),
      db.collection(Collections.PLAYLISTS).countDocuments({ userId }),
      db.collection(Collections.PREFERENCES).findOne({ userId })
    ])

    return {
      id: userId,
      stats: {
        likedTracks: likes,
        playlists
      },
      preferences: prefs
        ? {
            repeatMode: prefs.repeatMode ?? null,
            shuffle: prefs.shuffle ?? null
          }
        : null
    }
  }
}
