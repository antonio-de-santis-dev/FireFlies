import { ArrowDown, ArrowUp, CircleAlert, ImageOff, ImagePlus, RefreshCw, Trash2 } from 'lucide-react';
import { useId, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { deletePhoto, reorderPhotos, replacePhoto, updatePhoto, uploadPhoto } from '../../api/content';
import { Img } from '../../components/ui';
import { ALLOWED_UPLOAD, MAX_UPLOAD_MB, type MediaItem } from '../../data/content';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { Switch } from './MenuAdmin';

/** Controlli immediati nel browser (tipo e peso); quelli veri li rifà il backend sui byte del file. */
function precheck(file: File): string | null {
  if (!ALLOWED_UPLOAD.includes(file.type)) return `«${file.name}» non è una foto JPG, PNG o WebP.`;
  if (file.size > MAX_UPLOAD_MB * 1024 * 1024) return `«${file.name}» pesa più di ${MAX_UPLOAD_MB} MB: riducila e riprova.`;
  return null;
}

/** Testo alternativo iniziale dal nome del file: si corregge subito dopo nella libreria. */
const altFromName = (file: File) => `Foto caricata: ${file.name.replace(/\.[^.]+$/, '').replace(/[-_]+/g, ' ').trim() || 'senza nome'}`;

const uploadMessage = (file: File, error: unknown) =>
  error instanceof ApiError && error.status !== 0 ? `«${file.name}»: ${error.message}` : `«${file.name}» non caricata: il server non risponde.`;

/** Pulsante «Carica foto»: invia le foto al backend (verifica, ricodifica, varianti) e restituisce l'ultima. */
export function UploadButton({ onUploaded, label = 'Carica foto', multiple = false }: { onUploaded?: (item: MediaItem) => void; label?: string; multiple?: boolean }) {
  const { dispatch, notify } = useStore();
  const ref = useRef<HTMLInputElement>(null);
  const id = useId();
  const [errors, setErrors] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const onFiles = async (files: FileList | null) => {
    if (!files?.length || busy) return;
    const errs: string[] = [];
    let last: MediaItem | null = null;
    setBusy(true);
    for (const f of Array.from(files)) {
      const invalid = precheck(f);
      if (invalid) {
        errs.push(invalid);
        continue;
      }
      try {
        const item = await uploadPhoto(f, altFromName(f));
        dispatch({ type: 'media/add', item });
        last = item;
      } catch (error) {
        errs.push(uploadMessage(f, error));
      }
    }
    setBusy(false);
    setErrors(errs);
    if (last) {
      onUploaded?.(last);
      notify('Foto aggiunta alla libreria. Controlla la descrizione per chi non vede l’immagine.');
    }
    if (ref.current) ref.current.value = '';
  };

  return (
    <div className="upload">
      <input ref={ref} id={id} type="file" accept={ALLOWED_UPLOAD.join(',')} multiple={multiple} className="sr-only" disabled={busy} onChange={(e) => onFiles(e.target.files)} />
      <label htmlFor={id} className="btn btn--ghost btn--sm upload__btn" aria-disabled={busy || undefined}>
        <ImagePlus size={18} aria-hidden="true" /> {busy ? 'Caricamento in corso…' : label}
      </label>
      <p className="hint">JPG, PNG o WebP, massimo {MAX_UPLOAD_MB} MB. Il server la ridimensiona e toglie i dati di posizione (GPS).</p>
      {errors.map((e) => (
        <p key={e} className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {e}</p>
      ))}
    </div>
  );
}

/** Scelta di una foto dalla libreria, con anteprime cliccabili e caricamento immediato. */
/** `contain`: anteprime intere, senza ritaglio (locandine degli eventi). */
export function PhotoPicker({ value, onChange, allowNone = false, legend, contain = false }: { value: string | null; onChange: (id: string | null) => void; allowNone?: boolean; legend: string; contain?: boolean }) {
  const { media } = useStore();
  const name = useId();
  return (
    <fieldset className={`picker${contain ? ' picker--contain' : ''}`}>
      <legend>{legend}</legend>
      <div className="picker__grid">
        {allowNone && (
          <label className={`picker__opt picker__opt--none${value == null ? ' is-selected' : ''}`}>
            <input type="radio" name={name} className="sr-only" checked={value == null} onChange={() => onChange(null)} />
            <ImageOff aria-hidden="true" />
            <span>Nessuna</span>
          </label>
        )}
        {media.map((m) => (
          <label key={m.id} className={`picker__opt${value === m.id ? ' is-selected' : ''}`}>
            <input type="radio" name={name} value={m.id} className="sr-only" checked={value === m.id} onChange={() => onChange(m.id)} />
            {/* eager: le miniature stanno in un riquadro scorrevole, il lazy loading le lascerebbe vuote */}
            <Img asset={m.asset} decorative eager sizes="120px" />
            <span className="sr-only">{m.asset.alt}</span>
            {m.uploaded && <span className="picker__new">Nuova</span>}
          </label>
        ))}
      </div>
      <UploadButton label="Carica una nuova foto" onUploaded={(m) => onChange(m.id)} />
    </fieldset>
  );
}

/** Testo alternativo modificabile: si salva quando si esce dal campo. */
function AltField({ item }: { item: MediaItem }) {
  const { dispatch, notify } = useStore();
  const [alt, setAlt] = useState(item.asset.alt);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    if (alt.trim() === item.asset.alt) return;
    try {
      // si applica solo la descrizione: la risposta può arrivare dopo quella di un'altra modifica (es. «In galleria»)
      const saved = await updatePhoto(item.id, { altText: alt.trim() });
      dispatch({ type: 'media/merge', id: item.id, alt: saved.asset.alt });
      setError(null);
      notify('Descrizione salvata.');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Descrizione non salvata: riprova.');
    }
  };
  return (
    <div className="field">
      <label htmlFor={`alt-${item.id}`}>Descrizione per chi non vede l'immagine</label>
      {error && <p className="error" id={`alt-${item.id}-err`}><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
      <input id={`alt-${item.id}`} className="input input--sm" value={alt} maxLength={250}
        onChange={(e) => setAlt(e.target.value)} onBlur={save} onKeyDown={(e) => e.key === 'Enter' && save()}
        aria-invalid={error ? true : undefined} aria-describedby={error ? `alt-${item.id}-err` : undefined} />
    </div>
  );
}

/** «Sostituisci»: nuovo file per la stessa foto; menu, titolari e galleria la mostrano subito aggiornata. */
function ReplaceButton({ item, onError }: { item: MediaItem; onError: (msg: string | null) => void }) {
  const { dispatch, notify } = useStore();
  const id = useId();
  const [busy, setBusy] = useState(false);
  const onFile = async (input: HTMLInputElement) => {
    const file = input.files?.[0];
    if (!file) return;
    const invalid = precheck(file);
    if (invalid) {
      onError(invalid);
      input.value = '';
      return;
    }
    setBusy(true);
    try {
      dispatch({ type: 'media/update', item: await replacePhoto(item.id, file) });
      onError(null);
      notify('Foto sostituita ovunque è usata.');
    } catch (e) {
      onError(uploadMessage(file, e));
    } finally {
      setBusy(false);
      input.value = '';
    }
  };
  return (
    <span className="photos__replace">
      <input id={id} type="file" accept={ALLOWED_UPLOAD.join(',')} className="sr-only" disabled={busy} onChange={(e) => onFile(e.currentTarget)} />
      <label htmlFor={id} className="btn btn--quiet btn--sm" aria-label={`Sostituisci il file: ${item.asset.alt}`}>
        <RefreshCw size={16} aria-hidden="true" /> {busy ? 'Caricamento…' : 'Sostituisci'}
      </label>
    </span>
  );
}

/** Gestione → Foto: libreria, galleria pubblica, ordine, testo alternativo, sostituzione ed eliminazione. */
export function Photos() {
  usePageMeta('Foto');
  const { media, dispatch, notify, mediaUsage } = useStore();
  const [blocked, setBlocked] = useState<string | null>(null);
  const inGallery = media.filter((m) => m.inGallery).length;

  const fail = (e: unknown, fallback: string) => setBlocked(e instanceof ApiError ? e.message : fallback);

  const remove = async (m: MediaItem) => {
    const used = mediaUsage(m.id);
    if (used.length) {
      setBlocked(`Non puoi eliminare questa foto: è usata in ${used.join(', ')}. Sostituiscila prima lì.`);
      return;
    }
    try {
      await deletePhoto(m.id);
      setBlocked(null);
      dispatch({ type: 'media/remove', id: m.id });
      notify('Foto eliminata dalla libreria.');
    } catch (e) {
      fail(e, 'Foto non eliminata: riprova.'); // 409 MEDIA_IN_USE: il backend elenca dove è usata
    }
  };

  const toggleGallery = async (m: MediaItem) => {
    try {
      const saved = await updatePhoto(m.id, { inGallery: !m.inGallery });
      dispatch({ type: 'media/merge', id: m.id, inGallery: saved.inGallery });
    } catch (e) {
      fail(e, 'Modifica non salvata: riprova.');
    }
  };

  const move = async (i: number, dir: -1 | 1) => {
    const ids = media.map((m) => m.id);
    [ids[i], ids[i + dir]] = [ids[i + dir], ids[i]];
    try {
      const fresh = new Map(media.map((m) => [m.id, m.uploaded]));
      dispatch({ type: 'media/set', items: (await reorderPhotos(ids)).map((m) => ({ ...m, uploaded: fresh.get(m.id) ?? false })) });
    } catch (e) {
      fail(e, 'Ordine non salvato: riprova.');
    }
  };

  return (
    <>
      <AdminHead title="Foto" lead={`${media.length} foto in libreria · ${inGallery} visibili nella galleria del sito`} />

      <section className="panel photos__upload" aria-labelledby="ph-up">
        <h2 id="ph-up" className="panel__title">Aggiungi foto</h2>
        <p className="small muted">Le foto caricate si possono usare subito per menu, staff e home. Per pubblicarle in galleria attiva «In galleria».</p>
        <UploadButton label="Scegli foto dal dispositivo" multiple />
      </section>

      {blocked && <p className="error photos__blocked" role="alert"><CircleAlert size={18} aria-hidden="true" /> {blocked}</p>}

      <ul className="photos">
        {media.map((m, i) => (
          <li key={m.id} className="photos__item">
            <Img asset={m.asset} className="photos__img" decorative sizes="(min-width: 1200px) 320px, (min-width: 640px) 45vw, 100vw" />
            <div className="photos__body">
              {m.uploaded ? <span className="badge badge--action">Caricata ora</span> : <span className="badge badge--neutral">Foto del locale</span>}
              <AltField key={m.asset.alt} item={m} />
              <Switch checked={m.inGallery} label={m.inGallery ? 'In galleria' : 'Non in galleria'} onChange={() => toggleGallery(m)} />
              <div className="photos__actions">
                <button type="button" className="icon-btn" aria-label={`Sposta prima: ${m.asset.alt}`} disabled={i === 0} onClick={() => move(i, -1)}>
                  <ArrowUp size={18} aria-hidden="true" />
                </button>
                <button type="button" className="icon-btn" aria-label={`Sposta dopo: ${m.asset.alt}`} disabled={i === media.length - 1} onClick={() => move(i, 1)}>
                  <ArrowDown size={18} aria-hidden="true" />
                </button>
                <ReplaceButton item={m} onError={setBlocked} />
                <button type="button" className="btn btn--quiet btn--sm photos__del" onClick={() => remove(m)}>
                  <Trash2 size={16} aria-hidden="true" /> Elimina
                </button>
              </div>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
