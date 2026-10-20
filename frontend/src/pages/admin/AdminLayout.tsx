import { Armchair, CalendarDays, ExternalLink, Images, Inbox, LayoutDashboard, LogOut, Megaphone, Palette, Settings, UtensilsCrossed } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { logout as serverLogout } from '../../api/auth';
import { ApiError } from '../../api/client';
import { loadAdminMenu, loadAnnouncements, loadLibrary, loadSettings, loadSiteContent } from '../../api/content';
import { listReservations } from '../../api/reservations';
import { Toast } from '../../components/PublicLayout';
import { Img } from '../../components/ui';
import { logo } from '../../data/assets';
import { Link, navigate } from '../../router';
import { useStore } from '../../store';

// `owner: true` = visibile solo al titolare. È solo comodità: i permessi veri li controlla il backend su ogni API.
export const ADMIN_NAV = [
  { to: '/gestione', label: 'Panoramica', icon: LayoutDashboard, owner: false },
  { to: '/gestione/menu', label: 'Menu e prezzi', icon: UtensilsCrossed, owner: false },
  { to: '/gestione/eventi', label: 'Eventi', icon: CalendarDays, owner: false },
  { to: '/gestione/richieste', label: 'Richieste', icon: Inbox, owner: false },
  { to: '/gestione/capienza', label: 'Capienza e tavoli', icon: Armchair, owner: false },
  { to: '/gestione/foto', label: 'Foto', icon: Images, owner: false },
  { to: '/gestione/comunicazioni', label: 'Comunicazioni', icon: Megaphone, owner: false },
  { to: '/gestione/sito', label: 'Personalizza il sito', icon: Palette, owner: true },
  { to: '/gestione/impostazioni', label: 'Orari e contatti', icon: Settings, owner: true },
];

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

export function AdminLayout({ children }: { children: ReactNode }) {
  const { dispatch, pendingRequests: fresh, role } = useStore();
  const ready = useAdminData();
  usePendingCount();
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
        <span><strong>Area gestori</strong><span className="demo-bar__more"> · menu, foto, testi, orari, avvisi e prenotazioni si salvano sul server; gli eventi sono ancora dimostrativi</span></span>
      </div>
      <div className="admin__shell">
        <aside className="admin__side">
          <Link to="/gestione" className="admin__brand" aria-label="Panoramica area gestori">
            <Img asset={logo.mark} decorative eager />
            <span>Gestione<small>{role === 'titolare' ? 'Titolare' : 'Staff'}</small></span>
          </Link>
          <nav aria-label="Area gestori" className="admin__nav">
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

export function AdminHead({ title, children, lead }: { title: string; lead?: string; children?: ReactNode }) {
  return (
    <header className="admin-head">
      <div>
        <h1>{title}</h1>
        {lead && <p className="muted">{lead}</p>}
      </div>
      {children && <div className="admin-head__actions">{children}</div>}
    </header>
  );
}
