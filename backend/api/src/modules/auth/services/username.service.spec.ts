import { describe, expect, it } from 'vitest'
import {
  isValidUsername,
  normalizeUsername,
  resolveCollision,
  usernameBase
} from './username.service'

describe('normalizeUsername()', () => {
  it('derives from display names', () => {
    expect(normalizeUsername('Ashu Pathak')).toBe('ashupathak')
    expect(normalizeUsername('Ed-Sheeran.99')).toBe('ed-sheeran.99')
    expect(normalizeUsername('sagar_264')).toBe('sagar_264')
  })

  it('falls back to the email prefix, never the domain', () => {
    expect(normalizeUsername('Sagar264@gmail.com')).toBe('sagar264')
  })

  it('rejects too-short and illegal results', () => {
    expect(normalizeUsername('ab')).toBe('')
    expect(normalizeUsername('   ')).toBe('')
    expect(normalizeUsername('!!!')).toBe('')
    expect(normalizeUsername(null)).toBe('')
  })

  it('truncates long names to 20 chars', () => {
    expect(normalizeUsername('abcdefghijklmnopqrstu')).toHaveLength(20)
  })
})

describe('usernameBase()', () => {
  it('prefers the display name over email', () => {
    expect(usernameBase('Ashu Pathak', 'other@gmail.com')).toBe('ashupathak')
    expect(usernameBase(null, 'sagar264@gmail.com')).toBe('sagar264')
    expect(usernameBase(null, null)).toBe('listener')
  })
})

describe('resolveCollision()', () => {
  it('returns the base when free', () => {
    expect(resolveCollision('ashu', () => false)).toBe('ashu')
  })

  it('appends deterministic numeric suffixes', () => {
    const taken = new Set(['ashu', 'ashu2'])
    expect(resolveCollision('ashu', (n) => taken.has(n))).toBe('ashu3')
    // Stable for a stable database state.
    expect(resolveCollision('ashu', (n) => taken.has(n))).toBe('ashu3')
  })

  it('never issues reserved names bare', () => {
    expect(resolveCollision('you', () => false)).toBe('you_fan')
    expect(resolveCollision('admin', () => false)).toBe('admin_fan')
  })

  it('terminates for max-length stems', () => {
    const stem = 'abcdefghijklmnopqrst'
    const out = resolveCollision(stem, (n) => n === stem)
    expect(out).not.toBe(stem)
    expect(out.length).toBeLessThanOrEqual(20)
  })
})

describe('isValidUsername()', () => {
  it('mirrors the Android rules', () => {
    expect(isValidUsername('ashu')).toBe(true)
    expect(isValidUsername('Ed-Sheeran.99')).toBe(true)
    expect(isValidUsername('ab')).toBe(false)
    expect(isValidUsername('ashu pathak')).toBe(false)
    expect(isValidUsername('sagar!')).toBe(false)
    expect(isValidUsername(null)).toBe(false)
  })
})
