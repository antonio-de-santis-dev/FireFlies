// Impostazioni › Capienza (FASE 7, req. 6 e 8): persone al massimo per giorno della settimana e fascia, chiusure e
// aperture straordinarie, limiti per data, riepilogo per giorno e fascia. Nessuna gestione dei tavoli: la capienza si
// ragiona in persone, il backend la controlla sul registro dei posti a ogni conferma.
import { CircleAlert, Plus, Save, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import {
  addException, addOpeningException, BANDS, bookingConfig, capacityMatrix, capacitySummary, createWindow, deleteException,
  deleteOpeningException, deleteWindow, hhmm, saveCapacityMatrix, savePolicy, updateZone,
  type Band, type BookingConfig, type CapacityMatrix, type Policy, type ServiceWindow, type SummaryRow,
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

/** Messaggio dell'errore; per «capienza sotto i confermati» dice dove e quanti. */
function message(e: unknown): string {
  if (!(e instanceof ApiError)) return 'Si è verificato un errore imprevisto. Riprova.';
  const ctx = (e.problem as { context?: { conflicts?: { slotStart: string; occupiedCovers: number; newCapacity?: number; capacity?: number }[] } } | undefined)?.context;
  if (e.code === 'CAPACITY_BELOW_CONFIRMED' && ctx?.conflicts?.length) {
    const first = ctx.conflicts[0];
    const when = new Date(first.slotStart);
    const label = `${fmtShort(isoOf(when))} alle ${when.toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' })}`;
    return `Non salvato: ${label} ci sono già ${first.occupiedCovers} persone confermate, più del nuovo limite. Annulla o sposta prima quelle prenotazioni${ctx.conflicts.length > 1 ? ` (${ctx.conflicts.length} quarti d'ora coinvolti)` : ''}.`;
  }
  return e.message;
}

type Act = <T>(op: () => Promise<T>, done: string | ((result: T) => string)) => Promise<boolean>;

/** Sezione «Capienza» delle Impostazioni (solo titolare: la pagina è già riservata, il backend lo verifica comunque). */
export function CapacitySettings() {
  const { notify } = useStore();
  const [config, setConfig] = useState<BookingConfig | null>(null);
  const [matrix, setMatrix] = useState<CapacityMatrix | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  const load = useCallback(() => {
    Promise.all([bookingConfig(), capacityMatrix()])
      .then(([c, m]) => { setConfig(c); setMatrix(m); setError(null); setVersion((v) => v + 1); })
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
        <h2 id="cap-title">Capienza</h2>
        <p className="muted">Quante persone può accogliere il locale in ogni fascia. Raggiunto il limite il sito non accetta altre richieste
          per quell'orario e nessun gestore può confermare oltre: il controllo lo fa il server, anche con più conferme nello stesso momento.</p>
      </header>
      {error && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
      {!config && !error && <p className="muted" role="status">Caricamento della capienza…</p>}
      {config && matrix && (
        <div className="settings">
          <ZonePanel config={config} act={act} />
          <MatrixPanel key={`m${version}`} matrix={matrix} act={act} />
          <SummaryPanel refreshKey={version} />
          <OpeningDatesPanel matrix={matrix} act={act} />
          <ExceptionsPanel config={config} act={act} />
          <PolicyPanel policy={config.policy} act={act} />
          <WindowsPanel config={config} act={act} />
        </div>
      )}
    </section>
  );
}

function ZonePanel({ config, act }: { config: BookingConfig; act: Act }) {
  const zone = config.zones[0];
  const [capacity, setCapacity] = useState(zone?.physicalCapacity ?? 0);
  useEffect(() => setCapacity(zone?.physicalCapacity ?? 0), [zone?.physicalCapacity]);
  if (!zone) return null;
  return (
    <form className="panel" aria-labelledby="cap-zone" onSubmit={(e) => { e.preventDefault(); void act(() => updateZone(zone, { physicalCapacity: capacity, active: zone.active || capacity > 0 }), 'Capienza del locale salvata.'); }}>
      <h3 id="cap-zone" className="panel__title">Capienza del locale</h3>
      <div className="field">
        <label htmlFor="zone-cap">Persone presenti al massimo</label>
        <input id="zone-cap" className="input" type="number" inputMode="numeric" min={0} max={2000} value={capacity} onChange={(e) => setCapacity(Number(e.target.value))} />
      </div>
      <p className="hint">È il tetto di ogni fascia. Se ci sono già più persone confermate, la modifica viene rifiutata.</p>
      <button type="submit" className="btn btn--action btn--sm"><Save size={16} aria-hidden="true" /> Salva la capienza</button>
    </form>
  );
}

function MatrixPanel({ matrix, act }: { matrix: CapacityMatrix; act: Act }) {
  const key = (d: number, b: Band) => `${d}|${b}`;
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(
    matrix.cells.map((c) => [key(c.isoDay, c.band), c.maxCovers == null ? '' : String(c.maxCovers)])));
  const cell = (d: number, b: Band) => matrix.cells.find((c) => c.isoDay === d && c.band === b);
  const save = (e: FormEvent) => {
    e.preventDefault();
    const cells = matrix.cells.filter((c) => c.offered).map((c) => {
      const v = values[key(c.isoDay, c.band)]?.trim() ?? '';
      return { isoDay: c.isoDay, band: c.band, maxCovers: v === '' ? null : Number(v) };
    });
    void act(() => saveCapacityMatrix(cells), 'Capienza per fascia salvata.');
  };
  return (
    <form className="panel panel--wide" aria-labelledby="cap-matrix" onSubmit={save}>
      <h3 id="cap-matrix" className="panel__title">Persone al massimo per giorno e fascia</h3>
      <p className="hint">Vuoto = fino alla capienza del locale ({matrix.physicalCapacity} persone). «—» = fascia non prenotabile quel giorno.</p>
      <div className="table-wrap">
        <table className="table table--compact cap-matrix">
          <caption className="sr-only">Persone presenti al massimo per giorno della settimana e fascia</caption>
          <thead><tr><th scope="col">Giorno</th>{BANDS.map((b) => <th key={b.id} scope="col">{b.label}</th>)}</tr></thead>
          <tbody>
            {DAYS.map((day, i) => (
              <tr key={day}>
                <th scope="row">{day}</th>
                {BANDS.map((b) => {
                  const c = cell(i + 1, b.id);
                  return (
                    <td key={b.id} data-label={b.label}>
                      {c?.offered ? (
                        <>
                          <label className="sr-only" htmlFor={`cm-${i + 1}-${b.id}`}>{b.label} di {day.toLowerCase()}</label>
                          <input id={`cm-${i + 1}-${b.id}`} className="input input--sm cap-matrix__input" type="number" inputMode="numeric" min={0}
                            max={1000} placeholder={String(matrix.physicalCapacity)} value={values[key(i + 1, b.id)] ?? ''}
                            onChange={(e) => setValues((v) => ({ ...v, [key(i + 1, b.id)]: e.target.value }))} />
                        </>
                      ) : <span className="muted" aria-label="non prenotabile">—</span>}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <button type="submit" className="btn btn--action btn--sm"><Save size={16} aria-hidden="true" /> Salva la capienza per fascia</button>
    </form>
  );
}

/** Riepilogo per giorno e fascia: richieste, confermate, persone, capienza e posti ancora disponibili. */
export function SummaryPanel({ refreshKey = 0 }: { refreshKey?: number }) {
  const [from, setFrom] = useState(todayIso());
  const [rows, setRows] = useState<SummaryRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    setRows(null);
    capacitySummary(from, plusDays(from, 13))
      .then((r) => current && (setRows(r.rows), setError(null)))
      .catch((e) => current && setError(message(e)));
    return () => { current = false; };
  }, [from, refreshKey]);
  return (
    <section className="panel panel--wide" aria-labelledby="cap-sum">
      <div className="panel__head">
        <h3 id="cap-sum" className="panel__title">Riepilogo per giorno e fascia</h3>
        <div className="field field--inline">
          <label htmlFor="sum-from">Dal</label>
          <input id="sum-from" className="input input--sm" type="date" value={from} onChange={(e) => setFrom(e.target.value || todayIso())} />
        </div>
      </div>
      <p className="hint">14 giorni. «Disponibili» è il gruppo più grande che si può ancora accogliere in almeno un orario della fascia. I giorni
        passati già puliti mostrano le statistiche anonime.</p>
      {error && <p className="error" role="alert">{error}</p>}
      {!rows && !error && <p className="muted" role="status">Caricamento…</p>}
      {rows && rows.length === 0 && <p className="muted">Nessuna fascia prenotabile in questi giorni.</p>}
      {rows && rows.length > 0 && (
        <div className="table-wrap">
          <table className="table table--compact cap-summary">
            <caption className="sr-only">Riepilogo prenotazioni per giorno e fascia</caption>
            <thead><tr><th scope="col">Giorno</th><th scope="col">Fascia</th><th scope="col">Richieste</th><th scope="col">Confermate</th><th scope="col">Persone</th><th scope="col">Capienza</th><th scope="col">Disponibili</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.date}${r.band}`} className={r.closed ? 'is-closed' : r.available === 0 ? 'is-full' : undefined}>
                  <td data-label="Giorno">{fmtShort(r.date)}</td>
                  <td data-label="Fascia">{r.label}{r.closed && <span className="badge badge--neutral">Chiuso</span>}</td>
                  <td data-label="Richieste">{r.requests}{r.pending > 0 && <span className="muted small"> ({r.pending} in attesa)</span>}</td>
                  <td data-label="Confermate">{r.confirmed}</td>
                  <td data-label="Persone">{r.guests}</td>
                  <td data-label="Capienza">{r.capacity ?? '—'}</td>
                  <td data-label="Disponibili"><strong>{r.available ?? '—'}</strong></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function OpeningDatesPanel({ matrix, act }: { matrix: CapacityMatrix; act: Act }) {
  const [x, setX] = useState({ day: todayIso(), closed: true, opensAt: '18:00', closesAt: '23:00', note: '' });
  const add = (e: FormEvent) => {
    e.preventDefault();
    void act(() => addOpeningException({ day: x.day, closed: x.closed, opensAt: x.closed ? null : x.opensAt, closesAt: x.closed ? null : x.closesAt, note: x.note || undefined }),
      (r) => (x.closed ? 'Chiusura straordinaria aggiunta: quel giorno il sito non accetta richieste.' : 'Apertura straordinaria aggiunta.')
        + (r.activeReservations > 0 ? ` Attenzione: in quella data ci sono già ${r.activeReservations} prenotazioni o richieste, che restano valide: gestiscile da Richieste.` : ''));
  };
  return (
    <section className="panel" aria-labelledby="cap-dates">
      <h3 id="cap-dates" className="panel__title">Chiusure e aperture straordinarie</h3>
      <p className="hint">Valgono su tutto il giorno e prevalgono sugli orari della settimana: un giorno chiuso non si può scegliere nel calendario del sito.</p>
      {matrix.openingExceptions.length === 0 ? <p className="muted">Nessuna data straordinaria.</p> : (
        <ul className="cap-list">
          {matrix.openingExceptions.map((e) => (
            <li key={e.id}>
              <strong>{fmtShort(e.day)}</strong>
              <span>{e.closed ? 'Chiuso' : `Aperto ${hhmm(e.opensAt!)}–${hhmm(e.closesAt!)}`}{e.note ? ` · ${e.note}` : ''}</span>
              <button type="button" className="icon-btn icon-btn--sm" aria-label={`Elimina la data straordinaria del ${fmtShort(e.day)}`} onClick={() => void act(() => deleteOpeningException(e.id), 'Data straordinaria eliminata.')}><Trash2 size={14} aria-hidden="true" /></button>
            </li>
          ))}
        </ul>
      )}
      <form className="cap-form" aria-label="Nuova data straordinaria" onSubmit={add}>
        <div className="editor__row">
          <div className="field"><label htmlFor="od-day">Data</label><input id="od-day" className="input" type="date" min={todayIso()} value={x.day} onChange={(e) => setX((v) => ({ ...v, day: e.target.value }))} /></div>
          <div className="field"><label htmlFor="od-kind">Tipo</label>
            <select id="od-kind" className="select" value={x.closed ? 'closed' : 'open'} onChange={(e) => setX((v) => ({ ...v, closed: e.target.value === 'closed' }))}>
              <option value="closed">Chiusura straordinaria</option><option value="open">Apertura straordinaria</option>
            </select></div>
          {!x.closed && <>
            <div className="field"><label htmlFor="od-open">Apre</label><input id="od-open" className="input" type="time" value={x.opensAt} onChange={(e) => setX((v) => ({ ...v, opensAt: e.target.value }))} /></div>
            <div className="field"><label htmlFor="od-close">Chiude</label><input id="od-close" className="input" type="time" value={x.closesAt} onChange={(e) => setX((v) => ({ ...v, closesAt: e.target.value }))} /></div>
          </>}
          <div className="field"><label htmlFor="od-note">Nota</label><input id="od-note" className="input" maxLength={140} value={x.note} onChange={(e) => setX((v) => ({ ...v, note: e.target.value }))} placeholder="Es. ferie, festa patronale" /></div>
        </div>
        <button type="submit" className="btn btn--ghost btn--sm"><Plus size={16} aria-hidden="true" /> Aggiungi la data</button>
      </form>
    </section>
  );
}

function ExceptionsPanel({ config, act }: { config: BookingConfig; act: Act }) {
  const [x, setX] = useState({ serviceDate: todayIso(), kind: 'CAPACITY_OVERRIDE' as 'CLOSED' | 'CAPACITY_OVERRIDE', capacityOverride: 20, reason: '' });
  const windowName = (id: string | null) => (id ? config.windows.find((w) => w.id === id)?.name ?? 'fascia' : 'tutto il giorno');
  return (
    <section className="panel" aria-labelledby="cap-exc">
      <h3 id="cap-exc" className="panel__title">Limiti di persone per data</h3>
      {config.exceptions.length === 0 ? <p className="muted">Nessun limite particolare nei prossimi giorni.</p> : (
        <ul className="cap-list">
          {config.exceptions.map((e) => (
            <li key={e.id}>
              <strong>{fmtShort(e.serviceDate)}</strong>
              <span>{e.kind === 'CLOSED' ? 'Chiuso agli arrivi' : `Al massimo ${e.capacityOverride} persone`} · {windowName(e.serviceWindowId)}{e.reason ? ` · ${e.reason}` : ''}</span>
              <button type="button" className="icon-btn icon-btn--sm" aria-label={`Elimina il limite del ${fmtShort(e.serviceDate)}`} onClick={() => void act(() => deleteException(e.id), 'Limite eliminato.')}><Trash2 size={14} aria-hidden="true" /></button>
            </li>
          ))}
        </ul>
      )}
      <form className="cap-form" aria-label="Nuovo limite per data" onSubmit={(e) => { e.preventDefault(); void act(() => addException({ serviceDate: x.serviceDate, kind: x.kind, capacityOverride: x.kind === 'CAPACITY_OVERRIDE' ? x.capacityOverride : null, reason: x.reason || undefined }), 'Limite aggiunto.'); }}>
        <div className="editor__row">
          <div className="field"><label htmlFor="exc-date">Data</label><input id="exc-date" className="input" type="date" min={todayIso()} value={x.serviceDate} onChange={(e) => setX((v) => ({ ...v, serviceDate: e.target.value }))} /></div>
          <div className="field"><label htmlFor="exc-kind">Tipo</label><select id="exc-kind" className="select" value={x.kind} onChange={(e) => setX((v) => ({ ...v, kind: e.target.value as typeof v.kind }))}><option value="CAPACITY_OVERRIDE">Persone al massimo</option><option value="CLOSED">Nessun arrivo</option></select></div>
          {x.kind === 'CAPACITY_OVERRIDE' && <div className="field"><label htmlFor="exc-cap">Persone al massimo</label><input id="exc-cap" className="input" type="number" inputMode="numeric" min={0} value={x.capacityOverride} onChange={(e) => setX((v) => ({ ...v, capacityOverride: Number(e.target.value) }))} /></div>}
          <div className="field"><label htmlFor="exc-reason">Motivo</label><input id="exc-reason" className="input" maxLength={140} value={x.reason} onChange={(e) => setX((v) => ({ ...v, reason: e.target.value }))} placeholder="Es. festa privata" /></div>
        </div>
        <button type="submit" className="btn btn--ghost btn--sm"><Plus size={16} aria-hidden="true" /> Aggiungi</button>
      </form>
    </section>
  );
}

function PolicyPanel({ policy, act }: { policy: Policy | null; act: Act }) {
  const [p, setP] = useState(() => policy ?? { webBookingEnabled: false, minLeadMinutes: 60, maxAdvanceDays: 60, maxPartySizeWeb: 12, pendingExpiryMinutesBeforeStart: 60 } as Policy);
  useEffect(() => { if (policy) setP(policy); }, [policy]);
  const up = <K extends keyof Policy>(k: K, v: Policy[K]) => setP((x) => ({ ...x, [k]: v }));
  const save = (e: FormEvent) => {
    e.preventDefault();
    void act(() => savePolicy({ webBookingEnabled: p.webBookingEnabled, minLeadMinutes: p.minLeadMinutes, maxAdvanceDays: p.maxAdvanceDays,
      maxPartySizeWeb: p.maxPartySizeWeb, pendingExpiryMinutesBeforeStart: p.pendingExpiryMinutesBeforeStart }), 'Regole delle richieste salvate.');
  };
  return (
    <form className="panel" aria-labelledby="cap-policy" onSubmit={save}>
      <h3 id="cap-policy" className="panel__title">Regole delle richieste dal sito</h3>
      <div className="editor__row">
        <div className="field"><label htmlFor="pol-lead">Anticipo minimo (minuti)</label><input id="pol-lead" className="input" type="number" min={0} value={p.minLeadMinutes} onChange={(e) => up('minLeadMinutes', Number(e.target.value))} /></div>
        <div className="field"><label htmlFor="pol-adv">Al massimo giorni prima</label><input id="pol-adv" className="input" type="number" min={1} max={365} value={p.maxAdvanceDays} onChange={(e) => up('maxAdvanceDays', Number(e.target.value))} /></div>
        <div className="field"><label htmlFor="pol-party">Persone massime dal sito</label><input id="pol-party" className="input" type="number" min={1} max={50} value={p.maxPartySizeWeb} onChange={(e) => up('maxPartySizeWeb', Number(e.target.value))} /></div>
        <div className="field"><label htmlFor="pol-exp">Scadenza delle richieste (minuti prima dell'arrivo)</label><input id="pol-exp" className="input" type="number" min={0} value={p.pendingExpiryMinutesBeforeStart} onChange={(e) => up('pendingExpiryMinutesBeforeStart', Number(e.target.value))} /></div>
      </div>
      <p className="hint">Una richiesta non gestita in tempo scade da sola. Le richieste si aprono o chiudono con l'interruttore nei Contatti.</p>
      <button type="submit" className="btn btn--action btn--sm">Salva le regole</button>
    </form>
  );
}

const EMPTY_WINDOW = { isoDay: 5, name: 'Aperitivo', firstStart: '18:00', lastStart: '20:30', slotMinutes: 30, defaultDurationMinutes: 90 };

function WindowsPanel({ config, act }: { config: BookingConfig; act: Act }) {
  const zone = config.zones[0];
  const [w, setW] = useState(EMPTY_WINDOW);
  const up = <K extends keyof typeof w>(k: K, v: (typeof w)[K]) => setW((x) => ({ ...x, [k]: v }));
  const byDay = (d: number) => config.windows.filter((x) => x.isoDay === d && x.active);
  const add = (e: FormEvent) => {
    e.preventDefault();
    if (!zone) return;
    void act(() => createWindow({ zoneId: zone.id, isoDay: w.isoDay, name: w.name, firstStart: w.firstStart, lastStart: w.lastStart,
      slotMinutes: w.slotMinutes, defaultDurationMinutes: w.defaultDurationMinutes, maxCovers: null, active: true }), 'Fascia aggiunta.');
  };
  const label = (x: ServiceWindow) => `${x.name} ${hhmm(x.firstStart)}–${hhmm(x.lastStart)} ogni ${x.slotMinutes}' · ${x.defaultDurationMinutes}'`;
  return (
    <details className="panel panel--wide cap-windows">
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
