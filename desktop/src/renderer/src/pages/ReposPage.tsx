import { useQuery } from '@tanstack/react-query'
import { fetchRepositories, reposKey, type GithubRepository } from '@/api/repos'
import { ViewHeader } from '@/components/ViewHeader'
import { ViewEmpty, ViewError, ViewLoading } from '@/components/ViewState'
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
  const data = repos.data

  return (
    <section>
      <ViewHeader
        title={strings.repos.title}
        onRefresh={() => void repos.refetch()}
        isFetching={repos.isFetching}
        fetchedAt={data?.fetchedAt}
      />
      {repos.isPending && <ViewLoading />}
      {repos.isError && (
        <ViewError
          message={repos.error instanceof Error ? repos.error.message : strings.repos.loadError}
          onRetry={() => void repos.refetch()}
        />
      )}
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
