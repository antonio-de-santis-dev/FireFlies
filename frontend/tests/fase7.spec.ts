// FASE 7 end-to-end con frontend, backend e PostgreSQL reali: panoramica senza scorciatoie (req. 1), menu dei
// telefoni (req. 2), foto a due colonne (req. 7), capienza per giorno e fascia (req. 8), calendario di oggi (req. 9),
// scelta tavolo / evento privato e giorni chiusi (req. 10), larghezze 360–1920 senza scorrimento orizzontale.
import { expect, test } from '@playwright/test';
import { ownerApi, settingsBody } from './baseline';
import { bookableDay, go, horizontalOverflow, openTableBooking, requestCard, signIn, testPhone, weekSummaryRow } from './helpers';

const WIDTHS = [360, 375, 390, 768, 1366, 1440, 1920];
const isoWeekday = (iso: string) => ((new Date(`${iso}T12:00:00`).getDay() + 6) % 7) + 1;

test.describe('Panoramica e menu dei gestori', () => {
  test('panoramica: indicatori e cose da fare, nessuna scorciatoia che ripete il menu', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    await signIn(page);
    await go(page, '/gestione');
    await expect(page.getByRole('navigation', { name: 'Azioni rapide' })).toHaveCount(0);
    const main = page.locator('#admin-main');
    for (const dup of ['Personalizza il sito', 'Menu e prezzi', 'Foto', 'Comunicazioni']) {
      await expect(main.getByRole('link', { name: new RegExp(`^${dup}`) })).toHaveCount(0);
    }
    await expect(page.getByRole('list', { name: 'Riepilogo' })).toContainText('richieste in attesa');
    await expect(page.getByRole('list', { name: 'Riepilogo' })).toContainText('confermate oggi');
    await expect(page.getByRole('heading', { name: 'Richieste da gestire' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Prossimi eventi' })).toBeVisible();
  });

  test('ordine delle voci e permessi: titolare e staff', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    await signIn(page);
    const nav = page.getByRole('navigation', { name: 'Area gestori' });
    await expect(nav.getByRole('link')).toHaveText(['Panoramica', 'Menu e prezzi', 'Calendario prenotazioni oggi', 'Eventi',
      /^Richieste/, 'Foto', 'Comunicazioni', 'Personalizza il sito', 'Impostazioni']);
    await signIn(page, 'Staff');
    await expect(nav.getByRole('link')).toHaveText(['Panoramica', 'Menu e prezzi', 'Calendario prenotazioni oggi', 'Eventi',
      /^Richieste/, 'Foto', 'Comunicazioni']);
  });

  for (const width of [360, 375, 390]) {
    test(`menu del telefono a ${width}px: si apre, si chiude, focus corretto, pagina attiva indicata`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await signIn(page);
      await go(page, '/gestione/eventi');
      const toggle = page.getByRole('button', { name: /Menu, pagina attuale: Eventi/ });
      const nav = page.getByRole('navigation', { name: 'Area gestori' });
      await expect(toggle).toHaveAttribute('aria-expanded', 'false');
      await expect(nav).toBeHidden();
      const box = (await toggle.boundingBox())!;
      expect(box.height).toBeGreaterThanOrEqual(44);
      await toggle.click();
      await expect(toggle).toHaveAttribute('aria-expanded', 'true');
      await expect(nav).toBeVisible();
      await expect(nav.getByRole('link', { name: 'Eventi', exact: true })).toBeFocused();
      await expect(nav.getByRole('link', { name: 'Eventi', exact: true })).toHaveAttribute('aria-current', 'page');
      for (const link of await nav.getByRole('link').all()) {
        const b = (await link.boundingBox())!;
        expect(b.height, 'area da toccare').toBeGreaterThanOrEqual(44);
        expect(b.x + b.width).toBeLessThanOrEqual(width + 0.5);
      }
      expect(await horizontalOverflow(page)).toBe(0);
      await page.keyboard.press('Escape');
      await expect(nav).toBeHidden();
      await expect(toggle).toBeFocused();
      await toggle.click();
      await nav.getByRole('link', { name: 'Calendario prenotazioni oggi' }).click();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Calendario prenotazioni oggi');
      await expect(nav).toBeHidden();
      await expect(page.getByRole('button', { name: /pagina attuale: Calendario prenotazioni oggi/ })).toBeVisible();
      // tocco fuori dal menu: si chiude
      await page.getByRole('button', { name: /pagina attuale/ }).click();
      await page.mouse.click(width / 2, 700);
      await expect(nav).toBeHidden();
    });
  }
});

test.describe('Foto sui telefoni', () => {
  for (const width of [360, 375, 390]) {
    test(`almeno due miniature per riga a ${width}px, campi dentro le schede`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await signIn(page, 'Staff');
      await go(page, '/gestione/foto');
      const items = page.locator('.photos__item');
      await expect(items.nth(1)).toBeVisible();
      const a = (await items.nth(0).boundingBox())!;
      const b = (await items.nth(1).boundingBox())!;
      expect(Math.abs(a.y - b.y), 'stessa riga').toBeLessThan(1);
      expect(b.x).toBeGreaterThan(a.x);
      for (const i of [0, 1]) {
        const card = (await items.nth(i).boundingBox())!;
        for (const field of await items.nth(i).locator('input, select, button, label.btn').all()) {
          if (!(await field.isVisible())) continue;
          const f = (await field.boundingBox())!;
          expect(f.x, 'non esce a sinistra').toBeGreaterThanOrEqual(card.x - 0.5);
          expect(f.x + f.width, 'non esce a destra').toBeLessThanOrEqual(card.x + card.width + 0.5);
        }
      }
      expect(await horizontalOverflow(page)).toBe(0);
    });
  }
});

test.describe('Capienza, calendario di oggi, giorni chiusi', () => {
  // FIX-1: la capienza per giorno e fascia è diventata la capienza unica del locale (stesso controllo sul registro)
  test('titolare: capienza unica salvata nel backend; oltre il limite non si conferma', async ({ page }) => {
    const day = bookableDay(15, 0);
    const owner = await ownerApi();
    const original = (await owner.call('GET', '/admin/booking-config')).venueCapacity as number;
    try {
      await page.setViewportSize({ width: 1366, height: 900 });
      await signIn(page);
      await go(page, '/gestione/impostazioni');
      await expect(page.getByRole('heading', { level: 1 })).toHaveText('Impostazioni');
      await expect(page.getByRole('heading', { name: 'Capienza e calendario', exact: true })).toBeVisible();
      await expect(page.getByLabel(/^Sera di /)).toHaveCount(0); // niente più tabella giorno × fascia
      const field = page.getByLabel('Capienza massima del locale (persone)');
      await field.fill('30');
      await page.getByRole('button', { name: 'Salva la capienza' }).click();
      await expect(page.locator('.toast')).toContainText('Capienza del locale salvata: 30 persone');
      await page.reload();
      await expect(page.getByLabel('Capienza massima del locale (persone)')).toHaveValue('30');

      // due richieste da 20 alle 21:00: la seconda conferma è rifiutata dal backend (40 > 30)
      const names = [`Sera A ${Date.now()}`, `Sera B ${Date.now()}`];
      for (const name of names) {
        await owner.call('POST', '/admin/reservations', { source: 'PHONE', date: day, time: '21:00', guests: 20, name, phone: testPhone(), confirmNow: false, needsCallback: false });
      }
      await signIn(page, 'Staff');
      await go(page, '/gestione/richieste');
      await page.getByLabel('Periodo').selectOption('day');
      await page.getByLabel('Giorno', { exact: true }).fill(day);
      for (const [i, name] of names.entries()) {
        await requestCard(page, name).getByRole('button', { name: `Gestisci la richiesta di ${name}` }).click();
        const detail = page.getByRole('dialog', { name: new RegExp(name) });
        await detail.getByRole('button', { name: 'Conferma', exact: true }).click();
        if (i === 0) await expect(detail.locator('.res-detail__facts')).toContainText('Confermata');
        else await expect(detail.getByRole('alert')).toBeVisible();
        await detail.getByRole('button', { name: 'Chiudi' }).first().click();
      }
      await expect(requestCard(page, names[1])).toContainText('In attesa');

      // riepilogo settimanale, fascia Sera: confermate, persone, capienza
      await signIn(page);
      await go(page, '/gestione/impostazioni');
      const row = await weekSummaryRow(page, day, 'Sera');
      // FIX 2: persone confermate nella fascia su capienza, quante ne restano e disponibilità reale
      await expect(row.locator('td[data-label="Confermate / capienza"]')).toHaveText('20 / 30');
      await expect(row.locator('td[data-label="Restano nella fascia"]')).toHaveText('10');
      await expect(row.locator('td[data-label="Disponibili adesso"]')).toHaveText(/^\d+/);
      // ridurre sotto i confermati: errore chiaro, nulla cambia
      await page.getByLabel('Capienza massima del locale (persone)').fill('15');
      await page.getByRole('button', { name: 'Salva la capienza' }).click();
      await expect(page.locator('.toast')).toContainText('persone confermate, più della nuova capienza');
      await page.reload();
      await expect(page.getByLabel('Capienza massima del locale (persone)')).toHaveValue('30');
      // pulizia: richieste annullate
      const found = await owner.call('GET', `/admin/reservations?from=${day}&to=${day}&q=Sera`);
      for (const r of found.items as { id: string; version: number; status: string }[]) {
        if (r.status === 'PENDING' || r.status === 'CONFIRMED') {
          await owner.call('PATCH', `/admin/reservations/${r.id}/status`, { version: r.version, status: 'CANCELLED', reason: 'OTHER' });
        }
      }
    } finally {
      await owner.call('PUT', '/admin/venue-capacity', { capacity: original });
      await owner.dispose();
    }
  });

  test('calendario di oggi: solo le confermate, filtro per fascia, niente telefoni', async ({ page }) => {
    // ora di Roma, come il backend: un arrivo tra 30–45 minuti, oggi
    const rome = (d: Date) => Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Rome', year: 'numeric', month: '2-digit',
      day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(d).map((p) => [p.type, p.value]));
    const now = rome(new Date());
    const later = new Date(Date.now() + 30 * 60_000);
    later.setUTCMinutes(Math.ceil(later.getUTCMinutes() / 15) * 15, 0, 0);
    const slot = rome(later);
    const today = `${now.year}-${now.month}-${now.day}`;
    test.skip(`${slot.year}-${slot.month}-${slot.day}` !== today, 'a ridosso della mezzanotte non resta un orario di oggi');
    const time = `${slot.hour}:${slot.minute}`;
    const confirmed = `Oggi confermata ${Date.now()}`;
    const waiting = `Oggi in attesa ${Date.now()}`;
    const phone = testPhone();
    const owner = await ownerApi();
    try {
      await owner.call('POST', '/admin/reservations', { source: 'PHONE', date: today, time, guests: 3, name: confirmed, phone, confirmNow: true, needsCallback: false });
      await owner.call('POST', '/admin/reservations', { source: 'PHONE', date: today, time, guests: 2, name: waiting, phone: testPhone(), confirmNow: false, needsCallback: false });
      for (const width of WIDTHS) {
        await page.setViewportSize({ width, height: 900 });
        await signIn(page, 'Staff');
        await go(page, '/gestione/oggi');
        const list = page.getByRole('list', { name: 'Prenotazioni confermate di oggi' });
        await expect(list).toContainText(confirmed);
        await expect(list).not.toContainText(waiting);
        await expect(list).not.toContainText(phone);
        await expect(list.locator('.today__item', { hasText: confirmed })).toContainText(time);
        expect(await horizontalOverflow(page), `${width}px`).toBe(0);
        if (width !== 1366) continue;
        const band = (await list.locator('.today__item', { hasText: confirmed }).locator('.today__band').textContent())!.trim();
        const other = ['Colazione', 'Pomeriggio', 'Aperitivo', 'Sera'].find((b) => b !== band)!;
        await page.getByRole('group', { name: 'Filtra per fascia' }).getByRole('button', { name: new RegExp(`^${other}`) }).click();
        await expect(page.getByText(confirmed)).toHaveCount(0);
        await page.getByRole('group', { name: 'Filtra per fascia' }).getByRole('button', { name: new RegExp(`^${band}`) }).click();
        await expect(list).toContainText(confirmed);
      }
    } finally {
      const found = await owner.call('GET', `/admin/reservations?from=${today}&to=${today}&q=Oggi`);
      for (const r of found.items as { id: string; version: number; status: string }[]) {
        if (r.status === 'PENDING' || r.status === 'CONFIRMED') {
          await owner.call('PATCH', `/admin/reservations/${r.id}/status`, { version: r.version, status: 'CANCELLED', reason: 'OTHER' });
        }
      }
      await owner.dispose();
    }
  });

  test('sito: prima la scelta tra tavolo ed evento / compleanno, con il telefono del locale', async ({ page }) => {
    const owner = await ownerApi();
    const original = (await owner.call('GET', '/admin/site-settings')).phone ?? null;
    const setPhone = async (phone: string | null) => {
      const current = await owner.call('GET', '/admin/site-settings');
      await owner.call('PUT', '/admin/site-settings', { ...settingsBody(current), phone, version: current.version });
    };
    try {
      await setPhone('+39 0836 000000');
      for (const width of [390, 1366]) {
        await page.setViewportSize({ width, height: 900 });
        await go(page, '/');
        await page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click();
        const choose = page.getByRole('dialog', { name: 'Prenota' });
        await expect(choose.getByRole('button', { name: /Prenota un tavolo/ })).toBeVisible();
        await choose.getByRole('button', { name: /Organizza un evento \/ compleanno/ }).click();
        const party = page.getByRole('dialog', { name: 'Organizza un evento o un compleanno' });
        await expect(party).toContainText('Contattaci per organizzare il tuo evento o compleanno');
        await expect(party.getByRole('link', { name: /Chiama/ })).toHaveAttribute('href', 'tel:+390836000000');
        await party.getByRole('button', { name: 'Indietro' }).click();
        await choose.getByRole('button', { name: /Prenota un tavolo/ }).click();
        await expect(page.getByRole('dialog', { name: 'Richiedi un tavolo' })).toContainText('Passo 1 di 5');
        expect(await horizontalOverflow(page)).toBe(0);
        await page.keyboard.press('Escape');
      }
      await setPhone(null);
      await go(page, '/');
      await page.reload();
      await page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click();
      await page.getByRole('button', { name: /Organizza un evento/ }).click();
      await expect(page.getByRole('dialog')).toContainText('non è ancora disponibile');
      await expect(page.getByRole('dialog').getByRole('link', { name: /Chiama/ })).toHaveCount(0);
    } finally {
      await setPhone(original);
      await owner.dispose();
    }
  });

  test('giorni chiusi: settimanale e straordinario disabilitati nel calendario e rifiutati dal backend', async ({ page }) => {
    const closedDay = bookableDay(2, 3);
    const owner = await ownerApi();
    let exceptionId = '';
    try {
      const added = await owner.call('POST', '/admin/opening-exceptions', { from: closedDay, to: closedDay, closed: true, note: 'Chiusura E2E' });
      exceptionId = added.exception.id;
      await page.setViewportSize({ width: 390, height: 844 });
      await go(page, '/');
      const d = await openTableBooking(page, () => page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click());
      // mercoledì: chiuso negli orari del sito; la data straordinaria: chiusa anche se di solito aperta
      const chips = d.getByRole('radiogroup', { name: 'Prossimi 14 giorni' }).getByRole('radio');
      const labels = await chips.evaluateAll((els) => els.map((e) => [e.getAttribute('aria-label') ?? '', (e as HTMLButtonElement).disabled] as const));
      expect(labels.filter(([l]) => /^Mercoledì/.test(l)).every(([l, dis]) => dis && /chiuso/.test(l))).toBe(true);
      const closedLabel = new Intl.DateTimeFormat('it-IT', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date(`${closedDay}T12:00:00`));
      await expect(d.getByRole('radio', { name: new RegExp(`^${closedLabel}, chiuso$`, 'i') })).toBeDisabled();
      await d.locator('#bk-date').fill(closedDay);
      await d.getByRole('button', { name: 'Avanti' }).click();
      await expect(d.locator('#bk-date-err')).toContainText('il locale è chiuso');
      // anche aggirando il modulo il backend rifiuta
      const status = await page.evaluate(async (date) => {
        await fetch('/api/v1/auth/csrf');
        const csrf = decodeURIComponent(document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1] ?? '');
        const r = await fetch('/api/v1/public/reservations', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, 'Idempotency-Key': crypto.randomUUID() },
          body: JSON.stringify({ date, time: '19:00', guests: 2, name: 'Chiuso E2E', phone: '+39 300 111 2233', privacyAccepted: true }) });
        return (await r.json()).code;
      }, closedDay);
      expect(status).toBe('DAY_CLOSED');
    } finally {
      if (exceptionId) await owner.call('DELETE', `/admin/opening-exceptions/${exceptionId}`);
      await owner.dispose();
    }
  });

  test('pagine nuove senza scorrimento orizzontale da 360 a 1920 px', async ({ page }) => {
    await signIn(page);
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 900 });
      for (const route of ['/gestione', '/gestione/oggi', '/gestione/richieste', '/gestione/impostazioni', '/gestione/eventi/nuovo', '/gestione/foto']) {
        await go(page, route);
        await expect(page.locator('#admin-main')).not.toHaveAttribute('aria-busy', 'true');
        expect(await horizontalOverflow(page), `${route} a ${width}px`).toBe(0);
      }
    }
  });
});
