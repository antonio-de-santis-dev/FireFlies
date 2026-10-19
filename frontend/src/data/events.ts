// Eventi DIMOSTRATIVI. «Domeniche del Fire» è un format citato nell'analisi Instagram
// (non verificata): nessuna data viene inventata. Le altre serate sono esempi marcati DEMO.

export type EventStatus = 'bozza' | 'pubblicato';

export type FireEvent = {
  id: string;
  title: string;
  kind: 'format' | 'serata';
  status: EventStatus;
  date: string | null; // ISO yyyy-mm-dd, null = da annunciare
  time: string | null;
  demo: boolean;
  artist: string;
  summary: string;
  body: string[];
  photo: string; // id nella libreria foto
  media: string[];
  seats: string;
  action: 'tavolo' | 'info';
};

export const events: FireEvent[] = [
  {
    id: 'domeniche-del-fire',
    title: 'Domeniche del Fire',
    kind: 'format',
    status: 'pubblicato',
    date: null,
    time: null,
    demo: false,
    artist: 'Line-up da annunciare di volta in volta',
    summary: 'Il format della domenica: aperitivo lungo, musica e la solita compagnia.',
    body: [
      "Le Domeniche del Fire sono il format ricorrente del locale. Il calendario reale non è ancora stato fornito: qui non mostriamo date finché il gestore non le conferma.",
      'Testo da validare: durata, orari, tipo di musica e se serve prenotare il tavolo.',
    ],
    photo: 'tavolataFesta',
    media: ['tavolataFesta', 'longDrinkBlu', 'amiciMaglia'],
    seats: 'Ingresso libero? Da confermare',
    action: 'info',
  },
  {
    id: 'dj-set-esempio',
    title: 'DJ set al Fire',
    kind: 'serata',
    status: 'pubblicato',
    date: '2026-10-31',
    time: '19:30',
    demo: true,
    artist: 'DJ da annunciare',
    summary: "Consolle all'ingresso, aperitivo dentro e fuori. Serata d'esempio per mostrare il layout.",
    body: [
      'Esempio di scheda serata: data, orario, artista, foto e azione per chiedere un tavolo.',
      "Le foto sono reali (DJ set davanti alla parete con la lucciola), la data e l'artista sono di esempio.",
    ],
    photo: 'djParete',
    media: ['djParete', 'djCappello', 'consolle'],
    seats: 'Tavoli su richiesta, conferma del locale',
    action: 'tavolo',
  },
  {
    id: 'aperitivo-vinili-esempio',
    title: 'Aperitivo e vinili',
    kind: 'serata',
    status: 'bozza',
    date: '2026-11-07',
    time: '18:30',
    demo: true,
    artist: 'Selezione musicale da definire',
    summary: 'Bozza non pubblicata: compare solo nell’area gestori.',
    body: ['Bozza di esempio per mostrare lo stato «bozza» nella gestione eventi.'],
    photo: 'cocktailRosso',
    media: ['cocktailRosso', 'cocktailAmbrato'],
    seats: 'Da definire',
    action: 'tavolo',
  },
];

const fmt = new Intl.DateTimeFormat('it-IT', { weekday: 'long', day: 'numeric', month: 'long' });

export const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export const formatEventDate = (e: Pick<FireEvent, 'date' | 'time'>) =>
  e.date ? `${capitalize(fmt.format(new Date(`${e.date}T12:00:00`)))}${e.time ? ` · ore ${e.time}` : ''}` : 'Date da annunciare';
