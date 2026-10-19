import { ArrowLeft, ArrowRight, Instagram } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Dialog } from '../../components/Dialog';
import { Img } from '../../components/ui';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';

export function Gallery() {
  usePageMeta('Galleria');
  const { settings, media } = useStore();
  // ordine e selezione decisi dal titolare in «Gestione → Foto»
  const items = media.filter((m) => m.inGallery);
  const [index, setIndex] = useState<number | null>(null);
  const current = index == null ? null : items[index]?.asset;
  const go = (d: number) => setIndex((i) => (i == null ? i : (i + d + items.length) % items.length));

  useEffect(() => {
    if (index == null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight') go(1);
      if (e.key === 'ArrowLeft') go(-1);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [index]);

  return (
    <>
      <section className="container page-head">
        <h1>Galleria</h1>
        <div className="page-head__side">
          <p className="lead">Foto reali del locale: bancone, piatti, drink e serate. Il resto, ogni giorno, su Instagram.</p>
          <a className="btn btn--gold" href={settings.instagram} target="_blank" rel="noopener noreferrer">
            <Instagram size={20} aria-hidden="true" /> Apri {settings.instagramHandle}
          </a>
          <p className="small muted">Link esterno: nessun feed incorporato, nessun tracciamento di terze parti.</p>
        </div>
      </section>
      <section className="container" aria-label="Foto">
        <ul className="gallery">
          {items.map((m, i) => (
            <li key={m.id} className={`gallery__item gallery__item--${i % 7}`}>
              <button type="button" onClick={() => setIndex(i)} aria-label={`Ingrandisci: ${m.asset.alt}`}>
                <Img asset={m.asset} decorative eager={i < 4} />
              </button>
            </li>
          ))}
        </ul>
      </section>
      <section className="container social" aria-labelledby="social-title">
        <div className="social__copy">
          <h2 id="social-title">Il resto è su Instagram.</h2>
          <p className="lead">Video dal bancone, DJ set, meme e novità del giorno: {settings.instagramHandle}.</p>
          <a className="btn btn--primary" href={settings.instagram} target="_blank" rel="noopener noreferrer">
            <Instagram size={20} aria-hidden="true" /> Seguici su Instagram
          </a>
          <p className="small muted">Anteprima con foto del locale: il feed reale non è incorporato (niente tracciamento di terze parti).</p>
        </div>
        <ul className="social__grid" aria-hidden="true">
          {items.slice(0, 6).map((m) => <li key={m.id}><Img asset={m.asset} decorative /></li>)}
        </ul>
      </section>
      {current && index != null && (
        <Dialog
          title={`Foto ${index + 1} di ${items.length}`}
          onClose={() => setIndex(null)}
          wide
          headExtra={
            <>
              <button type="button" className="icon-btn" aria-label="Foto precedente" onClick={() => go(-1)}><ArrowLeft aria-hidden="true" /></button>
              <button type="button" className="icon-btn" aria-label="Foto successiva" onClick={() => go(1)} data-autofocus><ArrowRight aria-hidden="true" /></button>
            </>
          }
        >
          <figure className="lightbox">
            <Img asset={current} eager />
            <figcaption>{current.alt}</figcaption>
          </figure>
        </Dialog>
      )}
    </>
  );
}
