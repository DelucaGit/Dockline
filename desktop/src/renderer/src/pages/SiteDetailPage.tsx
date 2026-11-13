import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiProblem, statusKey } from '@/api/github'
import { fetchRepositories, reposKey } from '@/api/repos'
import {
  fetchSite,
  linkSiteRepo,
  siteKey,
  siteLinksKey,
  unlinkSiteRepo,
  type SiteDetail,
  type SiteSourceProblem
} from '@/api/sites'
import { ViewHeader } from '@/components/ViewHeader'
import { CARD, ViewError, ViewLoading } from '@/components/ViewState'
import { Button } from '@/components/ui/button'
import { problemState } from '@/lib/problemState'
import { strings } from '@/strings'

const t = strings.site

const NOTICE = 'max-w-xl rounded-md border p-3 text-sm'
const WARNING = `${NOTICE} border-amber-600 bg-amber-100 text-amber-900 dark:border-amber-400 dark:bg-amber-900/60 dark:text-amber-100`

/** One source could not be asked: a note, never an error state. The rest of the view stays as it is. */
function SourceNotice({ problem }: { problem: SiteSourceProblem }) {
  const hostinger = problem.source === 'HOSTINGER'
  return (
    <div role="status" data-testid={`site-problem-${problem.source.toLowerCase()}`} className={`${NOTICE} border-border bg-muted`}>
      <p className="font-medium">{hostinger ? t.hostingerProblem : t.githubProblem}</p>
      <p className="mt-1 text-muted-foreground">{problem.message}</p>
      {hostinger && <p className="mt-1 text-muted-foreground">{t.hostingerProblemHint}</p>}
    </div>
  )
}

/** Lists the user's GitHub repositories; picking one saves the link at once. */
function RepoPicker({
  baseUrl,
  domain,
  current,
  onSaved,
  onCancel
}: {
  baseUrl: string
  domain: string
  current?: string
  onSaved: (site: SiteDetail) => void
  onCancel: () => void
}) {
  const p = t.picker
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [filter, setFilter] = useState('')
  const repos = useQuery({
    queryKey: reposKey(baseUrl),
    queryFn: () => fetchRepositories(baseUrl),
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0,
    gcTime: 0
  })
  const save = useMutation({
    mutationFn: (repoFullName: string) => linkSiteRepo(baseUrl, domain, repoFullName),
    onSuccess: onSaved
  })

  // A failed GitHub call changes the GitHub status at once; refresh the sidebar dot now.
  useEffect(() => {
    if (repos.isError) void queryClient.invalidateQueries({ queryKey: statusKey(baseUrl) })
  }, [repos.isError, repos.errorUpdatedAt, baseUrl, queryClient])

  let body
  if (repos.isPending) {
    body = <ViewLoading />
  } else if (repos.isError) {
    const problem = problemState(repos.error)
    if (problem.kind === 'NOT_CONNECTED') {
      body = (
        <div data-testid="github-not-connected">
          <p className="font-medium">{p.githubNotConnected}</p>
          <p className="mt-1 text-sm text-muted-foreground">{p.githubNotConnectedHint}</p>
          <Button className="mt-3" onClick={() => navigate('/settings')}>
            {p.goToSettings}
          </Button>
        </div>
      )
    } else if (problem.kind === 'TOKEN_REJECTED') {
      body = (
        <div role="alert">
          <p className="font-medium text-red-600 dark:text-red-400">{p.githubTokenRejected}</p>
          {problem.detail && <p className="mt-1 text-sm text-muted-foreground">{problem.detail}</p>}
          <Button className="mt-3" onClick={() => navigate('/settings', { state: { reconnect: true } })}>
            {p.reconnect}
          </Button>
        </div>
      )
    } else {
      body = (
        <div role="alert">
          <p className="font-medium text-red-600 dark:text-red-400">{p.loadError}</p>
          <p className="mt-1 text-sm text-muted-foreground">{problem.detail ?? p.loadErrorHint}</p>
          <Button className="mt-3" variant="outline" onClick={() => void repos.refetch()}>
            {strings.view.retry}
          </Button>
        </div>
      )
    }
  } else if (repos.data.repositories.length === 0) {
    body = <p className="text-sm text-muted-foreground">{p.empty}</p>
  } else {
    const needle = filter.trim().toLowerCase()
    const shown = repos.data.repositories.filter((repo) => repo.fullName.toLowerCase().includes(needle))
    body = (
      <>
        <label htmlFor="repo-filter" className="block text-sm font-medium">
          {p.filterLabel}
        </label>
        <input
          id="repo-filter"
          type="search"
          autoFocus
          autoComplete="off"
          spellCheck={false}
          value={filter}
          placeholder={p.filterPlaceholder}
          onChange={(e) => setFilter(e.target.value)}
          className="mt-1 h-9 w-full rounded-md border border-border bg-background px-2 text-foreground"
        />
        {shown.length === 0 ? (
          <p className="mt-3 text-sm text-muted-foreground">{p.noMatch}</p>
        ) : (
          <ul aria-label={p.listLabel} className="mt-3 max-h-72 space-y-1 overflow-auto">
            {shown.map((repo) => (
              <li key={repo.fullName}>
                <button
                  type="button"
                  data-testid="repo-option"
                  disabled={save.isPending}
                  aria-current={repo.fullName === current ? 'true' : undefined}
                  onClick={() => save.mutate(repo.fullName)}
                  className="flex w-full items-center gap-2 rounded-md border border-border px-3 py-2 text-left text-sm hover:bg-accent hover:text-accent-foreground focus-visible:bg-accent focus-visible:text-accent-foreground focus-visible:outline-none disabled:opacity-60"
                >
                  <span className="font-medium">{repo.fullName}</span>
                  {repo.fullName === current && <span className="text-xs text-muted-foreground">({p.current})</span>}
                </button>
              </li>
            ))}
          </ul>
        )}
        {repos.data.truncated && <p className="mt-2 text-sm text-muted-foreground">{p.truncated}</p>}
      </>
    )
  }

  return (
    <section data-testid="repo-picker" aria-labelledby="repo-picker-title" className="space-y-3 rounded-md border border-border p-3">
      <h3 id="repo-picker-title" className="font-medium">
        {p.title}
      </h3>
      <div>{body}</div>
      {save.isPending && (
        <p role="status" className="text-sm text-muted-foreground">
          {p.saving}
        </p>
      )}
      {save.isError && (
        // The backend's own explanation, e.g. "the site is Git-connected in Hostinger".
        <p role="alert" data-testid="link-error" className="text-sm text-red-600 dark:text-red-400">
          {save.error instanceof ApiProblem && save.error.detail ? save.error.detail : p.saveError}
        </p>
      )}
      <Button variant="outline" onClick={onCancel}>
        {p.cancel}
      </Button>
    </section>
  )
}

function LinkedRepo({ baseUrl, site }: { baseUrl: string; site: SiteDetail }) {
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<'view' | 'picking' | 'confirmingRemove'>('view')
  const key = siteKey(baseUrl, site.domain)
  const linksChanged = () => queryClient.invalidateQueries({ queryKey: siteLinksKey(baseUrl) })

  const remove = useMutation({
    mutationFn: () => unlinkSiteRepo(baseUrl, site.domain),
    onSuccess: async () => {
      // Shown at once; the refetch then tells whether Hostinger knows a repo after all.
      queryClient.setQueryData<SiteDetail>(key, (old) => old && { ...old, repo: null, source: 'NONE', repoWarning: null })
      setMode('view')
      void linksChanged()
      await queryClient.invalidateQueries({ queryKey: key })
    }
  })

  const manual = site.source === 'MANUAL'
  return (
    <div className={`${CARD} space-y-3`}>
      <h2 className="font-semibold">{t.repoHeading}</h2>
      {site.repo ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <button
            type="button"
            data-testid="site-repo"
            onClick={() => void window.dockline.openExternal(site.repo!.htmlUrl)}
            title={t.openOnGithub}
            className="font-medium hover:underline focus-visible:underline focus-visible:outline-none"
          >
            {site.repo.fullName}
          </button>
          <span data-testid="site-repo-source" className="rounded border border-border px-1.5 py-0.5 text-xs text-muted-foreground">
            {manual ? t.linkedManually : t.fromHostinger}
          </span>
        </div>
      ) : (
        <div>
          <p className="font-medium">{t.noRepo}</p>
          <p className="mt-1 text-sm text-muted-foreground">{t.noRepoHint}</p>
        </div>
      )}
      {site.source === 'HOSTINGER' && <p className="text-sm text-muted-foreground">{t.fromHostingerHint}</p>}
      {site.repoWarning && (
        <div role="status" data-testid="repo-warning" className={WARNING}>
          <p className="font-medium">{t.repoWarningTitle}</p>
          <p className="mt-1">{site.repoWarning.message}</p>
        </div>
      )}

      {mode === 'picking' && (
        <RepoPicker
          baseUrl={baseUrl}
          domain={site.domain}
          current={site.repo?.fullName}
          onCancel={() => setMode('view')}
          onSaved={(saved) => {
            queryClient.setQueryData(key, saved)
            setMode('view')
            void linksChanged()
          }}
        />
      )}
      {mode === 'confirmingRemove' && site.repo && (
        <div role="alertdialog" aria-labelledby="remove-link-title" className="space-y-3 rounded-md border border-border p-3">
          <p id="remove-link-title" className="font-medium">
            {t.confirmRemoveTitle.replace('{repo}', site.repo.fullName)}
          </p>
          <p className="text-sm text-muted-foreground">{t.confirmRemoveText}</p>
          {remove.isError && (
            <p role="alert" className="text-sm text-red-600 dark:text-red-400">
              {remove.error instanceof ApiProblem && remove.error.detail ? remove.error.detail : t.removeError}
            </p>
          )}
          <div className="flex gap-2">
            <Button onClick={() => remove.mutate()} disabled={remove.isPending}>
              {remove.isPending ? t.removing : t.confirmRemoveYes}
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                remove.reset()
                setMode('view')
              }}
            >
              {t.confirmRemoveNo}
            </Button>
          </div>
        </div>
      )}
      {mode === 'view' && site.source === 'NONE' && <Button onClick={() => setMode('picking')}>{t.linkRepo}</Button>}
      {mode === 'view' && manual && (
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => setMode('picking')}>
            {t.change}
          </Button>
          <Button variant="outline" onClick={() => setMode('confirmingRemove')}>
            {t.remove}
          </Button>
        </div>
      )}
    </div>
  )
}

export function SiteDetailPage({ baseUrl }: { baseUrl: string }) {
  const domain = useParams().domain ?? ''
  const navigate = useNavigate()
  // Opening the view may reuse the backend's saved answer; Refresh (and "Try again") asks Hostinger again.
  const forceRefresh = useRef(false)
  const site = useQuery({
    queryKey: siteKey(baseUrl, domain),
    queryFn: async () => {
      const answer = await fetchSite(baseUrl, domain, forceRefresh.current)
      forceRefresh.current = false
      return answer
    },
    retry: (count, error) => !(error instanceof ApiProblem && error.status === 404) && count < 3,
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0,
    gcTime: 0
  })
  const data = site.isError ? undefined : site.data
  const refresh = () => {
    forceRefresh.current = true
    void site.refetch()
  }
  const notFound = site.error instanceof ApiProblem && site.error.code === 'SITE_NOT_FOUND'

  return (
    <section>
      <Link
        to="/sites"
        className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground hover:underline focus-visible:text-foreground focus-visible:underline focus-visible:outline-none"
      >
        <ArrowLeft className="size-4" aria-hidden />
        {t.back}
      </Link>
      <ViewHeader title={data?.domain ?? domain} onRefresh={refresh} isFetching={site.isFetching} />
      {site.isPending && <ViewLoading />}
      {site.isError && !site.isFetching && notFound && (
        <div data-testid="site-not-found" className="mt-8 text-center">
          <p className="text-lg font-medium">{t.notFound}</p>
          <p className="mt-1 text-muted-foreground">{t.notFoundHint}</p>
          <Button className="mt-3" onClick={() => navigate('/sites')}>
            {t.back}
          </Button>
        </div>
      )}
      {site.isError && !site.isFetching && !notFound && (
        <ViewError title={t.loadError} message={problemState(site.error).detail ?? t.loadErrorHint} onRetry={refresh} />
      )}
      {data && (
        <div className="mt-4 space-y-3">
          {data.problems.map((problem) => (
            <SourceNotice key={problem.source} problem={problem} />
          ))}
          <LinkedRepo baseUrl={baseUrl} site={data} />
        </div>
      )}
    </section>
  )
}
