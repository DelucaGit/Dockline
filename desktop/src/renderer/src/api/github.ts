import type { components } from '@/api/schema'

export type SourceStatus = components['schemas']['SourceStatus']
export type ConnectionState = components['schemas']['ConnectionState']
export type StatusResponse = components['schemas']['StatusResponse']
export type GithubDeviceCode = components['schemas']['GithubDeviceCode']
export type GithubConnectProgress = components['schemas']['GithubConnectProgress']

/** A problem+json answer from the backend (e.g. "GitHub is not set up"). */
export class ApiProblem extends Error {
  constructor(
    readonly status: number,
    readonly title: string | undefined,
    readonly detail: string | undefined
  ) {
    super(detail ?? title ?? `Request failed with ${status}`)
  }
}

async function post(baseUrl: string, path: string): Promise<Response> {
  const res = await fetch(`${baseUrl}${path}`, { method: 'POST' })
  if (res.ok) return res
  let title: string | undefined
  let detail: string | undefined
  try {
    const body = (await res.json()) as components['schemas']['ProblemDetail']
    title = body.title
    detail = body.detail
  } catch {
    // not JSON: fall through with just the status
  }
  throw new ApiProblem(res.status, title, detail)
}

export async function fetchStatus(baseUrl: string): Promise<StatusResponse> {
  const res = await fetch(`${baseUrl}/api/status`)
  if (!res.ok) throw new Error(`GET /api/status failed with ${res.status}`)
  return (await res.json()) as StatusResponse
}

export const statusKey = (baseUrl: string) => ['status', baseUrl] as const

export async function startGithubConnect(baseUrl: string): Promise<GithubDeviceCode> {
  return (await (await post(baseUrl, '/api/github/connect')).json()) as GithubDeviceCode
}

export async function pollGithubConnect(baseUrl: string): Promise<GithubConnectProgress> {
  return (await (await post(baseUrl, '/api/github/connect/poll')).json()) as GithubConnectProgress
}

export async function disconnectGithub(baseUrl: string): Promise<void> {
  await post(baseUrl, '/api/github/disconnect')
}

/** Ask GitHub about the saved token right now (the backend otherwise caches the answer for a minute). */
export async function recheckGithub(baseUrl: string): Promise<void> {
  await post(baseUrl, '/api/github/recheck')
}
