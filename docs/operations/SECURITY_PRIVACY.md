# SECURITY_PRIVACY — sicurezza e dati personali (stato alla FASE 7)

> Sintesi operativa. Decisioni in DECISION_LOG (ADR-0003, T-17, T-39, T-40, T-63, T-66, T-67); verifiche aperte
> V-01…V-04 in PRE_DEPLOY.md.

## 1. Accesso
- Sessioni server (Spring Session JDBC), cookie `HttpOnly`, `Secure` in prod, CSRF su **ogni** scrittura (anche la
  richiesta pubblica di prenotazione).
- Ruoli **OWNER** e **STAFF** decisi dal backend; doppio controllo: regola sull'URL e `@PreAuthorize`.
  - Solo titolare: utenti, contenuti e impostazioni del sito, configurazione prenotazioni (zone, fasce, regole,
    eccezioni, **capienza per giorno e fascia**, **chiusure e aperture straordinarie**), eventi non esclusivi,
    annullamento di eventi con prenotazioni.
  - STAFF e titolare: prenotazioni, eventi esclusivi, menu, foto, avvisi, calendario di oggi, riepilogo della capienza,
    nuovo invio di una notifica.
- Limiti: login per account e per IP, richieste dal sito per IP (memoria) e per telefono (database).

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

## 3. Notifiche
Gateway solo finti; il worker non può partire in `prod` finché non c'è un provider reale autorizzato (NOTIFICATIONS.md).
Nessun messaggio a clienti senza dati veri e autorizzazione del titolare. SMS solo dopo l'autorizzazione dei costi.

## 4. Segreti
Nessuna credenziale nel codice. Password, chiavi S3/R2 e future chiavi dei provider solo da variabili d'ambiente o
secret manager; `.env.example` senza valori. I test e2e generano credenziali casuali a ogni esecuzione.

## 5. Da completare prima del deploy (FASE 8)
Testo dell'informativa privacy validato; IP dietro proxy (V-01); backup cifrati con la stessa conservazione; verifica
dei log in produzione senza dati personali.
