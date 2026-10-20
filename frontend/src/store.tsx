// Stato dell'app. Accesso dei gestori reale (FASE 3); menu, foto, testi, orari e avvisi dal backend (FASE 4).
// Eventi e richieste tavolo restano dimostrativi in memoria fino alle FASI 5–6.
import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react';
import { onUnauthorized } from './api/client';
import { loadPublic, type PublicData } from './api/content';
import { photos, type Asset } from './data/assets';
import type { Announcement, MediaItem, SiteContent } from './data/content';
import { events as seedEvents, type FireEvent } from './data/events';
import type { Category, MenuItem } from './data/menu';
import { demoRequests, type BookingRequest, type RequestStatus } from './data/requests';
import { defaultSettings, type SiteSettings } from './data/site';
import { navigate } from './router';

export type Role = 'titolare' | 'staff';

type State = {
  contentStatus: 'loading' | 'ready' | 'error';
  demoContent: boolean; // il backend segnala dati dimostrativi (solo sviluppo locale)
  menu: MenuItem[];
  categories: Category[];
  allergens: { code: string; name: string }[];
  events: FireEvent[];
  requests: BookingRequest[];
  settings: SiteSettings;
  content: SiteContent;
  media: MediaItem[]; // sito pubblico: le foto della galleria; area gestori: l'intera libreria
  mediaIndex: Record<string, Asset>; // tutte le foto note per id (menu, titolari, staff, testata, libreria)
  announcements: Announcement[];
  role: Role | null; // null = non autenticato; il ruolo arriva dal backend, mai da una scelta nel login
  user: { displayName: string; email: string } | null;
  authChecked: boolean; // la sessione è stata verificata con il backend almeno una volta
  authNotice: string | null; // messaggio per la pagina di accesso (es. sessione scaduta)
  mapConsent: boolean; // il visitatore ha scelto di caricare la mappa in questa sessione
};

type Action =
  | { type: 'public/loaded'; data: PublicData }
  | { type: 'public/error' }
  | { type: 'admin/menu'; categories: Category[]; items: MenuItem[]; allergens: { code: string; name: string }[]; mediaIndex: Record<string, Asset> }
  | { type: 'media/set'; items: MediaItem[] }
  | { type: 'media/index'; mediaIndex: Record<string, Asset> }
  | { type: 'menu/save'; item: MenuItem }
  | { type: 'menu/remove'; id: string }
  | { type: 'event/save'; event: FireEvent }
  | { type: 'request/status'; id: string; status: RequestStatus; notice?: BookingRequest['notice'] }
  | { type: 'settings/save'; settings: SiteSettings }
  | { type: 'content/save'; content: SiteContent; mediaIndex?: Record<string, Asset> }
  | { type: 'media/add'; item: MediaItem }
  | { type: 'media/update'; item: MediaItem }
  // solo i campi cambiati da una singola richiesta: due modifiche ravvicinate non si annullano a vicenda
  | { type: 'media/merge'; id: string; alt?: string; inGallery?: boolean }
  | { type: 'media/remove'; id: string }
  | { type: 'announcements/set'; items: Announcement[] }
  | { type: 'announcement/save'; item: Announcement }
  | { type: 'announcement/remove'; id: string }
  | { type: 'session'; role: Role | null; user?: { displayName: string; email: string } | null; notice?: string | null }
  | { type: 'map/consent' };

const upsert = <T extends { id: string }>(list: T[], item: T) =>
  list.some((x) => x.id === item.id) ? list.map((x) => (x.id === item.id ? item : x)) : [...list, item];

const withAsset = (index: Record<string, Asset>, item: MediaItem) => ({ ...index, [item.id]: item.asset });

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'public/loaded': {
      const d = action.data;
      return {
        ...state, contentStatus: 'ready', demoContent: d.demoContent, settings: d.settings, content: d.content,
        announcements: d.announcements, categories: d.categories, menu: d.menu, allergens: d.allergens, media: d.gallery,
        mediaIndex: { ...state.mediaIndex, ...d.mediaIndex },
      };
    }
    case 'public/error':
      return { ...state, contentStatus: 'error' };
    case 'admin/menu':
      return { ...state, categories: action.categories, menu: action.items, allergens: action.allergens, mediaIndex: { ...state.mediaIndex, ...action.mediaIndex } };
    case 'media/set':
      return { ...state, media: action.items, mediaIndex: action.items.reduce(withAsset, state.mediaIndex) };
    case 'media/index':
      return { ...state, mediaIndex: { ...state.mediaIndex, ...action.mediaIndex } };
    case 'menu/save':
      return { ...state, menu: upsert(state.menu, action.item) };
    case 'menu/remove':
      return { ...state, menu: state.menu.filter((m) => m.id !== action.id) };
    case 'event/save':
      return { ...state, events: upsert(state.events, action.event) };
    case 'request/status':
      return { ...state, requests: state.requests.map((r) => (r.id === action.id ? { ...r, status: action.status, notice: action.notice ?? r.notice } : r)) };
    case 'settings/save':
      return { ...state, settings: action.settings };
    case 'content/save':
      return { ...state, content: action.content, mediaIndex: { ...state.mediaIndex, ...action.mediaIndex } };
    case 'media/add':
      return { ...state, media: [action.item, ...state.media], mediaIndex: withAsset(state.mediaIndex, action.item) };
    case 'media/update':
      return { ...state, media: upsert(state.media, action.item), mediaIndex: withAsset(state.mediaIndex, action.item) };
    case 'media/merge': {
      const merge = (m: MediaItem): MediaItem => ({
        ...m,
        asset: action.alt === undefined ? m.asset : { ...m.asset, alt: action.alt },
        inGallery: action.inGallery ?? m.inGallery,
      });
      const media = state.media.map((m) => (m.id === action.id ? merge(m) : m));
      const updated = media.find((m) => m.id === action.id);
      return { ...state, media, mediaIndex: updated ? withAsset(state.mediaIndex, updated) : state.mediaIndex };
    }
    case 'media/remove':
      return { ...state, media: state.media.filter((m) => m.id !== action.id) };
    case 'announcements/set':
      return { ...state, announcements: action.items };
    case 'announcement/save':
      return { ...state, announcements: upsert(state.announcements, action.item) };
    case 'announcement/remove':
      return { ...state, announcements: state.announcements.filter((a) => a.id !== action.id) };
    case 'session':
      return { ...state, role: action.role, user: action.user ?? null, authChecked: true, authNotice: action.notice ?? null };
    case 'map/consent':
      return { ...state, mapConsent: true };
  }
}

type Store = State & {
  dispatch: (a: Action) => void;
  /** Riprova il caricamento dei contenuti pubblici (dopo un errore di rete). */
  reloadPublic: () => void;
  /** Foto per id: libreria del backend, oppure foto del mockup (eventi dimostrativi); null se non esiste. */
  mediaAsset: (id: string | null | undefined) => Asset | null;
  /** Come mediaAsset, ma con una foto reale di riserva per gli spazi che non possono restare vuoti. */
  mediaOr: (id: string | null | undefined) => Asset;
  /** Dove è usata una foto (dal backend; il backend lo verifica comunque prima di eliminarla). */
  mediaUsage: (id: string) => string[];
  toast: string | null;
  notify: (msg: string) => void;
  booking: { open: boolean; eventId?: string };
  openBooking: (eventId?: string) => void;
  closeBooking: () => void;
};

const Ctx = createContext<Store | null>(null);

const emptyContent: SiteContent = {
  heroClaim: '', heroLead: '', heroPhoto: '', storyTitle: '', storyText: '', storyConfirmed: false,
  sections: { momento: true, trio: true, persone: true, eventi: true, striscia: true, visita: true },
  staff: [], staffConfirmed: false, owners: [], ownersConfirmed: false, menuHero: { photos: [], intervalSec: 60 },
};

const bundled = photos as Record<string, Asset>;

export function StoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, {
    contentStatus: 'loading',
    demoContent: false,
    menu: [],
    categories: [],
    allergens: [],
    events: seedEvents,
    requests: demoRequests,
    settings: { ...defaultSettings, hours: defaultSettings.hours.map((h) => ({ ...h, open: null, close: null })) },
    content: emptyContent,
    media: [],
    mediaIndex: {},
    announcements: [],
    role: null,
    user: null,
    authChecked: false,
    authNotice: null,
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

  const reloadPublic = useCallback(() => {
    // build statica: solo i dati del mockup, nessuna chiamata (il modulo non entra nella build normale)
    if (import.meta.env.MODE === 'static') {
      import('./data/demo').then(({ demoData }) => dispatch({ type: 'public/loaded', data: demoData() }));
      return;
    }
    loadPublic(defaultSettings)
      .then((data) => dispatch({ type: 'public/loaded', data }))
      .catch(() => dispatch({ type: 'public/error' }));
  }, []);
  useEffect(reloadPublic, [reloadPublic]);

  // Sessione scaduta mentre la scheda era aperta: la prima API dei gestori che risponde 401 riporta all'accesso.
  useEffect(() => {
    onUnauthorized(() => {
      dispatch({ type: 'session', role: null, notice: 'La sessione è scaduta o è stata chiusa: accedi di nuovo. Le modifiche non salvate sono andate perse.' });
      navigate('/gestione/login');
    });
    return () => onUnauthorized(null);
  }, []);

  const value = useMemo(() => {
    const byLibrary = new Map(state.media.map((m) => [m.id, m]));
    const mediaAsset = (id: string | null | undefined) => (id ? state.mediaIndex[id] ?? byLibrary.get(id)?.asset ?? bundled[id] ?? null : null);
    const mediaUsage = (id: string) => [
      ...(byLibrary.get(id)?.usages ?? []),
      ...state.events.filter((e) => e.photo === id || e.media.includes(id)).map((e) => `evento «${e.title}»`),
    ];
    return {
      ...state,
      dispatch,
      reloadPublic,
      mediaAsset,
      mediaOr: (id: string | null | undefined) => mediaAsset(id) ?? photos.bartenderShaker,
      mediaUsage,
      toast,
      notify,
      booking,
      openBooking,
      closeBooking,
    };
  }, [state, reloadPublic, toast, notify, booking, openBooking, closeBooking]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useStore() {
  const s = useContext(Ctx);
  if (!s) throw new Error('useStore fuori da StoreProvider');
  return s;
}
