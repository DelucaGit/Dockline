import { expect, test } from './support/fixtures'
import type { Page } from '@playwright/test'

const FAKE_TOKEN = 'ghp_0123456789abcdefTESTtokenNOTreal1234'

async function report(window: Page, query: string) {
  const status = await window.evaluate(async (q) => {
    const state = await (globalThis as unknown as Window).dockline.getBackendState()
    if (state.status !== 'ready') throw new Error('Backend not ready')
    return (await fetch(`${state.baseUrl}/api/test/report-error?${q}`, { method: 'POST' })).status
  }, query)
  expect(status).toBe(200)
}

test('an empty log shows "No errors"', async ({ launchApp }) => {
  const { window } = await launchApp({ DOCKLINE_TEST_ENDPOINTS: 'true' })
  await expect(window.getByText('Backend connected')).toBeVisible()

  await window.getByRole('link', { name: 'Log' }).click()

  await expect(window.getByText('No errors', { exact: true })).toBeVisible()
  await expect(window.getByTestId('log-entry')).toHaveCount(0)
})

test('errors reported by the backend show in the Log, can be filtered, copied for AI and cleared', async ({
  launchApp
}) => {
  const { window } = await launchApp({ DOCKLINE_TEST_ENDPOINTS: 'true' })
  await expect(window.getByText('Backend connected')).toBeVisible()

  await report(window, 'source=GITHUB&level=ERROR&action=GET%20/user&statusCode=401&message=First%20failure')
  await report(window, 'source=HOSTINGER&level=WARN&action=GET%20/domains&statusCode=429&message=Second%20warning')
  await report(window, 'source=BACKEND&level=ERROR&action=startup&message=Third%20problem')

  await window.getByRole('link', { name: 'Log' }).click()

  // Newest first.
  const entries = window.getByTestId('log-entry')
  await expect(entries).toHaveCount(3)
  await expect(entries.nth(0)).toContainText('Third problem')
  await expect(entries.nth(1)).toContainText('Second warning')
  await expect(entries.nth(2)).toContainText('First failure')
  await expect(entries.nth(2)).toContainText('GET /user')
  await expect(entries.nth(2)).toContainText('401')

  // Filters.
  await window.getByLabel('Source').selectOption('HOSTINGER')
  await expect(entries).toHaveCount(1)
  await expect(entries.first()).toContainText('Second warning')
  await window.getByLabel('Source').selectOption('')
  await window.getByLabel('Level').selectOption('ERROR')
  await expect(entries).toHaveCount(2)
  await expect(window.getByText('Second warning')).toHaveCount(0)
  await window.getByLabel('Level').selectOption('')
  await expect(entries).toHaveCount(3)

  // Details are expandable and already masked by the backend.
  const first = entries.nth(2)
  await first.getByText('First failure').click()
  await expect(first).toContainText('ghp_****')
  await expect(first).not.toContainText(FAKE_TOKEN)

  // Copy for AI.
  await first.getByRole('button', { name: 'Copy for AI' }).click()
  await expect(first.getByRole('button', { name: 'Copied' })).toBeVisible()
  const clipboard = await window.evaluate(() => navigator.clipboard.readText())
  expect(clipboard).toContain('App version:')
  expect(clipboard).toContain('Source: GITHUB')
  expect(clipboard).toContain('Action: GET /user')
  expect(clipboard).toContain('Status code: 401')
  expect(clipboard).toContain('Message: First failure')
  expect(clipboard).toContain('Request:')
  expect(clipboard).toContain('Response:')
  expect(clipboard).toContain('Stack trace:')
  expect(clipboard).toContain('ghp_****')
  expect(clipboard).not.toContain(FAKE_TOKEN)
  expect(clipboard).not.toMatch(/ghp_[A-Za-z0-9]{16,}/)

  // Clear.
  await window.getByRole('button', { name: 'Clear' }).click()
  await expect(window.getByText('No errors', { exact: true })).toBeVisible()
  await expect(entries).toHaveCount(0)
})
