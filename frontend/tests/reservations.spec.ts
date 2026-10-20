// Prenotazioni V1 end-to-end: frontend reale, backend reale, PostgreSQL reale (scripts/e2e.mjs).
// Ogni test usa nomi, giorni e numeri di prova diversi: le prenotazioni restano nel database come per i gestori veri.
import { expect, request, test, type Page } from '@playwright/test';
import { ownerApi } from './baseline';
import { bookableDay, go, signIn, testPhone, watchProblems } from './helpers';

const BACKEND = () => process.env.FIREFLIES_BACKEND_URL ?? 'http://localhost:18080';

/** Richiesta dal sito fatta direttamente all'API pubblica (come farebbe il modulo), con CSRF e Idempotency-Key. */
async function webRequest(body: { date: string; time: string; guests: number; name: string; email?: string }) {
  const ctx = await request.newContext({ baseURL: BACKEND() });
  try {
    await ctx.get('/api/v1/auth/csrf');
    const token = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
    const res = await ctx.post('/api/v1/public/reservations', {
      data: { ...body, phone: testPhone(), privacyAccepted: true, notes: '' },
      headers: { 'X-XSRF-TOKEN': decodeURIComponent(token), 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(res.status(), await res.text()).toBe(201);
    return (await res.json()) as { reference: string };
  } finally {
    await ctx.dispose();
  }
}

async function openRequest(page: Page, name: string) {
  await go(page, '/gestione/richieste');
  await page.getByLabel('Cerca').fill(name);
  const row = page.getByRole('row', { name: new RegExp(name) });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: `Gestisci la richiesta di ${name}` }).click();
  return page.getByRole('dialog', { name: new RegExp(name) });
}

test.describe('Prenotazioni', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('il cliente chiede un tavolo, lo staff lo trova in attesa e lo conferma', async ({ page }) => {
    const problems = watchProblems(page);
    const name = `Cliente E2E ${Date.now()}`;
    const day = bookableDay(3, 0);
    await go(page, '/');
    await page.getByRole('banner').getByRole('button', { name: 'Richiedi un tavolo' }).click();
    const d = page.getByRole('dialog', { name: 'Richiedi un tavolo' });
    await d.locator('#bk-date').fill(day);
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('radio', { name: '20:00' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('button', { name: '4 persone' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByLabel('Nome').fill(name);
    await d.getByLabel('Telefono', { exact: true }).fill(testPhone());
    await d.getByLabel(/^Email/).fill('cliente.e2e@example.invalid');
    await d.getByLabel(/^Note/).fill('Tavolo fuori se possibile');
    await d.getByLabel(/Ho letto l'informativa/).check();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('button', { name: 'Invia la richiesta' }).click();
    const done = page.getByRole('dialog', { name: 'Richiesta inviata' });
    await expect(done).toContainText('non è ancora una prenotazione');
    const reference = (await done.locator('.booking-ref').textContent())!.trim();
    await done.getByRole('button', { name: 'Chiudi' }).first().click();

    await signIn(page, 'Staff');
    await expect(page.getByRole('navigation', { name: 'Area gestori' }).locator('.admin__count')).toBeVisible();
    const detail = await openRequest(page, name);
    await expect(detail).toContainText(reference);
    await expect(detail).toContainText('Tavolo fuori se possibile');
    await expect(detail.locator('.res-detail__capacity')).toContainText("C'è posto");
    await expect(detail).toContainText('Ricevuta della richiesta · email al cliente');
    await expect(detail).toContainText('Email preparata, non inviata');
    await detail.getByRole('button', { name: 'Conferma', exact: true }).click();
    await expect(page.locator('.toast')).toContainText('Prenotazione confermata');
    await expect(detail.locator('.res-detail__capacity')).toContainText('Posti già occupati nel registro');
    await expect(detail).toContainText('SMS non previsto (canale spento)');
    await expect(detail).not.toContainText('Inviata');
    await expect(detail.getByRole('button', { name: 'Conferma', exact: true })).toHaveCount(0);
    await detail.getByRole('button', { name: 'Chiudi' }).first().click();
    await expect(page.getByRole('row', { name: new RegExp(name) })).toContainText('Confermata');

    // salvato davvero: dopo il ricaricamento resta confermata
    await page.reload();
    await page.getByLabel('Cerca').fill(name);
    await expect(page.getByRole('row', { name: new RegExp(name) })).toContainText('Confermata');
    expect(problems).toEqual([]);
  });

  test('rifiuto con motivo e annullamento: storico e posti liberati', async ({ page }) => {
    const day = bookableDay(4, 0);
    const rejected = `Rifiuto E2E ${Date.now()}`;
    const cancelled = `Annullo E2E ${Date.now()}`;
    await webRequest({ date: day, time: '18:30', guests: 2, name: rejected });
    await webRequest({ date: day, time: '19:00', guests: 10, name: cancelled });
    await signIn(page, 'Staff');

    let detail = await openRequest(page, rejected);
    await detail.getByRole('button', { name: 'Rifiuta' }).click();
    await detail.getByLabel('Motivo').selectOption('NO_CAPACITY');
    await detail.getByLabel(/^Nota/).fill('Sala riservata');
    await detail.getByRole('button', { name: 'Rifiuta la richiesta' }).click();
    await expect(detail.locator('.res-detail__facts')).toContainText('Rifiutata · Posti esauriti');
    await expect(detail).toContainText('«Sala riservata»');
    await detail.getByRole('button', { name: 'Chiudi' }).first().click();

    detail = await openRequest(page, cancelled);
    await detail.getByRole('button', { name: 'Conferma', exact: true }).click();
    await expect(detail.locator('.res-detail__facts')).toContainText('Confermata');
    await detail.getByRole('button', { name: 'Chiudi' }).first().click();
    await go(page, '/gestione/capienza');
    await page.getByLabel('Giorno', { exact: true }).fill(day);
    const at = page.getByRole('row', { name: /^19:00/ }).first();
    await expect(at.getByRole('cell').nth(2)).toHaveText(/^\d+$/);
    const before = Number(await at.getByRole('cell').nth(2).textContent());
    expect(before).toBeGreaterThanOrEqual(10);

    detail = await openRequest(page, cancelled);
    await detail.getByRole('button', { name: 'Annulla' }).click();
    await detail.getByLabel('Motivo').selectOption('CUSTOMER_REQUEST');
    await detail.getByRole('button', { name: 'Annulla la prenotazione' }).click();
    await expect(page.locator('.toast')).toContainText('i posti sono di nuovo liberi');
    await expect(detail.locator('.res-detail__list').last()).toContainText('Confermata → Annullata');
    await detail.getByRole('button', { name: 'Chiudi' }).first().click();
    await go(page, '/gestione/capienza');
    await page.getByLabel('Giorno', { exact: true }).fill(day);
    await expect(page.getByRole('row', { name: /^19:00/ }).first().getByRole('cell').nth(2)).toHaveText(String(before - 10));
  });

  test('inserimento manuale da telefono: conferma immediata, avviso fuori orario, dati obbligatori', async ({ page }) => {
    const name = `Telefono E2E ${Date.now()}`;
    await signIn(page, 'Staff');
    await go(page, '/gestione/richieste');
    await page.getByRole('button', { name: 'Nuova prenotazione' }).click();
    const d = page.getByRole('dialog', { name: 'Nuova prenotazione' });
    await d.getByLabel('Giorno').fill(bookableDay(5, 0));
    await d.getByLabel('Ora', { exact: true }).fill('19:15');
    await d.getByLabel('Persone').fill('3');
    await d.getByLabel('Nome', { exact: true }).fill(name);
    await d.getByRole('button', { name: 'Salva la prenotazione' }).click();
    await expect(d.locator('#mn-err')).toContainText('telefono');
    await expect(d.getByRole('textbox', { name: 'Telefono' })).toHaveAttribute('aria-invalid', 'true');
    await d.getByRole('textbox', { name: 'Telefono' }).fill(testPhone());
    await d.getByLabel(/Conferma subito/).check();
    await d.getByRole('button', { name: 'Salva la prenotazione' }).click();
    await expect(page.locator('.toast')).toContainText('Prenotazione confermata');
    await expect(page.locator('.toast')).toContainText('Attenzione');
    const detail = page.getByRole('dialog', { name: new RegExp(name) });
    await expect(detail.locator('.res-detail__facts')).toContainText('Telefono');
    await expect(detail.locator('.res-detail__facts')).toContainText('Confermata');
  });

  test('due gestori sulla stessa richiesta: chi arriva secondo vede i dati aggiornati', async ({ page }) => {
    const name = `Conflitto E2E ${Date.now()}`;
    await webRequest({ date: bookableDay(6, 0), time: '18:00', guests: 2, name });
    await signIn(page, 'Staff');
    const detail = await openRequest(page, name);
    // nel frattempo il titolare la rifiuta da un'altra postazione
    const owner = await ownerApi();
    try {
      const list = await owner.call('GET', `/admin/reservations?q=${encodeURIComponent(name)}`);
      const item = list.items[0];
      await owner.call('PATCH', `/admin/reservations/${item.id}/status`, { version: item.version, status: 'REJECTED', reason: 'VENUE_DECISION' });
    } finally {
      await owner.dispose();
    }
    await detail.getByRole('button', { name: 'Conferma', exact: true }).click();
    await expect(detail.getByRole('alert')).toContainText('Qualcun altro ha appena modificato');
    await expect(detail.locator('.res-detail__facts')).toContainText('Rifiutata');
  });

  test('titolare: aggiunge un tavolo e lo assegna a mano a una prenotazione confermata', async ({ page }) => {
    const name = `Tavolo E2E ${Date.now()}`;
    const code = `E${String(Date.now()).slice(-4)}`;
    await webRequest({ date: bookableDay(7, 0), time: '20:30', guests: 6, name });
    await signIn(page);
    await go(page, '/gestione/capienza');
    const form = page.getByRole('form', { name: 'Nuovo tavolo' });
    await form.getByLabel('Codice').fill(code);
    await form.getByLabel('Posti min').fill('2');
    await form.getByLabel('Posti max').fill('4');
    await form.getByRole('button', { name: 'Aggiungi il tavolo' }).click();
    await expect(page.locator('.toast')).toContainText(`Tavolo ${code} aggiunto`);
    await expect(page.locator('.cap-tag', { hasText: code })).toBeVisible();

    const detail = await openRequest(page, name);
    await detail.getByRole('button', { name: 'Conferma', exact: true }).click();
    await expect(detail.locator('.res-detail__facts')).toContainText('Confermata');
    await detail.getByRole('button', { name: 'Tavoli' }).click();
    await detail.getByLabel(new RegExp(`^${code} ·`)).check();
    await expect(detail).toContainText('Posti scelti: 4');
    // 4 posti per 6 persone: il titolare può fare un'eccezione motivata
    await detail.getByLabel(/Eccezione del titolare/).check();
    await detail.getByLabel("Motivo dell'eccezione").fill('Aggiungiamo due sgabelli');
    await detail.getByRole('button', { name: 'Assegna i tavoli' }).click();
    await expect(page.locator('.toast')).toContainText('Tavoli aggiornati');
    await expect(detail.locator('.res-detail__facts')).toContainText(`${code} (4 posti, eccezione)`);
  });

  test('staff: vede i posti del giorno ma non può cambiare la configurazione', async ({ page }) => {
    await signIn(page, 'Staff');
    await go(page, '/gestione/capienza');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Capienza e tavoli');
    await expect(page.getByRole('heading', { name: 'Posti del giorno' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Salva le regole' })).toHaveCount(0);
    await expect(page.getByRole('form', { name: 'Nuovo tavolo' })).toHaveCount(0);
    await expect(page.getByLabel('Persone massime dal sito')).toBeDisabled();
  });

  test('richieste dal sito chiuse dal titolare: il modulo invita a chiamare', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/impostazioni');
    const toggle = page.getByRole('switch', { name: 'Richieste tavolo attive' });
    await expect(toggle).toHaveAttribute('aria-checked', 'true'); // regola caricata dal server
    await toggle.click();
    await expect(page.locator('.toast')).toContainText('Richieste dal sito chiuse');
    try {
      await go(page, '/');
      await page.getByRole('banner').getByRole('button', { name: 'Richiedi un tavolo' }).click();
      await expect(page.getByRole('dialog', { name: 'Richiedi un tavolo' })).toContainText('non accetta richieste dal sito');
    } finally {
      const owner = await ownerApi();
      try {
        const config = await owner.call('GET', '/admin/booking-config');
        const { id: _id, effectiveFrom: _from, ...policy } = config.policy;
        await owner.call('PUT', '/admin/booking-policy', { ...policy, webBookingEnabled: true });
      } finally {
        await owner.dispose();
      }
    }
  });
});
