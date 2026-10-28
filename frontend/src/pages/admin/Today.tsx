// Calendario prenotazioni oggi (FASE 7, req. 9): solo le prenotazioni CONFERMATE di oggi (ora di Roma), con un solo
// filtro per fascia. Niente tavoli, niente telefoni: chi è in sala vede orario, nome, persone, fascia ed evento.
import { CalendarCheck, CircleAlert, RefreshCw, Users } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { BANDS, hhmm, todayReservations, type Band, type Today as TodayData } from '../../api/reservations';
import { usePageMeta } from '../../router';
import { AdminHead } from './AdminLayout';
import { fmtShort } from './Requests';

export function Today() {
  usePageMeta('Calendario prenotazioni oggi');
  const [band, setBand] = useState<Band | null>(null);
  const [data, setData] = useState<TodayData | null>(null);
  const [error, setError] = useState<string | null>(null);

  // FIX 3 (F-03): vale solo l'ultima lettura partita. Cambiando fascia in fretta (Colazione → Sera) la risposta più
  // lenta della fascia precedente sovrascriveva l'elenco mentre il filtro mostrava già l'altra fascia
  const seq = useRef(0);
  const load = useCallback(() => {
    const mine = ++seq.current;
    todayReservations(band)
      .then((d) => { if (mine === seq.current) { setData(d); setError(null); } })
      .catch((e) => { if (mine === seq.current) setError(e instanceof ApiError ? e.message : 'Calendario non caricato: riprova.'); });
  }, [band]);

  // si aggiorna da solo: ogni minuto, al ritorno sulla scheda e quando un'altra pagina cambia una prenotazione
  useEffect(() => {
    load();
    const timer = window.setInterval(load, 60_000);
    const onFocus = () => load();
    window.addEventListener('focus', onFocus);
    window.addEventListener('fireflies:reservations-changed', onFocus);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('fireflies:reservations-changed', onFocus);
    };
  }, [load]);

  const total = (b: Band | null) => (b ? data?.bands.find((x) => x.band === b) : null);
  const all = data ? { reservations: data.bands.reduce((n, b) => n + b.reservations, 0), guests: data.bands.reduce((n, b) => n + b.guests, 0) } : null;

  return (
    <>
      <AdminHead title="Calendario prenotazioni oggi" lead={data ? `${fmtShort(data.date)} · solo prenotazioni confermate` : 'Solo prenotazioni confermate'}>
        <button type="button" className="btn btn--ghost btn--sm" onClick={load}><RefreshCw size={16} aria-hidden="true" /> Aggiorna</button>
      </AdminHead>
      <div className="chips" role="group" aria-label="Filtra per fascia">
        <button type="button" className="chip" aria-pressed={band === null} onClick={() => setBand(null)}>
          Tutte {all && <span className="count">{all.reservations}</span>}
        </button>
        {BANDS.map((b) => (
          <button key={b.id} type="button" className="chip" aria-pressed={band === b.id} onClick={() => setBand(b.id)}>
            {b.label} {data && <span className="count">{total(b.id)?.reservations ?? 0}</span>}
          </button>
        ))}
      </div>
      {error && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
      {!data && !error && <p className="muted" role="status">Caricamento…</p>}
      {data && (
        <>
          <p className="today__total" role="status">
            <Users size={18} aria-hidden="true" />
            <strong>{data.reservations} {data.reservations === 1 ? 'prenotazione' : 'prenotazioni'}</strong> · {data.guests} persone
            {band ? ` · ${BANDS.find((b) => b.id === band)?.label}` : ''}
          </p>
          {data.items.length === 0 ? (
            <div className="empty empty--admin">
              <CalendarCheck size={32} aria-hidden="true" />
              <p>Nessuna prenotazione confermata {band ? 'in questa fascia' : 'per oggi'}.</p>
            </div>
          ) : (
            <ol className="today" aria-label="Prenotazioni confermate di oggi">
              {data.items.map((r) => (
                <li key={r.id} className="today__item">
                  <time className="today__time" dateTime={`${data.date}T${r.time}`}>{hhmm(r.time)}</time>
                  <div className="today__who">
                    <strong>{r.name ?? 'Senza nome'}</strong>
                    <span className="muted small">{r.reference}{r.event ? ` · Serata «${r.event}»` : ''}</span>
                  </div>
                  <span className="today__guests"><Users size={16} aria-hidden="true" /> {r.guests} <span className="sr-only">persone</span></span>
                  <span className="badge badge--neutral today__band">{r.bandLabel}</span>
                </li>
              ))}
            </ol>
          )}
        </>
      )}
    </>
  );
}
