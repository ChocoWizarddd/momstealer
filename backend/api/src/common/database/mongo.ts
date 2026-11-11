import process from 'node:process'
import { MongoClient, type Collection, type Db, type Document, type IndexSpecification } from 'mongodb'

let client: MongoClient | null = null
let db: Db | null = null

/**
 * Collection names for NightLight user data. Music-provider data is never
 * stored here; MongoDB only holds application-owned user data.
 */
export const Collections = {
  USERS: 'users',
  LIKES: 'likes',
  RECENTLY_PLAYED: 'recentlyPlayed',
  SEARCH_HISTORY: 'searchHistory',
  PLAYLISTS: 'playlists',
  PLAYLIST_TRACKS: 'playlistTracks',
  PREFERENCES: 'preferences',
  SESSIONS: 'sessions',
  OTPS: 'otps',
  SEARCH_EVENTS: 'searchEvents',
  PLAY_EVENTS: 'playEvents'
} as const

/**
 * Create an index, self-healing when an existing index with the same name has
 * different options (e.g. a pre-existing non-sparse `deviceId_1` from an older
 * deployment). Drops and recreates in that case instead of failing startup.
 */
async function createIndexHealing<T extends Document>(
  col: Collection<T>,
  spec: IndexSpecification,
  options: { name?: string } & Record<string, unknown>
) {
  try {
    await col.createIndex(spec, options)
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error)
    const isIndexConflict = /IndexOptionsConflict|IndexKeySpecsConflict|same name as the requested index/i.test(msg)
    if (!isIndexConflict) throw error
    const probe = await col.listIndexes().toArray()
    const dup = probe.find((ix) => JSON.stringify(ix.key) === JSON.stringify(spec))
    if (dup && typeof dup.name === 'string') await col.dropIndex(dup.name)
    await col.createIndex(spec, options)
  }
}

async function ensureIndexes(database: Db) {
  const users = database.collection(Collections.USERS)
  await createIndexHealing(users, { deviceId: 1 }, { unique: true, sparse: true, name: 'deviceId_1' })
  await createIndexHealing(users, { email: 1 }, { unique: true, sparse: true, name: 'email_1' })
  await users.createIndex({ deviceId: 1 }, { unique: true, sparse: true })
  await users.createIndex({ email: 1 }, { unique: true, sparse: true })
  // tokenHash MUST be sparse: password registration and Firebase/Google
  // exchange insert users before a session exists, so the field is missing.
  // A non-sparse unique index collapses all such documents onto the single
  // `null` slot and every subsequent user creation fails with E11000.
  await createIndexHealing(users, { tokenHash: 1 }, { unique: true, sparse: true, name: 'tokenHash_1' })
  // Usernames are sparse (old docs predate them) + unique (one permanent
  // name per Firebase account). Sparse skips docs missing the field.
  await createIndexHealing(users, { username: 1 }, { unique: true, sparse: true, name: 'username_1' })

  const otps = database.collection(Collections.OTPS)
  await otps.createIndex({ email: 1, createdAt: -1 })
  await otps.createIndex({ expiresAt: 1 }, { expireAfterSeconds: 0 })

  const likes = database.collection(Collections.LIKES)
  await likes.createIndex({ userId: 1, trackId: 1 }, { unique: true })
  await likes.createIndex({ userId: 1, createdAt: -1 })

  const recentlyPlayed = database.collection(Collections.RECENTLY_PLAYED)
  await recentlyPlayed.createIndex({ userId: 1, trackId: 1 }, { unique: true })
  await recentlyPlayed.createIndex({ userId: 1, playedAt: -1 })

  const searchHistory = database.collection(Collections.SEARCH_HISTORY)
  await searchHistory.createIndex({ userId: 1, createdAt: -1 })

  const playlists = database.collection(Collections.PLAYLISTS)
  await playlists.createIndex({ userId: 1, createdAt: -1 })

  const playlistTracks = database.collection(Collections.PLAYLIST_TRACKS)
  await playlistTracks.createIndex({ playlistId: 1, position: 1 }, { unique: true })
  await playlistTracks.createIndex({ playlistId: 1, trackId: 1 }, { unique: true })

  const preferences = database.collection(Collections.PREFERENCES)
  await preferences.createIndex({ userId: 1 }, { unique: true })

  // NightLight-owned popularity events. Minimal rows, 180-day retention
  // bounds growth; aggregates are computed from these (never fabricated).
  const searchEvents = database.collection(Collections.SEARCH_EVENTS)
  await searchEvents.createIndex({ createdAt: -1 })
  await searchEvents.createIndex({ query: 1, createdAt: -1 })
  await searchEvents.createIndex({ createdAt: 1 }, { expireAfterSeconds: 180 * 24 * 3600 })

  const playEvents = database.collection(Collections.PLAY_EVENTS)
  await playEvents.createIndex({ userId: 1, trackKey: 1, createdAt: -1 })
  await playEvents.createIndex({ createdAt: 1 }, { expireAfterSeconds: 180 * 24 * 3600 })

  const sessions = database.collection(Collections.SESSIONS)
  await sessions.createIndex({ code: 1 }, { unique: true })
  // Expire idle listen-together sessions after 3 hours.
  await sessions.createIndex({ updatedAt: 1 }, { expireAfterSeconds: 3 * 60 * 60 })
}

export async function connectMongo(): Promise<Db> {
  if (db) return db

  const uri = process.env.MONGODB_URI
  if (!uri) {
    throw new Error('MONGODB_URI environment variable is not set')
  }

  client = new MongoClient(uri, {
    serverSelectionTimeoutMS: 10000,
    connectTimeoutMS: 10000,
    maxPoolSize: 20,
    // Stable API is supported by the installed driver (mongodb ^7) and by
    // MongoDB Atlas, keeping the deployed runtime predictable.
    serverApi: { version: '1' }
  })

  await client.connect()
  db = client.db(process.env.MONGODB_DB_NAME || 'nightlight')
  await ensureIndexes(db)
  return db
}

export function getDb(): Db {
  if (!db) throw new Error('MongoDB has not been connected yet')
  return db
}

export function collection<T extends Document = Document>(name: string): Collection<T> {
  return getDb().collection<T>(name)
}

export async function closeMongo(): Promise<void> {
  await client?.close()
  client = null
  db = null
}
