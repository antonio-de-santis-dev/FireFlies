# Strategia di test — backend V1

> FASE 1 · 19/10/2026 · Stato: **proposta da approvare**. Nessun test backend esiste ancora; qui è descritto cosa verrà scritto e come.
> Regola: nel report di ogni fase solo test **realmente eseguiti**, con comando e risultato.

## 1. Livelli

| Livello | Strumenti | Cosa copre | Database |
|---|---|---|---|
| Unità | JUnit 5, AssertJ | state machine, calcolo slot, conversione Europe/Rome e DST, regole policy, validazioni, normalizzazione telefono, template | nessuno, `Clock` fisso |
| Web (slice) | `@WebMvcTest` + MockMvc | mappatura errori `problem+json`, validazione DTO, codici HTTP, permessi per ruolo, CSRF | nessuno |
| Integrazione | `@SpringBootTest` + **PostgreSQL 17 reale** (Testcontainers) | repository, migrazioni Flyway, vincoli (`CHECK`, `EXCLUDE`, unique parziali), transazioni, outbox, job | PostgreSQL 17, immagine con versione fissata |
| Concorrenza | integrazione + `ExecutorService`/`CountDownLatch` | gare reali tra transazioni (sezione 3) | PostgreSQL reale |
| Contratto API | test che confrontano le risposte con `openapi.yaml` (validatore OpenAPI) | forma delle risposte ed errori | PostgreSQL reale |
| Adattatore storage | Testcontainers MinIO | `MediaStorage` S3: URL firmati, HEAD, cancellazione | — |
| E2E frontend | Playwright (già presente, 38 test) + nuovi test sulle API | flussi utente, regressioni visive del mockup | backend locale |

**Non si usa H2 né un DB in memoria**: `tstzrange`, `EXCLUDE USING gist`, `FOR UPDATE SKIP LOCKED`, `NULLS NOT DISTINCT` esistono solo in PostgreSQL (adattamento della ricetta `@DataJpaTest` della skill java-spring-review).

## 2. Ambiente PostgreSQL riproducibile

| Contesto | Come | Stato verificato il 19/10 |
|---|---|---|
| Sviluppo locale | `infra/compose.yaml`: servizio `postgres:17-alpine` con **digest fissato**, volume, healthcheck `pg_isready`, credenziali da `.env` (`.env.example` senza segreti) | Docker avviabile nel container; PostgreSQL 17.11 + `btree_gist` provati |
| Test di integrazione | Testcontainers `PostgreSQLContainer("postgres:17-alpine@sha256:…")`, un container condiviso per tutta la suite (`@ServiceConnection`), Flyway applicato all'avvio; ogni test pulisce le proprie tabelle (`TRUNCATE … CASCADE`) per non dipendere dall'ordine | Testcontainers 2.0.5 gestito da Spring Boot 4.1.1 |
| CI (GitHub Actions) | `ubuntu-latest` con Docker; `./mvnw verify`; cache Maven; immagine scaricata una volta | da creare in FASE 2 |
| Fallback senza Docker | variabile `FIREFLIES_TEST_DB_URL` che punta a un PostgreSQL 17 locale (binari presenti nel container) | binari PostgreSQL 16 presenti; 17 tramite Docker |

Rischio osservato: Docker Hub ha risposto una volta `429 Too Many Requests` (limite di download anonimi). Mitigazione: digest fissato, cache in CI, eventuale registry alternativo (`public.ecr.aws/docker/library/postgres`) — da decidere in FASE 2.

Test delle migrazioni: (1) applicazione da zero su DB vuoto; (2) `flyway validate` in CI; (3) una migrazione già rilasciata non si modifica mai (controllo del checksum).

## 3. Test di concorrenza (prioritari)

Ogni test avvia N thread con transazioni **separate** (`TransactionTemplate`), li sblocca insieme con un `CountDownLatch`, poi verifica gli invarianti con SQL diretto. Ognuno viene eseguito 20 volte (`@RepeatedTest`) per far emergere gare rare.

| # | Scenario | Atteso |
|---|---|---|
| C-1 | 2 gestori confermano in contemporanea due richieste che insieme superano l'ultimo posto della fascia | esattamente 1 `CONFIRMED`, l'altra resta `PENDING` con `409 CAPACITY_EXCEEDED`; `confirmed_covers ≤ capacity_covers` |
| C-2 | 20 conferme parallele da 2 persone su capienza 10 | esattamente 5 riuscite; contatore = 10 (già verificato nello spike SQL) |
| C-3 | Stesso scenario C-1 sul contatore di un **evento** | come C-1 |
| C-4 | 2 assegnazioni contemporanee dello stesso tavolo con intervalli sovrapposti | una sola riuscita, l'altra `409 TABLE_CONFLICT`; nessuna conferma lasciata a metà (rollback completo) |
| C-5 | Stessa `Idempotency-Key` inviata 10 volte in parallelo | 1 prenotazione; tutte le risposte con lo stesso `publicRef` |
| C-6 | 10 invii paralleli stesso telefono + stesso orario, chiavi diverse | 1 prenotazione, gli altri `409 DUPLICATE_REQUEST` |
| C-7 | Doppio clic «Conferma» (2 richieste stessa `version`) | 1 transizione, 1 riga di storico, **1 sola** notifica in outbox; l'altra `200` senza effetti o `409 VERSION_CONFLICT` |
| C-8 | Job di scadenza e conferma del gestore nello stesso istante | uno solo vince; stato finale coerente, coperti consumati solo se `CONFIRMED` |
| C-9 | Gestore abbassa la capienza mentre un altro conferma | o la conferma fallisce o il cambio capienza fallisce con `409 CAPACITY_BELOW_CONFIRMED`; mai `confirmed > capacity` |
| C-10 | Spostamento di data di una confermata (2 contatori) in parallelo con una conferma sul contatore di destinazione | nessun deadlock (ordine per `id`), capienza rispettata |
| C-11 | Pubblicazione evento bloccante mentre arriva una richiesta web nella stessa fascia | la richiesta viene rifiutata oppure compare nell'elenco conflitti; mai ignorata in silenzio |
| C-12 | 2 worker outbox in parallelo sugli stessi 50 messaggi | ogni messaggio inviato una volta (`FOR UPDATE SKIP LOCKED`); nessun messaggio perso |
| C-13 | Annullamento e cambio orario della stessa prenotazione in contemporanea | uno `409 VERSION_CONFLICT`; assegnazioni e contatori coerenti |

Dopo ogni test di concorrenza gira la **query di riconciliazione**: per ogni contatore `confirmed_covers = SUM(guest_count)` delle prenotazioni `CONFIRMED/COMPLETED/NO_SHOW` collegate, e nessuna assegnazione attiva per prenotazioni non confermate.

## 4. Unità e regole

- **State machine**: tutte le coppie (stato, comando) → transizione ammessa / `INVALID_TRANSITION` (tabella esaustiva 6 × 5); idempotenza della ripetizione.
- **Orari**: slot allineati al passo; primo/ultimo orario; anticipo minimo; giorni massimi; data passata; eccezioni `CLOSED` e `CAPACITY_OVERRIDE`; fasce di giorni diversi.
- **DST**: 29/03/2026 02:30 → `422 INVALID_LOCAL_TIME`; 25/10/2026 02:30 → offset +02:00; durata a cavallo del cambio d'ora calcolata in tempo reale.
- **Telefono**: `3331234567` → `+393331234567`; `+39 000 000 0001`; numeri esteri; input non valido → `422`.
- **Capienza**: rilascio non scende sotto zero; delta di modifica in più/in meno.
- **Notifiche**: chi riceve cosa (BOOKING_RULES §9); `SKIPPED` con motivo quando non c'è canale; testo SMS ≤ 320 caratteri.

## 5. API, sicurezza e permessi

- Anonimo su `/admin/**` → `401`; STAFF su endpoint OWNER → `403`; `POST/PUT/PATCH/DELETE` senza CSRF → `403`.
- UUID inesistente o di un'altra risorsa → `404` senza dettagli (IDOR).
- Login: credenziali errate → `401` generico (stesso messaggio per email inesistente); 6° tentativo → `429`; id di sessione cambia dopo il login; logout invalida la sessione.
- Rate limit della prenotazione pubblica → `429` con `Retry-After`.
- Input ostili: stringhe oltre il limite, caratteri di controllo, JSON con campi sconosciuti (`additionalProperties: false` → `400`), HTML nelle note (salvato come testo, mai interpretato).
- Upload: file non immagine rinominato `.jpg` → `422 MEDIA_INVALID`; > 5 MB → rifiutato già dall'URL firmato; EXIF/GPS rimossi nelle varianti.
- Risposte pubbliche: verifica automatica che non contengano campi `customer`, `phone`, `email`.

## 6. Flussi non ideali (richiesti dal master plan)

| Scenario | Atteso |
|---|---|
| Cliente senza email | conferma registrata, email `SKIPPED (NO_EMAIL)`, SMS `SKIPPED (SMS_DISABLED)` finché non autorizzato |
| Telefono malformato | `422` con messaggio sul campo |
| Provider email offline | notifica `FAILED` → ritentativi con backoff (1 m, 5 m, 30 m, 2 h, 6 h) → `DEAD`; prenotazione **resta** `CONFIRMED` |
| Worker interrotto durante l'invio | lease scaduto → messaggio ripreso (at-least-once); chiave di idempotenza verso il provider dove supportata |
| Riavvio del database durante una conferma | transazione annullata, nessun contatore incrementato senza prenotazione confermata; il client riceve `503` e può ripetere |
| Evento annullato con prenotazioni | comportamento esplicito secondo `cancelReservations` |
| Modifica fascia durante una prenotazione in corso | la richiesta usa la regola vigente al momento della transazione; nessuno stato intermedio |
| SMS ripetuto (doppia conferma) | una sola riga outbox grazie a `business_event_key` |

## 7. Frontend

- I 38 test Playwright esistenti del mockup devono **restare verdi** a ogni fase (regressione visiva e di accessibilità).
- Nuovi E2E quando le API sono collegate (FASE 3+): login reale, richiesta dal sito, inserimento telefonico, conferma con toast di notifica, conflitto di capienza mostrato in modo comprensibile, caricamento foto.
- Breakpoint 360/375/390/768/1366/1440/1920, tastiera, axe, `prefers-reduced-motion`, come già fatto.

## 8. Comandi previsti (dalla FASE 2)

```bash
./mvnw test                      # unità + slice
./mvnw verify                    # + integrazione e concorrenza (richiede Docker)
./mvnw verify -Pconcurrency      # solo suite di concorrenza, ripetuta
cd frontend && npm run verifica  # typecheck + Playwright + axe
```

## 9. Criteri per chiudere una fase

Tutti i test della fase verdi in CI; query di riconciliazione a zero differenze; nessun test saltato senza motivazione scritta; nessuna regressione nei test del mockup; report con comandi e risultati reali.
