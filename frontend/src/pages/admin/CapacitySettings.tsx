// Impostazioni › Capienza (FIX-1): una sola capienza per tutto il locale, regole delle richieste dal sito, riepilogo di
// una settimana per una fascia, chiusure e aperture straordinarie su un giorno o su un intervallo. Il backend resta
// l'autorità: controlla capienza, conflitti e sovrapposizioni anche con più salvataggi nello stesso momento.
import { CalendarRange, ChevronLeft, ChevronRight, CircleAlert, Pencil, Plus, Save, Trash2, X } from 'lucide-react';
import { useCallback, useEffect, useId, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import {
  addOpeningException, BANDS, bookingConfig, capacitySummary, createWindow, deleteException, deleteOpeningException,
  deleteWindow, hhmm, savePolicy, saveVenueCapacity, updateOpeningException,
  type Band, type BookingConfig, type OpeningException, type OpeningInput, type OpeningResult, type Policy, type ServiceWindow,
  type SummaryRow,
} from '../../api/reservations';
import { useStore } from '../../store';
import { fmtShort } from './Requests';

const DAYS = ['Lunedì', 'Martedì', 'Mercoledì', 'Giovedì', 'Venerdì', 'Sabato', 'Domenica'];
const isoOf = (d: Date) => {
  const x = new Date(d);
  x.setMinutes(x.getMinutes() - x.getTimezoneOffset());
  return x.toISOString().slice(0, 10);
};
const todayIso = () => isoOf(new Date());
const plusDays = (iso: string, n: number) => {
  const d = new Date(`${iso}T12:00:00`);
  d.setDate(d.getDate() + n);
  return isoOf(d);
};
/** Lunedì della settimana che contiene la data. */
const mondayOf = (iso: string) => plusDays(iso, -((new Date(`${iso}T12:00:00`).getDay() + 6) % 7));
const longDate = new Intl.DateTimeFormat('it-IT', { day: 'numeric', month: 'long', year: 'numeric' });
const dayMonth = new Intl.DateTimeFormat('it-IT', { day: 'numeric', month: 'long' });
const weekday = new Intl.DateTimeFormat('it-IT', { weekday: 'short', day: 'numeric', month: 'numeric' });
const at = (iso: string) => new Date(`${iso}T12:00:00`);
const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** Primo estremo di un intervallo: senza mese/anno se uguali a quelli dell'ultimo giorno. */
function firstOf(a: Date, b: Date) {
  if (a.getFullYear() !== b.getFullYear()) return longDate.format(a);
  return a.getMonth() === b.getMonth() ? String(a.getDate()) : dayMonth.format(a);
}

/** «20 – 26 ottobre 2026», «28 ottobre – 3 novembre 2026». */
export function weekLabel(monday: string) {
  const b = at(plusDays(monday, 6));
  return `${firstOf(at(monday), b)} – ${longDate.format(b)}`;
}

/** «25 dicembre 2026» oppure «10 – 17 agosto 2026 · 8 giorni». */
export function rangeLabel(e: Pick<OpeningException, 'day' | 'endDay'>) {
  if (e.day === e.endDay) return longDate.format(at(e.day));
  const a = at(e.day);
  const b = at(e.endDay);
  const days = Math.round((b.getTime() - a.getTime()) / 86_400_000) + 1;
  return `${firstOf(a, b)} – ${longDate.format(b)} · ${days} giorni`;
}

/** Fascia in corso adesso (stessi orari di arrivo del backend: <11, <17, <20, sera). */
const currentBand = (): Band => {
  const h = new Date().getHours();
  return h < 11 ? 'COLAZIONE' : h < 17 ? 'POMERIGGIO' : h < 20 ? 'APERITIVO' : 'SERA';
};

/** Messaggio dell'errore; per «capienza sotto i confermati» dice dove e quanti. */
function message(e: unknown): string {
  if (!(e instanceof ApiError)) return 'Si è verificato un errore imprevisto. Riprova.';
  const ctx = (e.problem as { context?: { conflicts?: { slotStart: string; occupiedCovers: number }[] } } | undefined)?.context;
  if (e.code === 'CAPACITY_BELOW_CONFIRMED' && ctx?.conflicts?.length) {
    const first = ctx.conflicts[0];
    const when = new Date(first.slotStart);
    const label = `${fmtShort(isoOf(when))} alle ${when.toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' })}`;
    return `Non salvato: ${label} ci sono già ${first.occupiedCovers} persone confermate, più della nuova capienza. Annulla o sposta prima quelle prenotazioni${ctx.conflicts.length > 1 ? ` (${ctx.conflicts.length} quarti d'ora coinvolti)` : ''}.`;
  }
  return e.message;
}

type Act = <T>(op: () => Promise<T>, done: string | ((result: T) => string)) => Promise<boolean>;

/** Sezione «Capienza» delle Impostazioni (solo titolare: la pagina è già riservata, il backend lo verifica comunque). */
export function CapacitySettings() {
  const { notify } = useStore();
  const [config, setConfig] = useState<BookingConfig | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  const load = useCallback(() => {
    bookingConfig()
      .then((c) => { setConfig(c); setError(null); setVersion((v) => v + 1); })
      .catch((e) => setError(message(e)));
  }, []);
  useEffect(load, [load]);

  const act: Act = async (op, done) => {
    try {
      const result = await op();
      notify(typeof done === 'function' ? done(result) : done);
      load();
      return true;
    } catch (e) {
      notify(message(e));
      load();
      return false;
    }
  };

  return (
    <section className="capacity-settings" id="capienza" aria-labelledby="cap-title">
      <header className="capacity-settings__head">
        <h2 id="cap-title">Capienza e calendario</h2>
        <p className="muted">Un solo numero per tutto il locale. Raggiunto il limite il sito non accetta altre richieste per quell'orario e
          nessun gestore può confermare oltre: il controllo lo fa il server, anche con più conferme nello stesso momento.</p>
      </header>
      {error && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
      {!config && !error && <p className="muted" role="status">Caricamento della capienza…</p>}
      {config && (
        <div className="cap-grid">
          <VenueCapacityPanel key={`v${version}`} capacity={config.venueCapacity} act={act} />
          <PolicyPanel key={`p${version}`} policy={config.policy} act={act} />
          <WeekSummaryPanel refreshKey={version} />
          <OpeningDatesPanel items={config.openingExceptions} act={act} />
          {config.exceptions.length > 0 && <ClosedBandsPanel config={config} act={act} />}
          <WindowsPanel config={config} act={act} />
        </div>
      )}
    </section>
  );
}

function VenueCapacityPanel({ capacity, act }: { capacity: number; act: Act }) {
  const [value, setValue] = useState(capacity > 0 ? String(capacity) : '');
  const [invalid, setInvalid] = useState<string | null>(null);
  const save = (e: FormEvent) => {
    e.preventDefault();
    const n = Number(value);
    if (!Number.isInteger(n) || n < 1 || n > 1000) {
      setInvalid('Indica un numero intero di persone, da 1 a 1000.');
      return;
    }
    setInvalid(null);
    void act(() => saveVenueCapacity(n), `Capienza del locale salvata: ${n} persone in ogni giorno e fascia.`);
  };
  return (
    <form className="panel cap-grid__venue" aria-labelledby="cap-venue" onSubmit={save} noValidate>
      <h3 id="cap-venue" className="panel__title">Capienza massima del locale</h3>
      <div className="field">
        <label htmlFor="venue-cap">Capienza massima del locale (persone)</label>
        <input id="venue-cap" className="input cap-venue__input" type="number" inputMode="numeric" min={1} max={1000} value={value}
          aria-invalid={!!invalid} aria-describedby="venue-cap-hint" onChange={(e) => setValue(e.target.value)} />
        {invalid && <p className="error" role="alert">{invalid}</p>}
      </div>
      <p className="hint" id="venue-cap-hint">Persone presenti <strong>nello stesso momento</strong>, uguale per ogni giorno e fascia
        (Colazione, Pomeriggio, Aperitivo, Sera). Non è un totale della giornata: chi se ne va libera il posto. Se in qualche orario
        ci sono già più persone confermate, la riduzione viene rifiutata e ti diciamo dove.</p>
      <button type="submit" className="btn btn--action btn--sm"><Save size={16} aria-hidden="true" /> Salva la capienza</button>
    </form>
  );
}

function PolicyPanel({ policy, act }: { policy: Policy | null; act: Act }) {
  const [p, setP] = useState({
    minLeadMinutes: policy?.minLeadMinutes ?? 60, maxAdvanceDays: policy?.maxAdvanceDays ?? 60,
    pendingExpiryMinutesBeforeStart: policy?.pendingExpiryMinutesBeforeStart ?? 60,
  });
  const up = (k: keyof typeof p, v: number) => setP((x) => ({ ...x, [k]: v }));
  const save = (e: FormEvent) => {
    e.preventDefault();
    void act(() => savePolicy({ webBookingEnabled: !!policy?.webBookingEnabled, ...p }), 'Regole delle richieste salvate.');
  };
  return (
    <form className="panel cap-grid__policy" aria-labelledby="cap-policy" onSubmit={save}>
      <h3 id="cap-policy" className="panel__title">Regole delle richieste dal sito</h3>
      <div className="cap-policy__fields">
        <div className="field"><label htmlFor="pol-lead">Anticipo minimo (minuti)</label><input id="pol-lead" className="input" type="number" inputMode="numeric" min={0} value={p.minLeadMinutes} onChange={(e) => up('minLeadMinutes', Number(e.target.value))} /></div>
        <div className="field"><label htmlFor="pol-adv">Al massimo giorni prima</label><input id="pol-adv" className="input" type="number" inputMode="numeric" min={1} max={365} value={p.maxAdvanceDays} onChange={(e) => up('maxAdvanceDays', Number(e.target.value))} /></div>
        <div className="field"><label htmlFor="pol-exp">Scadenza (minuti prima dell'arrivo)</label><input id="pol-exp" className="input" type="number" inputMode="numeric" min={0} value={p.pendingExpiryMinutesBeforeStart} onChange={(e) => up('pendingExpiryMinutesBeforeStart', Number(e.target.value))} /></div>
      </div>
      <p className="hint">Una richiesta non gestita in tempo scade da sola. Le richieste si aprono o chiudono con l'interruttore nei
        Contatti. Il numero di persone per richiesta segue la capienza del locale (oltre 30 persone il sito invita a chiamare).</p>
      <button type="submit" className="btn btn--action btn--sm"><Save size={16} aria-hidden="true" /> Salva le regole</button>
    </form>
  );
}

/** Riepilogo di una settimana per una fascia: un giorno per riga, solo i numeri utili. */
export function WeekSummaryPanel({ refreshKey = 0 }: { refreshKey?: number }) {
  const thisMonday = mondayOf(todayIso());
  const [monday, setMonday] = useState(thisMonday);
  const [band, setBand] = useState<Band>(currentBand);
  const [rows, setRows] = useState<SummaryRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const titleId = useId();
  useEffect(() => {
    let current = true;
    setRows(null);
    capacitySummary(monday, plusDays(monday, 6), band)
      .then((r) => current && (setRows(r.rows), setError(null)))
      .catch((e) => current && setError(message(e)));
    return () => { current = false; };
  }, [monday, band, refreshKey]);
  const isCurrent = monday === thisMonday;
  const today = todayIso();
  const bandLabel = BANDS.find((b) => b.id === band)!.label;
  return (
    <section className="panel cap-grid__week" aria-labelledby={titleId}>
      <div className="week-head">
        <h3 id={titleId} className="panel__title">Riepilogo della settimana</h3>
        <div className="field field--inline week-band">
          <label htmlFor="week-band">Fascia</label>
          <select id="week-band" className="select" value={band} onChange={(e) => setBand(e.target.value as Band)}>
            {BANDS.map((b) => <option key={b.id} value={b.id}>{b.label}</option>)}
          </select>
        </div>
      </div>
      <div className="week-nav" role="group" aria-label="Settimana">
        <button type="button" className="icon-btn week-nav__btn" aria-label="Settimana precedente" onClick={() => setMonday((m) => plusDays(m, -7))}>
          <ChevronLeft size={20} aria-hidden="true" />
        </button>
        <p className="week-nav__label" aria-live="polite">
          <CalendarRange size={16} aria-hidden="true" /> <span>{weekLabel(monday)}</span>
          {isCurrent && <span className="badge badge--demo">Questa settimana</span>}
        </p>
        <button type="button" className="icon-btn week-nav__btn" aria-label="Settimana successiva" onClick={() => setMonday((m) => plusDays(m, 7))}>
          <ChevronRight size={20} aria-hidden="true" />
        </button>
        {!isCurrent && <button type="button" className="btn btn--ghost btn--sm week-nav__today" onClick={() => setMonday(thisMonday)}>Questa settimana</button>}
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {!rows && !error && <p className="muted" role="status">Caricamento…</p>}
      {rows && (
        <div className="table-wrap week-wrap">
          <table className="table week-table">
            <caption className="sr-only">{bandLabel}, settimana {weekLabel(monday)}</caption>
            <thead><tr><th scope="col">Giorno</th><th scope="col">Prenotazioni</th><th scope="col">Confermate / capienza</th><th scope="col">Restano nella fascia</th><th scope="col">Disponibili adesso</th></tr></thead>
            <tbody>
              {rows.map((r) => {
                const offered = r.capacity != null;
                return (
                  <tr key={r.date} data-date={r.date} className={[r.date === today ? 'is-today' : '', r.closed || !offered ? 'is-closed' : '', offered && !r.closed && r.available === 0 ? 'is-full' : ''].join(' ').trim() || undefined}
                    aria-current={r.date === today ? 'date' : undefined}>
                    <th scope="row" className="week-table__day">
                      {capitalize(weekday.format(at(r.date)))}
                      {r.date === today && <span className="badge badge--demo">Oggi</span>}
                      {r.closed && <span className="badge badge--neutral">Chiuso</span>}
                      {!offered && !r.closed && <span className="badge badge--neutral">Fascia non prevista</span>}
                    </th>
                    <td data-label="Prenotazioni">{r.confirmed}{r.pending > 0 && <span className="muted small"> + {r.pending} in attesa</span>}</td>
                    <td data-label="Confermate / capienza">{r.bandCapacity != null ? `${r.bandConfirmed} / ${r.bandCapacity}` : r.guests}</td>
                    <td data-label="Restano nella fascia">{r.bandRemaining ?? '—'}</td>
                    <td data-label="Disponibili adesso">
                      <strong>{offered && !r.closed && r.date >= today ? r.available : '—'}</strong>
                      {offered && !r.closed && r.date >= today && r.eventArrivals > 0 && (
                        <span className="muted small week-table__note">{r.eventArrivals === r.arrivals ? 'Riservata a un evento' : `Evento in ${r.eventArrivals} orari su ${r.arrivals}`}</span>
                      )}
                      {offered && !r.closed && r.date >= today && r.limitedByPhysical && r.eventArrivals < r.arrivals && (
                        <span className="muted small week-table__note">Limitati dai posti occupati nello stesso momento</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint">Ogni fascia di ogni giorno accoglie al massimo la capienza del locale in persone confermate, anche con il
        ricambio dei tavoli: annullare una confermata restituisce i posti, completata e non presentata no. «Disponibili adesso» è il
        gruppo più grande che si può ancora accogliere in almeno un orario della fascia: tiene conto anche dei posti occupati nello
        stesso momento e degli eventi. I giorni già puliti dalla conservazione mostrano le statistiche anonime.</p>
    </section>
  );
}

type OpeningForm = { mode: 'day' | 'range'; from: string; to: string; closed: boolean; opensAt: string; closesAt: string; note: string };
const emptyForm = (): OpeningForm => ({ mode: 'day', from: todayIso(), to: todayIso(), closed: true, opensAt: '18:00', closesAt: '23:00', note: '' });

function conflictText(r: OpeningResult): string | null {
  if (!r.conflicts.length) return null;
  const parts = r.conflicts.slice(0, 3).map((c) => `${fmtShort(c.date)}: ${c.confirmed} confermate (${c.confirmedGuests} persone)${c.pending ? `, ${c.pending} in attesa` : ''}`);
  return `Attenzione: in ${r.conflicts.length === 1 ? 'questo giorno ci sono' : `${r.conflicts.length} giorni ci sono`} già prenotazioni — ${parts.join('; ')}${r.conflicts.length > 3 ? '…' : ''}. Restano valide: gestiscile da Richieste.`;
}

type ClosureConflict = { date: string; pending: number; confirmed: number; confirmedGuests: number };

/** FIX 2: giorni che si stanno chiudendo con prenotazioni attive (409 CLOSURE_HAS_RESERVATIONS), da confermare. */
export function closureConflicts(e: unknown): ClosureConflict[] | null {
  if (!(e instanceof ApiError) || e.code !== 'CLOSURE_HAS_RESERVATIONS') return null;
  return ((e.problem as { context?: { conflicts?: ClosureConflict[] } } | undefined)?.context?.conflicts) ?? [];
}

/** Riquadro di conferma consapevole: elenco dei giorni, nessuna prenotazione annullata in automatico. */
export function ClosureConfirm({ conflicts, busy, onConfirm, onCancel }: {
  conflicts: ClosureConflict[]; busy: boolean; onConfirm: () => void; onCancel: () => void;
}) {
  return (
    <div className="panel panel--warn closure-confirm" role="alertdialog" aria-labelledby="closure-confirm-title" aria-describedby="closure-confirm-desc">
      <p className="error" id="closure-confirm-title"><CircleAlert size={16} aria-hidden="true" /> Nei giorni che stai chiudendo ci sono prenotazioni attive</p>
      <ul className="res-detail__list" aria-label="Giorni con prenotazioni">
        {conflicts.slice(0, 8).map((c) => (
          <li key={c.date}><span>{fmtShort(c.date)}</span><span>{c.confirmed} confermate ({c.confirmedGuests} persone){c.pending ? ` · ${c.pending} in attesa` : ''}</span></li>
        ))}
        {conflicts.length > 8 && <li><span>…e altri {conflicts.length - 8} giorni</span><span /></li>}
      </ul>
      <p className="small" id="closure-confirm-desc">Chiudendo, il sito non accetterà nuove richieste in quei giorni. Le prenotazioni già presenti
        <strong> non vengono annullate né rifiutate</strong>: dovrai gestirle tu dalla pagina Richieste (chiama i clienti).</p>
      <div className="confirm-row">
        <button type="button" className="btn btn--quiet btn--sm" disabled={busy} onClick={onCancel}>Non chiudere</button>
        <button type="button" className="btn btn--action btn--sm" disabled={busy} onClick={onConfirm}>Chiudi comunque</button>
      </div>
    </div>
  );
}

function OpeningDatesPanel({ items, act }: { items: OpeningException[]; act: Act }) {
  const [form, setForm] = useState<OpeningForm>(emptyForm);
  const [editing, setEditing] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [invalid, setInvalid] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState<{ input: OpeningInput; conflicts: ClosureConflict[] } | null>(null);
  const up = <K extends keyof OpeningForm>(k: K, v: OpeningForm[K]) => setForm((f) => ({ ...f, [k]: v }));

  const startEdit = (e: OpeningException) => {
    setEditing(e.id);
    setInvalid(null);
    setForm({ mode: e.day === e.endDay ? 'day' : 'range', from: e.day, to: e.endDay, closed: e.closed,
      opensAt: hhmm(e.opensAt) || '18:00', closesAt: hhmm(e.closesAt) || '23:00', note: e.note ?? '' });
    requestAnimationFrame(() => document.getElementById('od-from')?.focus());
  };
  const cancel = () => { setEditing(null); setForm(emptyForm()); setInvalid(null); setConfirming(null); };

  const save = async (input: OpeningInput, confirm: boolean) => {
    setBusy(true);
    const ok = await act(() => (editing ? updateOpeningException(editing, input, confirm) : addOpeningException(input, confirm))
      .catch((e: unknown) => {
        const conflicts = closureConflicts(e);
        if (conflicts) setConfirming({ input, conflicts });
        throw e;
      }), (r: OpeningResult) => {
      setConfirming(null);
      setWarning(conflictText(r));
      const what = r.exception.closed ? 'Chiusura straordinaria' : 'Apertura straordinaria';
      return `${what} ${editing ? 'aggiornata' : 'salvata'}: ${rangeLabel(r.exception)}.`;
    });
    setBusy(false);
    if (ok) cancel();
  };

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    if (busy) return;
    const to = form.mode === 'day' ? form.from : form.to;
    if (!form.from || !to) return setInvalid('Scegli il giorno.');
    if (to < form.from) return setInvalid("L'ultimo giorno non può essere prima del primo.");
    if (!form.closed && (!form.opensAt || !form.closesAt)) return setInvalid("Per un'apertura straordinaria indica gli orari.");
    setInvalid(null);
    setConfirming(null);
    const input: OpeningInput = { from: form.from, to, closed: form.closed, opensAt: form.closed ? null : form.opensAt,
      closesAt: form.closed ? null : form.closesAt, note: form.note.trim() || undefined };
    await save(input, false);
  };

  return (
    <section className="panel cap-grid__dates" aria-labelledby="cap-dates">
      <h3 id="cap-dates" className="panel__title">Chiusure e aperture straordinarie</h3>
      <p className="hint">Valgono su tutto il giorno e prevalgono sugli orari della settimana. Un giorno chiuso non si può scegliere nel
        calendario del sito; le prenotazioni già presenti non vengono mai cancellate da sole.</p>
      {confirming && (
        <ClosureConfirm conflicts={confirming.conflicts} busy={busy} onCancel={() => setConfirming(null)}
          onConfirm={() => void save(confirming.input, true)} />
      )}
      {warning && <p className="notice notice--warn" role="status"><CircleAlert size={16} aria-hidden="true" /> {warning}
        <button type="button" className="icon-btn icon-btn--sm" aria-label="Chiudi l'avviso" onClick={() => setWarning(null)}><X size={14} aria-hidden="true" /></button></p>}
      <div className={`dates-layout${items.length === 0 ? ' is-empty' : ''}`}>
        <div className="dates-layout__list">
          {items.length === 0 ? <p className="muted">Nessuna data straordinaria.</p> : (
            <ul className="exc-list" aria-label="Date straordinarie">
              {items.map((e) => (
                <li key={e.id} className={editing === e.id ? 'is-editing' : undefined}>
                  <div className="exc-list__main">
                    <strong>{rangeLabel(e)}</strong>
                    <span className={`badge ${e.closed ? 'badge--neutral' : 'badge--demo'}`}>{e.closed ? 'Chiuso' : `Aperto ${hhmm(e.opensAt)}–${hhmm(e.closesAt)}`}</span>
                    {e.note && <span className="muted small">{e.note}</span>}
                  </div>
                  {confirmDelete === e.id ? (
                    <div className="exc-list__actions" role="group" aria-label={`Conferma l'eliminazione di ${rangeLabel(e)}`}>
                      <button type="button" className="btn btn--action btn--sm" onClick={() => { setConfirmDelete(null); void act(() => deleteOpeningException(e.id), 'Data straordinaria eliminata.'); }}>Elimina</button>
                      <button type="button" className="btn btn--ghost btn--sm" onClick={() => setConfirmDelete(null)}>Annulla</button>
                    </div>
                  ) : (
                    <div className="exc-list__actions">
                      <button type="button" className="icon-btn" aria-label={`Modifica ${rangeLabel(e)}`} onClick={() => startEdit(e)}><Pencil size={16} aria-hidden="true" /></button>
                      <button type="button" className="icon-btn" aria-label={`Elimina ${rangeLabel(e)}`} onClick={() => setConfirmDelete(e.id)}><Trash2 size={16} aria-hidden="true" /></button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
        <form className="cap-form dates-form" aria-label={editing ? 'Modifica la data straordinaria' : 'Nuova data straordinaria'} onSubmit={submit} noValidate>
          <fieldset className="cap-fieldset">
            <legend className="cap-legend">Quando</legend>
            <div className="seg" role="radiogroup" aria-label="Quando">
              <label className="seg__opt"><input type="radio" name="od-mode" checked={form.mode === 'day'} onChange={() => up('mode', 'day')} /><span>Un giorno</span></label>
              <label className="seg__opt"><input type="radio" name="od-mode" checked={form.mode === 'range'} onChange={() => setForm((f) => ({ ...f, mode: 'range', to: f.to < f.from ? f.from : f.to }))} /><span>Più giorni</span></label>
            </div>
            <div className="dates-form__days">
              <div className="field"><label htmlFor="od-from">{form.mode === 'day' ? 'Giorno' : 'Dal'}</label>
                <input id="od-from" className="input" type="date" min={todayIso()} value={form.from}
                  onChange={(e) => setForm((f) => ({ ...f, from: e.target.value, to: f.to < e.target.value ? e.target.value : f.to }))} /></div>
              {form.mode === 'range' && <div className="field"><label htmlFor="od-to">Al (compreso)</label>
                <input id="od-to" className="input" type="date" min={form.from || todayIso()} value={form.to} onChange={(e) => up('to', e.target.value)} /></div>}
            </div>
          </fieldset>
          <fieldset className="cap-fieldset">
            <legend className="cap-legend">Tipo</legend>
            <div className="seg" role="radiogroup" aria-label="Tipo">
              <label className="seg__opt"><input type="radio" name="od-kind" checked={form.closed} onChange={() => up('closed', true)} /><span>Chiusura</span></label>
              <label className="seg__opt"><input type="radio" name="od-kind" checked={!form.closed} onChange={() => up('closed', false)} /><span>Apertura straordinaria</span></label>
            </div>
            {!form.closed && <div className="dates-form__days">
              <div className="field"><label htmlFor="od-open">Apre alle</label><input id="od-open" className="input" type="time" value={form.opensAt} onChange={(e) => up('opensAt', e.target.value)} /></div>
              <div className="field"><label htmlFor="od-close">Chiude alle</label><input id="od-close" className="input" type="time" value={form.closesAt} onChange={(e) => up('closesAt', e.target.value)} /></div>
            </div>}
          </fieldset>
          <div className="field"><label htmlFor="od-note">Nota (facoltativa)</label><input id="od-note" className="input" maxLength={140} value={form.note} onChange={(e) => up('note', e.target.value)} placeholder="Es. ferie, festa patronale" /></div>
          {invalid && <p className="error" role="alert">{invalid}</p>}
          <div className="dates-form__actions">
            <button type="submit" className="btn btn--action btn--sm" disabled={busy}>
              {editing ? <><Save size={16} aria-hidden="true" /> Salva le modifiche</> : <><Plus size={16} aria-hidden="true" /> Aggiungi</>}
            </button>
            {editing && <button type="button" className="btn btn--ghost btn--sm" onClick={cancel}>Annulla la modifica</button>}
          </div>
        </form>
      </div>
    </section>
  );
}

/** Chiusure agli arrivi di una sola fascia in una data, create prima di FIX-1: si vedono e si possono togliere. */
function ClosedBandsPanel({ config, act }: { config: BookingConfig; act: Act }) {
  const windowName = (id: string | null) => (id ? config.windows.find((w) => w.id === id)?.name ?? 'fascia' : 'tutto il giorno');
  return (
    <section className="panel cap-grid__bands" aria-labelledby="cap-bands">
      <h3 id="cap-bands" className="panel__title">Fasce chiuse agli arrivi</h3>
      <ul className="exc-list">
        {config.exceptions.map((e) => (
          <li key={e.id}>
            <div className="exc-list__main"><strong>{fmtShort(e.serviceDate)}</strong><span>{windowName(e.serviceWindowId)}</span>{e.reason && <span className="muted small">{e.reason}</span>}</div>
            <div className="exc-list__actions"><button type="button" className="icon-btn" aria-label={`Riapri ${windowName(e.serviceWindowId)} del ${fmtShort(e.serviceDate)}`} onClick={() => void act(() => deleteException(e.id), 'Fascia riaperta agli arrivi.')}><Trash2 size={16} aria-hidden="true" /></button></div>
          </li>
        ))}
      </ul>
    </section>
  );
}

const EMPTY_WINDOW = { isoDay: 5, name: 'Aperitivo', firstStart: '18:00', lastStart: '20:30', slotMinutes: 30, defaultDurationMinutes: 90 };

function WindowsPanel({ config, act }: { config: BookingConfig; act: Act }) {
  const zone = config.zones.find((z) => z.active) ?? config.zones[0];
  const [w, setW] = useState(EMPTY_WINDOW);
  const up = <K extends keyof typeof w>(k: K, v: (typeof w)[K]) => setW((x) => ({ ...x, [k]: v }));
  const byDay = (d: number) => config.windows.filter((x) => x.isoDay === d && x.active);
  const add = (e: FormEvent) => {
    e.preventDefault();
    if (!zone) return;
    void act(() => createWindow({ zoneId: zone.id, isoDay: w.isoDay, name: w.name, firstStart: w.firstStart, lastStart: w.lastStart,
      slotMinutes: w.slotMinutes, defaultDurationMinutes: w.defaultDurationMinutes, active: true }), 'Fascia aggiunta.');
  };
  const label = (x: ServiceWindow) => `${x.name} ${hhmm(x.firstStart)}–${hhmm(x.lastStart)} ogni ${x.slotMinutes}' · ${x.defaultDurationMinutes}'`;
  return (
    <details className="panel cap-grid__windows cap-windows">
      <summary><h3 className="panel__title">Orari di arrivo delle fasce</h3></summary>
      <p className="hint">Primo e ultimo arrivo, passo tra gli arrivi e permanenza prevista. Il nome (Colazione, Pomeriggio, Aperitivo, Sera) decide la fascia.</p>
      <ul className="cap-list">
        {DAYS.map((d, i) => (
          <li key={d}>
            <strong>{d}</strong>
            {byDay(i + 1).length === 0 ? <span className="muted">nessuna fascia</span> : byDay(i + 1).map((x) => (
              <span key={x.id} className="cap-tag">
                {label(x)}
                <button type="button" className="icon-btn icon-btn--sm" aria-label={`Elimina la fascia ${x.name} di ${d.toLowerCase()}`} onClick={() => void act(() => deleteWindow(x.id), 'Fascia eliminata.')}><Trash2 size={14} aria-hidden="true" /></button>
              </span>
            ))}
          </li>
        ))}
      </ul>
      <form className="cap-form" onSubmit={add} aria-label="Nuova fascia">
        <div className="editor__row">
          <div className="field"><label htmlFor="win-day">Giorno</label><select id="win-day" className="select" value={w.isoDay} onChange={(e) => up('isoDay', Number(e.target.value))}>{DAYS.map((d, i) => <option key={d} value={i + 1}>{d}</option>)}</select></div>
          <div className="field"><label htmlFor="win-name">Fascia</label><select id="win-name" className="select" value={w.name} onChange={(e) => up('name', e.target.value)}>{BANDS.map((b) => <option key={b.id} value={b.label}>{b.label}</option>)}</select></div>
          <div className="field"><label htmlFor="win-first">Primo arrivo</label><input id="win-first" className="input" type="time" step={900} value={w.firstStart} onChange={(e) => up('firstStart', e.target.value)} /></div>
          <div className="field"><label htmlFor="win-last">Ultimo arrivo</label><input id="win-last" className="input" type="time" step={900} value={w.lastStart} onChange={(e) => up('lastStart', e.target.value)} /></div>
          <div className="field"><label htmlFor="win-step">Ogni (minuti)</label><select id="win-step" className="select" value={w.slotMinutes} onChange={(e) => up('slotMinutes', Number(e.target.value))}>{[15, 30, 60].map((n) => <option key={n} value={n}>{n}</option>)}</select></div>
          <div className="field"><label htmlFor="win-dur">Permanenza (minuti)</label><input id="win-dur" className="input" type="number" min={30} step={15} value={w.defaultDurationMinutes} onChange={(e) => up('defaultDurationMinutes', Number(e.target.value))} /></div>
        </div>
        <button type="submit" className="btn btn--ghost btn--sm"><Plus size={16} aria-hidden="true" /> Aggiungi la fascia</button>
      </form>
    </details>
  );
}
