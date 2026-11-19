import { defineConfig, devices } from '@playwright/test';

// The production build under `vite preview`, which sends the same security headers as the starter.
// Host calls are routed to the mock host fixtures (e2e/mockHost.ts), so no backend is needed.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env['CI']),
  retries: process.env['CI'] ? 1 : 0,
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173/',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chromium' } }],
  webServer: {
    command: 'npm run build && npx vite preview --host 127.0.0.1',
    url: 'http://127.0.0.1:4173/',
    reuseExistingServer: !process.env['CI'],
    timeout: 120_000,
  },
});
