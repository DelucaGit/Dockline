import type { Page } from '@playwright/test'
import type { WireMock } from './support/wiremock'
import { expect, test } from './support/fixtures'
import {
  FAKE_GITHUB,
  githubEnv,
  stubDeviceCode,
  stubRepos,
  stubTokenSuccess,
  stubUser,
  type FakeRepo
} from './support/githubStubs'

/** Start the app, connect (fake) GitHub through Settings, and open the Repos view. */
async function connectAndOpenRepos(
  wiremock: WireMock,
  launchApp: (env?: Record<string, string>) => Promise<{ window: Page; app: import('@playwright/test').ElectronApplication }>
) {
  await stubDeviceCode(wiremock, { interval: 1 })
  await stubTokenSuccess(wiremock)
  await stubUser(wiremock)
  const running = await launchApp(githubEnv(wiremock))
  const { window } = running
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
  await window.getByRole('link', { name: 'Repos', exact: true }).click()
  return running
}

const repo = (name: string, extra: Partial<FakeRepo> = {}): FakeRepo => ({
  name,
  pushedAt: new Date(Date.now() - 3 * 3600 * 1000).toISOString(),
  ...extra
})

test('the list shows name, private/public and last push, across several pages', async ({ wiremock, launchApp }) => {
  await stubRepos(
    wiremock,
    [repo('alpha', { private: true }), repo('bravo'), repo('charlie', { pushedAt: null }), repo('delta')],
    { pages: 2 }
  )
  const { window } = await connectAndOpenRepos(wiremock, launchApp)

  const rows = window.getByTestId('repo-row')
  await expect(rows).toHaveCount(4)
  const alpha = rows.filter({ hasText: `${FAKE_GITHUB.login}/alpha` })
  await expect(alpha).toContainText('Private')
  await expect(alpha).toContainText('3 hours ago')
  await expect(rows.filter({ hasText: `${FAKE_GITHUB.login}/bravo` })).toContainText('Public')
  await expect(rows.filter({ hasText: `${FAKE_GITHUB.login}/charlie` })).toContainText('No pushes yet')
  await expect(rows.filter({ hasText: `${FAKE_GITHUB.login}/delta` })).toBeVisible()
  await expect(window.getByText(/Last updated/)).toBeVisible()
})

test('Refresh shows a new repository and changes "Last updated"', async ({ wiremock, launchApp }) => {
  await stubRepos(wiremock, [repo('alpha')])
  const { window } = await connectAndOpenRepos(wiremock, launchApp)
  await expect(window.getByTestId('repo-row')).toHaveCount(1)
  const label = window.getByText(/Last updated/)
  const before = await label.textContent()

  await stubRepos(wiremock, [repo('alpha'), repo('newcomer')])
  await window.waitForTimeout(1100) // "Last updated" shows seconds
  await window.getByRole('button', { name: 'Refresh' }).click()

  await expect(window.getByRole('button', { name: `${FAKE_GITHUB.login}/newcomer` })).toBeVisible()
  await expect(window.getByTestId('repo-row')).toHaveCount(2)
  await expect(label).not.toHaveText(before ?? '')
})

test('clicking a repository opens it on GitHub, not in the app', async ({ wiremock, launchApp }) => {
  await stubRepos(wiremock, [repo('alpha')])
  const { app, window } = await connectAndOpenRepos(wiremock, launchApp)

  // (connecting already opened GitHub's device page once; the repo link is the newest entry)
  await window.getByRole('button', { name: `${FAKE_GITHUB.login}/alpha` }).click()

  await expect
    .poll(async () => {
      const opened = await app.evaluate(() => (globalThis as { __docklineOpenedUrls?: string[] }).__docklineOpenedUrls)
      return opened?.at(-1)
    })
    .toBe(`https://github.com/${FAKE_GITHUB.login}/alpha`)
  expect(window.url()).not.toContain('github.com')
  await expect(window.getByRole('heading', { name: 'Repos' })).toBeVisible()
})

test('an account without repositories shows "No repositories"', async ({ wiremock, launchApp }) => {
  await stubRepos(wiremock, [])
  const { window } = await connectAndOpenRepos(wiremock, launchApp)

  await expect(window.getByText('No repositories')).toBeVisible()
  await expect(window.getByTestId('repo-row')).toHaveCount(0)
})
