import { ChevronRight, CircleAlert, Inbox, Phone, Plus, RefreshCw, Search, Users } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ApiError } from '../../api/client';
import { listEvents, type AdminEvent } from '../../api/events';
import {
  changeStatus, createManual, getReservation, hhmm, listReservations, notificationLabel, resendNotification,
  REASON_LABEL, SOURCE_LABEL, STATUS_LABEL, TEMPLATE_LABEL, updateReservation,
  type Detail, type ListResult, type ReservationSource, type ReservationStatus, type StatusChange,
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
};

/** Messaggio leggibile per un errore dell'API (il backend scrive già in italiano). */
const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Si è verificato un errore imprevisto. Riprova.');

type Period = 'upcoming' | 'day' | 'all';

/**
 * Chip a scorrimento orizzontale controllato: sfumatura e freccia a destra finché c'è altro da vedere (FASE 8, req. 2).
 * La freccia scorre di una schermata; i chip restano raggiungibili da tastiera (Tab) e al tocco.
 */
export function ScrollChips({ label, children }: { label: string; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [more, setMore] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => setMore(el.scrollWidth - el.clientWidth - el.scrollLeft > 4);
    update();
    el.addEventListener('scroll', update, { passive: true });
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    ro?.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      ro?.disconnect();
    };
  }, [children]);
  return (
    <div className={`chips-scroll${more ? ' has-more' : ''}`}>
      <div ref={ref} className="chips" role="group" aria-label={label}>{children}</div>
      {more && (
        <button type="button" className="chips-scroll__more" aria-label="Mostra gli altri filtri"
          onClick={() => ref.current?.scrollBy({ left: ref.current.clientWidth * 0.7, behavior: 'smooth' })}>
          <ChevronRight size={18} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}

export function Requests() {
  usePageMeta('Richieste');
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
    window.dispatchEvent(new Event('fireflies:reservations-changed')); // calendario di oggi aperto in un'altra vista
  }, [dispatch]);

  const counts = data?.counts ?? {};
  const all = STATUSES.reduce((n, s) => n + (counts[s] ?? 0), 0);
  const rows = data?.items ?? [];

  return (
    <>
      <AdminHead compact title="Richieste" lead="Le richieste dal sito arrivano «In attesa»: confermale solo se c'è posto. Le notifiche ai clienti vengono preparate ma non ancora inviate.">
        <button type="button" className="btn btn--action btn--sm" onClick={() => setCreating(true)}><Plus size={16} aria-hidden="true" /> Nuova prenotazione</button>
      </AdminHead>

      {/* FASE 8, req. 2: filtri compatti (sul telefono Periodo e Origine affiancati, ricerca con Aggiorna a icona) */}
      <div className="toolbar toolbar--compact" role="search" aria-label="Filtra le richieste">
        <div className="field field--compact toolbar__period">
          <label htmlFor="rq-period">Periodo</label>
          <select id="rq-period" className="select select--sm" value={period} onChange={(e) => setPeriod(e.target.value as Period)}>
            <option value="upcoming">Da oggi in poi</option>
            <option value="day">Un giorno</option>
            <option value="all">Tutte, anche passate</option>
          </select>
        </div>
        <div className="field field--compact toolbar__source">
          <label htmlFor="rq-source">Origine</label>
          <select id="rq-source" className="select select--sm" value={source} onChange={(e) => setSource(e.target.value as ReservationSource | '')}>
            <option value="">Tutte</option>
            {(Object.keys(SOURCE_LABEL) as ReservationSource[]).map((s) => <option key={s} value={s}>{SOURCE_LABEL[s]}</option>)}
          </select>
        </div>
        {period === 'day' && (
          <div className="field field--compact toolbar__day">
            <label htmlFor="rq-day">Giorno</label>
            <input id="rq-day" className="input input--sm" type="date" value={day} onChange={(e) => setDay(e.target.value || todayIso())} />
          </div>
        )}
        <div className="field field--compact toolbar__search">
          <label htmlFor="rq-q">Cerca</label>
          <div className="toolbar__search-row">
            <span className="input-icon">
              <Search size={16} aria-hidden="true" />
              <input id="rq-q" className="input input--sm" type="search" placeholder="Nome, telefono, codice" value={query} onChange={(e) => setQuery(e.target.value)} />
            </span>
            <button type="button" className="icon-btn toolbar__refresh" onClick={reload} aria-label="Aggiorna l'elenco" title="Aggiorna l'elenco">
              <RefreshCw size={18} aria-hidden="true" className={loading ? 'spin' : undefined} />
            </button>
          </div>
        </div>
      </div>

      <ScrollChips label="Filtra per stato">
        <button type="button" className="chip" aria-pressed={tab === 'ALL'} onClick={() => setTab('ALL')}>
          Tutte<span className="count">{all}</span>
        </button>
        {STATUSES.map((s) => (
          <button key={s} type="button" className="chip" aria-pressed={tab === s} onClick={() => setTab(s)}>
            {STATUS_LABEL[s]}<span className="count">{counts[s] ?? 0}</span>
          </button>
        ))}
      </ScrollChips>

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
        <div aria-busy={loading}>
          {/* FASE 7, req. 5: ogni richiesta è una scheda separata (righe ben distinte su desktop, schede sul telefono) */}
          <ul className="res-list" aria-label="Richieste">
            {rows.map((r) => (
              <li key={r.id}>
                <article className={`res-card res-card--${r.status.toLowerCase()}`} aria-label={`${r.customerName}, ${fmtShort(r.date)} ${hhmm(r.time)}`}>
                  <div className="res-card__when">
                    <strong className="res-card__time">{hhmm(r.time)}</strong>
                    <span className="res-card__date">{fmtShort(r.date)}</span>
                  </div>
                  <div className="res-card__who">
                    <h3 className="res-card__name">{r.customerName}</h3>
                    <p className="res-card__meta small muted">{SOURCE_LABEL[r.source]} · {r.reference} · ricevuta {fmtStamp(r.createdAt)}</p>
                    {r.eventTitle && <p className="res-card__event small">Serata «{r.eventTitle}»</p>}
                    {r.notes && <p className="res-card__notes small">{r.notes}</p>}
                  </div>
                  <div className="res-card__contact">
                    <span className="res-card__guests"><Users size={16} aria-hidden="true" /> {r.guests} {r.guests === 1 ? 'persona' : 'persone'}</span>
                    <a href={`tel:${r.customerPhone}`} className="res-card__phone"><Phone size={14} aria-hidden="true" /> {r.customerPhone}</a>
                    {r.customerEmail && <span className="small res-card__email">{r.customerEmail}</span>}
                  </div>
                  <div className="res-card__status">
                    <ReservationBadge status={r.status} />
                    {r.needsCallback && <span className="badge badge--confirm">Da richiamare</span>}
                    <button type="button" className="btn btn--ghost btn--sm" onClick={() => setOpenId(r.id)} aria-label={`Gestisci la richiesta di ${r.customerName}`}>Gestisci</button>
                  </div>
                </article>
              </li>
            ))}
          </ul>
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

type Mode = 'view' | 'reject' | 'cancel' | 'edit';

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
          {s.eventTitle && <div><dt>Evento</dt><dd>{s.eventTitle}</dd></div>}
          <div><dt>Stato</dt><dd>{STATUS_LABEL[s.status]}{s.statusReason ? ` · ${REASON_LABEL[s.statusReason] ?? s.statusReason}` : ''}</dd></div>
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
            {can('CONFIRM') && <button type="button" className="btn btn--action btn--sm" disabled={busy} onClick={() => status({ status: 'CONFIRMED' }, 'Prenotazione confermata: posti occupati. Notifica preparata, non inviata.')}>Conferma</button>}
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

        <section className="res-detail__section" aria-labelledby="res-notif">
          <h3 id="res-notif">Notifiche</h3>
          {detail.notifications.length === 0 ? <p className="small muted">Nessuna notifica preparata.</p> : (
            <ul className="res-detail__list">
              {detail.notifications.map((n) => (
                <li key={n.id}>
                  <span>{TEMPLATE_LABEL[n.template] ?? n.template} · {n.channel === 'EMAIL' ? 'email' : 'SMS'} {n.recipient === 'STAFF' ? 'al locale' : 'al cliente'}</span>
                  <span className={`badge ${n.delivered ? 'badge--success' : 'badge--neutral'}`}>{notificationLabel(n)}</span>
                  {n.resendable && (
                    <button type="button" className="btn btn--quiet btn--sm" disabled={busy}
                      onClick={() => {
                        // FIX 3 (F-07): disabilitato subito, fino alla risposta: un doppio clic accodava due nuovi invii
                        if (busy) return;
                        setBusy(true);
                        void resendNotification(n.id).then(() => { notify('Nuovo invio in coda.'); load(); })
                          .catch((e) => setError(message(e))).finally(() => setBusy(false));
                      }}>
                      Invia di nuovo
                    </button>
                  )}
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
            {detail.changes.filter((c) => c.action === 'RESERVATION_UPDATED').map((c, i) => (
              <li key={`c${i}`}>
                <span>Dati modificati</span>
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
        <button type="submit" className="btn btn--action btn--sm" disabled={busy}>{kind === 'reject' ? 'Rifiuta la richiesta' : 'Annulla la prenotazione'}</button>
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
        <button type="submit" className="btn btn--action btn--sm" disabled={busy}>Salva le modifiche</button>
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
  // serate pubblicate su richiesta: una prenotazione per evento prende giorno e orario dall'evento
  const [events, setEvents] = useState<AdminEvent[]>([]);
  const [eventId, setEventId] = useState('');
  useEffect(() => {
    listEvents().then((list) => setEvents(list.filter((e) => e.status === 'PUBLISHED' && e.bookingMode === 'REQUEST'))).catch(() => undefined);
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const d = await createManual({ ...v, email: v.email || undefined, notes: v.notes || undefined, internalNote: v.internalNote || undefined,
        eventId: eventId || undefined });
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
        {events.length > 0 && (
          <div className="field">
            <label htmlFor="mn-event">Per una serata <span className="muted">(facoltativo)</span></label>
            <select id="mn-event" className="select" value={eventId} onChange={(e) => setEventId(e.target.value)}>
              <option value="">Nessuna: prenotazione ordinaria</option>
              {events.map((e) => <option key={e.id} value={e.id}>{e.title} · {e.date ? fmtShort(e.date) : ''} {e.startTime ? hhmm(e.startTime) : ''}</option>)}
            </select>
          </div>
        )}
        <div className="editor__row">
          {!eventId && <><div className="field"><label htmlFor="mn-date">Giorno</label><input id="mn-date" className="input" type="date" min={todayIso()} value={v.date} onChange={(e) => up('date', e.target.value)} {...invalid('date')} /></div>
          <div className="field"><label htmlFor="mn-time">Ora</label><input id="mn-time" className="input" type="time" step={900} value={v.time} onChange={(e) => up('time', e.target.value)} {...invalid('time')} /></div></>}
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
          <button type="submit" className="btn btn--action btn--sm" disabled={busy}>{busy ? 'Salvataggio…' : 'Salva la prenotazione'}</button>
          <button type="button" className="btn btn--quiet btn--sm" onClick={onClose}>Annulla</button>
        </div>
      </form>
    </Dialog>
  );
}
