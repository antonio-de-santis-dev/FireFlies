import { test } from '@playwright/test';
import { signIn } from './helpers';
const out = '/tmp/claude-0/-home-user-fireflies/172ae791-3e58-5051-8320-ac31f9493a9e/scratchpad/shots';
for (const w of [390, 1366]) {
  test(`esplora ${w}`, async ({ page }) => {
    await page.setViewportSize({ width: w, height: w < 500 ? 844 : 900 });
    await page.goto('/#/locale');
    await page.locator('.story').scrollIntoViewIfNeeded();
    await page.waitForTimeout(400);
    await page.locator('.story').screenshot({ path: `${out}/story-${w}.png` });
    await page.goto('/#/menu');
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${out}/menu-${w}.png`, fullPage: false });
    await page.mouse.wheel(0, 1600);
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${out}/menu-scroll-${w}.png` });
    await signIn(page, 'Titolare');
    await page.goto('/#/gestione/impostazioni');
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${out}/settings-${w}.png`, fullPage: true });
    if (w < 500) {
      await page.screenshot({ path: `${out}/admin-head-${w}.png` });
      await page.locator('.admin-nav-toggle, [aria-controls="admin-nav"], button:has-text("Menu")').first().click().catch(() => {});
      await page.waitForTimeout(300);
      await page.screenshot({ path: `${out}/admin-nav-${w}.png` });
    }
  });
}
