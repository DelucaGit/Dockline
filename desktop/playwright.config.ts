import { defineConfig } from '@playwright/test'

// End-to-end tests: Playwright drives the real Electron app (real React + real Java backend).
// Run with `npm run test:e2e` (builds the app first; build the backend jar before that).
export default defineConfig({
  testDir: 'e2e',
  // Each test starts its own Electron app and Java backend: run them one at a time.
  workers: 1,
  fullyParallel: false,
  // The backend may take a while to start on a cold CI machine (the app itself waits up to 60 s).
  timeout: 120_000,
  expect: { timeout: 70_000 },
  forbidOnly: !!process.env['CI'],
  retries: 0,
  reporter: 'list',
  outputDir: 'test-results'
})
