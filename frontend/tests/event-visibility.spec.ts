// FIX 2 (prima FASE 7, req. 4): un nuovo evento nasce bozza; si pubblica e si nasconde dall'elenco con l'interruttore
// «Pubblica sul sito», persistenza dopo il ricaricamento e lettura pubblica, errore che riporta l'interruttore com'era,
// eliminazione con conferma. Stack reale.
import { expect, test, type Page } from '@playwright/test';
import { bookableDay, go, publishFromList, publishSwitch, signIn } from './helpers';

const day = bookableDay(11, 0); // prima della serata demo (sabato ≥ 14 giorni)

async function publicTitles(page: Page): Promise<string[]> {
  return page.evaluate(async () => {
    const r = await fetch('/api/v1/public/events');
    return (await r.json()).map((e: { title: string }) => e.title);
  });
}

async function fill(page: Page, title: string, start: string, end: string) {
  await page.getByLabel('Titolo').fill(title);
  await page.getByLabel('Data').fill(day);
  await page.getByLabel('Orario').fill(start);
  void end; // FASE 8: nessun campo «Fine»
}

const row = (page: Page, title: string) => page.locator('.ev-admin__item', { hasText: title });
const save = (page: Page) => page.getByRole('button', { name: 'Salva', exact: true }).click();

test.describe('Eventi: pubblicazione dall\'elenco (FIX 2)', () => {
  test.use({ viewport: { width: 1366, height: 900 } });

  test('nuovo evento = bozza; switch in elenco pubblica, resta dopo il ricaricamento e sul sito; modifica non cambia la visibilità', async ({ page }) => {
    const stamp = Date.now();
    const pub = `Visibile E2E ${stamp}`;
    const draft = `Bozza E2E ${stamp}`;
    await signIn(page, 'Staff');
    await go(page, '/gestione/eventi/nuovo');
    await expect(page.getByRole('switch', { name: 'Visibile sul sito' })).toHaveCount(0); // niente switch nell'editor
    await expect(page.getByRole('button', { name: 'Pubblica', exact: true })).toHaveCount(0);
    await fill(page, pub, '23:07', '');
    await save(page);
    await expect(page.locator('.toast')).toContainText(`Bozza «${pub}» creata`);
    await expect(row(page, pub)).toContainText('Bozza');
    await expect(row(page, pub)).toContainText('23:07 – 03:07'); // qualunque minuto, 4 ore, fine dopo mezzanotte
    await publishFromList(page, pub);
    await expect(row(page, pub)).toContainText('Pubblicato');

    await go(page, '/gestione/eventi/nuovo');
    await fill(page, draft, '17:00', '');
    await save(page);
    await expect(row(page, draft)).toContainText('Bozza');

    await page.reload();
    await expect(publishSwitch(page, pub)).toHaveAttribute('aria-checked', 'true');
    await expect(publishSwitch(page, draft)).toHaveAttribute('aria-checked', 'false');
    await expect(row(page, pub)).toContainText('Pubblicato');
    const titles = await publicTitles(page);
    expect(titles).toContain(pub);
    expect(titles).not.toContain(draft);

    const visitor = await page.context().browser()!.newPage();
    await go(visitor, '/eventi');
    await expect(visitor.locator('.event-row', { hasText: pub })).toBeVisible();
    await expect(visitor.locator('.event-row', { hasText: draft })).toHaveCount(0);
    await visitor.close();

    // modifica di altri campi: la visibilità resta quella salvata
    await page.getByRole('link', { name: `Modifica ${pub}` }).click();
    await page.getByLabel('Artista / DJ').fill('DJ di prova');
    await save(page);
    await expect(page.locator('.toast')).toContainText(`«${pub}» salvato`);
    await expect(publishSwitch(page, pub)).toHaveAttribute('aria-checked', 'true');

    // spento dall'elenco: torna bozza e sparisce dal sito, anche dopo il ricaricamento
    await publishSwitch(page, pub).click();
    await expect(page.locator('.toast')).toContainText('tolto dal sito');
    await page.reload();
    await expect(publishSwitch(page, pub)).toHaveAttribute('aria-checked', 'false');
    await expect(row(page, pub)).toContainText('Bozza');
    expect(await publicTitles(page)).not.toContain(pub);
  });

  test('pubblicazione rifiutata: lo switch torna spento con l\'errore, nessuno stato a metà', async ({ page }) => {
    const stamp = Date.now();
    const first = `Prima esclusiva ${stamp}`;
    const clash = `Sovrapposta ${stamp}`;
    await signIn(page);
    await go(page, '/gestione/eventi/nuovo');
    await fill(page, first, '16:00', '');
    await save(page);
    await publishFromList(page, first);

    await go(page, '/gestione/eventi/nuovo');
    await fill(page, clash, '16:23', '');
    await save(page);
    const toggle = publishSwitch(page, clash);
    await toggle.click();
    await expect(row(page, clash).getByRole('alert')).toContainText('altro evento esclusivo');
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await expect(toggle).toBeEnabled();
    await page.reload();
    await expect(publishSwitch(page, clash)).toHaveAttribute('aria-checked', 'false');
    await expect(row(page, clash)).toContainText('Bozza');
    expect(await publicTitles(page)).not.toContain(clash);

    // eliminazione con conferma esplicita; dopo il ricaricamento non c'è più, né sul sito
    for (const title of [clash, first]) {
      await page.getByRole('link', { name: `Modifica ${title}` }).click();
      await page.getByRole('button', { name: 'Elimina evento' }).click();
      const confirm = page.getByRole('group', { name: "Conferma l'eliminazione" });
      await expect(confirm).toContainText('non si può annullare');
      await page.getByRole('button', { name: 'Elimina definitivamente' }).click();
      await expect(page.locator('.toast')).toContainText(`«${title}» eliminato definitivamente`);
    }
    await page.reload();
    await expect(page.getByRole('heading', { level: 1, name: 'Eventi' })).toBeVisible();
    await expect(row(page, first)).toHaveCount(0);
    expect(await publicTitles(page)).not.toContain(first);
  });
});

// FASE 8, requisito 1: l'editor non chiede più ora di fine e coperti; dopo il ricaricamento i valori ricavati restano.
test('editor senza «Fine» e senza coperti: durata e posti ricavati, salvati e ripresi dopo il ricaricamento', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 900 });
  const title = `Senza fine E2E ${Date.now()}`;
  await signIn(page);
  await go(page, '/gestione/eventi/nuovo');
  await expect(page.getByLabel('Fine')).toHaveCount(0);
  await expect(page.getByLabel(/Persone al massimo/)).toHaveCount(0);
  await page.getByLabel('Titolo').fill(title);
  await page.getByLabel('Data').fill(day);
  await page.getByLabel('Orario').fill('12:00');
  await page.getByLabel('Pulsante sul sito').selectOption('REQUEST');
  await save(page);
  await publishFromList(page, title);
  await page.reload();
  await expect(row(page, title)).toContainText('12:00 – 16:00');
  await expect(row(page, title)).toContainText(/0\/\d+ posti confermati/);
  await page.getByRole('link', { name: `Modifica ${title}` }).click();
  await expect(page.locator('#ee-time-hint')).toContainText('Fine prevista alle 16:00');
  await page.getByLabel('Orario').fill('11:00');
  await save(page);
  await expect(page.locator('.toast')).toContainText(`«${title}» salvato`);
  await page.reload();
  await expect(row(page, title)).toContainText('11:00 – 15:00');
  await expect(row(page, title)).toContainText('Pubblicato');
  // pulizia: l'evento senza prenotazioni si elimina
  await page.getByRole('link', { name: `Modifica ${title}` }).click();
  await page.getByRole('button', { name: 'Elimina evento' }).click();
  await page.getByRole('button', { name: 'Elimina definitivamente' }).click();
  await expect(page.locator('.toast')).toContainText('eliminato definitivamente');
});
