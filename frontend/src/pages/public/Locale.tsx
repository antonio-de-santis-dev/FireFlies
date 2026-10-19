import { ArrowRight } from 'lucide-react';
import { OwnerCard } from '../../components/OwnerCard';
import { ConfirmBadge, Img } from '../../components/ui';
import { cutouts, photos } from '../../data/assets';
import { Link, usePageMeta } from '../../router';
import { useStore } from '../../store';

export function Locale() {
  usePageMeta('Il locale');
  const { openBooking, content, mediaOr } = useStore();
  return (
    <>
      <section className="story-hero">
        <div className="container story-hero__grid">
          <div>
            <h1>Una lucciola a Martano.</h1>
            <p className="lead">
              Di giorno un bar dove fare colazione con calma, la sera un bancone che si accende di colori, musica e gente.
            </p>
            {!content.storyConfirmed && <p><ConfirmBadge>Storia del locale da scrivere con i gestori</ConfirmBadge></p>}
          </div>
          <Img asset={cutouts.bartender} eager className="story-hero__cut" />
        </div>
      </section>

      <section className="owners" aria-labelledby="owners-title">
        <div className="container">
          <div className="owners__head">
            <h2 id="owners-title">I titolari</h2>
            <p className="lead">Tre maglie, tre colori, un solo bancone. Sono loro ad accenderlo ogni giorno.</p>
            {!content.ownersConfirmed && <ConfirmBadge>Nomi e descrizioni da scrivere con i titolari</ConfirmBadge>}
          </div>
          <div className="owners__list">
            {content.owners.map((o, i) => <OwnerCard key={o.id} owner={o} reverse={i % 2 === 1} />)}
          </div>
        </div>
      </section>

      <section className="container story">
        <div className="story__text">
          <h2>{content.storyTitle}</h2>
          {content.storyText.split(/\n{2,}/).map((p, i) => <p key={i}>{p}</p>)}
          {!content.storyConfirmed && (
            <p className="muted">
              Proposta di struttura: come è nato · cosa vuol dire stare al Fire · il rapporto con Martano e il Salento.
            </p>
          )}
        </div>
        <figure className="story__photo">
          <Img asset={photos.insegnaFermati} />
          <figcaption className="small muted">Sulla parete del locale: «Fermati. La giornata può aspettare. Il tuo momento no.»</figcaption>
        </figure>
      </section>

      <section className="spaces" aria-labelledby="spaces-title">
        <div className="container">
          <h2 id="spaces-title">Gli spazi</h2>
          <div className="spaces__grid">
            <article>
              <Img asset={photos.banconeClienti} />
              <h3>Il bancone</h3>
              <p>Pietra chiara, luce calda, la bottigliera alle spalle. Dove si fa colazione e dove nascono i cocktail.</p>
            </article>
            <article>
              <Img asset={photos.tavoloInterni} />
              <h3>I tavoli</h3>
              <p>Legno, verde e luce calda: qui si condivide il tagliere. Numero di coperti e spazio esterno da confermare.</p>
            </article>
            <article>
              <Img asset={photos.djSet} />
              <h3>L'ingresso</h3>
              <p>Quando c'è musica la consolle si mette qui, sotto l'insegna.</p>
            </article>
          </div>
        </div>
      </section>

      <section className="container team" aria-labelledby="team-title">
        <div className="team__head">
          <h2 id="team-title">Lo staff</h2>
          {!content.staffConfirmed && <ConfirmBadge>Nomi e ruoli da fornire</ConfirmBadge>}
        </div>
        <ul className="team__list">
          {content.staff.map((t) => (
            <li key={t.id}>
              <Img asset={mediaOr(t.photo)} />
              <p className="team__name">{t.name}</p>
              <p className="muted">{t.role}</p>
            </li>
          ))}
        </ul>
        <p className="small muted">Le foto con persone vanno pubblicate solo con il consenso delle persone ritratte.</p>
      </section>

      <section className="container closing">
        <h2>Ci vediamo al bancone?</h2>
        <div className="closing__ctas">
          <button type="button" className="btn btn--primary" onClick={() => openBooking()}>Richiedi un tavolo</button>
          <Link to="/menu" className="btn btn--ghost">Scopri il menu <ArrowRight size={18} aria-hidden="true" /></Link>
        </div>
      </section>
    </>
  );
}
