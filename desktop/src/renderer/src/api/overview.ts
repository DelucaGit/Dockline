import type { SiteDeployCommit } from '@/api/deploys'
import { problemFromResponse } from '@/api/github'
import type { components } from '@/api/schema'

export type OverviewCount = components['schemas']['OverviewCount']
export type OverviewProblem = components['schemas']['OverviewProblem']
export type OverviewProblemKind = components['schemas']['OverviewProblemKind']

// The generated schema drops "nullable" on `commit`; typed here as what the backend really sends.
export type OverviewDeploy = Omit<components['schemas']['OverviewDeploy'], 'commit'> & { commit: SiteDeployCommit | null }
export type OverviewDeploys = Omit<components['schemas']['OverviewDeploys'], 'deploys'> & { deploys: OverviewDeploy[] }
export type Overview = Omit<components['schemas']['Overview'], 'latestDeploys'> & { latestDeploys: OverviewDeploys }

export const overviewKey = (baseUrl: string) => ['overview', baseUrl] as const

/** Everything the Overview page shows. Always 200 while the backend is up. `refresh` skips the backend's saved copies. */
export async function fetchOverview(baseUrl: string, refresh = false): Promise<Overview> {
  const res = await fetch(`${baseUrl}/api/overview?refresh=${refresh ? 'true' : 'false'}`)
  if (res.ok) return (await res.json()) as Overview
  throw await problemFromResponse(res)
}
