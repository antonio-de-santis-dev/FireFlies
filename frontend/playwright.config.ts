import { defineConfig } from '@playwright/test';

// I test girano sulla build di produzione servita da `vite preview`:
// gli screenshot mostrano esattamente lo stesso HTML che si apre con `npm run preview`.
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4173/',
    reducedMotion: 'reduce',
    locale: 'it-IT',
    timezoneId: 'Europe/Rome',
    // input data/ora nel formato italiano (gg/mm/aaaa, 24h)
    launchOptions: { args: ['--lang=it-IT'], env: { ...process.env, LANG: 'it_IT.UTF-8', LANGUAGE: 'it' } },
  },
  webServer: {
    command: 'npx vite build && npx vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173/',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
