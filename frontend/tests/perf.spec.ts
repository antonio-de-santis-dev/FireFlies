// Misure di performance riproducibili (FASE 8, req. 6) sullo stack reale: stessa macchina, stesso profilo di rete
// (telefono su rete mobile lenta: 150 ms di latenza, 1,6 Mbit/s, CPU ×4). I numeri vanno nel report (perf-results.json).
import { expect, test, type Page } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { signIn } from './helpers';

const results: Record<string, unknown> = {};
test.afterAll(() => writeFileSync('tests/perf-results.json', JSON.stringify(results, null, 1)));

async function throttle(page: Page) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 150, downloadThroughput: 200 * 1024, uploadThroughput: 90 * 1024 });
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  return cdp;
}

/** Byte trasferiti per tipo (dalla Resource Timing del browser) e tempi della pagina. */
async function measure(page: Page) {
  return page.evaluate(() => {
    const res = performance.getEntriesByType('resource') as PerformanceResourceTiming[];
    const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming;
    const by = (f: (r: PerformanceResourceTiming) => boolean) => res.filter(f).reduce((n, r) => n + r.transferSize, 0);
    return {
      requests: res.length,
      jsBytes: by((r) => r.name.endsWith('.js')),
      cssBytes: by((r) => r.name.endsWith('.css')),
      imageBytes: by((r) => /\.(jpe?g|png|webp|avif)(\?|$)/.test(r.name)),
      apiCalls: res.filter((r) => r.name.includes('/api/')).map((r) => new URL(r.name).pathname + new URL(r.name).search),
      domContentLoadedMs: Math.round(nav.domContentLoadedEventEnd),
      loadMs: Math.round(nav.loadEventEnd),
    };
  });
}

test.describe('Performance', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('home pubblica su telefono e rete lenta', async ({ page }) => {
    await throttle(page);
    const started = Date.now();
    await page.goto('/#/', { timeout: 90_000 });
    await expect(page.locator('.hero h1, .hero__title').first()).toBeVisible({ timeout: 60_000 });
    const contentMs = Date.now() - started;
    await page.waitForLoadState('load');
    results.home = { ...(await measure(page)), contentVisibleMs: contentMs };
  });

  test('panoramica dei gestori: chiamate API, duplicati', async ({ page }) => {
    await signIn(page, 'Staff');
    const api: string[] = [];
    const bytes: Record<string, number> = { js: 0 };
    page.on('request', (r) => { if (r.url().includes('/api/')) api.push(`${r.method()} ${new URL(r.url()).pathname}${new URL(r.url()).search}`); });
    page.on('response', async (r) => { if (r.url().endsWith('.js')) bytes.js += Number(r.headers()['content-length'] ?? 0); });
    const started = Date.now();
    await page.goto('/#/gestione');
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Richieste da gestire' })).toBeVisible();
    const readyMs = Date.now() - started;
    await page.waitForTimeout(1500);
    const counts: Record<string, number> = {};
    api.forEach((p) => { counts[p] = (counts[p] ?? 0) + 1; });
    results.dashboard = { apiCalls: api.length, distinct: Object.keys(counts).length, readyMs,
      duplicates: Object.fromEntries(Object.entries(counts).filter(([, n]) => n > 1)), calls: counts };
  });
});
