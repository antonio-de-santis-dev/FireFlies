import { CircleAlert, ImageOff, Pencil, Plus, Save, Search, SearchX, Trash2 } from 'lucide-react';
import { PhotoPicker } from './media';
import { useMemo, useState, type FormEvent } from 'react';
import { ApiError, problemField } from '../../api/client';
import { deleteMenuItem, saveMenuItem, setMenuItemVisibility } from '../../api/content';
import { ConfirmBadge, Img } from '../../components/ui';
import { formatPrice, type CategoryId, type MenuItem } from '../../data/menu';
import { Link, navigate, usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return (
    <button type="button" role="switch" aria-checked={checked} className="switch" onClick={onChange}>
      <span className="switch__track" aria-hidden="true"><span className="switch__thumb" /></span>
      <span className="switch__label">{label}</span>
    </button>
  );
}

export function MenuAdmin() {
  usePageMeta('Gestione menu');
  const { menu, categories, allergens, dispatch, notify, mediaAsset } = useStore();
  const [cat, setCat] = useState<CategoryId | 'tutte'>('tutte');
  const toggle = async (m: MenuItem) => {
    try {
      const saved = await setMenuItemVisibility(m, !m.visible, allergens);
      dispatch({ type: 'menu/save', item: saved });
      notify(`«${m.name}» ${saved.visible ? 'visibile' : 'nascosta'} sul sito.`);
    } catch (e) {
      notify(e instanceof ApiError ? e.message : 'Modifica non salvata: riprova.');
    }
  };
  const [q, setQ] = useState('');
  const rows = useMemo(
    () => menu.filter((m) => (cat === 'tutte' || m.category === cat) && m.name.toLowerCase().includes(q.trim().toLowerCase())),
    [menu, cat, q],
  );

  return (
    <>
      <AdminHead title="Menu" lead={`${menu.length} voci · ${menu.filter((m) => !m.visible).length} nascoste dal sito`}>
        <Link to="/gestione/menu/nuova" className="btn btn--action"><Plus size={20} aria-hidden="true" /> Nuova voce</Link>
      </AdminHead>

      <div className="toolbar">
        <div className="field field--inline">
          <label htmlFor="am-q" className="sr-only">Cerca voce</label>
          <div className="input-icon">
            <Search size={18} aria-hidden="true" />
            <input id="am-q" className="input" type="search" placeholder="Cerca per nome" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
        </div>
        <div className="field field--inline">
          <label htmlFor="am-cat">Categoria</label>
          <select id="am-cat" className="select" value={cat} onChange={(e) => setCat(e.target.value as CategoryId | 'tutte')}>
            <option value="tutte">Tutte</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
          </select>
        </div>
      </div>

      {rows.length === 0 ? (
        <div className="empty empty--admin">
          <SearchX size={36} aria-hidden="true" />
          <h2>Nessuna voce trovata</h2>
          <p className="muted">Cambia categoria o cancella la ricerca.</p>
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => { setQ(''); setCat('tutte'); }}>Azzera filtri</button>
        </div>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <caption className="sr-only">Voci del menu</caption>
            <thead>
              <tr>
                <th scope="col">Voce</th>
                <th scope="col">Categoria</th>
                <th scope="col">Prezzo</th>
                <th scope="col">Allergeni</th>
                <th scope="col">Sul sito</th>
                <th scope="col"><span className="sr-only">Azioni</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => {
                const p = mediaAsset(m.photo);
                return (
                  <tr key={m.id} className={m.visible ? undefined : 'is-hidden'}>
                    <td data-label="Voce">
                      <span className="cell-item">
                        {p ? <Img asset={p} decorative className="cell-item__img" sizes="48px" /> : <span className="cell-item__img cell-item__img--none"><ImageOff size={18} aria-hidden="true" /></span>}
                        <strong>{m.name}</strong>
                      </span>
                    </td>
                    <td data-label="Categoria">{categories.find((c) => c.id === m.category)?.label}</td>
                    <td data-label="Prezzo">{m.price == null ? <span className="badge badge--confirm">Da definire</span> : formatPrice(m.price)}</td>
                    <td data-label="Allergeni">{m.allergens == null ? <span className="badge badge--confirm">Mancanti</span> : m.allergens.length ? m.allergens.join(', ') : 'Nessuno'}</td>
                    <td data-label="Sul sito">
                      <Switch
                        checked={m.visible}
                        label={m.visible ? 'Visibile' : 'Nascosta'}
                        onChange={() => toggle(m)}
                      />
                    </td>
                    <td>
                      <Link to={`/gestione/menu/${m.id}`} className="btn btn--ghost btn--sm" aria-label={`Modifica ${m.name}`}>
                        <Pencil size={16} aria-hidden="true" /> Modifica
                      </Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

const blank = (category: CategoryId): MenuItem => ({
  id: 'nuova', name: '', category, description: '', ingredients: [], allergens: null, price: null,
  photo: null, visible: false, source: 'reale',
});

export function MenuEditor({ id }: { id: string }) {
  const { menu, categories, allergens, dispatch, notify, mediaAsset } = useStore();
  const existing = menu.find((m) => m.id === id);
  const isNew = id === 'nuova';
  usePageMeta(isNew ? 'Nuova voce menu' : existing ? `Modifica ${existing.name}` : 'Voce non trovata');
  const [item, setItem] = useState<MenuItem>(() => existing ?? blank(categories[0]?.id ?? ''));
  const [price, setPrice] = useState(existing?.price != null ? existing.price.toFixed(2).replace('.', ',') : '');
  const [declared, setDeclared] = useState(existing?.allergens != null);
  const [errors, setErrors] = useState<{ name?: string; price?: string }>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  if (!existing && !isNew) {
    return (
      <div className="empty empty--admin">
        <h1>Voce non trovata</h1>
        <Link to="/gestione/menu" className="btn btn--ghost">Torna al menu</Link>
      </div>
    );
  }

  const up = <K extends keyof MenuItem>(k: K, v: MenuItem[K]) => setItem((s) => ({ ...s, [k]: v }));
  const toggleAllergen = (a: string) =>
    up('allergens', (item.allergens ?? []).includes(a) ? (item.allergens ?? []).filter((x) => x !== a) : [...(item.allergens ?? []), a]);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const next: typeof errors = {};
    if (item.name.trim().length < 2) next.name = 'Dai un nome alla voce (almeno 2 caratteri).';
    const parsed = price.trim() ? Number(price.replace(',', '.')) : null;
    if (parsed != null && (Number.isNaN(parsed) || parsed < 0 || parsed > 1000 || !/^\d+([.,]\d{1,2})?$/.test(price.trim()))) {
      next.price = 'Scrivi un prezzo in euro, es. 6,50. Lascia vuoto se non è ancora deciso.';
    }
    setErrors(next);
    setFailure(null);
    if (Object.keys(next).length) {
      document.getElementById(next.name ? 'me-name' : 'me-price')?.focus();
      return;
    }
    setBusy(true);
    try {
      const saved = await saveMenuItem({ ...item, name: item.name.trim(), price: parsed, allergens: declared ? item.allergens ?? [] : null },
        isNew, categories, allergens);
      dispatch({ type: 'menu/save', item: saved });
      notify(isNew ? `«${saved.name}» aggiunta al menu.` : `«${saved.name}» salvata.`);
      navigate('/gestione/menu');
    } catch (error) {
      const field = problemField(error);
      const message = error instanceof ApiError ? error.message : 'Salvataggio non riuscito: riprova.';
      if (field === 'name' || field === 'priceCents') {
        setErrors(field === 'name' ? { name: message } : { price: message });
        document.getElementById(field === 'name' ? 'me-name' : 'me-price')?.focus();
      } else {
        setFailure(message);
      }
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!confirmDelete) {
      setConfirmDelete(true);
      return;
    }
    try {
      await deleteMenuItem(item.id);
      dispatch({ type: 'menu/remove', id: item.id });
      notify(`«${item.name}» eliminata dal menu.`);
      navigate('/gestione/menu');
    } catch (error) {
      setFailure(error instanceof ApiError ? error.message : 'Eliminazione non riuscita: riprova.');
    }
  };

  const preview = mediaAsset(item.photo);

  return (
    <form className="editor" noValidate onSubmit={save}>
      <AdminHead title={isNew ? 'Nuova voce' : `Modifica: ${existing!.name}`} lead="Al salvataggio la voce si aggiorna subito sul sito.">
        <Link to="/gestione/menu" className="btn btn--quiet">Annulla</Link>
        <button type="submit" className="btn btn--action" disabled={busy}><Save size={18} aria-hidden="true" /> {busy ? 'Salvataggio…' : 'Salva'}</button>
      </AdminHead>
      {failure && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {failure}</p>}

      <div className="editor__grid">
        <div className="panel editor__main">
          <div className="field">
            <label htmlFor="me-name">Nome</label>
            {errors.name && <p className="error" id="me-name-err"><CircleAlert size={16} aria-hidden="true" />{errors.name}</p>}
            <input id="me-name" className="input" value={item.name} onChange={(e) => up('name', e.target.value)}
              aria-invalid={errors.name ? true : undefined} aria-describedby={errors.name ? 'me-name-err' : undefined} />
          </div>
          <div className="editor__row">
            <div className="field">
              <label htmlFor="me-cat">Categoria</label>
              <select id="me-cat" className="select" value={item.category} onChange={(e) => up('category', e.target.value as CategoryId)}>
                {categories.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="me-price">Prezzo (€)</label>
              {errors.price && <p className="error" id="me-price-err"><CircleAlert size={16} aria-hidden="true" />{errors.price}</p>}
              <input id="me-price" className="input" inputMode="decimal" placeholder="es. 6,50" value={price} onChange={(e) => setPrice(e.target.value)}
                aria-invalid={errors.price ? true : undefined} aria-describedby={errors.price ? 'me-price-err' : 'me-price-hint'} />
              <p className="hint" id="me-price-hint">Vuoto = «Prezzo da definire» sul sito.</p>
            </div>
          </div>
          <div className="field">
            <label htmlFor="me-desc">Descrizione</label>
            <textarea id="me-desc" className="textarea" value={item.description} maxLength={220} onChange={(e) => up('description', e.target.value)} />
            <p className="hint">{item.description.length}/220 caratteri</p>
          </div>
          <div className="field">
            <label htmlFor="me-ingr">Ingredienti</label>
            <input id="me-ingr" className="input" value={item.ingredients.join(', ')} aria-describedby="me-ingr-hint"
              onChange={(e) => up('ingredients', e.target.value.split(',').map((s) => s.trim()).filter(Boolean))} />
            <p className="hint" id="me-ingr-hint">Separali con una virgola.</p>
          </div>
          <fieldset className="allergens">
            <legend>Allergeni (Reg. UE 1169/2011)</legend>
            <label className="check">
              <input type="checkbox" checked={declared} onChange={(e) => setDeclared(e.target.checked)} />
              <span>Ho verificato gli allergeni di questa voce</span>
            </label>
            {!declared && <ConfirmBadge>Sul sito: «Da dichiarare»</ConfirmBadge>}
            <div className="allergens__grid" aria-disabled={!declared}>
              {allergens.map(({ name: a }) => (
                <label key={a} className="check check--sm">
                  <input type="checkbox" disabled={!declared} checked={(item.allergens ?? []).includes(a)} onChange={() => toggleAllergen(a)} />
                  <span>{a}</span>
                </label>
              ))}
            </div>
          </fieldset>
        </div>

        <aside className="editor__side">
          <div className="panel">
            <h2 className="panel__title">Pubblicazione</h2>
            <Switch checked={item.visible} onChange={() => up('visible', !item.visible)} label={item.visible ? 'Visibile sul sito' : 'Nascosta dal sito'} />
            {!isNew && (
              <button type="button" className="btn btn--quiet btn--sm editor__delete" onClick={remove}>
                <Trash2 size={16} aria-hidden="true" /> {confirmDelete ? 'Conferma: elimina la voce' : 'Elimina voce'}
              </button>
            )}
          </div>
          <div className="panel">
            <h2 className="panel__title">Foto</h2>
            {preview ? <Img asset={preview} className="editor__preview" sizes="360px" /> : <div className="editor__preview editor__preview--none"><ImageOff aria-hidden="true" /> Nessuna foto: la voce appare come solo testo</div>}
            <PhotoPicker legend="Scegli o carica una foto" allowNone value={item.photo} onChange={(id) => up('photo', id)} />
          </div>
        </aside>
      </div>
    </form>
  );
}
