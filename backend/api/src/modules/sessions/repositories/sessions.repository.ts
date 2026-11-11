import { collection, Collections } from '#common/database/mongo'
import type { Document } from 'mongodb'

export interface ChatMessageRecord {
  id: string
  deviceId: string
  name: string
  text: string
  createdAt: number
}

export interface MemberRecord {
  deviceId: string
  name: string
  joinedAt: number
}

export interface TrackSnapshot {
  id: string
  name: string
  artists: string
  album: string
  imageUrl: string
  duration: number
  year: string
}

export interface SessionStateRecord {
  track: TrackSnapshot | null
  positionMs: number
  playing: boolean
  updatedAt: number
}

const MAX_CHAT_MESSAGES = 100

export interface SessionRecord {
  code: string
  owner: string
  members: MemberRecord[]
  state: SessionStateRecord
  messages: ChatMessageRecord[]
  createdAt: number
}

interface SessionDoc extends Document {
  code: string
  owner: string
  members: MemberRecord[]
  state: SessionStateRecord
  messages?: ChatMessageRecord[]
  createdAt?: number
  updatedAt?: number
}

export class SessionsRepository {
  private collection() {
    return collection<SessionDoc>(Collections.SESSIONS)
  }

  async create(code: string, owner: string, name: string, track: TrackSnapshot): Promise<SessionRecord> {
    const now = Date.now()
    const doc: SessionDoc = {
      code,
      owner,
      members: [{ deviceId: owner, name, joinedAt: now }],
      state: {
        track,
        positionMs: 0,
        playing: true,
        updatedAt: now
      },
      createdAt: now,
      messages: []
    }
    await this.collection().insertOne(doc)
    return this.lean(doc)
  }

  async find(code: string): Promise<SessionRecord | null> {
    const doc = await this.collection().findOne({ code })
    return doc ? this.lean(doc) : null
  }

  async join(code: string, deviceId: string, name: string): Promise<SessionRecord | null> {
    const doc = await this.collection().findOne({ code })
    if (!doc) return null
    const already = doc.members.some((m) => m.deviceId === deviceId)
    if (!already) {
      await this.collection().updateOne(
        { code },
        // Typed collections reject dotted updates; this doc is ours.
        { $push: { members: { deviceId, name, joinedAt: Date.now() } } } as never
      )
    }
    const fresh = await this.collection().findOne({ code })
    return fresh ? this.lean(fresh) : null
  }

  async updateState(
    code: string,
    deviceId: string,
    patch: { track?: TrackSnapshot | null; positionMs?: number; playing?: boolean }
  ): Promise<SessionRecord | null> {
    const now = Date.now()
    const update: Record<string, unknown> = { 'state.updatedAt': now, updatedAt: now }
    if (patch.track !== undefined) update['state.track'] = patch.track
    if (patch.positionMs !== undefined) update['state.positionMs'] = Math.max(0, Math.round(patch.positionMs))
    if (patch.playing !== undefined) update['state.playing'] = Boolean(patch.playing)
    await this.collection().updateOne({ code, owner: deviceId }, { $set: update } as never)
    const fresh = await this.collection().findOne({ code })
    return fresh ? this.lean(fresh) : null
  }

  async addChat(code: string, deviceId: string, name: string, text: string): Promise<ChatMessageRecord | null> {
    const clean = text.trim().slice(0, 280)
    if (!clean) return null
    const now = Date.now()
    const message: ChatMessageRecord = {
      id: `${now}-${Math.random().toString(36).slice(2, 8)}`,
      deviceId,
      name: (name || 'Listener').trim().slice(0, 60),
      text: clean,
      createdAt: now
    }
    const result = await this.collection().findOneAndUpdate(
      { code },
      { $push: { messages: { $each: [message], $slice: -100 } }, $set: { updatedAt: now } } as never,
      { returnDocument: 'after' }
    )
    return result?.messages?.at(-1) ?? null
  }

  async getChat(code: string, after = 0): Promise<ChatMessageRecord[]> {
    const doc = await this.collection().findOne({ code })
    return (doc?.messages ?? []).filter((m) => m.createdAt > Math.max(0, after)).slice(-100)
  }

  async touch(code: string): Promise<void> {
    await this.collection().updateOne({ code }, { $set: { updatedAt: Date.now() } })
  }

  /**
   * Append a chat message to the session. Bounded to MAX_CHAT_MESSAGES by
   * trimming oldest messages when the array exceeds the limit.
   */
  async addMessage(code: string, msg: Omit<ChatMessageRecord, 'id' | 'createdAt'>): Promise<ChatMessageRecord> {
    const now = Date.now()
    const message: ChatMessageRecord = {
      id: `${now}-${Math.random().toString(36).slice(2, 8)}`,
      createdAt: now,
      ...msg
    }
    await this.collection().updateOne({ code }, {
      $push: { messages: { $each: [message], $slice: -MAX_CHAT_MESSAGES } },
      $set: { updatedAt: now, 'state.updatedAt': now }
    } as never)
    return message
  }

  /**
   * Fetch chat messages newer than `since` (timestamp). Returns an empty array
   * when `since` is >= the newest message.
   */
  getMessages(code: string, since = 0): Promise<ChatMessageRecord[]> {
    return this.getChat(code, since)
  }

  private lean(doc: SessionDoc): SessionRecord {
    return {
      code: doc.code,
      owner: doc.owner,
      members: doc.members ?? [],
      state: doc.state,
      messages: doc.messages ?? [],
      createdAt: doc.createdAt ?? 0
    }
  }
}
