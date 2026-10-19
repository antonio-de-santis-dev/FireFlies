# DECISION LOG — FireFlies Aperitif backend

> Aggiornato: 19/10/2026 · Fase: **1 (progettazione)** · FASE 0 chiusa dal committente.
> Legenda: **C** = consolidata dal committente · **A** = approvata dal master plan · **P** = proposta, **attende la tua approvazione** · **Q** = dato da chiedere ai titolari.
> Precedenza in caso di contrasto: decisioni consolidate > master plan > mockup > documenti precedenti.

## 1. Decisioni consolidate dal committente (19/10/2026)

| ID | Decisione |
|---|---|
| C-01 | Java 21, Spring Boot, PostgreSQL, Flyway, **monolite modulare** |
| C-02 | Prenotazioni ordinarie **e** di eventi confermate **manualmente** dal gestore; conferma automatica = possibile evoluzione futura, da **non** implementare ora |
| C-03 | Capienza configurabile dal gestore; assegnazione di un tavolo specifico **manuale e facoltativa** |
| C-04 | Algoritmi di assegnazione automatica e combinazione dei tavoli **esclusi dalla V1** |
| C-05 | Eventi con capienza configurabile, richieste in attesa e controlli contro i conflitti con le prenotazioni ordinarie |
| C-06 | Notifiche asincrone con transactional outbox; **nessun SMS reale** senza autorizzazione e configurazione del provider |
| C-07 | Cloudflare R2 candidato principale per le immagini, tramite interfaccia compatibile S3 |
| C-08 | Il frontend React esistente si preserva e si integra progressivamente, **senza modifiche grafiche non richieste** |
| C-09 | Audit della FASE 0 concluso |

## 2. Decisioni approvate dal master plan (ancora valide)

| ID | Decisione |
|---|---|
| A-04 | Spring Security con sessione server-side, cookie sicuri, CSRF, ruoli `OWNER`/`STAFF`; il link nascosto non è una protezione |
| A-08 | Stati `PENDING`, `CONFIRMED`, `REJECTED`, `CANCELLED`, `COMPLETED`, `NO_SHOW`; state machine esplicita e idempotente |
| A-09 | Anti-sovrapposizione tavoli atomica (`tstzrange` + `EXCLUDE USING gist`); capienza con controllo atomico in transazione |
| A-10 | Sorgenti `WEB`, `PHONE`, `EMAIL` (inserimento manuale, niente integrazioni centralino/IMAP) |
| A-11 | Fuso `Europe/Rome`, intervalli `[inizio, fine)`, orari pubblici ≠ fasce prenotabili |
| A-12 | Niente Redis, Kafka, Kubernetes, MongoDB, microservizi, blob nel DB |
| A-14 | Una fase alla volta, branch dedicati, nessun merge/deploy senza autorizzazione |

## 3. Proposte della FASE 1 — richiedono la tua approvazione

### 3.1 Stack e infrastruttura

| ID | Proposta | Motivo | Riferimento |
|---|---|---|---|
| P-01 | **Spring Boot 4.1.x** (oggi 4.1.1), non 3.5.x | linea corrente; 3.5 è l'ultima 3.x. Date di fine supporto da confermare su spring.io prima di fissare la versione | ADR-0001 |
| P-02 | PostgreSQL **17** in sviluppo e test; produzione ≥ 17 secondo il provider | versione verificata nel container con `btree_gist` | ADR-0002 |
| P-14 | Frontend e API sulla **stessa origine**; come servire i file statici si decide con l'hosting (FASE 8) | cookie sicuri senza CORS | SYSTEM_CONTEXT §1 |
| P-15 | Sessioni salvate in PostgreSQL (Spring Session JDBC) | sopravvivono ai riavvii, niente Redis | ADR-0003 |
| P-16 | Password con bcrypt costo 12; reset STAFF fatto dall'OWNER; reset via email solo con provider attivo | semplice e sicuro | ADR-0003 |
| P-17 | Foto: varianti 480/960/1600 in **JPEG** ricodificato senza EXIF/GPS (lettura WebP con TwelveMonkeys); WebP/AVIF in uscita più avanti | ImageIO non scrive WebP senza librerie native | ADR-0004 |
| P-18 | Spring Modulith **solo nei test** per verificare i confini tra moduli (facoltativo) | evita dipendenze cicliche nel tempo | SYSTEM_CONTEXT §4 |
| P-23 | Contratto OpenAPI scritto a mano (`openapi.yaml`) e verificato nei test; springdoc solo se vuoi la pagina interattiva | il contratto guida il codice, non il contrario | API_CONTRACT |
| P-28 | Struttura repository: `backend/`, `frontend/` (copia del mockup senza `node_modules`/`dist`/ZIP/video), `infra/`, `docs/` | ex D-14/D-18 | SYSTEM_CONTEXT §4 |

### 3.2 Regole di prenotazione

| ID | Proposta | Alternativa | Riferimento |
|---|---|---|---|
| P-03 | Capienza in **coperti per fascia prenotabile e per giorno** (es. «Aperitivo del venerdì: 40»); ogni prenotazione conta nella fascia d'arrivo | capienza per intervalli sovrapposti minuto per minuto | ADR-0006, BOOKING_RULES §3 |
| P-04 | Se i **confermati** hanno già esaurito la fascia, il sito risponde «completo, chiamaci» invece di accettare richieste destinate al rifiuto | accettare comunque richieste in attesa | BOOKING_RULES §5 |
| P-05 | Richieste non gestite **scadono** N minuti prima dell'arrivo (esempio 60) → `REJECTED` motivo `EXPIRED`, con avviso al cliente | aggiungere lo stato `EXPIRED` | BOOKING_RULES §6 |
| P-06 | Un evento prenotabile **blocca per default** le richieste ordinarie web sovrapposte (tutto il locale o la sua area); il gestore può comunque inserirle a mano con avviso | servizio ordinario sempre aperto | BOOKING_RULES §7 |
| P-07 | Capienza dell'evento **separata** dalle fasce ordinarie (contatore proprio) | capienza evento come quota delle fasce | BOOKING_RULES §7 |
| P-08 | Tavoli assegnabili solo a prenotazioni **confermate**; persone > posti del tavolo = **avviso**, non blocco | blocco rigido | BOOKING_RULES §8 |
| P-09 | In V1 il **cliente non modifica né annulla da solo**: contatta il locale, il gestore registra | link di annullamento monouso via email (V1.1) | BOOKING_RULES §6 |
| P-10 | **Telefono obbligatorio**, email facoltativa (cambia il passo «Dati» del mockup) | «telefono o email» come oggi | BOOKING_RULES §5 |
| P-11 | Notifiche: email al locale per ogni richiesta web; email al cliente (se presente) per ricevuta, conferma, rifiuto, annullamento, modifica; SMS solo `Fake` fino ad autorizzazione | SMS reale alla conferma | BOOKING_RULES §9 |
| P-12 | `COMPLETED`/`NO_SHOW` impostati **a mano** dal gestore; correzione tra i due solo OWNER entro 48 h | chiusura automatica a fine giornata | BOOKING_RULES §2 |
| P-13 | Anonimizzazione automatica dei dati cliente **90 giorni** dopo la data della prenotazione | altra durata | BOOKING_RULES §11 |
| P-19 | Massimo persone dal web configurabile (esempio 12; oltre: «chiamaci») | — | BOOKING_RULES §1 |
| P-21 | Limiti: 5 richieste/ora per IP, 3/giorno per telefono; login bloccato 15 min dopo 5 errori | valori diversi | BOOKING_RULES §5 |
| P-25 | Nessuna «forzatura» della capienza: per sforare il gestore alza la capienza di quella data (resta nell'audit) | pulsante «conferma comunque» | BOOKING_RULES §3 |
| P-26 | Il sito pubblico **non mostra** il numero di posti residui, solo «puoi chiedere» / «completo» | mostrare «ultimi posti» | API_CONTRACT §2 |
| P-27 | Sessione dei gestori scade dopo 8 ore di inattività | valore diverso | API_CONTRACT §3 |
| P-24 | Orari pubblici con chiusura dopo mezzanotte (flag «chiude il giorno dopo»); fasce prenotabili **non** oltre mezzanotte in V1 | — | DATA_MODEL §3.7 |

### 3.3 Modifiche al mockup che serviranno (solo funzionali, da approvare con screenshot prima/dopo nella fase in cui servono)

| ID | Modifica | Fase |
|---|---|---|
| P-20a | Passo «Dati» della prenotazione: telefono obbligatorio + email facoltativa (se approvi P-10) | 5 |
| P-20b | Fasce e slot del dialog letti dall'API invece che fissi; stato «completo» per slot | 5 |
| P-20c | Area Richieste: stati `Rifiutata`, `Completata`, `Non presentato`; «Da contattare» diventa un'etichetta; motivo per rifiuto/annullamento; stato del messaggio | 5 |
| P-20d | Nuovo inserimento «Prenotazione da telefono/email» | 5 |
| P-20e | Nuove pagine OWNER: Aree e tavoli, Fasce e capienze, Eccezioni di calendario | 5 |
| P-20f | Editor eventi: capienza numerica, ora di fine, «blocca prenotazioni ordinarie», elenco conflitti | 6 |
| P-20g | Login con email e password reali al posto della scelta ruolo | 3 |
| P-20h | Prezzi: sparisce il prezzo «demo» nei dati reali (resta nei dati demo locali) | 4 |

Le nuove schermate useranno solo componenti e stili già presenti (C-08).

## 4. Verifiche richieste dal committente — esito

| Verifica | Esito |
|---|---|
| Versioni Spring Boot e dipendenze compatibili | Maven Central (19/10/2026): Boot **4.1.1** gestisce Spring Framework 7.0.9, Security 7.1.1, Session 4.1.1, Hibernate 7.4.5, Jackson 3.1.5, Flyway 12.4.0, Testcontainers 2.0.5, driver PostgreSQL 42.7.13. springdoc 3.1.1 è costruito su Boot 4.1.0. Spring Modulith 2.1.1 disponibile, compatibilità con Boot 4.1 **da verificare** in FASE 2. Date di fine supporto: la pagina spring.io non le espone al fetch automatico → **da confermare** prima di fissare la versione |
| Bozza SQL e documentazione backend precedenti | `PROGETTAZIONE_BACKEND.md` e `V1__initial_schema.sql` **non disponibili**: repository GitHub vuoto, assenti nel progetto locale e nei file caricati; la ricerca su Google Drive è stata rifiutata (403). Lo schema è stato progettato da zero; se me li carichi, li confronto con DATA_MODEL e segnalo le differenze (in particolare `AUTO_TABLE`) |
| Regole annullamento, modifica, scadenza, orari | BOOKING_RULES §4 e §6 |
| Protezioni duplicati, conflitti tavoli, capienza | vincoli DB + update condizionale, verificati con spike SQL su PostgreSQL 17 ([evidence](evidence/FASE1_SQL_SPIKE.md)): 20 conferme parallele → esattamente 5 su capienza 10 |
| Ambiente PostgreSQL riproducibile | Docker compose + Testcontainers con immagine a digest fissato (TEST_STRATEGY §2). Nel container Docker si avvia; osservato un limite di download Docker Hub (429) → mitigazione prevista |
| Separazione V1 / sviluppi futuri | BOOKING_RULES §12, DATA_MODEL §5, ADR-0006; vincolo DB `confirmation_mode = 'MANUAL'` |

## 5. Dati da chiedere ai titolari (non bloccano la progettazione, bloccano l'attivazione)

| ID | Dato |
|---|---|
| Q-01 | Fasce prenotabili reali per giorno (orari di arrivo, durata media) e coperti per fascia |
| Q-02 | Aree e tavoli (se vogliono usarli da subito): codici, posti |
| Q-03 | Orari pubblici e chiusure (oggi 07–23, mercoledì chiuso: **non confermati**) |
| Q-04 | Email del locale per ricevere le richieste; telefono pubblico |
| Q-05 | Testo informativa privacy (da validare con un esperto) |
| Q-06 | Budget e provider per email e, separatamente, per SMS |

Finché mancano Q-01 e Q-04 le prenotazioni web restano disattivate.

## 6. Storico — FASE 0 (19/10/2026)

### 6.1 Incompatibilità mockup ↔ backend rilevate (ora risolte in progettazione)

| # | Mockup | Risolto in |
|---|---|---|
| 1 | stati `nuova/da-contattare/confermata/annullata` | state machine A-08 + `needs_callback` (BOOKING_RULES §2) |
| 2 | «telefono o email» | P-10 |
| 3 | orario come stringa su fasce fisse | `service_windows` + `booking-options` |
| 4 | niente aree/tavoli/capienza | DATA_MODEL §3.2–3.4 |
| 5 | posti evento come testo | `events.capacity_covers` |
| 6 | eventi senza ora di fine | `events.ends_at` |
| 7 | `price` + `demoPrice` | `price_cents` (P-20h) |
| 8 | allergeni come stringhe | `allergens` + `allergens_status` |
| 9 | foto `blob:` in memoria | upload firmato (ADR-0004) |
| 10 | login «scegli ruolo» | ADR-0003 (P-20g) |
| 11 | SMS simulato su `notice` | outbox (ADR-0005) |
| 12 | router `#/` e build single-file | build statica per demo resta; frontend reale con URL puliti (FASE 4) |
| 13 | niente inserimento telefonico | `POST /admin/reservations` (P-20d) |
| 14 | orari pubblici = prenotabili | separati (A-11) |

### 6.2 Documenti precedenti superati
`ARCHITETTURA_FUTURA.md`: API senza versione → `/api/v1`; prenotazioni «Fase 2» con WhatsApp/Telegram → outbox email + SMS (C-06); `NotificationLog` → `notification_outbox` + `notification_attempts`.
