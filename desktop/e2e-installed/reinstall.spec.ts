import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, sep } from 'node:path'
import { _electron as electron, type ElectronApplication, expect, type Page, test } from '@playwright/test'
import { githubEnv, stubDeviceCode, stubRepository, stubTokenSuccess, stubUser } from '../e2e/support/githubStubs'
import {
  FAKE_HOSTINGER,
  hostingerEnv,
  stubGitNotConnected,
  stubSites,
  stubSubscriptionsOk
} from '../e2e/support/hostingerStubs'
import { startWireMock, type WireMock } from '../e2e/support/wiremock'

/*
 * Installer smoke test (task #53): the real installer, the installed app, uninstall, reinstall and
 * install over an existing install. It proves that the settings file (a manual site–repo link) and the
 * log files live in the user's app-data folder, not in the install folder, and survive all three.
 *
 * Two modes:
 * - Default (any developer machine): the app gets its own temporary APPDATA folder and Electron
 *   user-data folder, and tokens are kept in memory, so the real %APPDATA%\Dockline and the real
 *   Windows Credential Manager are never touched. Tokens are entered again after each reinstall.
 * - DOCKLINE_SMOKE_REAL_USER_DATA=1 (CI, on a throwaway runner): the app uses the real per-user
 *   %APPDATA%\Dockline and the real Credential Manager, so it also proves that the uninstaller leaves
 *   the tokens. Refuses to run if a settings file already exists there, and removes the tokens at the end.
 */

const DESKTOP_DIR = join(import.meta.dirname, '..')
const VERSION = (JSON.parse(readFileSync(join(DESKTOP_DIR, 'package.json'), 'utf8')) as { version: string }).version
const INSTALLER = process.env['DOCKLINE_INSTALLER'] ?? join(DESKTOP_DIR, 'dist', `Dockline Setup ${VERSION}.exe`)
const REAL_USER_DATA = process.env['DOCKLINE_SMOKE_REAL_USER_DATA'] === '1'

const DOMAIN = 'plain.example'
const REPO = 'octocat/plain'

test.skip(process.platform !== 'win32', 'The installer is Windows only')

/** Run the installer silently into `dir` (also over an existing install). */
function install(dir: string): void {
  // NSIS wants `/D=` last and unquoted (the temp path has no spaces), so one command line is passed as is.
  const result = spawnSync(`"${INSTALLER}" /S /D=${dir}`, { shell: true, timeout: 300_000 })
  expect(result.status, `installer failed: ${String(result.stderr)}`).toBe(0)
  expect(existsSync(join(dir, 'Dockline.exe'))).toBe(true)
}

/** Run the uninstaller silently and wait until the app is gone (NSIS finishes in a copy of itself). */
async function uninstall(dir: string): Promise<void> {
  const result = spawnSync(`"${join(dir, 'Uninstall Dockline.exe')}" /S`, { shell: true, timeout: 300_000 })
  expect(result.status, `uninstaller failed: ${String(result.stderr)}`).toBe(0)
  await expect
    .poll(() => existsSync(join(dir, 'Dockline.exe')) || existsSync(join(dir, 'Uninstall Dockline.exe')), {
      intervals: [500],
      timeout: 120_000
    })
    .toBe(false)
}

/** Call the backend from the renderer, as the UI does. */
async function api(window: Page, method: string, path: string, json?: unknown): Promise<{ status: number; body: string }> {
  return window.evaluate(
    async ({ method, path, json }) => {
      const state = await (globalThis as unknown as Window).dockline.getBackendState()
      if (state.status !== 'ready') throw new Error(`Backend not ready: ${JSON.stringify(state)}`)
      const res = await fetch(`${state.baseUrl}${path}`, {
        method,
        headers: json === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: json === undefined ? undefined : JSON.stringify(json)
      })
      return { status: res.status, body: await res.text() }
    },
    { method, path, json }
  )
}

async function states(window: Page): Promise<string[]> {
  const body = JSON.parse((await api(window, 'GET', '/api/status')).body) as { sources: Array<{ state: string }> }
  return body.sources.map((s) => s.state)
}

async function connectBoth(window: Page): Promise<void> {
  expect((await api(window, 'PUT', '/api/hostinger/token', { token: FAKE_HOSTINGER.token })).status).toBe(200)
  expect((await api(window, 'POST', '/api/github/connect')).status).toBe(200)
  await expect
    .poll(async () => (JSON.parse((await api(window, 'POST', '/api/github/connect/poll')).body) as { state: string }).state, {
      intervals: [500],
      timeout: 15_000
    })
    .toBe('CONNECTED')
}

test('settings, logs and tokens survive uninstall + reinstall and installing over an existing install', async () => {
  test.skip(!existsSync(INSTALLER), `No installer at ${INSTALLER}; run npm run dist first`)

  const root = mkdtempSync(join(tmpdir(), 'dockline-smoke-'))
  const installDir = join(root, 'app')
  const appData = REAL_USER_DATA ? process.env['APPDATA']! : join(root, 'appdata')
  const userDataDir = join(appData, 'Dockline')
  const settingsFile = join(userDataDir, 'settings.json')
  const logFile = join(userDataDir, 'logs', 'dockline.log')
  if (REAL_USER_DATA) {
    expect(existsSync(settingsFile), `${settingsFile} already exists; this mode is for throwaway machines`).toBe(false)
  } else {
    mkdirSync(userDataDir, { recursive: true })
  }

  const wiremock: WireMock = await startWireMock()
  let app: ElectronApplication | null = null

  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined) env[k] = v
  // The installed app must find its folders by itself, so no overrides from the shell.
  for (const k of ['ELECTRON_RENDERER_URL', 'ELECTRON_RUN_AS_NODE', 'DOCKLINE_SETTINGS_DIR', 'DOCKLINE_LOG_DIR', 'DOCKLINE_TOKEN_STORE']) {
    delete env[k]
  }
  Object.assign(env, hostingerEnv(wiremock), githubEnv(wiremock), {
    APPDATA: appData,
    DOCKLINE_TOKEN_STORE: REAL_USER_DATA ? 'credential-manager' : 'memory',
    DOCKLINE_E2E_NO_BROWSER: '1'
  })

  /** Start the installed app like the Start-menu shortcut does (working folder = install folder). */
  const launch = async (): Promise<Page> => {
    app = await electron.launch({
      executablePath: join(installDir, 'Dockline.exe'),
      args: REAL_USER_DATA ? [] : [`--user-data-dir=${userDataDir}`],
      cwd: installDir,
      env
    })
    const window = await app.firstWindow()
    await expect(window.getByText('Backend connected')).toBeVisible()
    // The installed app, not a dev build. Electron's own data (by default %APPDATA%\dockline-desktop,
    // after the package name) is never in the install folder; in the default mode it is in the temporary folder.
    expect(await app.evaluate(({ app }) => app.isPackaged)).toBe(true)
    const electronData = (await app.evaluate(({ app }) => app.getPath('userData'))).toLowerCase()
    expect(electronData.startsWith((installDir + sep).toLowerCase())).toBe(false)
    if (!REAL_USER_DATA) expect(electronData).toBe(userDataDir.toLowerCase())
    return window
  }
  const close = async (): Promise<void> => {
    await app?.close()
    app = null
  }

  /** The manual link is back: in the settings file, from the API and in the Sites view. */
  const expectLinkKept = async (window: Page): Promise<void> => {
    const site = JSON.parse((await api(window, 'GET', `/api/sites/${DOMAIN}`)).body)
    expect(site).toMatchObject({ source: 'MANUAL', repo: { fullName: REPO } })
    await window.getByRole('link', { name: 'Sites', exact: true }).click()
    await expect(window.getByText(REPO)).toBeVisible()
  }

  try {
    await stubSubscriptionsOk(wiremock)
    await stubSites(wiremock, [{ domain: DOMAIN }])
    await stubGitNotConnected(wiremock, DOMAIN)
    await stubDeviceCode(wiremock)
    await stubTokenSuccess(wiremock)
    await stubUser(wiremock)
    await stubRepository(wiremock, REPO)

    // 1. First install: the backend starts, every main view opens, and a manual link is saved.
    install(installDir)
    let window = await launch()
    for (const name of ['Overview', 'Sites', 'Repos', 'Domains', 'Log', 'Settings']) {
      await window.getByRole('link', { name, exact: true }).click()
      await expect(window.getByRole('heading', { name, level: 1 })).toBeVisible()
    }
    await connectBoth(window)
    expect(await states(window)).toEqual(['CONNECTED', 'CONNECTED'])
    expect((await api(window, 'PUT', `/api/sites/${DOMAIN}/repo`, { repoFullName: REPO })).status).toBe(200)
    await expectLinkKept(window)
    await close()

    // User data is in the app-data folder, nothing of it in the install folder.
    expect(JSON.parse(readFileSync(settingsFile, 'utf8'))).toMatchObject({ version: 1, siteRepoLinks: { [DOMAIN]: REPO } })
    expect(existsSync(logFile)).toBe(true)
    const installed = readdirSync(installDir, { recursive: true }).map(String)
    expect(installed.filter((f) => /settings.*\.json$|\.log$/i.test(f))).toEqual([])

    // 2. Uninstall: the app is gone, the user data stays.
    await uninstall(installDir)
    expect(JSON.parse(readFileSync(settingsFile, 'utf8'))).toMatchObject({ siteRepoLinks: { [DOMAIN]: REPO } })
    expect(existsSync(logFile)).toBe(true)

    // 3. Install again: the link is still there; with the real Credential Manager so are the tokens.
    install(installDir)
    window = await launch()
    if (REAL_USER_DATA) expect(await states(window)).toEqual(['CONNECTED', 'CONNECTED'])
    else await connectBoth(window)
    await expectLinkKept(window)
    await close()

    // 4. Install over the existing install (what a newer version does): everything is kept again.
    install(installDir)
    window = await launch()
    if (REAL_USER_DATA) expect(await states(window)).toEqual(['CONNECTED', 'CONNECTED'])
    else await connectBoth(window)
    await expectLinkKept(window)
    if (REAL_USER_DATA) {
      // Leave no tokens behind in the runner's Credential Manager.
      await api(window, 'DELETE', '/api/hostinger/token')
      await api(window, 'POST', '/api/github/disconnect')
      expect(await states(window)).toEqual(['NOT_CONNECTED', 'NOT_CONNECTED'])
    }
    await close()
  } finally {
    await close().catch(() => {})
    await wiremock.stop()
    if (existsSync(join(installDir, 'Uninstall Dockline.exe'))) await uninstall(installDir).catch(() => {})
    rmSync(root, { recursive: true, force: true, maxRetries: 5 })
  }
})
