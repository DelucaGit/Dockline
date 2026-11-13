import { ApiProblem, problemFromResponse, type SourceStatus } from '@/api/github'
import type { components } from '@/api/schema'

async function send(baseUrl: string, path: string, method: string, body?: unknown): Promise<Response> {
  const res = await fetch(`${baseUrl}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (res.ok) return res
  let title: string | undefined
  let detail: string | undefined
  try {
    const problem = (await res.json()) as components['schemas']['ProblemDetail']
    title = problem.title
    detail = problem.detail
  } catch {
    // not JSON: fall through with just the status
  }
  throw new ApiProblem(res.status, title, detail)
}

/** The backend checks the token with Hostinger first; nothing is saved on any error. */
export async function saveHostingerToken(baseUrl: string, token: string): Promise<SourceStatus> {
  return (await (await send(baseUrl, '/api/hostinger/token', 'PUT', { token })).json()) as SourceStatus
}

export async function removeHostingerToken(baseUrl: string): Promise<void> {
  await send(baseUrl, '/api/hostinger/token', 'DELETE')
}

/** Ask Hostinger about the saved token right now (the backend otherwise caches the answer for a minute). */
export async function recheckHostinger(baseUrl: string): Promise<void> {
  await send(baseUrl, '/api/hostinger/recheck', 'POST')
}

export type HostingerSites = components['schemas']['HostingerSites']
export type HostingerSite = components['schemas']['HostingerSite']

export const sitesKey = (baseUrl: string) => ['hostinger-sites', baseUrl] as const

/** `refresh` skips the backend's 60-second copy and asks Hostinger again (Refresh button); opening the view reuses it. */
export async function fetchSites(baseUrl: string, refresh = false): Promise<HostingerSites> {
  const res = await fetch(`${baseUrl}/api/hostinger/sites${refresh ? '?refresh=true' : ''}`)
  if (res.ok) return (await res.json()) as HostingerSites
  throw await problemFromResponse(res)
}
