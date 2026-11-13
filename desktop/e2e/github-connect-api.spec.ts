import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubTokenPending,
  stubTokenSuccess,
  stubUser
} from './support/githubStubs'

/** Call the backend from the renderer, as the UI will. */
async function api(window: Page, method: string, path: string): Promise<{ status: number; body: string }> {
  return window.evaluate(
    async ({ method, path }) => {
      const state = await (globalThis as unknown as Window).dockline.getBackendState()
      if (state.status !== 'ready') throw new Error(`Backend not ready: ${JSON.stringify(state)}`)
      const res = await fetch(`${state.baseUrl}${path}`, { method })
      return { status: res.status, body: await res.text() }
    },
    { method, path }
  )
}

// The Connect UI comes in a later task; this proves the device-flow API works from the renderer
// against the fake GitHub, and that the token never reaches the renderer.
test('the renderer can connect to (fake) GitHub with the device flow and disconnect', async ({
  wiremock,
  launchApp
}) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenPending(wiremock)
  const { window } = await launchApp(githubEnv(wiremock))
  await expect(window.getByText('Backend connected')).toBeVisible()
  const seen: string[] = []

  const started = await api(window, 'POST', '/api/github/connect')
  seen.push(started.body)
  expect(started.status).toBe(200)
  expect(JSON.parse(started.body)).toEqual({
    userCode: FAKE_GITHUB.userCode,
    verificationUri: FAKE_GITHUB.verificationUri,
    expiresInSeconds: 900,
    intervalSeconds: 1
  })

  const pending = await api(window, 'POST', '/api/github/connect/poll')
  seen.push(pending.body)
  expect(JSON.parse(pending.body)).toMatchObject({ state: 'PENDING', intervalSeconds: 1 })

  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await expect
    .poll(
      async () => {
        const res = await api(window, 'POST', '/api/github/connect/poll')
        seen.push(res.body)
        return (JSON.parse(res.body) as { state: string }).state
      },
      { intervals: [500], timeout: 10_000 }
    )
    .toBe('CONNECTED')

  const status = await api(window, 'GET', '/api/status')
  seen.push(status.body)
  expect(JSON.parse(status.body).sources[0]).toMatchObject({
    source: 'GITHUB',
    state: 'CONNECTED',
    message: `Connected as ${FAKE_GITHUB.login}`
  })

  expect((await api(window, 'POST', '/api/github/disconnect')).status).toBe(204)
  const after = await api(window, 'GET', '/api/status')
  seen.push(after.body)
  expect(JSON.parse(after.body).sources[0]).toMatchObject({ source: 'GITHUB', state: 'NOT_CONNECTED' })

  seen.push((await api(window, 'GET', '/api/log')).body)
  for (const body of seen) {
    expect(body).not.toContain(FAKE_GITHUB.accessToken)
    expect(body).not.toContain(FAKE_GITHUB.deviceCode)
  }
})
