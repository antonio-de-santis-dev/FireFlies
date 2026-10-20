import { ArrowLeft, ArrowRight, CalendarDays, CalendarX2, Instagram, MapPin, Mic2, Users } from 'lucide-react';
import { useMemo, useState } from 'react';
import { ConfirmBadge, DemoBadge, Img } from '../../components/ui';
import { photos } from '../../data/assets';
import { formatEventDate, type FireEvent } from '../../data/events';
import { Link, usePageMeta } from '../../router';
import { useStore } from '../../store';

const PAST = [photos.djNotte, photos.furgoneCompleanno, photos.microfono, photos.tavolataFesta, photos.amiciMaglia, photos.bartenderBraccia, photos.djCappello, photos.consolle];

const monthFmt = new Intl.DateTimeFormat('it-IT', { month: 'long', year: 'numeric' });
const WEEK = ['L', 'M', 'M', 'G', 'V', 'S', 'D'];

function MiniCalendar({ events }: { events: FireEvent[] }) {
  const [offset, setOffset] = useState(0);
  const base = new Date();
  const first = new Date(base.getFullYear(), base.getMonth() + offset, 1);
  const days = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  const lead = (first.getDay() + 6) % 7;
  const iso = (d: number) => `${first.getFullYear()}-${String(first.getMonth() + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  const byDay = new Map(events.filter((e) => e.date).map((e) => [e.date!, e]));
  const todayIso = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  const inMonth = events.filter((e) => e.date?.startsWith(iso(1).slice(0, 7)));

  return (
    <div className="cal">
      <div className="cal__head">
        <button type="button" className="icon-btn" aria-label="Mese precedente" onClick={() => setOffset((o) => o - 1)} disabled={offset <= 0}>
          <ArrowLeft aria-hidden="true" />
        </button>
        <h2 className="cal__title" aria-live="polite">{monthFmt.format(first)}</h2>
        <button type="button" className="icon-btn" aria-label="Mese successivo" onClick={() => setOffset((o) => o + 1)} disabled={offset >= 2}>
          <ArrowRight aria-hidden="true" />
        </button>
      </div>
      {/* Griglia visiva; per gli screen reader contano solo i giorni con una serata (link). */}
      <div className="cal__grid">
        {WEEK.map((w, i) => <span key={i} className="cal__dow" aria-hidden="true">{w}</span>)}
        {Array.from({ length: lead }, (_, i) => <span key={`l${i}`} aria-hidden="true" />)}
        {Array.from({ length: days }, (_, i) => {
          const d = i + 1;
          const ev = byDay.get(iso(d));
          const cls = `cal__day${ev ? ' has-event' : ''}${iso(d) === todayIso ? ' is-today' : ''}`;
          return ev ? (
            <Link key={d} to={`/eventi/${ev.id}`} className={cls} aria-label={`${d} ${monthFmt.format(first)}: ${ev.title}${ev.demo ? ' (esempio demo)' : ''}`}>{d}</Link>
          ) : (
            <span key={d} className={cls} aria-hidden="true">{d}</span>
          );
        })}
      </div>
      <p className="small muted cal__foot">
        {inMonth.length ? `${inMonth.length} serata in questo mese (esempio demo).` : 'Nessuna data pubblicata per questo mese.'}
      </p>
    </div>
  );
}

/** «Domeniche del Fire» in evidenza, impaginata come una locandina. */
function FeaturedPoster({ e }: { e: FireEvent }) {
  const { mediaOr } = useStore();
  return (
    <section className="container featured" aria-labelledby="featured-title">
      <Link to={`/eventi/${e.id}`} className="poster">
        <span className="poster__media"><Img asset={mediaOr(e.photo)} className="poster__img" decorative sizes="(min-width: 768px) 58vw, 100vw" /></span>
        <span className="poster__body">
          <span className="poster__kicker">Ogni domenica · format del locale</span>
          <span id="featured-title" className="poster__title">Domeniche<br />del Fire</span>
          <span className="poster__meta">{e.summary}</span>
          <span className="poster__foot">
            <ConfirmBadge>Calendario e orari da confermare</ConfirmBadge>
            <span className="poster__cta">Scopri il format <ArrowRight size={18} aria-hidden="true" /></span>
          </span>
        </span>
      </Link>
    </section>
  );
}

function EventRow({ e }: { e: FireEvent }) {
  const { mediaOr } = useStore();
  return (
    <li className={`event-row${e.kind === 'format' ? ' event-row--format' : ''}`}>
      <Link to={`/eventi/${e.id}`} className="event-row__link">
        <span className="event-row__media"><Img asset={mediaOr(e.photo)} decorative className="event-row__img" sizes="(min-width: 1024px) 28vw, (min-width: 641px) 40vw, 100vw" /></span>
        <span className="event-row__body">
          <span className="event-row__badges">
            {e.kind === 'format' ? <ConfirmBadge>Format ricorrente</ConfirmBadge> : null}
            {e.status === 'annullato' && <span className="badge badge--neutral">Annullata</span>}
            {e.demo && <DemoBadge>Esempio demo</DemoBadge>}
          </span>
          <span className="event-row__title">{e.title}</span>
          <span className="event-row__date"><CalendarDays size={18} aria-hidden="true" /> {formatEventDate(e)}</span>
          <span className="event-row__summary">{e.summary}</span>
          <span className="event-row__cta">Dettagli della serata <ArrowRight size={18} aria-hidden="true" /></span>
        </span>
      </Link>
    </li>
  );
}

export function Events() {
  usePageMeta('Eventi');
  const { events, settings } = useStore();
  const [kind, setKind] = useState<'tutti' | 'format' | 'serata'>('tutti');
  const published = useMemo(() => events.filter((e) => e.status === 'pubblicato' || e.status === 'annullato'), [events]);
  const list = published.filter((e) => kind === 'tutti' || e.kind === kind);
  const featured = published.find((e) => e.id === 'domeniche-del-fire');

  return (
    <>
      <section className="page-hero page-hero--events">
        <div className="page-hero__bg"><Img asset={photos.djNotte} eager decorative /></div>
        <div className="container page-hero__over">
          <h1>Le serate</h1>
          <p className="lead">DJ set, aperitivi lunghi e le Domeniche del Fire. Le date le pubblica il locale: qui non ne inventiamo.</p>
        </div>
      </section>

      {featured && <FeaturedPoster e={featured} />}

      <section className="container events">
        <div className="events__main">
          <div className="chips" role="group" aria-label="Filtra gli eventi">
            {(['tutti', 'serata', 'format'] as const).map((k) => (
              <button key={k} type="button" className="chip" aria-pressed={kind === k} onClick={() => setKind(k)}>
                {k === 'tutti' ? 'Tutti' : k === 'serata' ? 'Serate' : 'Format ricorrenti'}
              </button>
            ))}
          </div>
          {list.length ? (
            <ul className="event-list">{list.map((e) => <EventRow key={e.id} e={e} />)}</ul>
          ) : (
            <div className="empty">
              <CalendarX2 size={40} aria-hidden="true" />
              <h2>Nessuna serata in programma.</h2>
              <p>Il calendario si aggiorna appena il locale pubblica le date. Nel frattempo, le novità passano da Instagram.</p>
              <a className="btn btn--gold" href={settings.instagram} target="_blank" rel="noopener noreferrer">
                <Instagram size={20} aria-hidden="true" /> Segui {settings.instagramHandle}
              </a>
            </div>
          )}
        </div>
        <aside className="events__side" aria-label="Calendario">
          <MiniCalendar events={published} />
        </aside>
      </section>

      <section className="past" aria-labelledby="past-title">
        <div className="container past__head">
          <h2 id="past-title">Le serate passate</h2>
          <p className="muted">Com'è andata: DJ set, compleanni del locale e tavolate. Foto reali, date non indicate.</p>
        </div>
        <ul className="container past__grid">
          {PAST.map((p, i) => (
            <li key={p.src} className={`past__item past__item--${i}`}><Img asset={p} /></li>
          ))}
        </ul>
      </section>
    </>
  );
}

export function EventDetail({ id }: { id: string }) {
  const { events, openBooking, settings, mediaOr, mediaAsset } = useStore();
  const e = events.find((x) => x.id === id && (x.status === 'pubblicato' || x.status === 'annullato'));
  const cancelled = e?.status === 'annullato';
  usePageMeta(e ? e.title : 'Serata non trovata');

  if (!e) {
    return (
      <section className="container empty empty--page">
        <CalendarX2 size={48} aria-hidden="true" />
        <h1>Serata non trovata.</h1>
        <p>Potrebbe essere stata rimossa o non ancora pubblicata.</p>
        <Link to="/eventi" className="btn btn--gold">Torna agli eventi</Link>
      </section>
    );
  }

  return (
    <article className="event-detail">
      <div className="container">
        <Link to="/eventi" className="back-link"><ArrowLeft size={18} aria-hidden="true" /> Tutti gli eventi</Link>
      </div>
      <div className="container event-detail__grid">
        <div className="event-detail__media">
          <div className="event-detail__poster"><Img asset={mediaOr(e.photo)} eager className="event-detail__hero" sizes="(min-width: 900px) 58vw, 100vw" /></div>
          <ul className="event-detail__thumbs" aria-label="Altre foto">
            {e.media.filter((m) => m !== e.photo && mediaAsset(m)).map((m) => (
              <li key={m}><Img asset={mediaAsset(m)!} /></li>
            ))}
          </ul>
        </div>
        <div className="event-detail__info">
          <p className="event-row__badges">
            {e.kind === 'format' && <ConfirmBadge>Format ricorrente</ConfirmBadge>}
            {cancelled && <span className="badge badge--neutral">Serata annullata</span>}
            {e.demo && <DemoBadge>Serata d'esempio: data e artista non reali</DemoBadge>}
          </p>
          <h1>{e.title}</h1>
          <dl className="event-facts">
            <div><dt><CalendarDays size={20} aria-hidden="true" /> Quando</dt><dd>{formatEventDate(e)}{e.endTime ? ` – ${e.endTime}` : ''}</dd></div>
            <div><dt><Mic2 size={20} aria-hidden="true" /> Chi suona</dt><dd>{e.artist}</dd></div>
            <div><dt><Users size={20} aria-hidden="true" /> Posti</dt><dd>{e.seats}</dd></div>
            <div><dt><MapPin size={20} aria-hidden="true" /> Dove</dt><dd>{settings.addressLine}</dd></div>
          </dl>
          {e.body.map((p, i) => <p key={i} className="event-detail__p">{p}</p>)}
          <div className="event-detail__ctas">
            {cancelled ? (
              <p className="muted">Questa serata è stata annullata dal locale.</p>
            ) : e.action === 'tavolo' ? (
              <button type="button" className="btn btn--primary" onClick={() => openBooking(e.id)}>Richiedi un tavolo per questa serata</button>
            ) : (
              <a className="btn btn--primary" href={settings.instagram} target="_blank" rel="noopener noreferrer">
                <Instagram size={20} aria-hidden="true" /> Chiedi info su Instagram
              </a>
            )}
            <Link to="/eventi" className="btn btn--ghost">Altre serate</Link>
          </div>
          <p className="small muted">Bevi responsabilmente: chi guida, ordina analcolico.</p>
        </div>
      </div>
    </article>
  );
}
