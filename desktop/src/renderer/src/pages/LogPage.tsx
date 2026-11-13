import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronRight, Copy, RefreshCw, Trash2 } from 'lucide-react'
import { clearLog, fetchLog, type LogEntry, type LogLevel, type LogSource } from '@/api/log'
import { Button } from '@/components/ui/button'
import { formatForAi } from '@/lib/copyForAi'
import { cn } from '@/lib/utils'
import { strings } from '@/strings'

const SOURCES: LogSource[] = ['GITHUB', 'HOSTINGER', 'BACKEND']
const LEVELS: LogLevel[] = ['ERROR', 'WARN']

function FilterSelect<T extends string>({
  label,
  value,
  options,
  onChange
}: {
  label: string
  value: T | ''
  options: T[]
  onChange: (value: T | '') => void
}) {
  return (
    <label className="flex items-center gap-2 text-sm">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as T | '')}
        className="h-9 rounded-md border border-border bg-background px-2 text-foreground"
      >
        <option value="">{strings.log.filters.all}</option>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </label>
  )
}

function Detail({ title, body }: { title: string; body?: string }) {
  if (!body) return null
  return (
    <div>
      <h3 className="text-xs font-medium uppercase text-muted-foreground">{title}</h3>
      <pre className="mt-1 overflow-x-auto whitespace-pre-wrap break-words rounded-md bg-muted p-2 text-xs">
        {body}
      </pre>
    </div>
  )
}

function CopyButton({ entry }: { entry: LogEntry }) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  async function copy() {
    try {
      await navigator.clipboard.writeText(formatForAi(entry))
      setState('copied')
    } catch {
      setState('failed')
    }
    setTimeout(() => setState('idle'), 2000)
  }
  const label =
    state === 'copied' ? strings.log.copied : state === 'failed' ? strings.log.copyFailed : strings.log.copyForAi
  return (
    <Button variant="ghost" size="sm" onClick={copy}>
      {state === 'copied' ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
      {label}
    </Button>
  )
}

function EntryRow({ entry }: { entry: LogEntry }) {
  const { request, response, stackTrace } = entry.context
  const hasDetails = Boolean(request || response || stackTrace)
  return (
    <li data-testid="log-entry" className="rounded-md border border-border">
      <details className="group">
        <summary className="flex cursor-pointer list-none items-start gap-3 p-3 text-sm [&::-webkit-details-marker]:hidden">
          <ChevronRight className="mt-0.5 size-4 shrink-0 transition-transform group-open:rotate-90" aria-hidden />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span
                className={cn(
                  'rounded px-1.5 py-0.5 text-xs font-medium',
                  entry.level === 'ERROR'
                    ? 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
                    : 'bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-300'
                )}
              >
                {entry.level}
              </span>
              <span className="font-medium">{entry.source}</span>
              <span className="text-muted-foreground">{entry.action}</span>
              {entry.statusCode !== undefined && <span className="text-muted-foreground">{entry.statusCode}</span>}
              <time dateTime={entry.time} className="ml-auto text-xs text-muted-foreground">
                {new Date(entry.time).toLocaleString()}
              </time>
            </div>
            <p className="mt-1 break-words">{entry.message}</p>
          </div>
        </summary>
        <div className="space-y-3 border-t border-border p-3 pl-10">
          <p className="text-xs text-muted-foreground">v{entry.context.appVersion}</p>
          {hasDetails ? (
            <>
              <Detail title={strings.log.copy.request} body={request} />
              <Detail title={strings.log.copy.response} body={response} />
              <Detail title={strings.log.copy.stackTrace} body={stackTrace} />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">{strings.log.noDetails}</p>
          )}
        </div>
      </details>
      <div className="flex justify-end border-t border-border px-3 py-1.5">
        <CopyButton entry={entry} />
      </div>
    </li>
  )
}

export function LogPage({ baseUrl }: { baseUrl: string }) {
  const [source, setSource] = useState<LogSource | ''>('')
  const [level, setLevel] = useState<LogLevel | ''>('')
  const queryClient = useQueryClient()

  const filters = { source: source || undefined, level: level || undefined }
  const log = useQuery({
    queryKey: ['log', baseUrl, filters],
    queryFn: () => fetchLog(baseUrl, filters),
    refetchOnWindowFocus: true
  })
  const clear = useMutation({
    mutationFn: () => clearLog(baseUrl),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['log', baseUrl] })
  })

  const entries = log.data ?? []
  return (
    <section>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="mr-auto text-2xl font-semibold">{strings.log.title}</h1>
        <FilterSelect label={strings.log.filters.source} value={source} options={SOURCES} onChange={setSource} />
        <FilterSelect label={strings.log.filters.level} value={level} options={LEVELS} onChange={setLevel} />
        <Button variant="ghost" onClick={() => void log.refetch()}>
          <RefreshCw className={cn('size-4', log.isFetching && 'animate-spin')} aria-hidden />
          {strings.log.refresh}
        </Button>
        <Button onClick={() => clear.mutate()} disabled={clear.isPending}>
          <Trash2 className="size-4" aria-hidden />
          {strings.log.clear}
        </Button>
      </div>

      {clear.isError && (
        <p role="alert" className="mt-3 text-sm text-red-600 dark:text-red-400">
          {strings.log.clearError}
        </p>
      )}
      {log.isError && (
        <p role="alert" className="mt-3 text-sm text-red-600 dark:text-red-400">
          {strings.log.loadError}
        </p>
      )}

      {log.isSuccess && entries.length === 0 && (
        <div className="mt-8 text-center">
          <p className="text-lg font-medium">{strings.log.empty}</p>
          <p className="mt-1 text-muted-foreground">{strings.log.emptyHint}</p>
        </div>
      )}

      {entries.length > 0 && (
        <ul aria-label={strings.log.title} className="mt-4 space-y-2">
          {entries.map((entry) => (
            <EntryRow key={entry.id} entry={entry} />
          ))}
        </ul>
      )}
    </section>
  )
}
