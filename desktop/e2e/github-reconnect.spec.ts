import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubTokenPending,
  stubTokenSuccess,
  stubUser,
  stubUserUnauthorized
} from './support/githubStubs'

// The connection surviving an app restart needs the real Windows Credential Manager, which these tests
// never touch (tokens stay in memory). That part is covered by the backend CredentialManagerTokenStoreTest.

test('a revoked token turns the dot red, "Reconnect" runs the device flow again and the dot turns green', async ({
  wiremock,
  launchApp
}) => {
  const newCode = 'ABCD-1234'
  const newToken = 'gho_FakeE2eSecondTokenNOTreal9876543210'
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  const { window } = await launchApp(githubEnv(wiremock))

  // Connect the first time.
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByRole('link', { name: 'GitHub: connected' })).toBeVisible()
  await expect(window.getByRole('button', { name: 'Reconnect', exact: true })).toHaveCount(0)

  // GitHub revokes the token. The user opens Settings again, which asks GitHub right away.
  await stubUserUnauthorized(wiremock)
  await window.getByRole('link', { name: 'Log' }).click()
  await window.getByRole('link', { name: 'Settings' }).click()

  const dot = window.getByTestId('connection-github')
  await expect(dot).toHaveAttribute('data-state', 'ERROR')
  await expect(window.getByRole('link', { name: 'GitHub: error — reconnect needed' })).toBeVisible()
  await expect(dot.locator('span[aria-hidden]')).toHaveClass(/bg-red-500/)
  await expect(window.getByText('Connection problem')).toBeVisible()
  await expect(window.getByText(/reconnect/i).first()).toBeVisible()
  await expect(window.getByRole('button', { name: 'Reconnect GitHub' })).toBeVisible()
  await expect(window.getByRole('button', { name: 'Reconnect', exact: true })).toBeVisible()

  // The revoked token is in the Log, with the token masked.
  await window.getByRole('link', { name: 'Log' }).click()
  const entry = window.getByTestId('log-entry').first()
  await expect(entry).toContainText('GITHUB')
  await expect(entry).toContainText('401')
  await expect(window.getByTestId('log-entry').filter({ hasText: FAKE_GITHUB.accessToken })).toHaveCount(0)

  // Reconnect from the sidebar: Settings opens and the device flow starts by itself, with a fresh code.
  await stubDeviceCode(wiremock, { interval: 1, userCode: newCode })
  await stubTokenPending(wiremock)
  await window.getByRole('button', { name: 'Reconnect GitHub' }).click()
  await expect(window.getByRole('heading', { name: 'Settings' })).toBeVisible()
  await expect(window.getByText(newCode)).toBeVisible()
  await expect(window.getByText('Waiting for approval…')).toBeVisible()

  // The user approves; GitHub accepts the new token.
  await stubUser(wiremock)
  await stubTokenSuccess(wiremock, newToken)

  await expect(window.getByRole('link', { name: 'GitHub: connected' })).toBeVisible()
  await expect(dot.locator('span[aria-hidden]')).toHaveClass(/bg-green-500/)
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
  await expect(window.getByText('Connection problem')).toHaveCount(0)
  await expect(window.getByRole('button', { name: 'Reconnect GitHub' })).toHaveCount(0)
  await expect(window.getByRole('button', { name: 'Reconnect', exact: true })).toHaveCount(0)

  // The new token is the one the backend now sends to GitHub.
  const sent = (await wiremock.requests())
    .filter((r) => r.method === 'GET' && r.url.startsWith('/user'))
    .map((r) => Object.entries(r.headers).find(([k]) => k.toLowerCase() === 'authorization')?.[1] ?? '')
  expect(sent.some((h) => h.includes(newToken))).toBe(true)
})
