import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import { ApiError } from '#common/errors/api-error'
import { requireUser } from '#common/middleware/auth'
import { rateLimit } from '#common/middleware/rate-limit'
import { AuthService, OtpService, PasswordService } from '../services'
import { FirebaseAuthService } from '../services/firebase-auth.service'
import type { Routes } from '#common/types'

const RegisterBody = z.object({
  deviceId: z.string().min(8).max(128).openapi({
    description: 'Stable per-install device identifier (e.g. UUID)'
  }),
  platform: z.string().max(32).optional().openapi({ default: 'android' }),
  appVersion: z.string().max(32).optional().openapi({ default: '1.0.0' })
})

const OtpRequestSchema = z.object({
  email: z.string().email().max(254)
})

const OtpVerifySchema = z.object({
  email: z.string().email().max(254),
  otp: z.string().regex(/^\d{6}$/)
})

const PreferencesSchema = z.object({
  languages: z.array(z.string().min(1).max(32)).max(32).default([]),
  categories: z.array(z.string().min(1).max(32)).max(32).default([])
})

const PasswordRegisterSchema = z.object({
  email: z.string().email().max(254),
  password: z.string().min(8).max(128)
})

const LoginSchema = z.object({
  email: z.string().email().max(254),
  password: z.string().min(1).max(128)
})

const ForgotPasswordSchema = z.object({
  email: z.string().email().max(254)
})

const ResetVerifySchema = z.object({
  email: z.string().email().max(254),
  otp: z.string().regex(/^\d{6}$/)
})

const ResetPasswordSchema = z.object({
  email: z.string().email().max(254),
  resetToken: z.string().min(16).max(128),
  newPassword: z.string().min(8).max(128)
})

export class AuthController implements Routes {
  public controller: OpenAPIHono
  private authService: AuthService
  private otpService: OtpService
  private passwordService: PasswordService
  private firebaseAuthService: FirebaseAuthService

  constructor() {
    this.controller = new OpenAPIHono()
    this.authService = new AuthService()
    this.otpService = new OtpService()
    this.passwordService = new PasswordService()
    this.firebaseAuthService = new FirebaseAuthService()
  }

  public initRoutes() {
    // Registration is write-heavy and brute-forceable: rate limit per IP.
    this.controller.use('/register', rateLimit({ limit: 10, windowMs: 60_000 }))
    this.controller.use('/request-otp', rateLimit({ limit: 10, windowMs: 60_000 }))
    this.controller.use('/verify-otp', rateLimit({ limit: 20, windowMs: 60_000 }))
    this.controller.use('/preferences', rateLimit({ limit: 30, windowMs: 60_000 }))

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/register',
        tags: ['Auth'],
        summary: 'Register a device',
        description:
          'Creates an anonymous device account and returns a bearer token. ' +
          'The token is returned exactly once; store it securely on the device.',
        operationId: 'registerDevice',
        request: {
          body: {
            content: { 'application/json': { schema: RegisterBody } }
          }
        },
        responses: {
          200: {
            description: 'Device registered or re-authenticated',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    token: z.string(),
                    user: z.object({
                      id: z.string(),
                      deviceId: z.string(),
                      createdAt: z.string()
                    })
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const body = ctx.req.valid('json')
        const deviceId = body.deviceId.trim()
        if (deviceId.length < 8) throw ApiError.badRequest('Invalid device identifier', 'BAD_DEVICE_ID')

        const result = await this.authService.register(deviceId, body.platform || 'android', body.appVersion || '1.0.0')

        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/auth/me',
        tags: ['Auth'],
        summary: 'Current user profile',
        operationId: 'getMe',
        responses: {
          200: {
            description: 'Profile for the authenticated device',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    id: z.string(),
                    stats: z.object({
                      likedTracks: z.number(),
                      playlists: z.number()
                    }),
                    preferences: z.any().nullable()
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const profile = await this.authService.getProfile(user.id)
        return ctx.json({ success: true, data: profile })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/request-otp',
        tags: ['Auth'],
        summary: 'Request an email verification code',
        operationId: 'requestOtp',
        request: {
          body: {
            content: { 'application/json': { schema: OtpRequestSchema } }
          }
        },
        responses: {
          200: {
            description: 'Code requested (or already in cooldown)',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    expiresIn: z.number(),
                    resendAfter: z.number(),
                    emailSent: z.boolean(),
                    devDelivery: z.boolean().optional()
                  })
                })
              }
            }
          },
          429: { description: 'Rate limited or resend cooldown' },
          503: { description: 'Email service not configured' }
        }
      }),
      async (ctx) => {
        const { email } = ctx.req.valid('json')
        const data = await this.otpService.requestOtp(email)
        return ctx.json({ success: true, data })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/verify-otp',
        tags: ['Auth'],
        summary: 'Verify a code and start an authenticated session',
        operationId: 'verifyOtp',
        request: {
          body: {
            content: { 'application/json': { schema: OtpVerifySchema } }
          }
        },
        responses: {
          200: {
            description: 'Verified; returns the session token exactly once',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    token: z.string(),
                    user: z.object({
                      id: z.string(),
                      email: z.string(),
                      createdAt: z.string()
                    }),
                    preferences: z
                      .object({
                        languages: z.array(z.string()),
                        categories: z.array(z.string())
                      })
                      .nullable()
                  })
                })
              }
            }
          },
          400: { description: 'Invalid / expired code' },
          429: { description: 'Too many attempts' }
        }
      }),
      async (ctx) => {
        const { email, otp } = ctx.req.valid('json')
        const data = await this.otpService.verifyOtp(email, otp)
        return ctx.json({ success: true, data })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'put',
        path: '/auth/preferences',
        tags: ['Auth'],
        summary: 'Save onboarding preferences (languages + categories)',
        operationId: 'savePreferences',
        request: {
          body: {
            content: { 'application/json': { schema: PreferencesSchema } }
          }
        },
        responses: {
          200: {
            description: 'Preferences saved',
            content: {
              'application/json': {
                schema: z.object({ success: z.boolean(), data: z.object({ saved: z.boolean() }) })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const user = await requireUser(ctx)
        const { languages, categories } = ctx.req.valid('json')
        await this.otpService.savePreferences(user.id, languages, categories)
        return ctx.json({ success: true, data: { saved: true } })
      }
    )

    this.registerPasswordRoutes()
  }

  /**
   * Email+password flows. Registration intentionally returns NO session: the
   * mailbox is proven via the emailed OTP and /auth/verify-otp issues the
   * session, so one code path owns session issuance.
   */
  private registerPasswordRoutes() {
    this.controller.use('/login', rateLimit({ limit: 10, windowMs: 60_000 }))
    this.controller.use('/forgot-password', rateLimit({ limit: 5, windowMs: 60_000 }))
    this.controller.use('/reset-verify', rateLimit({ limit: 20, windowMs: 60_000 }))
    this.controller.use('/reset-password', rateLimit({ limit: 10, windowMs: 60_000 }))

    const authData = (extra: z.ZodObject<z.ZodRawShape>) =>
      z.object({
        success: z.boolean(),
        data: extra
      })

    const sessionShape = {
      token: z.string(),
      user: z.object({
        id: z.string(),
        email: z.string(),
        username: z.string().nullish(),
        displayName: z.string().nullish(),
        photoUrl: z.string().nullish(),
        createdAt: z.string()
      })
    }

    const verificationShape = z.object({
      expiresIn: z.number(),
      resendAfter: z.number(),
      emailSent: z.boolean(),
      devDelivery: z.boolean()
    })

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/register-password',
        tags: ['Auth'],
        summary: 'Create a password account (emails a verification code)',
        description: 'Distinct from the legacy device /auth/register route.',
        operationId: 'registerPassword',
        request: { body: { content: { 'application/json': { schema: PasswordRegisterSchema } } } },
        responses: {
          200: {
            description: 'Account ready for email verification',
            content: {
              'application/json': {
                schema: authData(z.object({ user: z.object({ email: z.string() }), verification: verificationShape }))
              }
            }
          },
          400: { description: 'Invalid email or weak password' },
          429: { description: 'Rate limited / resend cooldown' }
        }
      }),
      async (ctx) => {
        const { email, password } = ctx.req.valid('json')
        const data = await this.passwordService.register(email, password)
        return ctx.json({ success: true, data })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/login',
        tags: ['Auth'],
        summary: 'Login with email and password',
        operationId: 'loginPassword',
        request: { body: { content: { 'application/json': { schema: LoginSchema } } } },
        responses: {
          200: {
            description: 'Session issued',
            content: { 'application/json': { schema: authData(z.object(sessionShape)) } }
          },
          401: { description: 'Invalid credentials' }
        }
      }),
      async (ctx) => {
        const { email, password } = ctx.req.valid('json')
        const data = await this.passwordService.login(email, password)
        return ctx.json({ success: true, data })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/forgot-password',
        tags: ['Auth'],
        summary: 'Begin password reset (emails a reset code)',
        operationId: 'forgotPassword',
        request: { body: { content: { 'application/json': { schema: ForgotPasswordSchema } } } },
        responses: {
          200: {
            description: 'Reset flow started (enumeration-safe)',
            content: { 'application/json': { schema: authData(verificationShape) } }
          }
        }
      }),
      async (ctx) => {
        const { email } = ctx.req.valid('json')
        const data = await this.passwordService.requestPasswordReset(email)
        return ctx.json({ success: true, data })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/reset-verify',
        tags: ['Auth'],
        summary: 'Verify the reset code (returns a one-time reset token)',
        operationId: 'resetVerify',
        request: { body: { content: { 'application/json': { schema: ResetVerifySchema } } } },
        responses: {
          200: {
            description: 'Code verified; reset token issued',
            content: {
              'application/json': {
                schema: authData(z.object({ resetToken: z.string() }))
              }
            }
          },
          400: { description: 'Invalid / expired code' }
        }
      }),
      async (ctx) => {
        const { email, otp } = ctx.req.valid('json')
        const data = await this.passwordService.verifyResetOtp(email, otp)
        return ctx.json({ success: true, data })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/reset-password',
        tags: ['Auth'],
        summary: 'Set a new password with the one-time reset token',
        operationId: 'resetPassword',
        request: { body: { content: { 'application/json': { schema: ResetPasswordSchema } } } },
        responses: {
          200: {
            description: 'Password updated; all sessions revoked',
            content: {
              'application/json': {
                schema: authData(z.object({ reset: z.boolean() }))
              }
            }
          },
          400: { description: 'Invalid / expired reset token or weak password' }
        }
      }),
      async (ctx) => {
        const { email, resetToken, newPassword } = ctx.req.valid('json')
        await this.passwordService.resetPassword(email, resetToken, newPassword)
        return ctx.json({ success: true, data: { reset: true } })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/logout',
        tags: ['Auth'],
        summary: 'Invalidate the current session server-side',
        operationId: 'logout',
        responses: {
          200: {
            description: 'Session invalidated (idempotent)',
            content: {
              'application/json': {
                schema: authData(z.object({ loggedOut: z.boolean() }))
              }
            }
          },
          401: { description: 'Missing or invalid session' }
        }
      }),
      async (ctx) => {
        const header = ctx.req.header('authorization') ?? ''
        const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length).trim() : ''
        await requireUser(ctx) // reject cleanly when no valid session
        await this.passwordService.logout(token)
        return ctx.json({ success: true, data: { loggedOut: true } })
      }
    )

    const FirebaseExchangeSchema = z.object({
      idToken: z.string().min(32).max(4096)
    })

    this.controller.openapi(
      createRoute({
        method: 'post',
        path: '/auth/firebase/exchange',
        tags: ['Auth'],
        summary: 'Exchange a verified Firebase ID token for a NightLight session',
        description:
          'Android authenticates with Firebase, then swaps the verified ID ' +
          'token for the same hashed session used by password login.',
        operationId: 'firebaseExchange',
        request: { body: { content: { 'application/json': { schema: FirebaseExchangeSchema } } } },
        responses: {
          200: {
            description: 'Session issued',
            content: { 'application/json': { schema: authData(z.object(sessionShape)) } }
          },
          401: { description: 'Invalid or expired Firebase token' },
          403: { description: 'Firebase email not verified' },
          503: { description: 'Firebase auth not configured on this deployment' }
        }
      }),
      async (ctx) => {
        const { idToken } = ctx.req.valid('json')
        const data = await this.firebaseAuthService.exchange(idToken)
        return ctx.json({ success: true, data })
      }
    )
  }
}
