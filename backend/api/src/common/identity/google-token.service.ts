import process from 'node:process'
import { createRemoteJWKSet, jwtVerify } from 'jose'

/**
 * Verifies Google Sign-In ID tokens (issued by accounts.google.com).
 *
 * Security contract:
 *  - Signature checked against Google's OAuth2 public JWKS.
 *  - `iss` must be accounts.google.com (or https://accounts.google.com).
 *  - `aud` must equal GOOGLE_WEB_CLIENT_ID (the OAuth web client registered
 *    in Firebase/Google Cloud - public, not a secret).
 *  - Expiry enforced by jwtVerify.
 *
 * Configuration: GOOGLE_WEB_CLIENT_ID env var on the server (not a secret).
 */

// Short cooldown so a Google key rotation is picked up on the next retry
// within seconds instead of failing for up to 30s (JWKSNoMatchingKey).
const GOOGLE_JWKS = createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'), {
  cooldownDuration: 10_000
})

export interface GoogleIdentity {
  sub: string
  email: string | null
  emailVerified: boolean
  /** Verified display claims (profile use only — never identity). */
  name: string | null
  picture: string | null
}

export function googleConfigured(): boolean {
  return Boolean(process.env.GOOGLE_WEB_CLIENT_ID)
}

export async function verifyGoogleIdToken(idToken: string): Promise<GoogleIdentity> {
  const clientId = process.env.GOOGLE_WEB_CLIENT_ID
  if (!clientId) {
    throw new Error('GOOGLE_WEB_CLIENT_ID is not configured')
  }

  const { payload } = await jwtVerify(idToken, GOOGLE_JWKS, {
    issuer: ['accounts.google.com', 'https://accounts.google.com'],
    audience: clientId,
    algorithms: ['RS256']
  })

  if (!payload.sub) {
    throw new Error('Google token missing subject')
  }

  return {
    sub: payload.sub,
    email: (payload.email as string) ?? null,
    // Google sets email_verified=true for gmail.com and verified addresses.
    emailVerified: payload.email_verified === true,
    name: (payload.name as string) ?? null,
    picture: (payload.picture as string) ?? null
  }
}
