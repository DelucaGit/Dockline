import { ApiProblem, type SourceStatus } from '@/api/github'
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
