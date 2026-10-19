# DECISION LOG — FireFlies Aperitif backend

> Aggiornato: 19/10/2026 · Fase: **0 (audit)** · Stato: bozza da approvare, non ancora committata.
> Precedenza in caso di contrasto: **decisioni correnti del master plan** > mockup > documenti precedenti.

## 1. Decisioni approvate (dal master plan, §2–§3)

| ID | Area | Decisione |
|---|---|---|
| A-01 | Frontend | Si conserva il mockup React 18 + TypeScript + Vite; si migra **gradualmente** da store in memoria a servizi API, senza cambiare l'estetica |
| A-02 | Backend | Java 21 + Spring Boot, **monolite modulare**, REST versionata `/api/v1` |
| A-03 | Database | PostgreSQL + Flyway; migrazioni incrementali, mai modificare una migrazione già applicata |
| A-04 | Accesso | Spring Security con **sessione server-side**, cookie `HttpOnly`/`Secure`/`SameSite`, CSRF, ruoli `OWNER`/`STAFF`; il link nascosto non è una protezione |
| A-05 | Media | Object storage S3-compatibile (candidato Cloudflare R2); nel DB solo metadati e chiavi; upload con URL firmato breve |
| A-06 | Notifiche | Transactional outbox su PostgreSQL + worker; `SmsGateway` con `FakeSmsGateway`; provider reale solo dopo autorizzazione costi |
| A-07 | Prenotazioni V1 | **Nessuna assegnazione automatica dei tavoli**; flusso conservativo *richiesta PENDING → il gestore approva/rifiuta* finché il committente non decide diversamente |
| A-08 | Stati | `PENDING`, `CONFIRMED`, `REJECTED`, `CANCELLED`, `COMPLETED`, `NO_SHOW`; state machine esplicita e idempotente; il client non può confermare |
| A-09 | Conflitti | Anti-sovrapposizione tavoli atomica (`tstzrange` + `EXCLUDE USING gist` su assegnazioni attive); capienza eventi con lock nella transazione |
| A-10 | Canali | Sorgenti `WEB`, `PHONE`, `EMAIL` (inserimento manuale del gestore, niente centralino/IMAP in V1) |
| A-11 | Orari | Fuso `Europe/Rome`, intervalli `[inizio, fine)`, gestione DST; orari pubblici ≠ finestre prenotabili |
| A-12 | Esclusi | Niente Redis, Kafka, Kubernetes, MongoDB, microservizi; niente blob nel DB |
| A-13 | V2 | Algoritmo tavoli, combinazioni, auto-conferma avanzata, lettura email/telefonate, waitlist intelligente: **non implementare** |
| A-14 | Processo | Una fase alla volta, branch dedicati, nessun merge/deploy senza autorizzazione |

## 2. Decisioni aperte

### Bloccanti per la FASE 1 (domande al committente)

| ID | Domanda | Raccomandazione |
|---|---|---|
| D-01 | Giorni normali: **conferma manuale** di ogni richiesta, oppure auto-conferma entro una capienza impostata dal gestore? | Conferma manuale in V1 |
| D-02 | Il gestore sceglie il **tavolo specifico** oppure gestisce un **totale di coperti** per fascia? | Coperti per fascia in V1 e tavolo **facoltativo** (modello pronto per i tavoli) |
| D-03 | Quanti tavoli/aree, posti, fasce, durata media? | Se non noti: configurazione vuota e disattivata, dati demo etichettati |
| D-04 | Eventi: capienza unica o per area? Il servizio ordinario resta prenotabile durante un evento? | Capienza unica per evento; servizio ordinario chiuso alle prenotazioni web nella stessa fascia |
| D-05 | Notifiche: SMS sempre alla conferma o solo con cellulare + consenso operativo? Email se presente? Notifica al gestore per ogni nuova richiesta? | V1 con email + `FakeSmsGateway`; SMS reale dopo il preventivo |
| D-06 | Il cliente deve lasciare **telefono obbligatorio** + email facoltativa (master plan) o «telefono **o** email» (mockup)? | Telefono obbligatorio, email facoltativa |

### Da decidere entro la FASE 2 (tecniche, ho una raccomandazione)

| ID | Tema | Raccomandazione |
|---|---|---|
| D-10 | Versione Spring Boot | **4.1.x** (ultima: 4.1.1); la 3.5 è a fine supporto open source — da verificare su spring.io prima di fissarla |
| D-11 | PostgreSQL | 17 o 18 in produzione (secondo il provider); 16 va bene in locale |
| D-12 | Prezzi | `price_cents integer` (null = prezzo non comunicato); il `demoPrice` resta solo nel mockup/seed demo |
| D-13 | Identificativi | UUID per entità esposte, `slug` per URL pubblici di eventi/categorie |
| D-14 | Struttura repository | Monorepo: `backend/`, `frontend/` (copia del mockup), `infra/`, `docs/` — vedi D-18 |
| D-15 | Dati nel frontend | Strato `api/client` + `api/services` con `fetch` tipizzato; valutare TanStack Query solo se la gestione cache diventa complessa |
| D-16 | Sessioni | Spring Session JDBC (sessioni in PostgreSQL) per sopravvivere ai riavvii, senza Redis |
| D-17 | Test di integrazione | Testcontainers in CI; in questo container il daemon Docker è spento → avviarlo o usare il PostgreSQL 16 locale per i test |
| D-18 | Migrazione mockup nel repo | Copiare `mockup-interattivo/` in `frontend/` **senza** `node_modules`, `dist`, `test-results`; asset originali inclusi (~4.7 MB). Gli ZIP (100 MB) e i video **non** nel repo |

### Non bloccanti (FASE 8 o successive)

Hosting (opzione A managed vs B VPS) con costi da fonti ufficiali · provider email (es. Resend) e SMS · dominio e TLS · retention dati prenotazioni (proposta 90 giorni) · testi legali GDPR (da validare con esperto) · 2FA per OWNER · lingue (inglese).

## 3. Contraddizioni e incompatibilità rilevate

### 3.1 Bozza SQL precedente
`PROGETTAZIONE_BACKEND.md` e `V1__initial_schema.sql` **non sono presenti** né nel repository GitHub (vuoto) né nel progetto locale né tra i file caricati. Non posso rivederli. Decisione proposta: **lo schema V1 si scrive da zero in FASE 2** partendo dal modello del master plan §4; se il committente li ha, li confrontiamo solo per segnalare differenze (in particolare ogni riferimento ad `AUTO_TABLE`, auto-conferma o assegnazione automatica va eliminato).

### 3.2 Mockup ↔ modello backend

| # | Mockup oggi | Requisito backend | Impatto |
|---|---|---|---|
| 1 | Stati richiesta `nuova / da-contattare / confermata / annullata` | `PENDING / CONFIRMED / REJECTED / CANCELLED / COMPLETED / NO_SHOW` | «da contattare» non è uno stato: diventa un **flag/nota** su PENDING; servono «Rifiutata», «Completata», «Non presentato» nella UI admin |
| 2 | Un solo campo «telefono **o** email» | `customer_phone` obbligatorio + email facoltativa | Cambia il passo 4 della prenotazione (vedi D-06) |
| 3 | Orario = stringa (`'19:30'`) su fasce fisse codificate nel componente | `starts_at/ends_at` `timestamptz`, finestre prenotabili configurate dal gestore | Le fasce del dialog vanno lette da `GET /public/booking-policy` |
| 4 | Nessun concetto di area, tavolo, durata, capienza | `dining_areas`, `dining_tables`, assegnazioni, capienza | Nuove schermate admin (aree/tavoli/regole) da **progettare e far approvare** prima di toccare la UI |
| 5 | Eventi con `seats: string` testuale | `capacity_max` numerico + allocazioni | Campo numerico nell'editor eventi |
| 6 | Eventi `bozza / pubblicato`, data facoltativa | `status`, `start_at/end_at` | Aggiungere ora di fine e stato «annullato» |
| 7 | `price: number | null` + `demoPrice` | `price_cents` | `demoPrice` solo nel seed demo, mai in produzione |
| 8 | Allergeni come array di stringhe | tabella `allergens` (14 UE) + relazione | mappatura 1:1 sui 14 allergeni già nel mockup |
| 9 | Foto come URL in memoria (`blob:`) | upload firmato → verifica → `READY` | La UI di caricamento resta, cambia il servizio |
| 10 | Login «scegli il ruolo» | credenziali reali, sessione, ruoli server | Pagina login da trasformare (FASE 3) |
| 11 | SMS simulato registrato su `notice` | outbox + stato consegna separato | Mostrare `QUEUED/SENT/FAILED` nella riga richiesta |
| 12 | Router con `#/…` e build single-file apribile da disco | URL puliti, API sullo stesso dominio | Il build statico resta per le demo; il frontend reale userà `BrowserRouter` + rewrite (decisione in FASE 4) |
| 13 | Nessun inserimento manuale da telefono | `POST /admin/reservations` con `source=PHONE/EMAIL` | Nuovo modulo nell'area Richieste |
| 14 | Orari pubblici unici (07–23, mercoledì chiuso, non confermati) | orari pubblici ≠ finestre prenotabili | Due concetti distinti nelle impostazioni |

### 3.3 Documenti precedenti
- `ARCHITETTURA_FUTURA.md` usa API `/api/public/…` e `/api/admin/…` **senza versione** → si adotta `/api/v1/…`.
- Lo stesso documento mette le prenotazioni in «Fase 2» con notifica via email/WhatsApp/Telegram → superato da A-06 (outbox, email + SMS via adapter).
- Usa `NotificationLog` → sostituito da `notification_outbox` + `notification_attempts`.
