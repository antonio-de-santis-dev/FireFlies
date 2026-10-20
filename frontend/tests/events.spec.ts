// Eventi end-to-end (FASE 6): frontend, backend e PostgreSQL reali. Giorni lontani da quelli usati dai test delle
// prenotazioni ordinarie e dalla serata demo; titoli e numeri sempre diversi.
import { expect, request, test, type Page } from '@playwright/test';
import { ownerApi } from './baseline';
import { bookableDay, go, signIn, testPhone, watchProblems } from './helpers';

const BACKEND = () => process.env.FIREFLIES_BACKEND_URL ?? 'http://localhost:18080';

async function publicPost(path: string, data: unknown) {
  const ctx = await request.newContext({ baseURL: BACKEND() });
  try {
    await ctx.get('/api/v1/auth/csrf');
    const token = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
    const res = await ctx.post(`/api/v1${path}`, {
      data, headers: { 'X-XSRF-TOKEN': decodeURIComponent(token), 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(res.status(), await res.text()).toBe(201);
    return res.json();
  } finally {
    await ctx.dispose();
  }
}

async function fillEvent(page: Page, title: string, day: string, start: string, end: string, quota: string) {
  await page.getByLabel('Titolo').fill(title);
  await page.getByLabel('Data').fill(day);
  await page.getByLabel('Orario').fill(start);
  await page.getByLabel('Fine').fill(end);
  await page.getByLabel('Pulsante sul sito').selectOption('REQUEST');
  await page.getByLabel('Persone al massimo per la serata').fill(quota);
}

test.describe('Eventi', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('serata dal backend: il cliente chiede un tavolo per l’evento, lo staff lo conferma', async ({ page }) => {
    const problems = watchProblems(page);
    const name = `Ospite serata ${Date.now()}`;
    await go(page, '/eventi');
    await page.getByRole('link', { name: /^Esempio demo DJ set al Fire/ }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('DJ set al Fire');
    await expect(page.locator('.event-facts')).toContainText('22:30 – 02:00');
    await expect(page.locator('.event-facts')).toContainText('Tavoli su richiesta, conferma del locale');
    await page.getByRole('button', { name: 'Richiedi un tavolo per questa serata' }).click();
    const d = page.getByRole('dialog', { name: 'Richiedi un tavolo' });
    await expect(d).toContainText('Per la serata DJ set al Fire');
    await expect(d.getByText('Passo 3 di 5')).toBeVisible(); // giorno e orario sono quelli della serata
    await d.getByRole('button', { name: '4 persone' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByLabel('Nome').fill(name);
    await d.getByLabel('Telefono', { exact: true }).fill(testPhone());
    await d.getByLabel(/Ho letto l'informativa/).check();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await expect(d.locator('.booking-recap')).toContainText('22:30');
    await expect(d.getByRole('button', { name: 'Modifica quando' })).toHaveCount(0);
    await d.getByRole('button', { name: 'Invia la richiesta' }).click();
    const done = page.getByRole('dialog', { name: 'Richiesta inviata' });
    await expect(done).toContainText('non è ancora una prenotazione');
    await done.getByRole('button', { name: 'Chiudi' }).first().click();

    await signIn(page, 'Staff');
    await go(page, '/gestione/richieste');
    await page.getByLabel('Cerca').fill(name);
    const row = page.getByRole('row', { name: new RegExp(name) });
    await expect(row).toContainText('Serata «DJ set al Fire»');
    await row.getByRole('button', { name: `Gestisci la richiesta di ${name}` }).click();
    const detail = page.getByRole('dialog', { name: new RegExp(name) });
    await expect(detail.locator('.res-detail__facts')).toContainText('DJ set al Fire');
    await detail.getByRole('button', { name: 'Conferma', exact: true }).click();
    await expect(page.locator('.toast')).toContainText('Prenotazione confermata');
    await expect(detail.locator('.res-detail__facts')).toContainText('Confermata');
    await detail.getByRole('button', { name: 'Chiudi' }).first().click();
    await go(page, '/gestione/eventi');
    await expect(page.locator('.ev-admin__item', { hasText: 'DJ set al Fire' })).toContainText(/\d+\/30 posti confermati/);
    // la richiesta confermata resta: si annulla per lasciare la quota della serata demo com'era
    const owner = await ownerApi();
    try {
      const found = await owner.call('GET', `/admin/reservations?q=${encodeURIComponent(name)}`);
      await owner.call('PATCH', `/admin/reservations/${found.items[0].id}/status`, { version: found.items[0].version, status: 'CANCELLED', reason: 'OTHER' });
    } finally {
      await owner.dispose();
    }
    expect(problems).toEqual([]);
  });

  test('staff: nuova serata esclusiva bloccata da una prenotazione confermata, poi pubblicata e annullata', async ({ page }) => {
    const day = bookableDay(9, 0);
    const title = `Serata E2E ${Date.now()}`;
    const ordinary = `Cliente ordinario ${Date.now()}`;
    const receipt = await publicPost('/public/reservations', { date: day, time: '19:00', guests: 4, name: ordinary, phone: testPhone(), privacyAccepted: true });
    const owner = await ownerApi();
    let reservationId = '';
    try {
      const found = await owner.call('GET', `/admin/reservations?q=${receipt.reference}`);
      reservationId = found.items[0].id;
      await owner.call('PATCH', `/admin/reservations/${reservationId}/status`, { version: found.items[0].version, status: 'CONFIRMED' });
    } finally {
      await owner.dispose();
    }

    await signIn(page, 'Staff');
    await go(page, '/gestione/eventi/nuovo');
    await fillEvent(page, title, day, '19:30', '22:00', '20');
    await page.getByRole('button', { name: 'Pubblica' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'prenotazioni già confermate' });
    await expect(alert).toContainText(receipt.reference);
    await expect(alert).toContainText('4 persone');
    await expect(alert.getByRole('button', { name: /Pubblica comunque/ })).toHaveCount(0); // non forzabile

    const again = await ownerApi();
    try {
      const r = await again.call('GET', `/admin/reservations/${reservationId}`);
      await again.call('PATCH', `/admin/reservations/${reservationId}/status`, { version: r.summary.version, status: 'CANCELLED', reason: 'VENUE_DECISION' });
    } finally {
      await again.dispose();
    }
    await page.getByRole('button', { name: 'Pubblica' }).click();
    await expect(page.locator('.toast')).toContainText(`«${title}» pubblicato sul sito`);
    await expect(page.locator('.ev-admin__item', { hasText: title })).toContainText('Pubblicato');
    await expect(page.locator('.ev-admin__item', { hasText: title })).toContainText('Esclusiva');

    // sul sito: la serata c'è e il servizio ordinario di quel giorno è chiuso nel suo orario
    await page.getByRole('link', { name: 'Vedi il sito' }).click();
    await page.getByRole('navigation', { name: 'Principale' }).getByRole('link', { name: 'Eventi', exact: true }).click();
    await expect(page.locator('.event-row', { hasText: title })).toBeVisible();
    await page.getByRole('banner').getByRole('button', { name: 'Richiedi un tavolo' }).click();
    const d = page.getByRole('dialog', { name: 'Richiedi un tavolo' });
    await d.locator('#bk-date').fill(day);
    await d.getByRole('button', { name: 'Avanti' }).click();
    await expect(d.getByRole('radio', { name: /^20:00, evento/ })).toBeDisabled();
    await expect(d.getByRole('radio', { name: '18:00', exact: true })).toBeEnabled(); // 18:00–19:30 finisce prima della serata
    await page.keyboard.press('Escape');

    // il titolare la annulla: sul sito resta visibile come annullata e gli orari tornano liberi
    await signIn(page);
    await go(page, '/gestione/eventi');
    await page.getByRole('link', { name: `Modifica ${title}` }).click();
    await page.getByRole('button', { name: "Annulla l'evento" }).click();
    await page.getByRole('button', { name: "Conferma l'annullamento" }).click();
    await expect(page.locator('.toast')).toContainText(`«${title}» annullato`);
    await expect(page.locator('.ev-admin__item', { hasText: title })).toContainText('Annullato');
    await go(page, '/eventi');
    await expect(page.locator('.event-row', { hasText: title })).toContainText('Annullata');
  });

  test('posti condivisi: lo staff non può abilitarli, il titolare sì con l’avviso sui posti non garantiti', async ({ page }) => {
    const day = bookableDay(10, 0);
    const title = `Condivisa E2E ${Date.now()}`;
    await signIn(page, 'Staff');
    await go(page, '/gestione/eventi/nuovo');
    const toggle = page.getByRole('switch', { name: /Esclusiva/ });
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByText('Solo il titolare può far condividere i posti')).toBeVisible();

    await signIn(page);
    await go(page, '/gestione/eventi/nuovo');
    await fillEvent(page, title, day, '18:00', '20:00', '40');
    await page.getByRole('switch', { name: /Esclusiva/ }).click();
    await expect(page.getByRole('switch', { name: /Posti condivisi/ })).toHaveAttribute('aria-checked', 'false');
    await page.getByRole('button', { name: 'Pubblica' }).click();
    // quota 40 su un locale da 40: l'avviso «posti non garantiti» compare solo se il servizio ordinario ne ha già occupati
    await expect(page.locator('.toast')).toContainText(`«${title}» pubblicato sul sito`);
    await go(page, '/gestione/eventi');
    await expect(page.locator('.ev-admin__item', { hasText: title })).toContainText('Posti condivisi');
    const owner = await ownerApi();
    try {
      const list = await owner.call('GET', '/admin/events') as unknown as { id: string; title: string; version: number }[];
      const ev = list.find((e) => e.title === title)!;
      await owner.call('POST', `/admin/events/${ev.id}/cancel`, { version: ev.version, cancelReservations: true });
    } finally {
      await owner.dispose();
    }
  });
});
