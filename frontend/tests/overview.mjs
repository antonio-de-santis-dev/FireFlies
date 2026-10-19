// Compone ../mockup-grafici/presentazione/mockup-overview.png usando SOLO gli screenshot già catturati da screenshots.spec.ts.
import sharp from 'sharp';

const W = 3200;
const PAD = 64;
const GAP = 32;
const BG = '#101010';
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');

const label = (text, width, size = 26, color = '#F5F0E8') =>
  Buffer.from(
    `<svg width="${width}" height="${size + 18}" xmlns="http://www.w3.org/2000/svg"><text x="0" y="${size}" font-family="DejaVu Sans, Arial, sans-serif" font-size="${size}" font-weight="700" fill="${color}">${esc(text)}</text></svg>`,
  );

async function tile(file, w, h) {
  // ritaglio dall'alto (primo schermo) e ridimensionamento proporzionale
  const img = sharp(`../mockup-grafici/${file}`);
  const meta = await img.metadata();
  const cropH = Math.min(meta.height, Math.round((meta.width * h) / w));
  return sharp(`../mockup-grafici/${file}`).extract({ left: 0, top: 0, width: meta.width, height: cropH }).resize(w, h, { fit: 'cover', position: 'top' }).png().toBuffer();
}

const sections = [
  {
    title: 'Desktop 1440 px — area pubblica',
    cols: 4,
    ratio: 900 / 1440,
    items: [
      ['desktop/01-home-iniziale.png', 'Home iniziale'],
      ['desktop/02-home-scroll.png', 'Home durante lo scroll'],
      ['desktop/05-menu-digitale.png', 'Menu digitale'],
      ['desktop/06-dettaglio-prodotto.png', 'Scheda prodotto'],
      ['desktop/07-calendario-eventi.png', 'Eventi e calendario'],
      ['desktop/09-dettaglio-evento.png', 'Dettaglio evento'],
      ['desktop/10-galleria.png', 'Galleria'],
      ['desktop/12-prenotazione-orario.png', 'Prenotazione in corso'],
      ['desktop/15-prenotazione-conferma-simulata.png', 'Conferma simulata'],
      ['desktop/16-locale-titolari.png', 'Il locale: titolari'],
      ['desktop/19-contatti-mappa-consenso.png', 'Contatti e mappa'],
      ['desktop/32-home-1920.png', 'Home 1920×1080'],
    ],
  },
  {
    title: 'Desktop 1440 px — area gestori',
    cols: 4,
    ratio: 900 / 1440,
    items: [
      ['desktop/22-dashboard.png', 'Dashboard'],
      ['desktop/23-gestione-menu.png', 'Gestione menu e prodotti'],
      ['desktop/24-modifica-prodotto.png', 'Modifica prodotto'],
      ['desktop/26-gestione-eventi.png', 'Gestione eventi'],
      ['desktop/27-prenotazioni.png', 'Prenotazioni'],
      ['desktop/28-galleria-fotografica.png', 'Galleria fotografica'],
      ['desktop/29-personalizza-sito.png', 'Personalizza il sito'],
      ['desktop/31-impostazioni-locale.png', 'Impostazioni del locale'],
    ],
  },
  {
    title: 'Smartphone 390 px',
    cols: 8,
    ratio: 844 / 390,
    items: [
      ['mobile/01-home.png', 'Home'],
      ['mobile/02-menu-aperto.png', 'Menu aperto'],
      ['mobile/03-home-scroll.png', 'Scorrimento'],
      ['mobile/06-menu-filtri.png', 'Menu con filtri'],
      ['mobile/07-scheda-prodotto.png', 'Scheda prodotto'],
      ['mobile/08-eventi.png', 'Eventi'],
      ['mobile/11-prenotazione-orario.png', 'Prenotazione'],
      ['mobile/16-galleria.png', 'Galleria'],
    ],
  },
];

const layers = [];
let y = PAD;
layers.push({ input: label('FireFlies Aperitif — prototipo navigabile (mockup, non produzione)', W - PAD * 2, 56), top: y, left: PAD });
y += 84;
layers.push({ input: label('Screenshot reali catturati con Playwright dalla build del prototipo · contenuti DEMO / DA CONFERMARE', W - PAD * 2, 28, '#C5A069'), top: y, left: PAD });
y += 80;

for (const s of sections) {
  if (s.title) {
    layers.push({ input: label(s.title, W - PAD * 2, 34, '#C5A069'), top: y, left: PAD });
    y += 64;
  }
  const tw = Math.floor((W - PAD * 2 - GAP * (s.cols - 1)) / s.cols);
  const th = Math.round(tw * s.ratio);
  for (let i = 0; i < s.items.length; i++) {
    const [file, text] = s.items[i];
    const col = i % s.cols;
    const row = Math.floor(i / s.cols);
    const left = PAD + col * (tw + GAP);
    const top = y + row * (th + 64);
    layers.push({ input: await tile(file, tw, th), top, left });
    layers.push({ input: label(text, tw, 24), top: top + th + 12, left });
  }
  y += Math.ceil(s.items.length / s.cols) * (th + 64) + 32;
}

await sharp({ create: { width: W, height: y + PAD, channels: 3, background: BG } })
  .composite(layers)
  .png({ compressionLevel: 9 })
  .toFile('../mockup-grafici/presentazione/mockup-overview.png');
console.log(`presentazione/mockup-overview.png ${W}×${y + PAD}`);
