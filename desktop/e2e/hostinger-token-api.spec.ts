import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import { FAKE_HOSTINGER, hostingerEnv, stubSubscriptionsOk, stubUnauthorized } from './support/hostingerStubs'

/** Call the backend from the renderer, as the UI will. */
async function api(
  window: Page,
  method: string,
  path: string,
  json?: unknown
): Promise<{ status: number; body: string }> {
  return window.evaluate(
    async ({ method, path, json }) => {
      const state = await (globalThis as unknown as Window).dockline.getBackendState()
      if (state.status !== 'ready') throw new Error(`Backend not ready: ${JSON.stringify(state)}`)
      const res = await fetch(`${state.baseUrl}${path}`, {
        method,
        headers: json === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: json === undefined ? undefined : JSON.stringify(json)
      })
      return { status: res.status, body: await res.text() }
    },
    { method, path, json }
  )
}

// The Hostinger token UI comes in a later task; this proves the token API works from the renderer
// against the fake Hostinger, and that the token never comes back to the renderer.
test('the renderer can save a Hostinger token (checked first), see a rejected one refused, and remove it', async ({
  wiremock,
  launchApp
}) => {
  await stubUnauthorized(wiremock)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await expect(window.getByText('Backend connected')).toBeVisible()
  const seen: string[] = []
  const hostinger = async (): Promise<unknown> => {
    const res = await api(window, 'GET', '/api/status')
    seen.push(res.body)
    return JSON.parse(res.body).sources[1]
  }

  const rejected = await api(window, 'PUT', '/api/hostinger/token', { token: FAKE_HOSTINGER.otherToken })
  seen.push(rejected.body)
  expect(rejected.status).toBe(422)
  expect(JSON.parse(rejected.body).detail).toContain('Hostinger rejected this token')
  expect(await hostinger()).toMatchObject({ source: 'HOSTINGER', state: 'NOT_CONNECTED' })

  await stubSubscriptionsOk(wiremock)
  const saved = await api(window, 'PUT', '/api/hostinger/token', { token: FAKE_HOSTINGER.token })
  seen.push(saved.body)
  expect(saved.status).toBe(200)
  expect(JSON.parse(saved.body)).toMatchObject({ source: 'HOSTINGER', state: 'CONNECTED' })
  expect(await hostinger()).toMatchObject({ source: 'HOSTINGER', state: 'CONNECTED' })

  await stubUnauthorized(wiremock)
  const recheck = await api(window, 'POST', '/api/hostinger/recheck')
  seen.push(recheck.body)
  expect(JSON.parse(recheck.body)).toMatchObject({ source: 'HOSTINGER', state: 'ERROR' })

  expect((await api(window, 'DELETE', '/api/hostinger/token')).status).toBe(204)
  expect(await hostinger()).toMatchObject({ source: 'HOSTINGER', state: 'NOT_CONNECTED' })

  seen.push((await api(window, 'GET', '/api/log?source=HOSTINGER')).body)
  for (const body of seen) {
    expect(body).not.toContain(FAKE_HOSTINGER.token)
    expect(body).not.toContain(FAKE_HOSTINGER.otherToken)
  }
})
