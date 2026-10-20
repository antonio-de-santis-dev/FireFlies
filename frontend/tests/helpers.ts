import { expect, request, type Page } from '@playwright/test';

export const PUBLIC_ROUTES = ['/', '/menu', '/eventi', '/eventi/dj-set-esempio', '/eventi/domeniche-del-fire', '/galleria', '/locale', '/contatti'];
// {Nome} = voce del menu: l'id (UUID del backend) si ricava al momento dal menu pubblico
export const ADMIN_ROUTES = ['/gestione', '/gestione/menu', '/gestione/menu/{Tagliere FireFlies}', '/gestione/eventi', '/gestione/eventi/dj-set-esempio', '/gestione/richieste', '/gestione/oggi', '/gestione/foto', '/gestione/comunicazioni', '/gestione/sito', '/gestione/impostazioni'];

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

/** Id di una voce del menu dal suo nome (gli id sono UUID del backend, non più nomi leggibili). */
export async function menuItemId(name: string): Promise<string> {
  const ctx = await request.newContext({ baseURL: process.env.FIREFLIES_BACKEND_URL ?? 'http://localhost:18080' });
  try {
    const menu = await (await ctx.get('/api/v1/public/menu')).json();
    const item = menu.categories.flatMap((c: { items: { id: string; name: string }[] }) => c.items).find((i: { name: string }) => i.name === name);
    if (!item) throw new Error(`Voce di menu non trovata: ${name}`);
    return item.id;
  } finally {
    await ctx.dispose();
  }
}

export async function go(page: Page, route: string) {
  const name = route.match(/\{(.+)\}/)?.[1];
  const resolved = name ? route.replace(`{${name}}`, await menuItemId(name)) : route;
  await page.goto(`/#${resolved}`);
  await page.waitForLoadState('networkidle');
}

/** Credenziali generate a ogni esecuzione da scripts/e2e.mjs (backend reale): mai scritte nel codice. */
export function credentials(role: 'Titolare' | 'Staff') {
  const prefix = role === 'Titolare' ? 'E2E_OWNER' : 'E2E_STAFF';
  const email = process.env[`${prefix}_EMAIL`];
  const password = process.env[`${prefix}_PASSWORD`];
  if (!email || !password) throw new Error('Credenziali e2e mancanti: avvia i test con «npm test» (scripts/e2e.mjs).');
  return { email, password };
}

/** Accesso reale: il ruolo dipende dall'account, non da una scelta nella pagina. */
export async function signIn(page: Page, role: 'Titolare' | 'Staff' = 'Titolare') {
  const { email, password } = credentials(role);
  await go(page, '/gestione/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Accedi' }).click();
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

/**
 * Giorno prenotabile con i dati demo (fasce in tutti i giorni tranne il mercoledì, chiuso): almeno `from` giorni
 * dopo oggi, così l'anticipo minimo (60 minuti) non conta. `skip` sceglie un giorno diverso per test diversi.
 */
export function bookableDay(from = 2, skip = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + from);
  let found = 0;
  for (;;) {
    if (d.getDay() !== 3 && found++ === skip) break;
    d.setDate(d.getDate() + 1);
  }
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 10);
}

/** Numero di prova sempre diverso (il backend accetta al massimo 3 richieste al giorno dallo stesso numero). */
export function testPhone(): string {
  return `+39 300 ${String(Date.now()).slice(-7, -4)} ${String(Date.now()).slice(-4)}`;
}

/** Apre il dialog di prenotazione dal pulsante indicato e sceglie «Prenota un tavolo» (FASE 7, req. 10). */
export async function openTableBooking(page: Page, open: () => Promise<void>) {
  await open();
  await page.getByRole('dialog', { name: 'Prenota' }).getByRole('button', { name: /Prenota un tavolo/ }).click();
  return page.getByRole('dialog', { name: 'Richiedi un tavolo' });
}

/** Una richiesta nella pagina Richieste (FASE 7: schede, non più righe di tabella). */
export function requestCard(page: Page, name: string | RegExp) {
  return page.getByRole('article', { name: typeof name === 'string' ? new RegExp(name) : name });
}
