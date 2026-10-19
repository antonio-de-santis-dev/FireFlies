// Screenshot REALI del prototipo (build di produzione servita da `vite preview`), nella lista richiesta dal brief.
// Output: ../mockup-grafici/{desktop,mobile}/ + INDEX.md con la corrispondenza screenshot ↔ vista/stato.
import { expect, test, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { brokenImages, go, loadAllImages, signIn, stubGoogleMaps, watchProblems } from './helpers';

const OUT = '../mockup-grafici';
const index: { file: string; route: string; viewport: string; state: string }[] = [];

test.afterAll(() => {
  const rows = index.map((i) => `| \`${i.file}\` | \`#${i.route}\` | ${i.viewport} | ${i.state} |`).join('\n');
  writeFileSync(
    `${OUT}/INDEX.md`,
    `# Indice degli screenshot\n\nCatturati con Playwright (Chromium) dalla build del prototipo (\`vite preview\`), \`prefers-reduced-motion: reduce\` per mostrare lo stato finale delle animazioni. Nessuna immagine è generata o ritoccata: le cornici in \`presentazione/\` contengono questi stessi file, non modificati.\n\n| File | Vista (hash) | Viewport | Stato mostrato |\n|---|---|---|---|\n${rows}\n`,
  );
});

async function shot(page: Page, dir: string, file: string, route: string, state: string, full = false) {
  mkdirSync(`${OUT}/${dir}`, { recursive: true });
  await page.evaluate(() => document.fonts.ready);
  await page.locator('.skeleton').first().waitFor({ state: 'detached' }).catch(() => {});
  if (full) await page.evaluate(() => { window.scrollTo(0, 0); (document.activeElement as HTMLElement | null)?.blur(); });
  expect(await brokenImages(page), 'immagini rotte').toEqual([]);
  // nelle catture a pagina intera la barra CTA fissa (mobile) comparirebbe a metà: nascosta solo lì
  const hide = full ? await page.addStyleTag({ content: '.mobile-cta{display:none!important}' }) : null;
  await page.screenshot({ path: `${OUT}/${dir}/${file}`, fullPage: full, animations: 'disabled', caret: 'hide' });
  if (hide) await hide.evaluate((el) => el.remove());
  const vp = page.viewportSize()!;
  index.push({ file: `${dir}/${file}`, route, viewport: `${vp.width}×${vp.height}${full ? ' (pagina intera)' : ''}`, state });
}

const futureIso = (days: number) => {
  const d = new Date(Date.now() + days * 86400000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

test.describe('desktop', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('area pubblica 1440', async ({ page }) => {
    const problems = watchProblems(page);
    await stubGoogleMaps(page);
    await go(page, '/');
    await loadAllImages(page);
    await shot(page, 'desktop', '01-home-iniziale.png', '/', 'Homepage: hero con logo, claim, CTA «Scopri il menu» / «Scopri gli eventi»');
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.moment') as HTMLElement).offsetTop - 40));
    await page.waitForTimeout(200);
    await shot(page, 'desktop', '02-home-scroll.png', '/', 'Homepage durante lo scroll: «Che momento è al Fire?»');
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.people') as HTMLElement).offsetTop - 80));
    await shot(page, 'desktop', '03-home-scroll-titolari.png', '/', 'Homepage: presentazione con i titolari');
    await shot(page, 'desktop', '04-home-pagina-intera.png', '/', 'Homepage completa', true);

    await page.evaluate(() => window.scrollTo(0, 0));
    await go(page, '/menu');
    await loadAllImages(page);
    await shot(page, 'desktop', '05-menu-digitale.png', '/menu', 'Menu digitale: categorie, ricerca, prezzi demo');
    await page.getByRole('button', { name: /^Cocktail/ }).click();
    await page.locator('.menu-item', { hasText: 'Calice lamponi e menta' }).click();
    await page.waitForTimeout(300);
    await shot(page, 'desktop', '06-dettaglio-prodotto.png', '/menu', 'Scheda prodotto aperta');
    await page.keyboard.press('Escape');

    await go(page, '/eventi');
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.events') as HTMLElement).offsetTop - 100));
    await shot(page, 'desktop', '07-calendario-eventi.png', '/eventi', 'Elenco eventi + calendario');
    await shot(page, 'desktop', '08-eventi-pagina-intera.png', '/eventi', 'Eventi: locandina «Domeniche del Fire», elenco, calendario, serate passate', true);
    await go(page, '/eventi/dj-set-esempio');
    await loadAllImages(page);
    await shot(page, 'desktop', '09-dettaglio-evento.png', '/eventi/dj-set-esempio', 'Dettaglio serata (esempio DEMO)');

    await go(page, '/galleria');
    await loadAllImages(page);
    await shot(page, 'desktop', '10-galleria.png', '/galleria', 'Galleria');
    await page.locator('.gallery__item button').nth(2).click();
    await page.waitForTimeout(300);
    await shot(page, 'desktop', '11-galleria-lightbox.png', '/galleria', 'Lightbox con navigazione');
    await page.keyboard.press('Escape');

    await go(page, '/eventi/dj-set-esempio');
    await page.getByRole('button', { name: 'Richiedi un tavolo per questa serata' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('radio', { name: '19:30' }).click();
    await page.waitForTimeout(200);
    await shot(page, 'desktop', '12-prenotazione-orario.png', '/eventi/dj-set-esempio (dialog)', 'Prenotazione in corso: passo 2, orario');
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await dialog.getByRole('button', { name: '4 persone' }).click();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await page.waitForTimeout(200);
    await shot(page, 'desktop', '13-prenotazione-errori.png', '/eventi/dj-set-esempio (dialog)', 'Passo 4: errori di compilazione');
    await dialog.getByLabel('Nome').fill('Mario');
    await dialog.getByLabel('Telefono o email').fill('mario@example.invalid');
    await dialog.getByLabel(/Ho letto l'informativa/).check();
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await page.waitForTimeout(200);
    await shot(page, 'desktop', '14-prenotazione-riepilogo.png', '/eventi/dj-set-esempio (dialog)', 'Passo 5: riepilogo modificabile');
    await dialog.getByRole('button', { name: 'Invia la richiesta (simulata)' }).click();
    await page.waitForTimeout(300);
    await shot(page, 'desktop', '15-prenotazione-conferma-simulata.png', '/eventi/dj-set-esempio (dialog)', 'Passo 6: conferma simulata, nessuna prenotazione effettuata');
    await page.getByRole('dialog').getByRole('button', { name: 'Chiudi' }).first().click();

    await go(page, '/locale');
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.owners') as HTMLElement).offsetTop));
    await shot(page, 'desktop', '16-locale-titolari.png', '/locale', 'Il locale: titolari con foto accanto alle descrizioni');
    await page.locator('.owner').first().getByRole('button', { name: /Gira la maglia/ }).click();
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.owner') as HTMLElement).getBoundingClientRect().top + window.scrollY - 120));
    await page.waitForTimeout(300);
    await shot(page, 'desktop', '17-locale-titolare-retro.png', '/locale', '«Gira la maglia»: retro con il motto');
    await shot(page, 'desktop', '18-locale-pagina-intera.png', '/locale', 'Il locale completo', true);

    await go(page, '/contatti');
    await loadAllImages(page);
    await shot(page, 'desktop', '19-contatti-mappa-consenso.png', '/contatti', 'Contatti: mappa Google in attesa del consenso');
    await page.getByRole('button', { name: 'Mostra la mappa' }).click();
    await page.waitForTimeout(300);
    await shot(page, 'desktop', '20-contatti-mappa-caricata.png', '/contatti', 'Dopo il clic: iframe Google Maps (nell’ambiente di test Google non è raggiungibile, l’iframe mostra un avviso)');
    expect(problems).toEqual([]);
  });

  test('area gestori 1440', async ({ page }) => {
    const problems = watchProblems(page);
    await go(page, '/gestione/login');
    await loadAllImages(page);
    await page.getByRole('button', { name: 'Compila con dati demo' }).click();
    await shot(page, 'desktop', '21-login-gestori.png', '/gestione/login', 'Login dimostrativo con scelta del ruolo');
    await signIn(page);
    await shot(page, 'desktop', '22-dashboard.png', '/gestione', 'Dashboard del titolare (dati fittizi)', true);
    await go(page, '/gestione/menu');
    await loadAllImages(page);
    await shot(page, 'desktop', '23-gestione-menu.png', '/gestione/menu', 'Gestione menu e prodotti');
    await go(page, '/gestione/menu/tagliere');
    await loadAllImages(page);
    await page.getByLabel('Prezzo (€)').fill('14,00');
    await page.getByLabel('Ho verificato gli allergeni di questa voce').check();
    await page.getByLabel('Glutine').check();
    await shot(page, 'desktop', '24-modifica-prodotto.png', '/gestione/menu/tagliere', 'Modifica prodotto: prezzo, allergeni, foto', true);
    await page.getByRole('button', { name: 'Salva' }).click();
    await shot(page, 'desktop', '25-prodotto-salvato.png', '/gestione/menu', 'Salvataggio simulato (avviso in basso)');
    await page.getByRole('status').filter({ hasText: 'salvata' }).waitFor({ state: 'detached' }).catch(() => {});
    await page.waitForTimeout(4400); // l'avviso «salvato» sparisce da solo dopo 4,2 s
    await go(page, '/gestione/eventi');
    await loadAllImages(page);
    await shot(page, 'desktop', '26-gestione-eventi.png', '/gestione/eventi', 'Gestione eventi');
    await go(page, '/gestione/richieste');
    await shot(page, 'desktop', '27-prenotazioni.png', '/gestione/richieste', 'Richieste di prenotazione e stati');
    await go(page, '/gestione/foto');
    await loadAllImages(page);
    await shot(page, 'desktop', '28-galleria-fotografica.png', '/gestione/foto', 'Libreria foto e galleria');
    await go(page, '/gestione/sito');
    await loadAllImages(page);
    await shot(page, 'desktop', '29-personalizza-sito.png', '/gestione/sito', 'Personalizza il sito (solo titolare)', true);
    await go(page, '/gestione/comunicazioni');
    await shot(page, 'desktop', '30-comunicazioni.png', '/gestione/comunicazioni', 'Avvisi pubblicati sul sito');
    await go(page, '/gestione/impostazioni');
    await shot(page, 'desktop', '31-impostazioni-locale.png', '/gestione/impostazioni', 'Impostazioni del locale: orari, contatti, mappa', true);
    expect(problems).toEqual([]);
  });
});

test.describe('desktop altri formati', () => {
  test('1920 e 1366', async ({ page }) => {
    await page.setViewportSize({ width: 1920, height: 1080 });
    await go(page, '/');
    await loadAllImages(page);
    await shot(page, 'desktop', '32-home-1920.png', '/', 'Homepage a 1920×1080');
    await page.setViewportSize({ width: 1366, height: 768 });
    await go(page, '/menu');
    await loadAllImages(page);
    await shot(page, 'desktop', '33-menu-1366.png', '/menu', 'Menu a 1366×768');
    await go(page, '/');
    await loadAllImages(page);
    await shot(page, 'desktop', '34-home-1366.png', '/', 'Homepage a 1366×768');
  });
});

test.describe('smartphone', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });

  test('390×844', async ({ page }) => {
    const problems = watchProblems(page);
    await go(page, '/');
    await loadAllImages(page);
    await shot(page, 'mobile', '01-home.png', '/', 'Home mobile con CTA sticky');
    await page.getByRole('button', { name: 'Apri il menu di navigazione' }).click();
    await page.waitForTimeout(300);
    await shot(page, 'mobile', '02-menu-aperto.png', '/', 'Menu di navigazione aperto');
    await page.keyboard.press('Escape');
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.moment') as HTMLElement).offsetTop));
    await page.waitForTimeout(200);
    await shot(page, 'mobile', '03-home-scroll.png', '/', 'Scorrimento della homepage');
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.people') as HTMLElement).offsetTop));
    await shot(page, 'mobile', '04-home-scroll-titolari.png', '/', 'Scorrimento: i titolari');
    await shot(page, 'mobile', '05-home-pagina-intera.png', '/', 'Home completa', true);

    await go(page, '/menu');
    await loadAllImages(page);
    await page.getByRole('button', { name: /^Cocktail/ }).click();
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.menu__tools') as HTMLElement).offsetTop - 70));
    await page.waitForTimeout(200);
    await shot(page, 'mobile', '06-menu-filtri.png', '/menu', 'Menu digitale con filtro Cocktail');
    await page.locator('.menu-item').first().click();
    await page.waitForTimeout(300);
    await shot(page, 'mobile', '07-scheda-prodotto.png', '/menu', 'Scheda prodotto aperta');
    await page.keyboard.press('Escape');

    await go(page, '/eventi');
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.featured') as HTMLElement).offsetTop - 70));
    await shot(page, 'mobile', '08-eventi.png', '/eventi', 'Eventi: locandina Domeniche del Fire');
    await shot(page, 'mobile', '09-eventi-pagina-intera.png', '/eventi', 'Eventi completo', true);

    await page.locator('.mobile-cta').getByRole('button', { name: 'Richiedi un tavolo' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('radiogroup', { name: 'Prossimi 14 giorni' }).getByRole('radio').nth(4).click();
    await page.waitForTimeout(200);
    await shot(page, 'mobile', '10-prenotazione-giorno.png', '/eventi (dialog)', 'Prenotazione passo 1: giorno');
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await dialog.getByRole('radio', { name: '20:00' }).click();
    await shot(page, 'mobile', '11-prenotazione-orario.png', '/eventi (dialog)', 'Passo 2: orario');
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await dialog.getByRole('button', { name: '6 persone' }).click();
    await shot(page, 'mobile', '12-prenotazione-persone.png', '/eventi (dialog)', 'Passo 3: persone');
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await dialog.getByLabel('Nome').fill('Giulia');
    await dialog.getByLabel('Telefono o email').fill('333 000 0000');
    await dialog.getByLabel(/Ho letto l'informativa/).check();
    await shot(page, 'mobile', '13-prenotazione-dati.png', '/eventi (dialog)', 'Passo 4: dati');
    await dialog.getByRole('button', { name: 'Avanti' }).click();
    await page.waitForTimeout(200);
    await shot(page, 'mobile', '14-prenotazione-riepilogo.png', '/eventi (dialog)', 'Passo 5: riepilogo');
    await dialog.getByRole('button', { name: 'Invia la richiesta (simulata)' }).click();
    await page.waitForTimeout(300);
    await shot(page, 'mobile', '15-prenotazione-conferma.png', '/eventi (dialog)', 'Passo 6: conferma simulata');
    await page.getByRole('dialog').getByRole('button', { name: 'Chiudi' }).first().click();

    await go(page, '/galleria');
    await loadAllImages(page);
    await shot(page, 'mobile', '16-galleria.png', '/galleria', 'Galleria fotografica');
    await page.locator('.gallery__item button').nth(4).click();
    await page.waitForTimeout(300);
    await shot(page, 'mobile', '17-galleria-lightbox.png', '/galleria', 'Lightbox');
    await page.keyboard.press('Escape');

    await go(page, '/locale');
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.owner') as HTMLElement).offsetTop - 70));
    await shot(page, 'mobile', '18-locale-titolari.png', '/locale', 'Il locale: titolare con descrizione');

    await signIn(page);
    await shot(page, 'mobile', '19-area-gestori.png', '/gestione', 'Area gestori su smartphone');
    expect(problems).toEqual([]);
  });

  test('375×812 e 360×800', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await go(page, '/');
    await loadAllImages(page);
    await shot(page, 'mobile', '20-home-375.png', '/', 'Home a 375×812');
    await page.setViewportSize({ width: 360, height: 800 });
    await go(page, '/menu');
    await loadAllImages(page);
    await shot(page, 'mobile', '21-menu-360.png', '/menu', 'Menu a 360×800');
    await go(page, '/');
    await loadAllImages(page);
    await shot(page, 'mobile', '22-home-360.png', '/', 'Home a 360×800');
  });
});

test.describe('revisione 19/10 — schermate modificate', () => {
  test('desktop', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await stubGoogleMaps(page);
    await go(page, '/');
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.visit') as HTMLElement).offsetTop - 72));
    await shot(page, 'desktop', '35-home-passa-a-trovarci-mappa.png', '/', 'Home: mappa tra indirizzo confermato e pulsanti; voce Home nella navbar');
    await go(page, '/menu');
    await loadAllImages(page);
    await page.locator('.menu-hero__dot').nth(1).click();
    await page.waitForTimeout(300);
    await shot(page, 'desktop', '36-menu-hero-rotazione.png', '/menu', 'Hero del menu a tutta area, seconda foto della rotazione');
    await go(page, '/');
    await page.getByRole('banner').getByRole('button', { name: 'Richiedi un tavolo' }).click();
    const d = page.getByRole('dialog');
    await d.getByRole('radiogroup', { name: 'Prossimi 14 giorni' }).getByRole('radio').nth(3).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('radio', { name: '19:30' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await page.waitForTimeout(200);
    await shot(page, 'desktop', '37-prenotazione-dati-allineati.png', '/ (dialog)', 'Passo «I tuoi dati»: campi allineati anche con errori');
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 1366, height: 768 });
    await go(page, '/gestione/login');
    await loadAllImages(page);
    await shot(page, 'desktop', '38-login-1366-senza-scroll.png', '/gestione/login', 'Login gestori a 1366×768: fermo a schermo');
    await page.setViewportSize({ width: 1440, height: 900 });
    await signIn(page);
    await go(page, '/gestione/richieste');
    await page.getByLabel('Cambia stato della richiesta di Cliente demo A').selectOption('confermata');
    await page.waitForTimeout(200);
    await shot(page, 'desktop', '39-richieste-sms-simulato.png', '/gestione/richieste', 'Stato «Confermata»: SMS simulato (avviso e nota nella riga)');
    await page.getByRole('status').filter({ hasText: 'SIMULATO' }).waitFor({ state: 'detached' }).catch(() => {});
    await page.waitForTimeout(4400);
    await go(page, '/gestione/sito');
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.customize__menuhero') as HTMLElement).getBoundingClientRect().top + window.scrollY - 20));
    await shot(page, 'desktop', '40-personalizza-testata-menu.png', '/gestione/sito', 'Titolare: foto, ordine e intervallo della testata del menu');
    const first = page.locator('.owner-edit').first();
    await first.getByText(/Cambia le foto di/).click();
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.owner-edit') as HTMLElement).getBoundingClientRect().top + window.scrollY - 20));
    await shot(page, 'desktop', '41-personalizza-foto-titolari.png', '/gestione/sito', 'Titolare: cambio foto fronte/retro dei titolari con anteprima');
  });

  test('smartphone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await go(page, '/');
    await loadAllImages(page);
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.visit') as HTMLElement).offsetTop - 64));
    await shot(page, 'mobile', '23-home-mappa.png', '/', 'Home mobile: «Passa a trovarci» con mappa');
    await go(page, '/menu');
    await loadAllImages(page);
    await shot(page, 'mobile', '24-menu-hero.png', '/menu', 'Menu mobile: hero a tutta area');
    await page.evaluate(() => window.scrollTo(0, (document.querySelector('.menu__group') as HTMLElement).getBoundingClientRect().top + window.scrollY - 200));
    await shot(page, 'mobile', '25-menu-schede-leggibili.png', '/menu', 'Menu mobile: schede prodotto più leggibili');
    await go(page, '/gestione/login');
    await shot(page, 'mobile', '26-login-mobile.png', '/gestione/login', 'Login mobile: solo pannello, niente foto');
  });
});
