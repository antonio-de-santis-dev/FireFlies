import { expect, test } from '@playwright/test';
import { keepBaseline, ownerApi } from './baseline';
import { go, signIn, watchProblems } from './helpers';

// FASE 4: menu, foto e contenuti salvati sul backend vero (PostgreSQL + storage locale delle foto).
// Ogni test riporta i dati demo com'erano (keepBaseline): l'ordine dei test non conta.

test.describe('Contenuti dal backend', () => {
  test.use({ viewport: { width: 1440, height: 900 } });
  keepBaseline();

  test('il sito pubblico legge menu, testi e foto dalle API, con varianti ridimensionate', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } }); // cache vuota
    const page = await context.newPage();
    const problems = watchProblems(page);
    const api: string[] = [];
    page.on('request', (r) => r.url().includes('/api/v1/public/') && api.push(new URL(r.url()).pathname));
    await go(page, '/menu');
    expect(api).toEqual(expect.arrayContaining(['/api/v1/public/site', '/api/v1/public/menu', '/api/v1/public/gallery']));
    // foto che non compare nella testata (lì è a tutta larghezza e il browser riuserebbe quella grande dalla cache)
    const thumb = page.locator('.menu-item', { hasText: 'Crostino mortadella e pistacchio' }).locator('img');
    await expect(thumb).toHaveAttribute('srcset', /w480\.jpg 480w/);
    await expect(thumb).toHaveAttribute('sizes', '104px');
    await thumb.scrollIntoViewIfNeeded();
    await expect.poll(() => thumb.evaluate((img: HTMLImageElement) => img.currentSrc), 'variante piccola per 104 px').toMatch(/w480\.jpg$/);
    await expect(page.locator('.menu-hero__img').first()).toHaveAttribute('sizes', '100vw');
    expect(problems).toEqual([]);
    await context.close();
  });

  test('staff: prezzo e allergeni salvati, visibili a un altro visitatore', async ({ page, browser }) => {
    await signIn(page, 'Staff');
    await go(page, '/gestione/menu/{Rustico}');
    await page.getByLabel('Prezzo (€)').fill('2,50');
    await page.getByLabel('Ho verificato gli allergeni di questa voce').check();
    await page.getByLabel('Glutine').check();
    await page.getByLabel('Latte').check();
    await page.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByRole('status')).toContainText('«Rustico» salvata');

    const visitor = await browser.newPage();
    await go(visitor, '/menu');
    const rustico = visitor.locator('.menu-item', { hasText: 'Rustico' });
    await expect(rustico).toContainText('€ 2,50');
    await rustico.click();
    const detail = visitor.getByRole('dialog');
    await expect(detail).toContainText('Glutine, Latte');
    await expect(detail.getByText('Da dichiarare')).toHaveCount(0);
    await visitor.close();
  });

  test('prezzo non valido: messaggio sul campo, nulla salvato', async ({ page }) => {
    await signIn(page, 'Staff');
    await go(page, '/gestione/menu/{Rustico}');
    for (const wrong of ['-3', '2,505', '1500']) {
      await page.getByLabel('Prezzo (€)').fill(wrong);
      await page.getByRole('button', { name: 'Salva' }).click();
      await expect(page.getByText('Scrivi un prezzo in euro')).toBeVisible();
    }
    await go(page, '/menu');
    await expect(page.locator('.menu-item', { hasText: 'Rustico' })).toContainText('demo');
  });

  test('nuova voce: creata, visibile sul sito, poi eliminata', async ({ page }) => {
    await signIn(page, 'Staff');
    await go(page, '/gestione/menu/nuova');
    await page.getByLabel('Nome').fill('Spritz della casa');
    await page.getByLabel('Categoria').selectOption({ label: 'Cocktail' });
    await page.getByLabel('Prezzo (€)').fill('7');
    await page.getByRole('switch', { name: 'Nascosta dal sito' }).click();
    await page.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByRole('status')).toContainText('«Spritz della casa» aggiunta');
    await go(page, '/menu?cat=cocktail');
    await expect(page.locator('.menu-item', { hasText: 'Spritz della casa' })).toContainText('€ 7,00');

    await go(page, '/gestione/menu/{Spritz della casa}');
    await page.getByRole('button', { name: 'Elimina voce' }).click();
    await page.getByRole('button', { name: 'Conferma: elimina la voce' }).click();
    await expect(page.getByRole('status')).toContainText('eliminata');
    await go(page, '/menu');
    await expect(page.locator('.menu-item', { hasText: 'Spritz della casa' })).toHaveCount(0);
  });

  test('foto: il backend rifiuta un file finto anche se si chiama .jpg', async ({ page }) => {
    await signIn(page, 'Staff');
    await go(page, '/gestione/foto');
    await page.locator('input[type=file]').first().setInputFiles({
      name: 'cocktail.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('<?php echo "non sono una foto"; ?>'),
    });
    await expect(page.getByRole('alert')).toContainText('«cocktail.jpg»: Il file non è una foto JPG, PNG o WebP.');
  });

  test('foto: sostituzione del file, la foto cambia ovunque è usata', async ({ page }) => {
    await signIn(page, 'Staff');
    await go(page, '/menu');
    const before = await page.locator('.menu-item', { hasText: 'Cappuccino' }).locator('img').getAttribute('src');

    await go(page, '/gestione/foto');
    const item = page.locator('.photos__item:has(input[value^="Cappuccino in tazza"])');
    await item.locator('.photos__replace input[type=file]').setInputFiles('public/assets/fireflies/foto/tagliere-aperitivo.webp');
    await expect(page.getByRole('status')).toContainText('Foto sostituita');

    await go(page, '/menu');
    const after = await page.locator('.menu-item', { hasText: 'Cappuccino' }).locator('img').getAttribute('src');
    expect(after).not.toBe(before);
    expect((await page.request.get(after!)).status()).toBe(200);
    expect((await page.request.get(before!)).status(), 'il file vecchio non è più servito').toBe(404);

    // si rimette la foto originale (il ripristino automatico riporta testi e usi, non i file)
    await go(page, '/gestione/foto');
    await item.locator('.photos__replace input[type=file]').setInputFiles('public/assets/fireflies/foto/cappuccino-bancone.webp');
    await expect(page.getByRole('status')).toContainText('Foto sostituita');
  });

  test('foto: descrizione e galleria modificate di seguito, nessuna risposta ne annulla un’altra', async ({ page }) => {
    // Riproduce in modo deterministico la gara vista in CI (run 39279505974): il server applica prima la modifica
    // della galleria (con la vecchia descrizione) e poi la descrizione, ma la risposta della galleria arriva per ultima.
    await signIn(page, 'Staff');
    await go(page, '/gestione/foto');
    const item = page.locator('.photos__item:has(input[value^="Cappuccino in tazza"])');
    const id = (await item.getByRole('textbox').getAttribute('id'))!.replace('alt-', '');
    // da qui in poi la foto si trova per id: l'attributo value cambia appena si scrive nel campo
    const photo = page.locator(`.photos__item:has(#alt-${id})`);
    let releaseAlt!: () => void;
    const altMayGo = new Promise<void>((r) => (releaseAlt = r));
    let altAnswered!: () => void;
    const altFulfilled = new Promise<void>((r) => (altAnswered = r));
    await page.route(`**/api/v1/admin/media/${id}`, async (route) => {
      if (route.request().method() !== 'PATCH') return route.continue();
      if ('altText' in (route.request().postDataJSON() ?? {})) {
        await altMayGo;
        await route.fulfill({ response: await route.fetch() });
        altAnswered();
      } else {
        const response = await route.fetch(); // il server ha già salvato la galleria, con la vecchia descrizione
        releaseAlt();
        await altFulfilled;
        await route.fulfill({ response }); // risposta «vecchia» consegnata per ultima
      }
    });

    const before = await photo.getByRole('switch').getAttribute('aria-checked');
    await photo.getByRole('textbox').fill('Cappuccino e cornetto al bancone del Fire');
    await photo.getByRole('switch').click(); // il clic fa uscire dal campo: due PATCH quasi insieme
    await altFulfilled;
    await page.unrouteAll({ behavior: 'wait' });
    await page.waitForLoadState('networkidle');
    await expect(photo.getByRole('textbox'), 'la descrizione appena salvata resta a schermo')
      .toHaveValue('Cappuccino e cornetto al bancone del Fire');
    await expect(photo.getByRole('switch'), 'e anche la galleria').toHaveAttribute('aria-checked', String(before !== 'true'));

    // il server ha entrambe le modifiche
    const media = await (await page.request.get('/api/v1/admin/media')).json();
    const saved = media.find((m: { id: string }) => m.id === id);
    expect(saved.alt).toBe('Cappuccino e cornetto al bancone del Fire');
    expect(saved.inGallery).toBe(before !== 'true');
    // e il selettore di foto del menu (stessa scheda, senza ricaricare) la mostra con la descrizione nuova
    await go(page, '/gestione/menu/{Cappuccino}');
    await expect(page.getByRole('radio', { name: 'Cappuccino e cornetto al bancone del Fire' })).toBeChecked();
  });

  test('foto in uso: il backend blocca l’eliminazione anche aggirando la UI', async ({ page }) => {
    await signIn(page, 'Staff');
    const media = await (await page.request.get('/api/v1/admin/media')).json();
    const used = media.find((m: { usages: unknown[] }) => m.usages.length > 0);
    const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
    const response = await page.request.delete(`/api/v1/admin/media/${used.id}`, { headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) } });
    expect(response.status()).toBe(409);
    expect((await response.json()).code).toBe('MEDIA_IN_USE');
  });

  test('staff: testi e orari del sito restano del titolare anche via API', async ({ page }) => {
    await signIn(page, 'Staff');
    expect((await page.request.get('/api/v1/admin/site-content')).status()).toBe(403);
    const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
    const put = await page.request.put('/api/v1/admin/site-settings', {
      data: { version: 0, addressLine: 'Altrove', addressConfirmed: true, hoursConfirmed: true, hours: [] },
      headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) },
    });
    expect(put.status()).toBe(403);
    // lo staff gestisce invece gli avvisi
    await go(page, '/gestione/comunicazioni');
    await page.getByLabel('Testo').fill('Avviso scritto dallo staff');
    await page.getByRole('button', { name: 'Aggiungi avviso' }).click();
    await go(page, '/');
    await expect(page.getByRole('region', { name: 'Avvisi del locale' })).toContainText('Avviso scritto dallo staff');
  });

  test('modifica in contemporanea: chi salva per secondo viene avvisato, niente sovrascritture', async ({ page }) => {
    await signIn(page);
    await go(page, '/gestione/sito');
    await page.getByLabel('Frase principale').fill('Versione del browser');
    // nel frattempo un altro titolare pubblica (stessa API dell'area gestori)
    const other = await ownerApi();
    const current = await other.call('GET', '/admin/site-content');
    await other.call('PUT', '/admin/site-content', {
      version: current.version, heroClaim: 'Versione dell’altro titolare', heroLead: current.heroLead, heroMediaId: current.heroPhoto?.id,
      sections: current.sections, storyTitle: current.storyTitle, storyText: current.storyText, storyConfirmed: current.storyConfirmed,
      owners: current.owners.map((o: Record<string, any>) => ({ id: o.id, name: o.name, roleLabel: o.roleLabel, description: o.description, // eslint-disable-line @typescript-eslint/no-explicit-any
        motto: o.motto, color: o.color, frontMediaId: o.front?.id, backMediaId: o.back?.id, visible: o.visible })),
      ownersConfirmed: current.ownersConfirmed, staff: current.staff.map((s: Record<string, any>) => ({ id: s.id, name: s.name, roleLabel: s.roleLabel, mediaId: s.photo?.id, visible: s.visible })), // eslint-disable-line @typescript-eslint/no-explicit-any
      staffConfirmed: current.staffConfirmed, menuHeroMediaIds: current.menuHero.photos.map((p: { id: string }) => p.id), menuHeroIntervalSec: current.menuHero.intervalSec,
    });
    await other.dispose();
    await page.getByRole('button', { name: 'Salva e pubblica' }).click();
    await expect(page.getByRole('alert')).toContainText('Qualcun altro ha salvato modifiche nel frattempo: ricarica la pagina');
    // come chiede il messaggio: ricaricando si vede la versione salvata dall'altro titolare, la propria non l'ha sovrascritta
    await go(page, '/');
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Versione dell’altro titolare');
  });
});

test.describe('Sessione scaduta in una scheda già aperta', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('salvando con la sessione scaduta si torna all’accesso con un avviso', async ({ page, context }) => {
    await signIn(page, 'Staff');
    await go(page, '/gestione/menu/{Birra}');
    await page.getByLabel('Prezzo (€)').fill('5');
    const session = (await context.cookies()).find((c) => c.name === 'FF_SESSION')!;
    await context.addCookies([{ ...session, value: 'ZmZmZmZmZmYtZmZmZi1mZmZmLWZmZmYtZmZmZmZmZmZmZmZm' }]); // sessione sconosciuta
    await page.getByRole('button', { name: 'Salva' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
    await expect(page.getByRole('alert')).toContainText('La sessione è scaduta');
    await go(page, '/menu');
    await expect(page.locator('.menu-item', { hasText: /^Birra/ })).not.toContainText('€ 5,00');
  });

  test('tornando sulla scheda dopo la scadenza si vede subito l’accesso', async ({ page, context }) => {
    await signIn(page, 'Staff');
    await go(page, '/gestione/menu');
    const session = (await context.cookies()).find((c) => c.name === 'FF_SESSION')!;
    await context.addCookies([{ ...session, value: 'ZmZmZmZmZmYtZmZmZi1mZmZmLWZmZmYtZmZmZmZmZmZmZmZm' }]);
    await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
    await expect(page.getByRole('alert')).toContainText('La sessione è scaduta');
    await expect(page.getByRole('navigation', { name: 'Area gestori' })).toHaveCount(0);
  });
});
