// FIX 3 — regressioni del frontend con backend e database reali: dati dei gestori conservati dopo una modifica agli
// eventi (F-01), interruttore delle richieste non riaperto dal pannello «Regole» (F-02), «Oggi» con risposte fuori
// ordine (F-03), campi della capienza non cancellati da altre azioni (F-04), virgole negli ingredienti (F-05), dialog
// di prenotazione non chiudibile durante l'invio (F-06), foto: conferma prima di eliminare e una modifica alla volta
// (F-08, F-09).
import { expect, test, type Page } from '@playwright/test';
import { keepBaseline, ownerApi } from './baseline';
import { bookableDay, go, openTableBooking, publishSwitch, signIn, testPhone } from './helpers';

keepBaseline();

const nav = (page: Page, label: string) => page.getByRole('navigation', { name: 'Area gestori' }).getByRole('link', { name: label, exact: true });

test('F-01: pubblicare un evento non cancella foto e voci di menu nascoste dell\'area gestori', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  const owner = await ownerApi();
  const title = `FIX3 F01 ${Date.now()}`;
  let eventId = '';
  try {
    // una foto fuori dalla galleria: nell'area gestori c'è, nel sito pubblico no
    const library = (await owner.call('GET', '/admin/media')) as unknown as { id: string; inGallery: boolean }[];
    await owner.call('PATCH', `/admin/media/${library[0].id}`, { inGallery: false });
    const draft = await owner.call('POST', '/admin/events', { title, kind: 'SERATA', date: bookableDay(40, 0), startTime: '21:00', bookingMode: 'NONE', mediaIds: [] });
    eventId = draft.id as string;

    await signIn(page);
    await go(page, '/gestione/foto');
    const photos = page.locator('.photos__item');
    await expect(photos.first()).toBeVisible();
    const before = await photos.count();
    expect(before).toBe(library.length);

    // navigazione interna (senza ricaricare): Eventi → pubblica → Foto
    await nav(page, 'Eventi').click();
    await publishSwitch(page, title).click();
    await expect(page.locator('.toast')).toContainText(`«${title}» pubblicato sul sito`);
    await nav(page, 'Foto').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Foto' })).toBeVisible();
    await expect(photos).toHaveCount(before);
  } finally {
    if (eventId) await owner.call('DELETE', `/admin/events/${eventId}`).catch(() => undefined);
    await owner.dispose();
  }
});

test('F-02: salvare le regole non riapre le richieste dal sito spente con l\'interruttore', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  const owner = await ownerApi();
  const original = (await owner.call('GET', '/admin/booking-config')).policy as Record<string, unknown>;
  try {
    await signIn(page);
    await go(page, '/gestione/impostazioni');
    const toggle = page.getByRole('switch', { name: /Richieste tavolo (attive|disattivate)/ });
    // l'interruttore parte «spento» finché non arrivano le regole: si aspetta lo stato salvato sul server
    await expect(toggle).toHaveAttribute('aria-checked', String(original.webBookingEnabled));
    if (!original.webBookingEnabled) {
      await toggle.click();
      await expect(page.locator('.toast')).toContainText('Richieste dal sito aperte');
    }
    await toggle.click();
    await expect(page.locator('.toast')).toContainText('Richieste dal sito chiuse');
    await page.locator('#pol-lead').fill('75');
    await page.getByRole('button', { name: 'Salva le regole' }).click();
    await expect(page.locator('.toast')).toContainText('Regole delle richieste salvate');
    const saved = (await owner.call('GET', '/admin/booking-config')).policy as { webBookingEnabled: boolean; minLeadMinutes: number };
    expect(saved.minLeadMinutes).toBe(75);
    expect(saved.webBookingEnabled, 'le richieste restano chiuse').toBe(false);

    // e al contrario: l'interruttore non rimanda indietro le regole appena salvate
    await page.getByRole('switch', { name: /Richieste tavolo/ }).click();
    await expect(page.locator('.toast')).toContainText('Richieste dal sito aperte');
    const after = (await owner.call('GET', '/admin/booking-config')).policy as { webBookingEnabled: boolean; minLeadMinutes: number };
    expect(after).toMatchObject({ webBookingEnabled: true, minLeadMinutes: 75 });
  } finally {
    await owner.call('PUT', '/admin/booking-policy', {
      webBookingEnabled: original.webBookingEnabled, minLeadMinutes: original.minLeadMinutes, maxAdvanceDays: original.maxAdvanceDays,
      pendingExpiryMinutesBeforeStart: original.pendingExpiryMinutesBeforeStart,
    });
    await owner.dispose();
  }
});

test('F-03: «Oggi» mostra la fascia scelta anche se la risposta precedente arriva dopo', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  const today = (band: string, name: string) => ({
    date: '2026-10-28', band, reservations: 1, guests: 2,
    bands: [{ band: 'COLAZIONE', label: 'Colazione', reservations: 1, guests: 2 }, { band: 'SERA', label: 'Sera', reservations: 1, guests: 2 }],
    items: [{ id: band, reference: `FF-${band}`, time: band === 'SERA' ? '21:00:00' : '08:00:00', name, guests: 2, band, bandLabel: band, event: null }],
  });
  await signIn(page);
  await page.route('**/api/v1/admin/reservations/today?band=COLAZIONE', async (r) => {
    await new Promise((ok) => setTimeout(ok, 1500));
    await r.fulfill({ json: today('COLAZIONE', 'Risposta lenta di colazione') });
  });
  await page.route('**/api/v1/admin/reservations/today?band=SERA', (r) => r.fulfill({ json: today('SERA', 'Risposta della sera') }));
  await go(page, '/gestione/oggi');
  await page.getByRole('button', { name: /^Colazione/ }).click();
  await page.getByRole('button', { name: /^Sera/ }).click();
  await expect(page.getByText('Risposta della sera')).toBeVisible();
  await page.waitForTimeout(1800); // la risposta della colazione arriva adesso
  await expect(page.getByRole('button', { name: /^Sera/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('Risposta della sera')).toBeVisible();
  await expect(page.getByText('Risposta lenta di colazione')).toHaveCount(0);
});

test('F-04: aggiungere una data straordinaria non cancella le regole che si stanno scrivendo', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  await signIn(page);
  await go(page, '/gestione/impostazioni');
  const lead = page.locator('#pol-lead');
  await lead.fill('95');
  // un'altra azione della sezione che ricarica la configurazione: salvare la capienza con lo stesso valore
  await page.getByRole('button', { name: 'Salva la capienza' }).click();
  await expect(page.locator('.toast')).toBeVisible();
  await page.waitForLoadState('networkidle');
  await expect(lead).toHaveValue('95');
});

test('F-05: negli ingredienti si scrivono le virgole', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  await signIn(page);
  await go(page, '/gestione/menu');
  await page.getByRole('link', { name: 'Nuova voce' }).click();
  const field = page.locator('#me-ingr');
  await field.pressSequentially('pane, burro, marmellata');
  await expect(field).toHaveValue('pane, burro, marmellata');
});

test('F-06: durante l\'invio il dialog non si chiude e la richiesta resta una sola', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const name = `FIX3 F06 ${Date.now()}`;
  await page.route('**/api/v1/public/reservations', async (r) => {
    if (r.request().method() === 'POST') await new Promise((ok) => setTimeout(ok, 1500));
    await r.continue();
  });
  await go(page, '/');
  const d = await openTableBooking(page, () => page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click());
  await d.locator('#bk-date').fill(bookableDay(9, 0));
  await d.getByRole('button', { name: 'Avanti' }).click();
  await d.getByRole('radio', { name: '19:00', exact: true }).click();
  await d.getByRole('button', { name: 'Avanti' }).click();
  await d.getByRole('button', { name: '2 persone', exact: true }).click();
  await d.getByRole('button', { name: 'Avanti' }).click();
  await d.getByLabel('Nome').fill(name);
  await d.getByLabel('Telefono', { exact: true }).fill(testPhone());
  await d.getByLabel(/Ho letto l'informativa/).check();
  await d.getByRole('button', { name: 'Avanti' }).click();
  await d.getByRole('button', { name: 'Invia la richiesta' }).click();
  await expect(d.getByRole('button', { name: 'Chiudi' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Richiesta inviata' })).toBeVisible(); // il riferimento arriva al cliente
  const owner = await ownerApi();
  try {
    const found = await owner.call('GET', `/admin/reservations?q=${encodeURIComponent(name)}`);
    expect(found.items).toHaveLength(1);
    const r = found.items[0] as { id: string; version: number };
    await owner.call('PATCH', `/admin/reservations/${r.id}/status`, { version: r.version, status: 'REJECTED', reason: 'OTHER' });
  } finally {
    await owner.dispose();
  }
});

test('F-08, F-09: foto, conferma prima di eliminare e spostamenti uno alla volta', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  await signIn(page);
  await go(page, '/gestione/foto');
  const items = page.locator('.photos__item');
  await expect(items.first()).toBeVisible();
  const count = await items.count();

  // eliminazione: serve la conferma; «No, tienila» non elimina nulla (una foto non usata altrove: l'ultima della libreria)
  const unused = items.last();
  await unused.getByRole('button', { name: /^Elimina la foto:/ }).click();
  const confirmRow = unused.getByRole('group', { name: "Conferma l'eliminazione" });
  if (await confirmRow.count()) {
    await confirmRow.getByRole('button', { name: 'No, tienila' }).click();
    await expect(confirmRow).toHaveCount(0);
  } else {
    await expect(page.getByRole('alert')).toContainText('è usata in'); // foto in uso: nessuna conferma, messaggio subito
  }
  await expect(items).toHaveCount(count);

  // spostamento lento: mentre si salva gli altri comandi sono disabilitati
  await page.route('**/api/v1/admin/media/order', async (r) => { await new Promise((ok) => setTimeout(ok, 800)); await r.continue(); });
  await items.nth(0).getByRole('button', { name: /^Sposta dopo/ }).click();
  await expect(items.nth(1).getByRole('button', { name: /^Sposta dopo/ })).toBeDisabled();
  await expect(items.nth(1).getByRole('button', { name: /^Sposta dopo/ })).toBeEnabled();
});
