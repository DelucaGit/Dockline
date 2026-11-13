/** Only plain https addresses may be opened in the user's browser (never file:, javascript:, http: ...). */
export function isSafeExternalUrl(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname !== '' && url.username === '' && url.password === ''
  } catch {
    return false
  }
}
