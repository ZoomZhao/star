import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests/browser',
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:5174',
    browserName: 'chromium',
    channel: 'chrome',
    trace: 'retain-on-failure',
  },
  reporter: 'list',
  webServer: [
    {
      command: 'node server/index.mjs',
      url: 'http://127.0.0.1:3002/api/health',
      env: {
        APP_ENV: 'development',
        PORT: '3002',
        DATABASE_PATH: ':memory:',
        BACKUP_DIR: './test-results/backups',
        ADMIN_PASSWORD: 'local-admin-2026',
        DEMO_MODE: 'true',
        APP_ORIGIN: 'http://127.0.0.1:5174',
      },
    },
    {
      command: 'npm run dev -- --port 5174 --strictPort',
      url: 'http://127.0.0.1:5174',
      env: { STAR_API_PROXY: 'http://127.0.0.1:3002' },
    },
  ],
});
