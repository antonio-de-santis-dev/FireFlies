import { CircleAlert, MessageCircle } from 'lucide-react';
import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { whatsAppReason } from '../../api/reservations';
import { whatsAppStatus, type WhatsAppStatusInfo } from '../../api/whatsapp';

const MODE: Record<string, { label: string; badge: string; text: string }> = {
  DISABLED: { label: 'Disattivato', badge: 'badge--neutral', text: 'Nessun messaggio WhatsApp parte. Le conferme delle prenotazioni funzionano come sempre.' },
  FAKE: { label: 'Simulato (FAKE)', badge: 'badge--info', text: 'I messaggi vengono solo simulati e registrati: nessun messaggio reale arriva ai clienti.' },
  META_TEST: { label: 'Prova con Meta (META_TEST)', badge: 'badge--confirm', text: 'Invii reali tramite Meta solo verso i numeri di prova autorizzati; tutti gli altri numeri sono bloccati.' },
};

const COUNT_LABEL: [keyof WhatsAppStatusInfo['counts'], string][] = [
  ['PENDING', 'In attesa'], ['SENT', 'Inviati a Meta'], ['DELIVERED', 'Consegnati'], ['READ', 'Letti'],
  ['FAILED', 'Non riusciti'], ['UNCERTAIN', 'Esito incerto'], ['SKIPPED', 'Non inviati'],
];

const yesNo = (v: boolean) => (v ? 'configurato' : 'non configurato');

/**
 * Diagnostica WhatsApp del titolare, in sola lettura: modalità, cosa è configurato (solo sì/no), conteggi e ultimo
 * errore. Nessun token o numero è visibile o modificabile e nessun pulsante fa partire messaggi.
 */
export function WhatsAppStatus() {
  const [info, setInfo] = useState<WhatsAppStatusInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    whatsAppStatus().then(setInfo).catch((e) => setError(e instanceof ApiError ? e.message : 'Stato di WhatsApp non disponibile.'));
  }, []);
  const mode = info ? MODE[info.mode] ?? { label: info.mode, badge: 'badge--neutral', text: '' } : null;
  return (
    <section className="capacity-settings" id="whatsapp" aria-labelledby="wa-title">
      <header className="capacity-settings__head">
        <h2 id="wa-title">Conferme su WhatsApp</h2>
        <p className="muted">Quando confermi una prenotazione, il cliente che lo ha chiesto nel modulo riceve un solo messaggio di
          servizio su WhatsApp. La modalità si cambia solo dalla configurazione del server, non da qui.</p>
      </header>
      {error && <p className="error" role="alert"><CircleAlert size={16} aria-hidden="true" /> {error}</p>}
      {!info && !error && <p className="muted" role="status">Caricamento dello stato di WhatsApp…</p>}
      {info && mode && (
        <div className="panel wa-status">
          <div className="wa-status__mode">
            <MessageCircle size={20} aria-hidden="true" />
            <span>Modalità</span>
            <span className={`badge ${mode.badge}`} data-testid="wa-mode">{mode.label}</span>
          </div>
          <p className="hint">{mode.text}</p>
          <dl className="wa-status__grid">
            <div><dt>Credenziali Meta</dt><dd>token {yesNo(info.configured.accessToken)}, numero mittente {yesNo(info.configured.phoneNumberId)}</dd></div>
            <div><dt>Webhook di consegna</dt><dd>{info.webhookEnabled ? 'attivo' : 'non attivo'}</dd></div>
            <div><dt>Modello</dt><dd><code>{info.templateName}</code> ({info.templateLanguage}), API {info.graphApiVersion}</dd></div>
            <div><dt>Numeri di prova</dt><dd>{info.testRecipients}</dd></div>
          </dl>
          <ul className="wa-status__counts" aria-label="Messaggi WhatsApp per stato">
            {COUNT_LABEL.map(([k, label]) => (
              <li key={k}><strong>{info.counts[k] ?? 0}</strong> <span className="small muted">{label}</span></li>
            ))}
          </ul>
          {info.lastError && (
            <p className="small" data-testid="wa-last-error">
              Ultimo errore: <strong>{whatsAppReason(info.lastError.code)}</strong>{' '}
              <span className="muted">({new Date(info.lastError.at).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' })})</span>
            </p>
          )}
        </div>
      )}
    </section>
  );
}
