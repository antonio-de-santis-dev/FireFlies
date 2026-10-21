import { request, test, type APIRequestContext } from '@playwright/test';
import { credentials } from './helpers';

// Dalla FASE 4 le modifiche fatte nei test restano nel database (come per i gestori veri).
// Ogni test che modifica menu, foto, testi, orari o avvisi riporta i dati com'erano prima di iniziare,
// così l'ordine dei test non conta e i controlli visivi vedono sempre gli stessi contenuti dimostrativi.

const BACKEND = () => process.env.FIREFLIES_BACKEND_URL ?? 'http://localhost:18080';

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

/** Sessione API del titolare (login reale con le credenziali generate da scripts/e2e.mjs). */
export async function ownerApi(): Promise<{ call: (method: string, path: string, data?: unknown) => Promise<Json>; dispose: () => Promise<void> }> {
  const ctx: APIRequestContext = await request.newContext({ baseURL: BACKEND() });
  const token = async () => (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
  await ctx.get('/api/v1/auth/csrf');
  const { email, password } = credentials('Titolare');
  const login = await ctx.post('/api/v1/auth/login', { data: { email, password }, headers: { 'X-XSRF-TOKEN': decodeURIComponent(await token()) } });
  if (!login.ok()) throw new Error(`login titolare per il ripristino: ${login.status()}`);
  await ctx.get('/api/v1/auth/csrf');
  const call = async (method: string, path: string, data?: unknown) => {
    const res = await ctx.fetch(`/api/v1${path}`, { method, data, headers: { 'X-XSRF-TOKEN': decodeURIComponent(await token()) } });
    if (!res.ok()) throw new Error(`${method} ${path} → ${res.status()} ${await res.text()}`);
    return res.status() === 204 ? {} : res.json();
  };
  return { call, dispose: () => ctx.dispose() };
}

type Snapshot = { menu: Json; media: Json[]; content: Json; settings: Json; announcements: Json[] };

async function take(): Promise<Snapshot> {
  const api = await ownerApi();
  try {
    const [menu, media, content, settings, announcements] = await Promise.all([
      api.call('GET', '/admin/menu'), api.call('GET', '/admin/media'), api.call('GET', '/admin/site-content'),
      api.call('GET', '/admin/site-settings'), api.call('GET', '/admin/announcements'),
    ]);
    return { menu, media: media as unknown as Json[], content, settings, announcements: announcements as unknown as Json[] };
  } finally {
    await api.dispose();
  }
}

const itemBody = (i: Json) => ({
  categoryId: i.categoryId, name: i.name, description: i.description, ingredients: i.ingredients, priceCents: i.priceCents,
  allergens: { declared: i.allergens.declared, codes: i.allergens.codes }, photoMediaId: i.photo?.id ?? null, visible: i.visible,
});

const contentBody = (c: Json) => ({
  heroClaim: c.heroClaim, heroLead: c.heroLead, heroMediaId: c.heroPhoto?.id ?? null, sections: c.sections,
  storyTitle: c.storyTitle, storyText: c.storyText, storyConfirmed: c.storyConfirmed,
  owners: c.owners.map((o: Json) => ({ id: o.id, name: o.name, roleLabel: o.roleLabel, description: o.description, motto: o.motto,
    color: o.color, frontMediaId: o.front?.id ?? null, backMediaId: o.back?.id ?? null, visible: o.visible })),
  ownersConfirmed: c.ownersConfirmed,
  staff: c.staff.map((s: Json) => ({ id: s.id, name: s.name, roleLabel: s.roleLabel, mediaId: s.photo?.id ?? null, visible: s.visible })),
  staffConfirmed: c.staffConfirmed,
  menuHeroMediaIds: c.menuHero.photos.map((p: Json) => p.id),
  menuHeroIntervalSec: c.menuHero.intervalSec,
});

export const settingsBody = (s: Json) => ({
  addressLine: s.addressLine, addressConfirmed: s.addressConfirmed, mapQuery: s.mapQuery, mapMode: s.mapMode, phone: s.phone,
  email: s.email, instagramUrl: s.instagramUrl, hoursConfirmed: s.hoursConfirmed,
  hours: s.hours.map((h: Json) => (h.closed ? { isoDay: h.isoDay, closed: true }
    : { isoDay: h.isoDay, closed: false, opensAt: h.opensAt.slice(0, 5), closesAt: h.closesAt.slice(0, 5) })),
});

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

async function restore(snap: Snapshot) {
  const api = await ownerApi();
  try {
    // 1. menu: voci tornate come prima, voci nuove eliminate (liberano anche le foto nuove)
    const menu = await api.call('GET', '/admin/menu');
    const before = new Map(snap.menu.items.map((i: Json) => [i.id, i]));
    for (const current of menu.items as Json[]) {
      const old = before.get(current.id) as Json | undefined;
      if (!old) await api.call('DELETE', `/admin/menu/items/${current.id}`);
      else if (!same(itemBody(old), itemBody(current))) await api.call('PUT', `/admin/menu/items/${current.id}`, { ...itemBody(old), version: current.version });
    }
    // 2. testi e impostazioni del titolare
    const content = await api.call('GET', '/admin/site-content');
    if (!same(contentBody(snap.content), contentBody(content))) {
      await api.call('PUT', '/admin/site-content', { ...contentBody(snap.content), version: content.version });
    }
    const settings = await api.call('GET', '/admin/site-settings');
    if (!same(settingsBody(snap.settings), settingsBody(settings))) {
      // FIX 2: ripristino degli orari di partenza anche se un test ha lasciato richieste nel giorno da richiudere
      await api.call('PUT', '/admin/site-settings', { ...settingsBody(snap.settings), version: settings.version, confirmClosures: true });
    }
    // 3. avvisi
    const announcements = (await api.call('GET', '/admin/announcements')) as unknown as Json[];
    const oldAnnouncements = new Map(snap.announcements.map((a) => [a.id, a]));
    for (const a of announcements) {
      const old = oldAnnouncements.get(a.id);
      if (!old) await api.call('DELETE', `/admin/announcements/${a.id}`);
      else if (old.active !== a.active || old.text !== a.text || old.tone !== a.tone) {
        await api.call('PUT', `/admin/announcements/${a.id}`, { text: old.text, tone: old.tone, active: old.active, visibleUntil: old.visibleUntil });
      }
    }
    // 4. foto: le nuove (ormai non più usate) eliminate, descrizioni, galleria e ordine come prima
    const media = (await api.call('GET', '/admin/media')) as unknown as Json[];
    const oldMedia = new Map(snap.media.map((m) => [m.id, m]));
    for (const m of media) {
      const old = oldMedia.get(m.id);
      if (!old) await api.call('DELETE', `/admin/media/${m.id}`);
      else if (old.alt !== m.alt || old.inGallery !== m.inGallery) await api.call('PATCH', `/admin/media/${m.id}`, { altText: old.alt, inGallery: old.inGallery });
    }
    if (!same(media.filter((m) => oldMedia.has(m.id)).map((m) => m.id), snap.media.map((m) => m.id))) {
      await api.call('PUT', '/admin/media/order', { mediaIds: snap.media.map((m) => m.id) });
    }
  } finally {
    await api.dispose();
  }
}

/** Da chiamare dentro un `test.describe` che modifica i dati: fotografa prima di ogni test e ripristina dopo. */
export function keepBaseline() {
  let snap: Snapshot | null = null;
  test.beforeEach(async () => {
    snap = await take();
  });
  test.afterEach(async () => {
    if (snap) await restore(snap);
  });
}

/** URL di una foto della libreria (variante principale) dal suo testo alternativo. */
export async function photoSrc(alt: RegExp): Promise<string> {
  const api = await ownerApi();
  try {
    const media = (await api.call('GET', '/admin/media')) as unknown as Json[];
    const found = media.find((m) => alt.test(m.alt));
    if (!found) throw new Error(`Foto non trovata: ${alt}`);
    return found.src;
  } finally {
    await api.dispose();
  }
}
