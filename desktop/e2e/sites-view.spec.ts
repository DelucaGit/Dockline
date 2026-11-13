import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubSites,
  stubSitesRateLimited,
  stubSitesRateLimitedOnce,
  stubSitesServerError,
  stubSubscriptionsOk
} from './support/hostingerStubs'
import { FAKE_GITHUB, githubEnv, stubDeviceCode, stubRepos, stubTokenSuccess, stubUser } from './support/githubStubs'

const dot = (window: Page, source: 'github' | 'hostinger') =>
  window.getByTestId(`connection-${source}`).locator('span[aria-hidden]')

async function connectHostinger(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByLabel('API token').fill(FAKE_HOSTINGER.token)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
}

const openSites = (window: Page) => window.getByRole('link', { name: 'Sites', exact: true }).click()

test('the list shows every website; Refresh picks up a new one and changes "Last updated"', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  await stubSites(wiremock, [{ domain: 'alpha.com' }, { domain: 'bravo.com', enabled: false }, { domain: null }], { pages: 2 })
  const { window } = await launchApp(hostingerEnv(wiremock))
  await connectHostinger(window)
  await openSites(window)

  const rows = window.getByTestId('site-row')
  await expect(rows).toHaveCount(3)
  await expect(rows.filter({ hasText: 'bravo.com' })).toContainText('Suspended')
  await expect(rows.filter({ hasText: '(no domain)' })).toBeVisible()
  const label = window.getByText(/Last updated/)
  const before = await label.textContent()

  await stubSites(wiremock, [{ domain: 'alpha.com' }, { domain: 'newcomer.com' }])
  await window.waitForTimeout(1100) // "Last updated" shows seconds
  await window.getByRole('button', { name: 'Refresh' }).click()
  await expect(window.getByText('newcomer.com')).toBeVisible()
  await expect(rows).toHaveCount(2)
  await expect(label).not.toHaveText(before ?? '')
})

test('not connected links to Settings; an empty plan says "No websites"', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  await stubSites(wiremock, [])
  const { window } = await launchApp(hostingerEnv(wiremock))
  await openSites(window)
  await expect(window.getByText('Hostinger is not connected')).toBeVisible()
  await window.getByRole('button', { name: 'Open Settings' }).click()
  await expect(window.getByLabel('API token')).toBeVisible()

  await connectHostinger(window)
  await openSites(window)
  await expect(window.getByText('No websites')).toBeVisible()
})

test('a short 429 is waited out and the list still appears', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  await stubSites(wiremock, [{ domain: 'alpha.com' }])
  await stubSitesRateLimitedOnce(wiremock, 1)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await connectHostinger(window)
  await openSites(window)
  await expect(window.getByTestId('site-row')).toHaveCount(1)
})

test('a long 429 shows "Rate limited, retrying in X s" with a yellow dot, then the list', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  await stubSitesRateLimited(wiremock, 25)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await connectHostinger(window)
  await openSites(window)

  const message = window.getByText(/Rate limited, retrying in \d+ s/)
  await expect(message).toBeVisible()
  await expect(dot(window, 'hostinger')).toHaveClass(/bg-yellow-500/)
  const seconds = async () => Number((await message.textContent())?.match(/(\d+) s/)?.[1])
  const first = await seconds()
  expect(first).toBeLessThanOrEqual(25)
  await expect.poll(seconds).toBeLessThan(first)

  // Hostinger recovers; when the wait is over the view asks again by itself.
  await stubSites(wiremock, [{ domain: 'alpha.com' }])
  await expect(window.getByTestId('site-row')).toHaveCount(1, { timeout: 40_000 })
  await expect(message).toHaveCount(0)
})

test('a Hostinger 500 shows Try again and a Log entry while GitHub still works', async ({ wiremock, launchApp }) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await stubRepos(wiremock, [{ name: 'alpha', pushedAt: new Date().toISOString() }])
  await stubSubscriptionsOk(wiremock)
  await stubSitesServerError(wiremock)
  const { window } = await launchApp({ ...githubEnv(wiremock), ...hostingerEnv(wiremock) })
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
  await connectHostinger(window)

  await openSites(window)
  await expect(window.getByRole('alert')).toBeVisible()
  await expect(window.getByRole('button', { name: 'Try again' })).toBeVisible()
  await expect(window.getByTestId('site-row')).toHaveCount(0)
  await expect(dot(window, 'github')).toHaveClass(/bg-green-500/)

  await window.getByRole('link', { name: 'Log' }).click()
  await expect(window.getByTestId('log-entry').filter({ hasText: 'HOSTINGER' }).first()).toBeVisible()

  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  await expect(window.getByTestId('repo-row')).toHaveCount(1)

  await stubSites(wiremock, [{ domain: 'alpha.com' }])
  await openSites(window)
  await expect(window.getByTestId('site-row')).toHaveCount(1)
  await expect(window.getByRole('alert')).toHaveCount(0)
})
