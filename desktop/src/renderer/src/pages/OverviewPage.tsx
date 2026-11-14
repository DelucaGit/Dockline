import { useEffect, useRef, useState, type ReactNode, type Ref } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { DEPLOY_REFRESH_MS } from '@/api/deploys'
import { statusKey, type SourceStatus, type StatusResponse } from '@/api/github'
import {
  fetchOverview,
  overviewKey,
  type Overview,
  type OverviewCount,
  type OverviewDeploy,
  type OverviewProblem
} from '@/api/overview'
import type { SiteSourceProblem } from '@/api/sites'
import { DeployStatus } from '@/components/DeployStatus'
import { ViewHeader } from '@/components/ViewHeader'
import { ViewError } from '@/components/ViewState'
import { Button } from '@/components/ui/button'
import { formatExactTime, formatRelativeTime } from '@/lib/relativeTime'
import { problemState } from '@/lib/problemState'
import { useForeground } from '@/lib/useForeground'
import { strings } from '@/strings'

const t = strings.overview

/** The wait when a rate limit does not say how long: one normal interval. */
const DEFAULT_WAIT_SECONDS = Math.ceil(DEPLOY_REFRESH_MS / 1000)

const isRateLimit = (error: SiteSourceProblem) => error.code.endsWith('_RATE_LIMITED')

/** When a rate-limited part may be asked again (ms), counted from when the answer arrived. */
const limitEnd = (error: SiteSourceProblem, answeredAt: number) =>
  answeredAt + (error.retryAfterSeconds ?? DEFAULT_WAIT_SECONDS) * 1000

function allErrors(data: Overview): SiteSourceProblem[] {
  return [...data.sites.errors, ...data.repos.errors, ...data.problems.errors, ...data.latestDeploys.errors]
}

const SECTION = 'rounded-md border border-border p-4'

/** Why one part of the page is missing or incomplete; only that part shows it. */
function PartErrors({ errors, answeredAt }: { errors: SiteSourceProblem[]; answeredAt: number }) {
  const navigate = useNavigate()
  if (errors.length === 0) return null
  return (
    <div className="mt-2 space-y-1">
      {errors.map((error) => {
        const limited = isRateLimit(error)
        const left = Math.max(0, Math.ceil((limitEnd(error, answeredAt) - Date.now()) / 1000))
        return (
          <div
            key={`${error.source}-${error.code}`}
            data-testid="overview-part-error"
            data-code={error.code}
            className="flex flex-wrap items-center gap-2 text-xs text-amber-700 dark:text-amber-400"
          >
            <span>{limited ? t.rateLimited.replace('{seconds}', String(left)) : error.message}</span>
            {error.code === 'GITHUB_TOKEN_REJECTED' && (
              <Button
                variant="outline"
                size="xs"
                onClick={() => navigate('/settings', { state: { reconnect: true } })}
              >
                {t.reconnect}
              </Button>
            )}
            {error.code === 'HOSTINGER_TOKEN_REJECTED' && (
              <Button
                variant="outline"
                size="xs"
                onClick={() => navigate('/settings', { state: { replaceToken: true } })}
              >
                {t.replaceToken}
              </Button>
            )}
            {error.code.endsWith('_NOT_CONNECTED') && (
              <Button variant="outline" size="xs" onClick={() => navigate('/settings')}>
                {t.goToSettings}
              </Button>
            )}
          </div>
        )
      })}
    </div>
  )
}

function PartLoading() {
  return (
    <p role="status" className="mt-2 text-sm text-muted-foreground">
      {t.loading}
    </p>
  )
}

interface CountCardProps {
  testId: string
  label: string
  title: string
  count: OverviewCount | undefined
  answeredAt: number
  /** A route to open, or a function to run, when the number is clicked. */
  to: string | (() => void)
}

function CountCard({ testId, label, title, count, answeredAt, to }: CountCardProps) {
  const number = (
    <>
      <span className="block text-sm text-muted-foreground">{label}</span>
      {count && (
        <span data-testid={`${testId}-value`} className="block text-3xl font-semibold">
          {count.count ?? t.notCounted}
        </span>
      )}
    </>
  )
  const linkClass = 'block rounded-md hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-foreground/50'
  return (
    <div className={SECTION}>
      {typeof to === 'string' ? (
        <Link to={to} data-testid={testId} title={title} aria-label={`${label}: ${count?.count ?? t.notCounted}`} className={linkClass}>
          {number}
        </Link>
      ) : (
        <button
          type="button"
          data-testid={testId}
          title={title}
          aria-label={`${label}: ${count?.count ?? t.notCounted}`}
          onClick={to}
          className={`w-full text-left ${linkClass}`}
        >
          {number}
        </button>
      )}
      {!count && <PartLoading />}
      {count && <PartErrors errors={count.errors} answeredAt={answeredAt} />}
    </div>
  )
}

function problemLink(problem: OverviewProblem): string {
  if (problem.kind === 'DOMAIN_EXPIRED' || problem.kind === 'DOMAIN_EXPIRING') return '/domains'
  if (problem.kind === 'SOURCE_NOT_CONNECTED' || problem.kind === 'SOURCE_FAILING' || !problem.domain) return '/settings'
  return `/sites/${encodeURIComponent(problem.domain)}`
}

function ProblemRow({ problem }: { problem: OverviewProblem }) {
  return (
    <li data-testid="overview-problem" data-kind={problem.kind} className="rounded-md border border-border p-3 text-sm">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="rounded border border-red-600/40 bg-red-500/10 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:text-red-400">
          {t.problemKind[problem.kind]}
        </span>
        <Link to={problemLink(problem)} className="font-medium hover:underline">
          {problem.domain ?? (problem.source === 'GITHUB' ? t.github : t.hostinger)}
        </Link>
        {problem.at && (
          <time dateTime={problem.at} title={formatExactTime(problem.at)} className="text-xs text-muted-foreground">
            {formatRelativeTime(problem.at)}
          </time>
        )}
      </div>
      <p className="mt-1 text-muted-foreground">{problem.message}</p>
    </li>
  )
}

function DeployRow({ deploy }: { deploy: OverviewDeploy }) {
  return (
    <li data-testid="overview-deploy" className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-border p-3">
      <Link to={`/sites/${encodeURIComponent(deploy.domain)}`} className="font-medium hover:underline">
        {deploy.domain}
      </Link>
      <DeployStatus deploy={{ ...deploy, source: 'HOSTINGER', problems: [] }} />
    </li>
  )
}

function SourceRow({ source }: { source: SourceStatus }) {
  const navigate = useNavigate()
  const name = source.source === 'GITHUB' ? t.github : t.hostinger
  return (
    <li
      data-testid={`overview-source-${source.source.toLowerCase()}`}
      data-state={source.state}
      className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-border p-3 text-sm"
    >
      <span className="font-medium">{name}</span>
      <span className="text-muted-foreground">{t.state[source.state]}</span>
      {source.message && <span className="basis-full text-xs text-muted-foreground">{source.message}</span>}
      {source.state === 'ERROR' && source.source === 'GITHUB' && (
        <Button variant="outline" size="xs" onClick={() => navigate('/settings', { state: { reconnect: true } })}>
          {t.reconnect}
        </Button>
      )}
      {source.state === 'ERROR' && source.source === 'HOSTINGER' && (
        <Button variant="outline" size="xs" onClick={() => navigate('/settings', { state: { replaceToken: true } })}>
          {t.replaceToken}
        </Button>
      )}
      {source.state === 'NOT_CONNECTED' && (
        <Button variant="outline" size="xs" onClick={() => navigate('/settings')}>
          {t.goToSettings}
        </Button>
      )}
    </li>
  )
}

function Section({ title, label, children, sectionRef }: { title: string; label: string; children: ReactNode; sectionRef?: Ref<HTMLElement> }) {
  return (
    <section ref={sectionRef} tabIndex={sectionRef ? -1 : undefined} aria-label={label} className={`${SECTION} focus:outline-none`}>
      <h2 className="font-semibold">{title}</h2>
      {children}
    </section>
  )
}

export function OverviewPage({ baseUrl }: { baseUrl: string }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const foreground = useForeground()
  // Opening the view and the automatic refresh reuse the backend's saved copies; Refresh asks the sources again.
  const forceRefresh = useRef(false)
  const overview = useQuery({
    queryKey: overviewKey(baseUrl),
    queryFn: async () => {
      const answer = await fetchOverview(baseUrl, forceRefresh.current)
      forceRefresh.current = false
      return answer
    },
    // Asked again like the deploy views: only while this view is open and the app is in the foreground.
    refetchInterval: foreground ? DEPLOY_REFRESH_MS : false,
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0
  })
  const data = overview.data
  const answeredAt = overview.dataUpdatedAt
  const refetch = overview.refetch

  const refresh = () => {
    forceRefresh.current = true
    void refetch()
  }

  // The answer carries the sources' state too: keep the sidebar dots in step without another request.
  useEffect(() => {
    if (data) queryClient.setQueryData<StatusResponse>(statusKey(baseUrl), { sources: data.sources })
  }, [data, baseUrl, queryClient])

  // Rate limited parts: count down once a second and ask again, once, when the shortest wait is over.
  const nextLimitEnd = data
    ? Math.min(...allErrors(data).filter(isRateLimit).map((error) => limitEnd(error, answeredAt)), Infinity)
    : Infinity
  const [, tick] = useState(0)
  const askedFor = useRef<number | null>(null)
  useEffect(() => {
    if (nextLimitEnd === Infinity || !foreground) return
    const check = () => {
      tick((n) => n + 1)
      if (Date.now() >= nextLimitEnd && askedFor.current !== nextLimitEnd) {
        askedFor.current = nextLimitEnd
        void refetch()
      }
    }
    const timer = setInterval(check, 1000)
    return () => clearInterval(timer)
  }, [nextLimitEnd, foreground, refetch])

  // Back in the foreground: do not wait a whole interval when what is shown is already old.
  const wasForeground = useRef(foreground)
  useEffect(() => {
    const returned = foreground && !wasForeground.current
    wasForeground.current = foreground
    if (returned && Date.now() - answeredAt >= DEPLOY_REFRESH_MS) void refetch()
    // Only a change of `foreground` matters here.
  }, [foreground])

  const problemsRef = useRef<HTMLElement>(null)
  const showProblems = () => {
    problemsRef.current?.scrollIntoView?.({ block: 'start', behavior: 'smooth' })
    problemsRef.current?.focus()
  }

  const nothingConnected = !!data && data.sources.length > 0 && data.sources.every((s) => s.state === 'NOT_CONNECTED')
  const failed = overview.isError && !overview.isFetching && !data

  return (
    <section>
      <ViewHeader title={t.title} onRefresh={refresh} isFetching={overview.isFetching} fetchedAt={data?.fetchedAt} />
      {failed && <ViewError title={t.loadError} message={problemState(overview.error).detail ?? t.loadErrorHint} onRetry={refresh} />}
      {nothingConnected && (
        <div data-testid="overview-nothing-connected" className="mt-8 text-center">
          <p className="text-lg font-medium">{t.nothingConnected}</p>
          <p className="mt-1 text-muted-foreground">{t.nothingConnectedHint}</p>
          <Button className="mt-3" onClick={() => navigate('/settings')}>
            {t.goToSettings}
          </Button>
        </div>
      )}
      {!failed && !nothingConnected && (
        <div className="mt-4 space-y-4">
          <div className="grid gap-4 sm:grid-cols-3">
            <CountCard testId="overview-count-sites" label={t.sites} title={t.showSites} count={data?.sites} answeredAt={answeredAt} to="/sites" />
            <CountCard testId="overview-count-repos" label={t.repos} title={t.showRepos} count={data?.repos} answeredAt={answeredAt} to="/repos" />
            <CountCard
              testId="overview-count-problems"
              label={t.problems}
              title={t.showProblems}
              count={data ? { count: data.problems.items.length, errors: [] } : undefined}
              answeredAt={answeredAt}
              to={showProblems}
            />
          </div>
          <Section title={t.problems} label={t.problems} sectionRef={problemsRef}>
            {!data && <PartLoading />}
            {data && data.problems.errors.length > 0 && <p className="mt-2 text-xs text-muted-foreground">{t.problemsIncomplete}</p>}
            {data && <PartErrors errors={data.problems.errors} answeredAt={answeredAt} />}
            {data && data.problems.items.length === 0 && <p className="mt-2 text-sm text-muted-foreground">{t.noProblems}</p>}
            {data && data.problems.items.length > 0 && (
              <ul data-testid="overview-problems" className="mt-2 space-y-2">
                {data.problems.items.map((problem, i) => (
                  <ProblemRow key={`${problem.kind}-${problem.source}-${problem.domain ?? ''}-${i}`} problem={problem} />
                ))}
              </ul>
            )}
          </Section>
          <Section title={t.deploysHeading} label={t.deploysHeading}>
            {!data && <PartLoading />}
            {data && <PartErrors errors={data.latestDeploys.errors} answeredAt={answeredAt} />}
            {data && data.latestDeploys.deploys.length === 0 && <p className="mt-2 text-sm text-muted-foreground">{t.noDeploys}</p>}
            {data && data.latestDeploys.deploys.length > 0 && (
              <ul data-testid="overview-deploys" className="mt-2 space-y-2">
                {data.latestDeploys.deploys.map((deploy) => (
                  <DeployRow key={deploy.domain} deploy={deploy} />
                ))}
              </ul>
            )}
          </Section>
          <Section title={t.sourcesHeading} label={t.sourcesHeading}>
            {!data && <PartLoading />}
            {data && (
              <ul className="mt-2 space-y-2">
                {data.sources.map((source) => (
                  <SourceRow key={source.source} source={source} />
                ))}
              </ul>
            )}
          </Section>
        </div>
      )}
    </section>
  )
}
