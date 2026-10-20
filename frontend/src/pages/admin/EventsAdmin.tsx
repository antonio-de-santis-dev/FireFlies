import { Ban, CircleAlert, Eye, Pencil, Plus, Save, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import {
  cancelEvent, createEvent, deleteEvent, EVENT_STATUS_LABEL, listEvents, publishEvent, updateEvent,
  type AdminEvent, type EventConflict, type EventInput, type EventStatus,
} from '../../api/events';
import { hhmm } from '../../api/reservations';
import { Img } from '../../components/ui';
import { formatEventDate } from '../../data/events';
import { Link, navigate, usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { PhotoPicker } from './media';
import { Switch } from './MenuAdmin';

const STATUS_CLASS: Record<EventStatus, string> = { DRAFT: 'badge--info', PUBLISHED: 'badge--success', CANCELLED: 'badge--neutral' };
const StatusPill = ({ s }: { s: EventStatus }) => <span className={`badge ${STATUS_CLASS[s]}`}>{EVENT_STATUS_LABEL[s]}</span>;
const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Si è verificato un errore imprevisto. Riprova.');
const when = (e: AdminEvent) => formatEventDate({ date: e.date, time: e.startTime ? hhmm(e.startTime) : null }) + (e.endTime ? ` – ${hhmm(e.endTime)}` : '');

/** Elenco eventi dal backend (i gestori vedono anche bozze e annullati). */
function useEvents() {
  const [events, setEvents] = useState<AdminEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reload = useCallback(() => {
    listEvents().then((e) => { setEvents(e); setError(null); }).catch((e) => setError(message(e)));
  }, []);
  useEffect(reload, [reload]);
  return { events, error, reload };
}

export function EventsAdmin() {
  usePageMeta('Gestione eventi');
  const { mediaOr } = useStore();
  const { events, error } = useEvents();
  const [tab, setTab] = useState<'ALL' | EventStatus>('ALL');
  const all = events ?? [];
  const rows = all.filter((e) => tab === 'ALL' || e.status === tab);
  return (
    <>
      <AdminHead title="Eventi" lead="Serate e format. Le bozze non compaiono sul sito; una serata esclusiva riserva il locale nel suo orario.">
        <Link to="/gestione/eventi/nuovo" className="btn btn--action"><Plus size={20} aria-hidden="true" /> Nuova bozza</Link>
      </AdminHead>
      <div className="chips" role="group" aria-label="Filtra per stato">
        {(['ALL', 'PUBLISHED', 'DRAFT', 'CANCELLED'] as const).map((t) => (
          <button key={t} type="button" className="chip" aria-pressed={tab === t} onClick={() => setTab(t)}>
            {t === 'ALL' ? 'Tutti' : t === 'PUBLISHED' ? 'Pubblicati' : t === 'DRAFT' ? 'Bozze' : 'Annullati'}
            <span className="count">{all.filter((e) => t === 'ALL' || e.status === t).length}</span>
          </button>
        ))}
      </div>
      {error && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
      {!events && !error && <p className="muted" role="status">Caricamento degli eventi…</p>}
      <ul className="ev-admin">
        {rows.map((e) => (
          <li key={e.id} className="ev-admin__item">
            <Img asset={mediaOr(e.poster?.id ?? e.media[0]?.id)} decorative className="ev-admin__img" />
            <div className="ev-admin__body">
              <p className="ev-admin__badges">
                <StatusPill s={e.status} />
                {e.kind === 'FORMAT' && <span className="badge badge--neutral">Format</span>}
                {e.kind === 'SERATA' && <span className="badge badge--neutral">{e.exclusive ? 'Esclusiva' : 'Posti condivisi'}</span>}
                {e.slug.endsWith('-esempio') && <span className="badge badge--demo">Esempio</span>}
              </p>
              <h2>{e.title}</h2>
              <p className="muted small">
                {when(e)}{e.artist ? ` · ${e.artist}` : ''}
                {e.bookingMode === 'REQUEST' && ` · ${e.confirmedCovers}/${e.capacityCovers} posti confermati${e.pendingReservations ? `, ${e.pendingReservations} richieste in attesa` : ''}`}
              </p>
            </div>
            <div className="ev-admin__actions">
              {e.status !== 'DRAFT' && (
                <Link to={`/eventi/${e.slug}`} className="btn btn--quiet btn--sm" aria-label={`Vedi sul sito: ${e.title}`}><Eye size={16} aria-hidden="true" /> Sul sito</Link>
              )}
              <Link to={`/gestione/eventi/${e.id}`} className="btn btn--ghost btn--sm" aria-label={`Modifica ${e.title}`}><Pencil size={16} aria-hidden="true" /> Modifica</Link>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}

type Form = {
  title: string; kind: 'SERATA' | 'FORMAT'; date: string; startTime: string; endTime: string; artist: string; summary: string;
  body: string; bookingMode: 'NONE' | 'REQUEST'; capacity: string; exclusive: boolean; poster: string | null;
};

const blank = (): Form => ({
  title: '', kind: 'SERATA', date: '', startTime: '', endTime: '', artist: '', summary: '', body: '', bookingMode: 'NONE',
  capacity: '', exclusive: true, poster: null,
});

const formOf = (e: AdminEvent): Form => ({
  title: e.title, kind: e.kind, date: e.date ?? '', startTime: e.startTime ? hhmm(e.startTime) : '', endTime: e.endTime ? hhmm(e.endTime) : '',
  artist: e.artist ?? '', summary: e.summary ?? '', body: e.body ?? '', bookingMode: e.bookingMode,
  capacity: e.capacityCovers == null ? '' : String(e.capacityCovers), exclusive: e.exclusive, poster: e.poster?.id ?? null,
});

const input = (f: Form, existing: AdminEvent | null): EventInput => ({
  title: f.title.trim(), kind: f.kind, date: f.date || null, startTime: f.startTime || null, endTime: f.endTime || null,
  exclusive: f.exclusive, bookingMode: f.bookingMode, capacityCovers: f.capacity ? Number(f.capacity) : null, artist: f.artist,
  summary: f.summary, body: f.body, posterMediaId: f.poster,
  mediaIds: [...new Set([f.poster, ...(existing?.media.map((m) => m.id) ?? [])].filter((x): x is string => !!x))],
});

type Problem = { text: string; field?: string; confirmed?: EventConflict[]; pending?: EventConflict[]; acknowledgeable?: boolean };

function problemOf(e: unknown): Problem {
  if (!(e instanceof ApiError)) return { text: message(e) };
  const ctx = (e.problem as { context?: Record<string, unknown> } | undefined)?.context ?? {};
  return {
    text: e.message, field: typeof ctx.field === 'string' ? ctx.field : undefined,
    confirmed: ctx.confirmed as EventConflict[] | undefined, pending: ctx.pending as EventConflict[] | undefined,
    acknowledgeable: ctx.acknowledgeable === true,
  };
}

export function EventEditor({ id }: { id: string }) {
  const { notify, mediaOr, role, reloadPublic } = useStore();
  const owner = role === 'titolare';
  const [createdId, setCreatedId] = useState<string | null>(null);
  const isNew = id === 'nuovo' && !createdId;
  const { events, error: loadError, reload } = useEvents();
  const existing = events?.find((e) => e.id === (createdId ?? id) || e.slug === id) ?? null;
  usePageMeta(isNew ? 'Nuova bozza evento' : existing ? `Modifica ${existing.title}` : 'Modifica evento');
  const [f, setF] = useState<Form | null>(isNew ? blank() : null);
  const [errors, setErrors] = useState<{ title?: string; date?: string }>({});
  const [problem, setProblem] = useState<Problem | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [cancelReservations, setCancelReservations] = useState(true);

  useEffect(() => {
    if (existing && !f) setF(formOf(existing));
  }, [existing, f]);

  if (!isNew && !existing) {
    return events || loadError ? (
      <div className="empty empty--admin">
        <h1>Evento non trovato</h1>
        <Link to="/gestione/eventi" className="btn btn--ghost">Torna agli eventi</Link>
      </div>
    ) : <p className="muted" role="status">Caricamento dell'evento…</p>;
  }
  if (!f) return <p className="muted" role="status">Caricamento dell'evento…</p>;

  const up = <K extends keyof Form>(k: K, v: Form[K]) => setF((s) => (s ? { ...s, [k]: v } : s));
  const locked = existing?.status === 'CANCELLED';

  const done = (saved: AdminEvent, text: string) => {
    reloadPublic();
    if (saved.warnings.length) {
      setWarnings(saved.warnings);
      notify(`${text} Controlla gli avvisi.`);
      setCreatedId(saved.id);
      reload();
      return;
    }
    notify(text);
    navigate('/gestione/eventi');
  };

  const save = (publish: boolean, acknowledgePending = false) => async (e?: FormEvent) => {
    e?.preventDefault();
    if (busy) return;
    const next: typeof errors = {};
    if (f.title.trim().length < 2) next.title = 'Dai un titolo alla serata (almeno 2 caratteri).';
    if (publish && f.kind === 'SERATA' && (!f.date || !f.startTime || !f.endTime)) next.date = 'Per pubblicare una serata serve la data, con ora di inizio e di fine. Puoi salvarla come bozza senza.';
    setErrors(next);
    if (Object.keys(next).length) {
      document.getElementById(next.title ? 'ee-title' : 'ee-date')?.focus();
      return;
    }
    setBusy(true);
    setProblem(null);
    let created: AdminEvent | null = null;
    try {
      let saved = existing
        ? await updateEvent(existing.id, existing.version, input(f, existing), acknowledgePending)
        : (created = await createEvent(input(f, null)));
      if (publish && saved.status === 'DRAFT') saved = await publishEvent(saved.id, saved.version, acknowledgePending);
      done(saved, publish ? `«${saved.title}» pubblicato sul sito.` : `Bozza «${saved.title}» salvata.`);
    } catch (err) {
      const p = problemOf(err);
      setProblem(p);
      if (p.field) document.getElementById(`ee-${p.field === 'capacityCovers' ? 'capacity' : p.field}`)?.focus();
      // la bozza è stata creata ma non pubblicata: si continua dalla sua pagina (nessun doppione al nuovo tentativo)
      if (created) {
        setCreatedId(created.id);
        reload();
      }
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    if (!existing) return;
    setBusy(true);
    setProblem(null);
    try {
      await cancelEvent(existing.id, existing.version, cancelReservations);
      reloadPublic();
      notify(`«${existing.title}» annullato${cancelReservations ? ': prenotazioni annullate e posti liberati' : ''}.`);
      navigate('/gestione/eventi');
    } catch (err) {
      setProblem(problemOf(err));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!existing) return;
    try {
      await deleteEvent(existing.id);
      notify(`Bozza «${existing.title}» eliminata.`);
      navigate('/gestione/eventi');
    } catch (err) {
      setProblem(problemOf(err));
    }
  };

  const conflicts = (list: EventConflict[] | undefined, label: string) => list && list.length > 0 && (
    <>
      <p className="small"><strong>{label}</strong></p>
      <ul className="res-detail__list">
        {list.map((c) => <li key={c.reference}><span>{c.reference} · {hhmm(c.time)}–{hhmm(c.endTime)}</span><span>{c.guests} persone</span></li>)}
      </ul>
    </>
  );

  return (
    <form className="editor" noValidate onSubmit={save(existing?.status === 'PUBLISHED')}>
      <AdminHead title={isNew ? 'Nuova bozza' : `Modifica: ${existing!.title}`} lead="Compila, salva come bozza o pubblica.">
        <Link to="/gestione/eventi" className="btn btn--quiet">Annulla</Link>
        {!locked && existing?.status !== 'PUBLISHED' && (
          <button type="button" className="btn btn--ghost" disabled={busy} onClick={save(false)}><Save size={18} aria-hidden="true" /> Salva bozza</button>
        )}
        {!locked && (
          <button type="button" className="btn btn--action" disabled={busy} onClick={save(true)}>
            {existing?.status === 'PUBLISHED' ? 'Salva le modifiche' : 'Pubblica'}
          </button>
        )}
      </AdminHead>

      {problem && (
        <div className="panel panel--warn" role="alert">
          <p className="error"><CircleAlert size={16} aria-hidden="true" /> {problem.text}</p>
          {conflicts(problem.confirmed, 'Prenotazioni confermate nello stesso orario (da annullare o spostare prima):')}
          {conflicts(problem.pending, 'Richieste in attesa nello stesso orario:')}
          {problem.acknowledgeable && (
            <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={save(true, true)}>
              Pubblica comunque: quelle richieste non potranno più essere confermate
            </button>
          )}
        </div>
      )}
      {warnings.includes('EVENT_QUOTA_NOT_GUARANTEED') && (
        <p className="hint warn" role="note">I posti dell'evento non sono garantiti: il servizio ordinario usa gli stessi posti e oggi ne restano meno della quota.</p>
      )}
      {warnings.includes('PENDING_REQUESTS_BLOCKED') && (
        <p className="hint warn" role="note">Le richieste ordinarie in attesa in quell'orario non potranno più essere confermate: rispondi ai clienti dalla pagina Richieste.</p>
      )}

      <div className="editor__grid">
        <fieldset className="panel editor__main cap-fieldset" disabled={locked}>
          <div className="field">
            <label htmlFor="ee-title">Titolo</label>
            {errors.title && <p className="error" id="ee-title-err"><CircleAlert size={16} aria-hidden="true" />{errors.title}</p>}
            <input id="ee-title" className="input" value={f.title} onChange={(e) => up('title', e.target.value)}
              aria-invalid={errors.title ? true : undefined} aria-describedby={errors.title ? 'ee-title-err' : undefined} />
          </div>
          <fieldset className="field">
            <legend>Tipo</legend>
            <div className="radio-row">
              <label className="check"><input type="radio" name="kind" checked={f.kind === 'SERATA'} onChange={() => up('kind', 'SERATA')} /> <span>Serata singola</span></label>
              <label className="check"><input type="radio" name="kind" checked={f.kind === 'FORMAT'} onChange={() => up('kind', 'FORMAT')} /> <span>Format ricorrente</span></label>
            </div>
          </fieldset>
          <div className="editor__row">
            <div className="field">
              <label htmlFor="ee-date">Data</label>
              {errors.date && <p className="error" id="ee-date-err"><CircleAlert size={16} aria-hidden="true" />{errors.date}</p>}
              <input id="ee-date" className="input" type="date" value={f.date} onChange={(e) => up('date', e.target.value)}
                aria-invalid={errors.date ? true : undefined} aria-describedby={errors.date ? 'ee-date-err' : undefined} />
            </div>
            <div className="field">
              <label htmlFor="ee-time">Orario</label>
              <input id="ee-time" className="input" type="time" step={900} value={f.startTime} onChange={(e) => up('startTime', e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="ee-end">Fine</label>
              <input id="ee-end" className="input" type="time" step={900} value={f.endTime} onChange={(e) => up('endTime', e.target.value)} aria-describedby="ee-end-hint" />
              <p className="hint" id="ee-end-hint">Prima dell'inizio = il giorno dopo (es. 22:30–02:00).</p>
            </div>
          </div>
          <div className="field">
            <label htmlFor="ee-artist">Artista / DJ</label>
            <input id="ee-artist" className="input" value={f.artist} onChange={(e) => up('artist', e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ee-summary">Frase di lancio</label>
            <input id="ee-summary" className="input" maxLength={300} value={f.summary} onChange={(e) => up('summary', e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ee-body">Descrizione</label>
            <textarea id="ee-body" className="textarea" maxLength={5000} value={f.body} onChange={(e) => up('body', e.target.value)} aria-describedby="ee-body-hint" />
            <p className="hint" id="ee-body-hint">Una riga vuota separa i paragrafi.</p>
          </div>
          <div className="editor__row">
            <div className="field">
              <label htmlFor="ee-action">Pulsante sul sito</label>
              <select id="ee-action" className="select" value={f.bookingMode} onChange={(e) => up('bookingMode', e.target.value as Form['bookingMode'])}>
                <option value="REQUEST">Richiedi un tavolo (conferma del locale)</option>
                <option value="NONE">Nessuna prenotazione: chiedi info su Instagram</option>
              </select>
            </div>
            {f.bookingMode === 'REQUEST' && (
              <div className="field">
                <label htmlFor="ee-capacity">Persone al massimo per la serata</label>
                <input id="ee-capacity" className="input" type="number" min={1} value={f.capacity} onChange={(e) => up('capacity', e.target.value)}
                  aria-invalid={problem?.field === 'capacityCovers' ? true : undefined} />
              </div>
            )}
          </div>
        </fieldset>
        <aside className="editor__side">
          <div className="panel">
            <h2 className="panel__title">Stato</h2>
            <p><StatusPill s={existing?.status ?? 'DRAFT'} /></p>
            {existing?.bookingMode === 'REQUEST' && (
              <p className="small">{existing.confirmedCovers} di {existing.capacityCovers} posti confermati · {existing.pendingCovers} persone in attesa.{' '}
                <a href="#/gestione/richieste">Vai alle richieste</a></p>
            )}
            <p className="small muted">«Pubblica» la rende visibile nella pagina Eventi.</p>
            {existing?.status === 'PUBLISHED' && !cancelling && (
              <button type="button" className="btn btn--ghost btn--sm" onClick={() => setCancelling(true)}><Ban size={16} aria-hidden="true" /> Annulla l'evento</button>
            )}
            {cancelling && existing && (
              <div className="res-detail__form">
                {existing.confirmedReservations + existing.pendingReservations > 0 && (
                  <label className="check"><input type="checkbox" checked={cancelReservations} onChange={(e) => setCancelReservations(e.target.checked)} />
                    <span>Annulla anche le {existing.confirmedReservations + existing.pendingReservations} prenotazioni e richieste dell'evento</span></label>
                )}
                {!owner && existing.confirmedReservations + existing.pendingReservations > 0 && <p className="hint warn">Con prenotazioni attive l'annullamento spetta al titolare.</p>}
                <button type="button" className="btn btn--action btn--sm" disabled={busy} onClick={() => void cancel()}>Conferma l'annullamento</button>
              </div>
            )}
            {existing?.status === 'DRAFT' && (
              <button type="button" className="btn btn--quiet btn--sm" onClick={() => void remove()}><Trash2 size={16} aria-hidden="true" /> Elimina la bozza</button>
            )}
          </div>
          {f.kind === 'SERATA' && (
            <div className="panel">
              <h2 className="panel__title">Posti</h2>
              <Switch checked={f.exclusive} onChange={() => !locked && (owner || !f.exclusive) && up('exclusive', !f.exclusive)}
                label={f.exclusive ? 'Esclusiva: il locale è riservato alla serata' : 'Posti condivisi con il servizio ordinario'} />
              <p className="small muted">
                {owner
                  ? 'Condividere i posti è sicuro: serata e servizio ordinario contano sugli stessi posti, mai oltre la capienza. I posti della serata però non sono garantiti.'
                  : 'Solo il titolare può far condividere i posti della serata con il servizio ordinario.'}
              </p>
            </div>
          )}
          <div className="panel">
            <h2 className="panel__title">Locandina</h2>
            <Img asset={mediaOr(f.poster)} className="editor__preview" />
            <PhotoPicker legend="Scegli o carica la locandina" value={f.poster} onChange={(pid) => up('poster', pid)} allowNone />
          </div>
        </aside>
      </div>
    </form>
  );
}
