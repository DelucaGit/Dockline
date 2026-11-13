import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubTokenDenied,
  stubTokenExpired,
  stubTokenPending,
  stubTokenSuccess,
  stubUser
} from './support/githubStubs'

// The app is started with DOCKLINE_E2E_NO_BROWSER=1 (see fixtures.ts), so "open the browser" is only recorded.
// The connection survives a restart only with the real Credential Manager; the E2E token store is
// per process (memory), so that part is covered by the backend tests instead.

test('connect: code is shown, the browser page is opened, approval turns the dot green', async ({ wiremock, launchApp }) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenPending(wiremock)
  const { app, window } = await launchApp(githubEnv(wiremock))
  await expect(window.getByRole('link', { name: 'GitHub: not connected' })).toBeVisible()

  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()

  await expect(window.getByText(FAKE_GITHUB.userCode)).toBeVisible()
  await expect(window.getByText('Waiting for approval…')).toBeVisible()
  await expect(window.getByRole('link', { name: FAKE_GITHUB.verificationUri })).toBeVisible()
  await expect(window.getByRole('button', { name: 'Copy code' })).toBeVisible()
  const opened = await app.evaluate(() => (globalThis as { __docklineOpenedUrls?: string[] }).__docklineOpenedUrls)
  expect(opened).toEqual([FAKE_GITHUB.verificationUri])

  // The user approves on GitHub.
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)

  await expect(window.getByText('Connected', { exact: true })).toBeVisible()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
  await expect(window.getByText(FAKE_GITHUB.userCode)).toHaveCount(0)
  const dot = window.getByTestId('connection-github')
  await expect(dot).toHaveAttribute('data-state', 'CONNECTED')
  await expect(window.getByRole('link', { name: 'GitHub: connected' })).toBeVisible()
  await expect(dot.locator('span[aria-hidden]')).toHaveClass(/bg-green-500/)
})

test('a denied code shows a message and "Try again" works', async ({ wiremock, launchApp }) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenDenied(wiremock)
  const { window } = await launchApp(githubEnv(wiremock))
  await window.getByRole('link', { name: 'Settings' }).click()

  await window.getByRole('button', { name: 'Connect to GitHub' }).click()

  await expect(window.getByRole('alert')).toContainText(/denied/i)
  await expect(window.getByText(FAKE_GITHUB.userCode)).toHaveCount(0)
  await expect(window.getByRole('link', { name: 'GitHub: not connected' })).toBeVisible()

  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  await window.getByRole('button', { name: 'Try again' }).click()

  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
  await expect(window.getByRole('link', { name: 'GitHub: connected' })).toBeVisible()
})

test('an expired code shows a message and "Try again"', async ({ wiremock, launchApp }) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenExpired(wiremock)
  const { window } = await launchApp(githubEnv(wiremock))
  await window.getByRole('link', { name: 'Settings' }).click()

  await window.getByRole('button', { name: 'Connect to GitHub' }).click()

  await expect(window.getByRole('alert')).toContainText(/expired/i)
  await expect(window.getByRole('button', { name: 'Try again' })).toBeVisible()
  await expect(window.getByRole('link', { name: 'GitHub: not connected' })).toBeVisible()
})

test('disconnect asks first, then the dot goes back to "not connected"', async ({ wiremock, launchApp }) => {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  const { window } = await launchApp(githubEnv(wiremock))
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByRole('link', { name: 'GitHub: connected' })).toBeVisible()

  await window.getByRole('button', { name: 'Disconnect' }).click()
  await expect(window.getByRole('alertdialog')).toBeVisible()
  await window.getByRole('button', { name: 'Keep connected' }).click()
  await expect(window.getByRole('link', { name: 'GitHub: connected' })).toBeVisible()

  await window.getByRole('button', { name: 'Disconnect' }).click()
  await window.getByRole('button', { name: 'Yes, disconnect' }).click()

  await expect(window.getByRole('link', { name: 'GitHub: not connected' })).toBeVisible()
  await expect(window.getByTestId('connection-github').locator('span[aria-hidden]')).toHaveClass(/bg-red-500/)
  await expect(window.getByRole('button', { name: 'Connect to GitHub' })).toBeVisible()
})

test('without a GitHub client ID the user gets a readable message', async ({ launchApp }) => {
  const { window } = await launchApp()
  await window.getByRole('link', { name: 'Settings' }).click()

  await window.getByRole('button', { name: 'Connect to GitHub' }).click()

  await expect(window.getByRole('alert')).toContainText('GitHub client ID is not configured')
  await expect(window.getByText(/^See docs\/github-oauth-app-setup\.md/)).toBeVisible()
})
