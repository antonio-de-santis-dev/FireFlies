import { CircleAlert, Save } from 'lucide-react';
import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { saveSettings } from '../../api/content';
import { bookingConfig, savePolicy, type Policy } from '../../api/reservations';
import type { SiteSettings } from '../../data/site';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { CapacitySettings } from './CapacitySettings';
import { Switch } from './MenuAdmin';

/** Interruttore delle richieste dal sito: salva subito la regola sul server (fasce e persone nella sezione «Capienza»). */
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
        maxPartySizeWeb: policy.maxPartySizeWeb, pendingExpiryMinutesBeforeStart: policy.pendingExpiryMinutesBeforeStart,
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
      <p className="hint">Vale subito per il sito. Persone per fascia, chiusure e limiti si impostano qui sotto, in <a href="#capienza" onClick={(e) => { e.preventDefault(); document.getElementById('capienza')?.scrollIntoView(); }}>Capienza</a>.</p>
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

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setFailure(null);
    setBusy(true);
    try {
      const saved = await saveSettings(s);
      dispatch({ type: 'settings/save', settings: saved });
      setS(saved);
      notify('Impostazioni salvate: il sito mostra già i nuovi orari e contatti.');
    } catch (err) {
      setFailure(err instanceof ApiError ? err.message : 'Salvataggio non riuscito: riprova.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
    <form className="editor" noValidate onSubmit={save}>
      <AdminHead title="Impostazioni" lead="Orari, indirizzo, contatti, mappa e capienza. Riservato al titolare.">
        <button type="submit" className="btn btn--action" disabled={busy}><Save size={18} aria-hidden="true" /> {busy ? 'Salvataggio…' : 'Salva'}</button>
      </AdminHead>
      {failure && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {failure}</p>}

      <div className="settings">
        <section className="panel" aria-labelledby="set-hours">
          <h2 id="set-hours" className="panel__title">Orari di apertura</h2>
          <table className="hours-table">
            <caption className="sr-only">Orari per giorno</caption>
            <thead>
              <tr><th scope="col">Giorno</th><th scope="col">Aperto</th><th scope="col">Apertura</th><th scope="col">Chiusura</th></tr>
            </thead>
            <tbody>
              {s.hours.map((h, i) => (
                <tr key={h.day}>
                  <th scope="row">{h.day}</th>
                  <td>
                    <Switch checked={!!h.open} label={h.open ? 'Sì' : 'Chiuso'}
                      onChange={() => upHour(i, h.open ? { open: null, close: null } : { open: '07:00', close: '23:00' })} />
                  </td>
                  <td>
                    <label className="sr-only" htmlFor={`h-open-${i}`}>Apertura {h.day}</label>
                    <input id={`h-open-${i}`} className="input input--sm" type="time" value={h.open ?? ''} disabled={!h.open} onChange={(e) => upHour(i, { open: e.target.value })} />
                  </td>
                  <td>
                    <label className="sr-only" htmlFor={`h-close-${i}`}>Chiusura {h.day}</label>
                    <input id={`h-close-${i}`} className="input input--sm" type="time" value={h.close ?? ''} disabled={!h.open} onChange={(e) => upHour(i, { close: e.target.value })} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
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
    </>
  );
}
