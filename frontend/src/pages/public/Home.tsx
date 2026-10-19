import { ArrowRight, CalendarHeart, Clock, Instagram, MapPin, UtensilsCrossed } from 'lucide-react';
import { useState } from 'react';
import { logo, photos } from '../../data/assets';
import { formatEventDate } from '../../data/events';
import { momentAt, moments } from '../../data/site';
import { Link, usePageMeta } from '../../router';
import { useStore } from '../../store';
import { Fireflies } from '../../components/Fireflies';
import { MapEmbed } from '../../components/MapEmbed';
import { ConfirmBadge, DemoBadge, Img } from '../../components/ui';

const todayIndex = () => (new Date().getDay() + 6) % 7;

export function HoursList({ compact = false }: { compact?: boolean }) {
  const { settings } = useStore();
  const t = todayIndex();
  return (
    <ul className={`hours${compact ? ' hours--compact' : ''}`}>
      {settings.hours.map((h, i) => (
        <li key={h.day} className={i === t ? 'is-today' : undefined}>
          <span>{h.day}{i === t && <span className="sr-only"> (oggi)</span>}</span>
          <span>{h.open ? `${h.open} – ${h.close}` : 'Chiuso'}</span>
        </li>
      ))}
    </ul>
  );
}

function Hero() {
  const { settings, content, mediaOr } = useStore();
  const today = settings.hours[todayIndex()];
  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="hero__media">
        {/* React 18 non conosce ancora `fetchPriority`: attributo HTML in minuscolo */}
        <Img asset={mediaOr(content.heroPhoto)} eager decorative {...({ fetchpriority: 'high' } as object)} />
      </div>
      <Fireflies className="hero__fireflies" />
      <div className="container hero__inner">
        <Img asset={logo.onDark} eager className="hero__logo" />
        <h1 id="hero-title" className="hero__title">{content.heroClaim}</h1>
        <p className="lead hero__lead">{content.heroLead}</p>
        <div className="hero__ctas">
          <Link to="/menu" className="btn btn--primary">
            Scopri il menu <ArrowRight size={20} aria-hidden="true" />
          </Link>
          <Link to="/eventi" className="btn btn--ghost">
            Scopri gli eventi
          </Link>
        </div>
        <p className="hero__meta small">
          <Clock size={16} aria-hidden="true" /> Oggi {today.open ? `${today.open} – ${today.close}` : 'chiuso'}
          {!settings.hoursConfirmed && <ConfirmBadge>orari da confermare</ConfirmBadge>}
          <span className="hero__claim-note">Claim proposto, da validare</span>
        </p>
      </div>
    </section>
  );
}

function MomentPicker() {
  const [hour, setHour] = useState(19);
  const m = momentAt(hour);
  return (
    <section className={`moment moment--${m.tone}${m.tone === 'day' ? ' theme-day' : ''}`} aria-labelledby="moment-title">
      <div className="container moment__grid">
        <div className="moment__copy">
          <h2 id="moment-title" className="reveal">Che momento è al Fire?</h2>
          <p className="moment__hint reveal" style={{ ['--i' as string]: 1 }}>
            Sposta l'ora e guarda cosa trovi al bancone.
          </p>
          <div className="moment__clock reveal" style={{ ['--i' as string]: 2 }} aria-hidden="true">
            {String(hour).padStart(2, '0')}:00
          </div>
          <label htmlFor="moment-range" className="sr-only">Ora del giorno</label>
          <input
            id="moment-range"
            className="moment__range"
            type="range"
            min={7}
            max={23}
            step={1}
            value={hour}
            aria-valuetext={`ore ${hour}: ${m.title}`}
            onChange={(e) => setHour(Number(e.target.value))}
          />
          <ol className="moment__ticks" aria-label="Fasce della giornata">
            {moments.map((x) => (
              <li key={x.id}>
                <button type="button" aria-pressed={x.id === m.id} onClick={() => setHour(x.from === 7 ? 8 : x.from)}>
                  {String(x.from).padStart(2, '0')}
                  <span>{x.id === 'colazione' ? 'Colazione' : x.id === 'pausa' ? 'Pausa' : x.id === 'aperitivo' ? 'Aperitivo' : 'Sera'}</span>
                </button>
              </li>
            ))}
          </ol>
          <div className="moment__result" aria-live="polite">
            <h3>{m.title}</h3>
            <p>{m.line}</p>
            <Link to={`/menu?cat=${m.menuCategory}`} className="moment__link">
              Vedi nel menu <ArrowRight size={18} aria-hidden="true" />
            </Link>
          </div>
          <p className="small moment__note">Fasce orarie indicative, da confermare con il locale.</p>
        </div>
        <div className="moment__visual" aria-hidden="true">
          {moments.map((x) => (
            <Img key={x.id} asset={x.cutout} decorative className={x.id === m.id ? 'is-active' : undefined} />
          ))}
        </div>
      </div>
    </section>
  );
}

function ThreeMoments() {
  return (
    <section className="trio container" aria-labelledby="trio-title">
      <h2 id="trio-title" className="trio__title reveal">Breakfast, Aperitif, Fire Nights.</h2>
      <div className="trio__grid">
        <article className="trio__item trio__item--aperitif">
          <Img asset={photos.tagliere} className="trio__img reveal" />
          <div className="trio__text reveal" style={{ ['--i' as string]: 1 }}>
            <h3>Aperitif</h3>
            <p>Taglieri da finire in compagnia, crostini con mortadella e pistacchio, il primo giro al bancone.</p>
            <Link to="/menu?cat=aperitivi">Aperitivi e taglieri <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
        </article>
        <article className="trio__item trio__item--breakfast">
          <Img asset={photos.cappuccino} className="trio__img reveal" />
          <div className="trio__text reveal" style={{ ['--i' as string]: 1 }}>
            <h3>Breakfast</h3>
            <p>Cappuccino e cornetto, prima di scuola, del lavoro o della spiaggia.</p>
            <Link to="/menu?cat=colazione">La colazione <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
        </article>
        <article className="trio__item trio__item--nights">
          <Img asset={photos.cocktailRosso} className="trio__img reveal" />
          <div className="trio__text reveal" style={{ ['--i' as string]: 1 }}>
            <h3>Fire Nights</h3>
            <p>Cocktail fatti al momento, musica e DJ set quando il calendario lo dice.</p>
            <Link to="/eventi">Le serate <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
        </article>
      </div>
    </section>
  );
}

function People() {
  const { content, mediaOr } = useStore();
  return (
    <section className="people" aria-labelledby="people-title">
      <div className="container people__grid">
        <div className="people__copy">
          <h2 id="people-title" className="reveal">Dietro il bancone ci sono loro.</h2>
          <p className="reveal" style={{ ['--i' as string]: 1 }}>
            Tre titolari, tre maglie colorate e un'idea semplice: un posto a Martano dove la mattina si fa colazione con
            calma e la sera ci si ritrova. FireFlies è il loro bancone.
          </p>
          {!content.ownersConfirmed && <p className="reveal" style={{ ['--i' as string]: 2 }}><ConfirmBadge>Presentazione da validare con i titolari</ConfirmBadge></p>}
          <Link to="/locale" className="btn btn--ghost reveal" style={{ ['--i' as string]: 3 }}>
            Conosci i titolari
          </Link>
        </div>
        <ul className="people__owners" aria-label="I titolari">
          {content.owners.map((o, i) => (
            <li key={o.id} className={`people__owner people__owner--${o.color} reveal`} style={{ ['--i' as string]: i + 1 }}>
              <Img asset={mediaOr(o.front)} />
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function EventsTeaser() {
  const { events, mediaOr } = useStore();
  const published = events.filter((e) => e.status === 'pubblicato');
  const format = published.find((e) => e.kind === 'format');
  const next = published.find((e) => e.kind === 'serata');
  return (
    <section className="teaser" aria-labelledby="teaser-title">
      <div className="container teaser__grid">
        <div className="teaser__copy">
          <h2 id="teaser-title" className="reveal">La sera, il Fire si accende.</h2>
          <p className="lead reveal" style={{ ['--i' as string]: 1 }}>
            DJ set all'ingresso, aperitivo che si allunga, le Domeniche del Fire. Date e line-up le pubblica il locale.
          </p>
          <Link to="/eventi" className="btn btn--primary reveal" style={{ ['--i' as string]: 2 }}>
            <CalendarHeart size={20} aria-hidden="true" /> Le prossime serate
          </Link>
        </div>
        <div className="teaser__cards">
          {next && (
            <Link to={`/eventi/${next.id}`} className="teaser__card teaser__card--big reveal">
              <Img asset={mediaOr(next.photo)} />
              <span className="teaser__card-body">
                {next.demo && <DemoBadge>Serata d'esempio</DemoBadge>}
                <strong>{next.title}</strong>
                <span>{formatEventDate(next)}</span>
              </span>
            </Link>
          )}
          {format && (
            <Link to={`/eventi/${format.id}`} className="teaser__card reveal" style={{ ['--i' as string]: 1 }}>
              <Img asset={mediaOr(format.photo)} />
              <span className="teaser__card-body">
                <ConfirmBadge>Format ricorrente</ConfirmBadge>
                <strong>{format.title}</strong>
                <span>{formatEventDate(format)}</span>
              </span>
            </Link>
          )}
        </div>
      </div>
    </section>
  );
}

function GalleryStrip() {
  const { settings, media } = useStore();
  // le prime foto della galleria scelte dal titolare, saltando quella già usata in apertura
  const strip = media.filter((m) => m.inGallery).slice(2, 8).map((m) => m.asset);
  return (
    <section className="strip" aria-labelledby="strip-title">
      <div className="container strip__head">
        <h2 id="strip-title" className="reveal">Da vicino.</h2>
        <div className="strip__links">
          <Link to="/galleria" className="btn btn--ghost btn--sm">Tutta la galleria</Link>
          <a href={settings.instagram} target="_blank" rel="noopener noreferrer" className="btn btn--quiet btn--sm">
            <Instagram size={18} aria-hidden="true" /> {settings.instagramHandle}
          </a>
        </div>
      </div>
      {/* tabIndex: la striscia scorre orizzontalmente anche da tastiera (frecce) */}
      <ul className="strip__list reveal" aria-label="Foto dal locale, scorri orizzontalmente" tabIndex={0}>
        {strip.map((p) => (
          <li key={p.src}>
            <Img asset={p} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function Visit() {
  const { settings } = useStore();
  return (
    <section className="visit theme-day" aria-labelledby="visit-title">
      <div className="container visit__grid">
        <div>
          <h2 id="visit-title" className="reveal">Passa a trovarci.</h2>
          <p className="visit__addr reveal" style={{ ['--i' as string]: 1 }}>
            <MapPin size={22} aria-hidden="true" /> {settings.addressLine}
          </p>
          <div className="visit__map reveal" style={{ ['--i' as string]: 2 }}>
            <MapEmbed />
          </div>
          <div className="visit__ctas reveal" style={{ ['--i' as string]: 3 }}>
            <a className="btn btn--gold" href={settings.mapsUrl} target="_blank" rel="noopener noreferrer">
              <MapPin size={20} aria-hidden="true" /> Apri le indicazioni
            </a>
            <Link to="/contatti" className="btn btn--ghost">Contatti</Link>
          </div>
        </div>
        <div className="visit__hours reveal" style={{ ['--i' as string]: 1 }}>
          <div className="visit__hours-head">
            <h3><Clock size={22} aria-hidden="true" /> Orari</h3>
            {!settings.hoursConfirmed && <ConfirmBadge />}
          </div>
          <HoursList />
          <Link to="/menu" className="visit__menu">
            <UtensilsCrossed size={18} aria-hidden="true" /> Guarda cosa si mangia e si beve
          </Link>
        </div>
      </div>
    </section>
  );
}

export function Home() {
  usePageMeta('Home');
  const { sections } = useStore().content;
  return (
    <>
      <Hero />
      {sections.momento && <MomentPicker />}
      {sections.trio && <ThreeMoments />}
      {sections.persone && <People />}
      {sections.eventi && <EventsTeaser />}
      {sections.striscia && <GalleryStrip />}
      {sections.visita && <Visit />}
    </>
  );
}
