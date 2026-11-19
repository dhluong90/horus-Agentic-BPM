import { defineConfig, devices } from '@playwright/test';

// The rule editing journey against a running example host with the real Rule Engine and the embedded cockpit.
// Start the host first (examples/rule-engine-only/README.md, "Edit rules in the cockpit"), then:
//   LIVE_HOST_URL=http://localhost:8080 LIVE_PASSWORD=<demo password> npm run e2e:live
export default defineConfig({
  testDir: './e2e-live',
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env['LIVE_HOST_URL'] ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chromium' } }],
});
