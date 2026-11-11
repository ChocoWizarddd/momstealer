/**
 * Typed Gaana provider errors. Callers map these to NightLight errors —
 * never forward stack traces or provider internals to Android.
 */
export type GaanaErrorCode =
  | 'NOT_SUPPORTED'
  | 'INVALID_URL'
  | 'NOT_FOUND'
  | 'REGION_BLOCKED'
  | 'RATE_LIMITED'
  | 'UPSTREAM_ERROR'
  | 'TIMEOUT'
  | 'FORMAT_CHANGED'
  | 'NO_PLAYABLE_BITRATE'
  | 'URL_EXPIRED'
  | 'CRYPTO_FAILED'

export class GaanaError extends Error {
  readonly code: GaanaErrorCode
  readonly status: number

  constructor(code: GaanaErrorCode, message: string, status = 502) {
    super(message)
    this.name = 'GaanaError'
    this.code = code
    this.status = status
  }
}
