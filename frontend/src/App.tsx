import { useEffect, type ReactNode } from 'react';
import { Img } from './components/ui';
import { logo } from './data/assets';
import { fetchSession, uiRole } from './api/auth';
import { PublicLayout } from './components/PublicLayout';
import { AdminLayout } from './pages/admin/AdminLayout';
import { Announcements } from './pages/admin/Announcements';
import { Customize } from './pages/admin/Customize';
import { Dashboard } from './pages/admin/Dashboard';
import { EventEditor, EventsAdmin } from './pages/admin/EventsAdmin';
import { Login } from './pages/admin/Login';
import { Photos } from './pages/admin/media';
import { OwnerOnly } from './pages/admin/OwnerOnly';
import { MenuAdmin, MenuEditor } from './pages/admin/MenuAdmin';
import { Requests } from './pages/admin/Requests';
import { Settings } from './pages/admin/Settings';
import { Contacts } from './pages/public/Contacts';
import { EventDetail, Events } from './pages/public/Events';
import { Gallery } from './pages/public/Gallery';
import { Home } from './pages/public/Home';
import { Locale } from './pages/public/Locale';
import { Menu } from './pages/public/Menu';
import { NotFound } from './pages/public/NotFound';
import { match, navigate, usePath } from './router';
import { useStore, type Role } from './store';

/** Animazione d'ingresso delle sezioni: attiva solo se supportata e se l'utente non chiede movimento ridotto. */
function useReveal(path: string) {
  useEffect(() => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce || !('IntersectionObserver' in window)) return;
    document.documentElement.classList.add('reveal-ready');
    const io = new IntersectionObserver(
      (entries) => entries.forEach((e) => {
        if (e.isIntersecting) {
          e.target.classList.add('is-in');
          io.unobserve(e.target);
        }
      }),
      { rootMargin: '0px 0px -8% 0px', threshold: 0.08 },
    );
    const t = window.setTimeout(() => document.querySelectorAll('.reveal:not(.is-in)').forEach((el) => io.observe(el)), 30);
    return () => {
      window.clearTimeout(t);
      io.disconnect();
    };
  }, [path]);
}

function publicPage(path: string): ReactNode {
  const route = path.split('?')[0];
  if (route === '/' || route === '/prenota') return <Home />;
  if (route === '/menu') return <Menu />;
  if (route === '/eventi') return <Events />;
  const ev = match('/eventi/:id', route);
  if (ev) return <EventDetail key={ev.id} id={ev.id} />;
  if (route === '/galleria') return <Gallery />;
  if (route === '/locale') return <Locale />;
  if (route === '/contatti') return <Contacts />;
  return <NotFound />;
}

function adminPage(route: string, role: Role): ReactNode {
  const owner = (page: ReactNode) => (role === 'titolare' ? page : <OwnerOnly />);
  if (route === '/gestione') return <Dashboard />;
  if (route === '/gestione/menu') return <MenuAdmin />;
  const mi = match('/gestione/menu/:id', route);
  if (mi) return <MenuEditor key={mi.id} id={mi.id} />;
  if (route === '/gestione/eventi') return <EventsAdmin />;
  const ei = match('/gestione/eventi/:id', route);
  if (ei) return <EventEditor key={ei.id} id={ei.id} />;
  if (route === '/gestione/richieste') return <Requests />;
  if (route === '/gestione/foto') return <Photos />;
  if (route === '/gestione/comunicazioni') return <Announcements />;
  if (route === '/gestione/sito') return owner(<Customize />);
  if (route === '/gestione/impostazioni') return owner(<Settings />);
  return <NotFound admin />;
}

/** Mentre arrivano i contenuti dal backend (pochi millisecondi) o se il server non risponde. */
function ContentGate({ failed, retry }: { failed: boolean; retry: () => void }) {
  return (
    <main className="content-gate" id="contenuto" aria-busy={!failed}>
      <Img asset={logo.onDarkSmall} eager className="content-gate__logo" />
      {failed ? (
        <div role="alert" className="content-gate__msg">
          <p>Il sito non riesce a caricare i contenuti in questo momento.</p>
          <button type="button" className="btn btn--gold" onClick={retry}>Riprova</button>
        </div>
      ) : (
        <p className="content-gate__msg muted">Caricamento…</p>
      )}
    </main>
  );
}

export function App() {
  const path = usePath();
  const route = path.split('?')[0];
  const { role, authChecked, dispatch, openBooking, contentStatus, reloadPublic } = useStore();
  const signedIn = role != null;
  useReveal(path);

  useEffect(() => {
    if (route === '/prenota') openBooking();
  }, [route, openBooking]);

  const isAdmin = route.startsWith('/gestione');
  // Area gestori: a ogni cambio di pagina si chiede al backend se la sessione è ancora valida.
  // Il ruolo viene dal backend; se la sessione è scaduta o chiusa si torna all'accesso con un avviso.
  useEffect(() => {
    if (!isAdmin) return;
    let current = true;
    fetchSession()
      .then((session) => {
        if (!current) return;
        if (session.authenticated && session.user) {
          const { displayName, email } = session.user;
          dispatch({ type: 'session', role: uiRole(session.user.role), user: { displayName, email } });
        } else {
          const expired = session.reason === 'SESSION_EXPIRED' || signedIn;
          dispatch({ type: 'session', role: null, notice: expired ? 'La sessione è scaduta o è stata chiusa: accedi di nuovo.' : null });
          if (route !== '/gestione/login') navigate('/gestione/login');
        }
      })
      .catch(() => {
        if (!current) return;
        dispatch({ type: 'session', role: null, notice: 'Il server non risponde: non è possibile verificare l’accesso.' });
        if (route !== '/gestione/login') navigate('/gestione/login');
      });
    return () => {
      current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- si ricontrolla a ogni pagina, non a ogni cambio di stato
  }, [isAdmin, route]);

  // Scheda rimasta aperta (es. il giorno dopo): quando torna in primo piano si ricontrolla la sessione,
  // così chi la riprende vede subito l'accesso invece di compilare un modulo che non potrà salvare.
  useEffect(() => {
    if (!isAdmin || !signedIn) return;
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      fetchSession()
        .then((session) => {
          if (session.authenticated) return;
          dispatch({ type: 'session', role: null, notice: 'La sessione è scaduta o è stata chiusa: accedi di nuovo.' });
          navigate('/gestione/login');
        })
        .catch(() => {});
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [isAdmin, signedIn, dispatch]);

  if (contentStatus !== 'ready' && route !== '/gestione/login') {
    return <ContentGate failed={contentStatus === 'error'} retry={reloadPublic} />;
  }

  if (isAdmin) {
    if (!authChecked && route !== '/gestione/login') return null; // pochi millisecondi: nessuna pagina riservata prima della verifica
    if (route === '/gestione/login' || !signedIn) return <Login />;
    return <AdminLayout>{adminPage(route, role!)}</AdminLayout>;
  }
  return <PublicLayout>{publicPage(path)}</PublicLayout>;
}
