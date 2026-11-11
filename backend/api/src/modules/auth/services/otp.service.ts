import { createHash, randomBytes, randomInt } from 'node:crypto'
import process from 'node:process'
import { collection, Collections, getDb } from '#common/database/mongo'
import { ApiError } from '#common/errors/api-error'
import { hashToken } from '#common/middleware/auth'
import type { ObjectId } from 'mongodb'

/**
 * Email OTP authentication for NightLight.
 *
 * Security contract:
 *  - OTPs are 6-digit and generated with a CSPRNG.
 *  - Only a SHA-256 hash is stored; the plaintext code is never persisted.
 *  - Expiry is 10 minutes; verification is capped at 5 attempts; a new code
 *    invalidates the previous one; resend is rate-limited to 60 seconds.
 *  - The code is delivered by Resend (server-side env vars only). When no
 *    RESEND_API_KEY is configured and we are NOT in production, the code is
 *    printed to the server console as a local development channel only — it is
 *    never returned in an API response and never leaves the server. In
 *    production without a key, requests fail gracefully instead of faking
 *    delivery.
 */

const OTP_TTL_MS = 10 * 60 * 1000 // 10 minutes
const MAX_ATTEMPTS = 5
const RESEND_COOLDOWN_MS = 60 * 1000 // 60 seconds
const PER_EMAIL_HOUR_LIMIT = 5

export interface RequestOtpResult {
  expiresIn: number // seconds
  resendAfter: number // seconds
  emailSent: boolean
  devDelivery: boolean
}

export interface VerifyOtpResult {
  token: string
  user: { id: string; email: string; createdAt: string }
  preferences: { languages: string[]; categories: string[] } | null
}

interface OtpRecord {
  _id: ObjectId
  email: string
  purpose: string
  codeHash: string
  expiresAt: Date
  createdAt: Date
  attempts: number
  used: boolean
}

function normalizeEmail(raw: string): string {
  const email = (raw ?? '').trim().toLowerCase()
  if (!/^[^\s@]+@[^\s@][^\s.@]*\.[^\s@]{2,}$/.test(email)) {
    throw ApiError.badRequest('Enter a valid email address', 'INVALID_EMAIL')
  }
  if (email.length > 254) {
    throw ApiError.badRequest('Enter a valid email address', 'INVALID_EMAIL')
  }
  return email
}

function sha256(value: string): string {
  return createHash('sha256').update(value).digest('hex')
}

/** Delivers the code. Returns true when a channel actually delivered it. */
async function deliverCode(
  email: string,
  code: string,
  expiresInMin: number
): Promise<{ sent: boolean; dev: boolean }> {
  const key = process.env.RESEND_API_KEY
  if (key) {
    const from = process.env.OTP_FROM_EMAIL || 'NightLight <onboarding@resend.dev>'
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${key}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        from,
        to: [email],
        subject: 'Your NightLight verification code',
        text:
          `Your NightLight verification code is ${code}.\n\n` +
          `It expires in ${expiresInMin} minutes.\n\n` +
          `If you did not request this code, you can safely ignore this email.`
      })
    })
    if (!res.ok) {
      console.error(`[NightLight] OTP email delivery failed: HTTP ${res.status}`)
      throw ApiError.internal('Could not send the verification email right now.')
    }
    return { sent: true, dev: false }
  }

  // Local development channel only. The code goes to the server console, never
  // into an API response and never into any client.
  if (process.env.NODE_ENV !== 'production') {
    console.info(`[NightLight] [dev] OTP for ${email}: ${code} (expires in ${expiresInMin} min)`)
    return { sent: true, dev: true }
  }

  throw new ApiError(503, 'EMAIL_SERVICE_NOT_CONFIGURED', 'Email service is not configured yet.')
}

export class OtpService {
  private otps = () => collection(Collections.OTPS)

  /**
   * `purpose` scopes codes so a login code can never authorize a password
   * reset and vice-versa. Existing login flows use the default.
   */
  async requestOtp(rawEmail: string, purpose: 'login' | 'reset' = 'login'): Promise<RequestOtpResult> {
    const email = normalizeEmail(rawEmail)
    const now = Date.now()

    // Per-email hourly cap (in addition to the per-IP controller limiter).
    const recentCount = await this.otps().countDocuments({
      email,
      purpose,
      createdAt: { $gte: new Date(now - 60 * 60 * 1000) }
    })
    if (recentCount >= PER_EMAIL_HOUR_LIMIT) {
      throw ApiError.rateLimited('Too many codes requested for this email. Try again in an hour.')
    }

    // 60s resend cooldown against the latest code for this purpose.
    const latest = (await this.otps().findOne({ email, purpose }, { sort: { createdAt: -1 } })) as OtpRecord | null
    if (latest) {
      const elapsed = now - latest.createdAt.getTime()
      if (elapsed < RESEND_COOLDOWN_MS) {
        throw new ApiError(429, 'RESEND_COOLDOWN', 'A code was just sent. Please wait before requesting another.', {
          resendAfter: Math.ceil((RESEND_COOLDOWN_MS - elapsed) / 1000)
        })
      }
    }

    const code = randomInt(0, 1_000_000).toString().padStart(6, '0')
    const expiresAt = new Date(now + OTP_TTL_MS)

    // Invalidate any previous code for this email+purpose (latest wins).
    await this.otps().updateMany({ email, purpose, used: false }, { $set: { used: true, supersededAt: new Date(now) } })

    await this.otps().insertOne({
      email,
      purpose,
      codeHash: sha256(code),
      expiresAt,
      createdAt: new Date(now),
      attempts: 0,
      used: false
    })

    const expiresInMin = Math.max(1, Math.round(OTP_TTL_MS / 60_000))
    const delivery = await deliverCode(email, code, expiresInMin)

    return {
      expiresIn: OTP_TTL_MS / 1000,
      resendAfter: RESEND_COOLDOWN_MS / 1000,
      emailSent: delivery.sent,
      devDelivery: delivery.dev
    }
  }

  async verifyOtp(rawEmail: string, rawCode: string): Promise<VerifyOtpResult> {
    const email = await this.consumeOtp(rawEmail, rawCode)

    // Issue a session token through the same hashed-token session system the
    // device registration flow uses, so one architecture serves both.
    const { token, user } = await this.issueSessionForEmail(email)

    const preferences = await getDb().collection(Collections.PREFERENCES).findOne({ userId: user.id })
    const prefs =
      preferences && (Array.isArray(preferences.languages) || Array.isArray(preferences.categories))
        ? {
            languages: Array.isArray(preferences.languages) ? preferences.languages : [],
            categories: Array.isArray(preferences.categories) ? preferences.categories : []
          }
        : null

    return { token, user, preferences: prefs }
  }

  /**
   * Validates an emailed code for ANY purpose (login, password reset) and
   * marks it used. The full security contract (hash-at-rest, expiry, attempt
   * cap, latest-code-wins) is enforced here exactly once for every consumer.
   * Returns the normalized email on success.
   */
  async consumeOtp(rawEmail: string, rawCode: string, purpose: 'login' | 'reset' = 'login'): Promise<string> {
    const email = normalizeEmail(rawEmail)
    const code = (rawCode ?? '').trim()
    if (!/^\d{6}$/.test(code)) {
      throw ApiError.badRequest('The code must be 6 digits', 'OTP_INVALID')
    }

    const record = (await this.otps().findOne({ email, purpose }, { sort: { createdAt: -1 } })) as OtpRecord | null

    const now = Date.now()
    if (!record || record.used || record.expiresAt.getTime() < now) {
      throw new ApiError(400, 'OTP_EXPIRED', 'This code has expired. Request a new one.')
    }

    if (record.attempts >= MAX_ATTEMPTS) {
      await this.otps().updateOne({ _id: record._id }, { $set: { used: true } })
      throw new ApiError(429, 'OTP_TOO_MANY_ATTEMPTS', 'Too many incorrect attempts. Request a new code.')
    }

    if (sha256(code) !== record.codeHash) {
      const attempts = record.attempts + 1
      await this.otps().updateOne({ _id: record._id }, { $set: { attempts } })
      throw new ApiError(400, 'OTP_INVALID', 'That code is incorrect.', {
        attemptsLeft: Math.max(0, MAX_ATTEMPTS - attempts)
      })
    }

    await this.otps().updateOne({ _id: record._id }, { $set: { used: true, usedAt: new Date(now) } })
    return email
  }

  /** Finds or creates the user record for an email and issues a fresh token. */
  private async issueSessionForEmail(email: string) {
    const users = collection(Collections.USERS)
    const rawToken = randomBytes(32).toString('base64url')
    const tokenHash = hashToken(rawToken)
    const now = new Date()

    const existing = (await users.findOne({ email })) as { _id: ObjectId; createdAt: Date } | null
    if (existing) {
      await users.updateOne({ _id: existing._id }, { $set: { tokenHash, lastSeenAt: now, emailVerifiedAt: now } })
      return {
        token: rawToken,
        user: { id: existing._id.toString(), email, createdAt: existing.createdAt.toISOString() }
      }
    }

    const result = await users.insertOne({
      email,
      tokenHash,
      platform: 'android',
      appVersion: '1.0.0',
      createdAt: now,
      lastSeenAt: now,
      emailVerifiedAt: now
    })
    return {
      token: rawToken,
      user: { id: result.insertedId.toString(), email, createdAt: now.toISOString() }
    }
  }

  async savePreferences(userId: string, languages: string[], categories: string[]): Promise<void> {
    const clean = (items: string[]) =>
      Array.from(new Set((items ?? []).filter((s) => typeof s === 'string' && s.trim().length > 0)))
    await getDb()
      .collection(Collections.PREFERENCES)
      .updateOne(
        { userId },
        {
          $set: {
            languages: clean(languages),
            categories: clean(categories),
            updatedAt: new Date()
          }
        },
        { upsert: true }
      )
  }
}
