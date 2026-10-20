// Area gestori caricata a parte (FASE 8, performance): import dinamico da App.tsx.
import type { ReactNode } from 'react';
import { NotFound } from '../public/NotFound';
import { match } from '../../router';
import type { Role } from '../../store';
import { AdminLayout } from './AdminLayout';
import { Announcements } from './Announcements';
import { Customize } from './Customize';
import { Dashboard } from './Dashboard';
import { EventEditor, EventsAdmin } from './EventsAdmin';
import { Photos } from './media';
import { MenuAdmin, MenuEditor } from './MenuAdmin';
import { OwnerOnly } from './OwnerOnly';
import { Requests } from './Requests';
import { Settings } from './Settings';
import { Today } from './Today';

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
  if (route === '/gestione/oggi') return <Today />;
  if (route === '/gestione/foto') return <Photos />;
  if (route === '/gestione/comunicazioni') return <Announcements />;
  if (route === '/gestione/sito') return owner(<Customize />);
  if (route === '/gestione/impostazioni') return owner(<Settings />);
  return <NotFound admin />;
}

export default function AdminArea({ route, role }: { route: string; role: Role }) {
  return <AdminLayout>{adminPage(route, role)}</AdminLayout>;
}
