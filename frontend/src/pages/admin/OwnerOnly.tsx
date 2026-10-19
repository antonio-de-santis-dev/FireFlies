import { LockKeyhole } from 'lucide-react';
import { Link, usePageMeta } from '../../router';

/** Mostrata allo staff che apre una pagina del titolare. In produzione il server risponde 403. */
export function OwnerOnly() {
  usePageMeta('Riservato al titolare');
  return (
    <div className="empty empty--admin">
      <LockKeyhole size={36} aria-hidden="true" />
      <h1>Riservato al titolare</h1>
      <p className="muted">Questa sezione cambia il sito per tutti i clienti: può modificarla solo il titolare.</p>
      <Link to="/gestione" className="btn btn--ghost btn--sm">Torna alla panoramica</Link>
    </div>
  );
}
