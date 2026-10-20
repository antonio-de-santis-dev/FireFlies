// FASE 7, requisito 4: «Visibile sul sito» con un solo «Salva», persistenza dopo il ricaricamento e lettura pubblica,
// nessuna bozza orfana se la pubblicazione è rifiutata, nascondere ed eliminare con conferma. Stack reale.
import { expect, test, type Page } from '@playwright/test';
import { bookableDay, go, signIn } from './helpers';

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

test.describe('Eventi: visibile sul sito', () => {
  test.use({ viewport: { width: 1366, height: 900 } });

  test('switch acceso + Salva = pubblicato, anche dopo il ricaricamento e sul sito; spento = bozza', async ({ page }) => {
    const stamp = Date.now();
    const pub = `Visibile E2E ${stamp}`;
    const draft = `Bozza E2E ${stamp}`;
    await signIn(page, 'Staff');
    await go(page, '/gestione/eventi/nuovo');
    await expect(page.getByRole('button', { name: 'Pubblica', exact: true })).toHaveCount(0);
    await fill(page, pub, '23:00', '23:45');
    await page.getByRole('switch', { name: 'Visibile sul sito' }).click();
    await save(page);
    await expect(page.locator('.toast')).toContainText(`«${pub}» pubblicato sul sito`);
    await expect(row(page, pub)).toContainText('Pubblicato');

    await go(page, '/gestione/eventi/nuovo');
    await fill(page, draft, '17:00', '17:45');
    await save(page);
    await expect(page.locator('.toast')).toContainText(`Bozza «${draft}» salvata`);

    await page.reload();
    await expect(row(page, pub)).toContainText('Pubblicato');
    await expect(row(page, draft)).toContainText('Bozza');
    const titles = await publicTitles(page);
    expect(titles).toContain(pub);
    expect(titles).not.toContain(draft);

    // nuova sessione (nuovo contesto senza cookie): il sito la mostra in elenco e nel calendario
    const visitor = await page.context().browser()!.newPage();
    await go(visitor, '/eventi');
    await expect(visitor.locator('.event-row', { hasText: pub })).toBeVisible();
    await expect(visitor.locator('.event-row', { hasText: draft })).toHaveCount(0);
    await visitor.close();

    // modifica del pubblicato senza toccare lo switch: resta pubblicato
    await go(page, '/gestione/eventi');
    await page.getByRole('link', { name: `Modifica ${pub}` }).click();
    await expect(page.getByRole('switch', { name: 'Visibile sul sito' })).toHaveAttribute('aria-checked', 'true');
    await page.getByLabel('Artista / DJ').fill('DJ di prova');
    await save(page);
    await expect(page.locator('.toast')).toContainText(`«${pub}» salvato`);
    await expect(row(page, pub)).toContainText('Pubblicato');

    // spento + Salva: torna bozza e sparisce dal sito
    await page.getByRole('link', { name: `Modifica ${pub}` }).click();
    await page.getByRole('switch', { name: 'Visibile sul sito' }).click();
    await save(page);
    await expect(page.locator('.toast')).toContainText('tolto dal sito');
    await page.reload();
    await expect(row(page, pub)).toContainText('Bozza');
    expect(await publicTitles(page)).not.toContain(pub);
  });

  test('pubblicazione rifiutata: nessuna bozza orfana, il modulo resta compilato', async ({ page }) => {
    const stamp = Date.now();
    const first = `Prima esclusiva ${stamp}`;
    const clash = `Sovrapposta ${stamp}`;
    await signIn(page);
    await go(page, '/gestione/eventi/nuovo');
    await fill(page, first, '16:00', '16:45');
    await page.getByRole('switch', { name: 'Visibile sul sito' }).click();
    await save(page);
    await expect(row(page, first)).toContainText('Pubblicato');

    await go(page, '/gestione/eventi/nuovo');
    await fill(page, clash, '16:15', '17:00');
    await page.getByRole('switch', { name: 'Visibile sul sito' }).click();
    await save(page);
    await expect(page.getByRole('alert').filter({ hasText: 'altro evento esclusivo' })).toBeVisible();
    await expect(page.getByLabel('Titolo')).toHaveValue(clash);
    await go(page, '/gestione/eventi');
    await expect(row(page, first)).toBeVisible();
    await expect(row(page, clash)).toHaveCount(0);

    // eliminazione con conferma esplicita; dopo il ricaricamento non c'è più, né sul sito
    await page.getByRole('link', { name: `Modifica ${first}` }).click();
    await page.getByRole('button', { name: 'Elimina evento' }).click();
    const confirm = page.getByRole('group', { name: "Conferma l'eliminazione" });
    await expect(confirm).toContainText('non si può annullare');
    await confirm.getByRole('button', { name: 'No, tienilo' }).click();
    await expect(confirm).toHaveCount(0);
    await page.getByRole('button', { name: 'Elimina evento' }).click();
    await page.getByRole('button', { name: 'Elimina definitivamente' }).click();
    await expect(page.locator('.toast')).toContainText(`«${first}» eliminato definitivamente`);
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
  await page.getByRole('switch', { name: 'Visibile sul sito' }).click();
  await save(page);
  await expect(page.locator('.toast')).toContainText(`«${title}» pubblicato sul sito`);
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
