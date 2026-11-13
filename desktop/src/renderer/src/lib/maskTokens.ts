const MASK = '****'

/**
 * Defence in depth: the backend already masks tokens, this masks them again before text leaves the
 * app (the "Copy for AI" clipboard text). Covers GitHub tokens and Authorization / Bearer values.
 */
export function maskTokens(text: string): string {
  return text
    .replace(/\bgithub_pat_[A-Za-z0-9_]+/g, `github_pat_${MASK}`)
    .replace(/\b(gh[pousr])_[A-Za-z0-9]+/g, `$1_${MASK}`)
    .replace(
      /\b(Authorization\s*[:=]\s*)(?:(Bearer|Basic|token)\s+)?[^\s"',;]+/gi,
      (_match, head: string, scheme?: string) => `${head}${scheme ? `${scheme} ` : ''}${MASK}`
    )
    .replace(/\b(Bearer\s+)[A-Za-z0-9._~+/=-]+/gi, `$1${MASK}`)
}
