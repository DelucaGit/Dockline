import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubDomains,
  stubDomainsRateLimited,
  stubDomainsServerError,
  stubDomainsUnauthorized,
  stubSubscriptionsOk
} from './support/hostingerStubs'
import { FAKE_GITHUB, githubEnv, stubDeviceCode, stubRepos, stubTokenSuccess, stubUser } from './support/githubStubs'

const DAY = 24 * 60 * 60 * 1000
/** Hostinger's date format (no milliseconds), relative to now so the test does not age. */
const inDays = (days: number) => new Date(Date.now() + days * DAY).toISOString().replace(/\.\d{3}Z$/, 'Z')

async function connectHostinger(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByLabel('API token').fill(FAKE_HOSTINGER.token)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
}

const openDomains = (window: Page) => window.getByRole('link', { name: 'Domains', exact: true }).click()
const dot = (window: Page, source: 'github' | 'hostinger') =>
  window.getByTestId(`connection-${source}`).locator('span[aria-hidden]')

test('the list shows expiry dates, highlights expiring-soon and expired domains; Refresh picks up changes', async ({
  wiremock,
  launchApp
}) => {
  await stubSubscriptionsOk(wiremock)
  await stubDomains(wiremock, [
    { domain: 'gone.com', expiresAt: inDays(-5) },
    { domain: 'flagged.com', expiresAt: inDays(300), status: 'expired' },
    { domain: 'soon.com', expiresAt: inDays(10) },
    { domain: 'later.com', expiresAt: '2030-06-15T00:00:00Z' },
    { domain: 'undated.com', expiresAt: null },
    { domain: null, expiresAt: null }
  ])
  const { window } = await launchApp(hostingerEnv(wiremock))
  await connectHostinger(window)
  await openDomains(window)

  const rows = window.getByTestId('domain-row')
  await expect(rows).toHaveCount(6)
  const row = (name: string) => rows.filter({ hasText: name })
  await expect(row('gone.com')).toHaveAttribute('data-expiry', 'expired')
  await expect(row('gone.com')).toContainText('Expired')
  await expect(row('flagged.com')).toHaveAttribute('data-expiry', 'expired')
  await expect(row('soon.com')).toHaveAttribute('data-expiry', 'soon')
  await expect(row('soon.com')).toContainText('Expiring soon')
  await expect(row('later.com')).toHaveAttribute('data-expiry', 'ok')
  await expect(row('later.com')).not.toContainText(/Expiring soon|Expired/)
  await expect(row('later.com').locator('time')).toHaveAttribute('datetime', '2030-06-15T00:00:00Z')
  await expect(row('later.com')).toContainText('2030')
  await expect(row('undated.com')).toContainText('No expiry date')
  await expect(row('(no name)')).toBeVisible()

  const label = window.getByText(/Last updated/)
  const before = await label.textContent()
  await stubDomains(wiremock, [{ domain: 'soon.com', expiresAt: inDays(10) }, { domain: 'newcomer.com', expiresAt: inDays(400) }])
  await window.waitForTimeout(1100) // "Last updated" shows seconds
  await window.getByRole('button', { name: 'Refresh' }).click()
  await expect(window.getByText('newcomer.com')).toBeVisible()
  await expect(rows).toHaveCount(2)
  await expect(label).not.toHaveText(before ?? '')
})

test('not connected links to Settings; an empty list says "No domains"', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  await stubDomains(wiremock, [])
  const { window } = await launchApp(hostingerEnv(wiremock))
  await openDomains(window)
  await expect(window.getByText('Hostinger is not connected')).toBeVisible()
  await window.getByRole('button', { name: 'Open Settings' }).click()
  await expect(window.getByLabel('API token')).toBeVisible()

  await connectHostinger(window)
  await openDomains(window)
  await expect(window.getByText('No domains')).toBeVisible()
})

test('a Hostinger 500 keeps the last domains visible under an error, GitHub still works, and the Log has an entry', async ({
  wiremock,
  launchApp
}) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await stubRepos(wiremock, [{ name: 'alpha', pushedAt: new Date().toISOString() }])
  await stubSubscriptionsOk(wiremock)
  await stubDomains(wiremock, [{ domain: 'alpha.com', expiresAt: inDays(200) }])
  const { window } = await launchApp({ ...githubEnv(wiremock), ...hostingerEnv(wiremock) })
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
  await connectHostinger(window)

  await openDomains(window)
  await expect(window.getByTestId('domain-row')).toHaveCount(1)

  await stubDomainsServerError(wiremock)
  await window.getByRole('button', { name: 'Refresh' }).click()
  await expect(window.getByRole('alert')).toBeVisible()
  await expect(window.getByRole('button', { name: 'Try again' })).toBeVisible()
  await expect(window.getByTestId('domain-row')).toHaveCount(1)
  await expect(window.getByText('alpha.com')).toBeVisible()
  await expect(dot(window, 'github')).toHaveClass(/bg-green-500/)

  await window.getByRole('link', { name: 'Log' }).click()
  await expect(window.getByTestId('log-entry').filter({ hasText: 'HOSTINGER' }).first()).toBeVisible()

  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  await expect(window.getByTestId('repo-row')).toHaveCount(1)

  await stubDomains(wiremock, [{ domain: 'alpha.com', expiresAt: inDays(200) }, { domain: 'beta.com', expiresAt: inDays(250) }])
  await openDomains(window) // may show the backend's 60-second copy
  await window.getByRole('button', { name: 'Refresh' }).click()
  await expect(window.getByTestId('domain-row')).toHaveCount(2)
  await expect(window.getByRole('alert')).toHaveCount(0)
})

test('a rejected token offers "Replace token"', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  await stubDomainsUnauthorized(wiremock)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await connectHostinger(window)
  await openDomains(window)
  await expect(window.getByText('Hostinger no longer accepts the saved token.')).toBeVisible()
  await window.getByRole('button', { name: 'Replace token' }).click()
  await expect(window.getByLabel('API token')).toBeVisible()
})

test('a long 429 shows "Rate limited, retrying in X s" with a yellow dot, then the list', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  await stubDomainsRateLimited(wiremock, 25)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await connectHostinger(window)
  await openDomains(window)

  const message = window.getByText(/Rate limited, retrying in \d+ s/)
  await expect(message).toBeVisible()
  await expect(dot(window, 'hostinger')).toHaveClass(/bg-yellow-500/)
  const seconds = async () => Number((await message.textContent())?.match(/(\d+) s/)?.[1])
  const first = await seconds()
  expect(first).toBeLessThanOrEqual(25)
  await expect.poll(seconds).toBeLessThan(first)

  // Hostinger recovers; when the wait is over the view asks again by itself.
  await stubDomains(wiremock, [{ domain: 'alpha.com', expiresAt: inDays(200) }])
  await expect(window.getByTestId('domain-row')).toHaveCount(1, { timeout: 40_000 })
  await expect(message).toHaveCount(0)
})
