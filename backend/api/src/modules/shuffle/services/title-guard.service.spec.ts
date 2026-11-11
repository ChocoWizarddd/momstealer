import { describe, expect, it } from 'vitest'
import { TitleGuardService } from './title-guard.service'

const svc = new TitleGuardService()

describe('TitleGuardService', () => {
  it('canonicalizes version variants of one title identically', () => {
    expect(svc.canonicalTitle('Perfect')).toBe('perfect')
    expect(svc.canonicalTitle('perfect!')).toBe('perfect')
    expect(svc.canonicalTitle('PERFECT')).toBe('perfect')
    expect(svc.canonicalTitle('Perfect - Live')).toBe('perfect')
    expect(svc.canonicalTitle('Perfect (Acoustic)')).toBe('perfect')
    expect(svc.canonicalTitle('Perfect [Radio Edit]')).toBe('perfect')
    expect(svc.canonicalTitle('Perfect (feat. Khalid)')).toBe('perfect')
    expect(svc.canonicalTitle('Perfect — Official Video')).toBe('perfect')
  })

  it('treats every variant of a title as one repetition family', () => {
    for (const variant of [
      'perfect!',
      'Perfect - Live',
      'Perfect (Acoustic)',
      'Perfect [Remix]',
      'Perfect (Radio Edit)'
    ]) {
      expect(svc.isSameTitleFamily('Perfect', variant)).toBe(true)
    }
  })

  it('keeps legitimately different titles separate', () => {
    expect(svc.isSameTitleFamily('Perfect', 'Photograph')).toBe(false)
    expect(svc.isSameTitleFamily('Night Changes', 'Dandelions')).toBe(false)
    expect(svc.isSameTitleFamily('', 'Perfect')).toBe(false)
    expect(svc.isSameTitleFamily('Perfect', '')).toBe(false)
  })

  it('still recognizes near-identical numbered variants', () => {
    expect(svc.isSameTitleFamily('Perfect', 'Perfect 2')).toBe(true)
    expect(svc.isSameTitleFamily('Perfect', 'Perfect Version 2')).toBe(true)
  })

  it('short titles are not merged by containment noise', () => {
    expect(svc.isSameTitleFamily('Hi', 'Hi Love')).toBe(false)
  })
})
