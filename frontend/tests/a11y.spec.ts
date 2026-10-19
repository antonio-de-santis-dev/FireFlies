// Audit automatico di accessibilità (axe-core, regole WCAG 2.0/2.1/2.2 A e AA) sulle viste principali.
import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { go, loadAllImages, signIn } from './helpers';

const results: Record<string, { id: string; impact: string | null | undefined; nodes: number; help: string }[]> = {};
test.afterAll(() => writeFileSync('tests/a11y-report.json', JSON.stringify(results, null, 1)));

const audit = async (page: import('@playwright/test').Page, name: string) => {
  await page.waitForTimeout(700); // attende la fine delle animazioni d'ingresso (dialog, foto)
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  results[name] = r.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, help: v.help }));
  return r.violations;
};

for (const [vp, w, h] of [['desktop', 1440, 900], ['mobile', 390, 844]] as const) {
  test(`axe ${vp}`, async ({ page }) => {
    await page.setViewportSize({ width: w, height: h });
    const found: string[] = [];
    for (const route of ['/', '/menu', '/eventi', '/eventi/dj-set-esempio', '/galleria', '/locale', '/contatti']) {
      await go(page, route);
      await loadAllImages(page);
      for (const v of await audit(page, `${vp} #${route}`)) found.push(`${route}: ${v.id} (${v.impact}) ×${v.nodes.length}`);
    }
    await go(page, '/menu');
    await page.locator('.menu-item').first().click();
    for (const v of await audit(page, `${vp} #/menu (scheda prodotto)`)) found.push(`scheda: ${v.id}`);
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: /Richiedi un tavolo/ }).last().click();
    for (const v of await audit(page, `${vp} prenotazione`)) found.push(`prenotazione: ${v.id}`);
    await page.keyboard.press('Escape');
    await signIn(page);
    for (const route of ['/gestione', '/gestione/menu', '/gestione/menu/{Tagliere FireFlies}', '/gestione/eventi', '/gestione/richieste', '/gestione/foto', '/gestione/sito', '/gestione/impostazioni']) {
      await go(page, route);
      for (const v of await audit(page, `${vp} #${route}`)) found.push(`${route}: ${v.id} (${v.impact}) ×${v.nodes.length}`);
    }
    expect(found).toEqual([]);
  });
}
