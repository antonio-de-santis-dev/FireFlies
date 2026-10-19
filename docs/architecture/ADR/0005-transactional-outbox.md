# ADR-0005 — Notifiche: transactional outbox at-least-once

- Stato: **Accettata** (C-06) · rev. 2 del 19/10/2026 dopo la revisione finale FASE 1
- Cambiato rispetto alla rev. 1: niente promesse di consegna unica; esiti incerti gestiti esplicitamente; lease con token; tentativi registrati prima della chiamata; tre blocchi contro SMS reali.

## Contesto
Le notifiche (email al locale, email/SMS al cliente) dipendono da provider esterni che possono essere lenti, rifiutare, o accettare un messaggio senza che noi riceviamo la risposta. Una conferma di prenotazione non deve dipendere dall'esito dell'invio.

## Garanzie (e cosa NON garantiamo)
| Garanzia | Meccanismo |
|---|---|
| Un fatto di business accoda **una sola** notifica per canale e destinatario | `business_event_key UNIQUE`, scritto nella stessa transazione del fatto |
| Un messaggio è in carico a **un solo worker alla volta** | `FOR UPDATE SKIP LOCKED` + `lease_token` + `locked_until` |
| Un worker lento non sovrascrive l'esito di chi ha ripreso il messaggio | ogni update finale ha `WHERE lease_token = :mio_token` |
| Ogni tentativo è tracciato, anche se il worker muore a metà | riga `notification_attempts` committata **prima** della chiamata al provider |
| **Consegna «exactly-once»: NON garantita** | è possibile solo se il provider deduplica con una chiave di idempotenza; altrimenti un esito incerto può produrre un doppio invio, quindi in quel caso **non** ritentiamo in automatico |

## Ciclo di vita di un messaggio
1. **Accodamento** (transazione del fatto): stato `PENDING`, `provider_idempotency_key = <id messaggio>`, `expires_at` (es. orario della prenotazione). Se il canale non è dovuto → `SKIPPED` con motivo (`NO_EMAIL`, `SMS_DISABLED`).
2. **Presa in carico** (transazione breve, ogni 10 s): fino a 20 righe `PENDING` con `next_attempt_at ≤ now()` → `SENDING`, nuovo `lease_token`, `locked_until = now() + 2 min`, `attempts + 1`, riga tentativo con `started_at`; commit.
3. **Invio** fuori da ogni transazione, timeout 10 s (< lease); la chiave di idempotenza viene passata al provider se la supporta.
4. **Esito** (transazione breve, condizionata al `lease_token`):

| Esito del provider | Classificazione | Stato successivo |
|---|---|---|
| accettato (2xx con id messaggio) | `ACCEPTED` | `SENT` (`DELIVERED` se in futuro arriva il webhook di consegna firmato) |
| rifiuto definitivo (numero/email non validi, 4xx non ripetibili) | `REJECTED_PERMANENT` | `FAILED` — nessun ritentativo |
| errore sicuramente prima dell'invio (DNS, connessione rifiutata) o 429/5xx che il provider documenta come non elaborati | `TRANSIENT_ERROR` | `PENDING` con backoff; dopo 5 tentativi `DEAD` |
| **esito incerto** (timeout dopo l'invio della richiesta, connessione interrotta durante la risposta) | `UNKNOWN` | provider **con** chiave di idempotenza → `PENDING` con backoff e **stessa chiave**; provider **senza** → **`UNCERTAIN`**, nessun ritentativo automatico |

5. **Recupero lease scaduti** (ogni minuto): `SENDING` con `locked_until < now()`. Se l'ultimo tentativo ha `finished_at` nullo il worker è morto durante la chiamata → trattato come `UNKNOWN` (regola sopra). Se non risulta alcuna chiamata iniziata → torna `PENDING` senza rischio.
6. **Scadenza**: un messaggio oltre `expires_at` non viene più inviato → `SKIPPED (EXPIRED)`.
7. **Messaggi superati**: annullando una prenotazione, le notifiche di conferma ancora `PENDING` diventano `SKIPPED (SUPERSEDED)` nella stessa transazione (quelle già `SENDING`/`SENT` restano: il cliente riceverà anche l'annullamento).

Backoff: 1 min, 5 min, 30 min, 2 h, 6 h con variazione casuale ±20 %; massimo 5 tentativi.

## Intervento manuale
`UNCERTAIN`, `FAILED` e `DEAD` compaiono in dashboard («Messaggio non confermato / non inviato»). Il gestore può:
- **Reinviare**: azione esplicita (audit `NOTIFICATION_MANUAL_RESEND`); la UI avverte che con esito incerto il cliente potrebbe riceverlo due volte; `manual_resend_count + 1`, nuova chiave di idempotenza, stato `PENDING`.
- **Segnare come gestito** (es. ha chiamato il cliente): `SKIPPED (HANDLED_MANUALLY)`.

## Nessun SMS reale in sviluppo e test
Tre condizioni **tutte** necessarie per usare un provider SMS reale:
1. profilo Spring `prod`;
2. configurazione `fireflies.notifications.sms.provider` diversa da `fake` **e** credenziali presenti nel secret manager;
3. interruttore `site_settings.sms_enabled = true` impostato dall'OWNER (con audit).

Inoltre: con profilo `local` o `test`, se è configurato un provider diverso da `fake` l'applicazione **non si avvia** (controllo all'avvio, coperto da test); la CI non ha credenziali di provider; l'adattatore reale verrà scritto solo in FASE 7 dopo la tua autorizzazione dei costi. Stessa regola per l'email: `FakeEmailGateway` in `local`/`test`.

## Alternative scartate
- Invio diretto nella transazione: un provider lento bloccherebbe la conferma, un errore la annullerebbe.
- Broker esterno (Kafka/RabbitMQ): escluso, non necessario.
- Ritentare sempre gli esiti incerti: con provider senza idempotenza produce SMS doppi (e costi doppi).
- Event publication registry di Spring Modulith: non distingue esiti incerti né mostra lo stato per canale.

## Conseguenze
- Il provider da scegliere (FASE 7) va valutato anche su: supporto a chiavi di idempotenza, webhook di consegna firmati, codici d'errore documentati.
- Tabelle: `notification_outbox` (con `lease_token`, `provider_idempotency_key`, `expires_at`) e `notification_attempts` (con `started_at`/`finished_at`/`outcome`). Nessuna tabella in più.
