// FIX 2 — verifiche end-to-end con backend e database reali: interruttore «Pubblica sul sito» nell'elenco eventi
// (stato salvato, disabilitato durante il salvataggio, ritorno allo stato precedente in caso di errore, layout desktop e
// telefono), orari degli eventi a qualunque minuto, riepilogo settimanale con il budget della fascia, conferma delle
// chiusure con prenotazioni attive. Screenshot reali allegati.
import { expect, test, type Page } from '@playwright/test';
import { ownerApi } from './baseline';
import { bookableDay, go, horizontalOverflow, publishFromList, publishSwitch, signIn, testPhone, weekSummaryRow } from './helpers';

async function shot(page: Page, name: string) {
  await test.info().attach(name, { body: await page.screenshot({ fullPage: false }), contentType: 'image/png' });
}

const row = (page: Page, title: string) => page.locator('.ev-admin__item', { hasText: title });

async function newDraft(page: Page, title: string, day: string, time: string) {
  await go(page, '/gestione/eventi/nuovo');
  await page.getByLabel('Titolo').fill(title);
  await page.getByLabel('Data').fill(day);
  await page.getByLabel('Orario').fill(time);
  await page.getByRole('button', { name: 'Salva', exact: true }).click();
  await expect(page.locator('.toast')).toContainText(`Bozza «${title}» creata`);
  await expect(row(page, title)).toContainText('Bozza');
}

async function removeEvents(prefix: string) {
  const owner = await ownerApi();
  try {
    const list = await owner.call('GET', '/admin/events') as unknown as { id: string; title: string; status: string; version: number }[];
    for (const e of list.filter((x) => x.title.startsWith(prefix))) await owner.call('DELETE', `/admin/events/${e.id}`).catch(() => undefined);
  } finally {
    await owner.dispose();
  }
}

test.describe('Eventi: interruttore «Pubblica sul sito» e orari al minuto', () => {
  test('orari a qualunque minuto, fine dopo mezzanotte, interruttore disabilitato durante il salvataggio', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    const title = `FIX2 minuto ${Date.now()}`;
    try {
      await signIn(page, 'Staff');
      await go(page, '/gestione/eventi/nuovo');
      await expect(page.getByLabel('Orario')).toHaveAttribute('step', '60');
      await newDraft(page, title, bookableDay(25, 0), '21:58');
      await expect(row(page, title)).toContainText('21:58 – 01:58');

      // salvataggio lento: interruttore disabilitato e «Salvataggio…», poi lo stato salvato
      await page.route('**/api/v1/admin/events/*/visibility', async (r) => { await new Promise((ok) => setTimeout(ok, 700)); await r.continue(); });
      const toggle = publishSwitch(page, title);
      await toggle.click();
      await expect(toggle).toBeDisabled();
      await expect(toggle).toHaveAttribute('aria-busy', 'true');
      await expect(page.locator('.toast')).toContainText(`«${title}» pubblicato sul sito`);
      await expect(toggle).toBeEnabled();
      await expect(toggle).toHaveAttribute('aria-checked', 'true');
      await page.unroute('**/api/v1/admin/events/*/visibility');
      await page.reload();
      await expect(publishSwitch(page, title)).toHaveAttribute('aria-checked', 'true');
      await expect(row(page, title)).toContainText('Pubblicato');
      await shot(page, 'elenco eventi 1366px: interruttore Sul sito');
    } finally {
      await removeEvents('FIX2 minuto');
    }
  });

  test('errore di rete: l\'interruttore torna com\'era e la riga spiega il problema', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    const title = `FIX2 errore ${Date.now()}`;
    try {
      await signIn(page);
      await newDraft(page, title, bookableDay(26, 0), '19:23');
      await page.route('**/api/v1/admin/events/*/visibility', (r) => r.abort('failed'));
      const toggle = publishSwitch(page, title);
      await toggle.click();
      await expect(row(page, title).getByRole('alert')).toContainText('Il server non risponde');
      await expect(toggle).toHaveAttribute('aria-checked', 'false');
      await expect(toggle).toBeEnabled();
      await page.unroute('**/api/v1/admin/events/*/visibility');
      await page.reload();
      await expect(publishSwitch(page, title)).toHaveAttribute('aria-checked', 'false');
      await publishFromList(page, title); // riprovando va
    } finally {
      await removeEvents('FIX2 errore');
    }
  });

  for (const width of [360, 390, 768]) {
    test(`elenco eventi a ${width}px: interruttore visibile, nessuna sovrapposizione, nessuno scorrimento orizzontale`, async ({ page }) => {
      await page.setViewportSize({ width, height: 860 });
      await signIn(page);
      await go(page, '/gestione/eventi');
      const items = page.locator('.ev-admin__item');
      await expect(items.first()).toBeVisible();
      const n = Math.min(await items.count(), 4);
      for (let i = 0; i < n; i++) {
        const item = items.nth(i);
        const sw = item.getByRole('switch');
        if (await sw.count() === 0) continue; // annullato: nessun interruttore
        const name = await sw.getAttribute('aria-label');
        expect(name).toMatch(/^Pubblica sul sito: .+/);
        const s = (await sw.boundingBox())!;
        expect(s.x + s.width).toBeLessThanOrEqual(width);
        expect(s.height).toBeGreaterThanOrEqual(44);
        for (const link of await item.getByRole('link').all()) {
          const l = (await link.boundingBox())!;
          const overlap = s.x < l.x + l.width && l.x < s.x + s.width && s.y < l.y + l.height && l.y < s.y + s.height;
          expect(overlap, `interruttore sovrapposto a «${await link.textContent()}»`).toBe(false);
        }
      }
      expect(await horizontalOverflow(page)).toBe(0);
      await shot(page, `elenco eventi ${width}px`);
    });
  }
});

test.describe('Capienza: budget della fascia e chiusure con prenotazioni', () => {
  test('riepilogo settimanale: confermate su capienza, restanti e disponibili adesso', async ({ page }) => {
    const owner = await ownerApi();
    const day = bookableDay(30, 0);
    let id = '';
    try {
      const res = await owner.call('POST', '/admin/reservations', { source: 'PHONE', date: day, time: '19:00', guests: 3, name: `Budget ${Date.now()}`, phone: testPhone(), confirmNow: true, needsCallback: false });
      id = res.summary.id;
      const capacity = (await owner.call('GET', '/admin/booking-config')).venueCapacity as number;
      await page.setViewportSize({ width: 1366, height: 900 });
      await signIn(page);
      await go(page, '/gestione/impostazioni');
      const r = await weekSummaryRow(page, day, 'Aperitivo');
      const confirmed = Number((await r.locator('td[data-label="Confermate / capienza"]').textContent())!.split('/')[0]);
      expect(confirmed).toBeGreaterThanOrEqual(3);
      await expect(r.locator('td[data-label="Confermate / capienza"]')).toHaveText(`${confirmed} / ${capacity}`);
      await expect(r.locator('td[data-label="Restano nella fascia"]')).toHaveText(String(capacity - confirmed));
      const available = Number((await r.locator('td[data-label="Disponibili adesso"] strong').textContent())!);
      expect(available).toBeLessThanOrEqual(capacity - confirmed);
      await r.scrollIntoViewIfNeeded();
      await shot(page, 'riepilogo settimanale con budget della fascia');
    } finally {
      if (id) {
        const d = await owner.call('GET', `/admin/reservations/${id}`);
        await owner.call('PATCH', `/admin/reservations/${id}/status`, { version: d.summary.version, status: 'CANCELLED', reason: 'OTHER' });
      }
      await owner.dispose();
    }
  });

  test('chiudere un giorno della settimana con prenotazioni attive chiede conferma; «Non chiudere» non salva nulla', async ({ page }) => {
    const owner = await ownerApi();
    const day = bookableDay(31, 0);
    let id = '';
    try {
      const res = await owner.call('POST', '/admin/reservations', { source: 'PHONE', date: day, time: '19:00', guests: 2, name: `Chiusura ${Date.now()}`, phone: testPhone(), confirmNow: true, needsCallback: false });
      id = res.summary.id;
      const dayName = new Intl.DateTimeFormat('it-IT', { weekday: 'long' }).format(new Date(`${day}T12:00:00`));
      await page.setViewportSize({ width: 1366, height: 900 });
      await signIn(page);
      await go(page, '/gestione/impostazioni');
      const toggle = page.getByRole('switch', { name: new RegExp(`^${dayName}: aperto$`, 'i') });
      await toggle.click();
      await page.getByRole('button', { name: 'Salva', exact: true }).click();
      const box = page.getByRole('alertdialog');
      await expect(box).toContainText('prenotazioni attive');
      await expect(box).toContainText('non vengono annullate né rifiutate');
      await shot(page, 'conferma chiusura con prenotazioni');
      await box.getByRole('button', { name: 'Non chiudere' }).click();
      await expect(box).toHaveCount(0);
      const options = await (await page.request.get('/api/v1/public/booking-options')).json();
      expect(options.closedDates).not.toContain(day);
      const after = await owner.call('GET', `/admin/reservations/${id}`);
      expect(after.summary.status).toBe('CONFIRMED');
    } finally {
      if (id) {
        const d = await owner.call('GET', `/admin/reservations/${id}`);
        await owner.call('PATCH', `/admin/reservations/${id}/status`, { version: d.summary.version, status: 'CANCELLED', reason: 'OTHER' });
      }
      await owner.dispose();
    }
  });
});
