// FIX-1 — verifiche end-to-end con backend e database reali: animazione iniziale, categorie del menu sul telefono,
// foto di «Perché FireFlies», menu dei gestori sul telefono, invio della prenotazione (doppio tocco, 429), Impostazioni
// (capienza unica, riepilogo settimanale, chiusure e aperture su intervalli, orari sul telefono). Screenshot reali allegati.
import { expect, test, type Page } from '@playwright/test';
import { ownerApi } from './baseline';
import { bookableDay, go, horizontalOverflow, openTableBooking, signIn, testPhone, weekSummaryRow } from './helpers';

const WIDTHS = [360, 375, 390, 430, 768, 1366, 1440, 1920];

async function shot(page: Page, name: string) {
  await test.info().attach(name, { body: await page.screenshot(), contentType: 'image/png' });
}

const isoOf = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const plus = (iso: string, n: number) => {
  const d = new Date(`${iso}T12:00:00`);
  d.setDate(d.getDate() + n);
  return isoOf(d);
};

test.describe('1. Animazione iniziale', () => {
  test.use({ reducedMotion: 'no-preference', viewport: { width: 390, height: 844 } });

  test('il velo con il logo sfuma sulla home, niente sfondo chiaro, nessun blocco, non riparte navigando', async ({ page }) => {
    await page.route('**/api/v1/public/site', async (r) => { await new Promise((ok) => setTimeout(ok, 400)); await r.continue(); });
    await page.goto('/#/');
    // prima ancora dei contenuti lo sfondo è già scuro (stile in linea in index.html)
    const bg = await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
    expect(bg).toBe('rgb(16, 16, 16)');
    await expect(page.locator('.content-gate, .intro-veil').first()).toBeVisible();
    await expect(page.locator('.intro-veil')).toHaveCount(1, { timeout: 10_000 });
    await shot(page, 'intro: velo in uscita');
    // il velo non intercetta i tocchi e se ne va da solo
    expect(await page.locator('.intro-veil').evaluate((el) => getComputedStyle(el).pointerEvents)).toBe('none');
    await expect(page.locator('.intro-veil')).toHaveCount(0, { timeout: 2_000 });
    await expect(page.locator('.hero')).toHaveClass(/hero--intro/);
    await page.getByRole('link', { name: 'Scopri il menu' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Il menu' })).toBeVisible();
    await page.evaluate(() => { location.hash = '#/'; }); // navigazione interna (router a hash), nessun ricaricamento
    await expect(page.locator('.hero')).toBeVisible();
    await expect(page.locator('.intro-veil')).toHaveCount(0);
    await expect(page.locator('.hero'), 'tornando alla home l\'ingresso non riparte').not.toHaveClass(/hero--intro/);
    await shot(page, 'intro: home dopo la navigazione interna');
  });

  test('«riduci movimento»: nessun velo e nessuna animazione del hero', async ({ browser }) => {
    const context = await browser.newContext({ reducedMotion: 'reduce', viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    await page.goto('/#/');
    await expect(page.locator('.hero h1')).toBeVisible();
    await expect(page.locator('.intro-veil')).toHaveCount(0);
    const animation = await page.locator('.hero__media img').evaluate((el) => getComputedStyle(el).animationName);
    expect(animation).toBe('none');
    await context.close();
  });
});

test.describe('2. Menu pubblico: categorie sul telefono', () => {
  for (const width of [360, 390, 430]) {
    test(`chip che portano alla sezione, categoria attiva, barra sempre visibile a ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await go(page, '/menu');
      const nav = page.getByRole('navigation', { name: 'Categorie del menu' });
      await expect(nav).toBeVisible();
      const chips = nav.getByRole('button');
      expect(await chips.count()).toBeGreaterThan(2);
      for (const chip of await chips.all()) {
        expect((await chip.boundingBox())!.height, 'area da toccare').toBeGreaterThanOrEqual(44);
      }
      const food = nav.getByRole('button', { name: /^Food/ });
      await food.scrollIntoViewIfNeeded();
      await food.click();
      const heading = page.getByRole('heading', { level: 2, name: 'Food' });
      await expect(food).toHaveAttribute('aria-current', 'true');
      await expect(heading).toBeFocused();
      await page.waitForTimeout(600);
      const navBox = (await nav.boundingBox())!;
      const headBox = (await heading.boundingBox())!;
      expect(headBox.y, 'titolo sotto la barra, non coperto').toBeGreaterThanOrEqual(navBox.y + navBox.height - 1);
      expect(headBox.y, 'e subito sotto, senza dover scorrere').toBeLessThan(navBox.y + navBox.height + 120);
      expect(navBox.y, 'barra agganciata sotto l\'intestazione').toBeLessThan(100);
      // scorrendo a mano la categoria attiva segue la pagina
      await page.getByRole('heading', { level: 2, name: 'Colazione' }).scrollIntoViewIfNeeded();
      await page.mouse.wheel(0, -200);
      await page.waitForTimeout(400);
      expect(await horizontalOverflow(page)).toBe(0);
      await shot(page, `menu ${width}px`);
    });
  }

  test('nomi lunghi e tastiera: nessun chip tagliato fuori dallo schermo, Invio porta alla sezione', async ({ page }) => {
    const owner = await ownerApi();
    const menu = await owner.call('GET', '/admin/menu');
    const cat = (menu.categories as { id: string; slug: string; name: string; hint: string | null; visible: boolean }[])
      .find((c) => c.visible && (menu.items as { categoryId: string; visible: boolean }[]).some((i) => i.categoryId === c.id && i.visible))!;
    const long = 'Taglieri e aperitivi da condividere'; // 35 caratteri (massimo 40)
    try {
      await owner.call('PUT', `/admin/menu/categories/${cat.id}`, { slug: cat.slug, name: long, hint: cat.hint, visible: true });
      await page.setViewportSize({ width: 360, height: 760 });
      await go(page, '/menu');
      const chip = page.getByRole('navigation', { name: 'Categorie del menu' }).getByRole('button', { name: new RegExp(long.slice(0, 20)) });
      const box = (await chip.boundingBox())!;
      expect(box.width).toBeLessThanOrEqual(360 * 0.8 + 1);
      await chip.focus();
      await page.keyboard.press('Enter');
      await expect(page.getByRole('heading', { level: 2, name: long })).toBeFocused();
      expect(await horizontalOverflow(page)).toBe(0);
    } finally {
      await owner.call('PUT', `/admin/menu/categories/${cat.id}`, { slug: cat.slug, name: cat.name, hint: cat.hint, visible: cat.visible });
      await owner.dispose();
    }
  });

  test('prodotti, prezzi e ordine invariati; il collegamento ?cat= apre la categoria', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await go(page, '/menu?cat=cocktail');
    await expect(page.locator('.menu-item').first()).toBeVisible();
    const total = await page.locator('.menu-item').count();
    await go(page, '/menu');
    await expect(page.locator('.menu-item')).toHaveCount(total);
    await expect(page.getByRole('navigation', { name: 'Categorie del menu' }).getByRole('button', { name: /^Tutto/ })).toContainText(String(total));
  });
});

test.describe('3. «Perché FireFlies»', () => {
  for (const width of [390, 1366, 1920]) {
    test(`foto proporzionata e soggetto intero a ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: width < 500 ? 844 : 900 });
      await go(page, '/locale');
      const section = page.locator('.story');
      await section.scrollIntoViewIfNeeded();
      const img = section.locator('.story__photo img');
      await expect(img).toBeVisible();
      await expect.poll(() => img.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0)).toBe(true);
      const m = await img.evaluate((el: HTMLImageElement) => {
        const r = el.getBoundingClientRect();
        return { w: r.width, h: r.height, nw: el.naturalWidth, nh: el.naturalHeight, fit: getComputedStyle(el).objectFit };
      });
      expect(m.fit).toBe('cover');
      expect(m.w / m.h, 'riquadro verticale 4:5').toBeCloseTo(0.8, 1);
      // ritaglio leggero: la parte visibile della foto (3:4) è almeno il 90 % dell'originale
      const visible = Math.min(1, (m.w / m.h) / (m.nw / m.nh)) * Math.min(1, (m.nw / m.nh) / (m.w / m.h));
      expect(visible).toBeGreaterThan(0.9);
      if (width >= 900) {
        expect(m.h, 'non più alta dello schermo').toBeLessThanOrEqual(900);
        const text = (await section.locator('.story__text').boundingBox())!;
        const photo = (await section.locator('.story__photo').boundingBox())!;
        expect(text.x + text.width, 'testo e foto affiancati').toBeLessThan(photo.x);
      }
      expect(await horizontalOverflow(page)).toBe(0);
      await test.info().attach(`perché fireflies ${width}px`, { body: await section.screenshot(), contentType: 'image/png' });
    });
  }
});

test.describe('5. Menu dei gestori sul telefono', () => {
  for (const width of [360, 375, 390, 430, 768]) {
    test(`intestazione compatta, pannello separato, Esci riconoscibile a ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 760 });
      await signIn(page);
      await go(page, '/gestione/richieste');
      const header = page.locator('.admin__side');
      expect((await header.boundingBox())!.height).toBeLessThanOrEqual(64);
      const toggle = page.getByRole('button', { name: /Menu, pagina attuale: Richieste/ });
      await expect(page.locator('.admin__current')).toContainText('Richieste');
      const logo = (await page.locator('.admin__brand img').boundingBox())!;
      expect(Math.abs(logo.width / logo.height - 1), 'logo non schiacciato').toBeLessThan(0.35);
      await toggle.click();
      const drawer = page.locator('#admin-drawer');
      await expect(drawer).toBeVisible();
      await expect(page.locator('.admin__backdrop')).toBeVisible();
      const logout = drawer.getByRole('button', { name: 'Esci' });
      await expect(logout).toBeVisible();
      expect((await logout.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      await expect(drawer.getByRole('link', { name: 'Vedi il sito' })).toBeVisible();
      expect(await page.evaluate(() => document.body.classList.contains('is-locked')), 'la pagina sotto non scorre').toBe(true);
      const box = (await drawer.boundingBox())!;
      expect(box.x + box.width).toBeLessThanOrEqual(width + 0.5);
      expect(await horizontalOverflow(page)).toBe(0);
      await shot(page, `menu gestori aperto ${width}px`);
      // tocco sullo sfondo scuro: si chiude
      await page.mouse.click(width / 2, 750);
      await expect(drawer).toBeHidden();
      expect(await page.evaluate(() => document.body.classList.contains('is-locked'))).toBe(false);
      // scelta di una voce: si chiude e cambia pagina
      await toggle.click();
      await drawer.getByRole('link', { name: 'Foto' }).click();
      await expect(drawer).toBeHidden();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Foto/);
      await expect(page.getByRole('button', { name: /pagina attuale: Foto/ })).toBeFocused({ timeout: 1 }).catch(() => undefined);
    });
  }

  test('lo staff vede solo le sue voci e il pannello non copre le notifiche', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 760 });
    await signIn(page, 'Staff');
    await page.getByRole('button', { name: /pagina attuale/ }).click();
    const nav = page.getByRole('navigation', { name: 'Area gestori' });
    await expect(nav.getByRole('link', { name: 'Impostazioni' })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Personalizza il sito' })).toHaveCount(0);
    const zDrawer = await page.locator('.admin__side').evaluate((el) => Number(getComputedStyle(el).zIndex));
    const zToast = await page.evaluate(() => {
      const t = document.createElement('div');
      t.className = 'toast';
      document.body.append(t);
      const z = Number(getComputedStyle(t).zIndex);
      t.remove();
      return z;
    });
    expect(zToast, 'le notifiche restano sopra il menu').toBeGreaterThan(zDrawer);
  });
});

test.describe('4. Invio della prenotazione', () => {
  test('doppio tocco su «Invia»: una sola richiesta al server, pulsante in caricamento', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    let posts = 0;
    await page.route('**/api/v1/public/reservations', async (r) => {
      posts++;
      await new Promise((ok) => setTimeout(ok, 600));
      await r.continue();
    });
    await go(page, '/');
    const d = await openTableBooking(page, () => page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click());
    await fillToLastStep(page, d);
    const send = d.getByRole('button', { name: 'Invia la richiesta' });
    await send.evaluate((b: HTMLButtonElement) => { b.click(); b.click(); });
    await expect(d.getByRole('button', { name: /Invio in corso/ })).toBeDisabled();
    await expect(page.getByRole('dialog', { name: 'Richiesta inviata' })).toBeVisible({ timeout: 10_000 });
    expect(posts, 'una sola richiesta inviata').toBe(1);
  });

  test('429: messaggio chiaro con l\'attesa, nessun blocco della pagina, la richiesta si può riprovare', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    let first = true;
    await page.route('**/api/v1/public/reservations', async (r) => {
      if (first) {
        first = false;
        await r.fulfill({ status: 429, headers: { 'Retry-After': '720', 'Content-Type': 'application/problem+json' },
          body: JSON.stringify({ status: 429, code: 'RATE_LIMITED', title: 'Troppe richieste',
            detail: "Da questa connessione sono già arrivate molte richieste nell'ultima ora: riprova tra 12 minuti o chiamaci.",
            context: { retryAfterSeconds: 720 } }) });
        return;
      }
      await r.continue();
    });
    await go(page, '/');
    const d = await openTableBooking(page, () => page.getByRole('button', { name: /Richiedi un tavolo/ }).first().click());
    await fillToLastStep(page, d);
    await d.getByRole('button', { name: 'Invia la richiesta' }).click();
    await expect(d.getByText(/riprova tra 12 minuti o chiamaci/)).toBeVisible();
    await shot(page, 'prenotazione: messaggio 429');
    await d.getByRole('button', { name: 'Invia la richiesta' }).click();
    await expect(page.getByRole('dialog', { name: 'Richiesta inviata' })).toBeVisible({ timeout: 10_000 });
  });
});

async function fillToLastStep(page: Page, d: ReturnType<Page['getByRole']>) {
  await d.locator('#bk-date').fill(bookableDay(3, 0));
  await d.getByRole('button', { name: 'Avanti' }).click();
  await d.getByRole('radio', { name: '20:00' }).click();
  await d.getByRole('button', { name: 'Avanti' }).click();
  await d.getByRole('button', { name: 'Avanti' }).click();
  await d.getByLabel('Nome').fill(`Doppio tocco ${Date.now()}`);
  await d.getByLabel('Telefono', { exact: true }).fill(testPhone());
  await d.getByLabel(/Ho letto l'informativa/).check();
  await d.getByRole('button', { name: 'Avanti' }).click();
}

test.describe('6. Impostazioni', () => {
  test('capienza unica: un solo campo, nessuna tabella per giorno e fascia, nessun limite di persone del modulo', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    await signIn(page);
    await go(page, '/gestione/impostazioni');
    await expect(page.getByLabel('Capienza massima del locale (persone)')).toBeVisible();
    await expect(page.getByLabel(/Persone massime dal sito/)).toHaveCount(0);
    await expect(page.getByRole('heading', { name: /Persone al massimo per giorno e fascia|Limiti di persone per data/ })).toHaveCount(0);
    // desktop senza buchi: capienza e regole sulla stessa riga, stessa altezza
    const venue = (await page.locator('.cap-grid__venue').boundingBox())!;
    const policy = (await page.locator('.cap-grid__policy').boundingBox())!;
    expect(Math.abs(venue.y - policy.y)).toBeLessThan(2);
    expect(Math.abs(venue.height - policy.height)).toBeLessThan(2);
    expect(policy.x - (venue.x + venue.width)).toBeLessThan(40);
    await page.locator('#capienza').scrollIntoViewIfNeeded();
    await shot(page, 'impostazioni: capienza e calendario 1366px');
  });

  test('riepilogo settimanale: frecce, intervallo, settimana corrente, una fascia per volta', async ({ page }) => {
    await page.setViewportSize({ width: 1366, height: 900 });
    await signIn(page);
    await go(page, '/gestione/impostazioni');
    const panel = page.locator('.cap-grid__week');
    await expect(panel.locator('.week-table tbody tr')).toHaveCount(7);
    await expect(panel.getByText('Questa settimana', { exact: true }).first()).toBeVisible();
    await expect(panel.locator('tr.is-today')).toHaveCount(1);
    const label = await panel.locator('.week-nav__label').textContent();
    await panel.getByRole('button', { name: 'Settimana successiva' }).click();
    await expect(panel.locator('.week-nav__label')).not.toHaveText(label!);
    await expect(panel.locator('tr.is-today')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Questa settimana' })).toBeVisible();
    await panel.getByRole('button', { name: 'Settimana precedente' }).click();
    await expect(panel.locator('.week-nav__label')).toHaveText(label!);
    for (const band of ['Colazione', 'Pomeriggio', 'Aperitivo', 'Sera']) {
      await panel.getByLabel('Fascia').selectOption({ label: band });
      await expect(panel.locator('.week-table caption')).toContainText(band);
      await expect(panel.locator('.week-table tbody tr')).toHaveCount(7);
    }
    await expect(panel.locator('thead th')).toHaveText(['Giorno', 'Prenotazioni', 'Persone confermate', 'Capienza', 'Disponibili']);
  });

  test('chiusura di un giorno, intervallo e apertura straordinaria: elenco, calendario del sito, modifica, eliminazione, conflitti', async ({ page }) => {
    const owner = await ownerApi();
    const first = bookableDay(20, 0);
    const last = plus(first, 3);
    const booked = plus(first, 10);
    try {
      // una prenotazione confermata in un giorno che poi verrà chiuso: segnalata, mai cancellata
      const res = await owner.call('POST', '/admin/reservations', { source: 'PHONE', date: booked, time: '19:00', guests: 2, name: `Conflitto ${Date.now()}`, phone: testPhone(), confirmNow: true, needsCallback: false });
      await page.setViewportSize({ width: 1366, height: 900 });
      await signIn(page);
      await go(page, '/gestione/impostazioni');
      const panel = page.locator('.cap-grid__dates');
      // intervallo
      await panel.getByRole('radio', { name: 'Più giorni' }).check();
      await panel.getByLabel('Dal', { exact: true }).fill(first);
      await panel.getByLabel('Al (compreso)').fill(last);
      await panel.getByLabel('Nota (facoltativa)').fill('Ferie E2E');
      await panel.getByRole('button', { name: 'Aggiungi' }).click();
      await expect(page.locator('.toast')).toContainText('Chiusura straordinaria salvata');
      await expect(panel.getByRole('listitem').filter({ hasText: 'Ferie E2E' })).toContainText('4 giorni');
      // validazione: fine prima dell'inizio
      await panel.getByRole('radio', { name: 'Più giorni' }).check();
      await panel.getByLabel('Dal', { exact: true }).fill(plus(first, 30));
      await panel.getByLabel('Al (compreso)').fill(plus(first, 28));
      await panel.getByRole('button', { name: 'Aggiungi' }).click();
      await expect(panel.getByRole('alert')).toContainText("L'ultimo giorno non può essere prima del primo");
      // sovrapposta: rifiutata dal backend
      await panel.getByRole('radio', { name: 'Un giorno' }).check();
      await panel.getByLabel('Giorno', { exact: true }).fill(plus(first, 1));
      await panel.getByRole('button', { name: 'Aggiungi' }).click();
      await expect(page.locator('.toast')).toContainText('si sovrappongono');
      // un giorno con prenotazione: avviso con i conteggi
      await panel.getByLabel('Giorno', { exact: true }).fill(booked);
      await panel.getByRole('button', { name: 'Aggiungi' }).click();
      await expect(panel.locator('.notice--warn')).toContainText('1 confermate (2 persone)');
      // apertura straordinaria su due giorni
      await panel.getByRole('radio', { name: 'Più giorni' }).check();
      await panel.getByLabel('Dal', { exact: true }).fill(plus(first, 40));
      await panel.getByLabel('Al (compreso)').fill(plus(first, 41));
      await panel.getByRole('radio', { name: 'Apertura straordinaria' }).check();
      await panel.getByLabel('Apre alle').fill('17:00');
      await panel.getByLabel('Chiude alle').fill('23:30');
      await panel.getByRole('button', { name: 'Aggiungi' }).click();
      await expect(page.locator('.toast')).toContainText('Apertura straordinaria salvata');
      await shot(page, 'impostazioni: chiusure e aperture');

      // calendario pubblico: i giorni dell'intervallo non sono prenotabili
      const config = await owner.call('GET', '/admin/booking-config');
      const options = await (await page.request.get('/api/v1/public/booking-options')).json();
      for (let d = first; d <= last; d = plus(d, 1)) expect(options.closedDates).toContain(d);
      expect(options.closedDates).toContain(booked);

      // modifica: l'intervallo si accorcia
      const ferie = panel.getByRole('listitem').filter({ hasText: 'Ferie E2E' });
      await expect(ferie).toContainText('4 giorni');
      await ferie.getByRole('button', { name: /^Modifica/ }).click();
      await panel.getByLabel('Al (compreso)').fill(plus(first, 1));
      await panel.getByRole('button', { name: 'Salva le modifiche' }).click();
      await expect(page.locator('.toast')).toContainText('aggiornata');
      await expect(ferie).toContainText('2 giorni');
      // eliminazione con conferma
      await ferie.getByRole('button', { name: /^Elimina/ }).click();
      await ferie.getByRole('group', { name: /Conferma l'eliminazione/ }).getByRole('button', { name: 'Elimina' }).click();
      await expect(page.locator('.toast')).toContainText('eliminata');
      await expect(panel.getByRole('list', { name: 'Date straordinarie' })).not.toContainText('Ferie E2E');
      // la prenotazione nel giorno chiuso è ancora confermata
      const after = await owner.call('GET', `/admin/reservations/${res.summary.id}`);
      expect(after.summary.status).toBe('CONFIRMED');
      await owner.call('PATCH', `/admin/reservations/${res.summary.id}/status`, { version: after.summary.version, status: 'CANCELLED', reason: 'OTHER' });
    } finally {
      const config = await owner.call('GET', '/admin/booking-config');
      for (const e of config.openingExceptions as { id: string; day: string }[]) {
        if (e.day >= first) await owner.call('DELETE', `/admin/opening-exceptions/${e.id}`).catch(() => undefined);
      }
      await owner.dispose();
    }
  });

  for (const width of [360, 390]) {
    test(`orari di apertura sul telefono a ${width}px: giorni chiari, campi nei riquadri, chiuso senza campi vuoti`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 });
      await signIn(page);
      await go(page, '/gestione/impostazioni');
      const list = page.getByRole('list', { name: 'Orari per giorno' });
      await expect(list.getByRole('listitem')).toHaveCount(7);
      const panel = (await page.locator('[aria-labelledby="set-hours"]').boundingBox())!;
      for (const input of await list.locator('input').all()) {
        const b = (await input.boundingBox())!;
        expect(b.x).toBeGreaterThanOrEqual(panel.x - 0.5);
        expect(b.x + b.width).toBeLessThanOrEqual(panel.x + panel.width + 0.5);
        expect(b.height).toBeGreaterThanOrEqual(44);
      }
      const closed = list.getByRole('listitem').filter({ hasText: 'Chiuso tutto il giorno' });
      if (await closed.count()) await expect(closed.first().locator('input')).toHaveCount(0);
      await expect(list.getByRole('switch', { name: /^Lunedì: / })).toBeVisible();
      expect(await horizontalOverflow(page)).toBe(0);
      await page.locator('[aria-labelledby="set-hours"]').scrollIntoViewIfNeeded();
      await shot(page, `orari ${width}px`);
    });
  }
});

test.describe('Responsività', () => {
  test('nessuno scorrimento orizzontale da 360 a 1920 px (menu, il locale, impostazioni)', async ({ page }) => {
    await signIn(page);
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 900 });
      for (const route of ['/menu', '/locale', '/gestione/impostazioni']) {
        await go(page, route);
        await page.waitForTimeout(150);
        expect(await horizontalOverflow(page), `${route} a ${width}px`).toBe(0);
      }
      await shot(page, `impostazioni ${width}px`);
    }
  });

  test('riepilogo settimanale sul telefono: schede leggibili, nessuna colonna tagliata', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await signIn(page);
    await go(page, '/gestione/impostazioni');
    const row = await weekSummaryRow(page, isoOf(new Date()), 'Aperitivo');
    const box = (await row.boundingBox())!;
    expect(box.x + box.width).toBeLessThanOrEqual(360);
    await row.scrollIntoViewIfNeeded();
    expect(await horizontalOverflow(page)).toBe(0);
    await shot(page, 'riepilogo settimanale 360px');
  });
});
