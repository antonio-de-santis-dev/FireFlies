import { Pause, Play } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useStore } from '../store';
import { ConfirmBadge, DemoBadge, Img } from './ui';

/**
 * Testata della pagina Menu: foto a tutta area con velatura scura e titolo sopra.
 * Le foto (scelte e ordinate dal titolare in «Personalizza il sito») ruotano con una dissolvenza
 * ogni `intervalSec` secondi (60 di default). Il pulsante pausa soddisfa WCAG 2.2.2;
 * con prefers-reduced-motion il cambio è istantaneo (gestito in CSS).
 */
export function MenuHero() {
  const { content, mediaAsset } = useStore();
  const slides = content.menuHero.photos.map((id) => ({ id, asset: mediaAsset(id) })).filter((s) => s.asset);
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const count = slides.length;
  const active = count ? index % count : 0;

  useEffect(() => {
    if (paused || count < 2) return;
    const t = window.setInterval(() => setIndex((i) => (i + 1) % count), content.menuHero.intervalSec * 1000);
    return () => window.clearInterval(t);
  }, [paused, count, content.menuHero.intervalSec]);

  return (
    <section className="menu-hero" aria-labelledby="menu-title">
      <div className="menu-hero__slides" aria-hidden="true">
        {slides.map((s, i) => (
          <Img key={s.id} asset={s.asset!} decorative eager={i === 0} sizes="100vw" className={`menu-hero__img${i === active ? ' is-active' : ''}`} />
        ))}
      </div>
      <div className="container menu-hero__inner">
        <h1 id="menu-title">Il menu</h1>
        <p className="lead">Dalla colazione al cocktail della sera. Le voci arrivano dalle foto reali del locale.</p>
        <p className="page-hero__badges">
          <DemoBadge>Menu dimostrativo</DemoBadge>
          <ConfirmBadge>Prezzi dimostrativi · allergeni da definire</ConfirmBadge>
        </p>
      </div>
      {count > 1 && (
        <div className="menu-hero__controls">
          <div className="menu-hero__dots" role="group" aria-label="Foto della testata">
            {slides.map((s, i) => (
              <button key={s.id} type="button" className="menu-hero__dot" aria-pressed={i === active}
                aria-label={`Mostra foto ${i + 1} di ${count}: ${s.asset!.alt}`} onClick={() => setIndex(i)} />
            ))}
          </div>
          <button type="button" className="icon-btn menu-hero__pause" aria-pressed={paused}
            aria-label={paused ? 'Riprendi la rotazione delle foto' : 'Metti in pausa la rotazione delle foto'} onClick={() => setPaused((p) => !p)}>
            {paused ? <Play size={18} aria-hidden="true" /> : <Pause size={18} aria-hidden="true" />}
          </button>
        </div>
      )}
    </section>
  );
}
