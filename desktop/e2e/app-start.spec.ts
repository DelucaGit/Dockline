import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, test } from './support/fixtures'

test('the app starts its backend, shows "Backend connected" and stops the backend on close', async ({
  launchApp
}) => {
  const { app, window } = await launchApp()

  await expect(window.getByText('Backend connected')).toBeVisible()
  await expect(window.getByRole('navigation', { name: 'Main menu' })).toBeVisible()

  // Remember where the backend listens, then close the app like a user would.
  const state = await window.evaluate(() => (globalThis as unknown as Window).dockline.getBackendState())
  if (state.status !== 'ready') throw new Error(`Backend not ready: ${JSON.stringify(state)}`)
  const healthUrl = `${state.baseUrl}/actuator/health`
  expect((await fetch(healthUrl)).ok).toBe(true)

  await app.close()

  // No backend is left running: its port no longer answers.
  await expect
    .poll(
      async () => {
        try {
          await fetch(healthUrl, { signal: AbortSignal.timeout(1000) })
          return 'still running'
        } catch {
          return 'stopped'
        }
      },
      { timeout: 10_000 }
    )
    .toBe('stopped')
})

test('a backend that cannot start shows a readable error instead of an endless loading screen', async ({
  launchApp
}) => {
  const missingJava = join(tmpdir(), 'dockline-no-such-java', 'java.exe')
  const { window } = await launchApp({ DOCKLINE_JAVA: missingJava })

  const alert = window.getByRole('alert')
  await expect(alert).toContainText('The backend could not be started')
  await expect(alert).toContainText('Java was not found')
  await expect(window.getByRole('button', { name: 'Retry' })).toBeVisible()
  await expect(window.getByText('Backend connected')).toHaveCount(0)
})
