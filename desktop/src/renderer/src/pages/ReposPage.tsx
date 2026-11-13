import { useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { statusKey } from '@/api/github'
import { fetchRepositories, reposKey, type GithubRepository } from '@/api/repos'
import { ViewHeader } from '@/components/ViewHeader'
import { CARD, ViewEmpty, ViewError, ViewLoading } from '@/components/ViewState'
import { problemState, type ProblemState } from '@/lib/problemState'
import { Button } from '@/components/ui/button'
import { formatExactTime, formatRelativeTime } from '@/lib/relativeTime'
import { strings } from '@/strings'

function Badge({ children }: { children: string }) {
  return <span className="rounded border border-border px-1.5 py-0.5 text-xs text-muted-foreground">{children}</span>
}

function RepoRow({ repo }: { repo: GithubRepository }) {
  const t = strings.repos
  return (
    <li data-testid="repo-row" className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-border p-3">
      <button
        type="button"
        onClick={() => void window.dockline.openExternal(repo.htmlUrl)}
        title={t.openOnGithub}
        className="font-medium hover:underline focus-visible:underline focus-visible:outline-none"
      >
        {repo.fullName}
      </button>
      <Badge>{repo.visibility === 'PRIVATE' ? t.private : t.public}</Badge>
      {repo.fork && <Badge>{t.fork}</Badge>}
      {repo.archived && <Badge>{t.archived}</Badge>}
      {repo.lastPushAt ? (
        <time
          dateTime={repo.lastPushAt}
          title={formatExactTime(repo.lastPushAt)}
          className="ml-auto text-sm text-muted-foreground"
        >
          {formatRelativeTime(repo.lastPushAt)}
        </time>
      ) : (
        <span className="ml-auto text-sm text-muted-foreground">{t.noPushes}</span>
      )}
    </li>
  )
}

function ReposProblem({ problem, onRetry }: { problem: ProblemState; onRetry: () => void }) {
  const t = strings.repos
  const navigate = useNavigate()
  if (problem.kind === 'NOT_CONNECTED') {
    return (
      <div className="mt-8 text-center">
        <p className="text-lg font-medium">{t.notConnected}</p>
        <p className="mt-1 text-muted-foreground">{problem.detail ?? t.notConnectedHint}</p>
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
        <Button className="mt-3" onClick={() => navigate('/settings', { state: { reconnect: true } })}>
          {t.reconnect}
        </Button>
      </div>
    )
  }
  const hint =
    problem.kind === 'RATE_LIMITED' && problem.retryAfterSeconds
      ? ` ${t.rateLimitedRetry.replace('{seconds}', String(problem.retryAfterSeconds))}`
      : ''
  const title = problem.kind === 'RATE_LIMITED' ? t.rateLimited : t.loadError
  return <ViewError title={title} message={`${problem.detail ?? t.loadErrorHint}${hint}`} onRetry={onRetry} />
}

export function ReposPage({ baseUrl }: { baseUrl: string }) {
  // Fresh on open and on Refresh only: no timer, no refetch on focus. Every visit to the page refetches.
  const repos = useQuery({
    queryKey: reposKey(baseUrl),
    queryFn: () => fetchRepositories(baseUrl),
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0,
    gcTime: 0
  })
  const data = repos.isError ? undefined : repos.data // a failed refresh shows the error, not a stale list
  const queryClient = useQueryClient()

  // The backend flips the GitHub status when a call fails (e.g. a revoked token); refresh the sidebar dot now.
  // Only the status query is touched: nothing Hostinger-specific is refetched.
  useEffect(() => {
    if (repos.isError) void queryClient.invalidateQueries({ queryKey: statusKey(baseUrl) })
  }, [repos.isError, repos.errorUpdatedAt, baseUrl, queryClient])

  return (
    <section>
      <ViewHeader
        title={strings.repos.title}
        onRefresh={() => void repos.refetch()}
        isFetching={repos.isFetching}
        fetchedAt={data?.fetchedAt}
      />
      {repos.isPending && <ViewLoading />}
      {repos.isError && <ReposProblem problem={problemState(repos.error)} onRetry={() => void repos.refetch()} />}
      {data && data.repositories.length === 0 && <ViewEmpty title={strings.repos.empty} />}
      {data && data.repositories.length > 0 && (
        <ul aria-label={strings.repos.title} className="mt-4 space-y-2">
          {data.repositories.map((repo) => (
            <RepoRow key={repo.fullName} repo={repo} />
          ))}
        </ul>
      )}
      {data?.truncated && <p className="mt-3 text-sm text-muted-foreground">{strings.repos.truncated}</p>}
    </section>
  )
}
