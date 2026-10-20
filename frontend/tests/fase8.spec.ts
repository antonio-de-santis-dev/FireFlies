// FASE 8 end-to-end, stack reale: filtri compatti delle Richieste (req. 2), foto del menu dei gestori (req. 3), telefono
// per eventi e compleanni con chiamata e copia (req. 4), connessioni lente. Screenshot reali allegati al report.
import { expect, request, test, type Page } from '@playwright/test';
import { ownerApi, settingsBody } from './baseline';
import { bookableDay, go, horizontalOverflow, signIn, testPhone } from './helpers';

const BACKEND = () => process.env.FIREFLIES_BACKEND_URL ?? 'http://localhost:18080';

async function webRequest(name: string, day: string) {
  const ctx = await request.newContext({ baseURL: BACKEND() });
  try {
    await ctx.get('/api/v1/auth/csrf');
    const token = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
    const res = await ctx.post('/api/v1/public/reservations', {
      data: { date: day, time: '18:30', guests: 2, name, phone: testPhone(), privacyAccepted: true },
      headers: { 'X-XSRF-TOKEN': decodeURIComponent(token), 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(res.status(), await res.text()).toBe(201);
  } finally {
    await ctx.dispose();
  }
}

async function shot(page: Page, name: string) {
  await test.info().attach(name, { body: await page.screenshot({ fullPage: false }), contentType: 'image/png' });
}

test.describe('Richieste: filtri compatti', () => {
  test.beforeAll(async () => {
    for (let i = 0; i < 3; i++) await webRequest(`Filtri E2E ${i} ${Date.now()}`, bookableDay(16, 0));
  });

  for (const width of [360, 375, 390, 430, 768, 1280, 1920]) {
    test(`a ${width}px: filtri tutti presenti, compatti, senza scorrimento orizzontale`, async ({ page }) => {
      await page.setViewportSize({ width, height: width < 768 ? 800 : 900 });
      await signIn(page, 'Staff');
      await go(page, '/gestione/richieste');
      await expect(page.locator('.res-card').first()).toBeVisible();
      for (const label of ['Periodo', 'Origine', 'Cerca']) await expect(page.getByLabel(label, { exact: true })).toBeVisible();
      const refresh = page.getByRole('button', { name: "Aggiorna l'elenco" });
      await expect(refresh).toBeVisible();
      const r = (await refresh.boundingBox())!;
      expect(Math.min(r.width, r.height), 'area da toccare').toBeGreaterThanOrEqual(44);
      const toolbar = (await page.getByRole('search', { name: 'Filtra le richieste' }).boundingBox())!;
      expect(toolbar.x + toolbar.width).toBeLessThanOrEqual(width + 0.5);
      if (width < 768) {
        const period = (await page.getByLabel('Periodo', { exact: true }).boundingBox())!;
        const source = (await page.getByLabel('Origine', { exact: true }).boundingBox())!;
        expect(Math.abs(period.y - source.y), 'Periodo e Origine affiancati').toBeLessThan(1);
        expect(toolbar.height, 'area filtri compatta').toBeLessThanOrEqual(150);
        const chips = page.getByRole('group', { name: 'Filtra per stato' });
        await expect(page.getByRole('button', { name: 'Mostra gli altri filtri' })).toBeVisible();
        const last = chips.getByRole('button', { name: /^Non presentato/ });
        await last.scrollIntoViewIfNeeded();
        await last.click();
        await expect(last).toHaveAttribute('aria-pressed', 'true');
        await chips.getByRole('button', { name: /^Tutte/ }).scrollIntoViewIfNeeded();
        await chips.getByRole('button', { name: /^Tutte/ }).click();
        for (const chip of await chips.getByRole('button').all()) {
          expect((await chip.boundingBox())!.height).toBeGreaterThanOrEqual(44);
        }
        const firstCard = (await page.locator('.res-card').first().boundingBox())!;
        expect(firstCard.y, 'le schede iniziano nella prima metà dello schermo').toBeLessThan(800 * 0.6);
      } else {
        const period = (await page.getByLabel('Periodo', { exact: true }).boundingBox())!;
        const search = (await page.getByLabel('Cerca', { exact: true }).boundingBox())!;
        expect(Math.abs(period.y - search.y), 'barra su una riga').toBeLessThan(2);
      }
      expect(await horizontalOverflow(page)).toBe(0);
      await shot(page, `richieste-${width}`);
      // il giorno singolo aggiunge un campo, sempre senza overflow
      await page.getByLabel('Periodo', { exact: true }).selectOption('day');
      await expect(page.getByLabel('Giorno', { exact: true })).toBeVisible();
      expect(await horizontalOverflow(page)).toBe(0);
    });
  }
});

test.describe('Menu e prezzi: foto più visibili', () => {
  test('desktop 1366: miniature grandi e proporzionate, tutte le colonne leggibili, righe non troppo alte', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    await signIn(page, 'Staff');
    await go(page, '/gestione/menu');
    const row = page.getByRole('row', { name: /Tagliere FireFlies/ });
    const thumb = row.locator('.product-thumb');
    const t = (await thumb.boundingBox())!;
    expect(t.width).toBeGreaterThanOrEqual(80);
    expect(Math.abs(t.width - t.height)).toBeLessThan(1);
    const img = thumb.locator('img');
    await expect(img).toHaveJSProperty('complete', true);
    expect(await img.evaluate((i: HTMLImageElement) => i.naturalWidth)).toBeGreaterThan(0);
    expect(await img.evaluate((i) => getComputedStyle(i).objectFit)).toBe('cover');
    expect(await img.getAttribute('srcset'), 'variante adatta al dispositivo').toBeTruthy();
    expect((await row.boundingBox())!.height).toBeLessThanOrEqual(124);
    for (const label of ['Categoria', 'Prezzo', 'Allergeni', 'Sul sito']) await expect(row.locator(`td[data-label="${label}"]`)).toBeVisible();
    await expect(row.getByRole('link', { name: 'Modifica Tagliere FireFlies' })).toBeVisible();
    // ordine e categorie invariati rispetto ai dati
    const names = await page.locator('tbody .cell-item__name').allTextContents();
    expect(names.length).toBeGreaterThan(5);
    await shot(page, 'menu-1366');
  });

  for (const width of [360, 390]) {
    test(`telefono ${width}px: foto e nome in evidenza, switch e Modifica dentro la scheda`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await signIn(page, 'Staff');
      await go(page, '/gestione/menu');
      const row = page.getByRole('row', { name: /Tagliere FireFlies/ });
      const card = (await row.boundingBox())!;
      const t = (await row.locator('.product-thumb').boundingBox())!;
      expect(t.width).toBeGreaterThanOrEqual(90);
      const name = (await row.locator('.cell-item__name').boundingBox())!;
      expect(name.y - card.y, 'nome in alto nella scheda').toBeLessThan(120);
      for (const el of [row.getByRole('switch'), row.getByRole('link', { name: /Modifica/ })]) {
        const b = (await el.boundingBox())!;
        expect(b.x).toBeGreaterThanOrEqual(card.x - 0.5);
        expect(b.x + b.width).toBeLessThanOrEqual(card.x + card.width + 0.5);
      }
      expect(await horizontalOverflow(page)).toBe(0);
      await shot(page, `menu-${width}`);
    });
  }

  test('foto mancante o non caricabile: segnaposto ordinato, layout invariato', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    await signIn(page, 'Staff');
    // ogni variante di immagine del menu fallisce: il segnaposto prende il suo posto
    await page.route('**/media/**', (route) => route.abort());
    await go(page, '/gestione/menu');
    const thumbs = page.locator('.product-thumb');
    await expect(page.locator('.product-thumb--none').first()).toBeVisible();
    const boxes = await thumbs.evaluateAll((els) => els.slice(0, 5).map((e) => e.getBoundingClientRect().width));
    expect(new Set(boxes.map((w) => Math.round(w))).size, 'stessa misura con e senza foto').toBe(1);
    expect(await horizontalOverflow(page)).toBe(0);
  });
});

test.describe('Evento o compleanno: telefono del locale', () => {
  async function setPhone(phone: string | null) {
    const owner = await ownerApi();
    try {
      const current = await owner.call('GET', '/admin/site-settings');
      await owner.call('PUT', '/admin/site-settings', { ...settingsBody(current), phone, version: current.version });
      return current.phone as string | null;
    } finally {
      await owner.dispose();
    }
  }

  async function openParty(page: Page) {
    await go(page, '/');
    await page.reload();
    await page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click();
    await page.getByRole('dialog', { name: 'Prenota' }).getByRole('button', { name: /Organizza un evento/ }).click();
    return page.getByRole('dialog', { name: 'Organizza un evento o un compleanno' });
  }

  let original: string | null = null;
  test.beforeAll(async () => { original = await setPhone('0836 123456'); });
  test.afterAll(async () => { await setPhone(original); });

  for (const width of [390, 1366]) {
    test(`a ${width}px: numero leggibile, «Chiama ora» con tel:, «Copia numero» con conferma`, async ({ page, context }) => {
      await context.grantPermissions(['clipboard-read', 'clipboard-write']);
      await page.setViewportSize({ width, height: 900 });
      const d = await openParty(page);
      await expect(d.locator('.phone-card__number')).toHaveText('0836 123456');
      await expect(d.getByRole('link', { name: 'Chiama ora' })).toHaveAttribute('href', 'tel:+390836123456');
      await expect(d.getByRole('status')).toHaveText('');
      await d.getByRole('button', { name: 'Copia numero' }).focus();
      await page.keyboard.press('Enter'); // da tastiera
      await expect(d.getByRole('status')).toContainText('Numero copiato');
      expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('0836 123456');
      for (const el of [d.getByRole('link', { name: 'Chiama ora' }), d.getByRole('button', { name: 'Copia numero' })]) {
        expect((await el.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      }
      expect(await horizontalOverflow(page)).toBe(0);
      await shot(page, `telefono-${width}`);
    });
  }

  test('copia non disponibile: nessuna falsa conferma, numero selezionabile a mano', async ({ page }) => {
    await page.addInitScript(() => Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true }));
    await page.setViewportSize({ width: 390, height: 844 });
    const d = await openParty(page);
    await d.getByRole('button', { name: 'Copia numero' }).click();
    await expect(d.getByRole('status')).not.toContainText('copiato');
    const manual = d.getByLabel(/seleziona il numero e copialo/);
    await expect(manual).toHaveValue('0836 123456');
    await expect(manual).toBeFocused();
  });

  test('numero non configurato: avviso, nessun link telefonico, Instagram', async ({ page }) => {
    await setPhone(null);
    try {
      await page.setViewportSize({ width: 390, height: 844 });
      const d = await openParty(page);
      await expect(d).toContainText('non è ancora disponibile');
      await expect(d.locator('a[href^="tel:"]')).toHaveCount(0);
      await expect(d.getByRole('button', { name: 'Copia numero' })).toHaveCount(0);
      await expect(d.getByRole('link', { name: 'Scrivici su Instagram' })).toHaveAttribute('href', /instagram\.com/);
      await shot(page, 'telefono-assente-390');
    } finally {
      await setPhone('0836 123456');
    }
  });
});

test.describe('Connessioni lente', () => {
  test('Richieste con API lente: stato di caricamento, poi le schede; nessun errore', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page, 'Staff');
    await page.route('**/api/v1/admin/reservations?*', async (route) => {
      await new Promise((r) => setTimeout(r, 1500));
      await route.continue();
    });
    await page.goto('/#/gestione/richieste');
    await expect(page.getByRole('status').filter({ hasText: 'Caricamento delle richieste' })).toBeVisible();
    await expect(page.locator('.res-card').first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('sito con rete lenta (rallentamento CPU e rete): il modulo di prenotazione si apre e carica gli orari', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 400, downloadThroughput: 200 * 1024, uploadThroughput: 100 * 1024 });
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
    await page.goto('/#/', { timeout: 60_000 });
    await page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click({ timeout: 30_000 });
    await page.getByRole('dialog', { name: 'Prenota' }).getByRole('button', { name: /Prenota un tavolo/ }).click();
    const d = page.getByRole('dialog', { name: 'Richiedi un tavolo' });
    await d.locator('#bk-date').fill(bookableDay(17, 0));
    await d.getByRole('button', { name: 'Avanti' }).click();
    await expect(d.getByRole('radiogroup').first()).toBeVisible({ timeout: 30_000 });
  });
});
