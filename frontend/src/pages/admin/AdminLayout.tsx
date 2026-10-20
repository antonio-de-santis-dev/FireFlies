import { CalendarCheck, CalendarDays, ExternalLink, Images, Inbox, LayoutDashboard, LogOut, Megaphone, Menu as MenuIcon, Palette, Settings, UtensilsCrossed, X } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { logout as serverLogout } from '../../api/auth';
import { ApiError } from '../../api/client';
import { loadAdminMenu, loadAnnouncements, loadLibrary, loadSettings, loadSiteContent } from '../../api/content';
import { listReservations } from '../../api/reservations';
import { Toast } from '../../components/PublicLayout';
import { Img } from '../../components/ui';
import { logo } from '../../data/assets';
import { Link, navigate, usePath } from '../../router';
import { useStore } from '../../store';

// `owner: true` = visibile solo al titolare. È solo comodità: i permessi veri li controlla il backend su ogni API.
// Ordine della FASE 7 (req. 2); «Capienza e tavoli» non esiste più: la capienza è in Impostazioni.
export const ADMIN_NAV = [
  { to: '/gestione', label: 'Panoramica', icon: LayoutDashboard, owner: false },
  { to: '/gestione/menu', label: 'Menu e prezzi', icon: UtensilsCrossed, owner: false },
  { to: '/gestione/oggi', label: 'Calendario prenotazioni oggi', icon: CalendarCheck, owner: false },
  { to: '/gestione/eventi', label: 'Eventi', icon: CalendarDays, owner: false },
  { to: '/gestione/richieste', label: 'Richieste', icon: Inbox, owner: false },
  { to: '/gestione/foto', label: 'Foto', icon: Images, owner: false },
  { to: '/gestione/comunicazioni', label: 'Comunicazioni', icon: Megaphone, owner: false },
  { to: '/gestione/sito', label: 'Personalizza il sito', icon: Palette, owner: true },
  { to: '/gestione/impostazioni', label: 'Impostazioni', icon: Settings, owner: true },
];

/** Voce attiva: la pagina o una sua sottopagina (es. /gestione/eventi/123 → Eventi). */
export function activeNav(path: string) {
  const route = path.split('?')[0];
  return [...ADMIN_NAV].sort((a, b) => b.to.length - a.to.length).find((n) => route === n.to || route.startsWith(`${n.to}/`)) ?? ADMIN_NAV[0];
}

/**
 * Dati dell'area gestori dal backend: menu, libreria foto e avvisi per tutti; testi e impostazioni solo per il
 * titolare (per lo staff il backend risponderebbe 403). Le pagine mostrano i dati appena arrivano.
 */
function useAdminData() {
  const { dispatch, role, settings, notify } = useStore();
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (!role) return;
    let current = true;
    const jobs: Promise<unknown>[] = [
      loadAdminMenu().then((m) => current && dispatch({ type: 'admin/menu', ...m })),
      loadLibrary().then((items) => current && dispatch({ type: 'media/set', items })),
      loadAnnouncements().then((items) => current && dispatch({ type: 'announcements/set', items })),
    ];
    if (role === 'titolare') {
      jobs.push(loadSiteContent().then(({ content, mediaIndex }) => current && dispatch({ type: 'content/save', content, mediaIndex })));
      jobs.push(loadSettings(settings).then((s) => current && dispatch({ type: 'settings/save', settings: s })));
    }
    Promise.all(jobs)
      .catch((e) => {
        // 401: ci pensa il gestore globale (ritorno all'accesso); il resto lo dice un avviso
        if (current && !(e instanceof ApiError && e.status === 401)) notify(e instanceof ApiError ? e.message : 'Dati non caricati: ricarica la pagina.');
      })
      .finally(() => current && setReady(true));
    return () => {
      current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- si ricarica solo quando cambia l'account
  }, [role]);
  return ready;
}

/** Conta le richieste «in attesa» (contatore nel menu). Silenziosa: un errore non deve disturbare la pagina. */
export function refreshPending(dispatch: ReturnType<typeof useStore>['dispatch']) {
  return listReservations({ status: ['PENDING'], size: 1 })
    .then((r) => dispatch({ type: 'requests/pending', count: r.total }))
    .catch(() => undefined);
}

function usePendingCount() {
  const { dispatch, role } = useStore();
  useEffect(() => {
    if (!role) return;
    void refreshPending(dispatch);
    const timer = window.setInterval(() => void refreshPending(dispatch), 60_000);
    const onFocus = () => void refreshPending(dispatch);
    window.addEventListener('focus', onFocus);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', onFocus);
    };
  }, [role, dispatch]);
}

/**
 * Menu dei telefoni (FASE 7, req. 2): un pulsante apre l'elenco completo sopra la pagina. Si chiude con il pulsante,
 * con Esc, scegliendo una voce o toccando fuori; aprendolo il focus va sulla voce attiva, chiudendolo torna al pulsante.
 */
function useMobileNav(path: string) {
  const [open, setOpen] = useState(false);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => setOpen(false), [path]);
  useEffect(() => {
    if (!open) return;
    const focusFrame = requestAnimationFrame(() => {
      const nav = navRef.current;
      (nav?.querySelector<HTMLAnchorElement>('a[aria-current="page"]') ?? nav?.querySelector<HTMLAnchorElement>('a'))?.focus();
    });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        toggleRef.current?.focus();
      }
    };
    const onClick = (e: MouseEvent) => {
      const t = e.target as Node;
      if (!navRef.current?.contains(t) && !toggleRef.current?.contains(t)) setOpen(false);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onClick);
    return () => {
      cancelAnimationFrame(focusFrame);
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onClick);
    };
  }, [open]);
  return { open, setOpen, toggleRef, navRef };
}

export function AdminLayout({ children }: { children: ReactNode }) {
  const { dispatch, pendingRequests: fresh, role } = useStore();
  const ready = useAdminData();
  usePendingCount();
  const path = usePath();
  const current = activeNav(path);
  const mobile = useMobileNav(path);
  const logout = async () => {
    try {
      await serverLogout();
    } catch {
      // Anche se il server non risponde si esce dall'interfaccia; la sessione scade comunque dopo 8 ore.
    }
    dispatch({ type: 'session', role: null });
    navigate('/gestione/login');
  };
  return (
    <div className="admin">
      <a className="skip-link" href="#admin-main" onClick={(e) => { e.preventDefault(); document.getElementById('admin-main')?.focus(); }}>
        Vai al contenuto
      </a>
      <div className="demo-bar demo-bar--admin" role="note">
        <span><strong>Area gestori</strong><span className="demo-bar__more"> · menu, foto, testi, orari, avvisi, prenotazioni ed eventi si salvano sul server</span></span>
      </div>
      <div className="admin__shell">
        <aside className="admin__side">
          <Link to="/gestione" className="admin__brand" aria-label="Panoramica area gestori">
            <Img asset={logo.mark} decorative eager />
            <span>Gestione<small>{role === 'titolare' ? 'Titolare' : 'Staff'}</small></span>
          </Link>
          <button ref={mobile.toggleRef} type="button" className="admin__menu-btn" aria-expanded={mobile.open} aria-controls="admin-nav"
            aria-label={`Menu, pagina attuale: ${current.label}`} onClick={() => mobile.setOpen(!mobile.open)}>
            {mobile.open ? <X size={22} aria-hidden="true" /> : <MenuIcon size={22} aria-hidden="true" />}
            <span className="admin__menu-label" aria-hidden="true">{current.label}</span>
          </button>
          <nav ref={mobile.navRef} id="admin-nav" aria-label="Area gestori" className={`admin__nav${mobile.open ? ' is-open' : ''}`}>
            <ul>
              {ADMIN_NAV.filter((n) => !n.owner || role === 'titolare').map(({ to, label, icon: Icon }) => (
                <li key={to}>
                  <Link to={to}>
                    <Icon size={20} aria-hidden="true" />
                    <span>{label}</span>
                    {to === '/gestione/richieste' && fresh > 0 && (
                      <span className="admin__count" aria-label={`${fresh} in attesa`}>{fresh}</span>
                    )}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
          <div className="admin__side-foot">
            <a href="#/" className="admin__site"><ExternalLink size={18} aria-hidden="true" /> Vedi il sito</a>
            <button type="button" className="admin__logout" onClick={logout}><LogOut size={18} aria-hidden="true" /> Esci</button>
          </div>
        </aside>
        <main id="admin-main" className="admin__main" tabIndex={-1} aria-busy={!ready}>{ready ? children : <p className="muted">Caricamento dei dati…</p>}</main>
      </div>
      <Toast />
    </div>
  );
}

/** `compact`: sul telefono titolo più piccolo e senza la descrizione, per lasciare spazio ai contenuti (FASE 8). */
export function AdminHead({ title, children, lead, compact = false }: { title: string; lead?: string; children?: ReactNode; compact?: boolean }) {
  return (
    <header className={`admin-head${compact ? ' admin-head--compact' : ''}`}>
      <div>
        <h1>{title}</h1>
        {lead && <p className="muted">{lead}</p>}
      </div>
      {children && <div className="admin-head__actions">{children}</div>}
    </header>
  );
}
