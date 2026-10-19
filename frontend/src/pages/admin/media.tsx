import { ArrowDown, ArrowUp, CircleAlert, ImageOff, ImagePlus, Trash2 } from 'lucide-react';
import { useId, useRef, useState } from 'react';
import { DemoBadge, Img } from '../../components/ui';
import { ALLOWED_UPLOAD, MAX_UPLOAD_MB, type MediaItem } from '../../data/content';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { Switch } from './MenuAdmin';

/** Legge una foto scelta dal dispositivo. Solo in memoria (URL blob): nulla viene caricato su un server. */
async function readUpload(file: File): Promise<MediaItem | string> {
  if (!ALLOWED_UPLOAD.includes(file.type)) return `«${file.name}» non è una foto JPG, PNG o WebP.`;
  if (file.size > MAX_UPLOAD_MB * 1024 * 1024) return `«${file.name}» pesa più di ${MAX_UPLOAD_MB} MB: riducila e riprova.`;
  const src = URL.createObjectURL(file);
  const size = await new Promise<{ w: number; h: number } | null>((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = () => resolve(null);
    img.src = src;
  });
  if (!size) {
    URL.revokeObjectURL(src);
    return `«${file.name}» non si apre come immagine.`;
  }
  const name = file.name.replace(/\.[^.]+$/, '').replace(/[-_]+/g, ' ');
  return {
    id: `foto-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`,
    asset: { src, w: size.w, h: size.h, alt: `Foto caricata: ${name}` },
    inGallery: false,
    uploaded: true,
  };
}

/** Pulsante «Carica foto»: aggiunge le foto alla libreria e restituisce l'ultima caricata. */
export function UploadButton({ onUploaded, label = 'Carica foto', multiple = false }: { onUploaded?: (item: MediaItem) => void; label?: string; multiple?: boolean }) {
  const { dispatch, notify } = useStore();
  const ref = useRef<HTMLInputElement>(null);
  const id = useId();
  const [errors, setErrors] = useState<string[]>([]);

  const onFiles = async (files: FileList | null) => {
    if (!files?.length) return;
    const errs: string[] = [];
    let last: MediaItem | null = null;
    for (const f of Array.from(files)) {
      const r = await readUpload(f);
      if (typeof r === 'string') errs.push(r);
      else {
        dispatch({ type: 'media/add', item: r });
        last = r;
      }
    }
    setErrors(errs);
    if (last) {
      onUploaded?.(last);
      notify('Foto aggiunta alla libreria (solo in questa sessione demo).');
    }
    if (ref.current) ref.current.value = '';
  };

  return (
    <div className="upload">
      <input ref={ref} id={id} type="file" accept={ALLOWED_UPLOAD.join(',')} multiple={multiple} className="sr-only" onChange={(e) => onFiles(e.target.files)} />
      <label htmlFor={id} className="btn btn--ghost btn--sm upload__btn">
        <ImagePlus size={18} aria-hidden="true" /> {label}
      </label>
      <p className="hint">JPG, PNG o WebP, massimo {MAX_UPLOAD_MB} MB.</p>
      {errors.map((e) => (
        <p key={e} className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {e}</p>
      ))}
    </div>
  );
}

/** Scelta di una foto dalla libreria, con anteprime cliccabili e caricamento immediato. */
export function PhotoPicker({ value, onChange, allowNone = false, legend }: { value: string | null; onChange: (id: string | null) => void; allowNone?: boolean; legend: string }) {
  const { media } = useStore();
  const name = useId();
  return (
    <fieldset className="picker">
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
            <input type="radio" name={name} className="sr-only" checked={value === m.id} onChange={() => onChange(m.id)} />
            {/* eager: le miniature stanno in un riquadro scorrevole, il lazy loading le lascerebbe vuote */}
            <Img asset={m.asset} decorative eager />
            <span className="sr-only">{m.asset.alt}</span>
            {m.uploaded && <span className="picker__new">Nuova</span>}
          </label>
        ))}
      </div>
      <UploadButton label="Carica una nuova foto" onUploaded={(m) => onChange(m.id)} />
    </fieldset>
  );
}

/** Gestione → Foto: libreria, galleria pubblica, ordine, testo alternativo. */
export function Photos() {
  usePageMeta('Foto');
  const { media, dispatch, notify, mediaUsage } = useStore();
  const [blocked, setBlocked] = useState<string | null>(null);
  const inGallery = media.filter((m) => m.inGallery).length;

  const remove = (m: MediaItem) => {
    const used = mediaUsage(m.id);
    if (used.length) {
      setBlocked(`Non puoi eliminare questa foto: è usata in ${used.join(', ')}. Sostituiscila prima lì.`);
      return;
    }
    setBlocked(null);
    dispatch({ type: 'media/remove', id: m.id });
    notify('Foto eliminata dalla libreria (solo demo).');
  };

  return (
    <>
      <AdminHead title="Foto" lead={`${media.length} foto in libreria · ${inGallery} visibili nella galleria del sito`}>
        <DemoBadge>Caricamenti solo in memoria</DemoBadge>
      </AdminHead>

      <section className="panel photos__upload" aria-labelledby="ph-up">
        <h2 id="ph-up" className="panel__title">Aggiungi foto</h2>
        <p className="small muted">Le foto caricate si possono usare subito per menu, eventi, staff e home. Per pubblicarle in galleria attiva «In galleria».</p>
        <UploadButton label="Scegli foto dal dispositivo" multiple />
      </section>

      {blocked && <p className="error photos__blocked" role="alert"><CircleAlert size={18} aria-hidden="true" /> {blocked}</p>}

      <ul className="photos">
        {media.map((m, i) => (
          <li key={m.id} className="photos__item">
            <Img asset={m.asset} className="photos__img" decorative />
            <div className="photos__body">
              {m.uploaded ? <span className="badge badge--action">Caricata ora</span> : <span className="badge badge--neutral">Foto del locale</span>}
              <div className="field">
                <label htmlFor={`alt-${m.id}`}>Descrizione per chi non vede l'immagine</label>
                <input id={`alt-${m.id}`} className="input input--sm" value={m.asset.alt}
                  onChange={(e) => dispatch({ type: 'media/update', item: { ...m, asset: { ...m.asset, alt: e.target.value } } })} />
              </div>
              <Switch checked={m.inGallery} label={m.inGallery ? 'In galleria' : 'Non in galleria'}
                onChange={() => dispatch({ type: 'media/update', item: { ...m, inGallery: !m.inGallery } })} />
              <div className="photos__actions">
                <button type="button" className="icon-btn" aria-label={`Sposta prima: ${m.asset.alt}`} disabled={i === 0} onClick={() => dispatch({ type: 'media/move', id: m.id, dir: -1 })}>
                  <ArrowUp size={18} aria-hidden="true" />
                </button>
                <button type="button" className="icon-btn" aria-label={`Sposta dopo: ${m.asset.alt}`} disabled={i === media.length - 1} onClick={() => dispatch({ type: 'media/move', id: m.id, dir: 1 })}>
                  <ArrowDown size={18} aria-hidden="true" />
                </button>
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
