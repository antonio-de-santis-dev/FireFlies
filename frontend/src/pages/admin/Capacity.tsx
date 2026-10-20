import { CircleAlert, Plus, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import {
  addException, bookingConfig, createArea, createTable, createWindow, dayCapacity, deleteArea, deleteException, deleteTable,
  deleteWindow, hhmm, savePolicy, updateZone, type BookingConfig, type DayCapacity, type Policy, type ServiceWindow,
} from '../../api/reservations';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { Switch } from './MenuAdmin';
import { fmtShort } from './Requests';

const DAYS = ['Lunedì', 'Martedì', 'Mercoledì', 'Giovedì', 'Venerdì', 'Sabato', 'Domenica'];
const todayIso = () => {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 10);
};
const message = (e: unknown) => (e instanceof ApiError ? e.message : 'Si è verificato un errore imprevisto. Riprova.');

/**
 * Capienza e tavoli: posti del giorno (staff e titolare) e configurazione delle prenotazioni (solo titolare:
 * regole del sito, capienza della zona, fasce orarie, aree e tavoli, chiusure e limiti per data).
 */
export function Capacity() {
  usePageMeta('Capienza e tavoli');
  const { role, notify } = useStore();
  const owner = role === 'titolare';
  const [config, setConfig] = useState<BookingConfig | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    bookingConfig().then((c) => { setConfig(c); setError(null); }).catch((e) => setError(message(e)));
  }, []);
  useEffect(load, [load]);

  /** Esegue un'operazione del titolare, avvisa e ricarica la configurazione. */
  const act = async (op: () => Promise<unknown>, done: string) => {
    try {
      await op();
      notify(done);
      load();
      return true;
    } catch (e) {
      notify(message(e));
      load();
      return false;
    }
  };

  return (
    <>
      <AdminHead title="Capienza e tavoli" lead={owner
        ? 'Quante persone può accogliere il locale, in quali orari si chiede un tavolo dal sito e quali tavoli ci sono. I posti si contano a quarti d\'ora: nessun tavolo viene assegnato in automatico.'
        : 'Posti occupati e liberi del giorno. La configurazione la cambia il titolare.'} />
      <DayView />
      {error && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
      {config && (
        <div className="settings">
          <PolicyPanel policy={config.policy} owner={owner} act={act} />
          <ZonePanel config={config} owner={owner} act={act} />
          <WindowsPanel config={config} owner={owner} act={act} />
          <TablesPanel config={config} owner={owner} act={act} />
          <ExceptionsPanel config={config} owner={owner} act={act} />
        </div>
      )}
    </>
  );
}

type Act = (op: () => Promise<unknown>, done: string) => Promise<boolean>;

function DayView() {
  const [date, setDate] = useState(todayIso());
  const [data, setData] = useState<DayCapacity | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let current = true;
    setData(null);
    dayCapacity(date).then((d) => current && (setData(d), setError(null))).catch((e) => current && setError(message(e)));
    return () => { current = false; };
  }, [date]);
  return (
    <section className="panel" aria-labelledby="cap-day">
      <div className="panel__head">
        <h2 id="cap-day">Posti del giorno</h2>
        <div className="field field--inline">
          <label htmlFor="cap-date">Giorno</label>
          <input id="cap-date" className="input input--sm" type="date" value={date} onChange={(e) => setDate(e.target.value || todayIso())} />
        </div>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {!data && !error && <p className="muted" role="status">Caricamento…</p>}
      {data?.zones.map((z) => (
        <div key={z.zoneId} className="cap-day">
          <p className="small muted">{z.name} · {fmtShort(data.date)} · capienza fisica {z.physicalCapacity}</p>
          {z.quanta.length === 0 ? <p className="muted">Nessuna fascia prenotabile in questo giorno.</p> : (
            <div className="table-wrap">
              <table className="table table--compact">
                <caption className="sr-only">Posti ogni quarto d'ora, {z.name}</caption>
                <thead><tr><th scope="col">Ora</th><th scope="col">Capienza</th><th scope="col">Confermati</th><th scope="col">In attesa</th><th scope="col">Liberi</th></tr></thead>
                <tbody>
                  {z.quanta.map((q) => {
                    const free = Math.max(0, q.capacity - q.confirmed);
                    return (
                      <tr key={q.time} className={free === 0 ? 'is-full' : undefined}>
                        <td data-label="Ora">{hhmm(q.time)}</td>
                        <td data-label="Capienza">{q.capacity}</td>
                        <td data-label="Confermati">{q.confirmed}</td>
                        <td data-label="In attesa">{q.pending || '—'}</td>
                        <td data-label="Liberi"><strong>{q.event ? 'Evento' : free}</strong></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ))}
      {data && data.zones.length === 0 && <p className="muted">Nessuna zona attiva: il titolare deve impostare la capienza.</p>}
    </section>
  );
}

function PolicyPanel({ policy, owner, act }: { policy: Policy | null; owner: boolean; act: Act }) {
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
      <h2 id="cap-policy" className="panel__title">Richieste dal sito</h2>
      <fieldset disabled={!owner} className="cap-fieldset">
        <Switch checked={p.webBookingEnabled} onChange={() => owner && up('webBookingEnabled', !p.webBookingEnabled)} label={p.webBookingEnabled ? 'Richieste dal sito aperte' : 'Richieste dal sito chiuse'} />
        <div className="editor__row">
          <div className="field"><label htmlFor="pol-lead">Anticipo minimo (minuti)</label><input id="pol-lead" className="input" type="number" min={0} value={p.minLeadMinutes} onChange={(e) => up('minLeadMinutes', Number(e.target.value))} /></div>
          <div className="field"><label htmlFor="pol-adv">Al massimo giorni prima</label><input id="pol-adv" className="input" type="number" min={1} max={365} value={p.maxAdvanceDays} onChange={(e) => up('maxAdvanceDays', Number(e.target.value))} /></div>
          <div className="field"><label htmlFor="pol-party">Persone massime dal sito</label><input id="pol-party" className="input" type="number" min={1} max={50} value={p.maxPartySizeWeb} onChange={(e) => up('maxPartySizeWeb', Number(e.target.value))} /></div>
          <div className="field"><label htmlFor="pol-exp">Scadenza delle richieste (minuti prima dell'arrivo)</label><input id="pol-exp" className="input" type="number" min={0} value={p.pendingExpiryMinutesBeforeStart} onChange={(e) => up('pendingExpiryMinutesBeforeStart', Number(e.target.value))} /></div>
        </div>
        <p className="hint">Una richiesta non gestita in tempo scade da sola: il cliente non resta in sospeso fino all'arrivo.</p>
        {owner && <button type="submit" className="btn btn--primary btn--sm">Salva le regole</button>}
      </fieldset>
    </form>
  );
}

function ZonePanel({ config, owner, act }: { config: BookingConfig; owner: boolean; act: Act }) {
  const zone = config.zones[0];
  const [capacity, setCapacity] = useState(zone?.physicalCapacity ?? 0);
  useEffect(() => setCapacity(zone?.physicalCapacity ?? 0), [zone?.physicalCapacity]);
  if (!zone) return null;
  return (
    <form className="panel" aria-labelledby="cap-zone" onSubmit={(e) => { e.preventDefault(); void act(() => updateZone(zone, { physicalCapacity: capacity, active: zone.active || capacity > 0 }), 'Capienza salvata.'); }}>
      <h2 id="cap-zone" className="panel__title">Capienza del locale</h2>
      <fieldset disabled={!owner} className="cap-fieldset">
        <div className="editor__row">
          <div className="field"><label htmlFor="zone-cap">Persone presenti al massimo ({zone.name})</label><input id="zone-cap" className="input" type="number" min={0} max={2000} value={capacity} onChange={(e) => setCapacity(Number(e.target.value))} /></div>
        </div>
        <Switch checked={zone.active} onChange={() => owner && void act(() => updateZone(zone, { active: !zone.active }), zone.active ? 'Zona disattivata.' : 'Zona attiva.')} label={zone.active ? 'Zona attiva' : 'Zona non attiva'} />
        <p className="hint">Se ci sono già prenotazioni confermate oltre la nuova capienza, il sistema rifiuta la modifica.</p>
        {owner && <button type="submit" className="btn btn--primary btn--sm">Salva la capienza</button>}
      </fieldset>
    </form>
  );
}

const EMPTY_WINDOW = { isoDay: 5, name: 'Aperitivo', firstStart: '18:00', lastStart: '20:30', slotMinutes: 30, defaultDurationMinutes: 90, maxCovers: '' };

function WindowsPanel({ config, owner, act }: { config: BookingConfig; owner: boolean; act: Act }) {
  const zone = config.zones[0];
  const [w, setW] = useState(EMPTY_WINDOW);
  const up = <K extends keyof typeof w>(k: K, v: (typeof w)[K]) => setW((x) => ({ ...x, [k]: v }));
  const byDay = (d: number) => config.windows.filter((x) => x.isoDay === d && x.active);
  const add = (e: FormEvent) => {
    e.preventDefault();
    if (!zone) return;
    void act(() => createWindow({ zoneId: zone.id, isoDay: w.isoDay, name: w.name, firstStart: w.firstStart, lastStart: w.lastStart,
      slotMinutes: w.slotMinutes, defaultDurationMinutes: w.defaultDurationMinutes, maxCovers: w.maxCovers === '' ? null : Number(w.maxCovers), active: true }),
    'Fascia aggiunta.');
  };
  const label = (x: ServiceWindow) => `${x.name} ${hhmm(x.firstStart)}–${hhmm(x.lastStart)} ogni ${x.slotMinutes}' · ${x.defaultDurationMinutes}'${x.maxCovers != null ? ` · max ${x.maxCovers}` : ''}`;
  return (
    <section className="panel" aria-labelledby="cap-win">
      <h2 id="cap-win" className="panel__title">Fasce orarie prenotabili</h2>
      <p className="hint">Primo e ultimo arrivo, passo tra gli arrivi e permanenza prevista. Il limite di presenze è facoltativo.</p>
      <ul className="cap-list">
        {DAYS.map((d, i) => (
          <li key={d}>
            <strong>{d}</strong>
            {byDay(i + 1).length === 0 ? <span className="muted">nessuna fascia</span> : byDay(i + 1).map((x) => (
              <span key={x.id} className="cap-tag">
                {label(x)}
                {owner && <button type="button" className="icon-btn icon-btn--sm" aria-label={`Elimina la fascia ${x.name} di ${d.toLowerCase()}`} onClick={() => void act(() => deleteWindow(x.id), 'Fascia eliminata.')}><Trash2 size={14} aria-hidden="true" /></button>}
              </span>
            ))}
          </li>
        ))}
      </ul>
      {owner && (
        <form className="cap-form" onSubmit={add} aria-label="Nuova fascia">
          <div className="editor__row">
            <div className="field"><label htmlFor="win-day">Giorno</label><select id="win-day" className="select" value={w.isoDay} onChange={(e) => up('isoDay', Number(e.target.value))}>{DAYS.map((d, i) => <option key={d} value={i + 1}>{d}</option>)}</select></div>
            <div className="field"><label htmlFor="win-name">Nome</label><input id="win-name" className="input" maxLength={40} value={w.name} onChange={(e) => up('name', e.target.value)} /></div>
            <div className="field"><label htmlFor="win-first">Primo arrivo</label><input id="win-first" className="input" type="time" step={900} value={w.firstStart} onChange={(e) => up('firstStart', e.target.value)} /></div>
            <div className="field"><label htmlFor="win-last">Ultimo arrivo</label><input id="win-last" className="input" type="time" step={900} value={w.lastStart} onChange={(e) => up('lastStart', e.target.value)} /></div>
          </div>
          <div className="editor__row">
            <div className="field"><label htmlFor="win-step">Ogni (minuti)</label><select id="win-step" className="select" value={w.slotMinutes} onChange={(e) => up('slotMinutes', Number(e.target.value))}>{[15, 30, 60].map((n) => <option key={n} value={n}>{n}</option>)}</select></div>
            <div className="field"><label htmlFor="win-dur">Permanenza (minuti)</label><input id="win-dur" className="input" type="number" min={30} step={15} value={w.defaultDurationMinutes} onChange={(e) => up('defaultDurationMinutes', Number(e.target.value))} /></div>
            <div className="field"><label htmlFor="win-max">Limite presenze</label><input id="win-max" className="input" type="number" min={0} placeholder="nessuno" value={w.maxCovers} onChange={(e) => up('maxCovers', e.target.value)} /></div>
          </div>
          <button type="submit" className="btn btn--ghost btn--sm"><Plus size={16} aria-hidden="true" /> Aggiungi la fascia</button>
        </form>
      )}
    </section>
  );
}

function TablesPanel({ config, owner, act }: { config: BookingConfig; owner: boolean; act: Act }) {
  const zone = config.zones[0];
  const [areaName, setAreaName] = useState('');
  const [t, setT] = useState({ areaId: '', code: '', minSeats: 2, maxSeats: 4 });
  const areas = config.areas.filter((a) => a.active);
  const areaId = t.areaId || areas[0]?.id || '';
  return (
    <section className="panel" aria-labelledby="cap-tables">
      <h2 id="cap-tables" className="panel__title">Aree e tavoli</h2>
      <p className="hint">I tavoli si scelgono a mano nella richiesta confermata: il sistema impedisce solo di dare lo stesso tavolo a due prenotazioni sovrapposte.</p>
      {areas.length === 0 && <p className="muted">Nessuna area: aggiungine una (es. Sala, Dehors).</p>}
      <ul className="cap-list">
        {areas.map((a) => {
          const tables = config.tables.filter((x) => x.areaId === a.id && x.active);
          return (
            <li key={a.id}>
              <strong>{a.name}</strong>
              {tables.length === 0 ? <span className="muted">nessun tavolo</span> : tables.map((x) => (
                <span key={x.id} className="cap-tag">
                  {x.code} · {x.minSeats === x.maxSeats ? x.maxSeats : `${x.minSeats}–${x.maxSeats}`} posti
                  {owner && <button type="button" className="icon-btn icon-btn--sm" aria-label={`Elimina il tavolo ${x.code}`} onClick={() => void act(() => deleteTable(x.id), 'Tavolo eliminato (o disattivato se aveva prenotazioni).')}><Trash2 size={14} aria-hidden="true" /></button>}
                </span>
              ))}
              {owner && tables.length === 0 && <button type="button" className="btn btn--quiet btn--sm" onClick={() => void act(() => deleteArea(a.id), 'Area eliminata.')}>Elimina l'area</button>}
            </li>
          );
        })}
      </ul>
      {owner && zone && (
        <div className="editor__row">
          <form className="cap-form" aria-label="Nuova area" onSubmit={(e) => { e.preventDefault(); void act(() => createArea(zone.id, areaName), 'Area aggiunta.').then((ok) => ok && setAreaName('')); }}>
            <div className="field"><label htmlFor="area-name">Nuova area</label><input id="area-name" className="input" maxLength={40} value={areaName} onChange={(e) => setAreaName(e.target.value)} placeholder="Es. Dehors" /></div>
            <button type="submit" className="btn btn--ghost btn--sm"><Plus size={16} aria-hidden="true" /> Aggiungi l'area</button>
          </form>
          {areas.length > 0 && (
            <form className="cap-form" aria-label="Nuovo tavolo" onSubmit={(e) => { e.preventDefault(); void act(() => createTable({ ...t, areaId }), `Tavolo ${t.code} aggiunto.`).then((ok) => ok && setT((x) => ({ ...x, code: '' }))); }}>
              <div className="editor__row">
                <div className="field"><label htmlFor="tb-area">Area</label><select id="tb-area" className="select" value={areaId} onChange={(e) => setT((x) => ({ ...x, areaId: e.target.value }))}>{areas.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}</select></div>
                <div className="field"><label htmlFor="tb-code">Codice</label><input id="tb-code" className="input" maxLength={20} value={t.code} onChange={(e) => setT((x) => ({ ...x, code: e.target.value }))} placeholder="Es. T7" /></div>
                <div className="field"><label htmlFor="tb-min">Posti min</label><input id="tb-min" className="input" type="number" min={1} value={t.minSeats} onChange={(e) => setT((x) => ({ ...x, minSeats: Number(e.target.value) }))} /></div>
                <div className="field"><label htmlFor="tb-max">Posti max</label><input id="tb-max" className="input" type="number" min={1} value={t.maxSeats} onChange={(e) => setT((x) => ({ ...x, maxSeats: Number(e.target.value) }))} /></div>
              </div>
              <button type="submit" className="btn btn--ghost btn--sm"><Plus size={16} aria-hidden="true" /> Aggiungi il tavolo</button>
            </form>
          )}
        </div>
      )}
    </section>
  );
}

function ExceptionsPanel({ config, owner, act }: { config: BookingConfig; owner: boolean; act: Act }) {
  const [x, setX] = useState({ serviceDate: todayIso(), kind: 'CLOSED' as 'CLOSED' | 'CAPACITY_OVERRIDE', capacityOverride: 20, reason: '' });
  const windowName = (id: string | null) => (id ? config.windows.find((w) => w.id === id)?.name ?? 'fascia' : 'tutto il giorno');
  return (
    <section className="panel" aria-labelledby="cap-exc">
      <h2 id="cap-exc" className="panel__title">Chiusure e limiti per data</h2>
      {config.exceptions.length === 0 ? <p className="muted">Nessuna chiusura o limite nei prossimi giorni.</p> : (
        <ul className="cap-list">
          {config.exceptions.map((e) => (
            <li key={e.id}>
              <strong>{fmtShort(e.serviceDate)}</strong>
              <span>{e.kind === 'CLOSED' ? 'Chiuso agli arrivi' : `Al massimo ${e.capacityOverride} persone`} · {windowName(e.serviceWindowId)}{e.reason ? ` · ${e.reason}` : ''}</span>
              {owner && <button type="button" className="icon-btn icon-btn--sm" aria-label={`Elimina l'eccezione del ${fmtShort(e.serviceDate)}`} onClick={() => void act(() => deleteException(e.id), 'Eccezione eliminata.')}><Trash2 size={14} aria-hidden="true" /></button>}
            </li>
          ))}
        </ul>
      )}
      {owner && (
        <form className="cap-form" aria-label="Nuova chiusura o limite" onSubmit={(e) => { e.preventDefault(); void act(() => addException({ serviceDate: x.serviceDate, kind: x.kind, capacityOverride: x.kind === 'CAPACITY_OVERRIDE' ? x.capacityOverride : null, reason: x.reason || undefined }), 'Eccezione aggiunta.'); }}>
          <div className="editor__row">
            <div className="field"><label htmlFor="exc-date">Data</label><input id="exc-date" className="input" type="date" min={todayIso()} value={x.serviceDate} onChange={(e) => setX((v) => ({ ...v, serviceDate: e.target.value }))} /></div>
            <div className="field"><label htmlFor="exc-kind">Tipo</label><select id="exc-kind" className="select" value={x.kind} onChange={(e) => setX((v) => ({ ...v, kind: e.target.value as typeof v.kind }))}><option value="CLOSED">Chiuso agli arrivi</option><option value="CAPACITY_OVERRIDE">Limite di presenze</option></select></div>
            {x.kind === 'CAPACITY_OVERRIDE' && <div className="field"><label htmlFor="exc-cap">Persone al massimo</label><input id="exc-cap" className="input" type="number" min={0} value={x.capacityOverride} onChange={(e) => setX((v) => ({ ...v, capacityOverride: Number(e.target.value) }))} /></div>}
            <div className="field"><label htmlFor="exc-reason">Motivo</label><input id="exc-reason" className="input" maxLength={200} value={x.reason} onChange={(e) => setX((v) => ({ ...v, reason: e.target.value }))} placeholder="Es. ferie, festa privata" /></div>
          </div>
          <button type="submit" className="btn btn--ghost btn--sm"><Plus size={16} aria-hidden="true" /> Aggiungi</button>
        </form>
      )}
    </section>
  );
}
