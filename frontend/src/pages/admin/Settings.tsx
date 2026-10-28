import { CircleAlert, Save } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { saveSettings } from '../../api/content';
import { bookingConfig, savePolicy, type Policy } from '../../api/reservations';
import type { SiteSettings } from '../../data/site';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { CapacitySettings, ClosureConfirm, closureConflicts } from './CapacitySettings';
import { Switch } from './MenuAdmin';
import { WhatsAppStatus } from './WhatsAppStatus';

/** Interruttore delle richieste dal sito: salva subito la regola sul server (capienza e calendario nella sezione «Capienza»). */
function BookingSwitch() {
  const { notify } = useStore();
  const [policy, setPolicy] = useState<Policy | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    bookingConfig().then((c) => setPolicy(c.policy)).catch(() => setPolicy(null));
  }, []);
  const toggle = async () => {
    if (!policy || busy) return;
    setBusy(true);
    try {
      const saved = await savePolicy({
        webBookingEnabled: !policy.webBookingEnabled, minLeadMinutes: policy.minLeadMinutes, maxAdvanceDays: policy.maxAdvanceDays,
        pendingExpiryMinutesBeforeStart: policy.pendingExpiryMinutesBeforeStart,
      });
      setPolicy(saved);
      notify(saved.webBookingEnabled ? 'Richieste dal sito aperte.' : 'Richieste dal sito chiuse: il modulo invita a chiamare.');
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Modifica non riuscita: riprova.');
    } finally {
      setBusy(false);
    }
  };
  const on = !!policy?.webBookingEnabled;
  return (
    <>
      <Switch checked={on} onChange={() => void toggle()} label={on ? 'Richieste tavolo attive' : 'Richieste tavolo disattivate'} />
      <p className="hint">Vale subito per il sito. Capienza del locale, chiusure e regole si impostano qui sotto, in <a href="#capienza" onClick={(e) => { e.preventDefault(); document.getElementById('capienza')?.scrollIntoView(); }}>Capienza e calendario</a>.</p>
    </>
  );
}

export function Settings() {
  usePageMeta('Impostazioni');
  const { settings, dispatch, notify } = useStore();
  const [s, setS] = useState<SiteSettings>(settings);
  const up = <K extends keyof SiteSettings>(k: K, v: SiteSettings[K]) => setS((x) => ({ ...x, [k]: v }));
  const upHour = (i: number, patch: Partial<SiteSettings['hours'][number]>) =>
    up('hours', s.hours.map((h, j) => (j === i ? { ...h, ...patch } : h)));

  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [closures, setClosures] = useState<ReturnType<typeof closureConflicts>>(null);

  const persist = async (confirmClosures: boolean) => {
    if (busy) return;
    setFailure(null);
    setBusy(true);
    try {
      const saved = await saveSettings(s, confirmClosures);
      dispatch({ type: 'settings/save', settings: saved });
      setS(saved);
      setClosures(null);
      notify('Impostazioni salvate: il sito mostra già i nuovi orari e contatti.');
    } catch (err) {
      // FIX 2: giorno chiuso con prenotazioni attive → conferma consapevole, nessun annullamento automatico
      const conflicts = closureConflicts(err);
      setClosures(conflicts);
      if (!conflicts) setFailure(err instanceof ApiError ? err.message : 'Salvataggio non riuscito: riprova.');
    } finally {
      setBusy(false);
    }
  };
  const save = (e: FormEvent) => {
    e.preventDefault();
    void persist(false);
  };

  return (
    <>
    <form className="editor" noValidate onSubmit={save}>
      <AdminHead title="Impostazioni" lead="Orari, contatti, mappa, capienza e calendario. Riservato al titolare.">
        <button type="submit" className="btn btn--action" disabled={busy}><Save size={18} aria-hidden="true" /> {busy ? 'Salvataggio…' : 'Salva'}</button>
      </AdminHead>
      {failure && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {failure}</p>}
      {closures && <ClosureConfirm conflicts={closures} busy={busy} onCancel={() => setClosures(null)} onConfirm={() => void persist(true)} />}

      <div className="settings">
        <section className="panel hours-panel" aria-labelledby="set-hours">
          <h2 id="set-hours" className="panel__title">Orari di apertura</h2>
          {/* FIX-1, req. 6.2: una riga per giorno (scheda sul telefono): nome, interruttore Aperto/Chiuso, orari solo se aperto */}
          <ul className="hours-list" aria-label="Orari per giorno">
            {s.hours.map((h, i) => (
              <li key={h.day} className={`hours-day${h.open ? '' : ' is-closed'}`}>
                <span className="hours-day__name" id={`h-day-${i}`}>{h.day}</span>
                <Switch checked={!!h.open} label={h.open ? 'Aperto' : 'Chiuso'} ariaLabel={`${h.day}: ${h.open ? 'aperto' : 'chiuso'}`}
                  onChange={() => upHour(i, h.open ? { open: null, close: null } : { open: '07:00', close: '23:00' })} />
                {h.open ? (
                  <div className="hours-day__times" role="group" aria-labelledby={`h-day-${i}`}>
                    <label htmlFor={`h-open-${i}`}>Dalle<span className="sr-only"> ({h.day})</span></label>
                    <input id={`h-open-${i}`} className="input input--sm" type="time" value={h.open ?? ''} onChange={(e) => upHour(i, { open: e.target.value })} />
                    <label htmlFor={`h-close-${i}`}>alle<span className="sr-only"> ({h.day})</span></label>
                    <input id={`h-close-${i}`} className="input input--sm" type="time" value={h.close ?? ''} onChange={(e) => upHour(i, { close: e.target.value })} />
                  </div>
                ) : <span className="hours-day__closed">Chiuso tutto il giorno</span>}
              </li>
            ))}
          </ul>
          {/* FIX 2, fasceorarie: gli orari di apertura sono l'unica impostazione del calendario delle prenotazioni */}
          <p className="hint" id="hours-arrivals-hint">Valgono anche per le prenotazioni: il sito propone gli arrivi di Colazione (07:30–10:30),
            Pomeriggio (15:00–17:00), Aperitivo (18:00–20:30) e Sera (21:00–22:00) che stanno interamente dentro l'apertura, permanenza
            compresa. Un giorno aperto è subito prenotabile, senza altro da configurare.</p>
          <label className="check">
            <input type="checkbox" checked={s.hoursConfirmed} onChange={(e) => up('hoursConfirmed', e.target.checked)} />
            <span>Orari verificati dal gestore (toglie l'etichetta «Da confermare» dal sito)</span>
          </label>
        </section>

        <section className="panel" aria-labelledby="set-contacts">
          <h2 id="set-contacts" className="panel__title">Contatti</h2>
          <div className="field">
            <label htmlFor="set-addr">Indirizzo</label>
            <input id="set-addr" className="input" value={s.addressLine} onChange={(e) => up('addressLine', e.target.value)} />
          </div>
          <label className="check">
            <input type="checkbox" checked={s.addressConfirmed} onChange={(e) => up('addressConfirmed', e.target.checked)} />
            <span>Indirizzo verificato</span>
          </label>
          <div className="editor__row">
            <div className="field">
              <label htmlFor="set-phone">Telefono</label>
              <input id="set-phone" className="input" type="tel" placeholder="Non ancora fornito" value={s.phone ?? ''} onChange={(e) => up('phone', e.target.value || null)} />
            </div>
            <div className="field">
              <label htmlFor="set-mail">Email</label>
              <input id="set-mail" className="input" type="email" placeholder="Non ancora fornita" value={s.email ?? ''} onChange={(e) => up('email', e.target.value || null)} />
            </div>
          </div>
          <div className="field">
            <label htmlFor="set-ig">Instagram</label>
            <input id="set-ig" className="input" type="url" value={s.instagram} onChange={(e) => up('instagram', e.target.value)} />
          </div>
          <BookingSwitch />
        </section>

        <section className="panel" aria-labelledby="set-map">
          <h2 id="set-map" className="panel__title">Mappa nella pagina Contatti</h2>
          <div className="field">
            <label htmlFor="set-mapq">Indirizzo o nome cercato su Google Maps</label>
            <input id="set-mapq" className="input" value={s.mapQuery} onChange={(e) => up('mapQuery', e.target.value)} aria-describedby="set-mapq-hint" />
            <p className="hint" id="set-mapq-hint">Meglio l'indirizzo completo, es. «Via …, 73025 Martano LE».</p>
          </div>
          <fieldset className="field">
            <legend>Quando caricare la mappa</legend>
            <div className="radio-row radio-row--stack">
              <label className="check">
                <input type="radio" name="mapmode" checked={s.mapMode === 'consenso'} onChange={() => up('mapMode', 'consenso')} />
                <span><strong>Dopo un clic del visitatore</strong> (consigliato: nessun cookie di Google finché non sceglie)</span>
              </label>
              <label className="check">
                <input type="radio" name="mapmode" checked={s.mapMode === 'automatico'} onChange={() => up('mapMode', 'automatico')} />
                <span><strong>Subito</strong> (richiede banner cookie con consenso preventivo)</span>
              </label>
            </div>
          </fieldset>
        </section>
      </div>
    </form>
    <CapacitySettings />
    <WhatsAppStatus />
    </>
  );
}
