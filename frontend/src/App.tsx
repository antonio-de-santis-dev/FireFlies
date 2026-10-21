import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react';
import { Img } from './components/ui';
import { logo } from './data/assets';
import { fetchSession, uiRole } from './api/auth';
import { PublicLayout } from './components/PublicLayout';
import { Contacts } from './pages/public/Contacts';
import { EventDetail, Events } from './pages/public/Events';
import { Gallery } from './pages/public/Gallery';
import { Home } from './pages/public/Home';
import { Locale } from './pages/public/Locale';
import { Menu } from './pages/public/Menu';
import { NotFound } from './pages/public/NotFound';
import { match, navigate, usePath } from './router';
import { useStore } from './store';

// FASE 8 (performance): l'area gestori è un file a parte, scaricato solo da chi apre /gestione. I visitatori del sito
// non scaricano più editor, tabelle e moduli dei gestori.
const loadAdminArea = () => import('./pages/admin/AdminArea');
const AdminArea = lazy(loadAdminArea);
const Login = lazy(() => import('./pages/admin/Login').then((m) => ({ default: m.Login })));

/**
 * Animazione d'ingresso delle sezioni: attiva solo se supportata e se l'utente non chiede movimento ridotto.
 * Si registra di nuovo quando arrivano i contenuti: prima c'è solo la schermata di attesa, senza sezioni da osservare.
 */
function useReveal(path: string, ready: boolean) {
  useEffect(() => {
    if (!ready) return;
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
  }, [path, ready]);
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

/** Mentre arrivano i contenuti dal backend (pochi millisecondi) o se il server non risponde. */
function ContentGate({ failed, retry }: { failed: boolean; retry: () => void }) {
  return (
    <main className="content-gate" id="contenuto" aria-busy={!failed}>
      <GateBody failed={failed} retry={retry} />
    </main>
  );
}

function GateBody({ failed, retry }: { failed: boolean; retry: () => void }) {
  return (
    <>
      <Img asset={logo.onDarkSmall} eager className="content-gate__logo" />
      {failed ? (
        <div role="alert" className="content-gate__msg">
          <p>Il sito non riesce a caricare i contenuti in questo momento.</p>
          <button type="button" className="btn btn--gold" onClick={retry}>Riprova</button>
        </div>
      ) : (
        <p className="content-gate__msg muted">Caricamento…</p>
      )}
    </>
  );
}

/**
 * FIX-1, req. 1 — uscita morbida della schermata d'attesa: arrivati i contenuti, la pagina è già sotto e il velo con il
 * logo sfuma (circa mezzo secondo, solo opacità e scala: niente ricalcoli di layout). Non blocca i tocchi (pointer-events:
 * none), si toglie da solo anche se l'animazione non parte (scheda in background) e non c'è con «riduci movimento».
 * Solo al primo caricamento: la navigazione interna non lo ripropone.
 */
let introShown = false;
function useIntroVeil(ready: boolean) {
  const [phase, setPhase] = useState<'waiting' | 'leaving' | 'done'>(() => (introShown ? 'done' : 'waiting'));
  useEffect(() => {
    if (!ready || phase !== 'waiting') return;
    introShown = true;
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce) {
      setPhase('done');
      return;
    }
    setPhase('leaving');
  }, [ready, phase]);
  useEffect(() => {
    if (phase !== 'leaving') return;
    const t = window.setTimeout(() => setPhase('done'), 900);
    return () => window.clearTimeout(t);
  }, [phase]);
  return { phase, finish: () => setPhase('done') };
}

function IntroVeil({ leaving, onDone }: { leaving: boolean; onDone: () => void }) {
  return (
    <div className={`intro-veil${leaving ? ' is-leaving' : ''}`} aria-hidden="true"
      onAnimationEnd={(e) => e.target === e.currentTarget && onDone()}>
      <GateBody failed={false} retry={() => undefined} />
    </div>
  );
}

export function App() {
  const path = usePath();
  const route = path.split('?')[0];
  const { role, authChecked, dispatch, openBooking, contentStatus, reloadPublic } = useStore();
  const signedIn = role != null;
  useReveal(path, contentStatus === 'ready');
  const intro = useIntroVeil(contentStatus === 'ready');

  useEffect(() => {
    if (route === '/prenota') openBooking();
  }, [route, openBooking]);

  const isAdmin = route.startsWith('/gestione');
  // sulla pagina di accesso si scarica in anticipo l'area gestori: dopo il login è già pronta
  useEffect(() => {
    if (isAdmin) void loadAdminArea();
  }, [isAdmin]);
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
    return (
      <Suspense fallback={<p className="muted" role="status">Caricamento dell'area gestori…</p>}>
        {route === '/gestione/login' || !signedIn ? <Login /> : <AdminArea route={route} role={role!} />}
      </Suspense>
    );
  }
  return (
    <>
      <PublicLayout>{publicPage(path)}</PublicLayout>
      {/* presente già nel primo fotogramma con i contenuti: la pagina compare solo sotto il velo, mai di colpo */}
      {intro.phase !== 'done' && <IntroVeil leaving={intro.phase === 'leaving'} onDone={intro.finish} />}
    </>
  );
}
