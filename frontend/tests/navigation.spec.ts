// Test funzionali del walkthrough: ogni interazione richiesta dal brief deve funzionare davvero.
import { expect, test } from '@playwright/test';
import { bookableDay, go, openTableBooking, signIn, stubGoogleMaps, testPhone, watchProblems } from './helpers';
import { keepBaseline, photoSrc } from './baseline';

test.describe('Area pubblica — desktop', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('navigazione principale e CTA hero', async ({ page }) => {
    const problems = watchProblems(page);
    await go(page, '/');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText("Dal primo caffè all'ultimo brindisi.");
    await expect(page.getByRole('img', { name: /FireFlies — Breakfast and Aperitif/ }).first()).toBeVisible();

    const nav = page.getByRole('navigation', { name: 'Principale' });
    for (const [label, h1] of [['Menu', 'Il menu'], ['Eventi', 'Le serate'], ['Il locale', 'Una lucciola a Martano.'], ['Galleria', 'Galleria'], ['Contatti', 'Contatti']]) {
      await nav.getByRole('link', { name: label, exact: true }).click();
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(h1);
      await expect(nav.getByRole('link', { name: label, exact: true })).toHaveAttribute('aria-current', 'page');
    }
    await page.getByRole('link', { name: /torna alla home/ }).click();
    await page.getByRole('link', { name: /Scopri il menu/ }).click();
    await expect(page).toHaveURL(/#\/menu$/);
    await go(page, '/');
    await page.getByRole('main').getByRole('link', { name: 'Le prossime serate' }).first().click();
    await expect(page).toHaveURL(/#\/eventi$/);
    expect(problems).toEqual([]);
  });

  test('«Che momento è al Fire?» cambia contenuto e tema', async ({ page }) => {
    await go(page, '/');
    const section = page.locator('section.moment');
    await expect(section.getByRole('heading', { level: 3 })).toHaveText("L'ora dell'aperitivo");
    await section.getByRole('button', { name: /07/ }).click();
    await expect(section.getByRole('heading', { level: 3 })).toHaveText('Colazione al bancone');
    await expect(section).toHaveClass(/theme-day/);
    await page.getByLabel('Ora del giorno').fill('22');
    await expect(section.getByRole('heading', { level: 3 })).toHaveText('Le lucciole si accendono');
    await section.getByRole('link', { name: /Vedi nel menu/ }).click();
    await expect(page.getByRole('button', { name: /^Cocktail/ })).toHaveAttribute('aria-pressed', 'true');
  });

  test('menu: filtri, ricerca, stato vuoto e dettaglio voce', async ({ page }) => {
    await go(page, '/menu');
    await page.getByRole('button', { name: /^Cocktail/ }).click();
    await expect(page.locator('.menu-item')).toHaveCount(4);
    await page.getByRole('button', { name: /^Tutto/ }).click();
    await page.getByLabel('Cerca nel menu').fill('lamponi');
    await expect(page.locator('.menu-item')).toHaveCount(1);
    await page.getByLabel('Cerca nel menu').fill('sushi');
    await expect(page.getByRole('heading', { name: 'Niente da queste parti.' })).toBeVisible();
    await page.getByRole('button', { name: 'Mostra tutto il menu' }).click();
    await expect(page.locator('.menu-item')).toHaveCount(14); // 15 voci, 1 nascosta

    await page.locator('.menu-item', { hasText: 'Tagliere FireFlies' }).click();
    const dialog = page.getByRole('dialog', { name: 'Tagliere FireFlies' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('€ 14,00');
    await expect(dialog).toContainText('prezzo dimostrativo');
    await expect(dialog).toContainText('Da dichiarare');
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(page.locator('.menu-item', { hasText: 'Tagliere FireFlies' })).toBeFocused();
  });

  test('eventi: lista, filtro, calendario e dettaglio', async ({ page }) => {
    await go(page, '/eventi');
    await page.getByRole('button', { name: 'Format ricorrenti' }).click();
    await expect(page.locator('.event-row')).toHaveCount(1);
    await expect(page.locator('.event-row')).toContainText('Date da annunciare');
    await page.getByRole('button', { name: 'Tutti' }).click();
    await expect(page.getByText('Aperitivo e vinili')).toHaveCount(0); // bozza non pubblica
    await page.getByRole('link', { name: /^Esempio demo DJ set al Fire/ }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('DJ set al Fire');
    await expect(page.getByText("Serata d'esempio: data e artista non reali")).toBeVisible();
    await page.getByRole('button', { name: 'Richiedi un tavolo per questa serata' }).click();
    await expect(page.getByRole('dialog', { name: 'Richiedi un tavolo' })).toContainText('Per la serata DJ set al Fire');
  });

  test('richiesta tavolo in 5 passi: validazione, riepilogo modificabile, invio reale «in attesa»', async ({ page }) => {
    await go(page, '/');
    const dialog = await openTableBooking(page, () => page.getByRole('banner').getByRole('button', { name: 'Richiedi un tavolo' }).click());
    await expect(dialog.getByText('Passo 1 di 5')).toBeVisible();
    // passo 1: giorno obbligatorio
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await expect(dialog.locator('#bk-date-err')).toContainText('Scegli il giorno');
    await dialog.locator('#bk-date').fill(bookableDay(2, 1));
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    // passo 2: orari veri del giorno, dal backend
    await expect(dialog.getByText('Passo 2 di 5')).toBeVisible();
    await expect(dialog.getByRole('radiogroup', { name: 'Aperitivo' })).toBeVisible();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await expect(dialog.locator('#bk-time-err')).toBeVisible();
    await dialog.getByRole('radio', { name: '19:30' }).click();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    // passo 3: persone
    await dialog.getByRole('button', { name: '6 persone' }).click();
    await expect(dialog.getByLabel('Persone', { exact: true })).toHaveValue('6');
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    // passo 4: dati con errori inline; telefono obbligatorio, email facoltativa
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await expect(dialog.getByLabel('Nome')).toHaveAttribute('aria-invalid', 'true');
    await expect(dialog.getByLabel('Nome')).toBeFocused();
    await expect(dialog.locator('#bk-phone-err')).toContainText('Il telefono è obbligatorio');
    await dialog.getByLabel('Nome').fill('Prova');
    await dialog.getByLabel('Telefono', { exact: true }).fill('non-valido');
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await expect(dialog.locator('#bk-phone-err')).toContainText('Controlla il numero');
    await dialog.getByLabel('Telefono', { exact: true }).fill(testPhone());
    await dialog.getByLabel(/Ho letto l'informativa/).check();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    // passo 5: riepilogo con modifica
    await expect(dialog.getByText('Controlla la richiesta')).toBeVisible();
    await expect(dialog).toContainText('19:30');
    await dialog.getByRole('button', { name: 'Modifica persone' }).click();
    await dialog.getByRole('button', { name: '4 persone' }).click();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await expect(dialog.locator('.booking-recap')).toContainText('4');
    await dialog.getByRole('button', { name: 'Invia la richiesta' }).click();
    // esito: richiesta salvata in attesa, mai presentata come prenotazione confermata
    const done = page.getByRole('dialog', { name: 'Richiesta inviata' });
    await expect(done).toContainText('Richiesta ricevuta: non è ancora una prenotazione.');
    await expect(done).toContainText('riservato solo dopo la conferma del locale');
    await expect(done.locator('.booking-ref')).toHaveText(/^FF-[A-Z0-9]{6}$/);
    await expect(done).toContainText('In attesa di conferma');
    await done.getByRole('button', { name: 'Chiudi' }).first().click();
    await expect(done).toBeHidden();
  });

  test('galleria: lightbox con frecce e chiusura', async ({ page }) => {
    await go(page, '/galleria');
    await page.locator('.gallery__item button').first().click();
    const box = page.getByRole('dialog', { name: 'Foto 1 di 26' });
    await expect(box).toBeVisible();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('dialog', { name: 'Foto 2 di 26' })).toBeVisible();
    await page.getByRole('button', { name: 'Foto precedente' }).click();
    await expect(box).toBeVisible();
    await page.getByRole('button', { name: 'Chiudi' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('ogni link interno porta a una vista esistente', async ({ page }) => {
    const seen = new Set<string>();
    for (const route of ['/', '/menu', '/eventi', '/locale', '/contatti', '/galleria']) {
      await go(page, route);
      const hrefs = await page.locator('a[href^="#/"]').evaluateAll((as) => as.map((a) => a.getAttribute('href')!));
      hrefs.forEach((h) => seen.add(h));
    }
    for (const href of seen) {
      if (href.startsWith('#/gestione')) continue;
      await page.goto(`/${href}`);
      await expect(page.getByRole('heading', { level: 1 }), href).not.toHaveText(/si è persa|non trovata/);
    }
    // nessun link vuoto o "#"
    const empty = await page.locator('a:not([href]), a[href="#"], a[href=""]').count();
    expect(empty).toBe(0);
  });
});

test.describe('Area pubblica — mobile', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

  test('menu mobile si apre, naviga e si chiude', async ({ page }) => {
    await go(page, '/');
    const toggle = page.getByRole('button', { name: 'Apri il menu di navigazione' });
    await toggle.click();
    await expect(page.getByRole('button', { name: 'Chiudi il menu di navigazione' })).toHaveAttribute('aria-expanded', 'true');
    await page.getByRole('navigation', { name: 'Menu mobile' }).getByRole('link', { name: 'Eventi' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Le serate');
    await expect(page.locator('#mobile-menu')).toBeHidden();
    await page.getByRole('button', { name: 'Apri il menu di navigazione' }).click();
    await page.keyboard.press('Escape');
    await expect(page.locator('#mobile-menu')).toBeHidden();
  });

  test('CTA sticky apre la richiesta tavolo a tutto schermo', async ({ page }) => {
    await go(page, '/menu');
    const dialog = await openTableBooking(page, () => page.locator('.mobile-cta').getByRole('button', { name: 'Richiedi un tavolo' }).click());
    await expect(dialog).toBeVisible();
    const box = await dialog.boundingBox();
    expect(box?.width).toBe(390);
    await dialog.getByRole('button', { name: 'Chiudi' }).click();
    await expect(dialog).toBeHidden();
  });
});

test.describe('Area gestori', () => {
  test.use({ viewport: { width: 1440, height: 900 } });
  keepBaseline();

  test('senza accesso si viene portati al login; errori di compilazione', async ({ page }) => {
    await go(page, '/gestione/richieste');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
    await page.getByRole('button', { name: 'Accedi' }).click();
    await expect(page.getByText("Inserisci un'email valida")).toBeVisible();
    await expect(page.getByLabel('Email')).toBeFocused();
  });

  test('menu: nascondere una voce si riflette sul sito; modifica salvata sul server', async ({ page }) => {
    await signIn(page);
    await page.getByRole('navigation', { name: 'Area gestori' }).getByRole('link', { name: 'Menu' }).click();
    await page.getByRole('row', { name: /Birra/ }).getByRole('switch').click();
    await expect(page.getByRole('status')).toContainText('«Birra» nascosta sul sito');
    await page.getByRole('link', { name: 'Modifica Tagliere FireFlies' }).click();
    await page.getByLabel('Prezzo (€)').fill('abc');
    await page.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByText('Scrivi un prezzo in euro')).toBeVisible();
    await page.getByLabel('Prezzo (€)').fill('12,50');
    await page.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByRole('status')).toContainText('«Tagliere FireFlies» salvata');
    await expect(page.getByRole('row', { name: /Tagliere FireFlies/ })).toContainText('€ 12,50');

    await page.getByRole('link', { name: 'Vedi il sito' }).click();
    await page.getByRole('navigation', { name: 'Principale' }).getByRole('link', { name: 'Menu', exact: true }).click();
    await expect(page.locator('.menu-item', { hasText: 'Tagliere FireFlies' })).toContainText('€ 12,50');
    await expect(page.locator('.menu-item', { hasText: /^Birra/ })).toHaveCount(0);
    // salvato davvero: dopo il ricaricamento (e per ogni visitatore) resta così
    await page.reload();
    await expect(page.locator('.menu-item', { hasText: 'Tagliere FireFlies' })).toContainText('€ 12,50');
    await expect(page.locator('.menu-item', { hasText: 'Tagliere FireFlies' }).locator('.badge--demo')).toHaveCount(0);
  });

  test('eventi: nuova bozza con validazione', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/eventi/nuovo');
    const visible = page.getByRole('switch', { name: 'Visibile sul sito' });
    await expect(visible).toHaveAttribute('aria-checked', 'false'); // un evento nuovo nasce bozza
    await page.getByRole('button', { name: 'Salva', exact: true }).click();
    await expect(page.getByText('Dai un titolo alla serata')).toBeVisible();
    await page.getByLabel('Titolo').fill('Serata di prova');
    await visible.click();
    await page.getByRole('button', { name: 'Salva', exact: true }).click();
    await expect(page.getByText('Per mettere una serata sul sito servono la data')).toBeVisible();
    await visible.click();
    await page.getByRole('button', { name: 'Salva', exact: true }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'Serata di prova' })).toBeVisible();
  });

  test('impostazioni: orari verificati tolgono l’etichetta dal sito', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/impostazioni');
    await page.getByLabel(/Orari verificati dal gestore/).check();
    await page.getByRole('button', { name: 'Salva', exact: true }).click();
    await expect(page.locator('.toast')).toContainText('Impostazioni salvate');
    await go(page, '/');
    await expect(page.locator('.hero').getByText('orari da confermare')).toHaveCount(0);
  });
});

test.describe('Mappa e area del titolare', () => {
  test.use({ viewport: { width: 1440, height: 900 } });
  keepBaseline();

  test('contatti: la mappa Google si carica solo dopo il clic', async ({ page }) => {
    await stubGoogleMaps(page);
    const requests: string[] = [];
    page.on('request', (r) => r.url().includes('google.com') && requests.push(r.url()));
    await go(page, '/contatti');
    await expect(page.getByText('Mappa di Google Maps')).toBeVisible();
    await expect(page.locator('iframe')).toHaveCount(0);
    expect(requests).toEqual([]); // nessuna richiesta a Google prima del consenso
    await page.getByRole('button', { name: 'Mostra la mappa' }).click();
    const frame = page.locator('iframe.map__frame');
    await expect(frame).toHaveAttribute('src', /maps\.google\.com\/maps\?q=FireFlies%20Aperitif%2C%20Via%20Giovanni%20XXIII.*output=embed/);
    await expect(frame).toHaveAttribute('title', /Mappa/);
  });

  test('titolare: personalizza home, sezioni, storia e staff', async ({ page }) => {
    await signIn(page);
    await page.getByRole('navigation', { name: 'Area gestori' }).getByRole('link', { name: 'Personalizza il sito' }).click();
    await page.getByLabel('Frase principale').fill('Accendi la tua serata.');
    await page.getByRole('switch', { name: /Striscia di foto/ }).click();
    await page.getByLabel('Racconto', { exact: true }).fill('Abbiamo aperto per dare a Martano un bancone dove stare bene.');
    await page.getByLabel('Testo definitivo').check();
    await page.getByLabel('Nome (1)').fill('Marco');
    await page.getByRole('button', { name: 'Salva e pubblica' }).click();
    await expect(page.getByRole('status')).toContainText('Sito aggiornato');

    await go(page, '/');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Accendi la tua serata.');
    await expect(page.locator('section.strip')).toHaveCount(0);
    await go(page, '/locale');
    await expect(page.getByText('Abbiamo aperto per dare a Martano')).toBeVisible();
    await expect(page.getByText('Storia del locale da scrivere')).toHaveCount(0);
    await expect(page.locator('.team__name').first()).toHaveText('Marco');
  });

  test('foto: caricamento con controlli, uso nel menu e in galleria', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/foto');
    const input = page.locator('input[type=file]').first();
    await input.setInputFiles({ name: 'menu.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4') });
    await expect(page.getByRole('alert')).toContainText('non è una foto JPG, PNG o WebP');
    await input.setInputFiles('public/assets/fireflies/logo/simbolo-512.png');
    await expect(page.getByText('Caricata ora')).toBeVisible();
    const alt = page.getByLabel(/Descrizione per chi non vede/).first();
    await alt.fill('Nuovo cocktail della casa');
    await alt.press('Enter'); // salvataggio della descrizione, confermato dal server prima di proseguire
    await expect(page.getByRole('status')).toContainText('Descrizione salvata');
    await page.getByRole('switch', { name: 'Non in galleria' }).first().click();
    await expect(page.getByRole('switch', { name: 'In galleria' }).first()).toBeVisible();

    await go(page, '/gestione/menu/{Cornetto}');
    await page.locator('.picker__opt').filter({ has: page.getByRole('radio', { name: 'Nuovo cocktail della casa' }) }).click();
    await expect(page.getByRole('radio', { name: 'Nuovo cocktail della casa' })).toBeChecked();
    await page.getByRole('button', { name: 'Salva' }).click();
    // variante ricodificata dal backend (PNG con trasparenza), non più un URL blob del browser
    await expect(page.getByRole('row', { name: /Cornetto/ }).locator('img')).toHaveAttribute('src', /^\/media\/.+\.png$/);

    await go(page, '/galleria');
    await expect(page.getByRole('button', { name: 'Ingrandisci: Nuovo cocktail della casa' })).toBeVisible();

    // una foto in uso non si può eliminare
    await go(page, '/gestione/foto');
    await page.locator('.photos__item:has(input[value="Nuovo cocktail della casa"])').getByRole('button', { name: 'Elimina' }).click();
    await expect(page.getByRole('alert')).toContainText('è usata in menu «Cornetto»');
  });

  test('comunicazioni: un avviso pubblicato compare sul sito', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/comunicazioni');
    await page.getByRole('button', { name: 'Aggiungi avviso' }).click();
    await expect(page.getByText('Scrivi il testo dell’avviso')).toBeVisible();
    await page.getByLabel('Testo').fill('Stasera chiusura anticipata alle 21:00');
    await page.getByLabel('Tipo').selectOption('importante');
    await page.getByRole('button', { name: 'Aggiungi avviso' }).click();
    await go(page, '/menu');
    const region = page.getByRole('region', { name: 'Avvisi del locale' });
    await expect(region).toContainText('Stasera chiusura anticipata alle 21:00');
    await region.getByRole('button', { name: 'Nascondi questo avviso' }).last().click();
    await expect(region).not.toContainText('Stasera chiusura anticipata');
  });

  test('staff: non vede né apre le pagine del titolare', async ({ page }) => {
    await signIn(page, 'Staff');
    const nav = page.getByRole('navigation', { name: 'Area gestori' });
    await expect(nav.getByRole('link', { name: 'Personalizza il sito' })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Impostazioni' })).toHaveCount(0);
    await go(page, '/gestione/sito');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Riservato al titolare');
  });

  test('nessun link pubblico all’area gestori, ma l’URL diretto funziona', async ({ page }) => {
    for (const route of ['/', '/menu', '/contatti']) {
      await go(page, route);
      await expect(page.locator('a[href^="#/gestione"]')).toHaveCount(0);
    }
    await page.getByRole('button', { name: 'Apri il menu di navigazione' }).isVisible().catch(() => false);
    await go(page, '/gestione/login');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
  });
});

test.describe('Il locale — titolari', () => {
  test.use({ viewport: { width: 1440, height: 900 } });
  keepBaseline();

  test('foto dei titolari accanto alle descrizioni, retro della maglia e testi modificabili', async ({ page }) => {
    await go(page, '/locale');
    const owners = page.locator('.owner');
    await expect(owners).toHaveCount(3);
    await expect(owners.first().getByRole('img', { name: /maglia gialla/ }).first()).toBeVisible();
    await expect(owners.first()).toContainText('«Il mio gruppo sanguigno è A‑PERITIVO.»');
    const turn = owners.first().getByRole('button', { name: /Gira la maglia/ });
    await turn.click();
    await expect(owners.first().getByRole('button', { name: /Torna al fronte/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(owners.first().locator('.owner__flip')).toHaveClass(/is-back/);

    await signIn(page);
    await go(page, '/gestione/sito');
    await page.getByLabel('Nome titolare 2').fill('Luca');
    await page.locator('.owner-edit').nth(1).getByLabel('Descrizione').fill('Lo trovi al bancone dalle sei: chiedigli il mojito.');
    await page.getByRole('button', { name: 'Salva e pubblica' }).click();
    await go(page, '/locale');
    await expect(owners.nth(1).getByRole('heading', { level: 3 })).toHaveText('Luca');
    await expect(owners.nth(1)).toContainText('chiedigli il mojito');
  });
});

test.describe('Revisione 19/10', () => {
  test.use({ viewport: { width: 1440, height: 900 } });
  keepBaseline();

  test('navbar con Home attiva, footer con Home, mappa nella home con indirizzo confermato', async ({ page }) => {
    await stubGoogleMaps(page);
    await go(page, '/');
    const nav = page.getByRole('navigation', { name: 'Principale' });
    await expect(nav.getByRole('link', { name: 'Home', exact: true })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('navigation', { name: 'Footer' }).getByRole('link', { name: 'Home', exact: true })).toBeVisible();
    const visit = page.locator('section.visit');
    await expect(visit).toContainText('Via Giovanni XXIII, 19 — 73025 Martano (LE)');
    // ordine: indirizzo → mappa → pulsanti
    const y = async (sel: string) => (await visit.locator(sel).first().boundingBox())!.y;
    expect(await y('.visit__addr')).toBeLessThan(await y('.visit__map'));
    expect(await y('.visit__map')).toBeLessThan(await y('.visit__ctas'));
    await visit.getByRole('button', { name: 'Mostra la mappa' }).click();
    await expect(visit.locator('iframe.map__frame')).toHaveAttribute('src', /Via%20Giovanni%20XXIII%2C%2019%2C%2073025%20Martano%20LE/);
    await nav.getByRole('link', { name: 'Menu', exact: true }).click();
    await nav.getByRole('link', { name: 'Home', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText("Dal primo caffè all'ultimo brindisi.");
  });

  test('hero del menu: foto a tutta area che ruotano ogni 60 s, con pausa', async ({ page }) => {
    await page.clock.install();
    await go(page, '/menu');
    const hero = page.locator('.menu-hero');
    await expect(hero.getByRole('heading', { level: 1 })).toHaveText('Il menu');
    const activeAlt = () => hero.locator('.menu-hero__img.is-active').getAttribute('src');
    const first = await activeAlt();
    // il tempo simulato scorre anche durante il caricamento dei contenuti dal backend: margine di qualche secondo
    await page.clock.runFor(55_000);
    expect(await activeAlt()).toBe(first);
    await page.clock.runFor(10_000);
    expect(await activeAlt()).not.toBe(first);
    const second = await activeAlt();
    await hero.getByRole('button', { name: /Metti in pausa/ }).click();
    await page.clock.runFor(120_000);
    expect(await activeAlt()).toBe(second);
    await hero.getByRole('button', { name: /Mostra foto 1 di/ }).click();
    expect(await activeAlt()).toBe(first);
  });

  test('titolare: sceglie e ordina le foto della testata e l’intervallo', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/sito');
    const box = page.locator('.customize__menuhero');
    await box.getByRole('button', { name: 'Sposta dopo la foto 1' }).click();
    await box.getByRole('button', { name: /Togli la foto 5/ }).click();
    await box.getByLabel('Cambia foto ogni').selectOption('10');
    await page.getByRole('button', { name: 'Salva e pubblica' }).click();
    await page.clock.install();
    await go(page, '/menu');
    await expect(page.locator('.menu-hero__dot')).toHaveCount(4);
    await expect(page.locator('.menu-hero__img.is-active')).toHaveAttribute('src', await photoSrc(/^Tagliere da aperitivo/));
    await page.clock.runFor(11_000);
    await expect(page.locator('.menu-hero__img.is-active')).toHaveAttribute('src', await photoSrc(/^Calice decorato/));
  });

  test('titolare: cambia la foto di un titolare (fronte) con anteprima', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/sito');
    const first = page.locator('.owner-edit').first();
    await first.getByText(/Cambia le foto di/).click();
    await first.locator('.picker').first().locator('.picker__opt').filter({ hasText: 'Bartender versa un cocktail' }).click();
    const bartender = await photoSrc(/^Bartender versa un cocktail/);
    await expect(first.locator('.owner-edit__previews img').first()).toHaveAttribute('src', bartender);
    await page.getByRole('button', { name: 'Salva e pubblica' }).click();
    await expect(page.getByRole('status')).toContainText('Sito aggiornato');
    await go(page, '/locale');
    await expect(page.locator('.owner').first().locator('.owner__img--front')).toHaveAttribute('src', bartender);
  });

  test('prenotazione: nel passo dati i campi sono allineati anche con errori', async ({ page }) => {
    await go(page, '/');
    const d = await openTableBooking(page, () => page.getByRole('banner').getByRole('button', { name: 'Richiedi un tavolo' }).click());
    await d.locator('#bk-date').fill(bookableDay());
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('radio', { name: '19:30' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    for (const withErrors of [false, true]) {
      if (withErrors) await d.getByRole('button', { name: 'Avanti' }).click();
      const a = (await d.getByLabel('Nome').boundingBox())!;
      const b = (await d.getByLabel('Telefono', { exact: true }).boundingBox())!;
      expect(Math.abs(a.y - b.y)).toBeLessThan(1);
      expect(Math.abs(a.height - b.height)).toBeLessThan(1);
    }
  });

  test('login gestori: desktop senza scroll, smartphone senza immagine', async ({ page }) => {
    for (const [w, h] of [[1920, 1080], [1440, 900], [1366, 768]]) {
      await page.setViewportSize({ width: w, height: h });
      await go(page, '/gestione/login');
      expect(await page.evaluate(() => document.documentElement.scrollHeight - innerHeight), `${w}×${h}`).toBe(0);
      expect(await page.evaluate(() => { const p = document.querySelector('.login__panel')!; return p.scrollHeight - p.clientHeight; }), `pannello ${w}×${h}`).toBe(0);
      await expect(page.locator('.login__media')).toBeVisible();
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.locator('.login__media')).toBeHidden();
    await expect(page.getByRole('button', { name: 'Accedi' })).toBeVisible();
  });
});
