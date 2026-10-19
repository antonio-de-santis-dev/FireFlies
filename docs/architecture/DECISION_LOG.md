# DECISION LOG — FireFlies Aperitif backend

> Aggiornato: 19/10/2026 · Fase: **3, autenticazione** · Report: [FASE3_REPORT](FASE3_REPORT.md) · FASE 2 approvata ([FASE2_REPORT](FASE2_REPORT.md)).
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
| C-15 | **FASE 1 approvata**; FASE 2 autorizzata | 19/10 |
| C-16 | Registro di occupazione `capacity_slots` a quanti da **15 minuti** (ex P-03, P-30) | 19/10 |
| C-17 | Capacità fisica **condivisa** tra eventi e prenotazioni ordinarie (ex P-06) | 19/10 |
| C-18 | Eventi **esclusivi per impostazione predefinita**; eventi non esclusivi abilitabili **solo esplicitamente dal titolare** (risolve P-31) | 19/10 |
| C-19 | Blocco predefinito per tavoli con posti insufficienti; eccezione motivata **riservata al titolare** (ex P-08) | 19/10 |
| C-20 | Gestione degli esiti incerti delle notifiche e nessun invio SMS reale confermati (ex P-34, P-37) | 19/10 |
| C-21 | **Strategia Git obbligatoria**: un branch per fase derivato dal precedente approvato, nessun merge automatico, `main` intoccabile senza autorizzazione, nessun force-push (MASTER_PLAN §18, sezione 1-bis) | 19/10 |
| C-22 | **FASE 2 approvata**; FASE 3 autorizzata (autenticazione e sicurezza dei gestori) | 19/10 |
| C-23 | Spring Security + sessioni server-side con **Spring Session JDBC** su PostgreSQL, cookie HttpOnly/Secure in HTTPS/SameSite, CSRF, hash robusto, ruoli OWNER/STAFF | 19/10 |
| C-24 | Il ruolo **non si sceglie al login**: lo assegna il backend dall'account | 19/10 |
| C-25 | Primo OWNER creato con una **procedura amministrativa**; nessuna credenziale fissa nel codice | 19/10 |
| C-26 | Area gestori non pubblicizzata con link, ma protetta dal backend | 19/10 |

## 1-bis. Strategia Git (C-21, regola definitiva)

| Branch | Deriva da | Stato |
|---|---|---|
| `main` | — | solo README iniziale; non si modifica senza autorizzazione |
| `fase-0/audit` | `main` | completato e approvato |
| `fase-1/progettazione` | `fase-0/audit` | completato e approvato |
| `fase-2/backend-foundation` | `fase-1/progettazione` | completato e approvato |
| `fase-3/autenticazione` | `fase-2/backend-foundation` | in revisione (questa fase) |
| `fase-4/menu-media` | `fase-3/autenticazione` | — |
| `fase-5/prenotazioni` | `fase-4/menu-media` | — |
| `fase-6/eventi-capienza`, `fase-7/notifiche`, `fase-8/hardening-deploy` | fase precedente | nomi proposti |

Regole: verificare stato e branch prima di iniziare; branch precedente completo e approvato; lavoro solo sul branch della fase; test eseguiti e documentati; commit ordinati; push previa verifica permessi; nessun merge automatico; `main` intoccabile; nessun force-push.

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

## 2-bis. Decisioni tecniche applicate in FASE 2

| ID | Decisione | Motivo / verifica |
|---|---|---|
| T-01 | **Spring Boot 4.1.1**, Java 21, Maven wrapper 3.9.11 | ultima versione su Maven Central; la linea 3.5 è fuori supporto OSS dal 30/06/2026, la 4.1 è supportata fino a metà 2027 (fonti in FASE2_REPORT §2) |
| T-02 | Versioni gestite dal BOM di Boot: Spring Framework 7.0.9, Flyway 12.4.0 (+ `flyway-database-postgresql`), driver PostgreSQL 42.7.13, HikariCP 7.0.2, Testcontainers 2.0.5, JUnit 6.0.3 | nessuna versione fissata a mano |
| T-03 | PostgreSQL **17** (immagine `postgres:17-alpine` fissata per digest, uguale in Compose e nei test) | Flyway 12.4 lo riconosce senza avvisi; migrazioni verificate su DB vuoto |
| T-04 | Accesso ai dati con **JDBC esplicito** (`JdbcClient`) in FASE 2; JPA/Hibernate si aggiunge solo quando servono le prime entità (FASE 3–4) | il registro di occupazione richiede SQL e lock espliciti; nessuna dipendenza inutile |
| T-05 | **Ordine globale dei lock**: prenotazione → evento → zona → quanti (zona, orario) → **righe dei tavoli in ordine di id** → assegnazioni | in FASE 2 i test hanno mostrato che due assegnazioni concorrenti dello stesso tavolo, senza lock della riga tavolo, possono andare in deadlock (il vincolo resta comunque inviolato) |
| T-06 | Rete di sicurezza: deadlock/timeout di lock → `409 CONCURRENT_UPDATE` («nulla è stato salvato, riprova») | nessun errore 500 per conflitti di concorrenza |
| T-07 | `type` dei problem+json come URN `urn:fireflies:problem:<codice>` | evita un dominio inventato; il dominio reale si decide in FASE 8 |
| T-08 | Tabelle Spring Session rinviate alla FASE 3 (insieme alla dipendenza) | lo schema va copiato dalla versione esatta della libreria |
| T-09 | Dati iniziali: zona «Locale» **spenta con capienza 0**, prenotazioni web **spente**, template notifiche **inattivi**, nessun utente, indirizzo confermato | nessun dato inventato attivo |
| T-10 | Nessun provider reale: `SmsGateway`/`EmailGateway` solo `Fake`; avvio bloccato se si configura altro (in `local`/`test` sempre, in `prod` finché non esiste l'adattatore approvato) | C-06, C-20 |
| T-11 | CI minima GitHub Actions (`./mvnw verify` su Ubuntu con Docker) sui branch `fase-*` | test di integrazione ripetibili fuori da questo container |

## 2-ter. Decisioni tecniche applicate in FASE 3

| ID | Decisione | Motivo / verifica |
|---|---|---|
| T-12 | Login JSON (`POST /api/v1/auth/login`) gestito da un servizio, non dal form login di Spring | risposte problem+json, limitazione tentativi e audit nello stesso punto |
| T-13 | Sessione in PostgreSQL (Spring Session JDBC 4.1.1), cookie `FF_SESSION`, timeout **8 h** di inattività, pulizia ogni 5 min; schema copiato dalla libreria nella migrazione **V8** | sopravvive ai riavvii, nessun Redis (P-15, P-27) |
| T-14 | Il «principal» in sessione è l'**id** dell'account: cambio di ruolo, disattivazione e reset password **chiudono tutte le sessioni** dell'account | il ruolo in sessione non può restare più ampio di quello sul DB |
| T-15 | CSRF «SPA» di Spring Security 7 (`csrf.spa()`): cookie `XSRF-TOKEN` leggibile + header `X-XSRF-TOKEN`; obbligatorio anche per login e logout; token e id di sessione rigenerati al login | anti login-CSRF e session fixation |
| T-16 | Password: bcrypt costo 12 con prefisso `{bcrypt}` (Argon2id possibile senza migrazione); minimo 12 caratteri, massimo 72 byte, non ripetitiva, senza il nome dell'email | P-16; bcrypt ignora i byte oltre il 72° |
| T-17 | Limite tentativi: 5 per email (anche inesistente) e 20 per IP in 15 min → `429` + `Retry-After`; blocco dell'account anche su DB (`locked_until`) | nessuna enumerazione degli account; il blocco resiste ai riavvii (P-21) |
| T-18 | `GET /auth/session` sempre `200` con `authenticated` e `reason: SESSION_EXPIRED` | il frontend sa se mostrare il login senza errori 401 in console |
| T-19 | Regole URL (`/api/v1/admin/users/**` OWNER, `/api/v1/admin/**` STAFF+, tutto il resto chiuso) **più** `@PreAuthorize` sui controller | difesa in profondità; percorsi sconosciuti → 401/403, non 404 |
| T-20 | Audit: accessi riusciti/falliti/bloccati, blocco account, logout, accesso negato, cambio/reset password, creazione e modifica account, primo titolare; mai password né email tentate | C-26, privacy |
| T-21 | Primo titolare: comando `java -jar … --fireflies.bootstrap.create-owner=true` con password chiesta nel terminale o da `FIREFLIES_BOOTSTRAP_PASSWORD`; rifiutata se passata come argomento; lock consultivo PostgreSQL contro esecuzioni parallele | C-25 |
| T-22 | Gestione account via API per il titolare (`/api/v1/admin/users`); **ultimo titolare attivo non rimovibile** | minimo indispensabile per assegnare i ruoli in modo sicuro; la schermata arriva più avanti |
| T-23 | Mockup copiato in `frontend/` senza modifiche (commit separato), poi solo: client API, login reale, verifica della sessione, logout reale, testi della pagina di accesso e della barra dell'area gestori | C-08, P-28 |
| T-24 | Test Playwright contro backend e PostgreSQL **reali** (`frontend/scripts/e2e.mjs`), credenziali casuali a ogni esecuzione; CI frontend su GitHub Actions | nessuna credenziale nei test |

## 3. Proposte — stato dopo l'approvazione della FASE 1

> Con l'approvazione della FASE 1 le proposte di questa sezione sono considerate **approvate come impostazione**. Restano da confermare solo i **valori numerici di esempio** (P-05 scadenza 60 min, P-13 anonimizzazione 90 giorni, P-19 massimo 12 persone dal web, P-21 limiti di frequenza, P-27 sessione 8 h) e i dati Q-01…Q-07, che non bloccano lo sviluppo. Le decisioni ancora aperte dopo la FASE 2 sono in FASE2_REPORT §6.

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
