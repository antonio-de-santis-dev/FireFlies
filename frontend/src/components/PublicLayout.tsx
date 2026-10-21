import { CalendarHeart, CircleCheck, Info, Instagram, Megaphone, Menu as MenuIcon, UtensilsCrossed, X } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { logo } from '../data/assets';
import { Link, usePath } from '../router';
import { useStore } from '../store';
import { BookingDialog } from './BookingDialog';
import { Img } from './ui';

export const NAV = [
  { to: '/', label: 'Home' },
  { to: '/menu', label: 'Menu' },
  { to: '/eventi', label: 'Eventi' },
  { to: '/locale', label: 'Il locale' },
  { to: '/galleria', label: 'Galleria' },
  { to: '/contatti', label: 'Contatti' },
];

// NOTA: l'area gestori (#/gestione/login) NON è collegata da nessuna parte del sito pubblico.
// Nel mockup resta raggiungibile solo digitando l'URL; in produzione dovrà stare dietro autenticazione
// reale lato server (e idealmente su un percorso non indicizzato), mai esposta ai visitatori.
export function DemoBar() {
  return (
    <div className="demo-bar" role="note">
      <span>
        <strong>Prototipo dimostrativo</strong>
        <span className="demo-bar__more"> · orari, prezzi, date e testi sono da confermare con il locale</span>
      </span>
    </div>
  );
}

const todayIso = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);

/** Avvisi pubblicati dal titolare (Gestione → Comunicazioni). Chiudibili per la sessione. */
function Announcements() {
  const { announcements } = useStore();
  const [closed, setClosed] = useState<string[]>([]);
  const today = todayIso();
  const live = announcements.filter((a) => a.active && (!a.until || a.until >= today) && !closed.includes(a.id));
  if (!live.length) return null;
  return (
    <div className="announcements" role="region" aria-label="Avvisi del locale">
      {live.map((a) => (
        <div key={a.id} className={`announcement announcement--${a.tone}`}>
          <div className="container announcement__inner">
            {a.tone === 'importante' ? <Megaphone size={20} aria-hidden="true" /> : <Info size={20} aria-hidden="true" />}
            <p>{a.text}</p>
            <button type="button" className="icon-btn" aria-label="Nascondi questo avviso" onClick={() => setClosed((c) => [...c, a.id])}>
              <X size={18} aria-hidden="true" />
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}

function Header() {
  const { openBooking } = useStore();
  const path = usePath();
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => setOpen(false), [path]);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 24);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);
  useEffect(() => {
    document.body.classList.toggle('is-locked', open);
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);

  const overHero = path === '/' && !scrolled && !open;

  return (
    <header className={`site-header${overHero ? ' is-over-hero' : ''}${open ? ' is-open' : ''}`}>
      <div className="container site-header__bar">
        <Link to="/" className="brand" aria-label="FireFlies Aperitif, torna alla home">
          <Img asset={logo.mark} eager decorative className="brand__mark" />
          <span className="brand__text" aria-hidden="true">
            FireFlies<span>Martano</span>
          </span>
        </Link>
        <nav aria-label="Principale" className="site-nav">
          <ul>
            {NAV.map((n) => (
              <li key={n.to}><Link to={n.to}>{n.label}</Link></li>
            ))}
          </ul>
        </nav>
        <div className="site-header__actions">
          <button type="button" className="btn btn--action btn--sm header-cta" onClick={() => openBooking()}>
            Richiedi un tavolo
          </button>
          <button
            type="button"
            className="icon-btn menu-toggle"
            aria-expanded={open}
            aria-controls="mobile-menu"
            aria-label={open ? 'Chiudi il menu di navigazione' : 'Apri il menu di navigazione'}
            onClick={() => setOpen((o) => !o)}
          >
            {open ? <X aria-hidden="true" /> : <MenuIcon aria-hidden="true" />}
          </button>
        </div>
      </div>

      <div id="mobile-menu" className="mobile-menu" hidden={!open}>
        <nav aria-label="Menu mobile">
          <ul>
            {NAV.map((n) => (
              <li key={n.to}><Link to={n.to}>{n.label}</Link></li>
            ))}
          </ul>
        </nav>
        <div className="mobile-menu__foot">
          <button type="button" className="btn btn--primary" onClick={() => { setOpen(false); openBooking(); }}>
            Richiedi un tavolo
          </button>
          <a className="btn btn--ghost" href="https://www.instagram.com/fireflies.aperitif/" target="_blank" rel="noopener noreferrer">
            <Instagram size={20} aria-hidden="true" /> Instagram
          </a>
        </div>
      </div>
    </header>
  );
}

function Footer() {
  const { settings } = useStore();
  return (
    <footer className="site-footer">
      <div className="container site-footer__grid">
        <div className="site-footer__brand">
          <Img asset={logo.onDark} className="site-footer__logo" />
          <p className="muted">Breakfast &amp; Aperitif · {settings.city}</p>
        </div>
        <nav aria-label="Footer" className="site-footer__nav">
          <ul>
            {NAV.map((n) => (
              <li key={n.to}><Link to={n.to}>{n.label}</Link></li>
            ))}
          </ul>
        </nav>
        <div className="site-footer__info">
          <a href={settings.instagram} target="_blank" rel="noopener noreferrer" className="site-footer__ig">
            <Instagram size={20} aria-hidden="true" /> {settings.instagramHandle}
          </a>
          <p className="muted small">{settings.addressLine}</p>
          <p className="small responsible">Bevi responsabilmente. Se guidi, scegli un analcolico: ce ne sono di buoni.</p>
        </div>
      </div>
      <div className="container site-footer__legal small">
        <span>© FireFlies Aperitif · Prototipo non pubblico</span>
        <span className="muted">Privacy e cookie policy: da redigere prima della pubblicazione</span>
      </div>
    </footer>
  );
}

function MobileCta() {
  const { openBooking } = useStore();
  const path = usePath();
  return (
    <div className="mobile-cta">
      {path !== '/menu' ? (
        <Link to="/menu" className="btn btn--ghost btn--sm">
          <UtensilsCrossed size={18} aria-hidden="true" /> Menu
        </Link>
      ) : (
        <Link to="/eventi" className="btn btn--ghost btn--sm">
          <CalendarHeart size={18} aria-hidden="true" /> Eventi
        </Link>
      )}
      <button type="button" className="btn btn--action btn--sm" onClick={() => openBooking()}>
        Richiedi un tavolo
      </button>
    </div>
  );
}

export function Toast() {
  const { toast } = useStore();
  return (
    <div aria-live="polite" role="status">
      {toast && (
        <div className="toast">
          <CircleCheck aria-hidden="true" /> {toast}
        </div>
      )}
    </div>
  );
}

export function PublicLayout({ children }: { children: ReactNode }) {
  const { booking } = useStore();
  const path = usePath();
  // FIX-1: la prima pagina entra con il velo dell'avvio, non con una seconda dissolvenza sovrapposta
  const [firstRoute] = useState(() => path.split('?')[0]);
  const route = path.split('?')[0];
  const [navigated, setNavigated] = useState(false);
  useEffect(() => {
    if (route !== firstRoute) setNavigated(true);
  }, [route, firstRoute]);
  return (
    <>
      <a className="skip-link" href="#contenuto" onClick={(e) => { e.preventDefault(); document.getElementById('contenuto')?.focus(); }}>
        Vai al contenuto
      </a>
      <DemoBar />
      <Announcements />
      <Header />
      <main id="contenuto" tabIndex={-1}>
        {/* la chiave sul percorso fa ripartire l'animazione d'ingresso a ogni cambio pagina (non alla prima) */}
        <div className={navigated || route !== firstRoute ? 'page-enter' : undefined} key={route}>{children}</div>
      </main>
      <Footer />
      <MobileCta />
      {booking.open && <BookingDialog key={booking.eventId ?? 'free'} />}
      <Toast />
    </>
  );
}
