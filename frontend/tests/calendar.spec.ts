// FIX 2 — audit del calendario: un giorno della settimana riaperto negli Orari torna selezionabile nel calendario
// pubblico senza riavvii né svuotare la cache; il calendario aperto prima della modifica e riaperto dopo mostra i dati
// nuovi; il mese successivo non ha giorni disabilitati per errore; una richiesta in un mercoledì riaperto viene accettata;
// in area gestori il riepilogo si aggiorna subito e un giorno aperto senza fasce è segnalato (non «chiuso»).
import { expect, test, type Page } from '@playwright/test';
import { keepBaseline, ownerApi } from './baseline';
import { go, openTableBooking, signIn, testPhone } from './helpers';

keepBaseline(); // orari del sito riportati come prima a ogni test (mercoledì chiuso nei dati dimostrativi)

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
/** Prossimo mercoledì ad almeno `from` giorni da oggi. */
function wednesday(from: number): string {
  const d = new Date();
  d.setDate(d.getDate() + from);
  while (d.getDay() !== 3) d.setDate(d.getDate() + 1);
  return iso(d);
}
const chipName = (day: string) => new Intl.DateTimeFormat('it-IT', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date(`${day}T12:00:00`));

async function setWednesday(open: boolean) {
  const owner = await ownerApi();
  try {
    const s = await owner.call('GET', '/admin/site-settings');
    const hours = (s.hours as { isoDay: number; closed: boolean; opensAt: string | null; closesAt: string | null }[]).map((h) => (h.isoDay === 3
      ? (open ? { isoDay: 3, closed: false, opensAt: '07:00', closesAt: '23:00' } : { isoDay: 3, closed: true })
      : h.closed ? { isoDay: h.isoDay, closed: true } : { isoDay: h.isoDay, closed: false, opensAt: h.opensAt!.slice(0, 5), closesAt: h.closesAt!.slice(0, 5) }));
    await owner.call('PUT', '/admin/site-settings', { ...s, hours, confirmClosures: true });
  } finally {
    await owner.dispose();
  }
}

async function openCalendar(page: Page) {
  return openTableBooking(page, () => page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click());
}

for (const viewport of [{ width: 1366, height: 900 }, { width: 390, height: 844 }]) {
  test(`mercoledì chiuso → aperto → di nuovo chiuso, senza riavvii (${viewport.width}px)`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const wed = wednesday(2);
    await go(page, '/');
    let d = await openCalendar(page);
    await expect(d.getByRole('radio', { name: new RegExp(`^${chipName(wed)}, chiuso$`, 'i') })).toBeDisabled();

    // il gestore riapre il mercoledì mentre il calendario è già aperto; il cliente chiude e riapre il calendario
    await setWednesday(true);
    await page.keyboard.press('Escape');
    d = await openCalendar(page);
    const chip = d.getByRole('radio', { name: new RegExp(`^${chipName(wed)}$`, 'i') });
    await expect(chip).toBeEnabled();
    await chip.click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await expect(d.getByRole('radio', { name: '19:00', exact: true })).toBeEnabled();

    // mese successivo: il mercoledì scelto dal campo data non è segnato chiuso
    await d.getByRole('button', { name: 'Indietro' }).click();
    const later = wednesday(30);
    await d.locator('#bk-date').fill(later);
    await expect(d.getByText('il locale è chiuso')).toHaveCount(0);
    await d.getByRole('button', { name: 'Avanti' }).click();
    await expect(d.getByRole('radio', { name: '19:00', exact: true })).toBeEnabled();

    // di nuovo chiuso: disabilitato alla riapertura del calendario
    await page.keyboard.press('Escape');
    await setWednesday(false);
    d = await openCalendar(page);
    await expect(d.getByRole('radio', { name: new RegExp(`^${chipName(wed)}, chiuso$`, 'i') })).toBeDisabled();
  });
}

test('richiesta inviata per un mercoledì riaperto: accettata dal backend', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  await setWednesday(true);
  const wed = wednesday(2);
  const name = `Mercoledì E2E ${Date.now()}`;
  await go(page, '/');
  const d = await openCalendar(page);
  await d.getByRole('radio', { name: new RegExp(`^${chipName(wed)}$`, 'i') }).click();
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
  const done = page.getByRole('dialog', { name: 'Richiesta inviata' });
  await expect(done).toContainText('non è ancora una prenotazione');
  // pulizia: la richiesta viene rifiutata, così il ripristino degli orari non trova prenotazioni attive
  const owner = await ownerApi();
  try {
    const found = await owner.call('GET', `/admin/reservations?q=${encodeURIComponent(name)}`);
    expect(found.items).toHaveLength(1);
    const r = found.items[0] as { id: string; version: number; status: string };
    expect(r.status).toBe('PENDING');
    await owner.call('PATCH', `/admin/reservations/${r.id}/status`, { version: r.version, status: 'REJECTED', reason: 'OTHER' });
  } finally {
    await owner.dispose();
  }
});

test('area gestori: riepilogo aggiornato subito dopo «Salva» e avviso «aperto ma senza fasce»', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  await signIn(page);
  await go(page, '/gestione/impostazioni');
  const wedSwitch = page.getByRole('switch', { name: /^Mercoledì: chiuso$/ });
  await expect(wedSwitch).toBeVisible();
  const panel = page.locator('.cap-grid__week');
  await panel.getByLabel('Fascia', { exact: true }).selectOption({ label: 'Aperitivo' });
  // il mercoledì della settimana corrente (il riepilogo parte da lunedì), chiuso prima del salvataggio
  const monday = new Date();
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
  const thisWed = new Date(monday);
  thisWed.setDate(monday.getDate() + 2);
  const wedRow = panel.locator(`.week-table tbody tr[data-date="${iso(thisWed)}"]`);
  await expect(wedRow.locator('th')).toContainText('Mer');
  await expect(wedRow.getByText('Chiuso')).toBeVisible();
  await wedSwitch.click();
  await page.getByRole('button', { name: 'Salva', exact: true }).click();
  await expect(page.locator('.toast')).toContainText('Impostazioni salvate');
  await expect(wedRow.getByText('Chiuso')).toHaveCount(0); // senza ricaricare la pagina
  await expect(page.getByText('aperto ma senza fasce prenotabili')).toHaveCount(0); // i dati demo hanno le fasce del mercoledì

  // con il mercoledì aperto ma senza fasce (configurazione simulata) compare l'avviso, non «chiuso»
  await page.route('**/api/v1/admin/booking-config', async (route) => {
    const res = await route.fetch();
    const body = await res.json();
    body.windows = body.windows.filter((w: { isoDay: number }) => w.isoDay !== 3);
    await route.fulfill({ response: res, json: body });
  });
  await page.reload();
  await expect(page.getByRole('status').filter({ hasText: 'aperto ma senza fasce prenotabili' })).toContainText('Mercoledì');
  await page.unroute('**/api/v1/admin/booking-config');
});

test('calendario pubblico: giorno aperto senza fasce indicato come «nessun orario prenotabile», non «chiuso»', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const wed = wednesday(2);
  await setWednesday(true);
  await page.route('**/api/v1/public/booking-options', async (route) => {
    const res = await route.fetch();
    const body = await res.json();
    body.closedDates = [...(body.closedDates ?? []), wed];
    body.unavailableDays = [...(body.unavailableDays ?? []), { date: wed, reason: 'NO_SERVICE_WINDOWS' }];
    await route.fulfill({ response: res, json: body });
  });
  await go(page, '/');
  const d = await openCalendar(page);
  await expect(d.getByRole('radio', { name: new RegExp(`^${chipName(wed)}, nessun orario prenotabile$`, 'i') })).toBeDisabled();
  await d.locator('#bk-date').fill(wed);
  await expect(d.getByText('non ci sono orari prenotabili dal sito')).toBeVisible();
});
