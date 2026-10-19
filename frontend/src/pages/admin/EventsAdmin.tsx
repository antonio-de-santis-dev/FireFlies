import { CircleAlert, Eye, Pencil, Plus, Save } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Img } from '../../components/ui';
import { PhotoPicker } from './media';
import { formatEventDate, type FireEvent } from '../../data/events';
import { Link, navigate, usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';

const StatusPill = ({ s }: { s: FireEvent['status'] }) => (
  <span className={`badge ${s === 'pubblicato' ? 'badge--success' : 'badge--info'}`}>{s === 'pubblicato' ? 'Pubblicato' : 'Bozza'}</span>
);

export function EventsAdmin() {
  usePageMeta('Gestione eventi');
  const { events, mediaOr } = useStore();
  const [tab, setTab] = useState<'tutti' | FireEvent['status']>('tutti');
  const rows = events.filter((e) => tab === 'tutti' || e.status === tab);
  return (
    <>
      <AdminHead title="Eventi" lead="Serate e format. Le bozze non compaiono sul sito.">
        <Link to="/gestione/eventi/nuovo" className="btn btn--action"><Plus size={20} aria-hidden="true" /> Nuova bozza</Link>
      </AdminHead>
      <div className="chips" role="group" aria-label="Filtra per stato">
        {(['tutti', 'pubblicato', 'bozza'] as const).map((t) => (
          <button key={t} type="button" className="chip" aria-pressed={tab === t} onClick={() => setTab(t)}>
            {t === 'tutti' ? 'Tutti' : t === 'pubblicato' ? 'Pubblicati' : 'Bozze'}
            <span className="count">{events.filter((e) => t === 'tutti' || e.status === t).length}</span>
          </button>
        ))}
      </div>
      <ul className="ev-admin">
        {rows.map((e) => (
          <li key={e.id} className="ev-admin__item">
            <Img asset={mediaOr(e.photo)} decorative className="ev-admin__img" />
            <div className="ev-admin__body">
              <p className="ev-admin__badges">
                <StatusPill s={e.status} />
                {e.kind === 'format' && <span className="badge badge--neutral">Format</span>}
                {e.demo && <span className="badge badge--demo">Esempio</span>}
              </p>
              <h2>{e.title}</h2>
              <p className="muted small">{formatEventDate(e)} · {e.artist}</p>
            </div>
            <div className="ev-admin__actions">
              {e.status === 'pubblicato' && (
                <Link to={`/eventi/${e.id}`} className="btn btn--quiet btn--sm" aria-label={`Vedi sul sito: ${e.title}`}><Eye size={16} aria-hidden="true" /> Sul sito</Link>
              )}
              <Link to={`/gestione/eventi/${e.id}`} className="btn btn--ghost btn--sm" aria-label={`Modifica ${e.title}`}><Pencil size={16} aria-hidden="true" /> Modifica</Link>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}

const blank = (): FireEvent => ({
  id: `serata-${Math.random().toString(36).slice(2, 7)}`,
  title: '', kind: 'serata', status: 'bozza', date: null, time: null, demo: true,
  artist: '', summary: '', body: [''], photo: 'djSet', media: ['djSet'], seats: '', action: 'tavolo',
});

export function EventEditor({ id }: { id: string }) {
  const { events, dispatch, notify, mediaOr } = useStore();
  const existing = events.find((e) => e.id === id);
  const isNew = id === 'nuovo';
  usePageMeta(isNew ? 'Nuova bozza evento' : existing ? `Modifica ${existing.title}` : 'Evento non trovato');
  const [ev, setEv] = useState<FireEvent>(() => existing ?? blank());
  const [errors, setErrors] = useState<{ title?: string; date?: string }>({});

  if (!existing && !isNew) {
    return (
      <div className="empty empty--admin">
        <h1>Evento non trovato</h1>
        <Link to="/gestione/eventi" className="btn btn--ghost">Torna agli eventi</Link>
      </div>
    );
  }

  const up = <K extends keyof FireEvent>(k: K, v: FireEvent[K]) => setEv((s) => ({ ...s, [k]: v }));

  const save = (status: FireEvent['status']) => (e?: FormEvent) => {
    e?.preventDefault();
    const next: typeof errors = {};
    if (ev.title.trim().length < 3) next.title = 'Dai un titolo alla serata (almeno 3 caratteri).';
    if (status === 'pubblicato' && ev.kind === 'serata' && !ev.date) next.date = 'Per pubblicare una serata serve la data. Puoi salvarla come bozza senza.';
    setErrors(next);
    if (Object.keys(next).length) {
      document.getElementById(next.title ? 'ee-title' : 'ee-date')?.focus();
      return;
    }
    dispatch({ type: 'event/save', event: { ...ev, status, title: ev.title.trim() } });
    notify(status === 'pubblicato' ? 'Evento pubblicato sul sito demo (solo in questa sessione).' : 'Bozza salvata solo in questa sessione demo.');
    navigate('/gestione/eventi');
  };

  return (
    <form className="editor" noValidate onSubmit={save(ev.status)}>
      <AdminHead title={isNew ? 'Nuova bozza' : `Modifica: ${existing!.title}`} lead="Compila, salva come bozza o pubblica.">
        <Link to="/gestione/eventi" className="btn btn--quiet">Annulla</Link>
        <button type="button" className="btn btn--ghost" onClick={save('bozza')}><Save size={18} aria-hidden="true" /> Salva bozza</button>
        <button type="button" className="btn btn--action" onClick={save('pubblicato')}>Pubblica</button>
      </AdminHead>

      <div className="editor__grid">
        <div className="panel editor__main">
          <div className="field">
            <label htmlFor="ee-title">Titolo</label>
            {errors.title && <p className="error" id="ee-title-err"><CircleAlert size={16} aria-hidden="true" />{errors.title}</p>}
            <input id="ee-title" className="input" value={ev.title} onChange={(e) => up('title', e.target.value)}
              aria-invalid={errors.title ? true : undefined} aria-describedby={errors.title ? 'ee-title-err' : undefined} />
          </div>
          <fieldset className="field">
            <legend>Tipo</legend>
            <div className="radio-row">
              <label className="check"><input type="radio" name="kind" checked={ev.kind === 'serata'} onChange={() => up('kind', 'serata')} /> <span>Serata singola</span></label>
              <label className="check"><input type="radio" name="kind" checked={ev.kind === 'format'} onChange={() => up('kind', 'format')} /> <span>Format ricorrente</span></label>
            </div>
          </fieldset>
          <div className="editor__row">
            <div className="field">
              <label htmlFor="ee-date">Data</label>
              {errors.date && <p className="error" id="ee-date-err"><CircleAlert size={16} aria-hidden="true" />{errors.date}</p>}
              <input id="ee-date" className="input" type="date" value={ev.date ?? ''} onChange={(e) => up('date', e.target.value || null)}
                aria-invalid={errors.date ? true : undefined} aria-describedby={errors.date ? 'ee-date-err' : undefined} />
            </div>
            <div className="field">
              <label htmlFor="ee-time">Orario</label>
              <input id="ee-time" className="input" type="time" value={ev.time ?? ''} onChange={(e) => up('time', e.target.value || null)} />
            </div>
          </div>
          <div className="field">
            <label htmlFor="ee-artist">Artista / DJ</label>
            <input id="ee-artist" className="input" value={ev.artist} onChange={(e) => up('artist', e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ee-summary">Frase di lancio</label>
            <input id="ee-summary" className="input" maxLength={140} value={ev.summary} onChange={(e) => up('summary', e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ee-body">Descrizione</label>
            <textarea id="ee-body" className="textarea" value={ev.body.join('\n\n')} onChange={(e) => up('body', e.target.value.split(/\n{2,}/))} />
          </div>
          <div className="editor__row">
            <div className="field">
              <label htmlFor="ee-seats">Posti / indicazioni</label>
              <input id="ee-seats" className="input" placeholder="es. Tavoli su richiesta" value={ev.seats} onChange={(e) => up('seats', e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="ee-action">Pulsante sul sito</label>
              <select id="ee-action" className="select" value={ev.action} onChange={(e) => up('action', e.target.value as FireEvent['action'])}>
                <option value="tavolo">Richiedi un tavolo</option>
                <option value="info">Chiedi info su Instagram</option>
              </select>
            </div>
          </div>
        </div>
        <aside className="editor__side">
          <div className="panel">
            <h2 className="panel__title">Stato</h2>
            <p><StatusPill s={ev.status} /></p>
            <p className="small muted">«Pubblica» la rende visibile nella pagina Eventi.</p>
          </div>
          <div className="panel">
            <h2 className="panel__title">Locandina</h2>
            <Img asset={mediaOr(ev.photo)} className="editor__preview" />
            <PhotoPicker legend="Scegli o carica la locandina" value={ev.photo} onChange={(id) => id && setEv((x) => ({ ...x, photo: id, media: [id, ...x.media.filter((m) => m !== id)] }))} />
          </div>
        </aside>
      </div>
    </form>
  );
}
