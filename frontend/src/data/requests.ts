// Richieste tavolo DEMO per l'area gestori. Nessun dato personale reale:
// nomi e recapiti sono segnaposto evidenti.

export type RequestStatus = 'nuova' | 'da-contattare' | 'confermata' | 'annullata';

export type BookingRequest = {
  id: string;
  date: string; // ISO
  time: string;
  guests: number;
  name: string;
  contact: string;
  notes: string;
  status: RequestStatus;
  receivedAt: string; // etichetta leggibile
  /** Notifica di conferma SIMULATA nel mockup (in produzione: provider SMS lato server). */
  notice?: { channel: 'sms' | 'nessuno'; at: string };
};

export const requestStatusLabel: Record<RequestStatus, string> = {
  nuova: 'Nuova',
  'da-contattare': 'Da contattare',
  confermata: 'Confermata',
  annullata: 'Annullata',
};

export const demoRequests: BookingRequest[] = [
  { id: 'r1', date: '2026-10-24', time: '19:30', guests: 4, name: 'Cliente demo A', contact: '+39 000 000 0001', notes: 'Tavolo fuori se possibile', status: 'nuova', receivedAt: 'Oggi, 10:12' },
  { id: 'r2', date: '2026-10-25', time: '18:30', guests: 8, name: 'Cliente demo B', contact: 'demo-b@example.invalid', notes: 'Compleanno, porta una torta', status: 'nuova', receivedAt: 'Oggi, 09:40' },
  { id: 'r3', date: '2026-10-24', time: '20:00', guests: 2, name: 'Cliente demo C', contact: '+39 000 000 0003', notes: '', status: 'da-contattare', receivedAt: 'Ieri, 18:05' },
  { id: 'r4', date: '2026-10-31', time: '19:30', guests: 6, name: 'Cliente demo D', contact: '+39 000 000 0004', notes: 'Per il DJ set', status: 'confermata', receivedAt: 'Ieri, 12:30' },
  { id: 'r5', date: '2026-10-19', time: '08:30', guests: 3, name: 'Cliente demo E', contact: 'demo-e@example.invalid', notes: 'Colazione di lavoro', status: 'annullata', receivedAt: '15 ott, 21:10' },
];
