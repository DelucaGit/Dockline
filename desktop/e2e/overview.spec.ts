import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubRepos,
  stubReposUnauthorized,
  stubTokenSuccess,
  stubUser,
  stubUserUnauthorized
} from './support/githubStubs'
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubBuilds,
  stubDomains,
  stubDomainsServerError,
  stubGitNotConnected,
  stubSites,
  stubSitesServerError,
  stubSubscriptionsOk
} from './support/hostingerStubs'
import type { WireMock } from './support/wiremock'

/*
 * The Overview page, end to end: the real Electron app and backend, with GitHub and Hostinger faked by WireMock.
 * Dates are relative to now, so "expiring" (within 30 days) stays true whenever the test runs.
 */

const FAILED = 'node-app.com'
const DEPLOYED = 'web-app.com'
const BLOG = 'blog.com'
const SITES = [FAILED, DEPLOYED, BLOG]
const REPOS = ['node-app', 'web-app', 'dotfiles']

const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString()
const hoursAgo = (n: number) => new Date(Date.now() - n * 3_600_000).toISOString()

async function stubGithub(wiremock: WireMock) {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await stubRepos(wiremock, REPOS.map((name) => ({ name })))
}

/** Three sites: two Node.js sites (one failed deploy, one completed), one WordPress site; one domain expiring soon. */
async function stubHostinger(wiremock: WireMock) {
  await stubSubscriptionsOk(wiremock)
  await stubSites(wiremock, [
    { domain: FAILED, websiteType: 'nodejs' },
    { domain: DEPLOYED, websiteType: 'nodejs' },
    { domain: BLOG }
  ])
  for (const domain of SITES) await stubGitNotConnected(wiremock, domain)
  await stubBuilds(wiremock, FAILED, [{ state: 'failed', commit: 'deadbeef00112233445566778899aabbccddeeff', createdAt: hoursAgo(1) }])
  await stubBuilds(wiremock, DEPLOYED, [{ state: 'completed', commit: 'cafebabe00112233445566778899aabbccddeeff', createdAt: hoursAgo(5) }])
  await stubDomains(wiremock, [
    { domain: FAILED, expiresAt: inDays(10) },
    { domain: DEPLOYED, expiresAt: inDays(400) },
    { domain: BLOG, expiresAt: inDays(200) }
  ])
}

async function connectGithub(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
}

async function connectHostinger(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByLabel('API token').fill(FAKE_HOSTINGER.token)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
}

async function openOverview(window: Page) {
  await window.getByRole('link', { name: 'Overview', exact: true }).click()
  await expect(window.getByRole('heading', { name: 'Overview', level: 1 })).toBeVisible()
}

const env = (wiremock: WireMock) => ({ ...githubEnv(wiremock), ...hostingerEnv(wiremock) })
const value = (window: Page, card: 'sites' | 'repos' | 'problems') => window.getByTestId(`overview-count-${card}-value`)

test('the Overview shows the counts, problems and latest deploys, and a deploy opens the site', async ({ wiremock, launchApp }) => {
  await stubGithub(wiremock)
  await stubHostinger(wiremock)
  const { window } = await launchApp(env(wiremock))

  // The app opens on the Overview.
  await expect(window.getByRole('heading', { name: 'Overview', level: 1 })).toBeVisible()

  await connectGithub(window)
  await connectHostinger(window)
  await openOverview(window)

  await expect(value(window, 'sites')).toHaveText(String(SITES.length))
  await expect(value(window, 'repos')).toHaveText(String(REPOS.length))
  await expect(value(window, 'problems')).toHaveText('2')

  const problems = window.getByRole('region', { name: 'Problems' }).getByTestId('overview-problem')
  await expect(problems).toHaveCount(2)
  await expect(problems.and(window.locator('[data-kind="DEPLOY_FAILED"]'))).toContainText(FAILED)
  await expect(problems.and(window.locator('[data-kind="DOMAIN_EXPIRING"]'))).toContainText(FAILED)

  await expect(window.getByTestId('overview-source-github')).toHaveAttribute('data-state', 'CONNECTED')
  await expect(window.getByTestId('overview-source-hostinger')).toHaveAttribute('data-state', 'CONNECTED')
  await expect(window.getByTestId('overview-part-error')).toHaveCount(0)

  // Latest deploys, newest first.
  const deploys = window.getByRole('region', { name: 'Latest deploys' }).getByTestId('overview-deploy')
  await expect(deploys).toHaveCount(2)
  await expect(deploys.nth(0)).toContainText(FAILED)
  await expect(deploys.nth(0).getByTestId('deploy-commit')).toContainText('deadbee')
  await expect(deploys.nth(1)).toContainText(DEPLOYED)
  await expect(deploys.nth(1).getByTestId('deploy-commit')).toContainText('cafebab')

  await deploys.nth(1).getByRole('link', { name: DEPLOYED, exact: true }).click()
  await expect(window.getByRole('heading', { name: DEPLOYED, level: 1 })).toBeVisible()
})

test('when Hostinger answers 500 the GitHub parts are still shown', async ({ wiremock, launchApp }) => {
  await stubGithub(wiremock)
  await stubSubscriptionsOk(wiremock)
  await stubSitesServerError(wiremock)
  await stubDomainsServerError(wiremock)
  const { window } = await launchApp(env(wiremock))

  await connectGithub(window)
  await connectHostinger(window)
  await openOverview(window)

  await expect(value(window, 'repos')).toHaveText(String(REPOS.length))
  await expect(value(window, 'sites')).toHaveText('—')
  const sitesCard = window.getByTestId('overview-count-sites').locator('..')
  await expect(sitesCard.getByTestId('overview-part-error')).toHaveAttribute('data-code', /^HOSTINGER_/)
  await expect(window.getByTestId('overview-source-github')).toHaveAttribute('data-state', 'CONNECTED')
  await expect(window.getByRole('region', { name: 'Latest deploys' }).getByTestId('overview-part-error').first()).toHaveAttribute(
    'data-code',
    /^HOSTINGER_/
  )
  await expect(window.getByTestId('overview-nothing-connected')).toHaveCount(0)
})

test('when the GitHub token is rejected the Hostinger parts are still shown and Reconnect is offered', async ({
  wiremock,
  launchApp
}) => {
  await stubGithub(wiremock)
  await stubHostinger(wiremock)
  const { window } = await launchApp(env(wiremock))

  await connectGithub(window)
  await connectHostinger(window)

  // GitHub revokes the token.
  await stubUserUnauthorized(wiremock)
  await stubReposUnauthorized(wiremock)
  await openOverview(window)

  await expect(value(window, 'sites')).toHaveText(String(SITES.length))
  await expect(value(window, 'repos')).toHaveText('—')
  const reposCard = window.getByTestId('overview-count-repos').locator('..')
  const rejected = reposCard.locator('[data-testid="overview-part-error"][data-code="GITHUB_TOKEN_REJECTED"]')
  await expect(rejected).toBeVisible()
  await expect(rejected.getByRole('button', { name: 'Reconnect', exact: true })).toBeVisible()

  const problems = window.getByRole('region', { name: 'Problems' }).getByTestId('overview-problem')
  await expect(problems.and(window.locator('[data-kind="DEPLOY_FAILED"]'))).toContainText(FAILED)
  await expect(problems.and(window.locator('[data-kind="DOMAIN_EXPIRING"]'))).toContainText(FAILED)
  await expect(window.getByRole('region', { name: 'Latest deploys' }).getByTestId('overview-deploy')).toHaveCount(2)

  await expect(window.getByTestId('overview-source-hostinger')).toHaveAttribute('data-state', 'CONNECTED')
  const github = window.getByTestId('overview-source-github')
  await expect(github).toHaveAttribute('data-state', 'ERROR')
  await github.getByRole('button', { name: 'Reconnect', exact: true }).click()
  await expect(window.getByRole('heading', { name: 'Settings', level: 1 })).toBeVisible()
})

test('with nothing connected the Overview shows how to get started', async ({ launchApp }) => {
  const { window } = await launchApp()

  await expect(window.getByRole('heading', { name: 'Overview', level: 1 })).toBeVisible()
  const empty = window.getByTestId('overview-nothing-connected')
  await expect(empty).toContainText('Nothing is connected yet')
  await expect(window.getByTestId('overview-count-sites')).toHaveCount(0)

  await empty.getByRole('button', { name: 'Open Settings' }).click()
  await expect(window.getByRole('heading', { name: 'Settings', level: 1 })).toBeVisible()
})
