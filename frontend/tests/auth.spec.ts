import { expect, test } from '@playwright/test';
import { credentials, go, signIn, watchProblems } from './helpers';

// Accesso reale all'area gestori (FASE 3): backend Spring Boot + PostgreSQL avviati da scripts/e2e.mjs.
// La sicurezza è verificata anche chiamando le API direttamente, come farebbe chi aggira l'interfaccia.

test.describe('Accesso gestori reale', () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('la pagina di accesso non fa scegliere il ruolo', async ({ page }) => {
    const problems = watchProblems(page);
    await go(page, '/gestione/login');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
    await expect(page.getByRole('radio')).toHaveCount(0);
    await expect(page.getByText('Entra come')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /dati demo/ })).toHaveCount(0);
    await expect(page.getByLabel('Email')).toBeVisible();
    await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
    expect(problems).toEqual([]);
  });

  test('senza accesso: pagine riservate rimandano al login e le API rispondono 401', async ({ page }) => {
    await go(page, '/gestione/richieste');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
    const api = await page.request.get('/api/v1/admin/users');
    expect(api.status()).toBe(401);
    expect((await api.json()).code).toBe('UNAUTHENTICATED');
  });

  test('credenziali sbagliate: messaggio generico e nessun accesso', async ({ page }) => {
    await go(page, '/gestione/login');
    await page.getByLabel('Email').fill(credentials('Titolare').email);
    await page.getByLabel('Password', { exact: true }).fill('password-sbagliata-di-prova');
    await page.getByRole('button', { name: 'Accedi' }).click();
    const alert = page.getByRole('alert');
    await expect(alert).toHaveText('Email o password non corretti.');
    await expect(alert).toBeFocused();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
  });

  test('titolare: ruolo dal backend, tutte le sezioni e API del titolare', async ({ page }) => {
    const problems = watchProblems(page);
    await signIn(page, 'Titolare');
    await expect(page.locator('.admin__brand')).toContainText('Titolare');
    const nav = page.getByRole('navigation', { name: 'Area gestori' });
    await expect(nav.getByRole('link', { name: 'Personalizza il sito' })).toBeVisible();
    const users = await page.request.get('/api/v1/admin/users');
    expect(users.status()).toBe(200);
    expect(problems).toEqual([]);
  });

  test('staff: niente sezioni del titolare e il backend nega le sue API anche aggirando la UI', async ({ page }) => {
    await signIn(page, 'Staff');
    await expect(page.locator('.admin__brand')).toContainText('Staff');
    const nav = page.getByRole('navigation', { name: 'Area gestori' });
    await expect(nav.getByRole('link', { name: 'Personalizza il sito' })).toHaveCount(0);
    await go(page, '/gestione/impostazioni');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Riservato al titolare');

    const users = await page.request.get('/api/v1/admin/users');
    expect(users.status()).toBe(403);
    expect((await users.json()).code).toBe('FORBIDDEN');
  });

  test('il cookie di sessione non è leggibile da JavaScript', async ({ page, context }) => {
    await signIn(page, 'Staff');
    const session = (await context.cookies()).find((c) => c.name === 'FF_SESSION');
    expect(session?.httpOnly).toBe(true);
    expect(session?.sameSite).toBe('Lax');
    expect(await page.evaluate(() => document.cookie)).not.toContain('FF_SESSION');
  });

  test('le scritture senza token CSRF sono rifiutate anche con una sessione valida', async ({ page }) => {
    await signIn(page, 'Titolare');
    const forged = await page.request.post('/api/v1/admin/users', {
      data: { email: 'intruso@example.invalid', displayName: 'Intruso', role: 'OWNER', password: 'una-frase-abbastanza-lunga' },
    });
    expect(forged.status()).toBe(403);
    expect((await forged.json()).code).toBe('CSRF_INVALID');
  });

  test('esci: sessione chiusa, le pagine riservate non si riaprono', async ({ page }) => {
    await signIn(page, 'Staff');
    await page.getByRole('button', { name: 'Esci' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
    expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401);

    await go(page, '/gestione/menu');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Area gestori');
    await page.goBack();
    await expect(page.getByRole('navigation', { name: 'Area gestori' })).toHaveCount(0);
  });

  test('sessione scaduta: si torna all’accesso con un avviso', async ({ page, context }) => {
    await signIn(page, 'Staff');
    const cookies = await context.cookies();
    const session = cookies.find((c) => c.name === 'FF_SESSION')!;
    // Una sessione che il server non conosce più (scaduta, chiusa o cancellata dal job di pulizia).
    await context.addCookies([{ ...session, value: 'ZmZmZmZmZmYtZmZmZi1mZmZmLWZmZmYtZmZmZmZmZmZmZmZm' }]);
    await go(page, '/gestione/menu');
    await expect(page.getByRole('alert')).toContainText('La sessione è scaduta');
    await expect(page.getByRole('navigation', { name: 'Area gestori' })).toHaveCount(0);
  });

  test('troppi tentativi: blocco temporaneo anche per un’email inesistente', async ({ page }) => {
    await go(page, '/gestione/login');
    await page.getByLabel('Email').fill('nessuno.e2e@example.invalid');
    for (let i = 0; i < 5; i++) {
      await page.getByLabel('Password', { exact: true }).fill(`sbagliata-${i}`);
      await page.getByRole('button', { name: 'Accedi' }).click();
      await expect(page.getByRole('alert')).toHaveText('Email o password non corretti.');
    }
    await page.getByLabel('Password', { exact: true }).fill('sbagliata-ancora');
    await page.getByRole('button', { name: 'Accedi' }).click();
    await expect(page.getByRole('alert')).toContainText('Troppi tentativi di accesso. Riprova tra');
  });
});
