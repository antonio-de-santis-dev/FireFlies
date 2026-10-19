# ADR-0005 — Notifiche con transactional outbox su PostgreSQL

- Stato: **Accettata** (C-06)
- Data: 19/10/2026

## Decisione
- Il cambio di stato della prenotazione e la riga `notification_outbox` si scrivono nella **stessa transazione**.
- `business_event_key` UNIQUE (es. `reservation:{id}:CONFIRMED:CUSTOMER:EMAIL`) → mai due notifiche per lo stesso fatto.
- Worker nello stesso JVM, ogni 10 s: in una transazione breve prende fino a 20 righe pronte con `FOR UPDATE SKIP LOCKED`, le marca `SENDING` con `locked_until`, fa commit; **poi** chiama il provider fuori da ogni transazione; infine registra l'esito (`notification_attempts`) e lo stato.
- Ritentativi con backoff (1 m, 5 m, 30 m, 2 h, 6 h), poi `DEAD` (visibile in dashboard, rinviabile a mano).
- Garanzia *at-least-once* (come indicato dalla skill microservices-architecture): un worker interrotto dopo l'invio ma prima della registrazione può causare un secondo invio; si passa una chiave di idempotenza al provider quando la supporta.
- `EmailGateway` e `SmsGateway` con implementazioni `Fake` (registrano nel DB e nel log, non inviano nulla). Provider reali attivabili solo da configurazione + approvazione dei costi.

## Alternative scartate
- Invio diretto nella transazione: un provider lento bloccherebbe la conferma; un errore la annullerebbe.
- Coda esterna (Kafka/RabbitMQ): esclusa dal master plan, non necessaria per questi volumi.
- Event publication registry di Spring Modulith: valida, ma serve comunque una tabella con stato per canale da mostrare in dashboard; la tabella dedicata è più trasparente.

## Conseguenze
- Payload minimo e cancellato dopo 30 giorni; nessuna nota libera del cliente nei messaggi.
- Stato prenotazione e stato consegna restano separati.
