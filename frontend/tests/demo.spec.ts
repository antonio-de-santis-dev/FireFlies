// Demo registrate: video reali (WebM) della navigazione nel prototipo + sequenza di screenshot di ogni passaggio.
// Output: ../demo/<demo>.webm e ../demo/<demo>/NN-passo.png
import { test, type Browser, type Page } from '@playwright/test';
import { mkdirSync, renameSync, rmSync } from 'node:fs';
import { stubGoogleMaps } from './helpers';

const OUT = '../demo';

async function record(browser: Browser, name: string, viewport: { width: number; height: number }, mobile: boolean, run: (page: Page, step: (label: string) => Promise<void>) => Promise<void>) {
  const tmp = `${OUT}/.tmp-${name}`;
  mkdirSync(`${OUT}/${name}`, { recursive: true });
  const context = await browser.newContext({
    viewport,
    recordVideo: { dir: tmp, size: viewport },
    hasTouch: mobile,
    isMobile: mobile,
    reducedMotion: 'no-preference', // nei video le animazioni sono attive
    locale: 'it-IT',
  });
  const page = await context.newPage();
  await stubGoogleMaps(page);
  let n = 0;
  const step = async (label: string) => {
    await page.waitForTimeout(500);
    n += 1;
    await page.screenshot({ path: `${OUT}/${name}/${String(n).padStart(2, '0')}-${label}.png` });
    await page.waitForTimeout(700);
  };
  await run(page, step);
  const video = page.video()!;
  await context.close();
  renameSync(await video.path(), `${OUT}/${name}.webm`);
  rmSync(tmp, { recursive: true, force: true });
}

const smoothScroll = (page: Page, to: number) =>
  page.evaluate(async (y) => {
    const start = window.scrollY;
    for (let i = 1; i <= 30; i++) {
      window.scrollTo(0, start + ((y - start) * i) / 30);
      await new Promise((r) => setTimeout(r, 30));
    }
  }, to);

test.describe.configure({ timeout: 180_000 });

test('Demo 1 — smartphone', async ({ browser }) => {
  await record(browser, '01-demo-smartphone', { width: 390, height: 844 }, true, async (page, step) => {
    await page.goto('/#/');
    await page.waitForLoadState('networkidle');
    await step('apertura-home');
    await smoothScroll(page, 900);
    await step('scorrimento-momento');
    await smoothScroll(page, 2600);
    await step('scorrimento-sezioni');
    await smoothScroll(page, 0);
    await page.getByRole('button', { name: 'Apri il menu di navigazione' }).click();
    await step('menu-aperto');
    await page.getByRole('navigation', { name: 'Menu mobile' }).getByRole('link', { name: 'Menu' }).click();
    await step('menu-digitale-caricamento');
    await page.getByRole('button', { name: /^Cocktail/ }).click();
    await step('sezione-cocktail');
    await page.locator('.menu-item').first().click();
    await step('prodotto-aperto');
    await page.getByRole('dialog').getByRole('button', { name: 'Chiudi' }).click();
    await page.getByRole('button', { name: 'Apri il menu di navigazione' }).click();
    await page.getByRole('navigation', { name: 'Menu mobile' }).getByRole('link', { name: 'Home' }).click();
    await step('ritorno-home');
  });
});

test('Demo 2 — desktop', async ({ browser }) => {
  await record(browser, '02-demo-desktop', { width: 1440, height: 900 }, false, async (page, step) => {
    await page.goto('/#/');
    await page.waitForLoadState('networkidle');
    await step('apertura-home');
    await smoothScroll(page, 1000);
    await page.locator('section.moment').getByRole('button', { name: /07/ }).click();
    await step('momento-colazione');
    await smoothScroll(page, 2200);
    await step('titolari');
    const nav = page.getByRole('navigation', { name: 'Principale' });
    await nav.getByRole('link', { name: 'Il locale' }).click();
    await step('il-locale');
    await nav.getByRole('link', { name: 'Galleria' }).click();
    await step('galleria');
    await page.locator('.gallery__item button').nth(2).click();
    await step('lightbox');
    await page.getByRole('button', { name: 'Foto successiva' }).click();
    await step('lightbox-successiva');
    await page.keyboard.press('Escape');
    await nav.getByRole('link', { name: 'Eventi' }).click();
    await step('eventi');
    await smoothScroll(page, 700);
    await step('calendario');
    await page.getByRole('link', { name: /DJ set al Fire.*esempio demo/ }).click();
    await step('dettaglio-evento');
    await page.getByRole('button', { name: 'Richiedi un tavolo per questa serata' }).click();
    await step('prenotazione-orario');
    const d = page.getByRole('dialog');
    await d.getByRole('radio', { name: '19:30' }).click();
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByRole('button', { name: '4 persone' }).click();
    await step('prenotazione-persone');
    await d.getByRole('button', { name: 'Avanti' }).click();
    await d.getByLabel('Nome').pressSequentially('Mario', { delay: 60 });
    await d.getByLabel('Telefono o email').pressSequentially('333 000 0000', { delay: 40 });
    await d.getByLabel(/Ho letto l'informativa/).check();
    await step('prenotazione-dati');
    await d.getByRole('button', { name: 'Avanti' }).click();
    await step('prenotazione-riepilogo');
    await d.getByRole('button', { name: 'Invia la richiesta (simulata)' }).click();
    await step('conferma-simulata');
  });
});

test('Demo 3 — area gestori', async ({ browser }) => {
  await record(browser, '03-demo-area-gestori', { width: 1440, height: 900 }, false, async (page, step) => {
    await page.goto('/#/gestione/login');
    await page.waitForLoadState('networkidle');
    await page.getByRole('button', { name: 'Compila con dati demo' }).click();
    await step('login');
    await page.getByRole('button', { name: 'Entra nella demo' }).click();
    await step('dashboard');
    const nav = page.getByRole('navigation', { name: 'Area gestori' });
    await nav.getByRole('link', { name: 'Menu e prezzi' }).click();
    await step('gestione-menu');
    await page.getByRole('link', { name: 'Modifica Tagliere FireFlies' }).click();
    await step('form-modifica');
    await page.getByLabel('Prezzo (€)').pressSequentially('14,00', { delay: 80 });
    await page.getByLabel('Ho verificato gli allergeni di questa voce').check();
    await page.getByLabel('Glutine').check();
    await page.getByLabel('Latte').check();
    await step('form-compilato');
    await page.getByRole('button', { name: 'Salva' }).click();
    await step('salvataggio-simulato');
    await nav.getByRole('link', { name: 'Personalizza il sito' }).click();
    await page.getByLabel('Frase principale').fill('Accendi la tua serata.');
    await step('personalizza-sito');
    await page.getByRole('button', { name: 'Salva e pubblica' }).click();
    await page.getByRole('link', { name: 'Vedi il sito' }).first().click();
    await step('sito-aggiornato');
  });
});
