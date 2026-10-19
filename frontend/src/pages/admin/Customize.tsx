import { ArrowDown, ArrowUp, CircleAlert, Eye, Plus, Save, Trash2, X } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { ApiError, problemField } from '../../api/client';
import { NEW_ID, saveSiteContent } from '../../api/content';
import { Img } from '../../components/ui';
import { homeSectionLabels, type HomeSection, type SiteContent } from '../../data/content';
import { Link, usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { PhotoPicker } from './media';
import { Switch } from './MenuAdmin';

/** Gestione → Personalizza il sito: riservata al titolare. */
export function Customize() {
  usePageMeta('Personalizza il sito');
  const { content, dispatch, notify, mediaOr } = useStore();
  const [c, setC] = useState<SiteContent>(content);
  const [error, setError] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const up = <K extends keyof SiteContent>(k: K, v: SiteContent[K]) => setC((x) => ({ ...x, [k]: v }));
  const moveHero = (i: number, d: -1 | 1) => {
    const photos = [...c.menuHero.photos];
    [photos[i], photos[i + d]] = [photos[i + d], photos[i]];
    up('menuHero', { ...c.menuHero, photos });
  };
  const upOwner = (id: string, patch: Partial<SiteContent['owners'][number]>) =>
    up('owners', c.owners.map((o) => (o.id === id ? { ...o, ...patch } : o)));
  const upStaff = (id: string, patch: Partial<SiteContent['staff'][number]>) =>
    up('staff', c.staff.map((s) => (s.id === id ? { ...s, ...patch } : s)));

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setFailure(null);
    if (c.heroClaim.trim().length < 3) {
      setError('La frase principale non può restare vuota.');
      document.getElementById('cu-claim')?.focus();
      return;
    }
    setError(null);
    const nameless = c.staff.findIndex((s) => !s.name.trim());
    if (nameless >= 0) {
      setFailure(`Scrivi il nome della persona ${nameless + 1} dello staff, oppure rimuovila.`);
      document.getElementById(`st-name-${c.staff[nameless].id}`)?.focus();
      return;
    }
    setBusy(true);
    try {
      const saved = await saveSiteContent(c);
      dispatch({ type: 'content/save', content: saved.content, mediaIndex: saved.mediaIndex });
      setC(saved.content);
      notify('Sito aggiornato: le modifiche sono già visibili ai clienti.');
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Salvataggio non riuscito: riprova.';
      if (problemField(err) === 'heroClaim') {
        setError(message);
        document.getElementById('cu-claim')?.focus();
      } else {
        setFailure(message);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="editor" noValidate onSubmit={save}>
      <AdminHead title="Personalizza il sito" lead="Testi, foto e sezioni che vedono i clienti. Riservato al titolare.">
        <Link to="/" className="btn btn--quiet"><Eye size={18} aria-hidden="true" /> Vedi il sito</Link>
        <button type="submit" className="btn btn--action" disabled={busy}><Save size={18} aria-hidden="true" /> {busy ? 'Pubblicazione…' : 'Salva e pubblica'}</button>
      </AdminHead>
      {failure && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {failure}</p>}

      <div className="customize">
        <section className="panel" aria-labelledby="cu-home">
          <h2 id="cu-home" className="panel__title">Apertura della home</h2>
          <div className="customize__hero-preview" aria-hidden="true">
            <Img asset={mediaOr(c.heroPhoto)} decorative />
            <div>
              <strong>{c.heroClaim || 'Frase principale'}</strong>
              <span>{c.heroLead}</span>
            </div>
          </div>
          <div className="field">
            <label htmlFor="cu-claim">Frase principale</label>
            {error && <p className="error" id="cu-claim-err">{error}</p>}
            <input id="cu-claim" className="input" maxLength={60} value={c.heroClaim} onChange={(e) => up('heroClaim', e.target.value)}
              aria-invalid={error ? true : undefined} aria-describedby={error ? 'cu-claim-err' : 'cu-claim-hint'} />
            <p className="hint" id="cu-claim-hint">{c.heroClaim.length}/60 caratteri: deve stare in due o tre righe.</p>
          </div>
          <div className="field">
            <label htmlFor="cu-lead">Frase di presentazione</label>
            <textarea id="cu-lead" className="textarea" maxLength={180} value={c.heroLead} onChange={(e) => up('heroLead', e.target.value)} />
          </div>
          <PhotoPicker legend="Foto di apertura" value={c.heroPhoto} onChange={(id) => id && up('heroPhoto', id)} />
        </section>

        <section className="panel" aria-labelledby="cu-sections">
          <h2 id="cu-sections" className="panel__title">Sezioni della home</h2>
          <p className="small muted">Spegni una sezione per nasconderla (es. «Anteprima serate» quando non ci sono eventi).</p>
          <ul className="customize__sections">
            {(Object.keys(homeSectionLabels) as HomeSection[]).map((k) => (
              <li key={k}>
                <Switch checked={c.sections[k]} label={homeSectionLabels[k]}
                  onChange={() => up('sections', { ...c.sections, [k]: !c.sections[k] })} />
              </li>
            ))}
          </ul>
        </section>

        <section className="panel" aria-labelledby="cu-story">
          <h2 id="cu-story" className="panel__title">Pagina «Il locale»</h2>
          <div className="field">
            <label htmlFor="cu-story-title">Titolo del racconto</label>
            <input id="cu-story-title" className="input" value={c.storyTitle} onChange={(e) => up('storyTitle', e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="cu-story-text">Racconto</label>
            <textarea id="cu-story-text" className="textarea textarea--tall" value={c.storyText} onChange={(e) => up('storyText', e.target.value)} />
            <p className="hint">Lascia una riga vuota per andare a capo con un nuovo paragrafo.</p>
          </div>
          <label className="check">
            <input type="checkbox" checked={c.storyConfirmed} onChange={(e) => up('storyConfirmed', e.target.checked)} />
            <span>Testo definitivo (toglie l'etichetta «da scrivere» dal sito)</span>
          </label>
        </section>

        <section className="panel customize__menuhero" aria-labelledby="cu-menuhero">
          <h2 id="cu-menuhero" className="panel__title">Testata della pagina Menu</h2>
          <p className="small muted">Le foto ruotano con una dissolvenza nell'ordine qui sotto. Il visitatore può mettere in pausa.</p>
          {c.menuHero.photos.length === 0 && <p className="error">Scegli almeno una foto: senza foto la testata resta scura.</p>}
          <ol className="hero-order">
            {c.menuHero.photos.map((id, i) => (
              <li key={id} className="hero-order__item">
                <Img asset={mediaOr(id)} decorative className="hero-order__img" />
                <span className="hero-order__n">{i + 1}</span>
                <div className="hero-order__actions">
                  <button type="button" className="icon-btn" aria-label={`Sposta prima la foto ${i + 1}`} disabled={i === 0} onClick={() => moveHero(i, -1)}><ArrowUp size={16} aria-hidden="true" /></button>
                  <button type="button" className="icon-btn" aria-label={`Sposta dopo la foto ${i + 1}`} disabled={i === c.menuHero.photos.length - 1} onClick={() => moveHero(i, 1)}><ArrowDown size={16} aria-hidden="true" /></button>
                  <button type="button" className="icon-btn" aria-label={`Togli la foto ${i + 1} dalla testata`} onClick={() => up('menuHero', { ...c.menuHero, photos: c.menuHero.photos.filter((x) => x !== id) })}><X size={16} aria-hidden="true" /></button>
                </div>
              </li>
            ))}
          </ol>
          <div className="editor__row">
            <div className="field">
              <label htmlFor="cu-interval">Cambia foto ogni</label>
              <select id="cu-interval" className="select" value={c.menuHero.intervalSec} onChange={(e) => up('menuHero', { ...c.menuHero, intervalSec: Number(e.target.value) })}>
                {[10, 30, 60, 120, 300].map((s) => <option key={s} value={s}>{s < 60 ? `${s} secondi` : s === 60 ? '1 minuto (consigliato)' : `${s / 60} minuti`}</option>)}
              </select>
            </div>
          </div>
          <details className="staff-edit__photo">
            <summary>Aggiungi foto alla testata</summary>
            <PhotoPicker legend="Scegli una foto da aggiungere" value={null} onChange={(id) => id && !c.menuHero.photos.includes(id) && up('menuHero', { ...c.menuHero, photos: [...c.menuHero.photos, id] })} />
          </details>
        </section>

        <section className="panel customize__owners" aria-labelledby="cu-owners">
          <h2 id="cu-owners" className="panel__title">I titolari (pagina «Il locale»)</h2>
          <p className="small muted">Nome, ruolo, descrizione, frase da citare e foto (fronte e retro) di ogni titolare. L’anteprima si aggiorna subito.</p>
          <ul className="staff-edit">
            {c.owners.map((o, i) => (
              <li key={o.id} className="staff-edit__item owner-edit">
                <div className="owner-edit__previews" aria-label={`Anteprima foto di ${o.name}`}>
                  <figure><Img asset={mediaOr(o.front)} className="owner-edit__img" decorative /><figcaption>Fronte</figcaption></figure>
                  <figure><Img asset={mediaOr(o.back)} className="owner-edit__img" decorative /><figcaption>Retro</figcaption></figure>
                </div>
                <div className="owner-edit__fields">
                  <div className="editor__row">
                    <div className="field">
                      <label htmlFor={`ow-name-${o.id}`}>Nome titolare {i + 1}</label>
                      <input id={`ow-name-${o.id}`} className="input" value={o.name} onChange={(e) => upOwner(o.id, { name: e.target.value })} />
                    </div>
                    <div className="field">
                      <label htmlFor={`ow-role-${o.id}`}>Ruolo</label>
                      <input id={`ow-role-${o.id}`} className="input" value={o.role} onChange={(e) => upOwner(o.id, { role: e.target.value })} />
                    </div>
                  </div>
                  <div className="field">
                    <label htmlFor={`ow-desc-${o.id}`}>Descrizione</label>
                    <textarea id={`ow-desc-${o.id}`} className="textarea" maxLength={320} value={o.description} onChange={(e) => upOwner(o.id, { description: e.target.value })} />
                  </div>
                  <div className="field">
                    <label htmlFor={`ow-motto-${o.id}`}>Frase da citare <span className="muted">(vuota = nessuna citazione)</span></label>
                    <input id={`ow-motto-${o.id}`} className="input" maxLength={80} value={o.motto} onChange={(e) => upOwner(o.id, { motto: e.target.value })} />
                  </div>
                  <details className="staff-edit__photo">
                    <summary>Cambia le foto di {o.name !== 'Nome da inserire' ? o.name : `titolare ${i + 1}`}</summary>
                    <p className="small muted">Meglio foto scontornate (PNG/WebP con sfondo trasparente), in verticale.</p>
                    <PhotoPicker legend="Foto principale (fronte)" value={o.front} onChange={(id) => id && upOwner(o.id, { front: id })} />
                    <PhotoPicker legend="Foto del retro (mostrata con «Gira la maglia»)" value={o.back} onChange={(id) => id && upOwner(o.id, { back: id })} />
                  </details>
                </div>
              </li>
            ))}
          </ul>
          <label className="check">
            <input type="checkbox" checked={c.ownersConfirmed} onChange={(e) => up('ownersConfirmed', e.target.checked)} />
            <span>Testi dei titolari definitivi</span>
          </label>
        </section>

        <section className="panel" aria-labelledby="cu-staff">
          <div className="panel__head">
            <h2 id="cu-staff" className="panel__title">Lo staff</h2>
            <button type="button" className="btn btn--ghost btn--sm"
              onClick={() => up('staff', [...c.staff, { id: `${NEW_ID}${Date.now()}`, name: '', role: '', photo: c.heroPhoto }])}>
              <Plus size={16} aria-hidden="true" /> Aggiungi persona
            </button>
          </div>
          <p className="small muted">Pubblica foto e nomi solo con il consenso delle persone.</p>
          <ul className="staff-edit">
            {c.staff.map((s, i) => (
              <li key={s.id} className="staff-edit__item">
                <div className="editor__row">
                  <div className="field">
                    <label htmlFor={`st-name-${s.id}`}>Nome ({i + 1})</label>
                    <input id={`st-name-${s.id}`} className="input" value={s.name} onChange={(e) => upStaff(s.id, { name: e.target.value })} />
                  </div>
                  <div className="field">
                    <label htmlFor={`st-role-${s.id}`}>Ruolo</label>
                    <input id={`st-role-${s.id}`} className="input" value={s.role} onChange={(e) => upStaff(s.id, { role: e.target.value })} />
                  </div>
                </div>
                <details className="staff-edit__photo">
                  <summary>Cambia foto</summary>
                  <PhotoPicker legend={`Foto di ${s.name || 'questa persona'}`} value={s.photo} onChange={(id) => id && upStaff(s.id, { photo: id })} />
                </details>
                <button type="button" className="btn btn--quiet btn--sm" onClick={() => up('staff', c.staff.filter((x) => x.id !== s.id))}>
                  <Trash2 size={16} aria-hidden="true" /> Rimuovi
                </button>
              </li>
            ))}
          </ul>
          <label className="check">
            <input type="checkbox" checked={c.staffConfirmed} onChange={(e) => up('staffConfirmed', e.target.checked)} />
            <span>Nomi e ruoli confermati</span>
          </label>
        </section>
      </div>
    </form>
  );
}
