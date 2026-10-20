// Locandine degli eventi sempre intere (FASE 7 · requisito 15): nessun ritaglio in alto, in basso o ai lati, proporzioni
// originali, nessuno scorrimento orizzontale. Su ogni larghezza richiesta si fotografa la locandina con Playwright e si
// controlla che le quattro bande colorate disegnate sui bordi dell'immagine di prova siano tutte visibili.
//
// Oltre alle tre locandine generate (verticale, quadrata, orizzontale), ogni immagine messa in tests/fixtures/locandine/
// (es. la locandina reale di riferimento) viene caricata e verificata allo stesso modo, con i controlli geometrici.
import { expect, request, test, type APIRequestContext, type Page, type TestInfo } from '@playwright/test';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import sharp from 'sharp';
import { credentials, go, horizontalOverflow, signIn } from './helpers';

const BACKEND = () => process.env.FIREFLIES_BACKEND_URL ?? 'http://localhost:18080';
const VIEWPORTS = [[1920, 1080], [1440, 900], [1366, 768], [768, 1024], [390, 844], [375, 812], [360, 780]] as const;
const BAND = 0.05; // spessore delle bande sui bordi (frazione del lato corto)

type Poster = { title: string; slug?: string; file: Buffer; marked: boolean; ratio: number };

/** Locandina di prova: fondo scuro, testo, e quattro bande colorate sui bordi (rosso, blu, verde, giallo). */
async function makePoster(width: number, height: number, label: string): Promise<Buffer> {
  const b = Math.round(Math.min(width, height) * BAND);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">
    <rect width="100%" height="100%" fill="#2a2a2a"/>
    <rect x="0" y="0" width="${width}" height="${b}" fill="#ff0000"/>
    <rect x="0" y="${height - b}" width="${width}" height="${b}" fill="#0000ff"/>
    <rect x="0" y="${b}" width="${b}" height="${height - 2 * b}" fill="#00ff00"/>
    <rect x="${width - b}" y="${b}" width="${b}" height="${height - 2 * b}" fill="#ffff00"/>
    <text x="50%" y="50%" font-size="${Math.round(width / 12)}" fill="#ffffff" text-anchor="middle" font-family="sans-serif">${label}</text>
    <text x="50%" y="${b * 2.5}" font-size="${Math.round(width / 20)}" fill="#ffffff" text-anchor="middle" font-family="sans-serif">SAB 21:30 · TEL 000</text>
  </svg>`;
  return sharp(Buffer.from(svg)).jpeg({ quality: 92 }).toBuffer();
}

/** Sessione del titolare con caricamento multipart (per le foto) e JSON. */
async function ownerSession(): Promise<{ ctx: APIRequestContext; headers: () => Promise<Record<string, string>> }> {
  const ctx = await request.newContext({ baseURL: BACKEND() });
  const token = async () => decodeURIComponent((await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '');
  await ctx.get('/api/v1/auth/csrf');
  const { email, password } = credentials('Titolare');
  const login = await ctx.post('/api/v1/auth/login', { data: { email, password }, headers: { 'X-XSRF-TOKEN': await token() } });
  expect(login.ok(), 'login titolare').toBe(true);
  await ctx.get('/api/v1/auth/csrf');
  return { ctx, headers: async () => ({ 'X-XSRF-TOKEN': await token() }) };
}

/** Carica la foto e pubblica una serata «posti condivisi», senza prenotazioni e lontana nel tempo: non tocca il registro. */
async function publishPoster(p: Poster, index: number) {
  const { ctx, headers } = await ownerSession();
  try {
    const media = await ctx.post('/api/v1/admin/media', {
      headers: await headers(),
      multipart: { file: { name: 'locandina.jpg', mimeType: 'image/jpeg', buffer: p.file }, altText: `Locandina ${p.title}` },
    });
    expect(media.status(), await media.text()).toBe(201);
    const mediaId = (await media.json()).id;
    const day = new Date(Date.now() + (45 + index) * 86_400_000);
    const iso = new Date(day.getTime() - day.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
    const created = await ctx.post('/api/v1/admin/events', {
      headers: await headers(),
      data: {
        title: p.title, kind: 'SERATA', date: iso, startTime: '23:00', endTime: '23:45', exclusive: false, bookingMode: 'NONE',
        summary: 'Locandina di prova per il controllo dei ritagli.', posterMediaId: mediaId, mediaIds: [mediaId],
      },
    });
    expect(created.status(), await created.text()).toBe(201);
    const event = await created.json();
    const published = await ctx.post(`/api/v1/admin/events/${event.id}/publish`, { headers: await headers(), data: { version: event.version } });
    expect(published.status(), await published.text()).toBe(200);
    p.slug = event.slug;
  } finally {
    await ctx.dispose();
  }
}

type Geometry = {
  fit: string; box: { x: number; y: number; width: number; height: number }; natural: { w: number; h: number };
  content: { x: number; y: number; width: number; height: number }; clippedBy: string | null; loaded: boolean;
};

/** Riquadro dell'immagine, area effettivamente disegnata (object-fit) e primo antenato che la taglierebbe. */
async function geometry(page: Page, selector: string): Promise<Geometry> {
  await page.locator(selector).first().scrollIntoViewIfNeeded();
  await page.locator(selector).first().evaluate((img: HTMLImageElement) => img.decode().catch(() => undefined));
  return page.locator(selector).first().evaluate((img: HTMLImageElement) => {
    const r = img.getBoundingClientRect();
    const fit = getComputedStyle(img).objectFit;
    const nw = img.naturalWidth;
    const nh = img.naturalHeight;
    let content = { x: r.x, y: r.y, width: r.width, height: r.height };
    if (fit === 'contain' || fit === 'scale-down') {
      const scale = Math.min(r.width / nw, r.height / nh, fit === 'scale-down' ? 1 : Infinity);
      const w = nw * scale;
      const h = nh * scale;
      content = { x: r.x + (r.width - w) / 2, y: r.y + (r.height - h) / 2, width: w, height: h };
    }
    let clippedBy: string | null = null;
    for (let el = img.parentElement; el && el !== document.body; el = el.parentElement) {
      const cs = getComputedStyle(el);
      if (cs.overflow === 'visible' && cs.overflowX === 'visible' && cs.overflowY === 'visible') continue;
      const a = el.getBoundingClientRect();
      if (content.x < a.x - 0.5 || content.y < a.y - 0.5 || content.x + content.width > a.right + 0.5
          || content.y + content.height > a.bottom + 0.5) {
        clippedBy = `${el.tagName.toLowerCase()}.${el.className}`;
        break;
      }
    }
    return { fit, box: { x: r.x, y: r.y, width: r.width, height: r.height }, natural: { w: nw, h: nh }, content, clippedBy,
      loaded: img.complete && nw > 0 };
  });
}

/** Controlli comuni: nessun cover, proporzioni conservate, niente ritagli dagli antenati, bande visibili. */
async function assertWhole(page: Page, testInfo: TestInfo, selector: string, p: Poster, name: string, thumbnail = false) {
  const g = await geometry(page, selector);
  expect(g.loaded, `${name}: immagine caricata`).toBe(true);
  expect(g.fit, `${name}: object-fit`).not.toBe('cover');
  expect(g.fit, `${name}: object-fit`).not.toBe('fill');
  const shown = g.content.width / g.content.height;
  expect(Math.abs(shown / p.ratio - 1), `${name}: proporzioni (mostrata ${shown.toFixed(3)}, originale ${p.ratio.toFixed(3)})`).toBeLessThan(0.02);
  expect(g.clippedBy, `${name}: tagliata da un contenitore`).toBeNull();
  if (!thumbnail) expect(Math.min(g.content.width, g.content.height), `${name}: dimensione leggibile`).toBeGreaterThan(150);

  // screenshot dell'elemento, ritagliato sull'area disegnata: le quattro bande dei bordi devono esserci tutte.
  // L'elemento va al centro dello schermo: la barra fissa «Richiedi un tavolo» dello smartphone non deve coprirlo.
  await page.locator(selector).first().evaluate((img) => img.scrollIntoView({ block: 'center' }));
  const element = await page.locator(selector).first().screenshot();
  const left = Math.max(0, Math.round(g.content.x - g.box.x));
  const top = Math.max(0, Math.round(g.content.y - g.box.y));
  const meta = await sharp(element).metadata();
  const area = { left, top, width: Math.min(meta.width! - left, Math.floor(g.content.width)), height: Math.min(meta.height! - top, Math.floor(g.content.height)) };
  const shot = await sharp(element).extract(area).png().toBuffer();
  await testInfo.attach(name, { body: shot, contentType: 'image/png' });
  if (!p.marked || thumbnail) return;
  const { data, info } = await sharp(shot).raw().toBuffer({ resolveWithObject: true });
  const px = (fx: number, fy: number) => {
    const x = Math.min(info.width - 1, Math.max(0, Math.round(fx * (info.width - 1))));
    const y = Math.min(info.height - 1, Math.max(0, Math.round(fy * (info.height - 1))));
    const i = (y * info.width + x) * info.channels;
    return [data[i], data[i + 1], data[i + 2]];
  };
  // metà spessore della banda, in frazione della larghezza (bande verticali) e dell'altezza (bande orizzontali)
  const bandX = (BAND * Math.min(1, 1 / p.ratio)) / 2;
  const bandY = (BAND * Math.min(1, p.ratio)) / 2;
  const is = ([r, gr, b]: number[], want: string) =>
    want === 'red' ? r > 180 && gr < 90 && b < 90
      : want === 'blue' ? b > 180 && r < 90 && gr < 90
        : want === 'green' ? gr > 180 && r < 90 && b < 90
          : r > 180 && gr > 180 && b < 90;
  expect(is(px(0.5, bandY), 'red'), `${name}: bordo superiore visibile`).toBe(true);
  expect(is(px(0.5, 1 - bandY), 'blue'), `${name}: bordo inferiore visibile`).toBe(true);
  expect(is(px(bandX, 0.5), 'green'), `${name}: bordo sinistro visibile`).toBe(true);
  expect(is(px(1 - bandX, 0.5), 'yellow'), `${name}: bordo destro visibile`).toBe(true);
}

const posters: Poster[] = [];

test.beforeAll(async () => {
  const stamp = Date.now();
  const generated: [string, number, number][] = [['verticale', 1080, 1620], ['quadrata', 1200, 1200], ['orizzontale', 1600, 900]];
  for (const [kind, w, h] of generated) {
    posters.push({ title: `Locandina ${kind} ${stamp}`, file: await makePoster(w, h, kind.toUpperCase()), marked: true, ratio: w / h });
  }
  const dir = join(process.cwd(), 'tests', 'fixtures', 'locandine');
  if (existsSync(dir)) {
    for (const f of readdirSync(dir).filter((x) => /\.(jpe?g|png|webp)$/i.test(x))) {
      const file = await sharp(readFileSync(join(dir, f))).rotate().jpeg({ quality: 92 }).toBuffer();
      const meta = await sharp(file).metadata();
      posters.push({ title: `Locandina fornita ${f} ${stamp}`, file, marked: false, ratio: meta.width! / meta.height! });
    }
  }
  for (let i = 0; i < posters.length; i++) await publishPoster(posters[i], i);
});

for (const [w, h] of VIEWPORTS) {
  test(`locandine intere a ${w}px: elenco, dettaglio, nessuno scorrimento orizzontale`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: w, height: h });
    for (const p of posters) {
      await go(page, '/eventi');
      const row = `.event-row:has(.event-row__title:text-is("${p.title}")) .event-row__img`;
      await assertWhole(page, testInfo, row, p, `${w}px elenco · ${p.title}`);
      expect(await horizontalOverflow(page), `${w}px elenco: scorrimento orizzontale`).toBeLessThanOrEqual(0);

      await go(page, `/eventi/${p.slug}`);
      await assertWhole(page, testInfo, '.event-detail__hero', p, `${w}px dettaglio · ${p.title}`);
      expect(await horizontalOverflow(page), `${w}px dettaglio: scorrimento orizzontale`).toBeLessThanOrEqual(0);
      const hero = await page.locator('.event-detail__poster').boundingBox();
      const info = await page.locator('.event-detail__info').boundingBox();
      if (w >= 900) {
        // desktop: locandina e informazioni affiancate, la locandina sta tutta nello schermo
        expect(info!.x, `${w}px: informazioni accanto alla locandina`).toBeGreaterThan(hero!.x + hero!.width - 1);
        expect(hero!.height, `${w}px: locandina alta al massimo quanto lo schermo`).toBeLessThanOrEqual(h);
      } else {
        // tablet e smartphone: prima la locandina intera, poi le informazioni
        expect(info!.y, `${w}px: informazioni sotto la locandina`).toBeGreaterThanOrEqual(hero!.y + hero!.height - 1);
        expect(hero!.width, `${w}px: riquadro della locandina a tutta larghezza`).toBeGreaterThan(w * 0.8);
        if (w > 640) expect(hero!.height, `${w}px: su tablet la locandina sta nello schermo`).toBeLessThanOrEqual(h);
      }
    }
  });
}

test('area gestori: anteprime delle locandine intere (elenco ed editor)', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await signIn(page);
  for (const p of posters) {
    await go(page, '/gestione/eventi');
    await assertWhole(page, testInfo, `.ev-admin__item:has(h2:text-is("${p.title}")) .ev-admin__img`, p, `elenco gestori · ${p.title}`, true);
    await page.getByRole('link', { name: `Modifica ${p.title}` }).click();
    await assertWhole(page, testInfo, '.editor__preview--poster', p, `editor · ${p.title}`);
  }
  const fit = await page.locator('.picker--contain .picker__opt img').first().evaluate((img) => getComputedStyle(img).objectFit);
  expect(fit, 'anteprime della libreria nell\'editor eventi').toBe('contain');
});
