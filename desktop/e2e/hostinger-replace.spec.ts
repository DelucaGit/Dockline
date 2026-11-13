import { expect, test } from './support/fixtures'
import { hostingerEnv, stubSubscriptionsOk, stubUnauthorized, SUBSCRIPTIONS_PATH } from './support/hostingerStubs'

// Real app + real backend + WireMock as Hostinger. "Survives a restart" needs the real Windows Credential
// Manager, which these tests never touch (the token store is in memory): check it by hand.
const OLD_TOKEN = 'hstg_FAKE_OLD_TOKEN_1'
const NEW_TOKEN = 'hstg_FAKE_NEW_TOKEN_2'
const BAD_TOKEN = 'hstg_FAKE_BAD_TOKEN_3'

test('a revoked token turns the dot red, "Replace token" fixes it, a bad one is rejected first', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  const { window } = await launchApp(hostingerEnv(wiremock))
  const dot = window.getByTestId('connection-hostinger')

  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByLabel('API token').fill(OLD_TOKEN)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
  await expect(window.getByRole('button', { name: 'Replace Hostinger token' })).toHaveCount(0)

  // Hostinger revokes the token. Opening Settings again asks Hostinger right away.
  await stubUnauthorized(wiremock)
  await window.getByRole('link', { name: 'Log' }).click()
  await window.getByRole('link', { name: 'Settings' }).click()

  await expect(dot).toHaveAttribute('data-state', 'ERROR')
  await expect(window.getByRole('link', { name: 'Hostinger: error — token needs replacing' })).toBeVisible()
  await expect(dot.locator('span[aria-hidden]')).toHaveClass(/bg-red-500/)
  await expect(window.getByRole('heading', { name: 'Replace token' })).toBeVisible()
  await expect(window.getByRole('button', { name: 'Replace Hostinger token' })).toBeVisible()
  await expect(window.getByText(/no longer accepts the saved token/)).toBeVisible()

  // The revoked token is in the Log, masked.
  await window.getByRole('link', { name: 'Log' }).click()
  const entry = window.getByTestId('log-entry').filter({ hasText: 'HOSTINGER' }).first()
  await expect(entry).toContainText('401')
  await expect(window.getByTestId('log-entry').filter({ hasText: OLD_TOKEN })).toHaveCount(0)

  // An invalid new token (Hostinger answers 401): the message shows and the old state is kept.
  await window.getByRole('button', { name: 'Replace Hostinger token' }).click()
  await expect(window.getByLabel('API token')).toBeFocused()
  await window.getByLabel('API token').fill(BAD_TOKEN)
  await window.getByRole('button', { name: 'Replace token', exact: true }).click()
  await expect(window.getByRole('alert')).toContainText(/hostinger/i)
  await expect(dot).toHaveAttribute('data-state', 'ERROR')
  await expect(dot.locator('span[aria-hidden]')).toHaveClass(/bg-red-500/)

  // A valid new token replaces the old one.
  await stubSubscriptionsOk(wiremock)
  await window.getByLabel('API token').fill(NEW_TOKEN)
  await window.getByRole('button', { name: 'Replace token', exact: true }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
  await expect(dot.locator('span[aria-hidden]')).toHaveClass(/bg-green-500/)
  await expect(window.getByText(/no longer accepts the saved token/)).toHaveCount(0)
  await expect(window.getByRole('button', { name: 'Replace Hostinger token' })).toHaveCount(0)

  const sent = (await wiremock.requests())
    .filter((r) => r.method === 'GET' && r.url.startsWith(SUBSCRIPTIONS_PATH))
    .map((r) => Object.entries(r.headers).find(([k]) => k.toLowerCase() === 'authorization')?.[1] ?? '')
  expect(sent.some((h) => h.includes(NEW_TOKEN))).toBe(true)
})
