import type { LogEntry } from '@/api/log'
import { maskTokens } from '@/lib/maskTokens'
import { strings } from '@/strings'

/** A readable text block with all context of one entry, ready to paste into an AI chat. */
export function formatForAi(entry: LogEntry): string {
  const t = strings.log.copy
  const lines = [
    t.intro,
    '',
    `${t.appVersion}: ${entry.context.appVersion}`,
    `${t.time}: ${entry.time}`,
    `${t.level}: ${entry.level}`,
    `${t.source}: ${entry.source}`,
    `${t.action}: ${entry.action}`,
    `${t.statusCode}: ${entry.statusCode ?? t.none}`,
    `${t.message}: ${entry.message}`
  ]
  const blocks: [string, string | undefined][] = [
    [t.request, entry.context.request],
    [t.response, entry.context.response],
    [t.stackTrace, entry.context.stackTrace]
  ]
  for (const [title, body] of blocks) {
    if (body) lines.push('', `${title}:`, body)
  }
  return maskTokens(lines.join('\n'))
}
