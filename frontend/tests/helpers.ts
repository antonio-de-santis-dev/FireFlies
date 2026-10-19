import { expect, type Page } from '@playwright/test';

export const PUBLIC_ROUTES = ['/', '/menu', '/eventi', '/eventi/dj-set-esempio', '/eventi/domeniche-del-fire', '/galleria', '/locale', '/contatti'];
export const ADMIN_ROUTES = ['/gestione', '/gestione/menu', '/gestione/menu/tagliere', '/gestione/eventi', '/gestione/eventi/dj-set-esempio', '/gestione/richieste', '/gestione/foto', '/gestione/comunicazioni', '/gestione/sito', '/gestione/impostazioni'];

/** Raccoglie errori di console, eccezioni e richieste fallite. */
export function watchProblems(page: Page) {
  const problems: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error' || m.type() === 'warning') problems.push(`console.${m.type()}: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  page.on('requestfailed', (r) => problems.push(`requestfailed: ${r.url()}`));
  page.on('response', (r) => r.status() >= 400 && problems.push(`HTTP ${r.status()}: ${r.url()}`));
  return problems;
}

export async function go(page: Page, route: string) {
  await page.goto(`/#${route}`);
  await page.waitForLoadState('networkidle');
}

export async function signIn(page: Page, role: 'Titolare' | 'Staff' = 'Titolare') {
  await go(page, '/gestione/login');
  await page.getByRole('button', { name: 'Compila con dati demo' }).click();
  await page.getByRole('radio', { name: new RegExp(role) }).check();
  await page.getByRole('button', { name: 'Entra nella demo' }).click();
  await expect(page.getByRole('heading', { level: 1, name: /Ecco la giornata/ })).toBeVisible();
}

/** Scorre la pagina per attivare il lazy loading e attende le immagini visibili. */
export async function loadAllImages(page: Page) {
  await page.evaluate(async () => {
    for (let y = 0; y < document.body.scrollHeight; y += 400) {
      window.scrollTo(0, y);
      await new Promise((r) => setTimeout(r, 50));
    }
    window.scrollTo(0, 0);
  });
  await page.waitForLoadState('networkidle');
  await page.evaluate(() =>
    Promise.race([
      new Promise((r) => setTimeout(r, 5000)),
      Promise.all(
        [...document.images]
          .filter((i) => i.offsetParent !== null)
          .map((i) => (i.complete ? 0 : new Promise((r) => (i.onload = i.onerror = r)))),
      ),
    ]),
  );
  await page.evaluate(() => document.fonts.ready);
  await page.locator('.skeleton').first().waitFor({ state: 'detached' }).catch(() => {});
}

/** Immagini visibili rotte (caricate ma con larghezza naturale 0). */
export async function brokenImages(page: Page) {
  return page.evaluate(() =>
    [...document.images].filter((i) => i.offsetParent !== null && i.complete && i.naturalWidth === 0).map((i) => i.src),
  );
}

export async function horizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
}

/** Google non è raggiungibile dall'ambiente di test: la richiesta dell'iframe viene risposta localmente
 *  con una pagina che lo dichiara (nessuna mappa finta). */
export async function stubGoogleMaps(page: Page) {
  await page.route(/maps\.google\.com/, (route) =>
    route.fulfill({
      contentType: 'text/html; charset=utf-8',
      body: '<body style="margin:0;display:grid;place-items:center;height:100vh;font:600 16px system-ui;background:#e8e4dc;color:#333;text-align:center;padding:16px">Qui il browser carica Google Maps.<br>Nell\'ambiente di test Google non è raggiungibile.</body>',
    }),
  );
}
