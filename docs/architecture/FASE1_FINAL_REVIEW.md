# FASE 1 — Revisione finale

> 19/10/2026 · Risposta alla «Revisione finale FASE 1» del committente. Solo progettazione: **nessun codice backend, nessuna migrazione definitiva, nessuna modifica al frontend**. FASE 2 non avviata.

## 1. Modifiche effettuate

### 1.1 Capienza e sovrapposizione delle fasce
- **Problema confermato**: nella rev. 1 una prenotazione contava solo nella fascia di arrivo, quindi 19:00–21:00 ×6 («Aperitivo») e 20:00–22:00 ×6 («Sera») su capienza 10 venivano accettate entrambe, con 12 persone presenti tra le 20:00 e le 21:00.
- **Soluzione**: capienza per **presenza contemporanea**. Il tempo è diviso in quanti da 15 minuti; per ogni zona e quanto il **registro di occupazione** (`capacity_slots`) tiene capienza effettiva e persone confermate presenti. Una conferma occupa tutti i quanti del suo intervallo `[inizio, fine)` in una sola transazione: crea i quanti mancanti, li **blocca in ordine di tempo**, controlla, aggiorna. Un `CHECK (occupied ≤ capacity)` sul database fa da ultima difesa.
- Nessun algoritmo di assegnazione: il registro conta persone, non sceglie tavoli.
- Verificato su PostgreSQL 17 (spike 2): il caso guida viene rifiutato; con 60 conferme parallele casuali la presenza massima è rimasta 10 su 10, il registro coincide con il ricalcolo e non si sono verificati deadlock.

### 1.2 Capacità condivisa tra eventi e servizio ordinario
- **Problema confermato**: nella rev. 1 l'evento aveva un contatore indipendente.
- **Soluzione**: **una sola capienza fisica** per zona. Le prenotazioni per evento occupano lo stesso registro delle ordinarie; la capienza dell'evento è solo una quota aggiuntiva (sulla riga evento, nessuna tabella in più). Regole esplicite per:
  - **evento su tutto il locale**: esclusivo per default, blocca il servizio ordinario in tutte le zone;
  - **evento limitato a un'area**: solo su una **zona fisicamente separata e configurata**, altrimenti `422 ZONE_NOT_SEPARATE`;
  - **evento contemporaneo al servizio ordinario**: ammesso solo come non esclusivo sulla stessa capienza (somma mai oltre la fisica) — oppure vietabile del tutto se preferisci (P-31);
  - **modifiche di capienza con confermati**: riduzione sotto i presenti → `409 CAPACITY_BELOW_CONFIRMED` con l'elenco, mai annullamenti automatici; cambi di orario/zona/esclusività di un evento con confermati → `409`.
- Pubblicare un evento esclusivo dove ci sono ordinarie confermate è **bloccato** (non forzabile).
- Verificato (spike 2): 20 gare «pubblica evento esclusivo» contro «conferma ordinaria» → sempre un solo vincitore, 0 sovrapposizioni.

### 1.3 Affidabilità delle notifiche
- Corretto il test che prometteva «ogni messaggio inviato una volta»: ora la semantica dichiarata è **at-least-once**.
- Aggiunti: chiave di business unica (nessun doppio accodamento), **lease con token** (un worker lento non sovrascrive l'esito di un altro), **tentativo registrato prima della chiamata** al provider, classificazione degli esiti (`ACCEPTED`, `REJECTED_PERMANENT`, `TRANSIENT_ERROR`, `UNKNOWN`), **backoff** con limite, stato **`UNCERTAIN`** per gli esiti incerti senza idempotenza del provider (nessun ritentativo automatico), chiave di idempotenza stabile verso il provider, messaggi scaduti o superati non inviati, reinvio manuale con avviso e audit.
- **Nessun SMS reale** in sviluppo e test: tre condizioni necessarie in produzione e avvio bloccato in `local`/`test` se è configurato un provider reale.
- Verificato (spike 2): 4 worker su 200 messaggi senza doppie prese in carico; il worker con lease scaduto non riesce a registrare l'esito.

### 1.4 Assegnazione manuale dei tavoli
- Resta manuale e facoltativa; i tavoli devono essere della zona della prenotazione.
- Posti insufficienti → **blocco predefinito** `422 SEATS_BELOW_PARTY_SIZE`.
- **Eccezione solo OWNER**, esplicita (`overrideSeats` + motivo obbligatorio), salvata sull'assegnazione (`seats_override_by`, `seats_override_reason`) e in **audit** (`TABLE_SEATS_OVERRIDE`). STAFF → `403`.
- L'eccezione riguarda solo i posti del tavolo, **mai** la capienza della zona.

### 1.5 File aggiornati
| File | Cosa è cambiato |
|---|---|
| `BOOKING_RULES.md` | §1 zone e quanti; §2 effetti delle transizioni sul registro; §3 riscritto (presenza contemporanea, procedura, cambi di capienza, vincoli operativi); §7 riscritto (capienza condivisa); §8 blocco posti + eccezione OWNER; §9 at-least-once; §10 permessi |
| `DATA_MODEL.md` | `capacity_buckets` → `capacity_zones` + `capacity_slots`; `occupied_until`; eventi `zone_id`/`exclusive`/`confirmed_covers`; eccezione posti; outbox con lease/idempotenza/scadenza; tentativi con inizio/fine/esito; `sms_enabled`; invarianti I-1…I-13 |
| `SYSTEM_CONTEXT.md` | modulo `capacity`, ordine globale dei lock, job di recupero lease e pulizia registro |
| `API_CONTRACT.md` | nuovi codici d'errore, `zoneId`, `durationMinutes`, `overrideSeats`, linea del tempo della capienza, zone, `acknowledgePending`, reinvio notifiche |
| `api/openapi.yaml` | solo i flussi toccati (conferma, tavoli, inserimento manuale, modifica, capienza, stati notifica); validato con Redocly CLI 2.1.0: **valido** |
| `docs/testing/TEST_STRATEGY.md` | concorrenza C-1…C-22, riconciliazione, eccezione posti, notifiche N-1…N-15, garanzie S-1…S-5 |
| `ADR/0002` | vincoli aggiornati |
| `ADR/0005` | riscritto (at-least-once) |
| `ADR/0006` | riscritto e rinominato `0006-prenotazioni-v1-capienza-per-presenza.md` |
| `DECISION_LOG.md` | consolidate C-10…C-14; proposte riviste e nuove; storico delle decisioni superate |
| `evidence/FASE1_SQL_SPIKE_2.md` | nuovo: script e risultati delle verifiche |

Tabelle: −1 (`capacity_buckets`), +2 (`capacity_zones`, `capacity_slots`). La zona è giustificata dalla richiesta stessa: serve un'entità «spazio fisicamente separato» per eventi limitati e capienza fisica; le aree dei tavoli non lo sono. **Nessuna dipendenza o servizio aggiunto.**

## 2. Decisioni tecniche definitive proposte
1. Spring Boot 4.1.x, Java 21, PostgreSQL 17, Flyway, monolite modulare (moduli e dipendenze in SYSTEM_CONTEXT §4).
2. Capienza = registro di occupazione per zona e quanto da 15 minuti, con lock ordinati e `CHECK` dichiarativo.
3. Ordine globale dei lock: **evento → zona → quanti (zona, orario) → prenotazione/tavoli**; isolamento `READ COMMITTED`.
4. Eventi sulla stessa capienza fisica; esclusivi per default; area-evento solo con zona fisicamente separata.
5. Tavoli manuali, vincolo di esclusione, blocco posti con eccezione OWNER auditata.
6. Outbox at-least-once con lease a token, tentativi registrati prima della chiamata, `UNCERTAIN` senza ritentativo cieco, SMS reali impossibili fuori da `prod`.
7. Conferma sempre manuale, vincolata anche nel database (`confirmation_mode = 'MANUAL'`).

## 3. Vincoli e casi limite

### Vincoli operativi V1 (da comunicare ai titolari)
- Orari e durate a multipli di **15 minuti**.
- **Durata fissa** per prenotazione: i posti si liberano prima solo se il gestore segna «andati via» o «non presentati»; chi resta oltre va **allungato** a mano.
- **Clienti senza prenotazione non tracciati**: il titolare imposta un limite prenotabile **più basso** della capienza fisica per lasciare margine.
- Una prenotazione confermata sta in **una sola zona**.
- Con una sola zona configurata, un evento esclusivo chiude il locale alle prenotazioni ordinarie per tutta la sua durata.
- Fasce prenotabili non oltre mezzanotte; eventi sì.

### Casi limite e comportamento
| Caso | Comportamento |
|---|---|
| Intervalli adiacenti (finisce 21:00, inizia 21:00) | non si sovrappongono |
| Cena a cavallo del cambio d'ora (25/10, 01:00–03:00) | quanti calcolati in tempo reale (3 ore effettive) |
| Orario inesistente (29/03 02:30) | `422 INVALID_LOCAL_TIME` |
| Capienza ridotta con persone già confermate | `409` con elenco; nessun annullamento |
| Evento esclusivo su orari con ordinarie confermate | pubblicazione bloccata finché non sono gestite |
| Richieste in attesa negli orari di un evento esclusivo | pubblicazione con conferma esplicita; quelle richieste non sono più confermabili come ordinarie |
| Evento spostato con confermati | `409`, prima si gestiscono le prenotazioni |
| Conferma e scadenza nello stesso istante | vince una sola |
| Due gestori sullo stesso tavolo | uno `409 TABLE_CONFLICT`, rollback completo |
| Posti tavolo insufficienti | blocco; eccezione solo OWNER con motivo e audit |
| Provider va in timeout dopo l'invio | `UNCERTAIN` (o ritentativo con la stessa chiave se il provider la supporta) |
| Worker interrotto a metà invio | recupero del lease → trattato come esito incerto |
| Prenotazione annullata prima che parta la conferma | conferma `SKIPPED (SUPERSEDED)` |
| Provider offline | backoff fino a `DEAD`; prenotazione invariata |
| Configurazione SMS reale in test | l'applicazione non si avvia |

## 4. Test che verranno realizzati in FASE 2
La FASE 2 crea scaffold, migrazione V1 e infrastruttura di test; i test dei moduli arrivano nelle fasi dei rispettivi moduli. Già in FASE 2:
1. **Migrazioni**: applicazione da zero su PostgreSQL 17 (Testcontainers), `flyway validate`, verifica che tutti i vincoli esistano.
2. **Test di vincolo diretti in SQL** (equivalenti allo spike, su schema reale): `ck_slot_not_overbooked`, `ck_slot_aligned`, `ex_table_no_overlap` con adiacenze, `ux_res_web_duplicate`, `ck_event_quota`, `ck_res_occupying`, `ck_override_complete`, `CHECK (confirmation_mode = 'MANUAL')`, stati e transizioni ammessi a livello di colonna.
3. **Infrastruttura di concorrenza**: utilità con `CountDownLatch` + transazioni separate + rilevazione deadlock; **query di riconciliazione** (TEST_STRATEGY §3.4) pronta per tutte le fasi.
4. **Prima suite sul registro di occupazione** (modulo `capacity` minimo): C-1, C-2, C-3, C-4, C-7, C-8 sul codice Java reale.
5. **Garanzie notifiche di base**: S-1, S-2 (bean fake e avvio bloccato) e la presa in carico N-2/N-10 sul worker reale.
6. Healthcheck, gestione errori `problem+json`, profili `local`/`test`/`prod`, CI minima con `./mvnw verify`.

Restano per le fasi successive: C-5…C-6, C-9…C-22 (FASE 5–6), N-1, N-3…N-15 (FASE 7), §4 tavoli (FASE 5), sicurezza e permessi (FASE 3).

## 5. Decisioni che richiedono ancora la tua approvazione
Le nuove o cambiate in questa revisione:
- **P-03 / P-30 / P-33 / P-35**: modello a quanti da 15 minuti, vincoli operativi, lock della zona.
- **P-06**: eventi sulla stessa capienza, esclusivi per default, area-evento solo su zona separata.
- **P-31**: eventi non esclusivi contemporanei al servizio ordinario **ammessi** (sicuri, posti evento non garantiti) **oppure vietati** in V1?
- **P-32**: divieto di spostare eventi con prenotazioni confermate.
- **P-08**: blocco posti tavolo con eccezione OWNER auditata.
- **P-34 / P-36 / P-37**: esiti incerti, backoff, tre condizioni per l'SMS reale.

Ancora aperte dalla rev. 1 (DECISION_LOG §3.3–3.5): P-01 (Spring Boot 4.1), P-02, P-04, P-05, P-09, P-11, P-12, P-13, P-14…P-19, P-21, P-23…P-29, modifiche funzionali al mockup P-20a…i.

Per avviare la FASE 2 servono anche: permesso di **scrittura su GitHub** e di caricare i branch (`fase-0/audit`, `fase-1/progettazione`), e conferma della struttura del repository (P-28).
