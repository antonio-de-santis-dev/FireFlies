// Contenuti personalizzabili dal TITOLARE (area «Personalizza il sito»), libreria foto e avvisi.
import { owners, photos, type Asset, type PhotoKey } from './assets';

export type MediaItem = {
  id: string;
  asset: Asset;
  inGallery: boolean;
  uploaded: boolean; // true = caricata nella sessione demo (solo in memoria)
};

// Ordine iniziale della galleria: alterna cibo, drink e persone.
const GALLERY_ORDER: PhotoKey[] = [
  'bartenderShaker', 'tagliere', 'banconeClienti', 'caliceLamponi', 'djNotte', 'ospiteCrostino', 'insegnaFermati',
  'cocktailRosso', 'tavolataFesta', 'cappuccino', 'djParete', 'longDrinkBlu', 'tavoloInterni', 'crostiniMani',
  'bartenderBraccia', 'servizioTagliere', 'furgoneCompleanno', 'cocktailAmbrato', 'amiciMaglia', 'birraRustici',
  'microfono', 'tumblerLamponi', 'djCappello', 'bartenderTagliere', 'consolle', 'djSet',
];

export const seedMedia: MediaItem[] = [
  ...GALLERY_ORDER.map((id) => ({ id, asset: photos[id], inGallery: true, uploaded: false })),
  // scontornati dei titolari: in libreria (sostituibili da «Personalizza il sito»), non in galleria
  ...(Object.keys(owners) as (keyof typeof owners)[]).map((k) => ({ id: `titolare-${k}`, asset: owners[k], inGallery: false, uploaded: false })),
];

export type HomeSection = 'momento' | 'trio' | 'persone' | 'eventi' | 'striscia' | 'visita';

export const homeSectionLabels: Record<HomeSection, string> = {
  momento: '«Che momento è al Fire?»',
  trio: 'Breakfast, Aperitif, Fire Nights',
  persone: 'Dietro il bancone (staff)',
  eventi: 'Anteprima serate',
  striscia: 'Striscia di foto',
  visita: 'Orari e posizione',
};

export type StaffMember = { id: string; name: string; role: string; photo: string };

// Titolari: foto ufficiali (scontornate, fronte/retro) + testi che il titolare compila in «Personalizza il sito».
export type Owner = {
  id: string;
  name: string;
  role: string;
  description: string;
  motto: string; // frase sulla maglia, mostrata come citazione (vuota = non mostrata)
  front: string; // id nella libreria foto
  back: string;
  color: 'giallo' | 'arancio' | 'verde';
};

export type SiteContent = {
  heroClaim: string;
  heroLead: string;
  heroPhoto: string; // id nella libreria foto
  sections: Record<HomeSection, boolean>;
  storyTitle: string;
  storyText: string;
  storyConfirmed: boolean;
  staff: StaffMember[];
  staffConfirmed: boolean;
  owners: Owner[];
  ownersConfirmed: boolean;
  /** Testata della pagina Menu: foto (id libreria) in ordine e intervallo di rotazione. */
  menuHero: { photos: string[]; intervalSec: number };
};

export const defaultContent: SiteContent = {
  heroClaim: "Dal primo caffè all'ultimo brindisi.",
  heroLead: 'Colazioni al bancone, aperitivi abbondanti, cocktail e serate con DJ set. A Martano, nel cuore del Salento.',
  heroPhoto: 'bartenderShaker',
  sections: { momento: true, trio: true, persone: true, eventi: true, striscia: true, visita: true },
  storyTitle: 'Perché «FireFlies»',
  storyText:
    'Qui va il racconto vero: chi ha aperto il locale, quando, perché la lucciola. Non inventiamo date né nomi dei proprietari: questo spazio è pensato per 3–4 frasi in prima persona, con il tono ironico dei social.',
  storyConfirmed: false,
  staff: [
    { id: 's1', name: 'Nome da inserire', role: 'Al bancone dei cocktail', photo: 'bartenderShaker' },
    { id: 's2', name: 'Nome da inserire', role: 'Taglieri e aperitivo', photo: 'bartenderTagliere' },
    { id: 's3', name: 'Nome da inserire', role: 'Le serate al bancone', photo: 'bartenderBraccia' },
  ],
  staffConfirmed: false,
  owners: [
    {
      id: 'o1', color: 'giallo', name: 'Nome da inserire', role: 'Titolare',
      description: 'Due o tre righe su chi è, cosa fa al Fire e cosa lo fa ridere. Da scrivere con lui: non inventiamo biografie.',
      motto: 'Il mio gruppo sanguigno è A\u2011PERITIVO.', front: 'titolare-gialloFronte', back: 'titolare-gialloRetro',
    },
    {
      id: 'o2', color: 'arancio', name: 'Nome da inserire', role: 'Titolare',
      description: 'Il suo posto preferito nel locale, il drink che consiglia, il momento della giornata in cui lo trovi.',
      motto: 'Dammi un buon mojito per restare.', front: 'titolare-arancioFronte', back: 'titolare-arancioRetro',
    },
    {
      id: 'o3', color: 'verde', name: 'Nome da inserire', role: 'Titolare',
      description: 'Come è nata l’idea di FireFlies e cosa non deve mai mancare al bancone.',
      // Il motto sulla maglia verde parla di reggere l'alcol: non lo riportiamo come testo (regola sul consumo
      // responsabile). Resta visibile nella foto del retro; la scelta finale spetta ai titolari.
      motto: '', front: 'titolare-verdeFronte', back: 'titolare-verdeRetro',
    },
  ],
  ownersConfirmed: false,
  menuHero: { photos: ['caliceLamponi', 'tagliere', 'cocktailRosso', 'tavoloInterni', 'cappuccino'], intervalSec: 60 },
};

export type Announcement = {
  id: string;
  text: string;
  tone: 'info' | 'importante';
  active: boolean;
  until: string | null; // ISO yyyy-mm-dd: dopo questa data non viene più mostrato
};

export const seedAnnouncements: Announcement[] = [
  {
    id: 'a1',
    text: "Avviso d'esempio del titolare: chiusure, serate e novità.",
    tone: 'info',
    active: true,
    until: null,
  },
  {
    id: 'a2',
    text: 'Esempio: chiusi per ferie dal 3 al 10 novembre.',
    tone: 'importante',
    active: false,
    until: '2026-11-10',
  },
];

export const ALLOWED_UPLOAD = ['image/jpeg', 'image/png', 'image/webp'];
export const MAX_UPLOAD_MB = 5;
