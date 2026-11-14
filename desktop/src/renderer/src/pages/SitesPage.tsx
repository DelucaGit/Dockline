import { useEffect, useRef } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import type { SiteDeploy } from '@/api/deploys'
import { statusKey } from '@/api/github'
import { fetchSites, sitesKey, type HostingerSite } from '@/api/hostinger'
import { fetchSiteLinks, siteLinksKey, type SiteRepo } from '@/api/sites'
import { DeployStatus } from '@/components/DeployStatus'
import { DeployTroubleNote } from '@/components/DeployTroubleNote'
import { RateLimited } from '@/components/RateLimited'
import { ViewHeader } from '@/components/ViewHeader'
import { CARD, ViewEmpty, ViewError, ViewLoading } from '@/components/ViewState'
import { Button } from '@/components/ui/button'
import { problemState, type ProblemState } from '@/lib/problemState'
import { useSiteDeploys } from '@/lib/useSiteDeploys'
import { strings } from '@/strings'

interface SiteRowProps {
  site: HostingerSite
  repo?: SiteRepo
  /** The site's latest deploy; null when there is none, undefined while the deploys are not known yet. */
  deploy?: SiteDeploy | null
}

function SiteRow({ site, repo, deploy }: SiteRowProps) {
  const t = strings.sites
  return (
    <li
      data-testid="site-row"
      className="relative flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-border p-3 has-[a]:hover:bg-accent/50"
    >
      {site.domain ? (
        // The link's ::after covers the whole row, so the row is clickable while the link stays one keyboard stop.
        <Link
          to={`/sites/${encodeURIComponent(site.domain)}`}
          title={t.openSite.replace('{domain}', site.domain)}
          className="font-medium after:absolute after:inset-0 after:rounded-md hover:underline focus-visible:underline focus-visible:outline-none focus-visible:after:ring-2 focus-visible:after:ring-foreground/50"
        >
          {site.domain}
        </Link>
      ) : (
        <span className="font-medium">{t.noDomain}</span>
      )}
      {site.websiteType && (
        <span className="rounded border border-border px-1.5 py-0.5 text-xs text-muted-foreground">{site.websiteType}</span>
      )}
      {!site.enabled && (
        <span className="rounded border border-border px-1.5 py-0.5 text-xs text-muted-foreground">{t.suspended}</span>
      )}
      {repo && (
        // Above the row link, so clicking the repo opens GitHub and does not also open the site.
        <button
          type="button"
          data-testid="site-row-repo"
          onClick={() => void window.dockline.openExternal(repo.htmlUrl)}
          title={t.openOnGithub}
          className="relative z-10 ml-auto text-sm text-muted-foreground hover:text-foreground hover:underline focus-visible:text-foreground focus-visible:underline focus-visible:outline-none"
        >
          {repo.fullName}
        </button>
      )}
      {deploy !== undefined && <DeployStatus deploy={deploy} className="basis-full" />}
    </li>
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
    return <RateLimited message={t.rateLimitedRetry} seconds={problem.retryAfterSeconds} onElapsed={onRetry} />
  }
  const title = problem.kind === 'RATE_LIMITED' ? t.rateLimited : t.loadError
  return <ViewError title={title} message={problem.detail ?? t.loadErrorHint} onRetry={onRetry} />
}

export function SitesPage({ baseUrl }: { baseUrl: string }) {
  // Opening the view may reuse the backend's 60-second copy; Refresh (and "Try again") asks Hostinger again.
  const forceRefresh = useRef(false)
  const sites = useQuery({
    queryKey: sitesKey(baseUrl),
    queryFn: async () => {
      // Cleared only after success, so react-query's automatic retries of a failed Refresh also skip the backend copy.
      const answer = await fetchSites(baseUrl, forceRefresh.current)
      forceRefresh.current = false
      return answer
    },
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0,
    gcTime: 0
  })
  const data = sites.isError ? undefined : sites.data
  const queryClient = useQueryClient()

  // The linked repo of each site is an extra: asked only once the list is there, never retried, and when it
  // fails (or Hostinger was not asked about a site yet) the row just shows no repo.
  const links = useQuery({
    queryKey: siteLinksKey(baseUrl),
    queryFn: () => fetchSiteLinks(baseUrl),
    enabled: !!data && data.sites.length > 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0
  })
  const repoByDomain = new Map<string, SiteRepo>()
  if (!links.isError) {
    for (const link of links.data?.links ?? []) {
      if (link.repo && link.hostingerChecked) repoByDomain.set(link.domain.toLowerCase(), link.repo)
    }
  }

  // The latest deploy of each site is an extra too: asked for once the list is there, then again automatically
  // while this view is open and the app is in the foreground. A failed call only shows a small note.
  const deploys = useSiteDeploys(baseUrl, !!data && data.sites.length > 0)

  const refresh = () => {
    forceRefresh.current = true
    void sites.refetch().then((answer) => {
      if (answer.isSuccess && answer.data.sites.length > 0) {
        void links.refetch()
        deploys.refresh()
      }
    })
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
      {data && data.sites.length > 0 && <DeployTroubleNote trouble={deploys.trouble} className="mt-3" />}
      {data && data.sites.length > 0 && (
        <ul aria-label={strings.sites.title} className="mt-4 space-y-2">
          {data.sites.map((site, i) => (
            <SiteRow
              key={`${site.domain ?? ''}-${site.username ?? ''}-${i}`}
              site={site}
              repo={site.domain ? repoByDomain.get(site.domain.toLowerCase()) : undefined}
              deploy={deploys.deployFor(site.domain)}
            />
          ))}
        </ul>
      )}
      {data?.truncated && <p className="mt-3 text-sm text-muted-foreground">{strings.sites.truncated}</p>}
    </section>
  )
}
