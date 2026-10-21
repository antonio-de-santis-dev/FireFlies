# SECURITY_PRIVACY — sicurezza e dati personali (stato a FIX-1)

> Sintesi operativa. Decisioni in DECISION_LOG (ADR-0003, T-17, T-39, T-40, T-63, T-66, T-67, T-74, T-75); verifiche
> aperte in PRE_DEPLOY.md; audit completo della FASE 8 in FASE8_REPORT §3.

## 1. Accesso
- Sessioni server (Spring Session JDBC), cookie `HttpOnly`, `Secure` in prod, CSRF su **ogni** scrittura (anche la
  richiesta pubblica di prenotazione).
- Ruoli **OWNER** e **STAFF** decisi dal backend; doppio controllo: regola sull'URL e `@PreAuthorize`.
  - Solo titolare: utenti, contenuti e impostazioni del sito, configurazione prenotazioni (zone, fasce, regole,
    eccezioni, **capienza del locale**, **chiusure e aperture straordinarie su giorni o intervalli**), eventi non esclusivi,
    annullamento di eventi con prenotazioni.
  - STAFF e titolare: prenotazioni, eventi esclusivi, menu, foto, avvisi, calendario di oggi, riepilogo della capienza,
    nuovo invio di una notifica.
- Limiti: login per account e per IP; modulo pubblico (FIX-1, T-80) per IP in memoria — letture della disponibilità
  120/min, tentativi di invio 30 in 10 minuti, richieste create 5/ora (FIX 2) contate solo se salvate davvero — e per telefono sul
  database (3 in 24 ore); `429` con `Retry-After`. I messaggi non contengono dati personali; l'audit della creazione
  registra solo origine, persone e riferimento.
- **Accessi negati** (FIX-1): ogni `403` di un gestore resta un `403` anche su percorsi lunghi (prima diventava `500`
  perché l'audit non si salvava); il percorso completo va nei metadati dell'audit, mai dati dei clienti.
- **IP del client in produzione** (FASE 8, V-01): solo dal `RemoteIpValve` con fiducia nelle reti private/loopback dove
  sta Caddy; un `X-Forwarded-For` inventato non cambia l'IP usato dai limiti. Il backend non ha porte pubbliche.
- **Matrice dei permessi automatica** (`ApiSecurityMatrixIT`): ogni endpoint registrato è sotto `/api/v1/public`,
  `/api/v1/auth` o `/api/v1/admin`; ogni API dei gestori senza sessione → 401; ogni `POST` senza CSRF → 403; le API del
  solo titolare con lo staff → 403. Un endpoint nuovo è controllato senza scrivere un test apposta.

## 2. Dati personali
| Dato | Dove | Chi lo vede | Conservazione |
|---|---|---|---|
| Nome, telefono, email, note del cliente | `reservations` | solo gestori autenticati | **cancellati con la prenotazione un mese dopo la data** (pulizia notturna, FASE 7) |
| Recapito nel messaggio | `notification_outbox.recipient` | nessuna UI lo mostra per intero | con la prenotazione, oppure 90 giorni dopo l'esito |
| Note del cliente | mai nel payload delle notifiche, mai nell'audit, mai nei log | — | — |
| Calendario di oggi | solo nome, persone, orario, fascia, evento | gestori | risposta `no-store` |
| Statistiche | `reservation_stats`: conteggi per giorno e fascia | gestori (riepilogo) | **senza dati personali**, conservate |
| Audit | azioni dei gestori, id e metadati senza dati dei clienti | titolare | 12 mesi |

Le API pubbliche non espongono mai numeri di posti né dati di altri clienti; gli errori di conflitto degli eventi
elencano solo riferimento, orario e persone.

## 2-bis. Intestazioni HTTP (FASE 8)
Backend (risposte dell'API): CSP `default-src 'none'`, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, HSTS 1 anno con sottodomini,
`Permissions-Policy` (niente fotocamera, microfono, posizione, pagamenti, USB), `Cross-Origin-Opener-Policy`.
Caddy (file statici): le stesse più la **CSP** (`script-src 'self'`, immagini solo dal sito e dall'origine delle foto,
iframe solo Google Maps, `frame-ancestors 'none'`). `/actuator/*` dall'esterno risponde 404 tranne `health`, senza dettagli.
Nessuna configurazione CORS: sito e API sono sulla stessa origine.

## 3. Notifiche
Gateway solo finti; il worker non può partire in `prod` finché non c'è un provider reale autorizzato (NOTIFICATIONS.md).
Nessun messaggio a clienti senza dati veri e autorizzazione del titolare. SMS solo dopo l'autorizzazione dei costi.

## 4. Segreti
Nessuna credenziale nel codice. Password, chiavi S3/R2 e future chiavi dei provider solo da variabili d'ambiente o
secret manager; `.env.example` e `infra/.env.prod.example` senza valori; `.gitignore` esclude ogni `.env.*` tranne gli
esempi; i `.dockerignore` tengono fuori dalle immagini `.env`, `target/`, `node_modules/` e i dati locali (controllato
dal workflow `images`). I test e2e e la prova di ripristino generano credenziali casuali a ogni esecuzione. La password
del primo titolare si inserisce da terminale, mai in un file (DEPLOY.md §7).

## 5. Backup
I dump contengono nomi e telefoni: permessi 600 (`umask 077`), conservazione 30 giorni come le prenotazioni, copia
fuori sede **solo cifrata** (DEPLOY.md §6).

## 6. Da completare prima del deploy
Testo dell'informativa privacy validato (V-04); cifratura e copia fuori sede dei backup sul server reale (V-09); verifica
dei log in produzione senza dati personali; controllo dell'IP del client con `curl` sul server reale (V-01).
