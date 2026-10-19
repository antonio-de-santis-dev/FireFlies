import { CalendarDays, ExternalLink, Images, Inbox, LayoutDashboard, LogOut, Megaphone, Palette, Settings, UtensilsCrossed } from 'lucide-react';
import type { ReactNode } from 'react';
import { logout as serverLogout } from '../../api/auth';
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
  { to: '/gestione/foto', label: 'Foto', icon: Images, owner: false },
  { to: '/gestione/comunicazioni', label: 'Comunicazioni', icon: Megaphone, owner: false },
  { to: '/gestione/sito', label: 'Personalizza il sito', icon: Palette, owner: true },
  { to: '/gestione/impostazioni', label: 'Orari e contatti', icon: Settings, owner: true },
];

export function AdminLayout({ children }: { children: ReactNode }) {
  const { dispatch, requests, role } = useStore();
  const fresh = requests.filter((r) => r.status === 'nuova').length;
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
        <span><strong>Area gestori</strong><span className="demo-bar__more"> · accesso reale, contenuti ancora dimostrativi: le modifiche restano solo in questa sessione</span></span>
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
                      <span className="admin__count" aria-label={`${fresh} nuove`}>{fresh}</span>
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
        <main id="admin-main" className="admin__main" tabIndex={-1}>{children}</main>
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
