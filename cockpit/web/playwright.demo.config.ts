import { defineConfig, devices } from '@playwright/test';

// Demo against a running example host (examples/rule-engine-only on the dev profile), not run in CI.
// COCKPIT_DEMO_URL=http://localhost:8080 HORUS_DEMO_PASSWORD=... npx playwright test -c playwright.demo.config.ts
export default defineConfig({
  testDir: './demo',
  testMatch: '*.demo.ts',
  workers: 1,
  reporter: 'list',
  outputDir: 'demo/output',
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chromium',
    baseURL: process.env['COCKPIT_DEMO_URL'] ?? 'http://localhost:8080',
    viewport: { width: 1440, height: 900 },
  },
});
