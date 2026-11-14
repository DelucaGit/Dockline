import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import type { Page } from '@playwright/test'
import { expect, test } from './support/fixtures'
import { FAKE_GITHUB, githubEnv, stubDeviceCode, stubRepos, stubRepository, stubTokenSuccess, stubUser } from './support/githubStubs'
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubDomains,
  stubGitAutoDeployment,
  stubGitInstallations,
  stubGitNotConnected,
  stubSites,
  stubSubscriptionsOk
} from './support/hostingerStubs'

// Takes the screenshots used in README.md. It is NOT part of the normal test run: it only runs when
// DOCKLINE_SCREENSHOTS=1, so a plain `npm run test:e2e` stays unchanged.
//   cd desktop && DOCKLINE_SCREENSHOTS=1 npx playwright test e2e/screenshots.spec.ts
// Everything on screen comes from fake GitHub and Hostinger servers (WireMock); no real account or token is used.
test.skip(!process.env['DOCKLINE_SCREENSHOTS'], 'only runs with DOCKLINE_SCREENSHOTS=1')

const OUT = join(import.meta.dirname, '..', '..', 'docs', 'images')
const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString()
const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString()

const SITES = [
  { domain: 'lindqvist-bageri.example', repo: 'lindqvist-bageri' },
  { domain: 'nordic-fika.example', repo: 'nordic-fika' },
  { domain: 'portfolio.example', repo: 'portfolio' },
  { domain: 'kodkaffe.example', repo: null }
] as const

async function connectBoth(window: Page) {
  await window.getByRole('link', { name: 'Settings' }).click()
  await window.getByRole('button', { name: 'Connect to GitHub' }).click()
  await expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible()
  await window.getByLabel('API token').fill(FAKE_HOSTINGER.token)
  await window.getByRole('button', { name: 'Save' }).click()
  await expect(window.getByRole('link', { name: 'Hostinger: connected' })).toBeVisible()
}

async function report(window: Page, query: string) {
  const status = await window.evaluate(async (q) => {
    const state = await (globalThis as unknown as Window).dockline.getBackendState()
    if (state.status !== 'ready') throw new Error('Backend not ready')
    return (await fetch(`${state.baseUrl}/api/test/report-error?${q}`, { method: 'POST' })).status
  }, query)
  expect(status).toBe(200)
}

for (const scheme of ['light', 'dark'] as const) {
  test(`screenshots (${scheme})`, async ({ wiremock, launchApp }) => {
    mkdirSync(OUT, { recursive: true })
    await stubDeviceCode(wiremock, { interval: 1 })
    await stubTokenSuccess(wiremock)
    await stubUser(wiremock)
    await stubRepos(wiremock, [
      { name: 'lindqvist-bageri', pushedAt: daysAgo(1) },
      { name: 'nordic-fika', pushedAt: daysAgo(3) },
      { name: 'portfolio', pushedAt: daysAgo(9) },
      { name: 'dockline', pushedAt: daysAgo(0) },
      { name: 'recipe-api', private: true, pushedAt: daysAgo(21) },
      { name: 'dotfiles', pushedAt: daysAgo(60) }
    ])
    for (const s of SITES) if (s.repo) await stubRepository(wiremock, `${FAKE_GITHUB.login}/${s.repo}`)

    await stubSubscriptionsOk(wiremock)
    await stubSites(wiremock, SITES.map((s) => ({ domain: s.domain })))
    await stubGitInstallations(wiremock, 'github')
    for (const s of SITES) {
      if (s.repo) await stubGitAutoDeployment(wiremock, s.domain, { owner: FAKE_GITHUB.login, repository: s.repo })
      else await stubGitNotConnected(wiremock, s.domain)
    }
    await stubDomains(wiremock, [
      { domain: 'lindqvist-bageri.example', expiresAt: inDays(240) },
      { domain: 'nordic-fika.example', expiresAt: inDays(21) },
      { domain: 'portfolio.example', expiresAt: inDays(400) },
      { domain: 'kodkaffe.example', expiresAt: inDays(88) }
    ])

    const { app, window } = await launchApp({ ...githubEnv(wiremock), ...hostingerEnv(wiremock), DOCKLINE_TEST_ENDPOINTS: 'true' })
    await app.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0]?.setContentSize(1280, 800))
    await window.emulateMedia({ colorScheme: scheme })
    await connectBoth(window)

    const shot = async (name: string, settle: () => Promise<unknown>, clip?: { x: number; y: number; width: number; height: number }) => {
      await settle()
      await window.waitForTimeout(400) // let transitions and fonts finish
      await window.screenshot({ path: join(OUT, `${name}-${scheme}.png`), clip })
    }

    await shot('settings', () => expect(window.getByText(`Connected as ${FAKE_GITHUB.login}`)).toBeVisible())

    await window.getByRole('link', { name: 'Sites', exact: true }).click()
    await shot('sites', () => expect(window.getByTestId('site-row')).toHaveCount(SITES.length))

    await window.getByRole('link', { name: 'Repos', exact: true }).click()
    await shot('repos', () => expect(window.getByTestId('repo-row')).toHaveCount(6))

    await window.getByRole('link', { name: 'Domains', exact: true }).click()
    await shot('domains', () => window.waitForLoadState('networkidle'))

    // The Log shows what the backend reported; every secret in it is already masked (ghp_****).
    await report(window, 'source=HOSTINGER&level=WARN&action=GET%20/api/hosting/v1/websites&statusCode=429&message=Rate%20limited%2C%20retrying%20in%2023%20s')
    await report(window, 'source=GITHUB&level=ERROR&action=GET%20/user/repos&statusCode=401&message=Token%20rejected')
    await window.getByRole('link', { name: 'Log', exact: true }).click()
    const entries = window.getByTestId('log-entry')
    await expect(entries).toHaveCount(2)
    await entries.first().getByText('Token rejected').click()
    // Only the top of the open entry: the stack trace below it is long and adds nothing here.
    await shot('log', () => expect(entries.first()).toContainText('ghp_****'), { x: 0, y: 0, width: 1280, height: 340 })
  })
}
