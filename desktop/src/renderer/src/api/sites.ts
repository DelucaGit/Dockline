import { problemFromResponse } from '@/api/github'
import type { components } from '@/api/schema'

export type SiteRepo = components['schemas']['SiteRepo']
export type SiteRepoWarning = components['schemas']['SiteRepoWarning']
export type SiteSourceProblem = components['schemas']['SiteSourceProblem']

// The generated schema drops "nullable" on fields that refer to another schema, so `repo` and `repoWarning`
// are typed here as what the backend really sends: null when there is none.
export type SiteDetail = Omit<components['schemas']['SiteDetail'], 'repo' | 'repoWarning'> & {
  repo: SiteRepo | null
  repoWarning: SiteRepoWarning | null
}
export type SiteLink = Omit<components['schemas']['SiteLink'], 'repo'> & { repo: SiteRepo | null }
export type SiteLinks = Omit<components['schemas']['SiteLinks'], 'links'> & { links: SiteLink[] }

export const siteKey = (baseUrl: string, domain: string) => ['site', baseUrl, domain.toLowerCase()] as const
export const siteLinksKey = (baseUrl: string) => ['site-links', baseUrl] as const

const sitePath = (domain: string) => `/api/sites/${encodeURIComponent(domain)}`

/** One site and its linked repo. `refresh` asks Hostinger again about the site's Git connection (Refresh button). */
export async function fetchSite(baseUrl: string, domain: string, refresh = false): Promise<SiteDetail> {
  const res = await fetch(`${baseUrl}${sitePath(domain)}${refresh ? '?refresh=true' : ''}`)
  if (res.ok) return (await res.json()) as SiteDetail
  throw await problemFromResponse(res)
}

/** The linked repo of every site, for the Sites list. Always answers 200 when the backend is up. */
export async function fetchSiteLinks(baseUrl: string): Promise<SiteLinks> {
  const res = await fetch(`${baseUrl}/api/sites/links`)
  if (res.ok) return (await res.json()) as SiteLinks
  throw await problemFromResponse(res)
}

/** Sets or changes the manual link; answers with the site as it is now. */
export async function linkSiteRepo(baseUrl: string, domain: string, repoFullName: string): Promise<SiteDetail> {
  const res = await fetch(`${baseUrl}${sitePath(domain)}/repo`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repoFullName })
  })
  if (res.ok) return (await res.json()) as SiteDetail
  throw await problemFromResponse(res)
}

/** Removes the manual link (also fine when there was none). */
export async function unlinkSiteRepo(baseUrl: string, domain: string): Promise<void> {
  const res = await fetch(`${baseUrl}${sitePath(domain)}/repo`, { method: 'DELETE' })
  if (!res.ok) throw await problemFromResponse(res)
}
