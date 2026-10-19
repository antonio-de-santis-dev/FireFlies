// Asset reali forniti dal cliente (ZIP «imafginiFierflies»), ridimensionati in WebP.
// Mappatura completa file originale → asset → sezione in docs/ASSET-INVENTORY.md.

const BASE = './assets/fireflies';

export type Asset = { src: string; alt: string; w: number; h: number };

const a = (path: string, w: number, h: number, alt: string): Asset => ({ src: `${BASE}/${path}`, w, h, alt });

export const photos = {
  djSet: a('foto/dj-set-ingresso.webp', 1440, 1069, "Due ragazzi alla consolle per un DJ set all'ingresso del locale, sotto l'insegna FireFlies"),
  caliceLamponi: a('foto/calice-lamponi-menta.webp', 1125, 1406, 'Calice decorato con drink chiaro, lamponi e menta fresca sul bancone'),
  tumblerLamponi: a('foto/tumbler-lamponi-alto.webp', 1440, 1440, "Bicchiere con ghiaccio, spiedino di lamponi e menta visto dall'alto"),
  servizioTagliere: a('foto/servizio-tagliere-bancone.webp', 1080, 1440, 'Un ragazzo dello staff porge un tagliere a un cliente al bancone'),
  tagliere: a('foto/tagliere-aperitivo.webp', 900, 1200, 'Tagliere da aperitivo con panini, focaccia, crudo e verdure'),
  bartenderTagliere: a('foto/bartender-prepara-tagliere.webp', 900, 1200, 'Bartender con occhiali scuri che completa un tagliere davanti alla bottigliera'),
  cappuccino: a('foto/cappuccino-bancone.webp', 1080, 1080, 'Cappuccino in tazza Caffè Moreno con cornetto sul bancone'),
  bartenderShaker: a('foto/bartender-shaker-insegna.webp', 1440, 1440, "Bartender versa un cocktail dallo shaker sotto l'insegna FireFlies"),
  cocktailRosso: a('foto/cocktail-rosso-bitter.webp', 1200, 1600, 'Cocktail rosso versato in una coppa tra bottiglie di bitter e vermouth'),
  longDrinkBlu: a('foto/long-drink-blu.webp', 1200, 1600, 'Long drink azzurro con rosmarino e ciotola di stuzzichini accanto a una lampada'),
  ospiteCrostino: a('foto/ospite-crostino.webp', 1200, 1600, 'Ospite con capelli blu assaggia un crostino con mortadella e pistacchio'),
  crostiniMani: a('foto/crostini-mani.webp', 1200, 1600, 'Mani tatuate che prendono un crostino con cavolo viola, accanto a crostino con mortadella'),
  birraRustici: a('foto/birra-rustici.webp', 1200, 1600, 'Birra versata in un bicchiere accanto a due rustici dorati'),
  cocktailAmbrato: a('foto/cocktail-ambrato-finger.webp', 1200, 1600, 'Cocktail ambrato in tumbler cesellato con finger food sul bancone'),
  // Secondo invio (ZIP «fier_imagini_2»): locale ed eventi
  banconeClienti: a('foto/bancone-clienti-insegna.webp', 1440, 1069, "Clienti al bancone sotto l'insegna dorata FIREFLIES, bottigliera illuminata di blu"),
  tavoloInterni: a('foto/tavolo-tagliere-interni.webp', 1200, 1600, 'Ragazza al tavolo con tagliere e birra, parete a listelli di legno e parete verde'),
  insegnaFermati: a('foto/insegna-fermati.webp', 1200, 1600, "Due ragazzi al tavolo sotto la scritta «Fermati. La giornata può aspettare. Il tuo momento no.»"),
  tavolataFesta: a('foto/tavolata-festa.webp', 1066, 711, 'Tavolata di amici che festeggiano con le braccia alzate'),
  bartenderBraccia: a('foto/bartender-braccia-alzate.webp', 1066, 711, 'Bartender con la maglia FireFlies alza le braccia dietro il bancone in luce blu'),
  amiciMaglia: a('foto/amici-maglia-fireflies.webp', 1066, 711, 'Due amici in posa durante una serata, uno con la maglia FireFlies'),
  djParete: a('foto/dj-parete-lucciola.webp', 1065, 710, 'DJ alla consolle davanti alla parete verde con la lucciola dorata'),
  djCappello: a('foto/dj-cappello-consolle.webp', 1065, 710, 'DJ con cappellino alla consolle davanti alla lucciola dorata'),
  djNotte: a('foto/dj-notte-esterno.webp', 1440, 1069, 'DJ set di sera davanti al locale tra luci colorate e fumo'),
  microfono: a('foto/microfono-ingresso.webp', 1440, 1069, "Ragazzi al microfono all'ingresso durante un evento"),
  consolle: a('foto/consolle-dettaglio.webp', 1065, 710, 'Mano sulla consolle Pioneer DJ illuminata'),
  furgoneCompleanno: a('foto/furgone-compleanno-fireflies.webp', 1065, 710, 'Furgone d’epoca di sera con lo striscione «Buon compleanno FireFlies»'),
} satisfies Record<string, Asset>;

// I tre titolari (scontornati): fronte e retro con il motto sulla maglia.
export const owners = {
  gialloFronte: a('titolari/titolare-giallo-fronte.webp', 508, 1000, 'Titolare con maglia gialla FireFlies e occhiali da sole'),
  gialloRetro: a('titolari/titolare-giallo-retro.webp', 848, 1000, 'Retro della maglia gialla: «Il mio gruppo sanguigno è A-PERITIVO»'),
  arancioFronte: a('titolari/titolare-arancio-fronte.webp', 859, 1000, 'Titolare con maglia arancione FireFlies, braccia aperte e sorriso'),
  arancioRetro: a('titolari/titolare-arancio-retro.webp', 594, 1000, 'Retro della maglia arancione: «Dammi un buon mojito per restare»'),
  verdeFronte: a('titolari/titolare-verde-fronte.webp', 519, 1000, 'Titolare con maglia verde FireFlies che indica sorridendo'),
  verdeRetro: a('titolari/titolare-verde-retro.webp', 422, 1000, 'Retro della maglia verde con la lucciola e una frase scherzosa'),
} satisfies Record<string, Asset>;

export const cutouts = {
  caliceLamponi: a('nosfondo/calice-lamponi-menta.webp', 743, 1200, 'Calice con lamponi e menta'),
  tumblerLamponi: a('nosfondo/tumbler-lamponi.webp', 1200, 774, 'Drink con spiedino di lamponi e menta'),
  tagliere: a('nosfondo/tagliere-aperitivo.webp', 900, 987, "Tagliere dell'aperitivo"),
  cappuccino: a('nosfondo/cappuccino.webp', 860, 423, 'Cappuccino con piattino'),
  bartender: a('nosfondo/bartender-shaker.webp', 1200, 818, 'Bartender che versa dallo shaker'),
  longDrinkBlu: a('nosfondo/long-drink-blu.webp', 856, 917, 'Long drink azzurro con stuzzichini'),
  crostini: a('nosfondo/crostini-mani.webp', 683, 1200, 'Crostini presi con le mani'),
  birraRustici: a('nosfondo/birra-rustici.webp', 908, 1200, 'Birra versata con due rustici'),
  cocktailAmbrato: a('nosfondo/cocktail-ambrato.webp', 712, 750, 'Cocktail ambrato'),
} satisfies Record<string, Asset>;

export const logo = {
  onDark: a('logo/fireflies-logo-su-scuro-960.png', 960, 685, 'FireFlies — Breakfast and Aperitif'),
  onDarkSmall: a('logo/fireflies-logo-su-scuro-480.png', 480, 342, 'FireFlies — Breakfast and Aperitif'),
  onLight: a('logo/fireflies-logo-su-chiaro-960.png', 960, 697, 'FireFlies — Breakfast and Aperitif'),
  mark: a('logo/simbolo-trasparente-256.png', 223, 256, 'FireFlies'),
};

export type PhotoKey = keyof typeof photos;
export const photoKeys = Object.keys(photos) as PhotoKey[];
