import { Save } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import type { SiteSettings } from '../../data/site';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { Switch } from './MenuAdmin';

export function Settings() {
  usePageMeta('Orari e contatti');
  const { settings, dispatch, notify } = useStore();
  const [s, setS] = useState<SiteSettings>(settings);
  const up = <K extends keyof SiteSettings>(k: K, v: SiteSettings[K]) => setS((x) => ({ ...x, [k]: v }));
  const upHour = (i: number, patch: Partial<SiteSettings['hours'][number]>) =>
    up('hours', s.hours.map((h, j) => (j === i ? { ...h, ...patch } : h)));

  const save = (e: FormEvent) => {
    e.preventDefault();
    dispatch({ type: 'settings/save', settings: s });
    notify('Impostazioni salvate solo in questa sessione demo.');
  };

  return (
    <form className="editor" noValidate onSubmit={save}>
      <AdminHead title="Orari e contatti" lead="Orari, contatti, mappa e richieste tavolo. Riservato al titolare.">
        <button type="submit" className="btn btn--action"><Save size={18} aria-hidden="true" /> Salva</button>
      </AdminHead>

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
          <Switch checked={s.bookingEnabled} onChange={() => up('bookingEnabled', !s.bookingEnabled)} label={s.bookingEnabled ? 'Richieste tavolo attive' : 'Richieste tavolo disattivate'} />
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
  );
}
