import { defineConfig } from '@playwright/test'

// These suites write through a real backend and require an isolated integration environment.
const liveTests =
  /[\\/](?:adoption|catalog|credential-transport|data-management|development|operator-refresh|platform|responsive|upgrade|workbench)\.spec\.ts$/
export default defineConfig({
  testDir: './tests',
  forbidOnly: !!process.env.CI,
  projects: [
    { name: 'fixture', testIgnore: liveTests },
    { name: 'live', testMatch: liveTests },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run dev -- --port 5173 --strictPort',
        url: 'http://127.0.0.1:5173',
        reuseExistingServer: !process.env.CI,
        timeout: 60000,
      },
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:5173',
    browserName: 'chromium',
    launchOptions: process.env.E2E_BROWSER ? { executablePath: process.env.E2E_BROWSER } : {},
    viewport: { width: 1920, height: 1080 },
    screenshot: 'only-on-failure',
    storageState: process.env.E2E_THEME
      ? {
          cookies: [],
          origins: [
            {
              origin: new URL(process.env.E2E_BASE_URL || 'http://127.0.0.1:5173').origin,
              localStorage: [{ name: 'flink-ui-theme', value: process.env.E2E_THEME }],
            },
          ],
        }
      : undefined,
  },
})
