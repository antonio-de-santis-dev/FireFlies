# Strategia di test — backend V1

> FASE 1 · rev. 2 del 19/10/2026 (revisione finale) · Stato: **proposta da approvare**. Nessun test backend esiste ancora: qui è descritto cosa verrà scritto in FASE 2 e nelle fasi successive.
> Regola: nei report solo test **realmente eseguiti**, con comando e risultato.
> Rev. 2: test di concorrenza sul registro di occupazione per intervalli e sulla capienza condivisa con gli eventi; test dell'outbox **corretti** (at-least-once, nessuna promessa di consegna unica); test dell'eccezione posti tavolo; garanzie «nessun SMS reale».

## 1. Livelli

| Livello | Strumenti | Cosa copre | Database |
|---|---|---|---|
| Unità | JUnit 5, AssertJ | state machine, quanti e allineamento, capienza effettiva di un quanto, Europe/Rome e DST, regole policy, validazioni, telefono, template, classificazione esiti provider, backoff | nessuno, `Clock` fisso |
| Web (slice) | `@WebMvcTest` + MockMvc | `problem+json`, validazione DTO, codici HTTP, permessi per ruolo, CSRF | nessuno |
| Integrazione | `@SpringBootTest` + **PostgreSQL 17 reale** (Testcontainers) | migrazioni Flyway, vincoli (`CHECK`, `EXCLUDE`, unique parziali), transazioni, lock, outbox, job | PostgreSQL 17, immagine a digest fissato |
| Concorrenza | integrazione + `ExecutorService`/`CountDownLatch` | gare reali tra transazioni (§3) | PostgreSQL reale |
| Contratto API | validatore OpenAPI sulle risposte | forma di risposte ed errori rispetto a `openapi.yaml` | PostgreSQL reale |
| Adattatore storage | Testcontainers MinIO | `MediaStorage` S3 | — |
| E2E frontend | Playwright (38 test esistenti) + nuovi test sulle API | flussi utente, regressioni del mockup | backend locale |

**Niente H2 né DB in memoria**: `tstzrange`, `EXCLUDE USING gist`, `FOR UPDATE SKIP LOCKED`, `NULLS NOT DISTINCT` esistono solo in PostgreSQL.

## 2. Ambiente PostgreSQL riproducibile

| Contesto | Come | Stato verificato il 19/10 |
|---|---|---|
| Sviluppo | `infra/compose.yaml`: `postgres:17-alpine` con **digest fissato**, volume, healthcheck `pg_isready`, credenziali da `.env` (`.env.example` senza segreti) | Docker avviabile nel container; PostgreSQL 17.11 + `btree_gist` provati (2 spike) |
| Integrazione | Testcontainers, un container condiviso per la suite (`@ServiceConnection`), Flyway all'avvio, pulizia per test (`TRUNCATE … CASCADE`) | Testcontainers 2.0.5 gestito da Boot 4.1.1 |
| CI | GitHub Actions `ubuntu-latest`, `./mvnw verify`, cache Maven | da creare in FASE 2 |
| Senza Docker | `FIREFLIES_TEST_DB_URL` verso un PostgreSQL 17 locale | ripiego |

Rischio: Docker Hub ha risposto una volta `429` (limite download anonimi). Mitigazione: digest fissato, cache in CI, registry alternativo da decidere (P-29).
Migrazioni: applicazione da zero, `flyway validate` in CI, checksum invariati per le migrazioni rilasciate.

## 3. Concorrenza — capienza, eventi, tavoli

Tecnica: N thread con transazioni **separate**, sbloccati insieme da un `CountDownLatch`; ogni test ripetuto 20 volte (`@RepeatedTest`); al termine sempre la **riconciliazione** (§3.4). Oltre agli esiti si conta che **non ci siano deadlock** (SQLState `40P01`).

### 3.1 Presenza contemporanea
| # | Scenario | Atteso |
|---|---|---|
| C-1 | Caso guida: capienza 10; A 19:00–21:00 ×6 confermata; B 20:00–22:00 ×6 | B → `409 CAPACITY_EXCEEDED`, `firstFullSlot = 20:00` |
| C-2 | A e B dello scenario C-1 confermate **in parallelo** | esattamente una riuscita |
| C-3 | 60 conferme parallele con arrivi, durate e persone casuali (seed fisso) | presenza massima ricalcolata ≤ capienza; 0 deadlock (già verificato nello spike 2: 5 riuscite, picco 10) |
| C-4 | Intervalli adiacenti (19:00–21:00 e 21:00–23:00) da 10 ciascuno su capienza 10 | entrambi riusciti |
| C-5 | Modifica di una confermata (spostamento +1 h) in parallelo con una conferma che occupa il nuovo orario | una sola riuscita; nessuno stato intermedio; 0 deadlock |
| C-6 | Uscita anticipata (`COMPLETED`) che libera posti mentre un'altra conferma li richiede | la conferma riesce solo se arriva dopo il rilascio; mai sopra capienza |
| C-7 | Cambio di capienza della fascia/zona (riduzione) in parallelo con una conferma | o la conferma fallisce o la riduzione dà `409 CAPACITY_BELOW_CONFIRMED`; **mai** quanti creati con la capienza vecchia dopo il commit della nuova (lock zona) |
| C-8 | Due conferme in zone diverse nello stesso orario | indipendenti, nessuna attesa reciproca |

### 3.2 Eventi e capienza condivisa
| # | Scenario | Atteso |
|---|---|---|
| C-9 | Pubblicazione di evento esclusivo vs conferma ordinaria sovrapposta, in parallelo | vince una sola; 0 quanti con evento esclusivo e coperti ordinari (spike 2: 20 gare) |
| C-10 | Due conferme parallele che superano insieme la **quota evento** | una sola; `confirmed_covers ≤ capacity_covers` dell'evento |
| C-11 | Evento **non esclusivo**: conferme parallele per evento e ordinarie nello stesso orario | somma ≤ capienza della zona; la quota evento non viene mai superata |
| C-12 | Evento su tutto il locale con 2 zone: conferma senza `zoneId` | `422 ZONE_REQUIRED` |
| C-13 | Evento limitato a un'area con una sola zona configurata | `422 ZONE_NOT_SEPARATE` |
| C-14 | Riduzione della quota evento sotto i confermati in parallelo con una conferma | mai confermati > quota |
| C-15 | Cambio di orario di un evento con prenotazioni confermate | `409 EVENT_HAS_CONFIRMED_RESERVATIONS`, nulla cambia |
| C-16 | Annullamento evento con `cancelReservations: true` mentre arriva una conferma | o la conferma fallisce o viene annullata nella stessa operazione; posti e quota tornano a zero |

### 3.3 Tavoli, richieste, scadenze
| # | Scenario | Atteso |
|---|---|---|
| C-17 | 2 assegnazioni parallele dello stesso tavolo con intervalli sovrapposti | una sola; l'altra `409 TABLE_CONFLICT` con rollback completo (anche dell'eventuale conferma nella stessa richiesta) |
| C-18 | Stessa `Idempotency-Key` inviata 10 volte in parallelo | 1 prenotazione, stessa risposta |
| C-19 | 10 invii paralleli stesso telefono + stesso orario, chiavi diverse | 1 prenotazione, gli altri `409 DUPLICATE_REQUEST` |
| C-20 | Doppio clic «Conferma» (stessa `version`) | 1 transizione, 1 storico, 1 notifica accodata; l'altra `200` senza effetti o `409 VERSION_CONFLICT` |
| C-21 | Job di scadenza e conferma nello stesso istante | uno solo vince; posti occupati solo se `CONFIRMED` |
| C-22 | Annullamento e cambio orario della stessa prenotazione in parallelo | uno `409 VERSION_CONFLICT`; registro e tavoli coerenti |

### 3.4 Riconciliazione (dopo ogni test di integrazione e concorrenza)
1. Per ogni quanto: `occupied_covers` = somma delle persone delle prenotazioni `CONFIRMED/COMPLETED/NO_SHOW` della zona con `starts_at ≤ quanto < occupied_until`.
2. Per ogni quanto: `occupied_covers ≤ capacity_covers`.
3. Nessun quanto con `exclusive_event_id` e persone di prenotazioni ordinarie.
4. Per ogni evento: `confirmed_covers` = somma delle persone delle sue prenotazioni che occupano posti.
5. Nessuna assegnazione tavolo attiva per prenotazioni non confermate; intervallo del tavolo = `[starts_at, occupied_until)`.

## 4. Tavoli: eccezione posti
| Caso | Atteso |
|---|---|
| 6 persone, tavoli per 4 in totale, STAFF | `422 SEATS_BELOW_PARTY_SIZE` |
| idem, STAFF con `overrideSeats: true` | `403 SEATS_OVERRIDE_FORBIDDEN`, nessuna modifica |
| idem, OWNER con `overrideSeats: true` senza motivo | `422 OVERRIDE_REASON_REQUIRED` |
| idem, OWNER con motivo | `200`; assegnazione con `seats_override = true`, autore e motivo; riga `audit_logs` `TABLE_SEATS_OVERRIDE` |
| eccezione posti con capienza della zona esaurita | `409 CAPACITY_EXCEEDED`: l'eccezione non tocca la capienza |
| 2 persone su tavolo da 8 (minimo 6) | `200` con avviso `MIN_SEATS_ABOVE_PARTY` |
| tavolo di un'altra zona | `422 TABLE_OUTSIDE_ZONE` |

## 5. Notifiche — semantica at-least-once (corretta)

**Cosa si verifica**: che ogni fatto produca **una** riga in outbox, che un messaggio sia **in carico a un solo worker alla volta**, che ogni tentativo sia **registrato**, e che gli esiti incerti **non** vengano ritentati alla cieca. **Non** si verifica (né si promette) che il cliente riceva il messaggio esattamente una volta: dipende dal provider.

Tutti i test usano `FakeSmsGateway`/`FakeEmailGateway` programmabili (risposta, errore, timeout prima o dopo «l'invio»), che registrano le chiamate ricevute.

| # | Scenario | Atteso |
|---|---|---|
| N-1 | Conferma ripetuta (doppio clic, ritentativo HTTP) | una sola riga outbox per `business_event_key` |
| N-2 | 4 worker in parallelo su 200 messaggi | ogni messaggio preso in carico una volta per lease (spike 2: 0 doppie prese); ogni messaggio chiamato dal fake **almeno** una volta |
| N-3 | Fake: errore transitorio (connessione rifiutata) 2 volte, poi successo | 3 tentativi registrati, intervalli di backoff rispettati (con `Clock` controllato), stato finale `SENT` |
| N-4 | Fake: errore transitorio sempre | dopo 5 tentativi `DEAD`; prenotazione **ancora** `CONFIRMED` |
| N-5 | Fake: rifiuto definitivo (numero non valido) | `FAILED` al primo tentativo, nessun ritentativo |
| N-6 | Fake: timeout **dopo** l'invio, provider configurato **senza** idempotenza | `UNCERTAIN`; nessun secondo invio automatico; il fake ha ricevuto 1 chiamata |
| N-7 | Come N-6 ma provider **con** idempotenza | ritentativo con **la stessa** `provider_idempotency_key`; il fake deduplica e conta 1 messaggio consegnato |
| N-8 | Worker interrotto dopo `started_at`, prima dell'esito (lease scaduto) | recupero → trattato come esito incerto (N-6/N-7) |
| N-9 | Worker interrotto **prima** di iniziare la chiamata | recupero → `PENDING`, invio regolare |
| N-10 | Worker lento con lease scaduto prova a registrare l'esito dopo che un altro worker ha ripreso il messaggio | 0 righe aggiornate (token vecchio); vale l'esito del nuovo worker (spike 2, verifica D) |
| N-11 | Prenotazione annullata mentre la conferma è ancora `PENDING` | conferma `SKIPPED (SUPERSEDED)`, annullamento accodato |
| N-12 | Messaggio oltre `expires_at` | `SKIPPED (EXPIRED)`, nessuna chiamata |
| N-13 | Reinvio manuale di `UNCERTAIN` senza `acknowledgeDuplicateRisk` | `422`; con il flag: nuova chiave, `manual_resend_count = 1`, riga di audit |
| N-14 | Provider offline durante una conferma | la conferma riesce; la notifica segue il backoff |
| N-15 | Payload e log | nessuna nota libera né dato personale nei log; payload e destinatario rimossi dalla pulizia dopo 30 giorni |

### Nessun SMS reale durante sviluppo e test
| # | Verifica |
|---|---|
| S-1 | Nel contesto di test il bean `SmsGateway` è `FakeSmsGateway` (test dedicato) |
| S-2 | Avvio con profilo `test` o `local` e `fireflies.notifications.sms.provider` ≠ `fake` → l'applicazione **non parte** (test di avvio) |
| S-3 | Con profilo `prod` ma `site_settings.sms_enabled = false` → messaggi SMS `SKIPPED (SMS_DISABLED)` |
| S-4 | La build non contiene credenziali (scansione dei segreti in CI); la CI non ha variabili di provider |
| S-5 | Stessa garanzia per l'email: `FakeEmailGateway` in `local`/`test` |

## 6. Unità e regole
- **State machine**: tutte le coppie (stato, comando) → ammessa / `INVALID_TRANSITION`; idempotenza.
- **Quanti**: allineamento (`19:10` → `422 NOT_ALIGNED_15_MIN`), elenco dei quanti di un intervallo, intervalli che passano la mezzanotte (eventi), capienza effettiva (fisica, fascia, eccezione, nessuna fascia).
- **Orari**: passo della fascia, primo/ultimo arrivo, anticipo minimo, giorni massimi, data passata, eccezioni.
- **DST**: 29/03/2026 02:30 → `422 INVALID_LOCAL_TIME`; 25/10/2026 02:30 → +02:00; una cena 01:00–03:00 il 25/10 occupa i quanti corretti in tempo reale.
- **Telefono**: normalizzazione E.164, numeri esteri, input non valido.
- **Classificazione esiti provider**: tabella codice/eccezione → `ACCEPTED` / `REJECTED_PERMANENT` / `TRANSIENT_ERROR` / `UNKNOWN`.

## 7. API, sicurezza e permessi
- Anonimo su `/admin/**` → `401`; STAFF su endpoint OWNER → `403`; scritture senza CSRF → `403`.
- UUID inesistente o non visibile → `404` senza dettagli (IDOR).
- Login: errore generico `401`; 6° tentativo → `429`; id di sessione rigenerato; logout invalida.
- Rate limit della prenotazione pubblica → `429` con `Retry-After`.
- Input ostili: lunghezze oltre i limiti, caratteri di controllo, campi sconosciuti → `400`, HTML nelle note salvato come testo.
- Upload: file non immagine → `422 MEDIA_INVALID`; EXIF/GPS rimossi.
- Risposte pubbliche: verifica automatica che non contengano dati di clienti.

## 8. Flussi non ideali
Cliente senza email (email `SKIPPED (NO_EMAIL)`), telefono malformato (`422`), provider offline (backoff, prenotazione invariata), riavvio del DB durante una conferma (rollback, nessun quanto occupato senza prenotazione confermata, client `503` e può ripetere con la stessa chiave), evento annullato, modifica di una fascia durante una conferma (lock della zona), SMS ripetuto (una riga outbox).

## 9. Frontend
I 38 test Playwright del mockup restano verdi a ogni fase. Nuovi E2E quando le API sono collegate (FASE 3+): login reale, richiesta dal sito, inserimento telefonico, conferma con stato del messaggio, conflitto di capienza mostrato con l'orario pieno, eccezione posti (solo titolare), caricamento foto. Breakpoint, tastiera, axe, reduced motion come oggi.

## 10. Comandi previsti (dalla FASE 2)
```bash
./mvnw test                      # unità + slice
./mvnw verify                    # + integrazione, concorrenza, riconciliazione (richiede Docker)
./mvnw verify -Pconcurrency      # solo suite di concorrenza, ripetuta
cd frontend && npm run verifica  # typecheck + Playwright + axe
```

## 11. Criteri per chiudere una fase
Tutti i test della fase verdi in CI; riconciliazione senza differenze; 0 deadlock nelle suite di concorrenza; nessun test saltato senza motivazione scritta; nessuna regressione del mockup; report con comandi e risultati reali.
