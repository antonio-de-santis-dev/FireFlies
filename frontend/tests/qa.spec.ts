// Controlli di qualità su tutti i breakpoint: overflow orizzontale, immagini mancanti,
// errori console/rete, dimensione dei target touch, reduced motion. Scrive tests/qa-report.json.
import { expect, test } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { ADMIN_ROUTES, brokenImages, go, horizontalOverflow, loadAllImages, PUBLIC_ROUTES, signIn, watchProblems } from './helpers';

const WIDTHS = [360, 375, 390, 768, 1366, 1440, 1920];
const report: Record<string, unknown>[] = [];

test.afterAll(() => {
  writeFileSync('tests/qa-report.json', JSON.stringify(report, null, 1));
});

for (const width of WIDTHS) {
  test(`breakpoint ${width}px: overflow, immagini, console, target touch`, async ({ page }) => {
    await page.setViewportSize({ width, height: width < 768 ? (width === 390 ? 844 : width === 375 ? 812 : 800) : width === 1366 ? 768 : width === 1920 ? 1080 : 900 });
    const problems = watchProblems(page);
    const failures: string[] = [];

    const check = async (route: string) => {
      await go(page, route);
      await loadAllImages(page);
      const overflow = await horizontalOverflow(page);
      const broken = await brokenImages(page);
      // target interattivi visibili più piccoli di 24px (minimo WCAG 2.5.8) e di 44px (obiettivo del progetto)
      const targets = await page.evaluate(() =>
        [...document.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea, [role="switch"]')]
          .filter((el) => el.offsetParent !== null && !el.classList.contains('sr-only') && getComputedStyle(el).visibility !== 'hidden')
          .map((el) => {
            const r = el.getBoundingClientRect();
            const name = (el.getAttribute('aria-label') || el.textContent || el.getAttribute('id') || el.tagName).trim().slice(0, 40);
            const inline = el.tagName === 'A' && getComputedStyle(el).display === 'inline'; // link nel testo: esenti (2.5.8)
            return { name, w: Math.round(r.width), h: Math.round(r.height), inline, type: (el as HTMLInputElement).type };
          })
          .filter((t) => !t.inline && t.type !== 'checkbox' && t.type !== 'radio' && (t.w < 44 || t.h < 44)),
      );
      const under24 = targets.filter((t) => t.w < 24 || t.h < 24);
      report.push({ width, route, overflow, broken, under44: targets, under24 });
      if (overflow > 0) failures.push(`${route}: overflow orizzontale ${overflow}px`);
      if (broken.length) failures.push(`${route}: immagini rotte ${broken.join(', ')}`);
      if (under24.length) failures.push(`${route}: target < 24px ${JSON.stringify(under24)}`);
    };

    for (const r of PUBLIC_ROUTES) await check(r);
    await signIn(page);
    for (const r of ADMIN_ROUTES) await check(r);

    expect(problems, 'errori console/rete').toEqual([]);
    expect(failures).toEqual([]);
  });
}

test('reduced motion: contenuti visibili senza animazioni', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await go(page, '/');
  expect(await page.evaluate(() => document.documentElement.classList.contains('reveal-ready'))).toBe(false);
  const hidden = await page.locator('.reveal').evaluateAll((els) => els.filter((e) => getComputedStyle(e).opacity !== '1').length);
  expect(hidden).toBe(0);
});

test('movimento attivo: le sezioni entrano allo scroll e restano visibili', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await go(page, '/');
  await expect(page.locator('html')).toHaveClass(/reveal-ready/);
  await loadAllImages(page);
  await page.waitForTimeout(900);
  const notIn = await page.locator('.reveal:not(.is-in)').count();
  expect(notIn).toBe(0);
});
