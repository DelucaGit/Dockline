import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubRepos,
  stubReposServerError,
  stubReposUnauthorized,
  stubTokenPending,
  stubTokenSuccess,
  stubUser,
  stubUserUnauthorized
} from './support/githubStubs'
import { FAKE_HOSTINGER, hostingerEnv, stubSubscriptionsOk } from './support/hostingerStubs'

// Real app + real backend + WireMock as both GitHub and Hostinger. Tokens stay in memory (see fixtures.ts).

async function connectGithub(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
}

const dotOf = (window: Page, source: 'github' | 'hostinger') =>
  window.getByTestId(`connection-${source}`).locator('span[aria-hidden]')

test('not connected: a friendly message and a button that opens Settings', async ({ wiremock, launchApp }) => {
  const { window } = await launchApp({ ...githubEnv(wiremock), ...hostingerEnv(wiremock) })
  await window.getByRole('link', { name: 'Repos', exact: true }).click()

  await expect(window.getByText('GitHub is not connected')).toBeVisible()
  await window.getByRole('button', { name: 'Open Settings' }).click()
  await expect(window.getByRole('heading', { name: 'Settings' })).toBeVisible()
  await expect(window.getByRole('button', { name: 'Connect to GitHub' })).toBeVisible()
})

test('a GitHub failure shows Try again and a Log entry while Hostinger keeps working', async ({ wiremock, launchApp }) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await stubRepos(wiremock, [{ name: 'alpha', pushedAt: new Date().toISOString() }])
  await stubSubscriptionsOk(wiremock)
  const { window } = await launchApp({ ...githubEnv(wiremock), ...hostingerEnv(wiremock) })
  await connectGithub(window)

  // Hostinger works in Settings before, during and after the GitHub failure.
  await window.getByLabel('API token').fill(FAKE_HOSTINGER.token)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()

  // GitHub starts failing.
  await stubReposServerError(wiremock)
  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  await expect(window.getByRole('alert')).toBeVisible()
  await expect(window.getByRole('button', { name: 'Try again' })).toBeVisible()
  await expect(window.getByTestId('repo-row')).toHaveCount(0)
  await expect(dotOf(window, 'hostinger')).toHaveClass(/bg-green-500/)

  // The failure is in the Log (and Hostinger settings still open and show the connection).
  await window.getByRole('link', { name: 'Log' }).click()
  const entry = window.getByTestId('log-entry').filter({ hasText: 'GET /user/repos' }).first()
  await expect(entry).toContainText('GITHUB')
  await window.getByRole('link', { name: 'Settings' }).click()
  await expect(window.getByRole('heading', { name: 'Hostinger' })).toBeVisible()
  await expect(dotOf(window, 'hostinger')).toHaveClass(/bg-green-500/)

  // GitHub recovers: Try again shows the list.
  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  await expect(window.getByRole('alert')).toBeVisible()
  await stubRepos(wiremock, [{ name: 'alpha', pushedAt: new Date().toISOString() }])
  await window.getByRole('button', { name: 'Try again' }).click()
  await expect(window.getByTestId('repo-row')).toHaveCount(1)
  await expect(window.getByRole('alert')).toHaveCount(0)
  await expect(dotOf(window, 'hostinger')).toHaveClass(/bg-green-500/)
})

test('a revoked token: Reconnect, a red GitHub dot, then the list again', async ({ wiremock, launchApp }) => {
  const newCode = 'WXYZ-5678'
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await stubSubscriptionsOk(wiremock)
  const { window } = await launchApp({ ...githubEnv(wiremock), ...hostingerEnv(wiremock) })
  await connectGithub(window)

  // GitHub revokes the token.
  await stubUserUnauthorized(wiremock)
  await stubReposUnauthorized(wiremock)
  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  await expect(window.getByRole('alert')).toBeVisible()
  await expect(window.getByRole('button', { name: 'Reconnect', exact: true }).first()).toBeVisible()
  await expect(window.getByTestId('connection-github')).toHaveAttribute('data-state', 'ERROR')
  await expect(dotOf(window, 'github')).toHaveClass(/bg-red-500/)

  // Reconnect from the Repos view: Settings opens and the device flow starts by itself.
  await stubDeviceCode(wiremock, { interval: 1, userCode: newCode })
  await stubTokenPending(wiremock)
  await window.getByRole('alert').getByRole('button', { name: 'Reconnect' }).click()
  await expect(window.getByRole('heading', { name: 'Settings' })).toBeVisible()
  await expect(window.getByText(newCode)).toBeVisible()

  // Approve with a new token.
  await stubUser(wiremock)
  await stubTokenSuccess(wiremock, 'gho_FakeE2eNewTokenNOTreal0123456789')
  await expect(window.getByRole('link', { name: 'GitHub: connected' })).toBeVisible()
  await expect(dotOf(window, 'github')).toHaveClass(/bg-green-500/)

  await stubRepos(wiremock, [{ name: 'alpha', pushedAt: new Date().toISOString() }])
  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  await expect(window.getByTestId('repo-row')).toHaveCount(1)
})
