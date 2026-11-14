import { problemFromResponse } from '@/api/github'
import type { components } from '@/api/schema'

export type SiteDeployStatus = components['schemas']['SiteDeployStatus']
export type SiteDeploySource = components['schemas']['SiteDeploySource']

// The generated schema drops "nullable" on these fields; typed here as what the backend really sends.
export type SiteDeployCommit = Omit<components['schemas']['SiteDeployCommit'], 'message'> & { message: string | null }
export type SiteDeploy = Omit<components['schemas']['SiteDeploy'], 'commit' | 'at'> & {
  at: string | null
  commit: SiteDeployCommit | null
}
export type SiteDeploys = Omit<components['schemas']['SiteDeploys'], 'deploys'> & { deploys: SiteDeploy[] }

export const deploysKey = (baseUrl: string) => ['site-deploys', baseUrl] as const

/**
 * How often the deploy status is asked for again while a view is visible and the app is in the foreground.
 * `VITE_DEPLOY_REFRESH_MS` overrides it at build time (tests and E2E).
 */
export const DEPLOY_REFRESH_MS = Number(import.meta.env.VITE_DEPLOY_REFRESH_MS) || 30_000

/** The latest deploy of every site. `refresh` skips the backend's 20-second copy (Refresh button). */
export async function fetchDeploys(baseUrl: string, refresh = false): Promise<SiteDeploys> {
  const res = await fetch(`${baseUrl}/api/sites/deploys?refresh=${refresh ? 'true' : 'false'}`)
  if (res.ok) return (await res.json()) as SiteDeploys
  throw await problemFromResponse(res)
}
