// Menu DIMOSTRATIVO. Nessun prezzo, ricetta o allergene è stato fornito dal locale:
// i nomi sono descrittivi di ciò che si vede nelle foto reali e vanno sostituiti con il menu ufficiale.

export type CategoryId = 'colazione' | 'aperitivi' | 'food' | 'cocktail' | 'analcolici' | 'birre';

export type Category = { id: CategoryId; label: string; hint: string };

export const categories: Category[] = [
  { id: 'colazione', label: 'Colazione', hint: 'Caffetteria e dolce del mattino' },
  { id: 'aperitivi', label: 'Aperitivi', hint: 'Taglieri e crostini da condividere' },
  { id: 'food', label: 'Food', hint: 'Salato da forno e qualcosa al volo' },
  { id: 'cocktail', label: 'Cocktail', hint: 'Dal bancone, con lo shaker' },
  { id: 'analcolici', label: 'Analcolici', hint: 'Per chi guida o preferisce così' },
  { id: 'birre', label: 'Birre', hint: 'Alla spina o in bottiglia' },
];

// I 14 allergeni del Reg. UE 1169/2011 (allegato II).
export const EU_ALLERGENS = [
  'Glutine', 'Crostacei', 'Uova', 'Pesce', 'Arachidi', 'Soia', 'Latte',
  'Frutta a guscio', 'Sedano', 'Senape', 'Sesamo', 'Solfiti', 'Lupini', 'Molluschi',
] as const;

export type MenuItem = {
  id: string;
  name: string;
  category: CategoryId;
  description: string;
  ingredients: string[]; // dedotti dalla foto, da confermare
  allergens: string[] | null; // null = non ancora dichiarati dal locale
  price: number | null; // prezzo REALE; null = non ancora comunicato dal locale
  demoPrice?: number; // prezzo DIMOSTRATIVO, mostrato sempre con l'etichetta «demo»
  photo: string | null; // id nella libreria foto
  visible: boolean;
  source: 'foto' | 'demo'; // 'foto' = voce suggerita da una foto reale; 'demo' = puro segnaposto
};

export const menuItems: MenuItem[] = [
  {
    id: 'cappuccino', demoPrice: 1.5, name: 'Cappuccino', category: 'colazione',
    description: 'Espresso e latte montato, servito al bancone. In foto la tazza Caffè Moreno.',
    ingredients: ['Caffè espresso', 'Latte'], allergens: null, price: null,
    photo: 'cappuccino', visible: true, source: 'foto',
  },
  {
    id: 'cornetto', demoPrice: 1.3, name: 'Cornetto', category: 'colazione',
    description: 'Il compagno del cappuccino. Farciture disponibili da confermare.',
    ingredients: [], allergens: null, price: null, photo: null, visible: true, source: 'demo',
  },
  {
    id: 'espresso', demoPrice: 1.1, name: 'Caffè espresso', category: 'colazione',
    description: 'Il rito del mattino, anche in versione macchiato o al ginseng se previsto.',
    ingredients: ['Caffè'], allergens: null, price: null, photo: null, visible: true, source: 'demo',
  },
  {
    id: 'tagliere', demoPrice: 14, name: 'Tagliere FireFlies', category: 'aperitivi',
    description: 'Panini mignon, focaccia, crudo, verdure e sfizi da condividere al tavolo.',
    ingredients: ['Panini mignon', 'Focaccia', 'Prosciutto crudo', 'Verdure', 'Formaggi'],
    allergens: null, price: null, photo: 'tagliere', visible: true, source: 'foto',
  },
  {
    id: 'crostino-mortadella', demoPrice: 3, name: 'Crostino mortadella e pistacchio', category: 'aperitivi',
    description: 'Pane ai cereali, crema morbida, mortadella e granella di pistacchio.',
    ingredients: ['Pane ai cereali', 'Crema di formaggio', 'Mortadella', 'Pistacchio'],
    allergens: null, price: null, photo: 'ospiteCrostino', visible: true, source: 'foto',
  },
  {
    id: 'crostino-cavolo', demoPrice: 3, name: 'Crostino al cavolo viola', category: 'aperitivi',
    description: 'Crema bianca, cavolo viola e una riduzione scura. Ricetta da confermare.',
    ingredients: ['Pane', 'Crema di formaggio', 'Cavolo viola'],
    allergens: null, price: null, photo: 'crostiniMani', visible: true, source: 'foto',
  },
  {
    id: 'rustico', demoPrice: 2, name: 'Rustico', category: 'food',
    description: 'Sfoglia dorata dal forno, da accompagnare a una birra fresca.',
    ingredients: ['Pasta sfoglia'], allergens: null, price: null,
    photo: 'birraRustici', visible: true, source: 'foto',
  },
  {
    id: 'panini-mignon', demoPrice: 6, name: 'Panini mignon', category: 'food',
    description: "Piccoli, farciti al momento: gli stessi che finiscono sul tagliere dell'aperitivo.",
    ingredients: ['Pane al latte', 'Salumi', 'Verdure'], allergens: null, price: null,
    photo: 'bartenderTagliere', visible: true, source: 'foto',
  },
  {
    id: 'calice-lamponi', demoPrice: 8, name: 'Calice lamponi e menta', category: 'cocktail',
    description: 'Drink chiaro e frizzante con spiedino di lamponi e menta fresca.',
    ingredients: ['Lamponi', 'Menta fresca', 'Ghiaccio'], allergens: null, price: null,
    photo: 'caliceLamponi', visible: true, source: 'foto',
  },
  {
    id: 'rosso-bitter', demoPrice: 8, name: 'Rosso al bitter', category: 'cocktail',
    description: 'Un classico rosso, amaro e profumato, servito in coppa. In foto bitter e vermouth.',
    ingredients: ['Bitter', 'Vermouth rosso', 'Scorza di arancia'], allergens: null, price: null,
    photo: 'cocktailRosso', visible: true, source: 'foto',
  },
  {
    id: 'long-blu', demoPrice: 8, name: 'Long drink blu', category: 'cocktail',
    description: 'Azzurro, alto, con rametto di rosmarino e bordo zuccherato.',
    ingredients: ['Rosmarino', 'Ghiaccio'], allergens: null, price: null,
    photo: 'longDrinkBlu', visible: true, source: 'foto',
  },
  {
    id: 'sour-ambrato', demoPrice: 9, name: 'Sour ambrato', category: 'cocktail',
    description: 'Ambrato, con schiuma morbida e decorazione croccante in superficie.',
    ingredients: ['Ghiaccio'], allergens: null, price: null,
    photo: 'cocktailAmbrato', visible: true, source: 'foto',
  },
  {
    id: 'analcolico-casa', demoPrice: 5, name: 'Analcolico della casa', category: 'analcolici',
    description: 'Fresco, colorato e senza alcol: la ricetta la decide il bartender.',
    ingredients: [], allergens: null, price: null, photo: null, visible: true, source: 'demo',
  },
  {
    id: 'spremuta', demoPrice: 3.5, name: 'Spremuta', category: 'analcolici',
    description: 'Disponibilità stagionale da confermare.',
    ingredients: ['Agrumi'], allergens: null, price: null, photo: null, visible: false, source: 'demo',
  },
  {
    id: 'birra', demoPrice: 4.5, name: 'Birra', category: 'birre',
    description: 'Versata al momento. Etichette e formati da confermare con il locale.',
    ingredients: ['Birra'], allergens: null, price: null,
    photo: 'birraRustici', visible: true, source: 'foto',
  },
];

const euro = (n: number) => `€ ${n.toFixed(2).replace('.', ',')}`;

export const formatPrice = (price: number | null) => (price == null ? 'Prezzo da definire' : euro(price));

/** Prezzo per il sito pubblico: reale se presente, altrimenti dimostrativo marcato come tale. */
export const publicPrice = (item: Pick<MenuItem, 'price' | 'demoPrice'>) =>
  item.price != null
    ? { text: euro(item.price), demo: false }
    : item.demoPrice != null
      ? { text: euro(item.demoPrice), demo: true }
      : { text: 'Prezzo da definire', demo: false };
