# DECISION LOG — FireFlies Aperitif backend

> Aggiornato: 20/10/2026 · Fase: **6, eventi e capienza** · FASI 2–5 approvate ([FASE5_REPORT](FASE5_REPORT.md)). Verifiche aperte prima del deploy: §4-bis e [PRE_DEPLOY](../operations/PRE_DEPLOY.md).
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
| C-27 | **FASE 5 approvata** (401 test, CI verde, ultimo commit `911c5be`); FASE 6 autorizzata: eventi reali, locandine dalla libreria della FASE 4, prenotazioni per eventi con conferma manuale, quota per evento, eventi esclusivi per impostazione predefinita | 20/10 |
| C-28 | Evento **non esclusivo** solo se il titolare lo abilita esplicitamente e solo se la condivisione è sicura (stesso registro dei posti, mai un secondo contatore) — conferma di C-18 | 20/10 |
| C-29 | Le quattro verifiche V-01…V-04 (§4-bis) restano aperte e sono requisiti pre-deploy; **nessun componente complesso** (Redis, Kafka) introdotto solo per risolverle | 20/10 |
| C-30 | **FASE 7 — completamento obbligatorio**: requisiti 1–14 oltre al 15 già fatto; nessun invio reale senza autorizzazione, credenziali e provider; conservazione: prenotazioni 1 mese, eventi 2 mesi, statistiche anonime conservate; nessuna migrazione distruttiva | 20/10 |
| C-31 | **FASE 7 approvata** (482 test, CI verde); **FASE 8**: hardening, performance, UX responsive, preparazione al deploy **senza pubblicare**, senza servizi a pagamento, notifiche reali spente; editor eventi senza ora di fine e senza coperti (valori ricavati nel backend) | 20/10 |

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

## 2-quater. Decisioni tecniche applicate in FASE 4

| ID | Decisione | Motivo / verifica |
|---|---|---|
| T-25 | Si resta su **JDBC esplicito** (`JdbcClient`), niente JPA (conferma di T-04) | menu e contenuti sono poche tabelle con sostituzioni in blocco e versioni ottimistiche: l'SQL resta leggibile, nessuna dipendenza in più |
| T-26 | **Upload attraverso il backend** (multipart, 5 MB) invece dell'URL firmato verso il bucket — **revisione di ADR-0004, da approvare** | il file viene verificato e ricodificato *prima* che un solo byte arrivi allo storage pubblico; niente CORS sul bucket, niente upload «pendenti» da ripulire; per un locale (pochi caricamenti al giorno, max 5 MB) il costo di banda è trascurabile. L'interfaccia `MediaStorage` resta quella di ADR-0004: tornare all'URL firmato non richiede altre modifiche |
| T-27 | Nessun originale conservato: solo varianti 480/960/1600 px (mai ingrandite), **JPEG progressivo** q=0,82 o **PNG a 256 colori** (taglio mediano + Floyd–Steinberg) per le immagini trasparenti; chiavi nuove a ogni sostituzione → cache `immutable` di 1 anno | P-17. Il PNG8 porta i titolari scontornati da ~500 KB a ~140 KB per variante; WebP/AVIF in uscita richiederebbero codec nativi (rimandati) |
| T-28 | Verifiche sull'upload: tipo dai primi byte (JPEG/PNG/WebP; HEIC/AVIF con messaggio dedicato), dimensioni lette dall'intestazione prima di decodificare (max 50 MP, 12.000 px per lato, min 200 px), orientamento EXIF applicato, metadati (EXIF/GPS) mai copiati, una ricodifica alla volta | difesa da file mascherati e «decompression bomb»; privacy delle foto scattate col telefono |
| T-29 | Errori dello storage: scrittura fallita → `503 SERVICE_UNAVAILABLE` con `Retry-After` e nessuna riga nel DB; cancellazione dei file **dopo il commit**, con le chiavi non cancellate in `media_orphans` e nuovi tentativi ogni ora (attesa crescente fino a 24 h) | il database resta la fonte di verità; un file orfano occupa spazio ma non è mai visibile |
| T-30 | Storage `filesystem` solo `local`/`test` (le varianti le serve il backend su `/media/**`); in `prod` solo `s3` (R2), credenziali solo da variabili `FIREFLIES_S3_*`; client HTTP del JDK, chunked encoding e checksum aggiuntivi disattivati per compatibilità R2 | ADR-0004; nessun account cloud creato in questa fase |
| T-31 | «Personalizza il sito» salva testi, sezioni, titolari, staff e testata del menu **in un'unica richiesta** (`PUT /admin/site-content`) con una sola versione; impostazioni e orari idem (`PUT /admin/site-settings`) | corrisponde al pulsante «Salva e pubblica» del mockup: niente pubblicazioni a metà, conflitto `409 VERSION_CONFLICT` chiaro |
| T-32 | Allergeni «dichiarati» solo con la verifica esplicita del gestore; chi e quando restano salvati; «nessun allergene» è una dichiarazione distinta da «da dichiarare» | Reg. UE 1169/2011 |
| T-33 | Dati demo: solo profilo `local`, solo su database vuoto, con prezzi in `demo_price_cents` (mai in `price_cents`) e allergeni «da dichiarare»; il database viene marcato `site_settings.demo_content`; con profilo `prod` l'avvio si ferma se il segnale è presente o se si chiede il caricamento | P-20h; dati demo mai in produzione per errore |
| T-34 | Letture pubbliche con **ETag** (`ShallowEtagHeaderFilter`) e `Cache-Control: public, max-age=60` (galleria 300 s); il frontend rivalida, quindi le modifiche dei gestori sono visibili subito | API_CONTRACT §1 |
| T-35 | Frontend: la build normale non contiene più i dati del mockup (verificato sul bundle); restano solo nella build statica (`--mode static`) | nessun contenuto demo servito da un build di produzione |
| T-36 | Sessione scaduta in una scheda aperta: ogni 401 di un'API dei gestori riporta all'accesso con un avviso; al ritorno sulla scheda (`visibilitychange`) la sessione viene ricontrollata | richiesta della FASE 4 |
| T-37 | **FASE 5** — Contatore unico dei posti: il registro a quanti della FASE 2 (`capacity_slots`). La capienza effettiva di un quanto la calcola `DiningSlotCapacityPolicy` = min(capienza fisica, limite della fascia o sua eccezione del giorno, limite di tutto il giorno); le richieste PENDING non occupano mai posti (solo informativo `pendingCovers`) | BOOKING_RULES §3; mandato FASE 5 «nessun secondo sistema di conteggio» |
| T-38 | Richiesta dal sito: controlli in quest'ordine — Idempotency-Key, campo trappola, campi, ripetizione dello stesso invio (stessa risposta `201`), limite per IP, privacy, richieste aperte, persone, giorno/arrivo/anticipo, limite per telefono, posti liberi (solo preventivo, senza lock). Ripetizione con la stessa chiave e dati diversi → `422 IDEMPOTENCY_KEY_REUSED`; due invii simultanei con la stessa chiave → uno salvato, l'altro `409 DUPLICATE_REQUEST` | BOOKING_RULES §5; differenza da API_CONTRACT rev. 1 (`200` sulla ripetizione) |
| T-39 | Limite per IP (5/ora) in memoria come quello dei login (T-17), configurabile con `fireflies.reservations.web-requests-per-hour` (solo l'e2e lo alza); limite per telefono (3/24 h) contato sul database, resiste ai riavvii | BOOKING_RULES §5 |
| T-40 | Anche `POST /public/reservations` passa dal controllo CSRF (il modulo chiede prima il token): nessuna scrittura senza token, nemmeno pubblica | ADR-0003 |
| T-41 | Notifiche: accodate nell'outbox nella stessa transazione del cambio di stato, chiave di business `RES:{id}:{modello}:{versione}:{canale}`; nessun worker di invio fino alla FASE 7 → email `PENDING` mostrate come «preparate, non inviate», SMS `SKIPPED/SMS_DISABLED` («non previsto»), avviso al locale `SKIPPED/NO_RECIPIENT` finché manca l'email pubblica; una conferma seguita da un annullamento marca i messaggi non partiti `SUPERSEDED`. Il campo `delivered` della UI è vero solo per `SENT` | ADR-0005; mandato FASE 5 «nessun invio reale» |
| T-42 | Scadenza delle richieste PENDING: job ogni 5 minuti (`fireflies.reservations.expiry-sweep`), `FOR UPDATE SKIP LOCKED`; una conferma contemporanea vince o perde intera (provato in `ReservationConcurrencyIT`) | BOOKING_RULES §6 |
| T-43 | Tavoli: lock delle righe in ordine di id prima dell'inserimento delle assegnazioni (T-05), vincolo di esclusione come ultima difesa; nessuna assegnazione automatica. Spostando una confermata nella stessa zona i tavoli seguono il nuovo orario (conflitto → nulla cambia); cambiando zona vengono tolti | BOOKING_RULES §8 |
| T-44 | Orari nelle risposte JSON nel formato ISO di Jackson (`"19:00:00"`), come per gli orari di apertura della FASE 4; il frontend mostra `HH:mm` | coerenza con le API esistenti |
| T-45 | Dati demo delle prenotazioni (solo profilo `local`): zona «Locale» da 40 posti, sala e dehors con 10 tavoli, le fasce del modulo approvato (Colazione, Pomeriggio, Aperitivo, Sera) nei giorni di apertura, richieste dal sito aperte. **Nessuna prenotazione finta** | T-33; Q-01…Q-03 restano da confermare con i titolari |
| T-46 | L'interruttore «Richieste tavolo attive» in «Orari e contatti» salva subito la regola `webBookingEnabled` sul server; fasce, posti e tavoli in una nuova pagina «Capienza e tavoli» (lettura per lo staff, modifica solo del titolare) costruita con i componenti esistenti | P-20e; grafica approvata invariata |
| T-47 | **FASE 6** — Esclusività di un evento = quanti marcati con `exclusive_event_id` nel registro della FASE 2, alla pubblicazione, con la stessa procedura a lock ordinati di una conferma (lock condiviso della zona, quanti creati, quanti bloccati in ordine di orario). Evento senza zona = tutte le zone attive. Nessuna tabella o contatore nuovi | BOOKING_RULES §7; C-13, C-17 |
| T-48 | Quota dell'evento = `events.confirmed_covers` ≤ `capacity_covers` (vincolo `ck_event_quota` come ultima difesa), controllata alla conferma **in aggiunta** al registro della zona, con la riga evento bloccata prima della zona. COMPLETED e NO_SHOW tengono la quota (l'evento è già iniziato), come conta la riconciliazione della FASE 2 | §7.1 |
| T-49 | Annullamento dell'evento con le sue prenotazioni: lock prima delle prenotazioni (in ordine di id), poi dell'evento, così nessuna conferma concorrente va in deadlock (verificato in `EventConcurrencyIT`) | ordine globale dei lock T-05 |
| T-50 | STAFF crea, modifica e pubblica eventi **esclusivi**; rendere un evento non esclusivo e annullare un evento con prenotazioni attive spettano al titolare (`403 EXCLUSIVITY_OWNER_ONLY`, `403 FORBIDDEN`) | C-18, C-28; BOOKING_RULES §10 |
| T-51 | Pubblicazione di un evento esclusivo: persone confermate nei suoi quanti o un altro evento esclusivo → `409 EVENT_CONFLICTS` non forzabile (elenco senza dati personali); richieste ordinarie in attesa → `409 EVENT_CONFLICTS` finché non si invia `acknowledgePending`; dopo, quelle richieste non sono più confermabili (`EVENT_BLOCKS_SLOT`). Evento non esclusivo: avviso `EVENT_QUOTA_NOT_GUARANTEED` se la quota supera i posti liberi | §7.3 |
| T-52 | Prenotazioni per evento: giorno e orario sono quelli dell'evento (`422 EVENT_TIME_FIXED` se si prova a cambiarli), persone modificabili con quota e registro; dal sito solo per eventi pubblicati «su richiesta», oltre l'anticipo minimo e con posti nella quota (`422 EVENT_FULL`, preventivo). Spostando un evento senza confermate le richieste in attesa seguono il nuovo orario | §7 |
| T-53 | Sito: eventi pubblicati e annullati (futuri o format), `booking: { mode, open, reason }` senza numeri di posti (P-26); un evento annullato resta visibile come tale | API_CONTRACT §2 |
| T-54 | Orari degli eventi a passi di 15 minuti, fine ≤ inizio = giorno dopo (es. 22:30–02:00), durata massima 12 ore, quota tra 1 e la capienza fisica | §4 |
| T-55 | Eventi demo (solo profilo `local`): «Domeniche del Fire» (format), «DJ set al Fire» pubblicato e prenotabile (sabato ad almeno 14 giorni, 22:30–02:00, dopo gli ultimi arrivi serali), «Aperitivo e vinili» bozza; l'etichetta «esempio» sul sito deriva dall'indirizzo `…-esempio` | T-45 |
| T-56 | **FASE 7 · req. 15** — Locandine degli eventi sempre intere: `object-fit: contain` (mai `cover`), altezza automatica, riquadri centrati su `--poster-bg` (nero del tema); scheda: tutta nello schermo su desktop e tablet, a tutta larghezza su smartphone; nessuno zoom che tagli i bordi. Le varianti del backend restano solo ridotte in scala (verificato da test) | richiesta del committente dopo il collaudo della FASE 6 |
| T-57 | **FASE 7 · req. 4** — Causa del difetto «Pubblica evento» (riprodotto con 5 scenari sul sistema reale): l'editor creava la bozza e poi la pubblicava con **due richieste**; se la seconda falliva (conflitto, data passata) restava una bozza orfana e l'editor rimaneva su «nuovo», così dopo un ricaricamento l'evento «spariva». In più l'elenco e la scheda pubblici avevano `Cache-Control: max-age=60`. Correzione: `visible` in `POST/PUT /admin/events` = pubblicazione **nella stessa transazione** (un errore annulla tutto), un solo «Salva» con lo switch «Visibile sul sito» (spento per un evento nuovo), letture pubbliche `no-cache` | riproduzione documentata in FASE7_REPORT §2 |
| T-58 | Un evento pubblicato torna bozza (switch spento) **solo senza prenotazioni attive** (`409 EVENT_HAS_ACTIVE_RESERVATIONS`), liberando la riserva esclusiva; modificarlo senza toccare lo switch lascia lo stato com'è. «Elimina evento» (con conferma) per bozze, pubblicati e annullati **senza prenotazioni**; con richieste o prenotazioni attive → annullarle prima; con solo storico chiuso → `409 EVENT_HAS_HISTORY` (lo elimina la pulizia notturna insieme allo storico) | C-28, BOOKING_RULES §7 |
| T-59 | **Req. 3** — Voce di menu nuova visibile per impostazione predefinita (lo switch si spegne prima di salvare); «Elimina definitivamente» con conferma esplicita, distinto da «Nascosta»; l'API di eliminazione esistente era già corretta (allergeni in cascata, foto lasciate in libreria) | — |
| T-60 | **Req. 5** — «Da richiamare» si spegne nel backend (`ReservationStore.updateStatus`) appena una richiesta esce da «In attesa» (confermata, rifiutata, annullata, scaduta, completata, non presentata); un gestore può riaccenderlo a mano dalla modifica | — |
| T-61 | **Req. 6/8** — Capienza «per giorno della settimana e fascia» = limite di persone **presenti nello stesso momento** nelle fasce prenotabili di quel giorno (`service_windows.max_covers`, già nel registro della FASE 2): nessun contatore nuovo, la conferma resta vincolante e concorrente sui quanti di 15 minuti. Fascia = nome della fascia prenotabile (Colazione, Pomeriggio, Aperitivo, Sera) oppure orario d'arrivo (<11, <17, <20, sera). Eccezioni per data = `booking_exceptions` esistenti; ridurre sotto i confermati → `409 CAPACITY_BELOW_CONFIRMED` con data e ora | ADR-0006, C-13, C-17 |
| T-62 | La pagina «Capienza e tavoli» e l'assegnazione dei tavoli **escono dall'interfaccia**; tabelle, API e vincoli dei tavoli restano nel database e nel backend (nessuna migrazione distruttiva, dati esistenti compatibili). La capienza si gestisce in Impostazioni › Capienza | richiesta del committente |
| T-63 | **Req. 9** — «Calendario prenotazioni oggi»: solo `CONFIRMED` con `reservation_date` = oggi (Europe/Rome), filtro per fascia, nome e persone ma **mai telefono, email o note**, risposta `no-store`, aggiornamento ogni minuto, al ritorno sulla scheda e dopo i cambi | — |
| T-64 | **Req. 10** — Giorno chiuso = data straordinaria chiusa, oppure giorno della settimana chiuso negli orari del sito, salvo **apertura straordinaria** (`opening_exceptions`, ora con API del titolare); vale anche la chiusura di tutto il giorno di `booking_exceptions`. Il sito disabilita quei giorni (`closedDates` in `booking-options`) e il backend rifiuta con `422 DAY_CLOSED`. Gli inserimenti dei gestori in un giorno chiuso restano possibili con l'avviso `DAY_CLOSED` (telefonate per feste private). Le fasce demo esistono in tutti i giorni: decide l'apertura | ADR-0006 |
| T-65 | Prima scelta del dialog: «Prenota un tavolo» (flusso normale) o «Organizza un evento / compleanno» → testo e `tel:` del numero pubblico del locale; senza numero un messaggio, mai un numero inventato. Non riguarda le serate in calendario (FASE 6), che aprono direttamente il loro modulo | — |
| T-66 | **Req. 5/12** — Pulizia notturna (`RetentionJob`, 02:00–05:00 Europe/Rome ogni ora): una sola esecuzione riuscita per giorno grazie alla riga `maintenance_runs (job, giorno)` presa con `INSERT … ON CONFLICT`; un tentativo fallito si riprende all'ora successiva, un RUNNING più vecchio di 2 ore si considera morto. Ordine: notifiche scadute → prenotazioni finite da ≥ 1 mese (qualunque stato, salvo notifiche ancora da inviare) con **statistiche anonime** in `reservation_stats` prima di cancellare storico, assegnazioni e notifiche della prenotazione → quanti del registro di quel passato → eventi finiti da ≥ 2 mesi senza prenotazioni → notifiche finali dopo 90 giorni → audit dopo 12 mesi. Lotti da 500 con `SKIP LOCKED`, transazioni brevi; mai prenotazioni future o in corso | V-04 |
| T-67 | **Req. 11** — Worker dell'outbox: presa in carico con lease (`SKIP LOCKED`), tentativi in `notification_attempts` con la stessa chiave di idempotenza, esiti ACCEPTED → SENT, errore temporaneo → FAILED con attese 1′/5′/15′/1h/4h e DEAD dopo 5, rifiuto → DEAD, esito ignoto o lease scaduto → UNCERTAIN (mai ritentato da solo), scadenza alla fine della prenotazione → SKIPPED/EXPIRED, modello spento → SKIPPED/TEMPLATE_INACTIVE, messaggi superati anche se FAILED. Nuovo invio manuale (max 3, audit `NOTIFICATION_RESENT`). **Gateway solo finti**: il worker è acceso nel profilo `local` e rifiuta di partire in `prod`; la UI scrive «Simulata: nessun messaggio reale» | ADR-0005; C-07, C-08 |
| T-68 | **Req. 2** — Menu dei gestori sul telefono: pulsante con la pagina attuale scritta, elenco a comparsa (Esc, tocco fuori, scelta di una voce lo chiudono; focus sulla voce attiva e poi di nuovo sul pulsante), voci alte 52 px. Ordine: Panoramica, Menu e prezzi, Calendario prenotazioni oggi, Eventi, Richieste, Foto, Comunicazioni, Personalizza il sito, Impostazioni | — |
| T-69 | **Req. 1/5/7** — Panoramica senza scorciatoie che ripetono il menu (resta solo il riepilogo con «confermate oggi»); Richieste come schede separate (riga larga su desktop, schede impilate sul telefono); Foto a due colonne sotto i 640 px, campi `min-width: 0` | grafica approvata invariata (stessi colori, font, componenti) |
| T-70 | Inserimenti dei gestori: la fascia di una prenotazione è quella in cui l'orario è un **arrivo** (21:00 → Sera), non la prima che lo copre per permanenza (l'Aperitivo delle 20:30) | coerenza delle fasce nel riepilogo |

## 2-quinquies. Decisioni tecniche applicate in FASE 8

| ID | Decisione | Riferimenti |
|---|---|---|
| T-71 | **Req. 1** — L'editor eventi non chiede più l'ora di fine: se manca, il backend la ricava. Evento esistente con orari → **mantiene la durata che aveva** (spostare l'inizio sposta anche la fine); evento nuovo → **4 ore** dall'inizio (`fireflies.events.default-duration`, PT4H, valori ammessi 15′–12h a multipli di 15′, controllati all'avvio). Data e ora di inizio restano obbligatorie per pubblicare. Le API accettano ancora `endTime` esplicito (compatibilità) | BOOKING_RULES §7.5 |
| T-72 | **Req. 1** — L'editor non chiede più i coperti: per gli eventi con richieste dal sito la quota è quella **già salvata** (eventi esistenti invariati), altrimenti la **capienza fisica del locale** (somma delle zone). Non è un secondo contatore: il registro della zona resta il limite (§7.1). Mai sotto le persone già confermate; capienza del locale non configurata → `422 VENUE_CAPACITY_MISSING` (nessun valore inventato) | BOOKING_RULES §7.5, C-13 |
| T-73 | **Req. 4** — «Organizza un evento / compleanno»: numero preso **solo** dalle impostazioni del sito, normalizzato (`+39` per i numeri italiani, 6–15 cifre, altrimenti nessun link); «Chiama ora» (`tel:`) e «Copia numero» con Clipboard API solo in contesto sicuro, conferma solo a copia riuscita, altrimenti campo selezionabile; nessun permesso chiesto. Senza numero: avviso e Instagram, mai `tel:` vuoto | T-65 |
| T-74 | **Req. 5 / V-01** — In `prod` l'IP del client lo decide il `RemoteIpValve` di Tomcat: fiducia in `X-Forwarded-For` solo da reti private/loopback (dove sta Caddy), ultimo indirizzo non fidato; un prefisso falsificato non aggira i limiti (`ProxyHeadersIT`). Una sola istanza del backend: i limiti in memoria restano corretti | PRE_DEPLOY V-01 |
| T-75 | **Req. 5** — Header aggiunti: HSTS (1 anno, sottodomini), `Permissions-Policy` (nessun sensore/pagamento), `Cross-Origin-Opener-Policy: same-origin`; la CSP la scrive Caddy sui file statici. Matrice dei permessi generata da **tutti** gli endpoint registrati (`ApiSecurityMatrixIT`) | — |
| T-76 | **Req. 6** — Area gestori e accesso caricati a parte (`React.lazy`), precaricati appena si apre `/gestione`: −27,6 % di JavaScript sul sito pubblico senza rallentare l'area gestori. Indice parziale `ix_outbox_due` (V11) per la presa in carico del worker | evidence/PERFORMANCE.md |
| T-77 | **Req. 7–8** — Produzione su un solo server con Docker Compose (Caddy + backend + PostgreSQL), foto su R2, backup `pg_dump -Fc` verificato e con impronte, ripristino solo su database vuoto; nessun servizio acquistato | DEPLOY.md |

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

## 4-bis. Verifiche aperte — requisiti prima del deploy (C-29, dettaglio in PRE_DEPLOY.md)

| ID | Verifica | Stato attuale | Cosa serve prima del deploy |
|---|---|---|---|
| V-01 | **IP del client dietro reverse proxy** e strategia di rate limiting in produzione | `prod` usa `forward-headers-strategy: framework`: l'IP viene da `X-Forwarded-For`, quindi è affidabile solo se il proxy davanti lo **sovrascrive** e il backend non è raggiungibile direttamente. Limiti per IP (login, richieste dal sito) in memoria, una sola istanza; limite per telefono e blocco account su PostgreSQL | scegliere il proxy (FASE 8), limitare la fiducia ai suoi indirizzi (`server.tomcat.remoteip.internal-proxies` o firewall), test con header falsificato; se servono più istanze, contatori in una tabella PostgreSQL (non Redis) |
| V-02 | **Più richieste in attesa che competono per gli stessi posti** nella dashboard | le richieste in attesa non occupano posti; il dettaglio mostra posti liberi minimi e «altre richieste in attesa nello stesso orario»; la conferma è vincolante e transazionale (vince chi conferma per primo, gli altri ricevono `409 CAPACITY_EXCEEDED`) | prova d'uso con i gestori; valutare un segnale «non ci sta più» direttamente nell'elenco e l'ordine di gestione (per arrivo o per orario) |
| V-03 | **Dati demo sostituiti** con capienza, tavoli, orari, contatti ed eventi reali | il caricamento demo esiste solo nel profilo `local` su database vuoto; `DemoDataGuard` blocca l'avvio in `prod` con un database marcato demo | database di produzione nuovo; inserimento dei dati reali dai titolari (Q-01…Q-05) e verifica a vista prima dell'apertura delle richieste dal sito |
| V-04 | **Privacy**: informativa, conservazione, protezione di telefono, email e note | testo dell'informativa «da redigere»; note mai nel payload delle notifiche né nell'audit; dati personali visibili solo ai gestori autenticati; **FASE 7: pulizia notturna** (prenotazioni dopo 1 mese con statistiche anonime, eventi dopo 2, notifiche finali dopo 90 giorni, audit dopo 12 mesi) | testo validato da un esperto; periodo di conservazione deciso (proposta: anonimizzazione 90 giorni dopo la data); job di anonimizzazione; backup cifrati con la stessa conservazione; log senza dati personali verificati |

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
