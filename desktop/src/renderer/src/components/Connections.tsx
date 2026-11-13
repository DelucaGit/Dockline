import { useQuery } from '@tanstack/react-query'
import { NavLink } from 'react-router-dom'
import { fetchStatus, statusKey, type ConnectionState } from '@/api/github'
import { cn } from '@/lib/utils'
import { strings } from '@/strings'

type Shown = ConnectionState | 'UNKNOWN'

const DOT: Record<Shown, string> = {
  CONNECTED: 'bg-green-500',
  LIMITED: 'bg-yellow-500',
  ERROR: 'bg-red-500',
  NOT_CONNECTED: 'bg-red-500',
  UNKNOWN: 'bg-muted-foreground'
}

/** Status dots for GitHub and Hostinger at the bottom of the sidebar. A click opens Settings. */
export function Connections({ baseUrl }: { baseUrl: string }) {
  const { data } = useQuery({
    queryKey: statusKey(baseUrl),
    queryFn: () => fetchStatus(baseUrl),
    // The interval pauses while the window is hidden or minimised.
    refetchInterval: 30_000,
    refetchOnWindowFocus: true
  })

  const entries = [
    { source: 'GITHUB' as const, label: strings.connections.github },
    { source: 'HOSTINGER' as const, label: strings.connections.hostinger }
  ]

  return (
    <div className="mb-2">
      <h2 className="px-3 pb-1 text-xs font-medium uppercase text-muted-foreground">{strings.connections.heading}</h2>
      {entries.map(({ source, label }) => {
        const state: Shown = data?.sources.find((s) => s.source === source)?.state ?? 'UNKNOWN'
        const text = strings.connections.state[state]
        return (
          <NavLink
            key={source}
            to="/settings"
            title={`${label}: ${text}`}
            aria-label={`${label}: ${text}`}
            data-testid={`connection-${source.toLowerCase()}`}
            data-state={state}
            className={cn('flex items-center gap-2 rounded-md px-3 py-1.5 text-sm hover:bg-accent')}
          >
            <span aria-hidden className={cn('size-2.5 shrink-0 rounded-full', DOT[state])} />
            <span>{label}</span>
          </NavLink>
        )
      })}
    </div>
  )
}
