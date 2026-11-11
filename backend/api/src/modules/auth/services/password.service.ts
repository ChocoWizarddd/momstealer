import { Buffer } from 'node:buffer'
import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto'
import { collection, Collections } from '#common/database/mongo'
import { ApiError } from '#common/errors/api-error'
import { hashToken } from '#common/middleware/auth'
import { OtpService } from './otp.service'

/**
 * Email + password authentication for NightLight.
 *
 * Security contract:
 *  - Passwords are hashed with scrypt (CSPRNG salt, timing-safe compare);
 *    plaintext passwords are never stored or logged.
 *  - Sessions reuse the existing single-token hashed session system
 *    (SHA-256 at rest on the user record, plaintext returned exactly once).
 *    Logout rotates the stored hash, which invalidates the client token.
 *  - Registration proves mailbox ownership through the hardened OtpService;
 *    the session is only issued by /auth/verify-otp after the code checks out.
 *  - Password reset reuses the OTP engine with purpose='reset' so a login
 *    code can never authorize a reset and vice-versa. Reset tokens are
 *    one-time, 10-minute, hashed at rest, and never returned in logs.
 *  - Unknown-email reset requests get an identical response to known ones
 *    (enumeration-safe).
 */

const RESET_TTL_MS = 10 * 60 * 1000
const SCRYPT_KEYLEN = 64
const MIN_PASSWORD_LENGTH = 8
const MAX_PASSWORD_LENGTH = 128

interface UserDoc {
  _id: { toString: () => string }
  email?: string
  passwordHash?: string
  tokenHash?: string
  createdAt?: Date
}

function normalizeEmail(raw: string): string {
  const email = (raw ?? '').trim().toLowerCase()
  if (!/^[^\s@]+@[^\s@][^\s.@]*\.[^\s@]{2,}$/.test(email) || email.length > 254) {
    throw ApiError.badRequest('Enter a valid email address', 'INVALID_EMAIL')
  }
  return email
}

function assertPasswordStrength(password: string): void {
  if (typeof password !== 'string' || password.length < MIN_PASSWORD_LENGTH) {
    throw ApiError.badRequest(`Password must be at least ${MIN_PASSWORD_LENGTH} characters`, 'PASSWORD_WEAK')
  }
  if (password.length > MAX_PASSWORD_LENGTH) {
    throw ApiError.badRequest('Password is too long', 'PASSWORD_WEAK')
  }
  if (!/[a-z]/i.test(password) || !/\d/.test(password)) {
    throw ApiError.badRequest('Password must contain letters and numbers', 'PASSWORD_WEAK')
  }
}

function hashPassword(password: string): string {
  const salt = randomBytes(16)
  const hash = scryptSync(password, salt, SCRYPT_KEYLEN)
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`
}

function verifyPassword(password: string, stored: string): boolean {
  try {
    const [scheme, saltHex, hashHex] = stored.split('$')
    if (scheme !== 'scrypt' || !saltHex || !hashHex) return false
    const expected = Buffer.from(hashHex, 'hex')
    const actual = scryptSync(password, Buffer.from(saltHex, 'hex'), expected.length)
    return timingSafeEqual(actual, expected)
  } catch {
    return false
  }
}

export interface PasswordRegisterResult {
  user: { email: string }
  verification: {
    expiresIn: number
    resendAfter: number
    emailSent: boolean
    devDelivery: boolean
  }
}

export interface AuthSuccess {
  token: string
  user: { id: string; email: string; createdAt: string }
}

export interface ResetRequestResult {
  expiresIn: number
  resendAfter: number
  emailSent: boolean
  devDelivery: boolean
}

export class PasswordService {
  private users = () => collection(Collections.USERS)
  private resetTokens = () => collection(Collections.SESSIONS)
  private otpService = new OtpService()

  /**
   * Creates (or re-requests verification for) a password account. The
   * response never contains a session token: the mailbox must be proven with
   * the emailed code via /auth/verify-otp. Existing accounts get a fresh code
   * instead of a 409, so responses never reveal whether an email is taken.
   */
  async register(rawEmail: string, password: string): Promise<PasswordRegisterResult> {
    const email = normalizeEmail(rawEmail)
    assertPasswordStrength(password)

    const existing = (await this.users().findOne({ email })) as UserDoc | null
    const now = new Date()
    if (!existing) {
      await this.users().insertOne({
        email,
        passwordHash: hashPassword(password),
        platform: 'android',
        appVersion: '1.0.0',
        createdAt: now,
        lastSeenAt: now
      })
    } else if (!existing.passwordHash) {
      // OTP-only account upgrading to a password.
      await this.users().updateOne({ _id: existing._id }, { $set: { passwordHash: hashPassword(password) } })
    }
    // Existing password account: fall through and email a fresh code.

    const verification = await this.otpService.requestOtp(email, 'login')
    return {
      user: { email },
      verification: {
        expiresIn: verification.expiresIn,
        resendAfter: verification.resendAfter,
        emailSent: verification.emailSent,
        devDelivery: verification.devDelivery
      }
    }
  }

  async login(rawEmail: string, password: string): Promise<AuthSuccess> {
    const email = normalizeEmail(rawEmail)
    const user = (await this.users().findOne({ email })) as UserDoc | null
    // Uniform error so responses do not reveal whether an email is registered.
    if (!user || !user.passwordHash || !verifyPassword(password, user.passwordHash)) {
      throw new ApiError(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect.')
    }
    const rawToken = randomBytes(32).toString('base64url')
    await this.users().updateOne(
      { _id: user._id },
      { $set: { tokenHash: hashToken(rawToken), lastSeenAt: new Date() } }
    )
    return {
      token: rawToken,
      user: {
        id: user._id.toString(),
        email,
        createdAt: (user.createdAt ?? new Date()).toISOString()
      }
    }
  }

  /**
   * Begins a password reset. Unknown emails receive an identical success
   * shape without sending anything, so account existence is not revealed.
   */
  async requestPasswordReset(rawEmail: string): Promise<ResetRequestResult> {
    const email = normalizeEmail(rawEmail)
    const user = (await this.users().findOne({ email })) as UserDoc | null
    if (!user) {
      return {
        expiresIn: 600,
        resendAfter: 60,
        emailSent: true,
        devDelivery: false
      }
    }
    return this.otpService.requestOtp(email, 'reset')
  }

  /** Verifies the reset OTP (purpose='reset'); returns the one-time reset token. */
  async verifyResetOtp(rawEmail: string, code: string): Promise<{ resetToken: string }> {
    const email = normalizeEmail(rawEmail)
    await this.otpService.consumeOtp(email, code, 'reset')

    const raw = randomBytes(32).toString('base64url')
    await this.resetTokens().insertOne({
      purpose: 'password_reset',
      email,
      tokenHash: hashToken(raw),
      expiresAt: new Date(Date.now() + RESET_TTL_MS),
      used: false,
      createdAt: new Date()
    })
    return { resetToken: raw }
  }

  /** Completes the reset: burns the token, stores the new hash, revokes sessions. */
  async resetPassword(rawEmail: string, resetToken: string, newPassword: string): Promise<void> {
    const email = normalizeEmail(rawEmail)
    assertPasswordStrength(newPassword)
    if (!resetToken || resetToken.length < 16) {
      throw ApiError.badRequest('Your reset link expired. Start again.', 'RESET_TOKEN_INVALID')
    }

    const rec = (await this.resetTokens().findOne({
      purpose: 'password_reset',
      email,
      tokenHash: hashToken(resetToken)
    })) as { _id: import('mongodb').ObjectId; used: boolean; expiresAt: Date } | null

    if (!rec || rec.used || rec.expiresAt.getTime() < Date.now()) {
      throw ApiError.badRequest('Your reset link expired. Start again.', 'RESET_TOKEN_INVALID')
    }
    await this.resetTokens().updateOne({ _id: rec._id }, { $set: { used: true, usedAt: new Date() } })

    const user = (await this.users().findOne({ email })) as UserDoc | null
    if (!user) {
      throw ApiError.badRequest('Your reset link expired. Start again.', 'RESET_TOKEN_INVALID')
    }
    // New credentials also rotate the session hash: any signed-in device is
    // logged out everywhere after a password change.
    await this.users().updateOne(
      { _id: user._id },
      { $set: { passwordHash: hashPassword(newPassword), tokenHash: hashToken(randomBytes(32).toString('base64url')) } }
    )
  }

  /**
   * Invalidates the caller's session server-side by rotating the stored
   * token hash away from the client's token. Safe to call twice.
   */
  async logout(rawToken: string): Promise<void> {
    if (!rawToken || rawToken.length < 16) return
    const tokenHash = hashToken(rawToken)
    const user = (await this.users().findOne({ tokenHash })) as { _id: import('mongodb').ObjectId } | null
    if (user) {
      await this.users().updateOne(
        { _id: user._id },
        { $set: { tokenHash: hashToken(randomBytes(32).toString('base64url')) } }
      )
    }
  }
}
