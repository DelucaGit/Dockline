import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { DEPLOY_REFRESH_MS, deploysKey, fetchDeploys, type SiteDeploy, type SiteDeploys } from '@/api/deploys'
import { problemState } from '@/lib/problemState'
import { useForeground } from '@/lib/useForeground'

/** Why the deploy status is not fresh right now. The last known status stays on screen meanwhile. */
export type DeployTrouble =
  | { kind: 'RATE_LIMITED'; /** Whole seconds until the status is asked for again. */ secondsLeft: number }
  | { kind: 'ERROR'; /** The backend's own explanation, when it gave one. */ message?: string }

export interface SiteDeploysState {
  /** A site's latest deploy: null when it has none, undefined while no answer has arrived yet. Case-insensitive. */
  deployFor: (domain: string | null | undefined) => SiteDeploy | null | undefined
  /** When the deploys were last fetched (ISO); undefined until the first answer. */
  fetchedAt?: string
  isFetching: boolean
  /** Asks again now, skipping the backend's saved copy (Refresh button). Does nothing while not enabled. */
  refresh: () => void
  trouble: DeployTrouble | null
}

const RATE_LIMITED = 'HOSTINGER_RATE_LIMITED'
/** The wait when a rate limit does not say how long: one normal interval. */
const DEFAULT_WAIT_SECONDS = Math.ceil(DEPLOY_REFRESH_MS / 1000)

const unreadable = (deploy: SiteDeploy) => deploy.status === 'UNKNOWN' && deploy.problems.length > 0

/**
 * A site whose status could not be read this time (e.g. rate limited) keeps the status it had before; only its
 * `problems` are the new ones. Done on the cached answer, so every view sees the same.
 */
function keepLastKnown(answer: SiteDeploys, previous: SiteDeploys | undefined): SiteDeploys {
  if (!previous) return answer
  const before = new Map(previous.deploys.map((deploy) => [deploy.domain.toLowerCase(), deploy]))
  return {
    ...answer,
    deploys: answer.deploys.map((deploy) => {
      const known = unreadable(deploy) ? before.get(deploy.domain.toLowerCase()) : undefined
      return known && !unreadable(known) ? { ...known, problems: deploy.problems } : deploy
    })
  }
}

type Found = { kind: 'RATE_LIMITED'; until: number } | { kind: 'ERROR'; message?: string } | null

/**
 * The latest deploy of every site, shared by the Sites list and the site detail view (same cache). While
 * `enabled`, it is asked for again every DEPLOY_REFRESH_MS, but only while the calling view is mounted and the
 * app is in the foreground. While rate limited it waits as long as it was told to, then asks again once.
 */
export function useSiteDeploys(baseUrl: string, enabled = true): SiteDeploysState {
  const queryClient = useQueryClient()
  const foreground = useForeground()
  const force = useRef(false)
  const key = deploysKey(baseUrl)

  // Decided before the query, from what is in the cache, so a rate limit can switch the interval off.
  const cached = queryClient.getQueryState<SiteDeploys>(key)
  let found: Found = null
  if (cached?.status === 'error') {
    const problem = problemState(cached.error)
    found =
      problem.kind === 'RATE_LIMITED'
        ? { kind: 'RATE_LIMITED', until: cached.errorUpdatedAt + (problem.retryAfterSeconds ?? DEFAULT_WAIT_SECONDS) * 1000 }
        : { kind: 'ERROR', message: problem.detail }
  } else if (cached?.data) {
    const problems = cached.data.deploys.flatMap((deploy) => deploy.problems)
    const limited = problems.filter((problem) => problem.code === RATE_LIMITED)
    if (limited.length > 0) {
      const seconds = Math.max(...limited.map((problem) => problem.retryAfterSeconds ?? 0)) || DEFAULT_WAIT_SECONDS
      found = { kind: 'RATE_LIMITED', until: cached.dataUpdatedAt + seconds * 1000 }
    } else if (problems.length > 0) {
      found = { kind: 'ERROR', message: problems[0].message ?? undefined }
    }
  }
  const limitedUntil = found?.kind === 'RATE_LIMITED' ? found.until : null

  const deploys = useQuery({
    queryKey: key,
    queryFn: async () => {
      const answer = await fetchDeploys(baseUrl, force.current)
      force.current = false
      return keepLastKnown(answer, queryClient.getQueryData<SiteDeploys>(key))
    },
    enabled,
    retry: false,
    // While rate limited the countdown below decides when to ask again, never the normal interval.
    refetchInterval: foreground && limitedUntil === null ? DEPLOY_REFRESH_MS : false,
    refetchOnWindowFocus: false,
    refetchOnMount: 'always',
    staleTime: 0
  })
  const refetch = deploys.refetch

  // Rate limited: count down once a second and ask again, once, when the wait is over.
  const [, tick] = useState(0)
  const askedFor = useRef<number | null>(null)
  useEffect(() => {
    if (limitedUntil === null || !foreground || !enabled) return
    const check = () => {
      tick((n) => n + 1)
      if (Date.now() >= limitedUntil && askedFor.current !== limitedUntil) {
        askedFor.current = limitedUntil
        void refetch()
      }
    }
    check()
    const timer = setInterval(check, 1000)
    return () => clearInterval(timer)
  }, [limitedUntil, foreground, enabled, refetch])

  // Back in the foreground: do not wait a whole interval when what is shown is already old.
  const wasForeground = useRef(foreground)
  const updatedAt = deploys.dataUpdatedAt
  useEffect(() => {
    const returned = foreground && !wasForeground.current
    wasForeground.current = foreground
    if (returned && enabled && limitedUntil === null && Date.now() - updatedAt >= DEPLOY_REFRESH_MS) void refetch()
    // Only a change of `foreground` matters here.
  }, [foreground])

  const byDomain = new Map<string, SiteDeploy>()
  for (const deploy of deploys.data?.deploys ?? []) byDomain.set(deploy.domain.toLowerCase(), deploy)

  let trouble: DeployTrouble | null = null
  if (enabled && found?.kind === 'RATE_LIMITED') {
    trouble = { kind: 'RATE_LIMITED', secondsLeft: Math.max(0, Math.ceil((found.until - Date.now()) / 1000)) }
  } else if (enabled && found?.kind === 'ERROR') {
    trouble = found
  }

  return {
    deployFor: (domain) => (deploys.data ? ((domain && byDomain.get(domain.toLowerCase())) || null) : undefined),
    fetchedAt: deploys.data?.fetchedAt,
    isFetching: deploys.isFetching,
    refresh: () => {
      if (!enabled) return
      force.current = true
      void refetch()
    },
    trouble
  }
}
