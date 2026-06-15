import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E configuration for the Flash Sale Concurrency Engine dashboard.
 *
 * Targets the Next.js dev server (default http://localhost:3000) which proxies
 * API calls to the Spring Boot backend (default http://localhost:1122).
 *
 * Prerequisites:
 *   docker compose -f environment/docker-compose-dev.yml up -d mysql redis kafka
 *   cd app/backend && mvn spring-boot:run -pl xxxx-start
 *   cd app/frontend && npm run dev
 *
 * Run: npx playwright test
 * Run UI: npx playwright test --ui
 * Debug: npx playwright test --debug
 */
export default defineConfig({
  testDir: './e2e/specs',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [
    ['html', { outputFolder: 'e2e/reports' }],
    ['list'],
  ],
  timeout: 60_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // The Next.js dev server must already be running.
  // webServer config omitted intentionally — start it separately so the
  // same server can be reused across multiple test runs during development.
});
