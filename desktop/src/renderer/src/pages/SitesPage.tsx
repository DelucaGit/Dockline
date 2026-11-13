import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { statusKey } from '@/api/github'
import { fetchSites, sitesKey, type HostingerSite } from '@/api/hostinger'
import { ViewHeader } from '@/components/ViewHeader'
import { CARD, ViewEmpty, ViewError, ViewLoading } from '@/components/ViewState'
import { Button } from '@/components/ui/button'
import { problemState, type ProblemState } from '@/lib/problemState'
import { strings } from '@/strings'

function SiteRow({ site }: { site: HostingerSite }) {
  const t = strings.sites
  return (
    <li data-testid="site-row" className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-border p-3">
      <span className="font-medium">{site.domain ?? t.noDomain}</span>
      {site.websiteType && (
        <span className="rounded border border-border px-1.5 py-0.5 text-xs text-muted-foreground">{site.websiteType}</span>
      )}
      {!site.enabled && (
        <span className="rounded border border-border px-1.5 py-0.5 text-xs text-muted-foreground">{t.suspended}</span>
      )}
    </li>
  )
}

/** "Rate limited, retrying in X s": counts down locally and asks again when it reaches 0. */
function RateLimited({ seconds, onElapsed }: { seconds: number; onElapsed: () => void }) {
  const [left, setLeft] = useState(seconds)
  const elapsed = useRef(onElapsed)
  elapsed.current = onElapsed
  useEffect(() => {
    setLeft(seconds)
    const started = Date.now()
    const timer = setInterval(() => {
      const remaining = Math.max(0, seconds - Math.floor((Date.now() - started) / 1000))
      setLeft(remaining)
      if (remaining === 0) {
        clearInterval(timer)
        elapsed.current()
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [seconds])
  return (
    <p role="status" className="mt-8 text-center text-lg font-medium">
      {strings.sites.rateLimitedRetry.replace('{seconds}', String(left))}
    </p>
  )
}

function SitesProblem({ problem, onRetry }: { problem: ProblemState; onRetry: () => void }) {
  const t = strings.sites
  const navigate = useNavigate()
  if (problem.kind === 'NOT_CONNECTED') {
    return (
      <div className="mt-8 text-center">
        <p className="text-lg font-medium">{t.notConnected}</p>
        <p className="mt-1 text-muted-foreground">{t.notConnectedHint}</p>
        <Button className="mt-3" onClick={() => navigate('/settings')}>
          {t.goToSettings}
        </Button>
      </div>
    )
  }
  if (problem.kind === 'TOKEN_REJECTED') {
    return (
      <div role="alert" className={`mt-6 ${CARD}`}>
        <h2 className="font-semibold text-red-600 dark:text-red-400">{t.tokenRejected}</h2>
        {problem.detail && <p className="mt-1 text-sm text-muted-foreground">{problem.detail}</p>}
        <Button className="mt-3" onClick={() => navigate('/settings', { state: { replaceToken: true } })}>
          {t.replaceToken}
        </Button>
      </div>
    )
  }
  if (problem.kind === 'RATE_LIMITED' && problem.retryAfterSeconds) {
    return <RateLimited seconds={problem.retryAfterSeconds} onElapsed={onRetry} />
  }
  const title = problem.kind === 'RATE_LIMITED' ? t.rateLimited : t.loadError
  return <ViewError title={title} message={problem.detail ?? t.loadErrorHint} onRetry={onRetry} />
}

export function SitesPage({ baseUrl }: { baseUrl: string }) {
  // Opening the view may reuse the backend's 60-second copy; Refresh (and "Try again") asks Hostinger again.
  const forceRefresh = useRef(false)
  const sites = useQuery({
    queryKey: sitesKey(baseUrl),
    queryFn: () => {
      const refresh = forceRefresh.current
      forceRefresh.current = false
      return fetchSites(baseUrl, refresh)
    },
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0,
    gcTime: 0
  })
  const data = sites.isError ? undefined : sites.data
  const queryClient = useQueryClient()
  const refresh = () => {
    forceRefresh.current = true
    void sites.refetch()
  }

  // A failure changes the Hostinger status at once (e.g. yellow while limited); refresh the sidebar dot now.
  useEffect(() => {
    if (sites.isError) void queryClient.invalidateQueries({ queryKey: statusKey(baseUrl) })
  }, [sites.isError, sites.errorUpdatedAt, baseUrl, queryClient])

  return (
    <section>
      <ViewHeader title={strings.sites.title} onRefresh={refresh} isFetching={sites.isFetching} fetchedAt={data?.fetchedAt} />
      {sites.isPending && <ViewLoading />}
      {sites.isError && !sites.isFetching && <SitesProblem problem={problemState(sites.error)} onRetry={refresh} />}
      {data && data.sites.length === 0 && <ViewEmpty title={strings.sites.empty} />}
      {data && data.sites.length > 0 && (
        <ul aria-label={strings.sites.title} className="mt-4 space-y-2">
          {data.sites.map((site, i) => (
            <SiteRow key={`${site.domain ?? ''}-${site.username ?? ''}-${i}`} site={site} />
          ))}
        </ul>
      )}
      {data?.truncated && <p className="mt-3 text-sm text-muted-foreground">{strings.sites.truncated}</p>}
    </section>
  )
}
