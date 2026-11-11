import process from 'node:process'
import { createRemoteJWKSet, jwtVerify } from 'jose'

/**
 * Verifies Firebase Authentication ID tokens.
 *
 * Security contract:
 *  - Signature checked against Google's public JWKS (x509 certs).
 *  - `iss` must be https://securetoken.google.com/<PROJECT_ID>.
 *  - `aud` must equal the Firebase PROJECT_ID.
 *  - Expiry enforced by jwtVerify.
 *
 * The only configuration needed is FIREBASE_PROJECT_ID (not a secret).
 * Set it in Render → Environment to activate Firebase auth in production.
 */

const FIREBASE_JWKS = createRemoteJWKSet(
  new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com')
)

export interface FirebaseIdentity {
  uid: string
  email: string | null
  emailVerified: boolean
  /** Verified display claims (profile use only — never identity). */
  name: string | null
  picture: string | null
}

export function firebaseConfigured(): boolean {
  return Boolean(process.env.FIREBASE_PROJECT_ID)
}

export async function verifyFirebaseIdToken(idToken: string): Promise<FirebaseIdentity> {
  const projectId = process.env.FIREBASE_PROJECT_ID
  if (!projectId) {
    throw new Error('FIREBASE_PROJECT_ID is not configured')
  }

  const { payload } = await jwtVerify(idToken, FIREBASE_JWKS, {
    issuer: `https://securetoken.google.com/${projectId}`,
    audience: projectId,
    algorithms: ['RS256']
  })

  const uid = (payload.user_id as string) ?? (payload.sub as string)
  if (!uid) {
    throw new Error('Firebase token missing user id')
  }

  return {
    uid,
    email: (payload.email as string) ?? null,
    emailVerified: payload.email_verified === true,
    name: (payload.name as string) ?? null,
    picture: (payload.picture as string) ?? null
  }
}
