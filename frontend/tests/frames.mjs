// Presentazione: inserisce gli screenshot REALI in cornici di laptop e smartphone.
// Il contenuto delle schermate non viene alterato: si aggiungono solo cornice, barra di stato del telefono e base del laptop.
import sharp from 'sharp';
import { mkdirSync } from 'node:fs';

const SRC = '../mockup-grafici';
const OUT = `${SRC}/presentazione`;
mkdirSync(OUT, { recursive: true });

const svg = (w, h, body) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">${body}</svg>`);
const text = (x, y, size, color, t, weight = 700, anchor = 'start') =>
  `<text x="${x}" y="${y}" font-family="DejaVu Sans, Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="${color}" text-anchor="${anchor}">${t.replace(/&/g, '&amp;')}</text>`;

async function rounded(buf, w, h, r) {
  const mask = svg(w, h, `<rect width="${w}" height="${h}" rx="${r}" ry="${r}" fill="#fff"/>`);
  return sharp(buf).resize(w, h).composite([{ input: mask, blend: 'dest-in' }]).png().toBuffer();
}

/** Smartphone: schermo 390×844 (screenshot @2x → 780×1688), barra di stato sopra lo screenshot. */
export async function phone(file) {
  const SW = 780, SH = 1688, STATUS = 96, BEZEL = 34;
  const W = SW + BEZEL * 2, H = SH + STATUS + BEZEL * 2;
  const shot = await sharp(`${SRC}/${file}`).extract({ left: 0, top: 0, width: SW, height: SH }).toBuffer();
  const status = svg(SW, STATUS, `<rect width="${SW}" height="${STATUS}" fill="#101010"/>
    ${text(64, 62, 30, '#F5F0E8', '9:41', 700)}
    <rect x="${SW / 2 - 110}" y="18" width="220" height="62" rx="31" fill="#000"/>
    <rect x="${SW - 150}" y="40" width="44" height="22" rx="6" fill="none" stroke="#F5F0E8" stroke-width="3"/>
    <rect x="${SW - 146}" y="44" width="30" height="14" rx="3" fill="#F5F0E8"/>
    <rect x="${SW - 104}" y="47" width="4" height="8" rx="2" fill="#F5F0E8"/>
    <circle cx="${SW - 196}" cy="51" r="11" fill="none" stroke="#F5F0E8" stroke-width="3"/>`);
  const screen = await sharp({ create: { width: SW, height: SH + STATUS, channels: 4, background: '#101010' } })
    .composite([{ input: status, top: 0, left: 0 }, { input: shot, top: STATUS, left: 0 }]).png().toBuffer();
  const body = svg(W, H, `<rect x="2" y="2" width="${W - 4}" height="${H - 4}" rx="118" fill="#0b0b0b" stroke="#3a3a3a" stroke-width="4"/>
    <rect x="${W - 2}" y="300" width="4" height="120" fill="#2a2a2a"/>`);
  return sharp(body).composite([{ input: await rounded(screen, SW, SH + STATUS, 86), top: BEZEL, left: BEZEL }]).png().toBuffer();
}

/** Laptop: schermo 1440×900 con cornice nera, fotocamera e base in alluminio. */
export async function laptop(file) {
  const SW = 1440, SH = 900, B = 36, TOP = 44;
  const LW = SW + B * 2, LH = SH + B + TOP;
  const shot = await sharp(`${SRC}/${file}`).extract({ left: 0, top: 0, width: SW, height: SH }).toBuffer();
  const lid = svg(LW, LH, `<rect width="${LW}" height="${LH}" rx="34" fill="#0d0d0d" stroke="#3b3b3b" stroke-width="3"/>
    <circle cx="${LW / 2}" cy="${TOP / 2}" r="6" fill="#262626"/>`);
  const BASE_W = LW + 240, BASE_H = 46;
  const base = svg(BASE_W, BASE_H, `<path d="M0 0 H${BASE_W} V18 Q${BASE_W} ${BASE_H} ${BASE_W - 60} ${BASE_H} H60 Q0 ${BASE_H} 0 18 Z" fill="#b9b6b0"/>
    <rect x="${BASE_W / 2 - 130}" y="0" width="260" height="12" rx="6" fill="#8e8b85"/>`);
  const W = BASE_W, H = LH + BASE_H;
  return sharp({ create: { width: W, height: H, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
    .composite([
      { input: lid, top: 0, left: 120 },
      { input: await rounded(shot, SW, SH, 6), top: TOP, left: 120 + B },
      { input: base, top: LH, left: 0 },
    ]).png().toBuffer();
}

async function scene(layers, w, h, title, sub, out) {
  const bg = svg(w, h, `<rect width="${w}" height="${h}" fill="#101010"/>
    ${title ? text(96, 120, 64, '#F5F0E8', title, 800) : ''}${sub ? text(96, 180, 30, '#C5A069', sub, 600) : ''}`);
  await sharp(bg).composite(layers).png({ compressionLevel: 9 }).toFile(`${OUT}/${out}`);
  console.log(out);
}

const scale = async (buf, w) => sharp(buf).resize({ width: w }).png().toBuffer();

// Singole cornici
const L = [
  ['desktop/01-home-iniziale.png', '01-laptop-home.png'],
  ['desktop/06-dettaglio-prodotto.png', '02-laptop-scheda-prodotto.png'],
  ['desktop/07-calendario-eventi.png', '03-laptop-eventi.png'],
  ['desktop/14-prenotazione-riepilogo.png', '04-laptop-prenotazione.png'],
  ['desktop/16-locale-titolari.png', '05-laptop-titolari.png'],
  ['desktop/22-dashboard.png', '06-laptop-dashboard.png'],
];
for (const [src, out] of L) {
  const f = await laptop(src);
  const m = await sharp(f).metadata();
  await scene([{ input: f, top: 80, left: 80 }], m.width + 160, m.height + 160, '', '', out);
}
const P = [
  ['mobile/01-home.png', '07-telefono-home.png'],
  ['mobile/02-menu-aperto.png', '08-telefono-menu-aperto.png'],
  ['mobile/06-menu-filtri.png', '09-telefono-menu-filtri.png'],
  ['mobile/07-scheda-prodotto.png', '10-telefono-scheda-prodotto.png'],
  ['mobile/08-eventi.png', '11-telefono-eventi.png'],
  ['mobile/11-prenotazione-orario.png', '12-telefono-prenotazione.png'],
  ['mobile/16-galleria.png', '13-telefono-galleria.png'],
  ['mobile/18-locale-titolari.png', '14-telefono-titolari.png'],
];
for (const [src, out] of P) {
  const f = await phone(src);
  const m = await sharp(f).metadata();
  await scene([{ input: f, top: 80, left: 80 }], m.width + 160, m.height + 160, '', '', out);
}

// Composizione principale: laptop + telefono
{
  const lap = await scale(await laptop('desktop/01-home-iniziale.png'), 2200);
  const ph = await scale(await phone('mobile/01-home.png'), 560);
  const lm = await sharp(lap).metadata();
  const pm = await sharp(ph).metadata();
  await scene(
    [{ input: lap, top: 260, left: 96 }, { input: ph, top: 260 + lm.height - pm.height + 40, left: 96 + lm.width - 360 }],
    96 + lm.width + 260, 260 + lm.height + 140,
    'FireFlies Aperitif — prototipo navigabile',
    'Screenshot reali del prototipo · desktop 1440×900 e smartphone 390×844 · contenuti DEMO / DA CONFERMARE',
    '00-presentazione-laptop-telefono.png',
  );
}

// Flusso di prenotazione su cinque telefoni
{
  const steps = ['mobile/10-prenotazione-giorno.png', 'mobile/11-prenotazione-orario.png', 'mobile/12-prenotazione-persone.png', 'mobile/14-prenotazione-riepilogo.png', 'mobile/15-prenotazione-conferma.png'];
  const labels = ['1 · Giorno', '2 · Orario', '3 · Persone', '5 · Riepilogo', '6 · Conferma simulata'];
  const phones = await Promise.all(steps.map(async (s) => scale(await phone(s), 520)));
  const pm = await sharp(phones[0]).metadata();
  const layers = phones.flatMap((p, i) => [
    { input: p, top: 260, left: 96 + i * (pm.width + 60) },
    { input: svg(pm.width, 60, text(pm.width / 2, 40, 30, '#F5F0E8', labels[i], 700, 'middle')), top: 260 + pm.height + 24, left: 96 + i * (pm.width + 60) },
  ]);
  await scene(layers, 96 * 2 + 5 * pm.width + 4 * 60, 260 + pm.height + 140, 'Prenotazione in 6 passi', 'Nessuna prenotazione reale: l’ultimo passo lo dichiara', '15-flusso-prenotazione.png');
}
