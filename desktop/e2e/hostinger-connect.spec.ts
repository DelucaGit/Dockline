import { expect, test } from './support/fixtures'
import { hostingerEnv, stubConnectionReset, stubServerError, stubSubscriptionsOk, stubUnauthorized } from './support/hostingerStubs'

// Real app + real backend + WireMock as Hostinger. The token store is in memory (see fixtures.ts).
const TOKEN = 'hstg_FAKE_TEST_TOKEN_123'

async function openSettings(window: import('@playwright/test').Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await expect(window.getByLabel('API token')).toBeVisible()
}

async function paste(window: import('@playwright/test').Page) {
  await window.getByLabel('API token').fill(TOKEN)
  await window.getByRole('button', { name: 'Save' }).click()
}

test('a valid token: "Connected", green dot, field cleared, token never on screen', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await expect(window.getByRole('link', { name: 'Hostinger: not connected' })).toBeVisible()
  await openSettings(window)
  await expect(window.getByLabel('API token')).toHaveAttribute('type', 'password')

  await paste(window)

  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
  await expect(window.getByTestId('connection-hostinger').locator('span[aria-hidden]')).toHaveClass(/bg-green-500/)
  await expect(window.getByRole('heading', { name: 'Hostinger' })).toBeVisible()
  await expect(window.getByText('Connected', { exact: true }).last()).toBeVisible()
  await expect(window.getByLabel('API token')).toHaveCount(0)
  expect(await window.evaluate(() => document.body.innerText)).not.toContain(TOKEN)
  expect(await window.evaluate(() => document.documentElement.outerHTML)).not.toContain(TOKEN)
  expect(await window.evaluate(() => Array.from(document.querySelectorAll('input')).map((i) => i.value))).not.toContain(TOKEN)
})

test('an invalid token: clear message, dot stays red, nothing stored', async ({ wiremock, launchApp }) => {
  await stubUnauthorized(wiremock)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await openSettings(window)

  await paste(window)

  await expect(window.getByRole('alert')).toContainText(/hostinger/i)
  await expect(window.getByLabel('API token')).toHaveValue('')
  await expect(window.getByRole('link', { name: 'Hostinger: not connected' })).toBeVisible()
  await expect(window.getByTestId('connection-hostinger').locator('span[aria-hidden]')).toHaveClass(/bg-red-500/)

  // Back to the start page and into Settings again: still not connected.
  await window.getByRole('link', { name: 'Overview' }).click()
  await openSettings(window)
  await expect(window.getByRole('link', { name: 'Hostinger: not connected' })).toBeVisible()
})

for (const [name, stub] of [
  ['unreachable (connection reset)', stubConnectionReset],
  ['answering with a server error', stubServerError]
] as const) {
  test(`Hostinger ${name}: the user is told, nothing is stored`, async ({ wiremock, launchApp }) => {
    await stub(wiremock)
    const { window } = await launchApp(hostingerEnv(wiremock))
    await openSettings(window)

    await paste(window)

    await expect(window.getByRole('alert')).toContainText(/reach|answer|try again/i)
    await expect(window.getByRole('link', { name: 'Hostinger: not connected' })).toBeVisible()
    await expect(window.getByLabel('API token')).toBeVisible()
  })
}

test('disconnect asks first, then the dot goes back to "not connected"', async ({ wiremock, launchApp }) => {
  await stubSubscriptionsOk(wiremock)
  const { window } = await launchApp(hostingerEnv(wiremock))
  await openSettings(window)
  await paste(window)
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()

  await window.getByRole('button', { name: 'Disconnect' }).click()
  await expect(window.getByRole('alertdialog')).toBeVisible()
  await window.getByRole('button', { name: 'Keep connected' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()

  await window.getByRole('button', { name: 'Disconnect' }).click()
  await window.getByRole('button', { name: 'Yes, disconnect' }).click()

  await expect(window.getByRole('link', { name: 'Hostinger: not connected' })).toBeVisible()
  await expect(window.getByTestId('connection-hostinger').locator('span[aria-hidden]')).toHaveClass(/bg-red-500/)
  await expect(window.getByLabel('API token')).toBeVisible()
})
