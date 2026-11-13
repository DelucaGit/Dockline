import { describe, expect, it } from 'vitest'
import { maskTokens } from './maskTokens'

describe('maskTokens', () => {
  it.each(['ghp', 'gho', 'ghu', 'ghs'])('masks %s_ tokens', (prefix) => {
    expect(maskTokens(`token ${prefix}_0123456789abcdefABCDEF end`)).toBe(`token ${prefix}_**** end`)
  })

  it('masks github_pat_ tokens', () => {
    expect(maskTokens('x github_pat_11ABC_def456 y')).toBe('x github_pat_**** y')
  })

  it('masks Bearer values', () => {
    expect(maskTokens('Bearer abc.DEF-123_xyz')).toBe('Bearer ****')
  })

  it('masks Authorization header values, with or without a scheme', () => {
    expect(maskTokens('Authorization: Bearer secret123')).toBe('Authorization: Bearer ****')
    expect(maskTokens('authorization: secret123')).toBe('authorization: ****')
  })

  it('leaves ordinary text alone', () => {
    const text = 'GET /user returned 401 for ghost_repo and the word bearer'
    expect(maskTokens(text)).toBe(text)
  })
})
