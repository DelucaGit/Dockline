const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60]
]

const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'always' })

/** A readable "3 hours ago" for an ISO time. Under a minute (or in the future) is "just now". */
export function formatRelativeTime(iso: string, now: number = Date.now()): string {
  const seconds = Math.floor((now - Date.parse(iso)) / 1000)
  if (Number.isNaN(seconds) || seconds < 60) return 'just now'
  for (const [unit, size] of UNITS) {
    if (seconds >= size) return formatter.format(-Math.floor(seconds / size), unit)
  }
  return 'just now'
}

/** The exact time, for a tooltip. */
export function formatExactTime(iso: string): string {
  return new Date(iso).toLocaleString()
}
