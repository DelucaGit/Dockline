import { useEffect, useRef } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { statusKey } from '@/api/github'
import { domainsKey, fetchDomains, type HostingerDomain } from '@/api/hostinger'
import { RateLimited } from '@/components/RateLimited'
import { ViewHeader } from '@/components/ViewHeader'
import { CARD, ViewEmpty, ViewError, ViewLoading } from '@/components/ViewState'
import { Button } from '@/components/ui/button'
import { problemState, type ProblemState } from '@/lib/problemState'
import { strings } from '@/strings'

const SOON_DAYS = 30
const DAY_MS = 24 * 60 * 60 * 1000

type Expiry = 'ok' | 'soon' | 'expired'

/** Expired: Hostinger says so, or the date is past. Soon: within the next 30 days. */
function expiryOf(domain: HostingerDomain, now: number): Expiry {
  const at = domain.expiresAt ? Date.parse(domain.expiresAt) : NaN
  if (domain.status === 'expired' || (!Number.isNaN(at) && at < now)) return 'expired'
  if (!Number.isNaN(at) && at - now <= SOON_DAYS * DAY_MS) return 'soon'
  return 'ok'
}

const formatDate = (iso: string) => new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium', timeZone: 'UTC' })

// Colour plus a text label, so the state never relies on colour alone; each theme has its own shades.
const ROW_LOOK: Record<Expiry, string> = {
  ok: 'border-border',
  soon: 'border-amber-500 bg-amber-50 dark:bg-amber-950/40',
  expired: 'border-red-500 bg-red-50 dark:bg-red-950/40'
}
const BADGE_LOOK: Record<'soon' | 'expired', string> = {
  soon: 'border-amber-600 bg-amber-100 text-amber-900 dark:border-amber-400 dark:bg-amber-900/60 dark:text-amber-100',
  expired: 'border-red-600 bg-red-100 text-red-900 dark:border-red-400 dark:bg-red-900/60 dark:text-red-100'
}

function DomainRow({ domain, now }: { domain: HostingerDomain; now: number }) {
  const t = strings.domains
  const expiry = expiryOf(domain, now)
  return (
    <li
      data-testid="domain-row"
      data-expiry={expiry}
      className={`flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border p-3 ${ROW_LOOK[expiry]}`}
    >
      <span className="font-medium">{domain.domain ?? t.noName}</span>
      {expiry !== 'ok' && (
        <span className={`rounded border px-1.5 py-0.5 text-xs font-medium ${BADGE_LOOK[expiry]}`}>
          {expiry === 'soon' ? t.expiringSoon : t.expired}
        </span>
      )}
      <span className="ml-auto text-sm text-muted-foreground">
        {domain.expiresAt ? (
          <>
            {expiry === 'expired' ? t.expiredOn : t.expires}{' '}
            <time dateTime={domain.expiresAt}>{formatDate(domain.expiresAt)}</time>
          </>
        ) : (
          t.noExpiry
        )}
      </span>
    </li>
  )
}

function DomainsProblem({ problem, onRetry }: { problem: ProblemState; onRetry: () => void }) {
  const t = strings.domains
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
    return <RateLimited message={t.rateLimitedRetry} seconds={problem.retryAfterSeconds} onElapsed={onRetry} />
  }
  const title = problem.kind === 'RATE_LIMITED' ? t.rateLimited : t.loadError
  return <ViewError title={title} message={problem.detail ?? t.loadErrorHint} onRetry={onRetry} />
}

export function DomainsPage({ baseUrl }: { baseUrl: string }) {
  // Opening the view may reuse the backend's 60-second copy; Refresh (and "Try again") asks Hostinger again.
  const forceRefresh = useRef(false)
  const domains = useQuery({
    queryKey: domainsKey(baseUrl),
    queryFn: async () => {
      // Cleared only after success, so react-query's automatic retries of a failed Refresh also skip the backend copy.
      const answer = await fetchDomains(baseUrl, forceRefresh.current)
      forceRefresh.current = false
      return answer
    },
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0,
    gcTime: 0
  })
  // Unlike Sites, the last successful answer stays on screen when a later request fails (react-query keeps `data`).
  const data = domains.data
  const queryClient = useQueryClient()
  const refresh = () => {
    forceRefresh.current = true
    void domains.refetch()
  }

  // A failure changes the Hostinger status at once (e.g. yellow while limited); refresh the sidebar dot now.
  useEffect(() => {
    if (domains.isError) void queryClient.invalidateQueries({ queryKey: statusKey(baseUrl) })
  }, [domains.isError, domains.errorUpdatedAt, baseUrl, queryClient])

  const now = Date.now()
  return (
    <section>
      <ViewHeader title={strings.domains.title} onRefresh={refresh} isFetching={domains.isFetching} fetchedAt={data?.fetchedAt} />
      {domains.isPending && <ViewLoading />}
      {domains.isError && !domains.isFetching && <DomainsProblem problem={problemState(domains.error)} onRetry={refresh} />}
      {data && data.domains.length === 0 && <ViewEmpty title={strings.domains.empty} />}
      {data && data.domains.length > 0 && (
        <ul aria-label={strings.domains.title} className="mt-4 space-y-2">
          {data.domains.map((domain, i) => (
            <DomainRow key={`${domain.domain ?? ''}-${i}`} domain={domain} now={now} />
          ))}
        </ul>
      )}
    </section>
  )
}
