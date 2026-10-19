// Contenuti del sito dal backend (FASE 4): sito pubblico, menu, foto, testi del titolare, orari, avvisi.
// Le risposte vengono tradotte nei tipi già usati dalle pagine del mockup approvato, così il layout non cambia.
import type { Asset } from '../data/assets';
import type { Announcement, HomeSection, MediaItem, Owner, SiteContent, StaffMember } from '../data/content';
import type { Category, MenuItem } from '../data/menu';
import type { DayHours, SiteSettings } from '../data/site';
import { api } from './client';

// --- forme delle risposte (API_CONTRACT §2, §7) ---------------------------------------------------------------

export type MediaRef = { id: string; alt: string; width: number; height: number; src: string; srcset: string };

type ItemDto = {
  id: string; categoryId?: string; categorySlug?: string; name: string; description: string | null; ingredients: string[];
  priceCents: number | null; demoPriceCents: number | null; allergens: { declared: boolean; codes: string[] };
  photo: MediaRef | null; visible?: boolean; version?: number;
};
type CategoryDto = { id?: string; slug: string; name: string; hint: string | null; visible?: boolean; items?: ItemDto[] };
type AllergenDto = { code: string; name: string };
type PublicMenuDto = { categories: CategoryDto[]; allergens: AllergenDto[] };
type AdminMenuDto = { categories: CategoryDto[]; items: ItemDto[]; allergens: AllergenDto[] };

type OwnerDto = { id: string; name: string; roleLabel: string | null; description: string | null; motto: string | null;
  color: string | null; front: MediaRef | null; back: MediaRef | null; visible: boolean };
type StaffDto = { id: string; name: string; roleLabel: string | null; photo: MediaRef | null; visible: boolean };
type ContentDto = { version: number; heroClaim: string | null; heroLead: string | null; heroPhoto: MediaRef | null;
  sections: Record<string, boolean>; storyTitle: string | null; storyText: string | null; storyConfirmed: boolean;
  owners: OwnerDto[]; ownersConfirmed: boolean; staff: StaffDto[]; staffConfirmed: boolean;
  menuHero: { photos: MediaRef[]; intervalSec: number } };
type HoursDto = { isoDay: number; closed: boolean; opensAt: string | null; closesAt: string | null };
type SettingsDto = { version: number; addressLine: string | null; addressConfirmed: boolean; mapQuery: string | null;
  mapMode: 'CONSENT' | 'AUTO'; phone: string | null; email: string | null; instagramUrl: string | null;
  hoursConfirmed: boolean; hours: HoursDto[] };
type AnnouncementDto = { id: string; text: string; tone: 'INFO' | 'IMPORTANT'; active: boolean; visibleUntil: string | null };
type PublicSiteDto = { settings: SettingsDto; content: ContentDto; announcements: AnnouncementDto[]; demoContent: boolean };
type LibraryDto = MediaRef & { inGallery: boolean; usages: { type: string; label: string }[] };

// --- traduzioni --------------------------------------------------------------------------------------------

const DAYS = [
  ['Lunedì', 'Lun'], ['Martedì', 'Mar'], ['Mercoledì', 'Mer'], ['Giovedì', 'Gio'], ['Venerdì', 'Ven'], ['Sabato', 'Sab'], ['Domenica', 'Dom'],
];

export const asset = (m: MediaRef): Asset => ({ src: m.src, alt: m.alt, w: m.width, h: m.height, srcSet: m.srcset || undefined });

/** Tutte le foto citate in una risposta, per risolvere gli id ovunque (menu, titolari, staff, testata). */
function collect(refs: (MediaRef | null | undefined)[], into: Record<string, Asset>) {
  for (const r of refs) if (r?.src) into[r.id] = asset(r);
}

function item(dto: ItemDto, slug: string, names: Map<string, string>): MenuItem {
  const demo = dto.priceCents == null && dto.demoPriceCents != null;
  return {
    id: dto.id,
    name: dto.name,
    category: dto.categorySlug ?? slug,
    description: dto.description ?? '',
    ingredients: dto.ingredients,
    allergens: dto.allergens.declared ? dto.allergens.codes.map((c) => names.get(c) ?? c) : null,
    price: dto.priceCents == null ? null : dto.priceCents / 100,
    demoPrice: dto.demoPriceCents == null ? undefined : dto.demoPriceCents / 100,
    photo: dto.photo?.id ?? null,
    visible: dto.visible ?? true,
    // con un prezzo vero la voce è stata confermata dai gestori: niente più etichette «demo»/«segnaposto»
    source: !demo ? 'reale' : dto.photo ? 'foto' : 'demo',
    version: dto.version,
  };
}

const category = (c: CategoryDto): Category => ({ id: c.slug, label: c.name, hint: c.hint ?? '', uuid: c.id });

function content(dto: ContentDto, media: Record<string, Asset>): SiteContent {
  collect([dto.heroPhoto, ...dto.owners.flatMap((o) => [o.front, o.back]), ...dto.staff.map((s) => s.photo), ...dto.menuHero.photos], media);
  return {
    version: dto.version,
    heroClaim: dto.heroClaim ?? '',
    heroLead: dto.heroLead ?? '',
    heroPhoto: dto.heroPhoto?.id ?? '',
    sections: { momento: true, trio: true, persone: true, eventi: true, striscia: true, visita: true, ...dto.sections } as Record<HomeSection, boolean>,
    storyTitle: dto.storyTitle ?? '',
    storyText: dto.storyText ?? '',
    storyConfirmed: dto.storyConfirmed,
    owners: dto.owners.map((o): Owner => ({
      id: o.id, name: o.name, role: o.roleLabel ?? '', description: o.description ?? '', motto: o.motto ?? '',
      front: o.front?.id ?? '', back: o.back?.id ?? '', color: (o.color ?? 'giallo') as Owner['color'],
    })),
    ownersConfirmed: dto.ownersConfirmed,
    staff: dto.staff.map((s): StaffMember => ({ id: s.id, name: s.name, role: s.roleLabel ?? '', photo: s.photo?.id ?? '' })),
    staffConfirmed: dto.staffConfirmed,
    menuHero: { photos: dto.menuHero.photos.map((p) => p.id), intervalSec: dto.menuHero.intervalSec },
  };
}

const handle = (url: string | null) => {
  const m = url?.match(/instagram\.com\/([^/?#]+)/);
  return m ? `@${m[1]}` : '';
};

function settings(dto: SettingsDto, base: SiteSettings): SiteSettings {
  const hours: DayHours[] = DAYS.map(([day, short], i) => {
    const h = dto.hours.find((x) => x.isoDay === i + 1);
    return { day, short, open: h && !h.closed ? h.opensAt!.slice(0, 5) : null, close: h && !h.closed ? h.closesAt!.slice(0, 5) : null };
  });
  const query = dto.mapQuery ?? dto.addressLine ?? base.name;
  return {
    ...base,
    version: dto.version,
    addressLine: dto.addressLine ?? '',
    addressConfirmed: dto.addressConfirmed,
    phone: dto.phone,
    email: dto.email,
    instagram: dto.instagramUrl ?? '',
    instagramHandle: handle(dto.instagramUrl),
    mapsUrl: `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`,
    hours,
    hoursConfirmed: dto.hoursConfirmed,
    mapQuery: query,
    mapMode: dto.mapMode === 'AUTO' ? 'automatico' : 'consenso',
  };
}

const announcement = (a: AnnouncementDto): Announcement => ({
  id: a.id, text: a.text, tone: a.tone === 'IMPORTANT' ? 'importante' : 'info', active: a.active, until: a.visibleUntil,
});

const libraryItem = (m: LibraryDto): MediaItem => ({
  id: m.id, asset: asset(m), inGallery: m.inGallery, uploaded: false, usages: m.usages.map((u) => u.label),
});

// --- sito pubblico -----------------------------------------------------------------------------------------

export type PublicData = {
  settings: SiteSettings;
  content: SiteContent;
  announcements: Announcement[];
  categories: Category[];
  menu: MenuItem[];
  allergens: { code: string; name: string }[];
  gallery: MediaItem[];
  mediaIndex: Record<string, Asset>;
  demoContent: boolean;
};

/** Il sito pubblico in tre letture con ETag (sito, menu, galleria). */
export async function loadPublic(base: SiteSettings): Promise<PublicData> {
  const [site, menuDto, gallery] = await Promise.all([
    api.get<PublicSiteDto>('/public/site'),
    api.get<PublicMenuDto>('/public/menu'),
    api.get<MediaRef[]>('/public/gallery'),
  ]);
  const mediaIndex: Record<string, Asset> = {};
  const names = new Map(menuDto.allergens.map((a) => [a.code, a.name]));
  const menu = menuDto.categories.flatMap((c) => (c.items ?? []).map((i) => item(i, c.slug, names)));
  collect(menuDto.categories.flatMap((c) => (c.items ?? []).map((i) => i.photo)), mediaIndex);
  collect(gallery, mediaIndex);
  return {
    settings: settings(site.settings, base),
    content: content(site.content, mediaIndex),
    announcements: site.announcements.map(announcement),
    categories: menuDto.categories.map(category),
    menu,
    allergens: menuDto.allergens,
    gallery: gallery.map((g) => ({ id: g.id, asset: asset(g), inGallery: true, uploaded: false })),
    mediaIndex,
    demoContent: site.demoContent,
  };
}

// --- area gestori: menu (staff) ----------------------------------------------------------------------------

export type AdminMenu = { categories: Category[]; items: MenuItem[]; allergens: { code: string; name: string }[]; mediaIndex: Record<string, Asset> };

export async function loadAdminMenu(): Promise<AdminMenu> {
  const dto = await api.get<AdminMenuDto>('/admin/menu');
  const names = new Map(dto.allergens.map((a) => [a.code, a.name]));
  const mediaIndex: Record<string, Asset> = {};
  collect(dto.items.map((i) => i.photo), mediaIndex);
  return { categories: dto.categories.map(category), items: dto.items.map((i) => item(i, '', names)), allergens: dto.allergens, mediaIndex };
}

function itemRequest(m: MenuItem, categories: Category[], allergens: { code: string; name: string }[]) {
  const codes = new Map(allergens.map((a) => [a.name, a.code]));
  return {
    version: m.version,
    categoryId: categories.find((c) => c.id === m.category)?.uuid,
    name: m.name,
    description: m.description || null,
    ingredients: m.ingredients,
    priceCents: m.price == null ? null : Math.round(m.price * 100),
    allergens: { declared: m.allergens != null, codes: (m.allergens ?? []).map((a) => codes.get(a) ?? a) },
    photoMediaId: m.photo,
    visible: m.visible,
  };
}

export async function saveMenuItem(m: MenuItem, isNew: boolean, categories: Category[], allergens: { code: string; name: string }[]) {
  const body = itemRequest(m, categories, allergens);
  const dto = isNew
    ? await api.post<ItemDto>('/admin/menu/items', { ...body, version: undefined })
    : await api.put<ItemDto>(`/admin/menu/items/${m.id}`, body);
  return item(dto, '', new Map(allergens.map((a) => [a.code, a.name])));
}

export async function setMenuItemVisibility(m: MenuItem, visible: boolean, allergens: { code: string; name: string }[]) {
  const dto = await api.patch<ItemDto>(`/admin/menu/items/${m.id}/visibility`, { version: m.version, visible });
  return item(dto, '', new Map(allergens.map((a) => [a.code, a.name])));
}

export const deleteMenuItem = (id: string) => api.del(`/admin/menu/items/${id}`);

// --- area gestori: foto (staff) ----------------------------------------------------------------------------

export async function loadLibrary(): Promise<MediaItem[]> {
  return (await api.get<LibraryDto[]>('/admin/media')).map(libraryItem);
}

export async function uploadPhoto(file: File, altText: string): Promise<MediaItem> {
  const form = new FormData();
  form.append('file', file);
  form.append('altText', altText);
  return { ...libraryItem(await api.upload<LibraryDto>('POST', '/admin/media', form)), uploaded: true };
}

export async function replacePhoto(id: string, file: File): Promise<MediaItem> {
  const form = new FormData();
  form.append('file', file);
  return { ...libraryItem(await api.upload<LibraryDto>('PUT', `/admin/media/${id}/file`, form)), uploaded: true };
}

export async function updatePhoto(id: string, patch: { altText?: string; inGallery?: boolean }): Promise<MediaItem> {
  return libraryItem(await api.patch<LibraryDto>(`/admin/media/${id}`, patch));
}

export async function reorderPhotos(ids: string[]): Promise<MediaItem[]> {
  return (await api.put<LibraryDto[]>('/admin/media/order', { mediaIds: ids })).map(libraryItem);
}

export const deletePhoto = (id: string) => api.del(`/admin/media/${id}`);

// --- area gestori: contenuti e impostazioni (titolare) -----------------------------------------------------

/** Gli id creati nel browser per le persone nuove iniziano con «nuovo-»: per il backend sono righe da inserire. */
export const NEW_ID = 'nuovo-';
const serverId = (id: string) => (id.startsWith(NEW_ID) ? null : id);
const mediaId = (id: string) => id || null;

export async function loadSiteContent(): Promise<{ content: SiteContent; mediaIndex: Record<string, Asset> }> {
  const mediaIndex: Record<string, Asset> = {};
  return { content: content(await api.get<ContentDto>('/admin/site-content'), mediaIndex), mediaIndex };
}

export async function saveSiteContent(c: SiteContent): Promise<{ content: SiteContent; mediaIndex: Record<string, Asset> }> {
  const dto = await api.put<ContentDto>('/admin/site-content', {
    version: c.version,
    heroClaim: c.heroClaim,
    heroLead: c.heroLead,
    heroMediaId: mediaId(c.heroPhoto),
    sections: c.sections,
    storyTitle: c.storyTitle,
    storyText: c.storyText,
    storyConfirmed: c.storyConfirmed,
    owners: c.owners.map((o) => ({ id: serverId(o.id), name: o.name, roleLabel: o.role, description: o.description,
      motto: o.motto, color: o.color, frontMediaId: mediaId(o.front), backMediaId: mediaId(o.back), visible: true })),
    ownersConfirmed: c.ownersConfirmed,
    staff: c.staff.map((s) => ({ id: serverId(s.id), name: s.name, roleLabel: s.role, mediaId: mediaId(s.photo), visible: true })),
    staffConfirmed: c.staffConfirmed,
    menuHeroMediaIds: c.menuHero.photos,
    menuHeroIntervalSec: c.menuHero.intervalSec,
  });
  const mediaIndex: Record<string, Asset> = {};
  return { content: content(dto, mediaIndex), mediaIndex };
}

export async function loadSettings(base: SiteSettings): Promise<SiteSettings> {
  return settings(await api.get<SettingsDto>('/admin/site-settings'), base);
}

export async function saveSettings(s: SiteSettings): Promise<SiteSettings> {
  const dto = await api.put<SettingsDto>('/admin/site-settings', {
    version: s.version,
    addressLine: s.addressLine,
    addressConfirmed: s.addressConfirmed,
    mapQuery: s.mapQuery,
    mapMode: s.mapMode === 'automatico' ? 'AUTO' : 'CONSENT',
    phone: s.phone,
    email: s.email,
    instagramUrl: s.instagram || null,
    hoursConfirmed: s.hoursConfirmed,
    hours: s.hours.map((h, i) => (h.open && h.close
      ? { isoDay: i + 1, closed: false, opensAt: h.open, closesAt: h.close }
      : { isoDay: i + 1, closed: true })),
  });
  return settings(dto, s);
}

// --- area gestori: avvisi (staff) --------------------------------------------------------------------------

export async function loadAnnouncements(): Promise<Announcement[]> {
  return (await api.get<AnnouncementDto[]>('/admin/announcements')).map(announcement);
}

const announcementRequest = (a: Announcement) => ({
  text: a.text, tone: a.tone === 'importante' ? 'IMPORTANT' : 'INFO', active: a.active, visibleUntil: a.until,
});

export async function createAnnouncement(a: Announcement): Promise<Announcement> {
  return announcement(await api.post<AnnouncementDto>('/admin/announcements', announcementRequest(a)));
}

export async function updateAnnouncement(a: Announcement): Promise<Announcement> {
  return announcement(await api.put<AnnouncementDto>(`/admin/announcements/${a.id}`, announcementRequest(a)));
}

export const deleteAnnouncement = (id: string) => api.del(`/admin/announcements/${id}`);
