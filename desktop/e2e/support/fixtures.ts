import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { _electron as electron, type ElectronApplication, type Page, test as base } from '@playwright/test'
import { startWireMock, type WireMock } from './wiremock'

const DESKTOP_DIR = join(import.meta.dirname, '..', '..')

/**
 * Nothing in an end-to-end test may reach the real GitHub or Hostinger: by default every external
 * API points at a closed local port. Tests that need an API pass a WireMock URL instead.
 * Tokens are kept in memory, never in the real Windows Credential Manager.
 */
const NO_REAL_APIS = {
  DOCKLINE_GITHUB_API_URL: 'http://127.0.0.1:9',
  DOCKLINE_GITHUB_OAUTH_URL: 'http://127.0.0.1:9',
  DOCKLINE_HOSTINGER_API_URL: 'http://127.0.0.1:9',
  DOCKLINE_TOKEN_STORE: 'memory',
  // The app records the addresses it would open in a browser instead of opening one.
  DOCKLINE_E2E_NO_BROWSER: '1'
}

export interface RunningApp {
  app: ElectronApplication
  window: Page
}

type Fixtures = {
  /** Start the real, built Electron app (out/) with extra environment variables for it and its backend. */
  launchApp: (env?: Record<string, string>) => Promise<RunningApp>
  /** A fresh WireMock server for this test; stopped automatically afterwards. */
  wiremock: WireMock
}

export const test = base.extend<Fixtures>({
  launchApp: async ({}, use) => {
    const apps: ElectronApplication[] = []
    await use(async (env = {}) => {
      const appEnv: Record<string, string> = {}
      for (const [k, v] of Object.entries(process.env)) if (v !== undefined) appEnv[k] = v
      // Always run the built app, never a dev server.
      delete appEnv['ELECTRON_RENDERER_URL']
      delete appEnv['ELECTRON_RUN_AS_NODE']
      // Backend log files go to a fresh temporary folder, never the real app-data folder.
      const logDir = mkdtempSync(join(tmpdir(), 'dockline-e2e-logs-'))
      // The same goes for the settings file (manual site–repo links). A test that restarts the app
      // passes its own DOCKLINE_SETTINGS_DIR to both launches so the settings survive.
      const settingsDir = mkdtempSync(join(tmpdir(), 'dockline-e2e-settings-'))
      const app = await electron.launch({
        args: [DESKTOP_DIR],
        cwd: DESKTOP_DIR,
        env: { ...appEnv, ...NO_REAL_APIS, DOCKLINE_LOG_DIR: logDir, DOCKLINE_SETTINGS_DIR: settingsDir, ...env }
      })
      apps.push(app)
      return { app, window: await app.firstWindow() }
    })
    // Closing the app also stops its backend.
    for (const app of apps) await app.close().catch(() => {})
  },

  wiremock: async ({}, use) => {
    const wiremock = await startWireMock()
    try {
      await use(wiremock)
    } finally {
      await wiremock.stop()
    }
  }
})

export { expect } from '@playwright/test'
