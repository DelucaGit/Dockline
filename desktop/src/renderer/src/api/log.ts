import type { components } from '@/api/schema'

export type LogEntry = components['schemas']['LogEntry']
export type LogSource = components['schemas']['LogSource']
export type LogLevel = components['schemas']['LogLevel']

export interface LogFilters {
  source?: LogSource
  level?: LogLevel
}

export async function fetchLog(baseUrl: string, filters: LogFilters): Promise<LogEntry[]> {
  const params = new URLSearchParams()
  if (filters.source) params.set('source', filters.source)
  if (filters.level) params.set('level', filters.level)
  const query = params.size > 0 ? `?${params}` : ''
  const res = await fetch(`${baseUrl}/api/log${query}`)
  if (!res.ok) throw new Error(`GET /api/log failed with ${res.status}`)
  return (await res.json()) as LogEntry[]
}

export async function clearLog(baseUrl: string): Promise<void> {
  const res = await fetch(`${baseUrl}/api/log`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`DELETE /api/log failed with ${res.status}`)
}
