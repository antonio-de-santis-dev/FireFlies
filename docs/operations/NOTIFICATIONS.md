# NOTIFICATIONS — email e SMS dall'outbox (FASE 7, requisito 11)

> Riferimenti: ADR-0005 (transactional outbox), BOOKING_RULES §9, DECISION_LOG T-41, T-67.
> **Nessun messaggio reale parte da questa versione**: esistono solo gateway finti.

## 1. Flusso
1. Un cambio di stato della prenotazione accoda le notifiche in `notification_outbox` **nella stessa transazione**
   (`NotificationOutbox.enqueue`), con chiave di business unica `RES:{id}:{modello}:{versione}:{canale}`: lo stesso
   fatto non produce due righe (dedupe).
2. Il worker (`NotificationWorker` → `NotificationDispatcher.runOnce`, ogni 30 s) a ogni giro:
   - riprende i lease scaduti (`SENDING` con `locked_until` passato) come `UNCERTAIN / LEASE_EXPIRED`;
   - salta i messaggi ormai inutili (`expires_at` = fine della prenotazione, oppure più vecchi di 30 giorni senza
     scadenza) come `SKIPPED / EXPIRED`;
   - prende in carico un lotto (20) di `PENDING` e `FAILED` scaduti con `UPDATE … FOR UPDATE SKIP LOCKED` e un
     lease di 2 minuti: due worker non prendono mai lo stesso messaggio;
   - per ogni messaggio: modello attivo? (`notification_templates.active`) → altrimenti `SKIPPED / TEMPLATE_INACTIVE`;
     sostituzione dei segnaposto `{{chiave}}` con il payload (un segnaposto senza valore diventa vuoto, mai mostrato
     grezzo; `telefono_locale` letto al momento dell'invio); tentativo registrato in `notification_attempts` con la
     chiave di idempotenza del provider, stabile tra i ritentativi.
3. L'esito lo scrive solo chi ha ancora il lease (`WHERE lease_token = ? AND state = 'SENDING'`).

## 2. Stati
| Stato | Significato | Cosa succede dopo |
|---|---|---|
| `PENDING` | accodato, non ancora preso in carico | il worker lo prende |
| `SENDING` | preso in carico, lease attivo | esito entro il lease, altrimenti `UNCERTAIN` |
| `SENT` | il provider l'ha accettato | fine; con il gateway finto la UI scrive «Simulata: nessun messaggio reale» |
| `DELIVERED` | conferma di consegna dal provider | non usato finché non c'è un provider reale (webhook) |
| `FAILED` | errore temporaneo | nuovo tentativo dopo 1′, 5′, 15′, 1 h, 4 h |
| `DEAD` | rifiuto definitivo o 5 tentativi falliti | solo nuovo invio manuale |
| `UNCERTAIN` | esito ignoto (timeout dopo l'invio, eccezione del gateway, lease scaduto) | **mai ritentato da solo**: decide un gestore |
| `SKIPPED` | non inviato, con motivo: `NO_RECIPIENT`, `SMS_DISABLED`, `TEMPLATE_INACTIVE`, `EXPIRED`, `SUPERSEDED` | fine |

`SUPERSEDED`: un fatto successivo rende inutile un messaggio non ancora partito (es. conferma seguita da annullamento),
anche se era in `FAILED`.

## 3. Nuovo invio manuale
`POST /api/v1/admin/notifications/{id}/resend` (STAFF e titolare, CSRF): solo da `FAILED`, `DEAD`, `UNCERTAIN`, al
massimo 3 volte (`409 RESEND_LIMIT`). Il messaggio torna `PENDING` con la **stessa chiave di idempotenza**: se il
primo invio era partito davvero, un provider che la rispetta non lo ripete. Audit `NOTIFICATION_RESENT`. Nel dettaglio
della prenotazione compare «Invia di nuovo».

## 4. Configurazione
| Proprietà | Default | Note |
|---|---|---|
| `fireflies.notifications.worker.enabled` (`FIREFLIES_NOTIFICATIONS_WORKER_ENABLED`) | `false`; `true` nel profilo `local` | **in `prod` l'avvio si rifiuta se acceso**: senza provider reale i clienti risulterebbero avvisati senza esserlo |
| `fireflies.notifications.worker.interval` | `PT30S` | |
| `fireflies.notifications.worker.batch-size` | `20` | |
| `fireflies.notifications.worker.max-attempts` | `5` | tetto del vincolo `ck_outbox_attempts` (10) |
| `fireflies.notifications.worker.lease` | `PT2M` | |
| `fireflies.notifications.email.provider`, `.sms.provider` | `fake` | qualunque altro valore blocca l'avvio (`NotificationGatewaysConfiguration`) |

I modelli dei messaggi (seed V7) sono **spenti**; nei dati demo locali vengono accesi, così in locale si vede il ciclo
completo con invii simulati. Nei test e2e il worker è spento (le notifiche restano «preparate, non inviate»).

## 5. Sospeso (richiede autorizzazione del titolare)
- **Provider email reale** e **provider SMS reale** (costi, credenziali, dominio del mittente, testi approvati).
  Credenziali solo da variabili d'ambiente o secret manager; nessuna chiave nel codice o in `.env.example`.
- Webhook di consegna (`DELIVERED`), elenco globale delle notifiche e «gestita a mano» (`mark-handled`), modifica dei
  modelli dall'area gestori: proposti nella FASE 1, non implementati.

## 6. Conservazione
Notifiche in stato finale cancellate dopo 90 giorni (`purge_after`, pulizia notturna); quelle di una prenotazione
cancellata dalla pulizia (dopo un mese) vanno con lei perché contengono telefono o email.

## 7. Test
`NotificationDispatcherIT` (6): modello spento/attivo, invio simulato e payload senza note, ritentativi fino a `DEAD`,
nuovo invio con audit e stessa chiave, `UNCERTAIN` mai ritentato, rifiuto, lease scaduto, scadenza, `SUPERSEDED`,
4 worker in parallelo su 60 messaggi senza doppioni; `OutboxClaimIT` (4) per i vincoli dello schema.
