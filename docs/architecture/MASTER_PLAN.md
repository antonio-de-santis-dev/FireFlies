# FIREFLIES APERITIF — MASTER PLAN PER CLAUDE CODE

> **Stato:** documento guida di progettazione e sviluppo incrementale, non autorizzazione a implementare tutto in una volta.  
> **Contesto:** FireFlies Aperitif, Via Giovanni XXIII 19, 73025 Martano (LE), Italia.  
> **Lingua:** italiano per UI, messaggi, documentazione e report.  
> **Fuso orario di esercizio:** `Europe/Rome`.  
> **Principio guida:** prima analizzare, poi proporre, ottenere approvazione, implementare un modulo alla volta e testare; preservare il mockup già approvato.

## 0 — MANDATO E MODALITÀ DI LAVORO OBBLIGATORIA

Agisci come **Software Architect, Senior Java/Spring Engineer, PostgreSQL Engineer, Frontend Engineer, Security Engineer, QA Engineer e Technical Lead**.

1. **Non iniziare a scrivere backend o a modificare migrazioni finché non hai completato la FASE 0 e ricevuto il via libera esplicito.**
2. Apri il progetto corrente, studia tutto il codice esistente, la documentazione e le immagini. Non ripartire da zero, non sostituire la UI con template generici e **non sovrascrivere modifiche realizzate nel frattempo**.
3. Leggi le skill effettivamente accessibili, non inventarne il contenuto. Registra nel report quali skill, sezioni e pratiche sono state applicate.
4. Per ogni fase: **analisi → proposta/decisioni → approvazione utente → implementazione → test → report → stop**. Non avanzare alla fase successiva senza consenso.
5. Lavora su branch dedicati, senza merge né deploy in produzione senza autorizzazione. Prima di creare branch, esamina lo stato Git; se la cartella non è sotto Git chiedi istruzioni, non fare init/push arbitrari. **Strategia dei branch obbligatoria: §18.**
6. Non creare credenziali reali, token, chiavi API o numeri di telefono fittizi spacciati per reali. Secrets solo via variabili d'ambiente/secret manager, `.env.example` senza segreti.
7. Implementazione semplice, modulare, osservabile e sostenibile: **evita microservizi prematuri, Kafka, Kubernetes, Redis, MongoDB e dipendenze non giustificate**.
8. Se trovi contraddizioni tra mockup, schema SQL precedente e questo documento, documentale e **dai priorità alle decisioni correnti**; non applicare in produzione uno schema preliminare senza revisionarlo.
9. Produci un resoconto chiaro con commit/file modificati, test realmente eseguiti e limiti. Se qualcosa non è accessibile o verificabile, dillo esplicitamente.

## 1 — MATERIALI E FONTI DA ANALIZZARE

- Archivio precedente `FireFlies-consegna.zip` o progetto estratto `FireFlies/`.
- `mockup-interattivo/`: React 18 + TypeScript + Vite, pagine pubbliche e area gestori, store in memoria, Playwright.
- `sito-html/`: esportazione statica, da aggiornare solo quando richiesto e con procedura controllata.
- `mockup-grafici/`, `demo/`, `documentazione/`.
- Documenti precedenti `PROGETTAZIONE_BACKEND.md` e `V1__initial_schema.sql`: **riferimenti preliminari**, contengono ancora ipotesi di auto-assegnazione (`AUTO_TABLE`), da correggere prima dell'utilizzo come migrazioni Flyway.
- Foto e loghi originali già ricevuti dal cliente; riutilizzare gli asset autentici. Colori principali: oro `#C5A069`, fucsia `#E41A70`.
- Skill/repository indicati: 
  - https://github.com/nextlevelbuilder/ui-ux-pro-max-skill
  - https://github.com/VoltAgent/awesome-design-md
  - https://github.com/Leonxlnx/taste-skill
  - https://github.com/pbakaus/impeccable
  - https://github.com/emilkowalski/skills
  - https://github.com/antonio-de-santis-dev/Skill-programmazione-
  - https://github.com/antonio-de-santis-dev/Formazione
  - https://playwright.dev/agent-cli/introduction
  - https://github.com/microsoft/playwright
- Instagram ufficiale: https://www.instagram.com/fireflies.aperitif/ . Dati del report Meta fornito dal committente sono materiali di riferimento, non verifica indipendente di follower, post o orari.

**Obiettivo del prodotto:** sito responsive con Home, Menu, Eventi, Galleria, Il locale, Contatti, richiesta tavoli; area gestori per contenuti, media, calendario, prenotazioni, impostazioni e notifiche.

## 2 — DECISIONI ARCHITETTURALI APPROVATE

| Area | Scelta | Motivazione |
|---|---|---|
| Frontend | Conservare React + TypeScript + Vite | Riutilizzo del prototipo e bassa complessità |
| Backend | Java 21 + Spring Boot (versione supportata da validare) | API e logiche robuste |
| Architettura | Monolite modulare, API REST versionate | Meno costi e manutenzione rispetto a microservizi |
| Database | PostgreSQL | Relazioni, vincoli, transazioni per prenotazioni |
| Schema/versionamento | Flyway | Migrazioni riproducibili e revisionabili |
| Accesso gestori | Spring Security + sessioni sicure, ruoli OWNER/STAFF | Protezione lato server |
| Immagini | Object storage S3-compatible, candidato Cloudflare R2 | Evitare blob nel DB, CDN e risparmio banda |
| Metadati immagini | PostgreSQL | Chiavi oggetto, dimensioni, formato, alt, associazioni |
| Email | Adapter/provider da scegliere (es. Resend) | Sostituibile, costo iniziale contenuto |
| SMS | Adapter/provider da scegliere, **solo previa autorizzazione costi** | Conferme al cliente, trasparenza costo |
| Notifiche | Transactional outbox su PostgreSQL + worker | Affidabilità senza coda esterna |
| Hosting | Decisione dopo analisi dei costi e requisiti di disponibilità | Evitare scelta prematura |
| Fuso orario | Europe/Rome | Conversione corretta orari appuntamenti |
| Backend caching | HTTP cache/ETag dove utile; CDN media | No Redis nella V1 senza necessità |

**Non mettere fotografie/video come byte nel database. Non usare MongoDB soltanto per le immagini.** MinIO resta una possibile implementazione self-hosted di storage S3-compatible, non necessario per la prima pubblicazione se R2 è adeguato.

## 3 — CORREZIONE CRITICA: PRENOTAZIONI V1

Decisione recente: **la capienza/assegnazione dei tavoli resta una decisione del gestore. L'assegnazione e l'ottimizzazione automatica vanno nella V2.**

Esiste una questione ancora aperta: in conversazione è stata ipotizzata l'auto-conferma nei giorni normali, ma l'ultima proposta prevede conferma manuale anche di queste prenotazioni. **Non presumere l'auto-conferma ordinaria**: in attesa di conferma adottare la scelta conservativa *richiesta in attesa → gestore assegna/approva*. Se il committente preferirà auto-conferma senza assegnazione del tavolo, progettare e validare una separata policy con limiti di capienza prima di realizzarla.

### Comportamento conservativo per V1 (soggetto ad approvazione esplicita)
- **Giorni normali:** cliente invia richiesta, gestore verifica disponibilità, sceglie un tavolo oppure gestisce la capienza, conferma/rifiuta. Non assegnare automaticamente un tavolo.
- **Eventi:** gestore stabilisce capacità massima per evento e/o area; il cliente invia richiesta manuale; il gestore approva o rifiuta entro i posti residui.
- **Telefono/email:** gestore può inserire una richiesta con `source=PHONE` o `EMAIL`, senza bisogno di integrazione con centralino/IMAP in V1.
- **Prenotazioni confermate:** impegnano tavolo e/o capacità come previsto dalla policy; richieste pendenti non consumano automaticamente capienza confermata, salvo regola futura di hold esplicitamente approvata.
- **Nessun doppio tavolo:** controllo atomico su assegnazioni attive per intervalli di occupazione sovrapposti.
- **Capienza eventi:** check atomico con lock della riga/evento o contatore dedicato, dentro una transazione PostgreSQL.
- **Modifiche a eventi e disponibilità:** non cancellare silenziosamente conferme esistenti; mostra conflitti e richiedi decisione manuale.
- **Data e ora:** validate sul backend; occupazione reale `[inizio, fine)`, non uguaglianza tra stringhe ora; orari locali Europe/Rome e gestione DST.
- **Stati:** `PENDING`, `CONFIRMED`, `REJECTED`, `CANCELLED`, `COMPLETED`, `NO_SHOW` (eventuale `WAITLISTED` solo se concordata).
- **Transizioni:** definire state machine esplicita e idempotente; client non può elevare una richiesta a confermata direttamente.
- **Privacy:** minimizzazione di dati, retention configurabile da concordare, informative GDPR, log senza dettagli sensibili.

**Domande bloccanti da presentare al committente nella FASE 0:**
1. Nei giorni normali vuoi conferma manuale di tutte le richieste (raccomandazione V1) oppure auto-conferma soggetta a una capienza impostata dal gestore, senza assegnazione tavolo?
2. Il gestore sceglie il tavolo specifico in dashboard oppure vuole in V1 gestire semplicemente un totale di coperti?
3. Quanti tavoli/aree, quanti posti e quali fasce/tempi medi? Se i valori non sono ancora noti, usare configurazioni disabilitate o dati demo chiaramente etichettati; non inventare dati reali.
4. Eventi: singola fascia, aree dedicate, possibilità di prenotare comunque il servizio ordinario in altre aree?
5. SMS: sempre alla conferma oppure se è presente un cellulare e il consenso alle comunicazioni operative? Email se disponibile? Preferenze da concordare.

## 4 — SCHEMA DATI: MODELLO LOGICO DA VALIDARE

### Identità e autorizzazioni
- `app_users(id, email unique case-insensitive, password_hash, display_name, active, created_at, updated_at)`
- `app_roles(code)` e `app_user_roles(user_id, role_code)`; oppure enum/authority se più semplice, purché coerente.
- `auth_sessions(id, user_id, token_hash, expires_at, revoked_at)` solo se si adottano sessioni persistenti; definire modello sessioni prima delle migrazioni.
- `audit_logs(id, actor_user_id, action, object_type, object_id, metadata_redacted, occurred_at)`.

### Spazi, tavoli, calendari
- `dining_areas(id, name, active, sort_order)`.
- `dining_tables(id, area_id, code, min_seats, max_seats, active, combinable)`; i posti sono **configurati dal gestore**, mai determinati da un algoritmo nascosto.
- `weekly_service_windows(id, area_id_nullable, iso_day, opens_at, closes_at, ...)`.
- `booking_exceptions(id, area_id_nullable, date/start/end, rule, reason)` per chiusure/override.
- `booking_policies(id, scope, effective_range, confirmation_mode, slot_duration, lead_time, max_party_size, ...)` solo campi effettivamente necessari; niente complessità ipotetica.
- Non mescolare orari di apertura pubblici e finestre prenotabili: i due concetti sono differenti.

### Prenotazioni
- `reservations(id, customer_name, customer_phone, customer_email_nullable, reservation_date, starts_at, ends_at, guest_count, status, source, event_id_nullable, area_id_nullable, notes_nullable, created_at, updated_at, idempotency_key_nullable, ...)`.
- `reservation_table_assignments(id, reservation_id, table_id, occupied_range, active, ...)` con indice e vincolo anti-overlap per assegnazioni confermate; in PostgreSQL valutare `tstzrange` + `EXCLUDE USING gist (table_id WITH =, occupied_range WITH &&)` con `btree_gist` e predicate parziale su assegnazioni attive. Verificare attentamente la semantica delle cancellazioni e conferme.
- `reservation_status_history(id, reservation_id, previous_status, new_status, changed_by, changed_at, note_nullable)`.
- Eventuale tabella `reservation_capacity_allocations` per prenotazioni su capienza-evento indipendenti dai tavoli.
- Vincoli per ospiti > 0, date/periodi validi, status e source ammessi; indici per `reservation_date`, `status`, `event_id`, `area_id`.
- Idempotenza delle POST (chiave richiesta) e delle transizioni con side effect per evitare prenotazioni/notifiche duplicate su retry.

### Eventi
- `events(id, title, description, start_at, end_at, location_area_id_nullable, status, booking_policy, capacity_max_nullable, ...)`.
- `event_capacity` o allocazioni/event counters atomici; evitare semplici `COUNT(*)` concorrenti senza lock e senza vincoli.
- Definire la relazione tra evento, capienza, area e prenotazioni ordinarie già confermate: impedire overflow o doppio uso dei medesimi posti.

### Menu
- `menu_categories(id, name, slug, sort_order, visible)`.
- `menu_items(id, category_id, name, description, price_cents, visible, sort_order, ...)` — prezzi come `numeric(10,2)` o centesimi interi, scelta uniforme.
- `allergens(id, name)`; `menu_item_allergens(menu_item_id, allergen_id)`.
- Informazioni su ingredienti e allergeni non devono essere inventate: i titolari devono poterle verificare.

### Media e sito
- `media_assets(id, storage_key, mime_type, bytes, width, height, alt_text, uploaded_by, status, created_at, ...)`.
- `media_variants(id, media_id, variant, storage_key, width, height, ...)` se implementate varianti.
- `media_usages(id, media_id, entity_type, entity_id, purpose, sort_order)` o tabelle di relazione specifiche con FK dove possibile.
- `hero_slides(id, page, media_id, sort_order, enabled, transition_seconds, ...)`; rotazione nella hero del Menu predefinita ogni 60 secondi.
- `site_settings`, `opening_hours`, `site_sections`/contenuti modificabili con autorizzazione OWNER; foto titolari fronte/retro dove richieste dal mockup.
- Per media in uso: policy di rimozione sicura; evitare link rotti e orfani.

### Notifiche
- `notification_outbox(id, business_event_key unique, reservation_id, channel, template_code, payload_minimized, state, attempts, next_attempt_at, created_at, ...)`.
- `notification_attempts(id, outbox_id, provider, provider_message_id, result, error_code, attempted_at)`.
- `notification_templates(code, channel, body_template, active, ...)`.
- Separare `booking_status` da `message_delivery_status`; un SMS fallito non annulla una prenotazione confermata.

### Aspetti SQL non negoziabili
- PostgreSQL versione supportata e documentata; migrazioni Flyway incrementali (`V1__...`, `V2__...`), senza modificare una migrazione già applicata.
- `created_at` e `updated_at` coerenti; UUID dove utile; FK e indici misurati.
- Usare transazioni ACID e un locking strategy testato sotto concorrenza.
- Considerare i limiti delle exclusion constraint: se lo stato confermato è nella tabella prenotazioni, un predicato sull'assegnazione non può riferirlo direttamente; predisporre una colonna assegnazione-attiva aggiornata transazionalmente o un modello adeguato.
- Tenere separata la bozza SQL precedente: **non eseguirla così com'è** perché incorpora automatismi della versione scartata.

## 5 — MODULI BACKEND E RESPONSABILITÀ

```text
backend/
  pom.xml / mvnw
  src/main/java/it/fireflies/
    FirefliesApplication.java
    common/            # errori, validazione, tracing, orologio
    security/          # login, sessioni, ruoli, CSRF/CORS
    reservation/       # workflow, verifica conflitti, assegnazioni
    dining/            # aree, tavoli, orari, eccezioni
    event/             # eventi e capienza
    menu/              # categorie, prodotti, allergeni
    media/             # asset, signed upload, lifecycle
    content/           # sito, home, hero, galleria, titolari
    notification/      # outbox, email, SMS, retry
    audit/             # eventi amministrativi
  src/main/resources/
    application.yml
    db/migration/
  src/test/
frontend/               # utilizzare l'attuale mockup come base
infra/                  # compose locale, configurazione deploy
```

Dentro ogni modulo usa suddivisioni `api`, `application`, `domain`, `persistence/infrastructure` dove giustificate; evita repository che attraversano direttamente i moduli senza API interne. Non introdurre astrazioni inutili.

## 6 — API REST V1 (BOZZA, DA DEFINIRE CON OPENAPI)

**Pubbliche**
- `GET /api/v1/public/site` — contatti, orari, sezioni pubbliche.
- `GET /api/v1/public/menu` — categorie e prodotti visibili.
- `GET /api/v1/public/events` e `GET /api/v1/public/events/{id}`.
- `GET /api/v1/public/booking-policy?date=...&time=...` — regola vigente, messaggio comprensibile (non promesse di disponibilità definitiva).
- `POST /api/v1/public/reservations` — valida, crea richiesta, idempotenza, anti-spam e rate limit.

**Gestori (richiesta sessione valida + autorizzazione)**
- `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`.
- `GET /api/v1/admin/reservations` con filtri/paginazione.
- `POST /api/v1/admin/reservations` per inserimento telefonico/email/manuale.
- `PATCH /api/v1/admin/reservations/{id}/status` con controllo versione/stato e side effects idempotenti.
- `PUT /api/v1/admin/reservations/{id}/tables` per assegnazione manuale validata.
- CRUD limitato e verificato per `/admin/areas`, `/admin/tables`, `/admin/booking-rules`, `/admin/events`, `/admin/menu`, `/admin/media`, `/admin/site-settings`.
- `POST /api/v1/admin/media/upload-intent` genera URL firmato breve per caricamento; `POST /api/v1/admin/media/{id}/finalize` valida presenza e proprietà; niente credenziali storage al browser.

**Regole API:** DTO separati dalle entity, OpenAPI, codici HTTP corretti, error model `problem+json`, limitazione dimensioni upload, input sanitation dove appropriata, paginazione, nessun dato privato nelle risposte pubbliche; validazione `@Valid` e log scrubbed.

## 7 — AUTENTICAZIONE E SICUREZZA

- La pagina gestori può non apparire nei link pubblici, **ma il link nascosto NON costituisce protezione**.
- In produzione ogni endpoint admin richiede controllo autenticazione e autorizzazione lato backend; `OWNER` modifica impostazioni sensibili, `STAFF` ha permessi limitati e descritti.
- Favorire sessione server-side con cookie `HttpOnly`, `Secure`, `SameSite` opportuno; policy CSRF coerente con l'architettura; CORS ristretto.
- Password hash robusto (Argon2id o bcrypt configurato correttamente), login rate limiting, password reset sicuro, logout/revoca, niente account di test nella produzione.
- Segreti dal runtime e non nel repository; HTTPS end-to-end.
- Threat model essenziale: brute force login, IDOR, CSRF, injection, upload malevoli, spam delle prenotazioni, enumeration, abuso invio SMS, accesso a PII.
- GDPR: informativa/privacy, basi giuridiche corrette per comunicazioni operative, retention/backup/delete; niente consenso marketing mischiato alle comunicazioni transazionali. Validare testo legale con esperto.
- Backup database e verifica periodica di restore; monitor health/readiness e alert; tracciamento errori evitando PII.

## 8 — MEDIA STORAGE / R2

- Object storage S3-compatible configurabile per provider; Cloudflare R2 candidato principale.
- Immagini e video non nel DB. Nel DB: solo metadati e chiavi storage, non URL firmati persistenti.
- Per asset pubblici: URL CDN stabili da base configurabile; per privati URL temporanei con scadenza.
- Upload autenticato: request intent → signed upload → verifica oggetto (MIME effettivo, size, dimensioni, eventuale scansione) → ottimizzazione (WebP/AVIF e thumbnail) → READY.
- Gestire upload incompleti/orfani con cleanup; cancellazioni differite e referenze; naming casuale, non filename fornito dall'utente.
- Lazy loading, `srcset`, dimensioni esplicite, caching aggressivo per asset con nome versionato.
- Budget/quote, lifecycle e monitoraggio storage prima del deploy.

## 9 — EMAIL, TELEFONO E SMS

- Separare **indirizzo email pubblico del locale**, **casella operativa per ricevere risposte** e **provider di invio transazionale**. Non assumere che una API per invio legga automaticamente la posta in arrivo.
- Numero pubblico per telefonate configurabile nelle impostazioni; prenotazioni telefoniche registrate manualmente dall'operatore in V1.
- Email notifiche al gestore su nuove richieste; conferme/cancellazioni al cliente attraverso canali autorizzati.
- SMS: in V1 implementare adattatore `SmsGateway` con `FakeSmsGateway` per dev/test e provider reale solo dopo scelta costi/registrazione mittente e autorizzazione del titolare.
- Usare transactional outbox: prenotazione + evento di notifica nella stessa transazione, worker che preleva in sicurezza (`FOR UPDATE SKIP LOCKED` se appropriato), retry con backoff, dead-letter logico, deduplicazione via chiave evento unica.
- Nessuna garanzia di consegna SMS soltanto perché provider ha accettato una richiesta; mostrare stato preciso (`QUEUED/SENT/FAILED/DELIVERED` se webhook supportato).
- Evitare invii doppi se il gestore seleziona due volte `CONFIRMED` o un job viene ripetuto.
- Template in italiano che includano data, ora, numero persone, stato, riferimento e modalità per contattare il locale; testo breve per SMS.

## 10 — PERFORMANCE E DISPONIBILITÀ

- Monolite JVM dimensionato per l'hosting reale; non fare promesse di RAM minima senza benchmark.
- PostgreSQL: Hikari pool proporzionato al piano DB; indici sulle query misurate; `EXPLAIN ANALYZE` su queries lente; paginazione e limiti.
- `GET` pubbliche cacheable dove sicuro, ETag o max-age breve; invalidazione al cambio menu/contenuti; le informazioni su prenotazioni NON vanno in cache pubblica.
- CDN media evita transito backend per ogni immagine; ottimizzazione build frontend, code splitting e controllo bundle.
- Notifiche su worker asincrono e retry; chiamate a provider esterni mai tenute dentro lock della transazione di conferma.
- Healthcheck, metriche, alert provider, backup/restore testati; configurare timeout, rate limiting e limiti file.
- Test prestazionali realistici: homepage, menu, lista dashboard e invio richieste; impostare SLO dopo misure, non inventarli.

## 11 — INFRASTRUTTURA E COSTI

Confronta almeno due opzioni con **costi aggiornati e fonti ufficiali** prima di scegliere:
A. Frontend statico/CDN + Spring Boot managed + PostgreSQL managed + R2 + email/SMS esterni.
B. VPS piccola con backend e PostgreSQL isolati in container + R2 + provider notifiche.

Per ogni opzione valuta: costo mensile effettivo, quote, storage/backups, email/SMS a consumo, uptime/sleep/free tiers, egress, RAM, gestione patch, ripristino incidenti, dati UE, dominio/TLS, effort operativo. Un piano gratuito che sospende il backend può essere inadatto per le prenotazioni.

Non decidere il provider senza consultare il committente. Usare Docker Compose per sviluppo locale (`backend`, `postgres`, eventuale storage S3 locale solo se utile), senza containerizzare inutilmente ogni modulo.

## 12 — STRATEGIA TEST E QUALITÀ

- Backend unit test: transizioni stati, regole policy, validazioni, capacity event, permessi, fallback notifiche.
- Integration test con PostgreSQL reale/Testcontainers: migrazioni Flyway, concorrenza prenotazioni, vincoli tavolo, atomicità e outbox.
- Concurrency test: due operatori confermano ultimo posto evento contemporaneamente; una conferma sola. Due assegnazioni contemporanee stesso tavolo/intervallo; una sola valida. Retry con stessa idempotency key; una sola prenotazione.
- API tests: errori 400/401/403/404/409/422/429 coerenti.
- Frontend component tests e Playwright end-to-end per menu, richiesta, prenotazione da telefono, login, conferma, toast di notifica, caricamento immagini.
- Responsive e accessibilità su 360/375/390/768/1366/1440/1920; tastiera, screen reader labels, focus, contrasto, reduced motion.
- Sicurezza: dipendenze aggiornate, analisi vulnerabilità, nessun secret esposto, tentativi accesso non autorizzato, upload ostili, input manipolati.
- Verificare anche flussi non ideali: niente email, telefono malformato, provider offline, DB riavviato, SMS ripetuto, evento cancellato, modifiche durante prenotazione.
- Non dichiarare tutto passato se i test non sono realmente eseguiti; inserire comandi e risultati nel report.

## 13 — FASI DI SVILUPPO CON CHECKPOINT OBBLIGATORI

### FASE 0 — AUDIT DEL PROGETTO (SOLO ANALISI, STOP)
- Verifica codice/mockup, cartelle, branch, lockfile, documentazione e asset.
- Valuta differenze tra mockup e requisiti backend; preserva i recenti fix del mockup.
- Leggi skill realmente disponibili e crea `docs/architecture/SKILL_AUDIT.md`.
- Produci `docs/architecture/DECISION_LOG.md` con approvati/aperti.
- Rivedi la bozza SQL precedente, segnala ciò che va corretto per la scelta **manuale V1 / automazione futura**.
- Poni **solo le questioni bloccanti** (modalità conferma nei giorni normali; tavoli vs coperti; capienza/eventi; canali notifica) e attendi risposta.
- **STOP: chiedi approvazione prima di procedere.**

### FASE 1 — PROGETTO DEL DOMINIO E CONTRATTI (STOP)
- Diagramma ER, invarianti, state machine prenotazioni, politiche di capienza e conflitto, tabella ruoli/permissi.
- OpenAPI per endpoint principali, esempi JSON, errori, autorizzazioni.
- ADR per database, storage, auth/sessioni, outbox, scelte di V1 vs V2.
- **STOP: approvazione.**

### FASE 2 — SCAFFOLD BACKEND E DATABASE (STOP)
- Java 21, Spring Boot, Maven wrapper, config profili local/test/prod, Docker Compose PostgreSQL.
- Flyway V1 revisionata da zero sulla base delle decisioni finali, migration test e seed **solo demo**.
- Readme avvio `./mvnw spring-boot:run` e avvio PostgreSQL; niente segreti hardcoded.
- Healthcheck, exception handling, test container, CI minima.
- **STOP: test e approvazione.**

### FASE 3 — AUTENTICAZIONE GESTORI (STOP)
- Login sicuro, OWNER/STAFF, sessioni protette, logout, controllo backend su endpoint admin; frontend login demo trasformato progressivamente.
- Test permessi, rate limit, accessi anonimi bloccati.
- **STOP: test e approvazione.**

### FASE 4 — MENU / CONTENUTI / MEDIA (STOP)
- CRUD menu e contenuti, R2 adapter (in locale fake o compatibile S3), signed upload, libreria foto, titolari e rotazione hero menu.
- Integrazione frontend preservando layout approvato; invalidazione cache.
- **STOP: test e approvazione.**

### FASE 5 — PRENOTAZIONI V1 (STOP)
- Richieste web, telefono, email manuale; dashboard gestione; assegnazione/capienza per gestore; conflitti robusti; status history; idempotenza.
- Attenzione: **nessun auto-assegnamento tavoli V1**. Conferma ordinaria solo nella modalità esplicitamente approvata.
- Test reali di concorrenza su PostgreSQL.
- **STOP: test e approvazione.**

### FASE 6 — EVENTI E CAPIENZA (STOP)
- Calendario eventi, aree, capacità massima, blocchi, approvazione manuale; incrocio con servizio ordinario.
- Test overflow e doppia conferma.
- **STOP: test e approvazione.**

### FASE 7 — EMAIL / SMS / OUTBOX (STOP)
- Email avvisi; provider SMS solo previa conferma dei costi; fake in sviluppo; tracking stati, retry e deduplica.
- Non inviare messaggi reali a clienti senza dati veri e autorizzazione.
- **STOP: test e approvazione.**

### FASE 8 — HARDENING, MONITORAGGIO, DEPLOY (STOP)
- Backup/restore, GDPR, error tracking, rate limits, accessibilità, performance, sicurezza, dominio e TLS.
- Comparazione hosting, budget e processo di rollback.
- Deploy staging prima, produzione solo dopo via libera esplicito.
- **STOP: collaudo finale.**

### FUTURO V2 — NON IMPLEMENTARE ORA
- Algoritmo di scelta tavoli in automatico.
- Ottimizzazione capienza, combinazione tavoli, tempi variabili, suggerimento alternative.
- Eventuale auto-conferma ordinaria con policy avanzata (solo se approvata).
- Automatizzazione lettura email/telefonate con integrazioni esterne.
- Lista d'attesa intelligente, CRM/fidelity e statistiche avanzate.

## 14 — REGOLE SULLE MODIFICHE AL FRONTEND GIÀ PROGETTATO

- Non modificare estetica approvata (palette, logo, struttura, animazioni) senza approvazione.
- Verifica che il mockup possa ancora essere in modifica da un altro agente: evita sovrascritture e merge automatici.
- Rispetta il pattern esistente `src/components`, `src/pages/public`, `src/pages/admin`, `src/store` e migra progressivamente da demo in-memory a servizi API.
- Prevedere layer `api/client`, `api/services`, DTO tipizzati, gestione loading/error/empty, autorizzazioni e invalidazione dati.
- Tutte le operazioni demo sono esplicitamente segnalate; non far credere che una prenotazione o un SMS sia reale.
- Prendere gli screenshot con Playwright del rendering effettivo e non inventare risultati visivi.

## 15 — CONSEGNE RICHIESTE PER OGNI FASE

In ogni fase consegna:
1. Stato iniziale + obiettivo e decisioni/assunzioni.
2. Piano degli interventi e lista file previsti.
3. Modifiche reali eseguite (solo dopo approvazione).
4. Test e loro esiti, con comandi riproducibili.
5. Report regressioni, rischi, questioni aperte.
6. Proposta della fase successiva, **senza avviarla da solo**.

File di documentazione progressivi:
```text
docs/
  architecture/
    MASTER_PLAN.md
    DECISION_LOG.md
    ADR/
    SYSTEM_CONTEXT.md
    DATA_MODEL.md
    BOOKING_RULES.md
    API_CONTRACT.md
    SECURITY_PRIVACY.md
    MEDIA_STORAGE.md
    NOTIFICATIONS.md
    HOSTING_COSTS.md
  testing/
    TEST_STRATEGY.md
    TEST_REPORT.md
  operations/
    LOCAL_SETUP.md
    DEPLOYMENT.md
    BACKUP_RESTORE.md
```

## 16 — PROMPT DI ESECUZIONE INIZIALE PER CLAUDE CODE

> Leggi integralmente `CLAUDE_CODE_MASTER_PLAN_FIREFLIES.md` e analizza il progetto FireFlies corrente, comprese documentazione, vecchia bozza SQL e skill accessibili. **Esegui esclusivamente la FASE 0. Non scrivere ancora backend né migrazioni, non modificare la UI, non eseguire push/merge/deploy.** Produci l'inventario del codice esistente, un resoconto delle scelte architetturali approvate, delle contraddizioni riscontrate e soltanto le domande realmente bloccanti. Poi fermati e attendi il mio via libera.

---

## 17 — CHECKLIST APPROVAZIONE FASE 0

- [ ] Stato del repository/branch verificato.
- [ ] Nessuna modifica recente sovrascritta.
- [ ] Skill realmente accessibili lette o indisponibilità dichiarata.
- [ ] Contraddizioni con lo SQL `AUTO_TABLE` evidenziate.
- [ ] Chiarito auto-conferma ordinaria sì/no.
- [ ] Chiarita modalità di gestione tavoli/coperti in V1.
- [ ] Definite regole minime per evento/capienza.
- [ ] Definita strategia notifiche demo vs reale.
- [ ] Approvati diagramma ER e piano API per fase successiva.

## 18 — STRATEGIA GIT OBBLIGATORIA (aggiunta il 19/10/2026, regola definitiva)

Repository: https://github.com/antonio-de-santis-dev/FireFlies. Il branch `main` è inizialmente vuoto (contiene solo il README iniziale).

**Ogni fase ha un branch dedicato e ogni nuovo branch deriva dall'ultimo branch completato e approvato:**

| Da | Nuovo branch | Fase |
|---|---|---|
| `main` | `fase-0/audit` | FASE 0 — Audit |
| `fase-0/audit` | `fase-1/progettazione` | FASE 1 — Dominio e contratti |
| `fase-1/progettazione` | `fase-2/backend-foundation` | FASE 2 — Scaffold backend e database |
| `fase-2/backend-foundation` | `fase-3/autenticazione` | FASE 3 — Autenticazione gestori |
| `fase-3/autenticazione` | `fase-4/menu-media` | FASE 4 — Menu, contenuti, media |
| `fase-4/menu-media` | `fase-5/prenotazioni` | FASE 5 — Prenotazioni V1 |
| `fase-5/prenotazioni` | `fase-6/eventi-capienza` | FASE 6 — Eventi e capienza *(nome proposto)* |
| `fase-6/eventi-capienza` | `fase-7/notifiche` | FASE 7 — Email, SMS, outbox *(nome proposto)* |
| `fase-7/notifiche` | `fase-8/hardening-deploy` | FASE 8 — Hardening, monitoraggio, deploy *(nome proposto)* |

**Regole obbligatorie**
1. Prima di iniziare una fase, verificare il branch attuale e lo stato del repository.
2. Verificare che il branch precedente sia completo e approvato.
3. Creare il nuovo branch partendo esattamente dal precedente.
4. Lavorare esclusivamente sul branch della fase corrente.
5. Eseguire i test disponibili e documentarne il risultato.
6. Fare commit ordinati e descrittivi.
7. Fare il push del branch su GitHub, previa verifica dei permessi disponibili.
8. Nessun merge automatico tra i branch.
9. Non modificare `main` senza autorizzazione.
10. Nessun force-push; non sovrascrivere modifiche esistenti.

A fine fase: commit e push del branch, poi **fermarsi** prima di iniziare la fase successiva e attendere l'approvazione.

---

**FINE — attendere approvazione del committente.**
