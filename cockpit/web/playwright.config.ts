import { defineConfig, devices } from '@playwright/test';

// The production build under `vite preview`, which sends the same security headers as the starter.
// Host calls are routed to the mock host fixtures (e2e/mockHost.ts), so no backend is needed.
// COCKPIT_E2E_PORT lets two checkouts run their suites side by side; 4173 by default.
const port = Number(process.env['COCKPIT_E2E_PORT'] ?? 4173);
const baseURL = `http://127.0.0.1:${String(port)}/`;

// The visual regression spec (#587) compares screenshots drawn on Linux, in CI's Playwright image: it has its own run,
// `npm run e2e:visual`, and the other runs leave it out. Its reviewed images are in e2e/visual-baselines.
const visual = process.env['COCKPIT_VISUAL'] === '1';

export default defineConfig({
  testDir: './e2e',
  ...(visual ? { testMatch: '**/visual-regression.spec.ts' } : { testIgnore: '**/visual-regression.spec.ts' }),
  snapshotPathTemplate: '{testDir}/visual-baselines/{arg}{ext}',
  fullyParallel: true,
  forbidOnly: Boolean(process.env['CI']),
  retries: process.env['CI'] ? 1 : 0,
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chromium' } }],
  webServer: {
    command: `npm run build && npx vite preview --host 127.0.0.1 --port ${String(port)} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env['CI'],
    timeout: 120_000,
  },
});
