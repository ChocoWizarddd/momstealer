import { describe, expect, it } from 'vitest'

/**
 * Chat validation logic is implemented inline in SessionsService.sendMessage()
 * and getMessages().  These tests verify the pure validation rules without
 * needing a MongoDB connection.
 */

describe('Session code normalization', () => {
  const normalize = (code: string) => (code ?? '').trim().toUpperCase().slice(0, 8)

  it('uppercases lowercase input', () => {
    expect(normalize('abc123')).toBe('ABC123')
  })

  it('trims whitespace', () => {
    expect(normalize('  ABC123  ')).toBe('ABC123')
  })

  it('truncates to 8 characters', () => {
    expect(normalize('ABCDEFGH1234')).toBe('ABCDEFGH')
  })

  it('handles empty string', () => {
    expect(normalize('')).toBe('')
  })
})

describe('Chat message validation', () => {
  const validate = (message: string): { ok: boolean; error?: string } => {
    const trimmed = (message ?? '').trim()
    if (trimmed.length === 0) return { ok: false, error: 'Messages cannot be empty' }
    if (trimmed.length > 280) return { ok: false, error: 'Messages must be 280 characters or fewer' }
    return { ok: true }
  }

  it('accepts normal message', () => {
    expect(validate('this song hits different').ok).toBe(true)
  })

  it('trims whitespace before validating', () => {
    expect(validate('   hello   ').ok).toBe(true)
  })

  it('rejects empty string', () => {
    expect(validate('').ok).toBe(false)
  })

  it('rejects whitespace-only', () => {
    expect(validate('   ').ok).toBe(false)
  })

  it('rejects 281 characters', () => {
    expect(validate('a'.repeat(281)).ok).toBe(false)
  })

  it('accepts exactly 280 characters', () => {
    expect(validate('a'.repeat(280)).ok).toBe(true)
  })

  it('accepts message with emoji', () => {
    expect(validate('this song 🔥 fr 😭').ok).toBe(true)
  })
})

describe('Session expiry check', () => {
  const STALE_MS = 3 * 60 * 60 * 1000 // 3 hours

  const isExpired = (updatedAt: number): boolean => {
    return Date.now() - updatedAt > STALE_MS
  }

  it('session from 1 hour ago is not expired', () => {
    expect(isExpired(Date.now() - 3600_000)).toBe(false)
  })

  it('session from 4 hours ago is expired', () => {
    expect(isExpired(Date.now() - 4 * 3600_000)).toBe(true)
  })
})
