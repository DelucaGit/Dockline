import { defineConfig } from '@playwright/test'

// Installer smoke test: installs the built installer (dist/), starts the INSTALLED app, uninstalls and
// reinstalls it, and checks that the user's settings survive. Separate from the normal end-to-end tests.
// Run with `npm run test:installed` after `npm run dist` (Windows only).
export default defineConfig({
  testDir: 'e2e-installed',
  workers: 1,
  fullyParallel: false,
  // Three installs, one uninstall and four app starts with a cold Java backend.
  timeout: 600_000,
  expect: { timeout: 70_000 },
  forbidOnly: !!process.env['CI'],
  retries: 0,
  reporter: 'list',
  outputDir: 'test-results/installed'
})
