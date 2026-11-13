import type { components } from '@/api/schema'
import { ApiProblem } from '@/api/github'

export type GithubRepositories = components['schemas']['GithubRepositories']
export type GithubRepository = components['schemas']['GithubRepository']

export const reposKey = (baseUrl: string) => ['github-repositories', baseUrl] as const

/** Every call goes to GitHub (the backend does not cache it), so only call this on open and on Refresh. */
export async function fetchRepositories(baseUrl: string): Promise<GithubRepositories> {
  const res = await fetch(`${baseUrl}/api/github/repositories`)
  if (res.ok) return (await res.json()) as GithubRepositories
  let title: string | undefined
  let detail: string | undefined
  let code: string | undefined
  try {
    const body = (await res.json()) as components['schemas']['ProblemDetail']
    title = body.title
    detail = body.detail
    code = (body as { code?: string }).code
  } catch {
    // not JSON: just the status
  }
  const retry = Number(res.headers.get('Retry-After'))
  throw new ApiProblem(res.status, title, detail, code, Number.isFinite(retry) && retry > 0 ? retry : undefined)
}
