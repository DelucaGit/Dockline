import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { ElectronApplication, Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubRepos,
  stubRepository,
  stubRepositoryNotFound,
  stubTokenSuccess,
  stubUser
} from './support/githubStubs'
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubGitAutoDeployment,
  stubGitInstallations,
  stubGitNotConnected,
  stubGitServerError,
  stubSites,
  stubSitesServerError,
  stubSubscriptionsOk
} from './support/hostingerStubs'
import type { WireMock } from './support/wiremock'

// The whole site–repo slice, from the fake Hostinger and GitHub (WireMock) to the screen:
// shop.example auto-deploys octocat/shop through Hostinger; plain.example is not Git-connected and gets a manual link.
const SHOP = 'shop.example'
const PLAIN = 'plain.example'
const SHOP_REPO = 'octocat/shop'
const ALPHA = `${FAKE_GITHUB.login}/alpha`
const BRAVO = `${FAKE_GITHUB.login}/bravo`

/** Hostinger: two websites, one of them Git-connected. */
async function stubHostinger(wiremock: WireMock) {
  await stubSubscriptionsOk(wiremock)
  await stubSites(wiremock, [{ domain: SHOP }, { domain: PLAIN }])
  await stubGitAutoDeployment(wiremock, SHOP, { owner: 'octocat', repository: 'shop' })
  await stubGitInstallations(wiremock, 'github')
  await stubGitNotConnected(wiremock, PLAIN)
}

/** GitHub: the device flow succeeds; the user has two repositories, and every repo used here exists. */
async function stubGithub(wiremock: WireMock) {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await stubRepos(wiremock, [{ name: 'alpha' }, { name: 'bravo' }])
  for (const repo of [SHOP_REPO, ALPHA, BRAVO]) await stubRepository(wiremock, repo)
}

async function connectHostinger(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByLabel('API token').fill(FAKE_HOSTINGER.token)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
}

async function connectGithub(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
}

const openSites = (window: Page) => window.getByRole('link', { name: 'Sites', exact: true }).click()

const row = (window: Page, domain: string) => window.getByTestId('site-row').filter({ hasText: domain })

/** Sites list, then the site's detail view. */
async function openSite(window: Page, domain: string) {
  await openSites(window)
  await window.getByRole('link', { name: domain, exact: true }).click()
  await expect(window.getByRole('heading', { name: domain, level: 1 })).toBeVisible()
}

/** Link repo (or Change), then pick a repository in the picker; picking saves at once. */
async function pickRepo(window: Page, button: 'Link repo' | 'Change', repo: string) {
  await window.getByRole('button', { name: button, exact: true }).click()
  await window.getByTestId('repo-picker').getByRole('button', { name: repo }).click()
  await expect(window.getByTestId('repo-picker')).toHaveCount(0)
  await expect(window.getByTestId('site-repo')).toHaveText(repo)
}

/** The app is started with DOCKLINE_E2E_NO_BROWSER=1 (see fixtures.ts), so "open the browser" is only recorded. */
const lastOpenedUrl = (app: ElectronApplication) =>
  app.evaluate(() => (globalThis as { __docklineOpenedUrls?: string[] }).__docklineOpenedUrls?.at(-1))

const bothEnv = (wiremock: WireMock) => ({ ...githubEnv(wiremock), ...hostingerEnv(wiremock) })

test('a Git-connected site shows its repo by itself; a manual link is created, changed and removed on another site', async ({
  wiremock,
  launchApp
}) => {
  await stubHostinger(wiremock)
  await stubGithub(wiremock)
  const { app, window } = await launchApp(bothEnv(wiremock))
  await connectGithub(window)
  await connectHostinger(window)

  // The Sites list already knows the repo of the Git-connected site; the other site has none.
  await openSites(window)
  await expect(window.getByTestId('site-row')).toHaveCount(2)
  await expect(row(window, SHOP).getByTestId('site-row-repo')).toHaveText(SHOP_REPO)
  await expect(row(window, PLAIN).getByTestId('site-row-repo')).toHaveCount(0)

  // Git-connected in Hostinger: shown without any setup, and it cannot be overridden.
  await window.getByRole('link', { name: SHOP, exact: true }).click()
  await expect(window.getByRole('heading', { name: SHOP, level: 1 })).toBeVisible()
  await expect(window.getByTestId('site-repo')).toHaveText(SHOP_REPO)
  await expect(window.getByTestId('site-repo-source')).toHaveText('from Hostinger')
  await expect(window.getByTestId('repo-warning')).toHaveCount(0)
  await expect(window.getByTestId('site-problem-hostinger')).toHaveCount(0)
  await expect(window.getByTestId('site-problem-github')).toHaveCount(0)
  for (const name of ['Link repo', 'Change', 'Remove']) {
    await expect(window.getByRole('button', { name, exact: true })).toHaveCount(0)
  }
  await window.getByRole('button', { name: SHOP_REPO }).click()
  await expect.poll(() => lastOpenedUrl(app)).toBe(`https://github.com/${SHOP_REPO}`)
  expect(window.url()).not.toContain('github.com')

  // The other site: no repo yet. Link one, using the filter.
  await window.getByRole('link', { name: 'Back to Sites' }).click()
  await window.getByRole('link', { name: PLAIN, exact: true }).click()
  await expect(window.getByRole('heading', { name: PLAIN, level: 1 })).toBeVisible()
  await expect(window.getByText('No repo linked')).toBeVisible()
  await expect(window.getByTestId('site-repo')).toHaveCount(0)
  await window.getByRole('button', { name: 'Link repo', exact: true }).click()
  const picker = window.getByTestId('repo-picker')
  await expect(picker.getByTestId('repo-option')).toHaveCount(2)
  await picker.getByRole('searchbox', { name: 'Filter repositories' }).fill('BRA')
  await expect(picker.getByTestId('repo-option')).toHaveCount(1)
  await picker.getByRole('button', { name: BRAVO }).click()
  await expect(picker).toHaveCount(0)
  await expect(window.getByTestId('site-repo')).toHaveText(BRAVO)
  await expect(window.getByTestId('site-repo-source')).toHaveText('linked manually')
  await expect(window.getByText('No repo linked')).toHaveCount(0)
  await expect(window.getByTestId('repo-warning')).toHaveCount(0)

  // The manual link shows in the Sites list and opens GitHub from there, without opening the site.
  await window.getByRole('link', { name: 'Back to Sites' }).click()
  await expect(row(window, PLAIN).getByTestId('site-row-repo')).toHaveText(BRAVO)
  await expect(row(window, SHOP).getByTestId('site-row-repo')).toHaveText(SHOP_REPO)
  await row(window, PLAIN).getByRole('button', { name: BRAVO }).click()
  await expect.poll(() => lastOpenedUrl(app)).toBe(`https://github.com/${BRAVO}`)
  await expect(window.getByRole('heading', { name: 'Sites', level: 1 })).toBeVisible()

  // Change it.
  await window.getByRole('link', { name: PLAIN, exact: true }).click()
  await expect(window.getByTestId('site-repo')).toHaveText(BRAVO)
  await pickRepo(window, 'Change', ALPHA)
  await expect(window.getByTestId('site-repo-source')).toHaveText('linked manually')
  await window.getByRole('button', { name: ALPHA }).click()
  await expect.poll(() => lastOpenedUrl(app)).toBe(`https://github.com/${ALPHA}`)

  // Remove asks first: "Keep link" changes nothing, "Yes, remove" removes it.
  await window.getByRole('button', { name: 'Remove', exact: true }).click()
  const dialog = window.getByRole('alertdialog')
  await expect(dialog).toContainText(ALPHA)
  await dialog.getByRole('button', { name: 'Keep link' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(window.getByTestId('site-repo')).toHaveText(ALPHA)
  await window.getByRole('button', { name: 'Remove', exact: true }).click()
  await dialog.getByRole('button', { name: 'Yes, remove' }).click()
  await expect(window.getByText('No repo linked')).toBeVisible()
  await expect(window.getByTestId('site-repo')).toHaveCount(0)
  await expect(window.getByRole('button', { name: 'Link repo', exact: true })).toBeVisible()

  await window.getByRole('link', { name: 'Back to Sites' }).click()
  await expect(row(window, SHOP).getByTestId('site-row-repo')).toHaveText(SHOP_REPO)
  await expect(row(window, PLAIN).getByTestId('site-row-repo')).toHaveCount(0)
})

test('a manual link is still there after the app is restarted', async ({ wiremock, launchApp }) => {
  await stubHostinger(wiremock)
  await stubGithub(wiremock)
  // Both launches share one settings folder; everything else (tokens, caches) starts from nothing.
  const settingsDir = mkdtempSync(join(tmpdir(), 'dockline-e2e-restart-'))
  const env = { ...bothEnv(wiremock), DOCKLINE_SETTINGS_DIR: settingsDir }

  const first = await launchApp(env)
  await connectGithub(first.window)
  await connectHostinger(first.window)
  await openSite(first.window, PLAIN)
  await pickRepo(first.window, 'Link repo', BRAVO)
  await first.app.close()

  // Saved in the versioned JSON settings file.
  const saved = JSON.parse(readFileSync(join(settingsDir, 'settings.json'), 'utf8')) as Record<string, unknown>
  expect(saved).toMatchObject({ version: 1, siteRepoLinks: { [PLAIN]: BRAVO } })

  // Tokens live in memory in these tests, so the accounts are connected again after the restart.
  const second = await launchApp(env)
  await connectGithub(second.window)
  await connectHostinger(second.window)
  await openSites(second.window)
  await expect(row(second.window, PLAIN).getByTestId('site-row-repo')).toHaveText(BRAVO)
  await second.window.getByRole('link', { name: PLAIN, exact: true }).click()
  await expect(second.window.getByTestId('site-repo')).toHaveText(BRAVO)
  await expect(second.window.getByTestId('site-repo-source')).toHaveText('linked manually')
  await expect(second.window.getByRole('button', { name: 'Change', exact: true })).toBeVisible()
  await expect(second.window.getByRole('button', { name: 'Remove', exact: true })).toBeVisible()
})

test('a linked repo that no longer exists on GitHub shows a warning instead of failing', async ({ wiremock, launchApp }) => {
  await stubHostinger(wiremock)
  await stubGithub(wiremock)
  const { window } = await launchApp(bothEnv(wiremock))
  await connectGithub(window)
  await connectHostinger(window)
  await openSite(window, PLAIN)
  await pickRepo(window, 'Link repo', ALPHA)
  await expect(window.getByTestId('repo-warning')).toHaveCount(0)

  // The repository is deleted on GitHub.
  await stubRepositoryNotFound(wiremock, ALPHA)
  await window.getByRole('button', { name: 'Refresh' }).click()

  await expect(window.getByTestId('repo-warning')).toBeVisible()
  await expect(window.getByTestId('repo-warning')).toContainText('There is a problem with the linked repo')
  // The link is kept and can still be changed or removed; nothing else broke.
  await expect(window.getByTestId('site-repo')).toHaveText(ALPHA)
  await expect(window.getByTestId('site-repo-source')).toHaveText('linked manually')
  await expect(window.getByRole('button', { name: 'Change', exact: true })).toBeVisible()
  await expect(window.getByRole('button', { name: 'Remove', exact: true })).toBeVisible()
  await expect(window.getByRole('alert')).toHaveCount(0)
  await expect(window.getByTestId('site-problem-github')).toHaveCount(0)

  // The same for a Git-connected site whose repository is gone.
  await stubRepositoryNotFound(wiremock, SHOP_REPO)
  await openSite(window, SHOP)
  await expect(window.getByTestId('site-repo')).toHaveText(SHOP_REPO)
  await expect(window.getByTestId('site-repo-source')).toHaveText('from Hostinger')
  await expect(window.getByTestId('repo-warning')).toBeVisible()

  await openSites(window)
  await expect(row(window, PLAIN).getByTestId('site-row-repo')).toHaveText(ALPHA)
})

test('without GitHub, Link repo explains that GitHub must be connected first and leads to Settings', async ({
  wiremock,
  launchApp
}) => {
  await stubHostinger(wiremock)
  const { window } = await launchApp(bothEnv(wiremock))
  await connectHostinger(window)

  // Hostinger's part works on its own: the Git-connected site still shows its repo.
  await openSite(window, SHOP)
  await expect(window.getByTestId('site-repo')).toHaveText(SHOP_REPO)
  await expect(window.getByTestId('site-repo-source')).toHaveText('from Hostinger')

  await openSite(window, PLAIN)
  await expect(window.getByText('No repo linked')).toBeVisible()
  await window.getByRole('button', { name: 'Link repo', exact: true }).click()
  const notConnected = window.getByTestId('github-not-connected')
  await expect(notConnected).toBeVisible()
  await expect(notConnected).toContainText('GitHub must be connected first')
  await expect(window.getByTestId('repo-option')).toHaveCount(0)

  await notConnected.getByRole('button', { name: 'Open Settings' }).click()
  await expect(window.getByRole('button', { name: 'Connect to GitHub' })).toBeVisible()
})

test('a Hostinger 500 leaves the manual link and the GitHub parts working', async ({ wiremock, launchApp }) => {
  await stubHostinger(wiremock)
  await stubGithub(wiremock)
  const { window } = await launchApp(bothEnv(wiremock))
  await connectGithub(window)
  await connectHostinger(window)
  await openSite(window, PLAIN)
  await pickRepo(window, 'Link repo', BRAVO)
  await expect(window.getByTestId('site-problem-hostinger')).toHaveCount(0)

  // Hostinger's Git endpoints start failing: the manual link stays, with a note about Hostinger.
  await stubGitServerError(wiremock, PLAIN)
  await window.getByRole('button', { name: 'Refresh' }).click()
  const note = window.getByTestId('site-problem-hostinger')
  await expect(note).toBeVisible()
  await expect(note).toContainText('The Hostinger Git connection could not be checked.')
  await expect(window.getByTestId('site-repo')).toHaveText(BRAVO)
  await expect(window.getByTestId('site-repo-source')).toHaveText('linked manually')
  await expect(window.getByTestId('site-problem-github')).toHaveCount(0)
  await expect(window.getByTestId('repo-warning')).toHaveCount(0)

  // Changing the link needs Hostinger (is the site Git-connected?): refused with a message, the old link stays.
  await window.getByRole('button', { name: 'Change', exact: true }).click()
  await window.getByTestId('repo-picker').getByRole('button', { name: ALPHA }).click()
  await expect(window.getByTestId('link-error')).toBeVisible()
  await expect(window.getByTestId('site-repo')).toHaveText(BRAVO)

  // Now the website list fails too.
  await stubSitesServerError(wiremock)
  await openSites(window)
  await window.getByRole('button', { name: 'Refresh' }).click()
  await expect(window.getByRole('alert')).toBeVisible()
  await expect(window.getByRole('button', { name: 'Try again' })).toBeVisible()
  await expect(window.getByTestId('site-row')).toHaveCount(0)

  // GitHub is not affected: the Repos view still lists the repositories.
  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  await expect(window.getByTestId('repo-row')).toHaveCount(2)
  await expect(window.getByRole('button', { name: BRAVO })).toBeVisible()

  await window.getByRole('link', { name: 'Log' }).click()
  await expect(window.getByTestId('log-entry').filter({ hasText: 'HOSTINGER' }).first()).toBeVisible()
})

test('a corrupt settings file does not stop the app: no manual links, and a warning in the Log', async ({
  wiremock,
  launchApp
}) => {
  await stubHostinger(wiremock)
  await stubGithub(wiremock)
  const settingsDir = mkdtempSync(join(tmpdir(), 'dockline-e2e-corrupt-'))
  // Looks like a saved link, but the file is cut off: not JSON.
  writeFileSync(join(settingsDir, 'settings.json'), `{ "version": 1, "siteRepoLinks": { "${PLAIN}": "${BRAVO}"`)

  const { window } = await launchApp({ ...bothEnv(wiremock), DOCKLINE_SETTINGS_DIR: settingsDir })
  await expect(window.getByText('Backend connected')).toBeVisible()
  await connectGithub(window)
  await connectHostinger(window)

  // No manual links; the link from Hostinger is still found.
  await openSites(window)
  await expect(window.getByTestId('site-row')).toHaveCount(2)
  await expect(row(window, SHOP).getByTestId('site-row-repo')).toHaveText(SHOP_REPO)
  await expect(row(window, PLAIN).getByTestId('site-row-repo')).toHaveCount(0)
  await window.getByRole('link', { name: PLAIN, exact: true }).click()
  await expect(window.getByText('No repo linked')).toBeVisible()

  await window.getByRole('link', { name: 'Log' }).click()
  const warning = window.getByTestId('log-entry').filter({ hasText: 'settings file' })
  await expect(warning).toHaveCount(1)
  await expect(warning).toContainText('cannot be used')
  await expect(warning).toContainText('BACKEND')

  // The unusable file was kept aside, and linking works again from a fresh file.
  expect(readdirSync(settingsDir).filter((name) => /^settings\.corrupt-.*\.json$/.test(name))).toHaveLength(1)
  await openSite(window, PLAIN)
  await pickRepo(window, 'Link repo', BRAVO)
  const saved = JSON.parse(readFileSync(join(settingsDir, 'settings.json'), 'utf8')) as Record<string, unknown>
  expect(saved).toMatchObject({ version: 1, siteRepoLinks: { [PLAIN]: BRAVO } })
})
