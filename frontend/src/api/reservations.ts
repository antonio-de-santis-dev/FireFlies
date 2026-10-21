// Prenotazioni V1 (FASE 5): richieste dal sito, area gestori, configurazione di zone, fasce e tavoli.
// Il backend è l'unica fonte di verità: qui solo forme delle risposte e chiamate.
import { api } from './client';

export type ReservationStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED' | 'COMPLETED' | 'NO_SHOW';
export type ReservationSource = 'WEB' | 'PHONE' | 'EMAIL';

export const STATUS_LABEL: Record<ReservationStatus, string> = {
  PENDING: 'In attesa',
  CONFIRMED: 'Confermata',
  REJECTED: 'Rifiutata',
  CANCELLED: 'Annullata',
  COMPLETED: 'Completata',
  NO_SHOW: 'Non presentato',
};

export const SOURCE_LABEL: Record<ReservationSource, string> = { WEB: 'Sito', PHONE: 'Telefono', EMAIL: 'Email' };

export const REASON_LABEL: Record<string, string> = {
  NO_CAPACITY: 'Posti esauriti',
  VENUE_DECISION: 'Decisione del locale',
  DUPLICATE: 'Richiesta doppia',
  OTHER: 'Altro',
  CUSTOMER_REQUEST: 'Richiesta del cliente',
  EXPIRED: 'Scaduta senza risposta',
};

/** «19:00:00» → «19:00» */
export const hhmm = (t: string | null | undefined) => (t ?? '').slice(0, 5);

/** Chiave casuale per l'intestazione Idempotency-Key (anche fuori da HTTPS, dove crypto.randomUUID manca). */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// --- pubblico -------------------------------------------------------------------------------------------------

export type SlotReason = 'TOO_EARLY' | 'DAY_CLOSED' | 'FULL' | 'EVENT' | null;
export type BookingOptions = {
  webBookingEnabled: boolean; maxPartySize: number; maxAdvanceDays: number; minLeadMinutes: number;
  firstDate: string | null; lastDate: string | null; date: string | null;
  groups: { name: string; slots: { time: string; available: boolean; reason: SlotReason }[] }[];
  /** Giorni senza arrivi possibili dal sito (chiusura settimanale o straordinaria, nessuna fascia): il calendario li disabilita. */
  closedDates?: string[];
  /**
   * FIX 2: gli stessi giorni con il motivo — `VENUE_CLOSED` locale chiuso (orari settimanali o chiusura straordinaria),
   * `NO_ARRIVAL_TIMES` aperto ma nessun orario di arrivo dentro l'orario di apertura (fasceorarie: le fasce sono interne),
   * `ARRIVALS_CLOSED` fasce chiuse agli arrivi in quella data.
   */
  unavailableDays?: { date: string; reason: DayClosure }[];
};
export type DayClosure = 'VENUE_CLOSED' | 'NO_ARRIVAL_TIMES' | 'ARRIVALS_CLOSED';
export type Receipt = { reference: string; status: ReservationStatus; date: string; time: string; guests: number; message: string };
export type WebRequest = {
  date: string; time: string; guests: number; name: string; phone: string; email: string; notes: string;
  privacyAccepted: boolean; website: string; eventId?: string;
};

export const bookingOptions = (date?: string, guests?: number) => {
  const q = new URLSearchParams();
  if (date) q.set('date', date);
  if (guests) q.set('guests', String(guests));
  const qs = q.toString();
  return api.get<BookingOptions>(`/public/booking-options${qs ? `?${qs}` : ''}`);
};

export const sendRequest = (body: WebRequest, idempotencyKey: string) =>
  api.post<Receipt>('/public/reservations', body, { 'Idempotency-Key': idempotencyKey });

// --- area gestori ---------------------------------------------------------------------------------------------

export type Summary = {
  id: string; reference: string; status: ReservationStatus; statusReason: string | null; source: ReservationSource;
  date: string; time: string; endTime: string; guests: number; customerName: string; customerPhone: string;
  customerEmail: string | null; notes: string | null; needsCallback: boolean; zoneId: string | null; tables: string[];
  createdAt: string; version: number; eventId: string | null; eventTitle: string | null;
};
export type Action = 'CONFIRM' | 'REJECT' | 'CANCEL' | 'EDIT' | 'TABLES' | 'COMPLETE' | 'NO_SHOW' | 'CORRECT' | 'NOTES';
export type Detail = {
  summary: Summary; notes: string | null; internalNote: string | null; zoneName: string | null; durationMinutes: number;
  createdBy: string | null; handledBy: string | null; confirmedAt: string | null; closedAt: string | null; updatedAt: string;
  tables: { tableId: string; code: string; area: string; maxSeats: number; seatsOverride: boolean; overrideReason: string | null }[];
  history: { fromStatus: ReservationStatus | null; toStatus: ReservationStatus; reason: string | null; note: string | null; by: string | null; at: string }[];
  changes: { action: string; by: string | null; at: string; details: Record<string, unknown> }[];
  notifications: {
    id: string; template: string; channel: 'EMAIL' | 'SMS'; recipient: 'CUSTOMER' | 'STAFF'; state: string; skipReason: string | null;
    delivered: boolean; simulated: boolean; resendable: boolean; createdAt: string;
  }[];
  capacity: { zoneId: string; capacity: number; minFree: number; pendingOthers: number; eventBlocks: boolean; fits: boolean } | null;
  actions: Action[];
  warnings: string[];
};
export type ListResult = { items: Summary[]; total: number; page: number; size: number; counts: Partial<Record<ReservationStatus, number>> };
export type ListFilter = { from?: string; to?: string; status?: ReservationStatus[]; source?: ReservationSource | ''; q?: string; needsCallback?: boolean; size?: number };

export function listReservations(f: ListFilter) {
  const q = new URLSearchParams();
  if (f.from) q.set('from', f.from);
  if (f.to) q.set('to', f.to);
  if (f.status?.length) q.set('status', f.status.join(','));
  if (f.source) q.set('source', f.source);
  if (f.q?.trim()) q.set('q', f.q.trim());
  if (f.needsCallback) q.set('needsCallback', 'true');
  q.set('size', String(f.size ?? 100));
  return api.get<ListResult>(`/admin/reservations?${q}`);
}

export const getReservation = (id: string) => api.get<Detail>(`/admin/reservations/${id}`);

export type StatusChange = {
  version: number; status: ReservationStatus; reason?: string; note?: string; tableIds?: string[];
  overrideSeats?: boolean; overrideReason?: string;
};
export const changeStatus = (id: string, body: StatusChange) => api.patch<Detail>(`/admin/reservations/${id}/status`, body);

export type ReservationUpdate = {
  version: number; date?: string; time?: string; durationMinutes?: number; guests?: number; name?: string; phone?: string;
  email?: string; notes?: string; internalNote?: string; needsCallback?: boolean;
};
export const updateReservation = (id: string, body: ReservationUpdate) => api.patch<Detail>(`/admin/reservations/${id}`, body);

export const changeTables = (id: string, body: { version: number; tableIds: string[]; overrideSeats?: boolean; overrideReason?: string }) =>
  api.put<Detail>(`/admin/reservations/${id}/tables`, body);

export type ManualRequest = {
  source: 'PHONE' | 'EMAIL'; date: string; time: string; durationMinutes?: number; guests: number; name: string; phone: string;
  email?: string; notes?: string; internalNote?: string; confirmNow: boolean; needsCallback: boolean; tableIds?: string[];
  eventId?: string;
};
export const createManual = (body: ManualRequest) => api.post<Detail>('/admin/reservations', body);

export type DayCapacity = {
  date: string;
  zones: { zoneId: string; name: string; physicalCapacity: number; quanta: { time: string; capacity: number; confirmed: number; pending: number; event: boolean }[] }[];
};
export const dayCapacity = (date: string) => api.get<DayCapacity>(`/admin/capacity?date=${date}`);

// --- configurazione (lettura: staff e titolare; scrittura: solo titolare) ------------------------------------

export type Zone = { id: string; name: string; physicalCapacity: number; active: boolean; sortOrder: number; version: number };
export type Area = { id: string; zoneId: string; name: string; active: boolean; sortOrder: number };
export type Table = { id: string; areaId: string; zoneId: string; code: string; minSeats: number; maxSeats: number; active: boolean; sortOrder: number };
export type ServiceWindow = {
  id: string; zoneId: string; isoDay: number; name: string; firstStart: string; lastStart: string; slotMinutes: number;
  defaultDurationMinutes: number; validFrom: string; validTo: string | null; active: boolean;
};
export type Policy = {
  id: string; effectiveFrom: string; webBookingEnabled: boolean; minLeadMinutes: number; maxAdvanceDays: number;
  pendingExpiryMinutesBeforeStart: number;
};
/** Fascia chiusa agli arrivi in una data (FIX-1: la capienza per data non esiste più). */
export type BookingException = { id: string; serviceDate: string; serviceWindowId: string | null; kind: 'CLOSED'; reason: string | null };
/** Chiusura o apertura straordinaria dal giorno `day` al giorno `endDay` compresi (un giorno solo: endDay = day). */
export type OpeningException = { id: string; day: string; endDay: string; closed: boolean; opensAt: string | null; closesAt: string | null; note: string | null };
export type BookingConfig = {
  zones: Zone[]; areas: Area[]; tables: Table[]; windows: ServiceWindow[]; policy: Policy | null; exceptions: BookingException[];
  /** FIX-1: capienza massima del locale, la stessa in ogni giorno e fascia. */
  venueCapacity: number; openingExceptions: OpeningException[];
};

export const bookingConfig = () => api.get<BookingConfig>('/admin/booking-config');

export const updateZone = (z: Zone, patch: Partial<Pick<Zone, 'name' | 'physicalCapacity' | 'active'>>) =>
  api.put<Zone>(`/admin/capacity-zones/${z.id}`, {
    version: z.version, name: patch.name ?? z.name, physicalCapacity: patch.physicalCapacity ?? z.physicalCapacity,
    active: patch.active ?? z.active, sortOrder: z.sortOrder,
  });

export const savePolicy = (p: Omit<Policy, 'id' | 'effectiveFrom'>) => api.put<Policy>('/admin/booking-policy', p);

/** FIX-1: l'unica capienza da configurare. Errore `CAPACITY_BELOW_CONFIRMED` se ci sono già più persone confermate. */
export const saveVenueCapacity = (capacity: number) => api.put<Zone>('/admin/venue-capacity', { capacity });

export const createArea = (zoneId: string, name: string) => api.post<Area>('/admin/areas', { zoneId, name });
export const deleteArea = (id: string) => api.del(`/admin/areas/${id}`);

export type TableInput = { areaId: string; code: string; minSeats: number; maxSeats: number; active?: boolean; sortOrder?: number };
export const createTable = (t: TableInput) => api.post<Table>('/admin/tables', t);
export const updateTable = (id: string, t: TableInput) => api.put<Table>(`/admin/tables/${id}`, t);
export const deleteTable = (id: string) => api.del(`/admin/tables/${id}`);


export const deleteException = (id: string) => api.del(`/admin/booking-exceptions/${id}`);

// --- FASE 7 / FIX-1: riepilogo per fascia, calendario di oggi, date straordinarie --------------------------

export type Band = 'COLAZIONE' | 'POMERIGGIO' | 'APERITIVO' | 'SERA';
export const BANDS: { id: Band; label: string }[] = [
  { id: 'COLAZIONE', label: 'Colazione' }, { id: 'POMERIGGIO', label: 'Pomeriggio' },
  { id: 'APERITIVO', label: 'Aperitivo' }, { id: 'SERA', label: 'Sera' },
];

export type SummaryRow = {
  date: string; band: Band; label: string; closed: boolean; requests: number; pending: number; confirmed: number; guests: number;
  capacity: number | null; available: number | null; fromStatistics: boolean;
  /** FIX 2: budget della fascia (capienza del locale), persone confermate che lo consumano e quante ne restano. */
  bandCapacity: number | null; bandConfirmed: number; bandRemaining: number | null;
  /** Gruppo più grande che sta fisicamente in un orario della fascia; arrivi riservati a un evento su arrivi totali. */
  physicalAvailable: number | null; eventArrivals: number; arrivals: number; limitedByPhysical: boolean;
};
export const capacitySummary = (from: string, to: string, band?: Band) =>
  api.get<{ from: string; to: string; rows: SummaryRow[] }>(`/admin/capacity-summary?from=${from}&to=${to}${band ? `&band=${band}` : ''}`);

export type TodayItem = { id: string; reference: string; time: string; name: string | null; guests: number; band: Band; bandLabel: string; event: string | null };
export type Today = { date: string; band: Band | null; items: TodayItem[]; bands: { band: Band; label: string; reservations: number; guests: number }[]; reservations: number; guests: number };
export const todayReservations = (band: Band | null) => api.get<Today>(`/admin/reservations/today${band ? `?band=${band}` : ''}`);

export type OpeningInput = { from: string; to: string; closed: boolean; opensAt?: string | null; closesAt?: string | null; note?: string };
/** Esito: per una chiusura, i giorni con prenotazioni già in attesa o confermate (restano valide, da gestire a mano). */
export type OpeningResult = {
  exception: OpeningException; activeReservations: number;
  conflicts: { date: string; pending: number; confirmed: number; confirmedGuests: number }[];
};
/** FIX 2: una chiusura su giorni con prenotazioni attive risponde `409 CLOSURE_HAS_RESERVATIONS` finché non si conferma. */
export const addOpeningException = (e: OpeningInput, confirmReservations = false) =>
  api.post<OpeningResult>('/admin/opening-exceptions', { ...e, confirmReservations });
export const updateOpeningException = (id: string, e: OpeningInput, confirmReservations = false) =>
  api.put<OpeningResult>(`/admin/opening-exceptions/${id}`, { ...e, confirmReservations });
export const deleteOpeningException = (id: string) => api.del(`/admin/opening-exceptions/${id}`);

/** Testo per lo stato di una notifica: mai «inviata» se non è partita davvero. */
export const resendNotification = (id: string) => api.post<void>(`/admin/notifications/${id}/resend`, {});

export function notificationLabel(n: Detail['notifications'][number]): string {
  if (n.delivered) return 'Inviata';
  if (n.simulated) return 'Simulata: nessun messaggio reale';
  if (n.state === 'SENDING') return 'In invio';
  if (n.state === 'UNCERTAIN') return 'Esito incerto: verifica col cliente';
  if (n.skipReason === 'TEMPLATE_INACTIVE') return 'Non inviata: modello non attivo';
  if (n.skipReason === 'EXPIRED') return 'Non inviata: ormai superata dalla data';
  if (n.state === 'PENDING') return n.channel === 'EMAIL' ? 'Email preparata, non inviata' : 'Preparata, non inviata';
  if (n.skipReason === 'SMS_DISABLED') return 'SMS non previsto (canale spento)';
  if (n.skipReason === 'NO_RECIPIENT') return n.recipient === 'STAFF' ? "Non preparata: manca l'email del locale" : 'Non preparata: nessuna email del cliente';
  if (n.skipReason === 'SUPERSEDED') return 'Superata da un aggiornamento';
  if (n.state === 'FAILED') return 'Invio non riuscito: nuovo tentativo automatico';
  if (n.state === 'DEAD') return 'Invio non riuscito';
  return 'Non inviata';
}

export const TEMPLATE_LABEL: Record<string, string> = {
  RESERVATION_RECEIVED: 'Ricevuta della richiesta',
  RESERVATION_NEW_FOR_VENUE: 'Avviso al locale',
  RESERVATION_CONFIRMED: 'Conferma',
  RESERVATION_REJECTED: 'Rifiuto',
  RESERVATION_CANCELLED: 'Annullamento',
  RESERVATION_UPDATED: 'Modifica',
  RESERVATION_EXPIRED: 'Scadenza',
};
