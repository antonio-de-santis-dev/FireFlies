import { Inbox, MessageSquare } from 'lucide-react';
import { useState } from 'react';
import { DemoBadge } from '../../components/ui';
import { requestStatusLabel, type RequestStatus } from '../../data/requests';
import { usePageMeta } from '../../router';
import { useStore } from '../../store';
import { AdminHead } from './AdminLayout';
import { fmtShort, StatusBadge } from './Dashboard';

const STATUSES: RequestStatus[] = ['nuova', 'da-contattare', 'confermata', 'annullata'];
const isPhone = (c: string) => /^\+?[\d\s.-]{8,16}$/.test(c.trim());

export function Requests() {
  usePageMeta('Richieste tavoli');
  const { requests, dispatch, notify } = useStore();
  const [tab, setTab] = useState<RequestStatus | 'tutte'>('tutte');
  const rows = requests.filter((r) => tab === 'tutte' || r.status === tab);

  return (
    <>
      <AdminHead title="Richieste tavoli" lead="Impostando «Confermata» il sistema invierà un SMS al cliente: nel prototipo l'invio è solo simulato.">
        <DemoBadge>Richieste d'esempio</DemoBadge>
      </AdminHead>
      <div className="chips" role="group" aria-label="Filtra per stato">
        <button type="button" className="chip" aria-pressed={tab === 'tutte'} onClick={() => setTab('tutte')}>
          Tutte<span className="count">{requests.length}</span>
        </button>
        {STATUSES.map((s) => (
          <button key={s} type="button" className="chip" aria-pressed={tab === s} onClick={() => setTab(s)}>
            {requestStatusLabel[s]}<span className="count">{requests.filter((r) => r.status === s).length}</span>
          </button>
        ))}
      </div>

      {rows.length === 0 ? (
        <div className="empty empty--admin">
          <Inbox size={36} aria-hidden="true" />
          <h2>Nessuna richiesta «{tab === 'tutte' ? '' : requestStatusLabel[tab]}»</h2>
          <p className="muted">Quando arriva una richiesta dal sito la trovi qui.</p>
        </div>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <caption className="sr-only">Richieste tavoli</caption>
            <thead>
              <tr>
                <th scope="col">Quando</th>
                <th scope="col">Persone</th>
                <th scope="col">Nome</th>
                <th scope="col">Contatto</th>
                <th scope="col">Note</th>
                <th scope="col">Stato</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td data-label="Quando"><strong>{fmtShort(r.date)} · {r.time}</strong><br /><span className="small muted">Ricevuta {r.receivedAt}</span></td>
                  <td data-label="Persone">{r.guests}</td>
                  <td data-label="Nome">{r.name}</td>
                  <td data-label="Contatto">{r.contact}</td>
                  <td data-label="Note">{r.notes || <span className="muted">—</span>}</td>
                  <td data-label="Stato">
                    <div className="status-cell">
                      <StatusBadge status={r.status} />
                      {r.status === 'confermata' && r.notice && (
                        <span className={`sms-note${r.notice.channel === 'sms' ? '' : ' sms-note--none'}`}>
                          <MessageSquare size={14} aria-hidden="true" />
                          {r.notice.channel === 'sms' ? `SMS simulato · ${r.notice.at}` : 'Nessun SMS: recapito email'}
                        </span>
                      )}
                      <label className="sr-only" htmlFor={`st-${r.id}`}>Cambia stato della richiesta di {r.name}</label>
                      <select id={`st-${r.id}`} className="select select--sm" value={r.status}
                        onChange={(e) => {
                          const status = e.target.value as RequestStatus;
                          if (status === 'confermata') {
                            // MOCKUP: nessun SMS parte davvero. In produzione il cambio di stato chiamerà il backend
                            // (es. PATCH /api/admin/booking-requests/{id}), che invierà l'SMS tramite un provider
                            // (es. Twilio, Vonage, Skebby), registrerà l'esito in un log notifiche e lo mostrerà qui.
                            const phone = isPhone(r.contact);
                            const at = new Intl.DateTimeFormat('it-IT', { hour: '2-digit', minute: '2-digit' }).format(new Date());
                            dispatch({ type: 'request/status', id: r.id, status, notice: { channel: phone ? 'sms' : 'nessuno', at } });
                            notify(phone
                              ? `SMS di conferma SIMULATO a ${r.contact}: nel prototipo non parte nessun messaggio.`
                              : `Richiesta confermata. Il recapito non è un numero: nessun SMS (simulazione).`);
                            return;
                          }
                          dispatch({ type: 'request/status', id: r.id, status });
                          notify(`Richiesta di ${r.name}: ${requestStatusLabel[status].toLowerCase()} (solo demo, nessun messaggio inviato).`);
                        }}>
                        {STATUSES.map((s) => <option key={s} value={s}>{requestStatusLabel[s]}</option>)}
                      </select>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="small muted note">SMS: in produzione serviranno un provider SMS, il consenso del cliente nell'informativa e un registro delle notifiche inviate. I dati personali delle richieste andranno cancellati automaticamente dopo un periodo definito (proposta: 90 giorni).</p>
    </>
  );
}
