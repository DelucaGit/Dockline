import type { components } from '@/api/schema'
import { problemFromResponse } from '@/api/github'

export type GithubRepositories = components['schemas']['GithubRepositories']
export type GithubRepository = components['schemas']['GithubRepository']

export const reposKey = (baseUrl: string) => ['github-repositories', baseUrl] as const

/** Every call goes to GitHub (the backend does not cache it), so only call this on open and on Refresh. */
export async function fetchRepositories(baseUrl: string): Promise<GithubRepositories> {
  const res = await fetch(`${baseUrl}/api/github/repositories`)
  if (res.ok) return (await res.json()) as GithubRepositories
  throw await problemFromResponse(res)
}
