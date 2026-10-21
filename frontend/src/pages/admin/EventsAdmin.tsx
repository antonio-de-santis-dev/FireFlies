import { Ban, CircleAlert, Eye, Pencil, Plus, Save, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import {
  cancelEvent, createEvent, deleteEvent, EVENT_STATUS_LABEL, listEvents, setEventVisibility, updateEvent,
  type AdminEvent, type EventConflict, type EventInput, type EventStatus,
} from '../../api/events';
import { hhmm } from '../../api/reservations';
import { Img } from '../../components/ui';
import { formatEventDate } from '../../data/events';
import { Link, navigate, replaceAddress, usePageMeta } from '../../router';
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
  const replace = useCallback((e: AdminEvent) => setEvents((list) => list?.map((x) => (x.id === e.id ? e : x)) ?? list), []);
  return { events, error, reload, replace };
}

/**
 * FIX 2: interruttore «Sul sito» di una riga dell'elenco. Mostra lo stato salvato; durante il salvataggio è
 * disabilitato; se il backend rifiuta (conflitti, prenotazioni attive, versione superata) torna com'era e la riga
 * spiega perché. Nessuno stato a metà: la pubblicazione è una sola transazione.
 */
function ConflictList({ list, label }: { list: EventConflict[] | undefined; label: string }) {
  if (!list?.length) return null;
  return (
    <>
      <p className="small"><strong>{label}</strong></p>
      <ul className="res-detail__list">
        {list.map((c) => <li key={c.reference}><span>{c.reference} · {hhmm(c.time)}–{hhmm(c.endTime)}</span><span>{c.guests} persone</span></li>)}
      </ul>
    </>
  );
}

function PublishSwitch({ event, onSaved, onStale }: { event: AdminEvent; onSaved: (e: AdminEvent) => void; onStale: () => void }) {
  const { notify, reloadPublic } = useStore();
  const published = event.status === 'PUBLISHED';
  const [shown, setShown] = useState(published);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  useEffect(() => setShown(event.status === 'PUBLISHED'), [event.status, event.version]);

  const toggle = async (acknowledgePending = false) => {
    if (busy) return;
    const next = acknowledgePending ? true : !shown;
    setShown(next);
    setBusy(true);
    setProblem(null);
    try {
      const saved = await setEventVisibility(event.id, event.version, next, acknowledgePending);
      onSaved(saved);
      setShown(saved.status === 'PUBLISHED');
      reloadPublic();
      notify(saved.status === 'PUBLISHED' ? `«${saved.title}» pubblicato sul sito.` : `«${saved.title}» tolto dal sito: ora è una bozza.`);
    } catch (err) {
      setShown(published); // torna allo stato salvato
      const p = problemOf(err);
      setProblem(p);
      if (err instanceof ApiError && err.code === 'VERSION_CONFLICT') onStale();
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <span className="ev-admin__publish">
        <Switch checked={shown} disabled={busy} busy={busy} onChange={() => void toggle()}
          label={busy ? 'Salvataggio…' : shown ? 'Sul sito' : 'Non pubblicato'} ariaLabel={`Pubblica sul sito: ${event.title}`} />
      </span>
      {problem && (
        <div className="ev-admin__problem panel panel--warn" role="alert">
          <p className="error"><CircleAlert size={16} aria-hidden="true" /> {problem.text}</p>
          <ConflictList list={problem.confirmed} label="Prenotazioni confermate nello stesso orario (da annullare o spostare prima):" />
          <ConflictList list={problem.pending} label="Richieste in attesa nello stesso orario:" />
          {problem.acknowledgeable && (
            <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={() => void toggle(true)}>
              Pubblica comunque: quelle richieste non potranno più essere confermate
            </button>
          )}
        </div>
      )}
    </>
  );
}

export function EventsAdmin() {
  usePageMeta('Gestione eventi');
  const { mediaOr } = useStore();
  const { events, error, reload, replace } = useEvents();
  const [tab, setTab] = useState<'ALL' | EventStatus>('ALL');
  const all = events ?? [];
  const rows = all.filter((e) => tab === 'ALL' || e.status === tab);
  return (
    <>
      <AdminHead title="Eventi" lead="Serate e format. Un nuovo evento nasce come bozza: lo pubblichi da qui con l'interruttore «Sul sito». Una serata esclusiva riserva il locale nel suo orario.">
        <Link to="/gestione/eventi/nuovo" className="btn btn--action"><Plus size={20} aria-hidden="true" /> Nuovo evento</Link>
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
              {e.status !== 'CANCELLED' && <PublishSwitch event={e} onSaved={replace} onStale={reload} />}
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
  title: string; kind: 'SERATA' | 'FORMAT'; date: string; startTime: string; artist: string; summary: string;
  body: string; bookingMode: 'NONE' | 'REQUEST'; exclusive: boolean; poster: string | null;
};

const blank = (): Form => ({
  title: '', kind: 'SERATA', date: '', startTime: '', artist: '', summary: '', body: '', bookingMode: 'NONE',
  exclusive: true, poster: null,
});

const formOf = (e: AdminEvent): Form => ({
  title: e.title, kind: e.kind, date: e.date ?? '', startTime: e.startTime ? hhmm(e.startTime) : '',
  artist: e.artist ?? '', summary: e.summary ?? '', body: e.body ?? '', bookingMode: e.bookingMode,
  exclusive: e.exclusive, poster: e.poster?.id ?? null,
});

// FASE 8: ora di fine e coperti non si chiedono più; il backend li ricava (durata già salvata o standard di 4 ore,
// quota già salvata o capienza del locale) e continua a controllare esclusività e posti sul registro (T-71, T-72).
const input = (f: Form, existing: AdminEvent | null): EventInput => ({
  title: f.title.trim(), kind: f.kind, date: f.date || null, startTime: f.startTime || null, endTime: null,
  exclusive: f.exclusive, bookingMode: f.bookingMode, capacityCovers: null, artist: f.artist,
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
  usePageMeta(isNew ? 'Nuovo evento' : existing ? `Modifica ${existing.title}` : 'Modifica evento');
  const [f, setF] = useState<Form | null>(isNew ? blank() : null);
  const [errors, setErrors] = useState<{ title?: string; date?: string }>({});
  const [problem, setProblem] = useState<Problem | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [deleting, setDeleting] = useState(false);
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
  const wasPublished = existing?.status === 'PUBLISHED';

  // FIX 2: «Salva» salva solo i contenuti. Un nuovo evento nasce bozza; pubblicare o nascondere si fa dall'elenco.
  const save = (acknowledgePending = false) => async (e?: FormEvent) => {
    e?.preventDefault();
    if (busy) return;
    const next: typeof errors = {};
    if (f.title.trim().length < 2) next.title = 'Dai un titolo alla serata (almeno 2 caratteri).';
    if (wasPublished && f.kind === 'SERATA' && (!f.date || !f.startTime)) next.date = 'Una serata pubblicata deve avere data e ora di inizio.';
    if (!!f.date !== !!f.startTime) next.date = 'Indica sia la data sia l\'ora di inizio (oppure nessuna delle due).';
    setErrors(next);
    if (Object.keys(next).length) {
      document.getElementById(next.title ? 'ee-title' : 'ee-date')?.focus();
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const saved = existing
        ? await updateEvent(existing.id, existing.version, input(f, existing), acknowledgePending)
        : await createEvent(input(f, null), acknowledgePending);
      const published = saved.status === 'PUBLISHED';
      const text = published ? `«${saved.title}» salvato.`
        : existing ? `Bozza «${saved.title}» salvata.`
        : `Bozza «${saved.title}» creata: pubblicala dall'elenco con l'interruttore «Sul sito».`;
      reloadPublic();
      if (saved.warnings.length) {
        // si resta sull'evento salvato (anche dopo un ricaricamento della pagina) per leggere gli avvisi
        setWarnings(saved.warnings);
        notify(`${text} Controlla gli avvisi.`);
        setCreatedId(saved.id);
        replaceAddress(`/gestione/eventi/${saved.id}`);
        setF(formOf(saved));
        reload();
        return;
      }
      notify(text);
      navigate('/gestione/eventi');
    } catch (err) {
      const p = problemOf(err);
      setProblem(p);
      if (p.field) document.getElementById(`ee-${p.field === 'endTime' ? 'time' : p.field}`)?.focus();
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
    setBusy(true);
    setProblem(null);
    try {
      await deleteEvent(existing.id);
      reloadPublic();
      notify(`«${existing.title}» eliminato definitivamente.`);
      navigate('/gestione/eventi');
    } catch (err) {
      setDeleting(false);
      setProblem(problemOf(err));
    } finally {
      setBusy(false);
    }
  };


  return (
    <form className="editor" noValidate onSubmit={save()}>
      <AdminHead title={isNew ? 'Nuovo evento' : `Modifica: ${existing!.title}`} lead="Compila e salva. La pubblicazione sul sito si gestisce dall'elenco eventi, con l'interruttore «Sul sito».">
        <Link to="/gestione/eventi" className="btn btn--quiet">Annulla</Link>
        {!locked && (
          <button type="submit" className="btn btn--action" disabled={busy}><Save size={18} aria-hidden="true" /> Salva</button>
        )}
      </AdminHead>

      {problem && (
        <div className="panel panel--warn" role="alert">
          <p className="error"><CircleAlert size={16} aria-hidden="true" /> {problem.text}</p>
          <ConflictList list={problem.confirmed} label="Prenotazioni confermate nello stesso orario (da annullare o spostare prima):" />
          <ConflictList list={problem.pending} label="Richieste in attesa nello stesso orario:" />
          {problem.acknowledgeable && (
            <button type="button" className="btn btn--ghost btn--sm" disabled={busy} onClick={save(true)}>
              Salva comunque: quelle richieste non potranno più essere confermate
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
              <input id="ee-time" className="input" type="time" step={60} value={f.startTime} onChange={(e) => up('startTime', e.target.value)} aria-describedby="ee-time-hint" />
              <p className="hint" id="ee-time-hint">{existing?.endTime
                ? `Fine prevista alle ${hhmm(existing.endTime)}: spostando l'inizio la durata resta la stessa.`
                : 'Qualunque minuto (es. 21:58). Una serata occupa il locale per 4 ore dall\'inizio.'}</p>
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
              <p className="hint">I posti sono quelli del locale: ogni richiesta si conferma a mano e il sistema non supera mai la capienza.</p>
            )}
          </div>
        </fieldset>
        <aside className="editor__side">
          <div className="panel">
            <h2 className="panel__title">Stato</h2>
            <p><StatusPill s={existing?.status ?? 'DRAFT'} /></p>
            {!locked && (
              <p className="small muted" id="ee-visible-hint">
                {wasPublished
                  ? 'È sul sito. Salvare le modifiche non cambia la visibilità: per toglierlo usa l\'interruttore «Sul sito» nell\'elenco eventi.'
                  : isNew
                    ? 'Nasce come bozza, visibile solo ai gestori. Dopo il salvataggio pubblicala dall\'elenco eventi.'
                    : 'Bozza, visibile solo ai gestori. Per pubblicarla usa l\'interruttore «Sul sito» nell\'elenco eventi.'}
              </p>
            )}
            {existing?.bookingMode === 'REQUEST' && (
              <p className="small">{existing.confirmedCovers} di {existing.capacityCovers} posti confermati · {existing.pendingCovers} persone in attesa.{' '}
                <a href="#/gestione/richieste">Vai alle richieste</a></p>
            )}
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
            {existing && !deleting && (
              <button type="button" className="btn btn--quiet btn--sm" onClick={() => setDeleting(true)}><Trash2 size={16} aria-hidden="true" /> Elimina evento</button>
            )}
            {existing && deleting && (
              <div className="res-detail__form" role="group" aria-label="Conferma l'eliminazione">
                <p className="small"><strong>Eliminare definitivamente «{existing.title}»?</strong> Sparisce dall'area gestori e dal sito; non si può annullare.
                  {existing.confirmedReservations + existing.pendingReservations > 0 && ' Ha prenotazioni attive: va prima annullato.'}</p>
                <div className="confirm-row">
                  <button type="button" className="btn btn--quiet btn--sm" onClick={() => setDeleting(false)}>No, tienilo</button>
                  <button type="button" className="btn btn--action btn--sm" disabled={busy} onClick={() => void remove()}>Elimina definitivamente</button>
                </div>
              </div>
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
            <Img asset={mediaOr(f.poster)} className="editor__preview editor__preview--poster" />
            <PhotoPicker legend="Scegli o carica la locandina" value={f.poster} onChange={(pid) => up('poster', pid)} allowNone contain />
          </div>
        </aside>
      </div>
    </form>
  );
}
