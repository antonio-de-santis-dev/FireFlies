import { ArrowRight, CalendarCheck, CalendarDays, CircleAlert, Inbox, PhoneCall, UtensilsCrossed } from 'lucide-react';
import { useEffect, useState } from 'react';
import { hhmm, listReservations, todayReservations, type Summary } from '../../api/reservations';
import { formatEventDate } from '../../data/events';
import { Link, usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { fmtShort, ReservationBadge } from './Requests';

const today = () => {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 10);
};

/** Richieste da gestire dal backend: in attesa e da richiamare, da oggi in poi. */
function useRequestsToHandle() {
  const [state, setState] = useState<{ pending: Summary[]; pendingTotal: number; callbacks: Summary[] } | null>(null);
  useEffect(() => {
    let current = true;
    Promise.all([
      listReservations({ from: today(), status: ['PENDING'], size: 6 }),
      listReservations({ from: today(), status: ['PENDING', 'CONFIRMED'], needsCallback: true, size: 6 }),
    ])
      .then(([p, c]) => current && setState({ pending: p.items, pendingTotal: p.total, callbacks: c.items.filter((r) => r.status !== 'PENDING') }))
      .catch(() => current && setState({ pending: [], pendingTotal: 0, callbacks: [] }));
    return () => { current = false; };
  }, []);
  return state;
}

/** Prenotazioni confermate di oggi (stessa fonte del «Calendario prenotazioni oggi»). */
function useTodayCount() {
  const [n, setN] = useState<{ reservations: number; guests: number } | null>(null);
  useEffect(() => {
    let current = true;
    todayReservations(null).then((t) => current && setN({ reservations: t.reservations, guests: t.guests })).catch(() => current && setN(null));
    return () => { current = false; };
  }, []);
  return n;
}

export function Dashboard() {
  usePageMeta('Panoramica');
  const { events, menu, settings, role } = useStore();
  // FASE 7, req. 1: niente scorciatoie che ripetono il menu di navigazione, solo indicatori e cose da fare
  const requests = useRequestsToHandle();
  const todayCount = useTodayCount();
  const fresh = requests?.pending ?? [];
  const toCall = requests?.callbacks ?? [];
  const upcoming = events.filter((e) => e.kind === 'serata' && e.status === 'pubblicato').sort((a, b) => (a.date ?? '').localeCompare(b.date ?? ''));
  const incomplete = menu.filter((m) => m.price == null || m.allergens == null);
  const todo = [
    !settings.hoursConfirmed && 'Orari di apertura',
    !settings.addressConfirmed && 'Indirizzo esatto',
    !settings.phone && 'Numero di telefono',
    'Testi «Il locale» e nomi dello staff',
    'Privacy e cookie policy',
  ].filter(Boolean) as string[];

  return (
    <>
      <AdminHead title="Ciao! Ecco la giornata." lead="Richieste tavolo ed eventi dal server: quello che vedi qui è quello che vede il sito." />

      <ul className="stats" aria-label="Riepilogo">
        <li className="stats__item stats__item--hot">
          <Inbox aria-hidden="true" />
          <span className="stats__n">{requests ? requests.pendingTotal : '…'}</span>
          <span>richieste in attesa</span>
        </li>
        <li className="stats__item">
          <CalendarCheck aria-hidden="true" />
          <span className="stats__n">{todayCount ? todayCount.reservations : '…'}</span>
          <span>{todayCount ? <Link to="/gestione/oggi">confermate oggi · {todayCount.guests} persone</Link> : 'confermate oggi'}</span>
        </li>
        <li className="stats__item">
          <PhoneCall aria-hidden="true" />
          <span className="stats__n">{requests ? toCall.length + fresh.filter((r) => r.needsCallback).length : '…'}</span>
          <span>da ricontattare</span>
        </li>
        <li className="stats__item">
          <CalendarDays aria-hidden="true" />
          <span className="stats__n">{upcoming.length}</span>
          <span>serate in calendario</span>
        </li>
        <li className="stats__item">
          <UtensilsCrossed aria-hidden="true" />
          <span className="stats__n">{incomplete.length}</span>
          <span>voci menu da completare</span>
        </li>
      </ul>

      <div className="dash">
        <section className="panel dash__requests" aria-labelledby="dash-req">
          <div className="panel__head">
            <h2 id="dash-req">Richieste da gestire</h2>
            <Link to="/gestione/richieste" className="panel__link">Tutte <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
          {!requests ? (
            <p className="muted" role="status">Caricamento…</p>
          ) : fresh.length + toCall.length === 0 ? (
            <p className="muted">Nessuna richiesta in attesa. Ottimo lavoro.</p>
          ) : (
            <ul className="req-mini">
              {[...fresh, ...toCall].map((r) => (
                <li key={r.id}>
                  <span className="req-mini__when">{fmtShort(r.date)} · {hhmm(r.time)}</span>
                  <span className="req-mini__who">{r.customerName} · {r.guests} pers.</span>
                  <ReservationBadge status={r.status} />
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel" aria-labelledby="dash-ev">
          <div className="panel__head">
            <h2 id="dash-ev">Prossimi eventi</h2>
            <Link to="/gestione/eventi" className="panel__link">Gestisci <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
          <ul className="ev-mini">
            {upcoming.map((e) => (
              <li key={e.id}>
                <Link to={`/gestione/eventi/${e.id}`}>{e.title}</Link>
                <span className="muted small">{formatEventDate(e)}</span>
                <span className={`badge ${e.status === 'pubblicato' ? 'badge--success' : 'badge--info'}`}>
                  {e.status === 'pubblicato' ? 'Pubblicato' : 'Bozza'}
                </span>
              </li>
            ))}
          </ul>
        </section>

        <section className="panel" aria-labelledby="dash-menu">
          <div className="panel__head">
            <h2 id="dash-menu">Menu da aggiornare</h2>
            <Link to="/gestione/menu" className="panel__link">Apri il menu <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
          <p className="small muted">Voci senza prezzo o senza allergeni dichiarati.</p>
          <ul className="todo">
            {incomplete.slice(0, 5).map((m) => (
              <li key={m.id}>
                <Link to={`/gestione/menu/${m.id}`}>{m.name}</Link>
                <span className="small muted">
                  {[m.price == null && 'prezzo', m.allergens == null && 'allergeni'].filter(Boolean).join(' e ')} mancanti
                </span>
              </li>
            ))}
          </ul>
          {incomplete.length > 5 && <p className="small muted">e altre {incomplete.length - 5}…</p>}
        </section>

        <section className="panel panel--warn" aria-labelledby="dash-todo">
          <div className="panel__head">
            <h2 id="dash-todo"><CircleAlert size={22} aria-hidden="true" /> Da confermare prima di pubblicare</h2>
          </div>
          <ul className="todo todo--plain">
            {todo.map((t) => <li key={t}>{t}</li>)}
          </ul>
          {role === 'titolare' && <Link to="/gestione/impostazioni" className="btn btn--ghost btn--sm">Vai alle impostazioni</Link>}
        </section>
      </div>
    </>
  );
}
