import { describe, expect, it } from 'vitest'
import { formatRelativeTime } from './relativeTime'

const NOW = Date.parse('2026-11-13T12:00:00Z')
const ago = (seconds: number) => new Date(NOW - seconds * 1000).toISOString()

describe('formatRelativeTime', () => {
  it.each([
    [5, 'just now'],
    [59, 'just now'],
    [60, '1 minute ago'],
    [5 * 60, '5 minutes ago'],
    [3600, '1 hour ago'],
    [3 * 3600, '3 hours ago'],
    [2 * 86400, '2 days ago'],
    [45 * 86400, '1 month ago'],
    [400 * 86400, '1 year ago']
  ])('%i seconds ago reads "%s"', (seconds, expected) => {
    expect(formatRelativeTime(ago(seconds), NOW)).toBe(expected)
  })

  it('treats a time in the future as just now', () => {
    expect(formatRelativeTime(ago(-300), NOW)).toBe('just now')
  })
})
