import { CircleAlert, Inbox, Phone, Plus, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import {
  bookingConfig, changeStatus, changeTables, createManual, getReservation, hhmm, listReservations, notificationLabel,
  REASON_LABEL, SOURCE_LABEL, STATUS_LABEL, TEMPLATE_LABEL, updateReservation,
  type BookingConfig, type Detail, type ListResult, type ReservationSource, type ReservationStatus, type StatusChange,
} from '../../api/reservations';
import { Dialog } from '../../components/Dialog';
import { capitalize } from '../../data/events';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead, refreshPending } from './AdminLayout';

const STATUSES: ReservationStatus[] = ['PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'COMPLETED', 'NO_SHOW'];

const STATUS_CLASS: Record<ReservationStatus, string> = {
  PENDING: 'badge--action',
  CONFIRMED: 'badge--success',
  REJECTED: 'badge--neutral',
  CANCELLED: 'badge--neutral',
  COMPLETED: 'badge--info',
  NO_SHOW: 'badge--confirm',
};

export function ReservationBadge({ status }: { status: ReservationStatus }) {
  return <span className={`badge ${STATUS_CLASS[status]}`}>{STATUS_LABEL[status]}</span>;
}

const shortDate = new Intl.DateTimeFormat('it-IT', { weekday: 'short', day: 'numeric', month: 'short' });
export const fmtShort = (iso: string) => capitalize(shortDate.format(new Date(`${iso}T12:00:00`)));
const stamp = new Intl.DateTimeFormat('it-IT', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
export const fmtStamp = (iso: string) => stamp.format(new Date(iso));
const todayIso = () => {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 10);
};

const WARNING_LABEL: Record<string, string> = {
  OUTSIDE_SLOTS: "L'orario non è uno degli arrivi previsti dalla fascia.",
  DAY_CLOSED: 'In quella data il locale risulta chiuso agli arrivi.',
  SHORT_NOTICE: "Arrivo a breve: meno dell'anticipo minimo previsto per il sito.",
  MIN_SEATS_ABOVE_PARTY: 'I tavoli scelti sono pensati per più persone.',
};

/** Messaggio leggibile per un errore dell'API (il backend scrive già in italiano). */
const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Si è verificato un errore imprevisto. Riprova.');

type Period = 'upcoming' | 'day' | 'all';

export function Requests() {
  usePageMeta('Richieste tavoli');
  const { dispatch } = useStore();
  const [tab, setTab] = useState<ReservationStatus | 'ALL'>('ALL');
  const [period, setPeriod] = useState<Period>('upcoming');
  const [day, setDay] = useState(todayIso());
  const [source, setSource] = useState<ReservationSource | ''>('');
  const [query, setQuery] = useState('');
  const [q, setQ] = useState('');
  const [data, setData] = useState<ListResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [tick, setTick] = useState(0);

  // ricerca: si aspetta che si smetta di scrivere
  useEffect(() => {
    const t = window.setTimeout(() => setQ(query), 300);
    return () => window.clearTimeout(t);
  }, [query]);

  useEffect(() => {
    let current = true;
    setLoading(true);
    listReservations({
      from: period === 'upcoming' ? todayIso() : period === 'day' ? day : undefined,
      to: period === 'day' ? day : undefined,
      status: tab === 'ALL' ? undefined : [tab],
      source,
      q,
    })
      .then((r) => {
        if (!current) return;
        setData(r);
        setError(null);
      })
      .catch((e) => current && setError(message(e)))
      .finally(() => current && setLoading(false));
    return () => {
      current = false;
    };
  }, [tab, period, day, source, q, tick]);

  const reload = useCallback(() => {
    setTick((n) => n + 1);
    void refreshPending(dispatch);
  }, [dispatch]);

  const counts = data?.counts ?? {};
  const all = STATUSES.reduce((n, s) => n + (counts[s] ?? 0), 0);
  const rows = data?.items ?? [];

  return (
    <>
      <AdminHead title="Richieste tavoli" lead="Le richieste dal sito arrivano «In attesa»: confermale solo se c'è posto. Le notifiche ai clienti vengono preparate ma non ancora inviate.">
        <button type="button" className="btn btn--primary btn--sm" onClick={() => setCreating(true)}><Plus size={16} aria-hidden="true" /> Nuova prenotazione</button>
      </AdminHead>

      <div className="toolbar" role="search" aria-label="Filtra le richieste">
        <div className="field field--inline">
          <label htmlFor="rq-period">Periodo</label>
          <select id="rq-period" className="select select--sm" value={period} onChange={(e) => setPeriod(e.target.value as Period)}>
            <option value="upcoming">Da oggi in poi</option>
            <option value="day">Un giorno</option>
            <option value="all">Tutte, anche passate</option>
          </select>
        </div>
        {period === 'day' && (
          <div className="field field--inline">
            <label htmlFor="rq-day">Giorno</label>
            <input id="rq-day" className="input input--sm" type="date" value={day} onChange={(e) => setDay(e.target.value || todayIso())} />
          </div>
        )}
        <div className="field field--inline">
          <label htmlFor="rq-source">Origine</label>
          <select id="rq-source" className="select select--sm" value={source} onChange={(e) => setSource(e.target.value as ReservationSource | '')}>
            <option value="">Tutte</option>
            {(Object.keys(SOURCE_LABEL) as ReservationSource[]).map((s) => <option key={s} value={s}>{SOURCE_LABEL[s]}</option>)}
          </select>
        </div>
        <div className="field field--inline">
          <label htmlFor="rq-q">Cerca</label>
          <input id="rq-q" className="input input--sm" type="search" placeholder="Nome, telefono, codice" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <button type="button" className="btn btn--quiet btn--sm" onClick={reload} aria-label="Aggiorna l'elenco"><RefreshCw size={16} aria-hidden="true" /> Aggiorna</button>
      </div>

      <div className="chips" role="group" aria-label="Filtra per stato">
        <button type="button" className="chip" aria-pressed={tab === 'ALL'} onClick={() => setTab('ALL')}>
          Tutte<span className="count">{all}</span>
        </button>
        {STATUSES.map((s) => (
          <button key={s} type="button" className="chip" aria-pressed={tab === s} onClick={() => setTab(s)}>
            {STATUS_LABEL[s]}<span className="count">{counts[s] ?? 0}</span>
          </button>
        ))}
      </div>

      {error ? (
        <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error} <button type="button" className="btn btn--quiet btn--sm" onClick={reload}>Riprova</button></p>
      ) : !data ? (
        <p className="muted" role="status">Caricamento delle richieste…</p>
      ) : rows.length === 0 ? (
        <div className="empty empty--admin">
          <Inbox size={36} aria-hidden="true" />
          <h2>Nessuna richiesta{tab === 'ALL' ? '' : ` «${STATUS_LABEL[tab]}»`}</h2>
          <p className="muted">Quando arriva una richiesta dal sito la trovi qui. Quelle al telefono le inserisci con «Nuova prenotazione».</p>
        </div>
      ) : (
        <div className="table-wrap" aria-busy={loading}>
          <table className="table">
            <caption className="sr-only">Richieste tavoli</caption>
            <thead>
              <tr>
                <th scope="col">Quando</th>
                <th scope="col">Persone</th>
                <th scope="col">Nome</th>
                <th scope="col">Contatto</th>
                <th scope="col">Note</th>
                <th scope="col">Stato</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td data-label="Quando">
                    <strong>{fmtShort(r.date)} · {hhmm(r.time)}</strong><br />
                    <span className="small muted">{SOURCE_LABEL[r.source]} · {r.reference} · ricevuta {fmtStamp(r.createdAt)}</span>
                  </td>
                  <td data-label="Persone">{r.guests}</td>
                  <td data-label="Nome">{r.customerName}{r.needsCallback && <><br /><span className="badge badge--confirm">Da richiamare</span></>}</td>
                  <td data-label="Contatto">
                    <a href={`tel:${r.customerPhone}`}>{r.customerPhone}</a>
                    {r.customerEmail && <><br /><span className="small">{r.customerEmail}</span></>}
                  </td>
                  <td data-label="Note">{r.notes || <span className="muted">—</span>}{r.tables.length > 0 && <><br /><span className="small muted">Tavoli {r.tables.join(', ')}</span></>}</td>
                  <td data-label="Stato">
                    <div className="status-cell">
                      <ReservationBadge status={r.status} />
                      <button type="button" className="btn btn--ghost btn--sm" onClick={() => setOpenId(r.id)} aria-label={`Gestisci la richiesta di ${r.customerName}`}>Gestisci</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.total > rows.length && <p className="small muted">Mostrate {rows.length} di {data.total}: restringi i filtri per vedere le altre.</p>}
        </div>
      )}
      <p className="small muted note">Email e SMS ai clienti non partono ancora: le notifiche vengono preparate e mostrate nel dettaglio come «preparate, non inviate». L'invio reale arriverà in una fase successiva, con un fornitore approvato.</p>

      {openId && <ReservationDialog id={openId} onClose={() => setOpenId(null)} onChanged={reload} />}
      {creating && <ManualDialog onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); reload(); setOpenId(id); }} />}
    </>
  );
}

// =============================================================================================================
// Dettaglio e azioni
// =============================================================================================================

type Mode = 'view' | 'reject' | 'cancel' | 'edit' | 'tables';

function ReservationDialog({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { notify, role } = useStore();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mode, setMode] = useState<Mode>('view');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    getReservation(id).then((d) => { setDetail(d); setLoadError(null); }).catch((e) => setLoadError(message(e)));
  }, [id]);
  useEffect(load, [load]);

  const run = async (op: () => Promise<Detail>, done: string) => {
    setBusy(true);
    setError(null);
    try {
      const d = await op();
      setDetail(d);
      setMode('view');
      notify(done);
      onChanged();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') {
        setError('Qualcun altro ha appena modificato questa prenotazione: ecco i dati aggiornati, controlla e riprova.');
        setMode('view');
        load();
        onChanged();
      } else {
        setError(message(e));
      }
    } finally {
      setBusy(false);
    }
  };

  const status = (body: Omit<StatusChange, 'version'>, done: string) =>
    detail && run(() => changeStatus(id, { version: detail.summary.version, ...body }), done);

  if (!detail) {
    return (
      <Dialog title="Richiesta" onClose={onClose}>
        {loadError ? <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {loadError}</p> : <p className="muted" role="status">Caricamento…</p>}
      </Dialog>
    );
  }

  const s = detail.summary;
  const can = (a: Detail['actions'][number]) => detail.actions.includes(a);
  const title = `${s.customerName} · ${fmtShort(s.date)} ${hhmm(s.time)}`;

  return (
    <Dialog title={title} onClose={onClose} wide headExtra={<ReservationBadge status={s.status} />}>
      <div className="res-detail" aria-busy={busy}>
        {error && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
        {detail.warnings.length > 0 && (
          <ul className="hint warn res-detail__warnings">{detail.warnings.map((w) => <li key={w}>{WARNING_LABEL[w] ?? w}</li>)}</ul>
        )}

        <dl className="booking-recap res-detail__facts">
          <div><dt>Quando</dt><dd>{fmtShort(s.date)} · {hhmm(s.time)}–{hhmm(s.endTime)}</dd></div>
          <div><dt>Persone</dt><dd>{s.guests}</dd></div>
          <div><dt>Telefono</dt><dd><a href={`tel:${s.customerPhone}`}><Phone size={14} aria-hidden="true" /> {s.customerPhone}</a></dd></div>
          <div><dt>Email</dt><dd>{s.customerEmail ?? '—'}</dd></div>
          <div><dt>Origine</dt><dd>{SOURCE_LABEL[s.source]} · codice {s.reference}</dd></div>
          <div><dt>Stato</dt><dd>{STATUS_LABEL[s.status]}{s.statusReason ? ` · ${REASON_LABEL[s.statusReason] ?? s.statusReason}` : ''}</dd></div>
          <div><dt>Zona e tavoli</dt><dd>{detail.zoneName ?? '—'}{detail.tables.length ? ` · ${detail.tables.map((t) => `${t.code} (${t.maxSeats} posti${t.seatsOverride ? ', eccezione' : ''})`).join(', ')}` : ' · nessun tavolo'}</dd></div>
          <div><dt>Note del cliente</dt><dd>{detail.notes ?? '—'}</dd></div>
          <div><dt>Nota interna</dt><dd>{detail.internalNote ?? '—'}</dd></div>
        </dl>

        {detail.capacity && (
          <p className={`res-detail__capacity${detail.capacity.fits ? '' : ' is-full'}`} role="note">
            {s.status === 'CONFIRMED'
              ? `Posti già occupati nel registro. Liberi nell'orario, oltre a questa: almeno ${detail.capacity.minFree} su ${detail.capacity.capacity}.`
              : detail.capacity.eventBlocks
                ? "In quell'orario c'è un evento riservato: non si può confermare."
                : detail.capacity.fits
                  ? `C'è posto: almeno ${detail.capacity.minFree} posti liberi su ${detail.capacity.capacity} in tutto l'orario.`
                  : `Non ci sta: nell'orario restano al massimo ${detail.capacity.minFree} posti liberi su ${detail.capacity.capacity}.`}
            {detail.capacity.pendingOthers > 0 && ` Altre richieste in attesa nello stesso orario: ${detail.capacity.pendingOthers} persone.`}
          </p>
        )}

        {mode === 'view' && (
          <div className="res-detail__actions">
            {can('CONFIRM') && <button type="button" className="btn btn--primary btn--sm" disabled={busy} onClick={() => status({ status: 'CONFIRMED' }, 'Prenotazione confermata: posti occupati. Notifica preparata, non inviata.')}>Conferma</button>}
            {can('REJECT') && <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={() => setMode('reject')}>Rifiuta</button>}
            {can('CANCEL') && <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={() => setMode('cancel')}>Annulla</button>}
            {can('COMPLETE') && <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={() => status({ status: 'COMPLETED' }, 'Segnata come completata.')}>Completata</button>}
            {can('NO_SHOW') && <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={() => status({ status: 'NO_SHOW' }, 'Segnata come «non presentato»: i posti futuri tornano liberi.')}>Non presentato</button>}
            {can('CORRECT') && role === 'titolare' && (
              <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                onClick={() => status({ status: s.status === 'NO_SHOW' ? 'COMPLETED' : 'NO_SHOW' }, 'Stato corretto.')}>
                Correggi in «{s.status === 'NO_SHOW' ? 'Completata' : 'Non presentato'}»
              </button>
            )}
            {(can('EDIT') || can('NOTES')) && <button type="button" className="btn btn--quiet btn--sm" disabled={busy} onClick={() => setMode('edit')}>{can('EDIT') ? 'Modifica' : 'Modifica note e recapiti'}</button>}
            {can('TABLES') && <button type="button" className="btn btn--quiet btn--sm" disabled={busy} onClick={() => setMode('tables')}>Tavoli</button>}
          </div>
        )}

        {(mode === 'reject' || mode === 'cancel') && (
          <ReasonForm kind={mode} busy={busy} onCancel={() => setMode('view')}
            onSubmit={(reason, note) => status({ status: mode === 'reject' ? 'REJECTED' : 'CANCELLED', reason, note },
              mode === 'reject' ? 'Richiesta rifiutata.' : 'Prenotazione annullata: i posti sono di nuovo liberi.')} />
        )}
        {mode === 'edit' && (
          <EditForm detail={detail} full={can('EDIT')} busy={busy} onCancel={() => setMode('view')}
            onSubmit={(body) => run(() => updateReservation(id, { version: s.version, ...body }), 'Modifiche salvate.')} />
        )}
        {mode === 'tables' && (
          <TablesForm detail={detail} owner={role === 'titolare'} busy={busy} onCancel={() => setMode('view')}
            onSubmit={(tableIds, overrideSeats, overrideReason) =>
              run(() => changeTables(id, { version: s.version, tableIds, overrideSeats, overrideReason }), 'Tavoli aggiornati.')} />
        )}

        <section className="res-detail__section" aria-labelledby="res-notif">
          <h3 id="res-notif">Notifiche</h3>
          {detail.notifications.length === 0 ? <p className="small muted">Nessuna notifica preparata.</p> : (
            <ul className="res-detail__list">
              {detail.notifications.map((n, i) => (
                <li key={i}>
                  <span>{TEMPLATE_LABEL[n.template] ?? n.template} · {n.channel === 'EMAIL' ? 'email' : 'SMS'} {n.recipient === 'STAFF' ? 'al locale' : 'al cliente'}</span>
                  <span className={`badge ${n.delivered ? 'badge--success' : 'badge--neutral'}`}>{notificationLabel(n)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="res-detail__section" aria-labelledby="res-hist">
          <h3 id="res-hist">Storico</h3>
          <ol className="res-detail__list">
            {detail.history.map((h, i) => (
              <li key={i}>
                <span>{h.fromStatus ? `${STATUS_LABEL[h.fromStatus]} → ` : ''}<strong>{STATUS_LABEL[h.toStatus]}</strong>{h.reason ? ` · ${REASON_LABEL[h.reason] ?? h.reason}` : ''}{h.note ? ` · «${h.note}»` : ''}</span>
                <span className="small muted">{fmtStamp(h.at)} · {h.by ?? (s.source === 'WEB' && !h.fromStatus ? 'dal sito' : 'automatico')}</span>
              </li>
            ))}
            {detail.changes.filter((c) => c.action === 'RESERVATION_UPDATED' || c.action === 'RESERVATION_TABLES_ASSIGNED' || c.action === 'TABLE_SEATS_OVERRIDE').map((c, i) => (
              <li key={`c${i}`}>
                <span>{c.action === 'RESERVATION_UPDATED' ? 'Dati modificati' : c.action === 'TABLE_SEATS_OVERRIDE' ? 'Eccezione sui posti del tavolo' : 'Tavoli assegnati'}</span>
                <span className="small muted">{fmtStamp(c.at)} · {c.by ?? '—'}</span>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </Dialog>
  );
}

function ReasonForm({ kind, busy, onSubmit, onCancel }: { kind: 'reject' | 'cancel'; busy: boolean; onSubmit: (reason: string, note: string) => void; onCancel: () => void }) {
  const reasons = kind === 'reject' ? ['NO_CAPACITY', 'VENUE_DECISION', 'DUPLICATE', 'OTHER'] : ['CUSTOMER_REQUEST', 'VENUE_DECISION', 'DUPLICATE', 'OTHER'];
  const [reason, setReason] = useState(reasons[0]);
  const [note, setNote] = useState('');
  return (
    <form className="res-detail__form" onSubmit={(e) => { e.preventDefault(); onSubmit(reason, note); }}>
      <div className="field">
        <label htmlFor="res-reason">Motivo</label>
        <select id="res-reason" className="select" value={reason} onChange={(e) => setReason(e.target.value)}>
          {reasons.map((r) => <option key={r} value={r}>{REASON_LABEL[r]}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="res-note">Nota <span className="muted">(facoltativa, resta nello storico)</span></label>
        <input id="res-note" className="input" maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} />
      </div>
      <div className="res-detail__actions">
        <button type="submit" className="btn btn--primary btn--sm" disabled={busy}>{kind === 'reject' ? 'Rifiuta la richiesta' : 'Annulla la prenotazione'}</button>
        <button type="button" className="btn btn--quiet btn--sm" onClick={onCancel}>Indietro</button>
      </div>
    </form>
  );
}

type EditBody = { date?: string; time?: string; durationMinutes?: number; guests?: number; name?: string; phone?: string; email?: string; notes?: string; internalNote?: string; needsCallback?: boolean };

function EditForm({ detail, full, busy, onSubmit, onCancel }: { detail: Detail; full: boolean; busy: boolean; onSubmit: (b: EditBody) => void; onCancel: () => void }) {
  const s = detail.summary;
  const [v, setV] = useState({
    date: s.date, time: hhmm(s.time), duration: detail.durationMinutes, guests: s.guests, name: s.customerName, phone: s.customerPhone,
    email: s.customerEmail ?? '', notes: detail.notes ?? '', internalNote: detail.internalNote ?? '', needsCallback: s.needsCallback,
  });
  const up = <K extends keyof typeof v>(k: K, val: (typeof v)[K]) => setV((x) => ({ ...x, [k]: val }));
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const body: EditBody = { name: v.name, phone: v.phone, email: v.email, notes: v.notes, internalNote: v.internalNote, needsCallback: v.needsCallback };
    if (full) Object.assign(body, { date: v.date, time: v.time, durationMinutes: v.duration, guests: v.guests });
    onSubmit(body);
  };
  return (
    <form className="res-detail__form" onSubmit={submit} noValidate>
      {full && (
        <div className="editor__row">
          <div className="field"><label htmlFor="ed-date">Giorno</label><input id="ed-date" className="input" type="date" value={v.date} onChange={(e) => up('date', e.target.value)} /></div>
          <div className="field"><label htmlFor="ed-time">Ora</label><input id="ed-time" className="input" type="time" step={900} value={v.time} onChange={(e) => up('time', e.target.value)} /></div>
          <div className="field"><label htmlFor="ed-dur">Durata (min)</label><input id="ed-dur" className="input" type="number" min={15} step={15} value={v.duration} onChange={(e) => up('duration', Number(e.target.value))} /></div>
          <div className="field"><label htmlFor="ed-guests">Persone</label><input id="ed-guests" className="input" type="number" min={1} max={50} value={v.guests} onChange={(e) => up('guests', Number(e.target.value))} /></div>
        </div>
      )}
      <div className="editor__row">
        <div className="field"><label htmlFor="ed-name">Nome</label><input id="ed-name" className="input" value={v.name} onChange={(e) => up('name', e.target.value)} /></div>
        <div className="field"><label htmlFor="ed-phone">Telefono</label><input id="ed-phone" className="input" type="tel" value={v.phone} onChange={(e) => up('phone', e.target.value)} /></div>
        <div className="field"><label htmlFor="ed-email">Email</label><input id="ed-email" className="input" type="email" value={v.email} onChange={(e) => up('email', e.target.value)} /></div>
      </div>
      <div className="field"><label htmlFor="ed-notes">Note del cliente</label><textarea id="ed-notes" className="textarea" maxLength={500} value={v.notes} onChange={(e) => up('notes', e.target.value)} /></div>
      <div className="field"><label htmlFor="ed-internal">Nota interna <span className="muted">(solo gestori)</span></label><textarea id="ed-internal" className="textarea" maxLength={500} value={v.internalNote} onChange={(e) => up('internalNote', e.target.value)} /></div>
      <label className="check"><input type="checkbox" checked={v.needsCallback} onChange={(e) => up('needsCallback', e.target.checked)} /><span>Da richiamare</span></label>
      {full && s.status === 'CONFIRMED' && <p className="hint">Spostando orario o persone il sistema ricontrolla i posti: se non bastano, nulla cambia.</p>}
      <div className="res-detail__actions">
        <button type="submit" className="btn btn--primary btn--sm" disabled={busy}>Salva le modifiche</button>
        <button type="button" className="btn btn--quiet btn--sm" onClick={onCancel}>Indietro</button>
      </div>
    </form>
  );
}

function TablesForm({ detail, owner, busy, onSubmit, onCancel }: { detail: Detail; owner: boolean; busy: boolean; onSubmit: (ids: string[], override: boolean, reason: string) => void; onCancel: () => void }) {
  const [config, setConfig] = useState<BookingConfig | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<string[]>(detail.tables.map((t) => t.tableId));
  const [override, setOverride] = useState(detail.tables.some((t) => t.seatsOverride));
  const [reason, setReason] = useState(detail.tables.find((t) => t.overrideReason)?.overrideReason ?? '');
  useEffect(() => {
    bookingConfig().then(setConfig).catch((e) => setError(message(e)));
  }, []);
  if (error) return <p className="error" role="alert">{error}</p>;
  if (!config) return <p className="muted" role="status">Caricamento dei tavoli…</p>;
  const tables = config.tables.filter((t) => t.active && t.zoneId === detail.summary.zoneId);
  const areaName = (id: string) => config.areas.find((a) => a.id === id)?.name ?? '';
  const seats = tables.filter((t) => chosen.includes(t.id)).reduce((n, t) => n + t.maxSeats, 0);
  const short = chosen.length > 0 && seats < detail.summary.guests;
  return (
    <form className="res-detail__form" onSubmit={(e) => { e.preventDefault(); onSubmit(chosen, short && override, reason); }}>
      {tables.length === 0 ? <p className="muted">Nessun tavolo in questa zona: il titolare li aggiunge in «Capienza e tavoli».</p> : (
        <fieldset className="field">
          <legend>Tavoli per {detail.summary.guests} persone (scelta manuale)</legend>
          <div className="res-tables">
            {tables.map((t) => (
              <label key={t.id} className="check check--sm">
                <input type="checkbox" checked={chosen.includes(t.id)} onChange={(e) => setChosen((c) => (e.target.checked ? [...c, t.id] : c.filter((x) => x !== t.id)))} />
                <span>{t.code} · {areaName(t.areaId)} · {t.maxSeats} posti</span>
              </label>
            ))}
          </div>
          <p className="hint">Posti scelti: {seats}. Il sistema non assegna tavoli da solo e blocca i tavoli già occupati in quell'orario.</p>
        </fieldset>
      )}
      {short && (owner ? (
        <>
          <label className="check"><input type="checkbox" checked={override} onChange={(e) => setOverride(e.target.checked)} /><span>Eccezione del titolare: i posti sono meno delle persone</span></label>
          {override && <div className="field"><label htmlFor="tb-reason">Motivo dell'eccezione</label><input id="tb-reason" className="input" maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Es. aggiungiamo due sgabelli" /></div>}
        </>
      ) : <p className="hint warn">I posti sono meno delle persone: solo il titolare può fare un'eccezione.</p>)}
      <div className="res-detail__actions">
        <button type="submit" className="btn btn--primary btn--sm" disabled={busy}>{chosen.length ? 'Assegna i tavoli' : 'Togli i tavoli'}</button>
        <button type="button" className="btn btn--quiet btn--sm" onClick={onCancel}>Indietro</button>
      </div>
    </form>
  );
}

// =============================================================================================================
// Inserimento manuale (telefono, email)
// =============================================================================================================

function ManualDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const { notify } = useStore();
  const [v, setV] = useState({
    source: 'PHONE' as 'PHONE' | 'EMAIL', date: todayIso(), time: '19:00', guests: 2, name: '', phone: '', email: '', notes: '',
    internalNote: '', confirmNow: false, needsCallback: false,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ field: string | null; text: string } | null>(null);
  const up = <K extends keyof typeof v>(k: K, val: (typeof v)[K]) => setV((x) => ({ ...x, [k]: val }));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const d = await createManual({ ...v, email: v.email || undefined, notes: v.notes || undefined, internalNote: v.internalNote || undefined });
      const warn = d.warnings.map((w) => WARNING_LABEL[w] ?? w).join(' ');
      notify(`${d.summary.status === 'CONFIRMED' ? 'Prenotazione confermata' : 'Richiesta inserita in attesa'} (${d.summary.reference}).${warn ? ` Attenzione: ${warn}` : ''}`);
      onCreated(d.summary.id);
    } catch (err) {
      const field = err instanceof ApiError ? (err.problem as { context?: { field?: string } } | undefined)?.context?.field ?? null : null;
      setError({ field, text: message(err) });
      if (field) requestAnimationFrame(() => document.getElementById(`mn-${field}`)?.focus());
    } finally {
      setBusy(false);
    }
  };
  const invalid = (f: string) => (error?.field === f ? { 'aria-invalid': true as const, 'aria-describedby': 'mn-err' } : {});

  return (
    <Dialog title="Nuova prenotazione" onClose={onClose} wide>
      <form className="res-detail__form" onSubmit={submit} noValidate aria-busy={busy}>
        {error && <p className="error" id="mn-err" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error.text}</p>}
        <fieldset className="field">
          <legend>Arrivata</legend>
          <div className="radio-row">
            <label className="check"><input type="radio" name="mn-source" checked={v.source === 'PHONE'} onChange={() => up('source', 'PHONE')} /><span>Al telefono</span></label>
            <label className="check"><input type="radio" name="mn-source" checked={v.source === 'EMAIL'} onChange={() => up('source', 'EMAIL')} /><span>Per email</span></label>
          </div>
        </fieldset>
        <div className="editor__row">
          <div className="field"><label htmlFor="mn-date">Giorno</label><input id="mn-date" className="input" type="date" min={todayIso()} value={v.date} onChange={(e) => up('date', e.target.value)} {...invalid('date')} /></div>
          <div className="field"><label htmlFor="mn-time">Ora</label><input id="mn-time" className="input" type="time" step={900} value={v.time} onChange={(e) => up('time', e.target.value)} {...invalid('time')} /></div>
          <div className="field"><label htmlFor="mn-guests">Persone</label><input id="mn-guests" className="input" type="number" min={1} max={50} value={v.guests} onChange={(e) => up('guests', Number(e.target.value))} {...invalid('guests')} /></div>
        </div>
        <div className="editor__row">
          <div className="field"><label htmlFor="mn-name">Nome</label><input id="mn-name" className="input" value={v.name} onChange={(e) => up('name', e.target.value)} {...invalid('name')} /></div>
          <div className="field"><label htmlFor="mn-phone">Telefono</label><input id="mn-phone" className="input" type="tel" value={v.phone} onChange={(e) => up('phone', e.target.value)} {...invalid('phone')} /></div>
          <div className="field"><label htmlFor="mn-email">Email <span className="muted">(facoltativa)</span></label><input id="mn-email" className="input" type="email" value={v.email} onChange={(e) => up('email', e.target.value)} {...invalid('email')} /></div>
        </div>
        <div className="field"><label htmlFor="mn-notes">Note del cliente</label><textarea id="mn-notes" className="textarea" maxLength={500} value={v.notes} onChange={(e) => up('notes', e.target.value)} /></div>
        <div className="field"><label htmlFor="mn-internal">Nota interna <span className="muted">(solo gestori)</span></label><textarea id="mn-internal" className="textarea" maxLength={500} value={v.internalNote} onChange={(e) => up('internalNote', e.target.value)} /></div>
        <label className="check"><input type="checkbox" checked={v.confirmNow} onChange={(e) => up('confirmNow', e.target.checked)} /><span>Conferma subito (il sistema controlla i posti)</span></label>
        <label className="check"><input type="checkbox" checked={v.needsCallback} onChange={(e) => up('needsCallback', e.target.checked)} /><span>Da richiamare</span></label>
        <div className="res-detail__actions">
          <button type="submit" className="btn btn--primary btn--sm" disabled={busy}>{busy ? 'Salvataggio…' : 'Salva la prenotazione'}</button>
          <button type="button" className="btn btn--quiet btn--sm" onClick={onClose}>Annulla</button>
        </div>
      </form>
    </Dialog>
  );
}
