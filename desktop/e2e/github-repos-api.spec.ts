import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubRepos,
  stubReposRateLimited,
  stubReposServerError,
  stubReposUnauthorized,
  stubTokenSuccess,
  stubUser
} from './support/githubStubs'

interface Reply {
  status: number
  contentType: string | null
  retryAfter: string | null
  body: string
}

/** Call the backend from the renderer, as the UI will. */
async function api(window: Page, method: string, path: string): Promise<Reply> {
  return window.evaluate(
    async ({ method, path }) => {
      const state = await (globalThis as unknown as Window).dockline.getBackendState()
      if (state.status !== 'ready') throw new Error(`Backend not ready: ${JSON.stringify(state)}`)
      const res = await fetch(`${state.baseUrl}${path}`, { method })
      return {
        status: res.status,
        contentType: res.headers.get('content-type'),
        retryAfter: res.headers.get('retry-after'),
        body: await res.text()
      }
    },
    { method, path }
  )
}

// The Repos view comes in a later task; this proves the repositories API works from the renderer against
// the fake GitHub (several pages, and each kind of error), and that the token never reaches the renderer.
test('the renderer can list (fake) GitHub repositories and gets a clear problem for each error', async ({
  wiremock,
  launchApp
}) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  const { window } = await launchApp(githubEnv(wiremock))
  await expect(window.getByText('Backend connected')).toBeVisible()
  const seen: string[] = []
  const call = async (path: string, method = 'GET'): Promise<Reply> => {
    const reply = await api(window, method, path)
    seen.push(reply.body)
    return reply
  }

  const notConnected = await call('/api/github/repositories')
  expect(notConnected.status).toBe(409)
  expect(notConnected.contentType).toContain('application/problem+json')
  expect(JSON.parse(notConnected.body)).toMatchObject({ code: 'GITHUB_NOT_CONNECTED' })

  await stubTokenSuccess(wiremock)
  await call('/api/github/connect', 'POST')
  await expect
    .poll(async () => (JSON.parse((await call('/api/github/connect/poll', 'POST')).body) as { state: string }).state, {
      intervals: [500],
      timeout: 10_000
    })
    .toBe('CONNECTED')

  await stubRepos(
    wiremock,
    [
      { name: 'older', pushedAt: '2025-05-05T05:05:05Z' },
      { name: 'secret', private: true, pushedAt: '2026-11-01T10:00:00Z' },
      { name: 'empty', pushedAt: null }
    ],
    { pages: 3 }
  )
  const listed = await call('/api/github/repositories')
  expect(listed.status).toBe(200)
  const body = JSON.parse(listed.body) as { repositories: unknown[]; fetchedAt: string; truncated: boolean }
  expect(body.truncated).toBe(false)
  expect(Number.isNaN(Date.parse(body.fetchedAt))).toBe(false)
  expect(body.repositories).toEqual([
    {
      name: 'secret',
      fullName: `${FAKE_GITHUB.login}/secret`,
      visibility: 'PRIVATE',
      lastPushAt: '2026-11-01T10:00:00Z',
      htmlUrl: `https://github.com/${FAKE_GITHUB.login}/secret`,
      fork: false,
      archived: false
    },
    expect.objectContaining({ name: 'older', visibility: 'PUBLIC', lastPushAt: '2025-05-05T05:05:05Z' }),
    expect.objectContaining({ name: 'empty', lastPushAt: null })
  ])

  await stubReposRateLimited(wiremock, 60)
  const limited = await call('/api/github/repositories')
  expect(limited.status).toBe(429)
  expect(limited.retryAfter).toBe('60')
  expect(JSON.parse(limited.body)).toMatchObject({ code: 'GITHUB_RATE_LIMITED' })

  await stubReposServerError(wiremock)
  const failing = await call('/api/github/repositories')
  expect(failing.status).toBe(502)
  expect(JSON.parse(failing.body)).toMatchObject({ code: 'GITHUB_UNAVAILABLE' })

  await stubReposUnauthorized(wiremock)
  const rejected = await call('/api/github/repositories')
  expect(rejected.status).toBe(409)
  expect(JSON.parse(rejected.body)).toMatchObject({ code: 'GITHUB_TOKEN_REJECTED' })
  const status = await call('/api/status')
  expect(JSON.parse(status.body).sources[0]).toMatchObject({ source: 'GITHUB', state: 'ERROR' })

  const log = await call('/api/log?source=GITHUB')
  expect((JSON.parse(log.body) as { action: string }[]).filter((e) => e.action === 'GET /user/repos')).toHaveLength(3)

  for (const text of seen) {
    expect(text).not.toContain(FAKE_GITHUB.accessToken)
  }
})
