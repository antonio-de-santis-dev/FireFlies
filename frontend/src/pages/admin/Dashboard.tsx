import { ArrowRight, CalendarDays, CircleAlert, Clock, Images, Inbox, Megaphone, Palette, PhoneCall, UtensilsCrossed } from 'lucide-react';
import { DemoBadge } from '../../components/ui';
import { capitalize, formatEventDate } from '../../data/events';
import { requestStatusLabel, type RequestStatus } from '../../data/requests';
import { Link, usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';

const STATUS_CLASS: Record<RequestStatus, string> = {
  nuova: 'badge--action',
  'da-contattare': 'badge--confirm',
  confermata: 'badge--success',
  annullata: 'badge--neutral',
};

export function StatusBadge({ status }: { status: RequestStatus }) {
  return <span className={`badge ${STATUS_CLASS[status]}`}>{requestStatusLabel[status]}</span>;
}

const shortDate = new Intl.DateTimeFormat('it-IT', { weekday: 'short', day: 'numeric', month: 'short' });
export const fmtShort = (iso: string) => capitalize(shortDate.format(new Date(`${iso}T12:00:00`)));

export function Dashboard() {
  usePageMeta('Panoramica');
  const { requests, events, menu, settings, role } = useStore();
  const fresh = requests.filter((r) => r.status === 'nuova');
  const toCall = requests.filter((r) => r.status === 'da-contattare');
  const upcoming = events.filter((e) => e.kind === 'serata').sort((a, b) => (a.date ?? '').localeCompare(b.date ?? ''));
  const incomplete = menu.filter((m) => m.price == null || m.allergens == null);
  const todo = [
    !settings.hoursConfirmed && 'Orari di apertura',
    !settings.addressConfirmed && 'Indirizzo esatto',
    !settings.phone && 'Numero di telefono',
    'Testi «Il locale» e nomi dello staff',
    'Privacy e cookie policy',
  ].filter(Boolean) as string[];

  return (
    <>
      <AdminHead title="Ciao! Ecco la giornata." lead="Panoramica dimostrativa: richieste ed eventi sono esempi.">
        <DemoBadge>Dati demo</DemoBadge>
      </AdminHead>

      <nav className="quick" aria-label="Azioni rapide">
        <ul>
          {role === 'titolare' && (
            <li><Link to="/gestione/sito" className="quick__item quick__item--main"><Palette aria-hidden="true" /><span><strong>Personalizza il sito</strong><small>Frase, foto di apertura, sezioni, storia e staff</small></span></Link></li>
          )}
          <li><Link to="/gestione/menu" className="quick__item"><UtensilsCrossed aria-hidden="true" /><span><strong>Menu e prezzi</strong><small>Voci, prezzi, allergeni, foto</small></span></Link></li>
          <li><Link to="/gestione/foto" className="quick__item"><Images aria-hidden="true" /><span><strong>Foto</strong><small>Carica e scegli la galleria</small></span></Link></li>
          <li><Link to="/gestione/comunicazioni" className="quick__item"><Megaphone aria-hidden="true" /><span><strong>Comunicazioni</strong><small>Avvisi in cima al sito</small></span></Link></li>
          {role === 'titolare' && (
            <li><Link to="/gestione/impostazioni" className="quick__item"><Clock aria-hidden="true" /><span><strong>Orari e contatti</strong><small>Orari, telefono, mappa</small></span></Link></li>
          )}
        </ul>
      </nav>

      <ul className="stats" aria-label="Riepilogo">
        <li className="stats__item stats__item--hot">
          <Inbox aria-hidden="true" />
          <span className="stats__n">{fresh.length}</span>
          <span>richieste nuove</span>
        </li>
        <li className="stats__item">
          <PhoneCall aria-hidden="true" />
          <span className="stats__n">{toCall.length}</span>
          <span>da ricontattare</span>
        </li>
        <li className="stats__item">
          <CalendarDays aria-hidden="true" />
          <span className="stats__n">{upcoming.length}</span>
          <span>serate in calendario</span>
        </li>
        <li className="stats__item">
          <UtensilsCrossed aria-hidden="true" />
          <span className="stats__n">{incomplete.length}</span>
          <span>voci menu da completare</span>
        </li>
      </ul>

      <div className="dash">
        <section className="panel dash__requests" aria-labelledby="dash-req">
          <div className="panel__head">
            <h2 id="dash-req">Richieste da gestire</h2>
            <Link to="/gestione/richieste" className="panel__link">Tutte <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
          {fresh.length + toCall.length === 0 ? (
            <p className="muted">Nessuna richiesta in attesa. Ottimo lavoro.</p>
          ) : (
            <ul className="req-mini">
              {[...fresh, ...toCall].map((r) => (
                <li key={r.id}>
                  <span className="req-mini__when">{fmtShort(r.date)} · {r.time}</span>
                  <span className="req-mini__who">{r.name} · {r.guests} pers.</span>
                  <StatusBadge status={r.status} />
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel" aria-labelledby="dash-ev">
          <div className="panel__head">
            <h2 id="dash-ev">Prossimi eventi</h2>
            <Link to="/gestione/eventi" className="panel__link">Gestisci <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
          <ul className="ev-mini">
            {upcoming.map((e) => (
              <li key={e.id}>
                <Link to={`/gestione/eventi/${e.id}`}>{e.title}</Link>
                <span className="muted small">{formatEventDate(e)}</span>
                <span className={`badge ${e.status === 'pubblicato' ? 'badge--success' : 'badge--info'}`}>
                  {e.status === 'pubblicato' ? 'Pubblicato' : 'Bozza'}
                </span>
              </li>
            ))}
          </ul>
        </section>

        <section className="panel" aria-labelledby="dash-menu">
          <div className="panel__head">
            <h2 id="dash-menu">Menu da aggiornare</h2>
            <Link to="/gestione/menu" className="panel__link">Apri il menu <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
          <p className="small muted">Voci senza prezzo o senza allergeni dichiarati.</p>
          <ul className="todo">
            {incomplete.slice(0, 5).map((m) => (
              <li key={m.id}>
                <Link to={`/gestione/menu/${m.id}`}>{m.name}</Link>
                <span className="small muted">
                  {[m.price == null && 'prezzo', m.allergens == null && 'allergeni'].filter(Boolean).join(' e ')} mancanti
                </span>
              </li>
            ))}
          </ul>
          {incomplete.length > 5 && <p className="small muted">e altre {incomplete.length - 5}…</p>}
        </section>

        <section className="panel panel--warn" aria-labelledby="dash-todo">
          <div className="panel__head">
            <h2 id="dash-todo"><CircleAlert size={22} aria-hidden="true" /> Da confermare prima di pubblicare</h2>
          </div>
          <ul className="todo todo--plain">
            {todo.map((t) => <li key={t}>{t}</li>)}
          </ul>
          {role === 'titolare' && <Link to="/gestione/impostazioni" className="btn btn--ghost btn--sm">Vai a orari e contatti</Link>}
        </section>
      </div>
    </>
  );
}
