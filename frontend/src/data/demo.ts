import { events as seedEvents } from './events';
// Dati DIMOSTRATIVI per la sola build statica (`npm run build:static`, pagina apribile senza backend).
// Nella build normale questo modulo non viene incluso: il sito legge tutto da /api/v1/public/*.
import type { PublicData } from '../api/content';
import { photos } from './assets';
import { defaultContent, seedAnnouncements, seedMedia } from './content';
import { categories, EU_ALLERGENS, menuItems } from './menu';
import { defaultSettings } from './site';

export function demoData(): PublicData {
  const mediaIndex = Object.fromEntries(seedMedia.map((m) => [m.id, m.asset]));
  return {
    settings: defaultSettings,
    content: defaultContent,
    announcements: seedAnnouncements,
    categories,
    menu: menuItems,
    allergens: EU_ALLERGENS.map((name) => ({ code: name, name })),
    gallery: seedMedia.filter((m) => m.inGallery),
    mediaIndex: { ...photos, ...mediaIndex },
    demoContent: true,
    events: seedEvents,
  };
}
