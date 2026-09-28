import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:5173',
    browserName: 'chromium',
    launchOptions: process.env.E2E_BROWSER ? { executablePath: process.env.E2E_BROWSER } : {},
    viewport: { width: 1920, height: 1080 },
    screenshot: 'only-on-failure',
  },
})
