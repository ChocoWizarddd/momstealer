import { HTTPException } from 'hono/http-exception'
import type { ContentfulStatusCode } from 'hono/utils/http-status'

/**
 * Structured API error. The `code` is a stable, machine-readable identifier
 * that clients can map to user-friendly states; `message` is human-readable
 * and must never leak internals.
 */
export class ApiError extends HTTPException {
  readonly code: string
  readonly details?: unknown

  constructor(status: ContentfulStatusCode, code: string, message: string, details?: unknown) {
    super(status, { message })
    this.code = code
    this.details = details
  }

  static badRequest(message: string, code = 'BAD_REQUEST') {
    return new ApiError(400, code, message)
  }

  static unauthorized(message = 'Authentication required') {
    return new ApiError(401, 'UNAUTHORIZED', message)
  }

  static forbidden(message = 'Not allowed') {
    return new ApiError(403, 'FORBIDDEN', message)
  }

  static notFound(message: string, code = 'NOT_FOUND') {
    return new ApiError(404, code, message)
  }

  static rateLimited(message = 'Too many requests, try again later') {
    return new ApiError(429, 'RATE_LIMITED', message)
  }

  static internal(message = 'Something went wrong on our end') {
    return new ApiError(500, 'INTERNAL_ERROR', message)
  }
}
