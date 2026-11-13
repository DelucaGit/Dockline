import { expect, test } from './support/fixtures'

// The Log screen comes in a later task; this checks that the renderer itself can use the log API
// (GET, and DELETE, which needs a CORS preflight) and that tokens come back masked.
test('the renderer can read and clear the backend log, with tokens masked', async ({ launchApp }) => {
  const { window } = await launchApp({ DOCKLINE_TEST_ENDPOINTS: 'true' })
  await expect(window.getByText('Backend connected')).toBeVisible()

  const result = await window.evaluate(async () => {
    const state = await (globalThis as unknown as Window).dockline.getBackendState()
    if (state.status !== 'ready') throw new Error(`Backend not ready: ${JSON.stringify(state)}`)
    const base = state.baseUrl
    const reported = await fetch(`${base}/api/test/report-error?source=HOSTINGER&level=WARN`, { method: 'POST' })
    const listed = await fetch(`${base}/api/log?source=HOSTINGER`)
    const listedBody = await listed.text()
    const cleared = await fetch(`${base}/api/log`, { method: 'DELETE' })
    const after = await (await fetch(`${base}/api/log`)).json()
    return {
      reported: reported.status,
      listed: listed.status,
      listedBody,
      cleared: cleared.status,
      afterCount: (after as unknown[]).length
    }
  })

  expect(result.reported).toBe(200)
  expect(result.listed).toBe(200)
  const entries = JSON.parse(result.listedBody) as Array<{ source: string; level: string }>
  expect(entries).toHaveLength(1)
  expect(entries[0]).toMatchObject({ source: 'HOSTINGER', level: 'WARN' })
  expect(result.listedBody).toContain('ghp_****')
  expect(result.listedBody).not.toMatch(/ghp_[A-Za-z0-9]{16,}/)
  expect(result.cleared).toBe(204)
  expect(result.afterCount).toBe(0)
})
