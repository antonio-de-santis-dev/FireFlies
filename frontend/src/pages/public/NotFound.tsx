import { Compass } from 'lucide-react';
import { Link, usePageMeta } from '../../router';

export function NotFound({ admin = false }: { admin?: boolean }) {
  usePageMeta('Pagina non trovata');
  return (
    <section className="container empty empty--page">
      <Compass size={48} aria-hidden="true" />
      <h1>Questa lucciola si è persa.</h1>
      <p>La pagina che cerchi non esiste, o non ancora.</p>
      <Link to={admin ? '/gestione' : '/'} className="btn btn--gold">{admin ? 'Torna alla panoramica' : 'Torna alla home'}</Link>
    </section>
  );
}
