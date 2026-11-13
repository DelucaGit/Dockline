// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { isSafeExternalUrl } from './externalUrl'

describe('isSafeExternalUrl', () => {
  it('accepts https addresses', () => {
    expect(isSafeExternalUrl('https://github.com/login/device')).toBe(true)
  })

  it.each([
    'http://github.com/login/device',
    'file:///C:/Windows/System32/calc.exe',
    'javascript:alert(1)',
    'ms-msdt:/id',
    'https://user:pw@github.com/',
    'github.com/login/device',
    '',
    '   '
  ])('rejects %s', (url) => {
    expect(isSafeExternalUrl(url)).toBe(false)
  })

  it('rejects things that are not strings', () => {
    expect(isSafeExternalUrl(undefined)).toBe(false)
    expect(isSafeExternalUrl({ href: 'https://github.com' })).toBe(false)
  })
})
