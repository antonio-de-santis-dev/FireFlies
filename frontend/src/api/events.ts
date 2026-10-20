// Eventi (FASE 6): sito pubblico e area gestori. Le risposte pubbliche vengono tradotte nel tipo `FireEvent` già usato
// dalle pagine approvate, così il layout non cambia.
import type { Asset } from '../data/assets';
import type { FireEvent } from '../data/events';
import { api } from './client';
import { asset, type MediaRef } from './content';
import { hhmm } from './reservations';

export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED';
export type BookingMode = 'NONE' | 'REQUEST';
type BookingReason = 'NONE' | 'FULL' | 'CLOSED' | 'TOO_LATE' | 'CANCELLED' | null;

type PublicEventDto = {
  id: string; slug: string; title: string; kind: 'SERATA' | 'FORMAT'; status: 'PUBLISHED' | 'CANCELLED';
  date: string | null; startTime: string | null; endTime: string | null; artist: string | null; summary: string | null;
  body: string[]; poster: MediaRef | null; media: MediaRef[]; booking: { mode: BookingMode; open: boolean; reason: BookingReason };
};

export type AdminEvent = {
  id: string; slug: string; title: string; kind: 'SERATA' | 'FORMAT'; status: EventStatus; date: string | null;
  startTime: string | null; endTime: string | null; zoneId: string | null; exclusive: boolean; bookingMode: BookingMode;
  capacityCovers: number | null; confirmedCovers: number; pendingCovers: number; confirmedReservations: number;
  pendingReservations: number; artist: string | null; summary: string | null; body: string | null; poster: MediaRef | null;
  media: MediaRef[]; updatedAt: string; version: number; warnings: string[];
};

export type EventInput = {
  title: string; kind: 'SERATA' | 'FORMAT'; date: string | null; startTime: string | null; endTime: string | null;
  exclusive: boolean; bookingMode: BookingMode; capacityCovers: number | null; artist: string; summary: string; body: string;
  posterMediaId: string | null; mediaIds: string[];
};

/** Prenotazione in conflitto con un evento esclusivo (senza dati personali). */
export type EventConflict = { reference: string; date: string; time: string; endTime: string; guests: number; status: string };

const SEATS: Record<string, string> = {
  OPEN: 'Tavoli su richiesta, conferma del locale',
  FULL: 'Posti esauriti per questa serata: chiamaci',
  TOO_LATE: 'Richieste chiuse: per un tavolo chiamaci',
  CLOSED: 'Richieste dal sito chiuse: chiamaci',
  CANCELLED: 'Serata annullata',
  NONE: 'Senza prenotazione',
};

/** Evento pubblico → tipo delle pagine. `id` resta lo slug (indirizzo /eventi/…); le foto finiscono nell'indice. */
export function fireEvent(e: PublicEventDto, mediaIndex: Record<string, Asset>): FireEvent {
  for (const m of [e.poster, ...e.media]) if (m?.src) mediaIndex[m.id] = asset(m);
  const photo = e.poster?.id ?? e.media[0]?.id ?? '';
  return {
    id: e.slug,
    uuid: e.id,
    title: e.title,
    kind: e.kind === 'FORMAT' ? 'format' : 'serata',
    status: e.status === 'CANCELLED' ? 'annullato' : 'pubblicato',
    date: e.date,
    time: e.startTime ? hhmm(e.startTime) : null,
    endTime: e.endTime ? hhmm(e.endTime) : null,
    // i soli esempi sono quelli dei dati demo locali, riconoscibili dall'indirizzo «…-esempio»
    demo: e.slug.endsWith('-esempio'),
    artist: e.artist ?? 'Da annunciare',
    summary: e.summary ?? '',
    body: e.body,
    photo,
    media: [photo, ...e.media.map((m) => m.id).filter((id) => id !== photo)].filter(Boolean),
    seats: SEATS[e.booking.open ? 'OPEN' : e.booking.reason ?? 'NONE'] ?? SEATS.NONE,
    action: e.booking.open ? 'tavolo' : 'info',
    bookable: e.booking.open,
  };
}

export async function loadPublicEvents(mediaIndex: Record<string, Asset>): Promise<FireEvent[]> {
  return (await api.get<PublicEventDto[]>('/public/events')).map((e) => fireEvent(e, mediaIndex));
}

// --- area gestori ---------------------------------------------------------------------------------------------

export const listEvents = () => api.get<AdminEvent[]>('/admin/events');
export const getEvent = (id: string) => api.get<AdminEvent>(`/admin/events/${id}`);
/** Un solo salvataggio: `visible` vero = pubblicato nella stessa transazione (se un controllo fallisce non resta nulla). */
export const createEvent = (e: EventInput, visible: boolean, acknowledgePending = false) =>
  api.post<AdminEvent>('/admin/events', { ...e, visible, acknowledgePending });
export const updateEvent = (id: string, version: number, e: EventInput, visible: boolean, acknowledgePending = false) =>
  api.put<AdminEvent>(`/admin/events/${id}`, { ...e, version, visible, acknowledgePending });
export const cancelEvent = (id: string, version: number, cancelReservations: boolean) =>
  api.post<AdminEvent>(`/admin/events/${id}/cancel`, { version, cancelReservations });
export const deleteEvent = (id: string) => api.del(`/admin/events/${id}`);

export const EVENT_STATUS_LABEL: Record<EventStatus, string> = { DRAFT: 'Bozza', PUBLISHED: 'Pubblicato', CANCELLED: 'Annullato' };
