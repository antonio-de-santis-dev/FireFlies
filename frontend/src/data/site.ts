// Dati di sito. Tutto ciò che non è verificato è marcato `confirmed: false`
// e mostrato nell'interfaccia con l'etichetta «Da confermare».
import { cutouts, type Asset } from './assets';

export type DayHours = { day: string; short: string; open: string | null; close: string | null };

export type SiteSettings = {
  name: string;
  city: string;
  addressLine: string;
  addressConfirmed: boolean;
  phone: string | null;
  email: string | null;
  instagram: string;
  instagramHandle: string;
  mapsUrl: string;
  hours: DayHours[];
  hoursConfirmed: boolean;
  bookingEnabled: boolean;
  mapQuery: string; // testo cercato su Google Maps (meglio l'indirizzo esatto quando confermato)
  mapMode: 'consenso' | 'automatico'; // consenso = la mappa si carica solo dopo un clic del visitatore
  version?: number; // versione sul backend (concorrenza ottimistica)
};

export const defaultSettings: SiteSettings = {
  name: 'FireFlies Aperitif',
  city: 'Martano (LE)',
  addressLine: 'Via Giovanni XXIII, 19 — 73025 Martano (LE)',
  addressConfirmed: true, // indirizzo confermato dal cliente il 19/10/2026
  phone: null,
  email: null,
  instagram: 'https://www.instagram.com/fireflies.aperitif/',
  instagramHandle: '@fireflies.aperitif',
  // Ricerca generica: nessun indirizzo inventato, nessuna mappa incorporata (niente cookie di terze parti).
  mapsUrl: 'https://www.google.com/maps/search/?api=1&query=FireFlies+Aperitif%2C+Via+Giovanni+XXIII%2C+19%2C+73025+Martano+LE',
  // Orari indicativi da analisi Instagram esterna: NON verificati.
  hours: [
    { day: 'Lunedì', short: 'Lun', open: '07:00', close: '23:00' },
    { day: 'Martedì', short: 'Mar', open: '07:00', close: '23:00' },
    { day: 'Mercoledì', short: 'Mer', open: null, close: null },
    { day: 'Giovedì', short: 'Gio', open: '07:00', close: '23:00' },
    { day: 'Venerdì', short: 'Ven', open: '07:00', close: '23:00' },
    { day: 'Sabato', short: 'Sab', open: '07:00', close: '23:00' },
    { day: 'Domenica', short: 'Dom', open: '07:00', close: '23:00' },
  ],
  hoursConfirmed: false,
  bookingEnabled: true,
  mapQuery: 'FireFlies Aperitif, Via Giovanni XXIII, 19, 73025 Martano LE',
  mapMode: 'consenso',
};

// «Che momento è al Fire?» — fasce della giornata (ipotesi da validare con il gestore).
export type Moment = {
  id: 'colazione' | 'pausa' | 'aperitivo' | 'sera';
  from: number;
  to: number;
  title: string;
  line: string;
  tone: 'day' | 'dusk' | 'night';
  cutout: Asset;
  menuCategory: string;
};

export const moments: Moment[] = [
  {
    id: 'colazione', from: 7, to: 11, tone: 'day',
    title: 'Colazione al bancone',
    line: 'Cappuccino, cornetto e due chiacchiere prima di partire.',
    cutout: cutouts.cappuccino, menuCategory: 'colazione',
  },
  {
    id: 'pausa', from: 11, to: 17, tone: 'day',
    title: 'Pausa caffè e qualcosa al volo',
    line: 'Un caffè, un rustico, un analcolico fresco. Offerta da definire col locale.',
    cutout: cutouts.birraRustici, menuCategory: 'food',
  },
  {
    id: 'aperitivo', from: 17, to: 21, tone: 'dusk',
    title: "L'ora dell'aperitivo",
    line: 'Taglieri abbondanti, crostini e il primo giro di drink.',
    cutout: cutouts.tagliere, menuCategory: 'aperitivi',
  },
  {
    id: 'sera', from: 21, to: 23, tone: 'night',
    title: 'Le lucciole si accendono',
    line: 'Cocktail, musica e, quando in programma, DJ set.',
    cutout: cutouts.caliceLamponi, menuCategory: 'cocktail',
  },
];

export const momentAt = (hour: number) =>
  moments.find((m) => hour >= m.from && hour < m.to) ?? moments[moments.length - 1];
