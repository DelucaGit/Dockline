import type { ElectronApplication, Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  countBuildsRequests,
  FAKE_HOSTINGER,
  hostingerEnv,
  stubBuilds,
  stubBuildsRateLimited,
  stubBuildsServerError,
  stubDomains,
  stubGitNotConnected,
  stubSites,
  stubSubscriptionsOk
} from './support/hostingerStubs'
import type { WireMock } from './support/wiremock'

/*
 * Deploy status, end to end. The E2E build refreshes every 2 s (desktop/.env.e2e, `--mode e2e`); production
 * keeps 30 s. The backend saves Hostinger's builds for 20 s, so a change in the fake shows up within about
 * 22 s, and "no builds requests for 24 s" proves the app stopped asking: had it kept asking every 2 s, the
 * saved copy would have run out and the backend would have asked Hostinger again.
 */

const NODE = 'node-app.com'
const WP = 'blog.com'
/** Longer than the backend's 20 s builds cache plus one 2 s refresh. */
const CACHE_RUN_OUT_MS = 24_000
/** Enough for the saved copy to run out and the next automatic refresh to arrive. */
const PICKED_UP = { timeout: 35_000 }

async function connectHostinger(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByLabel('API token').fill(FAKE_HOSTINGER.token)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
}

const openSites = (window: Page) => window.getByRole('link', { name: 'Sites', exact: true }).click()
const row = (window: Page, domain: string) => window.getByTestId('site-row').filter({ hasText: domain })
const badge = (window: Page, domain: string) => row(window, domain).getByTestId('deploy-badge')

/*
 * The app refreshes only while its page is visible and focused (useForeground). Playwright emulates focus and
 * visibility for the pages it drives: document.hasFocus() is always true and visibilityState stays "visible",
 * even after BrowserWindow.minimize() or hide() (checked on Windows), and no blur or visibilitychange event
 * reaches the page. So the window really is minimised and restored, and the page is also given the events a
 * real minimise sends (visibilityState "hidden", visibilitychange and blur), and the reverse on return.
 */
async function bringToFront(app: ElectronApplication, window: Page) {
  await app.evaluate(({ BrowserWindow }) => {
    const win = BrowserWindow.getAllWindows()[0]
    if (win.isMinimized()) win.restore()
    win.show()
    win.focus()
  })
  await window.evaluate(() => {
    // Drop the "hidden" set by minimise(), if any; the page then reads the real (emulated) value again.
    delete (document as { visibilityState?: unknown }).visibilityState
    document.dispatchEvent(new Event('visibilitychange'))
    globalThis.dispatchEvent(new Event('focus'))
  })
  await expect
    .poll(() => window.evaluate(() => document.visibilityState === 'visible' && document.hasFocus()), { timeout: 10_000 })
    .toBe(true)
}

async function minimise(app: ElectronApplication, window: Page) {
  await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].minimize())
  await window.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    globalThis.dispatchEvent(new Event('blur'))
  })
}

/** Starts the app with the given websites, connects Hostinger and opens the Sites list in the foreground. */
async function start(wiremock: WireMock, launchApp: (env?: Record<string, string>) => Promise<{ app: ElectronApplication; window: Page }>) {
  await stubSubscriptionsOk(wiremock)
  const { app, window } = await launchApp(hostingerEnv(wiremock))
  await bringToFront(app, window)
  await connectHostinger(window)
  await openSites(window)
  return { app, window }
}

test('a running deploy turns into "Succeeded" by itself, in the list and in the detail view', async ({ wiremock, launchApp }) => {
  await stubSites(wiremock, [{ domain: NODE, websiteType: 'nodejs' }])
  await stubBuilds(wiremock, NODE, [{ state: 'running' }])
  await stubGitNotConnected(wiremock, NODE)
  const { window } = await start(wiremock, launchApp)

  await expect(badge(window, NODE)).toHaveText('In progress')

  // The deploy finishes; nobody clicks anything.
  await stubBuilds(wiremock, NODE, [{ state: 'completed', commit: 'f00dbabe1234567890abcdef1234567890abcdef' }])
  await expect(badge(window, NODE)).toHaveText('Succeeded', PICKED_UP)
  await expect(row(window, NODE).getByTestId('deploy-commit')).toHaveText('f00dbab')

  await window.getByRole('link', { name: NODE, exact: true }).click()
  const card = window.getByTestId('site-deploy')
  await expect(card).toContainText('Latest deploy')
  await expect(card.getByTestId('deploy-badge')).toHaveText('Succeeded')
  await expect(card.getByTestId('deploy-commit')).toHaveText('f00dbab')
  await expect(card.getByTestId('deploy-fetched-at')).toBeVisible()
})

test('refreshing stops while the window is minimised and starts again when it is back', async ({ wiremock, launchApp }) => {
  await stubSites(wiremock, [{ domain: NODE, websiteType: 'nodejs' }])
  await stubBuilds(wiremock, NODE, [{ state: 'completed' }])
  const { app, window } = await start(wiremock, launchApp)
  await expect(badge(window, NODE)).toHaveText('Succeeded')

  await minimise(app, window)
  await window.waitForTimeout(1000) // a refresh that was already on its way
  const before = await countBuildsRequests(wiremock, NODE)
  await window.waitForTimeout(CACHE_RUN_OUT_MS)
  expect(await countBuildsRequests(wiremock, NODE)).toBe(before)

  await bringToFront(app, window)
  await expect.poll(() => countBuildsRequests(wiremock, NODE), { timeout: 10_000 }).toBeGreaterThan(before)
})

test('refreshing stops while another view is open and starts again on return', async ({ wiremock, launchApp }) => {
  await stubSites(wiremock, [{ domain: NODE, websiteType: 'nodejs' }])
  await stubBuilds(wiremock, NODE, [{ state: 'completed' }])
  await stubDomains(wiremock, [{ domain: NODE }])
  const { window } = await start(wiremock, launchApp)
  await expect(badge(window, NODE)).toHaveText('Succeeded')

  await window.getByRole('link', { name: 'Domains', exact: true }).click()
  await expect(window.getByTestId('domain-row')).toHaveCount(1)
  await window.waitForTimeout(1000)
  const before = await countBuildsRequests(wiremock, NODE)
  await window.waitForTimeout(CACHE_RUN_OUT_MS)
  expect(await countBuildsRequests(wiremock, NODE)).toBe(before)

  await openSites(window)
  await expect.poll(() => countBuildsRequests(wiremock, NODE), { timeout: 10_000 }).toBeGreaterThan(before)
})

test('a failed deploy shows "Failed"; a site that is not Node.js shows "No deploy information"', async ({ wiremock, launchApp }) => {
  await stubSites(wiremock, [
    { domain: NODE, websiteType: 'nodejs' },
    { domain: WP, websiteType: 'wordpress' }
  ])
  await stubBuilds(wiremock, NODE, [{ state: 'failed' }])
  const { window } = await start(wiremock, launchApp)

  await expect(badge(window, NODE)).toHaveText('Failed')
  await expect(row(window, WP).getByTestId('deploy-status')).toHaveText('No deploy information')
  await expect(badge(window, WP)).toHaveCount(0)
  // Hostinger is never asked about a WordPress site's builds.
  expect(await countBuildsRequests(wiremock, WP)).toBe(0)
})

test('a 500 from the builds endpoint keeps the last status, says why, and the rest of the app works', async ({ wiremock, launchApp }) => {
  await stubSites(wiremock, [{ domain: NODE, websiteType: 'nodejs' }])
  await stubBuilds(wiremock, NODE, [{ state: 'completed' }])
  await stubDomains(wiremock, [{ domain: NODE }])
  const { window } = await start(wiremock, launchApp)
  await expect(badge(window, NODE)).toHaveText('Succeeded')

  await stubBuildsServerError(wiremock, NODE)
  const trouble = window.getByTestId('deploy-trouble')
  await expect(trouble).toContainText('Deploy status could not be updated.', PICKED_UP)
  await expect(badge(window, NODE)).toHaveText('Succeeded')

  await window.getByRole('link', { name: 'Domains', exact: true }).click()
  await expect(window.getByTestId('domain-row')).toHaveCount(1)
})

test('a rate limit on the builds endpoint shows the retry message', async ({ wiremock, launchApp }) => {
  await stubSites(wiremock, [{ domain: NODE, websiteType: 'nodejs' }])
  // Longer than the backend waits inside a request (10 s), so the app gets the rate limit at once.
  await stubBuildsRateLimited(wiremock, NODE, 60)
  const { window } = await start(wiremock, launchApp)

  await expect(window.getByTestId('deploy-trouble')).toHaveText(/Deploy status: rate limited, retrying in \d+ s/)
})
