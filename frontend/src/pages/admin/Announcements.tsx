import { CircleAlert, Megaphone, Plus, Trash2 } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { createAnnouncement, deleteAnnouncement, updateAnnouncement } from '../../api/content';
import type { Announcement } from '../../data/content';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { Switch } from './MenuAdmin';

const blank = (): Announcement => ({ id: '', text: '', tone: 'info', active: true, until: null });

/** Gestione → Comunicazioni: avvisi in cima al sito (chiusure, serate, novità). */
export function Announcements() {
  usePageMeta('Comunicazioni');
  const { announcements, dispatch, notify } = useStore();
  const [draft, setDraft] = useState<Announcement>(blank);
  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);

  const publish = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (draft.text.trim().length < 5) {
      setError('Scrivi il testo dell’avviso (almeno 5 caratteri).');
      document.getElementById('an-text')?.focus();
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const saved = await createAnnouncement({ ...draft, text: draft.text.trim() });
      dispatch({ type: 'announcement/save', item: saved });
      notify(saved.active ? 'Avviso pubblicato in cima al sito.' : 'Avviso salvato, non ancora visibile.');
      setDraft(blank());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Avviso non salvato: riprova.');
      document.getElementById('an-text')?.focus();
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (a: Announcement) => {
    try {
      const saved = await updateAnnouncement({ ...a, active: !a.active });
      dispatch({ type: 'announcement/save', item: saved });
      notify(saved.active ? 'Avviso visibile sul sito.' : 'Avviso nascosto dal sito.');
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Modifica non salvata: riprova.');
    }
  };

  const remove = async (a: Announcement) => {
    try {
      await deleteAnnouncement(a.id);
      dispatch({ type: 'announcement/remove', id: a.id });
      notify('Avviso eliminato.');
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Avviso non eliminato: riprova.');
    }
  };

  return (
    <>
      <AdminHead title="Comunicazioni" lead="Avvisi brevi mostrati in cima a tutte le pagine del sito." />

      <form className="panel announce-form" noValidate onSubmit={publish} aria-labelledby="an-new">
        <h2 id="an-new" className="panel__title"><Megaphone size={20} aria-hidden="true" /> Nuovo avviso</h2>
        <div className="field">
          <label htmlFor="an-text">Testo</label>
          {error && <p className="error" id="an-text-err"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
          <input id="an-text" className="input" maxLength={140} placeholder="Es. Stasera chiusura anticipata alle 21:00"
            value={draft.text} onChange={(e) => setDraft({ ...draft, text: e.target.value })}
            aria-invalid={error ? true : undefined} aria-describedby={error ? 'an-text-err' : 'an-text-hint'} />
          <p className="hint" id="an-text-hint">{draft.text.length}/140 caratteri.</p>
        </div>
        <div className="editor__row">
          <div className="field">
            <label htmlFor="an-tone">Tipo</label>
            <select id="an-tone" className="select" value={draft.tone} onChange={(e) => setDraft({ ...draft, tone: e.target.value as Announcement['tone'] })}>
              <option value="info">Informazione (fondo scuro)</option>
              <option value="importante">Importante (fondo fucsia)</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="an-until">Mostra fino al <span className="muted">(facoltativo)</span></label>
            <input id="an-until" className="input" type="date" value={draft.until ?? ''} onChange={(e) => setDraft({ ...draft, until: e.target.value || null })} />
          </div>
        </div>
        <div className="announce-form__foot">
          <Switch checked={draft.active} label={draft.active ? 'Pubblica subito' : 'Salva senza pubblicare'} onChange={() => setDraft({ ...draft, active: !draft.active })} />
          <button type="submit" className="btn btn--action" disabled={busy}><Plus size={18} aria-hidden="true" /> Aggiungi avviso</button>
        </div>
      </form>

      <h2 className="admin-subtitle">Avvisi salvati</h2>
      {announcements.length === 0 ? (
        <p className="muted">Nessun avviso. Il sito non mostra nessuna barra.</p>
      ) : (
        <ul className="announce-list">
          {announcements.map((a) => (
            <li key={a.id} className={`announce-list__item announce-list__item--${a.tone}`}>
              <div>
                <p className="announce-list__text">{a.text}</p>
                <p className="small muted">{a.tone === 'importante' ? 'Importante' : 'Informazione'} · {a.until ? `fino al ${a.until.split('-').reverse().join('/')}` : 'senza scadenza'}</p>
              </div>
              <Switch checked={a.active} label={a.active ? 'Visibile' : 'Nascosto'}
                onChange={() => toggle(a)} />
              <button type="button" className="btn btn--quiet btn--sm" aria-label={`Elimina avviso: ${a.text}`} onClick={() => remove(a)}>
                <Trash2 size={16} aria-hidden="true" /> Elimina
              </button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
