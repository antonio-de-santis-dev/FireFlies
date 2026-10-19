# DECISION LOG — FireFlies Aperitif backend

> Aggiornato: 19/10/2026 · Fase: **1, revisione finale** · Riepilogo della revisione: [FASE1_FINAL_REVIEW](FASE1_FINAL_REVIEW.md).
> Legenda: **C** = consolidata dal committente · **A** = approvata dal master plan · **P** = proposta, **attende la tua approvazione** · **Q** = dato da chiedere ai titolari · ~~barrato~~ = superata.
> Precedenza in caso di contrasto: decisioni consolidate > master plan > mockup > documenti precedenti.

## 1. Decisioni consolidate dal committente

| ID | Decisione | Data |
|---|---|---|
| C-01 | Java 21, Spring Boot, PostgreSQL, Flyway, **monolite modulare** | 19/10 |
| C-02 | **Tutte** le prenotazioni (ordinarie ed eventi) richiedono la conferma del gestore; conferma automatica solo come evoluzione futura, non ora | 19/10 |
| C-03 | Capienza configurabile dal gestore | 19/10 |
| C-04 | Tavoli assegnati **manualmente**, assegnazione facoltativa; **nessun algoritmo** automatico di assegnazione/combinazione/distribuzione in V1 | 19/10 |
| C-05 | Eventi con capienza configurabile, richieste in attesa e controlli contro i conflitti con le prenotazioni ordinarie | 19/10 |
| C-06 | Notifiche asincrone con transactional outbox; **nessun SMS reale** (né backend SMS reale) senza autorizzazione e configurazione del provider | 19/10 |
| C-07 | Cloudflare R2 candidato principale, tramite interfaccia compatibile S3 | 19/10 |
| C-08 | Frontend React esistente preservato e integrato progressivamente; **grafica approvata invariata** | 19/10 |
| C-09 | Audit FASE 0 concluso | 19/10 |
| C-10 | Approvata l'impostazione generale della FASE 1: monolite modulare, PostgreSQL, Flyway, sicurezza server-side, transactional outbox, gestione manuale delle prenotazioni | 19/10 |
| C-11 | **Telefono del cliente obbligatorio, email facoltativa** (ex P-10) | 19/10 |
| C-12 | Richieste telefoniche ed email **inseribili manualmente** dal gestore | 19/10 |
| C-13 | La capienza complessiva effettiva non deve mai superare quella fisica, né per sovrapposizione di orari né per somma di eventi e servizio ordinario; in assenza di capacità fisicamente separate e configurate la contemporaneità non sicura va bloccata | 19/10 |
| C-14 | Outbox con semantica **at-least-once**: nessuna promessa di consegna exactly-once senza supporto del provider; chiavi di idempotenza, retry con backoff, gestione degli esiti incerti, prevenzione duplicati, registrazione dei tentativi, nessun SMS reale in sviluppo e test | 19/10 |

## 2. Decisioni approvate dal master plan (ancora valide)

| ID | Decisione |
|---|---|
| A-04 | Spring Security con sessione server-side, cookie sicuri, CSRF, ruoli `OWNER`/`STAFF`; il link nascosto non è una protezione |
| A-08 | Stati `PENDING`, `CONFIRMED`, `REJECTED`, `CANCELLED`, `COMPLETED`, `NO_SHOW`; state machine esplicita e idempotente |
| A-09 | Anti-sovrapposizione tavoli atomica (`tstzrange` + `EXCLUDE USING gist`); controlli di capienza atomici in transazione |
| A-10 | Sorgenti `WEB`, `PHONE`, `EMAIL` |
| A-11 | Fuso `Europe/Rome`, intervalli `[inizio, fine)`, orari pubblici ≠ fasce prenotabili |
| A-12 | Niente Redis, Kafka, Kubernetes, MongoDB, microservizi, blob nel DB |
| A-14 | Una fase alla volta, branch dedicati, nessun merge/deploy senza autorizzazione |

## 3. Proposte — richiedono la tua approvazione finale

### 3.1 Capienza, eventi, tavoli (riviste dopo la revisione)

| ID | Proposta | Riferimento |
|---|---|---|
| **P-03** | Capienza per **presenza contemporanea**: zone di capienza fisicamente separate (predefinita una sola, «Locale») e **registro di occupazione a quanti da 15 minuti**; conferma con lock ordinati dei quanti + `CHECK (occupied ≤ capacity)`. Sostituisce ~~il conteggio nella sola fascia di arrivo~~ | ADR-0006, BOOKING_RULES §3 |
| **P-30** | Granularità **15 minuti** per orari e durate di prenotazioni ed eventi | BOOKING_RULES §3.6 |
| **P-33** | Vincoli operativi V1 dichiarati: durata fissa (allungabile dal gestore), posti liberati prima solo con uscita anticipata/no-show segnati, **clienti senza prenotazione non tracciati → margine** impostato dal titolare (limite della fascia < capienza fisica), una zona per prenotazione | BOOKING_RULES §3.6 |
| **P-35** | Cambi di capienza serializzati con le conferme tramite lock della riga zona (`FOR SHARE`/`FOR UPDATE`); riduzione sotto i confermati → `409`, **mai annullamenti automatici** | BOOKING_RULES §3.5 |
| **P-06** | Eventi sulla **stessa capienza fisica** (quota evento = limite aggiuntivo); evento **esclusivo** per default (blocca il servizio ordinario nei suoi orari, web e manuale); evento limitato a un'area solo su **zona fisicamente separata**; pubblicazione bloccata (non forzabile) se ci sono ordinarie confermate negli stessi orari. Sostituisce ~~contatore evento indipendente~~ (ex P-07) | BOOKING_RULES §7 |
| **P-31** | Consentire eventi **non esclusivi** contemporanei al servizio ordinario nella stessa zona: sicuri perché condividono il registro; i posti evento non sono garantiti. *Alternativa*: vietarli del tutto in V1 | BOOKING_RULES §7.2 |
| **P-32** | Cambio di orario/zona/esclusività di un evento con prenotazioni confermate **vietato** in V1 (`409`): prima si gestiscono le prenotazioni | BOOKING_RULES §7.3 |
| **P-08** | Tavoli: posti insufficienti → **blocco predefinito**; eccezione solo **OWNER**, esplicita, con motivo obbligatorio, salvata sull'assegnazione e in **audit**; l'eccezione non tocca mai la capienza della zona. Sostituisce ~~avviso non bloccante~~ | BOOKING_RULES §8 |

### 3.2 Notifiche (riviste dopo la revisione)

| ID | Proposta | Riferimento |
|---|---|---|
| **P-34** | Esiti incerti: **ritentativo automatico solo se il provider supporta chiavi di idempotenza** (stessa chiave); altrimenti stato `UNCERTAIN`, decisione manuale del gestore con avviso di possibile doppio invio | ADR-0005 |
| **P-36** | Backoff 1 m, 5 m, 30 m, 2 h, 6 h (±20 %), massimo 5 tentativi, poi `DEAD`; messaggi scaduti (`expires_at`) e superati (annullamento) → `SKIPPED` | ADR-0005 |
| **P-37** | Tre condizioni per un SMS reale: profilo `prod` + provider configurato con credenziali + interruttore OWNER `sms_enabled`; avvio bloccato se in `local`/`test` è configurato un provider reale | ADR-0005 |
| P-11 | Destinatari: email al locale per ogni richiesta web; email al cliente (se presente) per ricevuta, conferma, rifiuto/scadenza, annullamento, modifica; SMS al cliente solo dopo C-06 (fino ad allora `Fake`) | BOOKING_RULES §9 |

### 3.3 Regole di prenotazione (invariate dalla rev. 1)

| ID | Proposta | Riferimento |
|---|---|---|
| P-04 | Se i **confermati** hanno già esaurito i posti nell'intervallo richiesto, il sito risponde «completo, chiamaci» (controllo informativo; quello vincolante è alla conferma) | BOOKING_RULES §5 |
| P-05 | Richieste non gestite scadono N minuti prima dell'arrivo (esempio 60) → `REJECTED` motivo `EXPIRED` | BOOKING_RULES §6 |
| P-09 | In V1 il cliente non modifica né annulla da solo: contatta il locale | BOOKING_RULES §6 |
| P-12 | `COMPLETED`/`NO_SHOW` impostati a mano; correzione tra i due solo OWNER entro 48 h | BOOKING_RULES §2 |
| P-13 | Anonimizzazione dei dati cliente 90 giorni dopo la data | BOOKING_RULES §11 |
| P-19 | Massimo persone dal web configurabile (esempio 12) | BOOKING_RULES §4 |
| P-21 | Limiti: 5 richieste/ora per IP, 3/giorno per telefono; login bloccato 15 min dopo 5 errori | BOOKING_RULES §5 |
| P-24 | Orari pubblici con chiusura dopo mezzanotte; fasce prenotabili non oltre mezzanotte in V1 (gli eventi sì) | DATA_MODEL §3.2 |
| P-25 | Nessuna «forzatura» della capienza: per sforare il titolare alza la capienza di quella data (audit) | BOOKING_RULES §3.5 |
| P-26 | Il sito pubblico non mostra il numero di posti residui | API_CONTRACT §2 |
| P-27 | Sessione dei gestori scade dopo 8 h di inattività | API_CONTRACT §3 |

### 3.4 Stack e infrastruttura (invariate dalla rev. 1)

| ID | Proposta | Riferimento |
|---|---|---|
| P-01 | Spring Boot **4.1.x** (oggi 4.1.1); date di fine supporto da confermare su spring.io prima di fissarla | ADR-0001 |
| P-02 | PostgreSQL 17 in sviluppo e test; produzione ≥ 17 | ADR-0002 |
| P-14 | Frontend e API sulla stessa origine | SYSTEM_CONTEXT §1 |
| P-15 | Sessioni in PostgreSQL (Spring Session JDBC) | ADR-0003 |
| P-16 | bcrypt costo 12; reset STAFF fatto dall'OWNER | ADR-0003 |
| P-17 | Varianti foto JPEG ricodificate senza EXIF/GPS | ADR-0004 |
| P-18 | Spring Modulith solo nei test (facoltativo) | SYSTEM_CONTEXT §4 |
| P-23 | `openapi.yaml` scritto a mano e verificato nei test | API_CONTRACT |
| P-28 | Repository: `backend/`, `frontend/` (copia del mockup senza `node_modules`/`dist`/ZIP/video), `infra/`, `docs/` | SYSTEM_CONTEXT §4 |
| P-29 | Immagine PostgreSQL a digest fissato; in caso di limiti Docker Hub usare il mirror `public.ecr.aws/docker/library/postgres` | TEST_STRATEGY §2 |

### 3.5 Modifiche funzionali al mockup (da approvare con screenshot prima/dopo nella fase in cui servono, senza cambiare la grafica)

| ID | Modifica | Fase |
|---|---|---|
| P-20a | Passo «Dati»: telefono obbligatorio + email facoltativa (conseguenza di C-11) | 5 |
| P-20b | Slot del dialog letti dall'API; stato «completo» | 5 |
| P-20c | Area Richieste: stati `Rifiutata`/`Completata`/`Non presentato`, «Da contattare» come etichetta, motivo, stato del messaggio (incluso «esito incerto») | 5 |
| P-20d | Inserimento «Prenotazione da telefono/email» | 5 |
| P-20e | Pagine OWNER: Zone e capienze, Aree e tavoli, Fasce, Eccezioni; linea del tempo dei posti | 5 |
| P-20f | Editor eventi: quota numerica, ora di fine, zona, «esclusivo», elenco conflitti | 6 |
| P-20g | Login con email e password | 3 |
| P-20h | Prezzo «demo» solo nei dati demo locali | 4 |
| P-20i | Assegnazione tavoli con blocco posti ed eccezione «solo titolare» con motivo | 5 |

## 4. Verifiche richieste — esito

| Verifica | Esito |
|---|---|
| Versioni Spring Boot e dipendenze | Maven Central (19/10): Boot 4.1.1 gestisce Framework 7.0.9, Security 7.1.1, Session 4.1.1, Hibernate 7.4.5, Jackson 3.1.5, Flyway 12.4.0, Testcontainers 2.0.5, driver PostgreSQL 42.7.13; springdoc 3.1.1 costruito su Boot 4.1.0; Modulith 2.1.1 da verificare. Date di supporto da confermare su spring.io |
| Bozza SQL precedente | **non disponibile** (repo vuoto, assente in locale e tra i caricamenti, Google Drive 403). Schema progettato da zero |
| Annullamento, modifica, scadenza, orari | BOOKING_RULES §2, §3.4, §4, §6 |
| Duplicati, tavoli, capienza | vincoli DB + lock ordinati; **spike 1** (vincoli) e **spike 2** (intervalli, eventi esclusivi, outbox) su PostgreSQL 17 — [evidence](evidence/) |
| Ambiente PostgreSQL riproducibile | compose + Testcontainers a digest fissato (TEST_STRATEGY §2) |
| Separazione V1 / futuro | BOOKING_RULES §12, DATA_MODEL §5, `CHECK (confirmation_mode = 'MANUAL')` |
| Capienza su intervalli sovrapposti (revisione) | risolta con P-03; caso 19–21/20–22 rifiutato; 60 conferme casuali parallele mai oltre la capienza, 0 deadlock |
| Capacità condivisa eventi/ordinarie (revisione) | risolta con P-06; 20 gare evento esclusivo/ordinaria con un solo vincitore |
| Affidabilità notifiche (revisione) | ADR-0005 rev. 2; test N-1…N-15 e S-1…S-5 |
| Tavoli sottodimensionati (revisione) | P-08; test in TEST_STRATEGY §4 |

## 5. Dati da chiedere ai titolari (bloccano l'attivazione, non lo sviluppo)

| ID | Dato |
|---|---|
| Q-01 | Fasce prenotabili reali per giorno (arrivi, durata media) e limite di presenze per fascia |
| Q-02 | **Capienza fisica** del locale e se esistono spazi fisicamente separati (es. dehors) con capienza propria |
| Q-03 | Aree e tavoli (se vogliono usarli da subito) |
| Q-04 | Orari pubblici e chiusure (oggi 07–23, mercoledì chiuso: **non confermati**) |
| Q-05 | Email del locale per ricevere le richieste; telefono pubblico |
| Q-06 | Informativa privacy (da validare con un esperto) |
| Q-07 | Budget e provider per email e, separatamente, per SMS |

## 6. Storico

### Rev. 1 della FASE 1 — superata dalla revisione finale
~~Contatore di coperti per «fascia × giorno» con la prenotazione conteggiata solo nella fascia di arrivo~~ (P-03 rev. 1) · ~~contatore evento indipendente~~ (P-07) · ~~posti tavolo insufficienti come semplice avviso~~ (P-08 rev. 1) · ~~test outbox «ogni messaggio inviato una volta»~~ (TEST_STRATEGY C-12 rev. 1) · tabella `capacity_buckets` rimossa.

### FASE 0 — incompatibilità mockup ↔ backend (risolte in progettazione)
| # | Mockup | Risolto in |
|---|---|---|
| 1 | stati `nuova/da-contattare/confermata/annullata` | A-08 + `needs_callback` |
| 2 | «telefono o email» | C-11 |
| 3 | orario come stringa su fasce fisse | `service_windows` + `booking-options` |
| 4 | niente zone/aree/tavoli/capienza | DATA_MODEL §3.2–3.4 |
| 5 | posti evento come testo | quota `events.capacity_covers` |
| 6 | eventi senza ora di fine | `events.ends_at` |
| 7 | `price` + `demoPrice` | `price_cents` (P-20h) |
| 8 | allergeni come stringhe | `allergens` + `allergens_status` |
| 9 | foto `blob:` | upload firmato (ADR-0004) |
| 10 | login «scegli ruolo» | ADR-0003 (P-20g) |
| 11 | SMS simulato su `notice` | outbox (ADR-0005) |
| 12 | router `#/` e build single-file | build statica per le demo; URL puliti nel frontend reale (FASE 4) |
| 13 | niente inserimento telefonico | C-12, `POST /admin/reservations` |
| 14 | orari pubblici = prenotabili | separati (A-11) |

Documenti precedenti superati: `ARCHITETTURA_FUTURA.md` (API senza versione, notifiche WhatsApp/Telegram, `NotificationLog`).
