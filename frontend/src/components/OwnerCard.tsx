import { RotateCw } from 'lucide-react';
import { useState } from 'react';
import type { Owner } from '../data/content';
import { useStore } from '../store';
import { Img } from './ui';

/** Titolare: foto scontornata accanto alla descrizione. «Gira» mostra il retro della maglia con il motto. */
export function OwnerCard({ owner, reverse }: { owner: Owner; reverse: boolean }) {
  const [back, setBack] = useState(false);
  const { mediaOr } = useStore();
  const label = owner.name && owner.name !== 'Nome da inserire' ? owner.name : 'questo titolare';
  return (
    <article className={`owner owner--${owner.color}${reverse ? ' owner--reverse' : ''}`}>
      <figure className="owner__figure">
        <div className={`owner__flip${back ? ' is-back' : ''}`}>
          <Img asset={mediaOr(owner.front)} className="owner__img owner__img--front" aria-hidden={back} />
          <Img asset={mediaOr(owner.back)} className="owner__img owner__img--back" aria-hidden={!back} />
        </div>
        <button type="button" className="btn btn--ghost btn--sm owner__turn" aria-pressed={back} onClick={() => setBack((b) => !b)}>
          <RotateCw size={16} aria-hidden="true" /> {back ? 'Torna al fronte' : 'Gira la maglia'}
          <span className="sr-only"> di {label}</span>
        </button>
      </figure>
      <div className="owner__text">
        <p className="owner__role">{owner.role}</p>
        <h3 className="owner__name">{owner.name}</h3>
        <p className="owner__desc">{owner.description}</p>
        {owner.motto && (
          <blockquote className="owner__motto">
            <p>«{owner.motto}»</p>
            <footer className="small">— scritto sulla sua maglia</footer>
          </blockquote>
        )}
      </div>
    </article>
  );
}
