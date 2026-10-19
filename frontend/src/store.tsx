// Stato del prototipo: solo in memoria (nessun localStorage, nessun invio di rete).
// Le modifiche fatte nell'area gestori si vedono sul sito pubblico fino al ricaricamento.
import { createContext, useCallback, useContext, useMemo, useReducer, useRef, useState, type ReactNode } from 'react';
import { photos, type Asset } from './data/assets';
import { defaultContent, seedAnnouncements, seedMedia, type Announcement, type MediaItem, type SiteContent } from './data/content';
import { events as seedEvents, type FireEvent } from './data/events';
import { menuItems as seedMenu, type MenuItem } from './data/menu';
import { demoRequests, type BookingRequest, type RequestStatus } from './data/requests';
import { defaultSettings, type SiteSettings } from './data/site';

export type Role = 'titolare' | 'staff';

type State = {
  menu: MenuItem[];
  events: FireEvent[];
  requests: BookingRequest[];
  settings: SiteSettings;
  content: SiteContent;
  media: MediaItem[];
  announcements: Announcement[];
  role: Role | null; // null = non autenticato (demo)
  mapConsent: boolean; // il visitatore ha scelto di caricare la mappa in questa sessione
};

type Action =
  | { type: 'menu/save'; item: MenuItem }
  | { type: 'menu/toggle'; id: string }
  | { type: 'event/save'; event: FireEvent }
  | { type: 'request/status'; id: string; status: RequestStatus; notice?: BookingRequest['notice'] }
  | { type: 'settings/save'; settings: SiteSettings }
  | { type: 'content/save'; content: SiteContent }
  | { type: 'media/add'; item: MediaItem }
  | { type: 'media/update'; item: MediaItem }
  | { type: 'media/remove'; id: string }
  | { type: 'media/move'; id: string; dir: -1 | 1 }
  | { type: 'announcement/save'; item: Announcement }
  | { type: 'announcement/remove'; id: string }
  | { type: 'session'; role: Role | null }
  | { type: 'map/consent' };

const upsert = <T extends { id: string }>(list: T[], item: T) =>
  list.some((x) => x.id === item.id) ? list.map((x) => (x.id === item.id ? item : x)) : [...list, item];

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'menu/save':
      return { ...state, menu: upsert(state.menu, action.item) };
    case 'menu/toggle':
      return { ...state, menu: state.menu.map((m) => (m.id === action.id ? { ...m, visible: !m.visible } : m)) };
    case 'event/save':
      return { ...state, events: upsert(state.events, action.event) };
    case 'request/status':
      return { ...state, requests: state.requests.map((r) => (r.id === action.id ? { ...r, status: action.status, notice: action.notice ?? r.notice } : r)) };
    case 'settings/save':
      return { ...state, settings: action.settings };
    case 'content/save':
      return { ...state, content: action.content };
    case 'media/add':
      return { ...state, media: [action.item, ...state.media] };
    case 'media/update':
      return { ...state, media: upsert(state.media, action.item) };
    case 'media/remove':
      return { ...state, media: state.media.filter((m) => m.id !== action.id) };
    case 'media/move': {
      const i = state.media.findIndex((m) => m.id === action.id);
      const j = i + action.dir;
      if (i < 0 || j < 0 || j >= state.media.length) return state;
      const media = [...state.media];
      [media[i], media[j]] = [media[j], media[i]];
      return { ...state, media };
    }
    case 'announcement/save':
      return { ...state, announcements: upsert(state.announcements, action.item) };
    case 'announcement/remove':
      return { ...state, announcements: state.announcements.filter((a) => a.id !== action.id) };
    case 'session':
      return { ...state, role: action.role };
    case 'map/consent':
      return { ...state, mapConsent: true };
  }
}

type Store = State & {
  dispatch: (a: Action) => void;
  /** Foto dalla libreria; null se l'id non esiste (es. foto rimossa). */
  mediaAsset: (id: string | null | undefined) => Asset | null;
  /** Come mediaAsset, ma con una foto reale di riserva per gli spazi che non possono restare vuoti. */
  mediaOr: (id: string | null | undefined) => Asset;
  /** Dove è usata una foto (per impedire la rimozione di foto in uso). */
  mediaUsage: (id: string) => string[];
  toast: string | null;
  notify: (msg: string) => void;
  booking: { open: boolean; eventId?: string };
  openBooking: (eventId?: string) => void;
  closeBooking: () => void;
};

const Ctx = createContext<Store | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, {
    menu: seedMenu,
    events: seedEvents,
    requests: demoRequests,
    settings: defaultSettings,
    content: defaultContent,
    media: seedMedia,
    announcements: seedAnnouncements,
    role: null,
    mapConsent: false,
  });
  const [toast, setToast] = useState<string | null>(null);
  const timer = useRef<number>();
  const notify = useCallback((msg: string) => {
    setToast(msg);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setToast(null), 4200);
  }, []);
  const [booking, setBooking] = useState<{ open: boolean; eventId?: string }>({ open: false });
  const openBooking = useCallback((eventId?: string) => setBooking({ open: true, eventId }), []);
  const closeBooking = useCallback(() => setBooking({ open: false }), []);

  const value = useMemo(() => {
    const byId = new Map(state.media.map((m) => [m.id, m.asset]));
    const mediaAsset = (id: string | null | undefined) => (id ? byId.get(id) ?? null : null);
    const mediaUsage = (id: string) => [
      ...state.menu.filter((m) => m.photo === id).map((m) => `menu «${m.name}»`),
      ...state.events.filter((e) => e.photo === id || e.media.includes(id)).map((e) => `evento «${e.title}»`),
      ...(state.content.heroPhoto === id ? ['foto di apertura della home'] : []),
      ...state.content.staff.filter((s) => s.photo === id).map((s) => `staff «${s.role}»`),
      ...state.content.owners.filter((o) => o.front === id || o.back === id).map((o) => `titolare «${o.name}»`),
      ...(state.content.menuHero.photos.includes(id) ? ['testata del menu'] : []),
    ];
    return {
      ...state,
      dispatch,
      mediaAsset,
      mediaOr: (id: string | null | undefined) => mediaAsset(id) ?? photos.bartenderShaker,
      mediaUsage,
      toast,
      notify,
      booking,
      openBooking,
      closeBooking,
    };
  }, [state, toast, notify, booking, openBooking, closeBooking]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useStore() {
  const s = useContext(Ctx);
  if (!s) throw new Error('useStore fuori da StoreProvider');
  return s;
}
