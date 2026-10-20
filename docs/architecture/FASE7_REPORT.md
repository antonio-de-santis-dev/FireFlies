# FASE 7 — Report tecnico (completamento obbligatorio)

> Branch `fase-7/notifiche`, creato da `fase-6/eventi-capienza` (`6ad7e0e`). 20/10/2026. Nessun merge su `main`, nessun
> force push, **FASE 8 non avviata**. Mandato: «COMPLETAMENTO OBBLIGATORIO FASE 7» (requisiti 1–14) più il requisito 15
> già consegnato e preservato. Decisioni T-57…T-70 e C-30 nel DECISION_LOG.

## 1. Stato dei requisiti

| # | Requisito | Stato | File principali | Test | Esito verificato | Limitazioni |
|---|---|---|---|---|---|---|
| 1 | Panoramica senza scorciatoie, indicatori, richieste, eventi | **Implementato** | `pages/admin/Dashboard.tsx`, `admin.css` (`.stats`) | `fase7.spec` «panoramica…», `qa.spec` 7 larghezze | ✅ locale e CI | — |
| 2 | Navigazione responsive, ordine voci, permessi, via «Capienza e tavoli» | **Implementato** | `AdminLayout.tsx` (`ADMIN_NAV`, `useMobileNav`), `admin.css`, `App.tsx`, `SecurityConfiguration` | `fase7.spec` ordine/permessi + menu a 360/375/390 (apertura, chiusura con Esc e tocco fuori, focus, `aria-current`, target ≥ 44 px, nessun overflow), `navigation.spec` staff | ✅ locale e CI | Impostazioni resta solo del titolare (come prima) |
| 3 | Menu: voce nuova visibile, eliminazione definitiva | **Implementato** | `MenuAdmin.tsx`, `media.tsx` | `MenuApiIT` (cancellata davvero, allergeni in cascata), `content.spec` (default, switch spento, conferma, persistenza dopo refresh, foto rimasta in libreria) | ✅ locale e CI | API di eliminazione esistente già corretta |
| 4 | Eventi: difetto di pubblicazione, switch «Visibile sul sito», nascondere, eliminare, pulizia dopo 2 mesi | **Implementato** | `EventService`, `EventController`, `EventsAdmin.tsx`, `api/events.ts`, `router.tsx`, `RetentionService` | `EventApiIT` +2, `event-visibility.spec` (2), `events.spec`, `navigation.spec`, `RetentionIT.eventiDopoDueMesiSenzaPrenotazioni` | ✅ locale e CI | causa e correzione in §2 |
| 5 | Richieste: schede, niente tavoli, «Da richiamare» automatico, pulizia dopo 1 mese con statistiche | **Implementato** | `Requests.tsx`, `admin.css` (`.res-card`), `ReservationStore.updateStatus`, `RetentionService`, V10 | `ReservationLifecycleIT.daRichiamare…`, `RetentionIT` (4), `reservations.spec`, `events.spec` | ✅ locale e CI | audit conservato (12 mesi) |
| 6 | Via la pagina «Capienza e tavoli» e l'assegnazione tavoli; capienza in persone per fascia | **Implementato** | rimosso `Capacity.tsx`; `CapacitySettings.tsx`; backend tavoli invariato | `reservations.spec` «niente più tavoli», `OperationsIT`, riconciliazione dopo ogni IT | ✅ locale e CI | tabelle e API dei tavoli restano (nessuna migrazione distruttiva) |
| 7 | Foto su telefono: 2 miniature per riga, campi nel riquadro | **Implementato** | `media.tsx`, `admin.css`, `base.css` (`min-width: 0`) | `fase7.spec` a 360/375/390 (stessa riga, campi dentro la scheda, nessun overflow), `qa.spec` | ✅ locale e CI | — |
| 8 | Impostazioni: rinomina, sezione Capienza (giorno × fascia, eccezioni per data), blocco vincolante, riduzione sotto i confermati, riepilogo | **Implementato** | `Settings.tsx`, `CapacitySettings.tsx`, `DiningService.capacityMatrix/save`, `Band`, `OperationsService.summary`, `DiningController` | `OperationsIT` (matrice, 403 staff, CSRF, limite sul registro, `CAPACITY_BELOW_CONFIRMED`, riepilogo, conferme concorrenti), `fase7.spec` capienza, `reservations.spec` | ✅ locale e CI | «Persone al massimo» = presenti nello stesso momento (T-61), non un totale giornaliero |
| 9 | Calendario prenotazioni oggi | **Implementato** | `Today.tsx`, `OperationsService.today`, `OperationsController` | `OperationsIT.calendarioDiOggiSoloConfermate`, `fase7.spec` (7 larghezze, filtro, niente telefoni) | ✅ locale e CI | aggiornamento ogni minuto / al focus, non in tempo reale |
| 10 | Sito: scelta tavolo / evento-compleanno con telefono; giorni chiusi bloccati | **Implementato** | `BookingDialog.tsx`, `BookingCalendar.venueOpen/closedDays`, `BookingOptionsService`, opening-exceptions API | `OperationsIT.giorniDiChiusura…`, `fase7.spec` scelta (con e senza telefono) e giorni chiusi (chip disabilitati, errore, `DAY_CLOSED` dall'API) | ✅ locale e CI | inserimenti dei gestori in giorno chiuso: solo avviso (T-64) |
| 11 | Notifiche: outbox completo, nessun invio reale | **Implementato (parti indipendenti)** / **Bloccato** l'invio reale | `NotificationDispatcher`, `NotificationWorker`, `NotificationController`, gateway finti, `ReservationViews`, `Requests.tsx` | `NotificationDispatcherIT` (6), `OutboxClaimIT` (4) | ✅ locale e CI | provider reali, webhook di consegna, modifica dei modelli: **in attesa di autorizzazione, credenziali e provider** (V-05) |
| 12 | Pulizia notturna coordinata 02:00 Europe/Rome | **Implementato** | `RetentionJob`, `RetentionService`, V10 `maintenance_runs` | `RetentionIT` (4: statistiche, mai future, eventi, una sola esecuzione con 8 thread, ripresa dopo FAILED), `RetentionJobTest` (ora legale) | ✅ locale e CI | periodi da confermare con il titolare (V-04) |
| 13 | Test unità, PostgreSQL, Playwright, 7 larghezze | **Implementato** | vedi §4 | — | ✅ locale; CI §4 | — |
| 14 | Documenti | **Implementato** | MASTER_PLAN, DECISION_LOG, BOOKING_RULES, DATA_MODEL, API_CONTRACT, OpenAPI, NOTIFICATIONS (nuovo), SECURITY_PRIVACY (nuovo), PRE_DEPLOY, LOCAL_SETUP, TEST_REPORT, WORK_PROGRESS, questo report | — | — | — |
| 15 | Locandine sempre intere | **Implementato** (FASE 7, invariato) | §5 | `posters.spec` (9), `ImageProcessorTest` | ✅ locale e CI | locandina di riferimento del committente non ancora fornita |

## 2. Requisito 4 — riproduzione e causa del difetto «Pubblica evento»
Riproduzione con 5 scenari sul sistema reale (Playwright + backend + PostgreSQL: nuovo senza prenotazione, nuovo su
richiesta, bozza poi pubblicata, staff, format). Le pubblicazioni riuscite restavano dopo il ricaricamento; **il difetto**:
- l'editor faceva **due richieste** (crea bozza, poi pubblica). Se la seconda veniva rifiutata (altro evento esclusivo,
  data passata, conflitti) la bozza restava comunque creata;
- l'editor restava su «nuovo» con l'id in memoria: dopo un ricaricamento il modulo era vuoto e l'evento sembrava sparito
  (ma c'era una bozza in elenco), e un nuovo tentativo poteva crearne un'altra;
- l'elenco e la scheda pubblici erano in cache per 60 secondi: un evento appena pubblicato poteva non comparire subito.

Correzione: `visible` nel salvataggio, pubblicazione nella stessa transazione (un errore annulla tutto), un solo
«Salva», indirizzo aggiornato all'id quando si resta sull'evento, letture pubbliche `no-cache` (T-57, T-58).

## 3. Scelte rilevanti
- **Capienza per fascia senza secondo contatore**: il valore per giorno e fascia è `service_windows.max_covers`, già
  applicato dal registro FASE 2 a ogni quanto; conferme concorrenti verificate (`OperationsIT`, 4 conferme, ne passa 1).
- **Pulizia**: statistiche anonime prima di cancellare; i quanti del registro di quel passato si cancellano solo se non
  più occupati; la riconciliazione dopo ogni test resta pulita.
- **Notifiche**: in locale il worker «invia» ai gateway finti e la UI lo dice («Simulata: nessun messaggio reale»); in
  produzione resta spento e l'avvio si rifiuta se lo si accende senza provider reale.
- **Nessuna migrazione distruttiva**: V10 aggiunge solo due tabelle.

## 4. Test (eseguiti in locale; CI in TEST_REPORT)
| Suite | Prima (FASE 6 + req. 15) | Ora |
|---|---|---|
| Backend unità | 74 | **78** (+`RetentionJobTest` 4) |
| Backend integrazione (PostgreSQL 17 reale) | 294 | **311** (+`RetentionIT` 4, `OperationsIT` 4, `NotificationDispatcherIT` 6, `EventApiIT` +2, `ReservationLifecycleIT` +1) |
| Playwright con stack reale | 78 | **93** (+`event-visibility.spec` 2, `fase7.spec` 13; spec esistenti aggiornate) |

Esecuzioni locali reali, in ordine: suite completa Playwright 80/93 passati, 5 falliti, 8 non eseguiti (setup delle
locandine scaduto); corretti i difetti trovati (logo compresso sotto i 900 px nel controllo dei target touch, selettori
dei test) e rieseguiti i 17 test coinvolti: 17/17 ✅. Backend `verify` completo: 78/78 unità, 310/311 IT; l'unico
fallimento era di isolamento (i modelli dei messaggi accesi dal caricamento demo restavano accesi per il test
successivo): corretto nella pulizia comune dei test e rieseguiti `MigrationIT` + `DemoContentLoaderIT` ✅. La conferma
finale su tutta la suite è la CI del commit `f9e3a8e`: **backend run 39443071752 ✅, frontend run 39443071652 ✅**
(482 test).

## 5. Requisito 15 (già consegnato)


#### Problema
Tutte le immagini degli eventi usavano `object-fit: cover` dentro riquadri a proporzioni o altezze fisse (4:3, 16:10,
260 px minimi), più uno zoom al passaggio del mouse in contenitori con `overflow: hidden`: le locandine verticali
perdevano parte alta e bassa (date, orari, sponsor, numeri).

#### Correzione (solo CSS e contenitori, stessa impaginazione)
| Dove | Prima | Ora |
|---|---|---|
| Elenco eventi (`#/eventi`), riga | `cover`, altezza 100% / min 260 px, 16:10 su smartphone | riquadro su fondo scuro, immagine `contain`, altezza automatica (massimo `min(70vh, 560px)` su desktop), intera su smartphone |
| Locandina in evidenza (Domeniche del Fire) | `cover`, 4:3 su smartphone | `contain`, altezza automatica (massimo `min(75vh, 620px)`) |
| Scheda evento (`#/eventi/:slug`) | `cover` 4:3 | `contain`; desktop e tablet: tutta nello schermo (affiancata alle informazioni da 900 px, sopra sotto i 900); smartphone (≤ 640 px): intera a tutta larghezza, poi le informazioni |
| Altre foto della scheda | quadrate `cover` | quadrate con l'immagine intera (`contain`), colonne vincolate |
| Area gestori: elenco eventi, anteprima nell'editor, foto della libreria nell'editor eventi | `cover` | `contain` su fondo scuro (`.editor__preview--poster`, `PhotoPicker contain`); le altre pagine (menu) non cambiano |
| Zoom al passaggio del mouse | `transform: scale(…)` (tagliava i bordi) | leggera luminosità, nessun ritaglio |

Il fondo dei riquadri è il token `--poster-bg` (= `--ff-ink-950`, il nero del tema). Nessuna altezza fissa, nessuna
deformazione. Il titolo della scheda va a capo se contiene parole lunghissime (prima allargava la pagina a 1366 px).

**Varianti del backend**: verificate, non ritagliano. `ImageProcessor` riduce solo in larghezza (480/960/1600 px) con
l'altezza proporzionale; nuovo test `locandineMaiRitagliateNelleVarianti` (1080×1920, 1240×1754, 1200×1200,
1920×1080). L'originale non viene modificato oltre alla ricodifica già prevista dalla FASE 4.

#### Test di regressione (`frontend/tests/posters.spec.ts`)
- Genera tre locandine di prova (verticale 2:3, quadrata, orizzontale 16:9) con quattro bande colorate sui bordi e testo
  in alto (data, orario, telefono finti), le carica dalla libreria e pubblica una serata per ciascuna.
- Su **1920, 1440, 1366, 768, 390, 375, 360 px**, in elenco e in scheda: `object-fit` mai `cover`/`fill`, proporzioni
  mostrate = originali (±2%), nessun contenitore che tagli l'area disegnata, nessuno scorrimento orizzontale; screenshot
  Playwright dell'area disegnata (allegati al report di Playwright) con il controllo dei pixel: **le quattro bande dei
  bordi devono essere tutte visibili**. Scheda: desktop affiancata e alta al massimo lo schermo, tablet sopra le
  informazioni e nello schermo, smartphone a tutta larghezza.
- Miniature delle altre foto (Domeniche del Fire) su tutte le larghezze; area gestori (elenco, anteprima, libreria).
- Ogni immagine messa in `frontend/tests/fixtures/locandine/` viene verificata allo stesso modo: **la locandina
  verticale di riferimento indicata dal committente non è arrivata in questa sessione**; appena disponibile va copiata lì.

#### Esito
- Locale: backend unità 74 ✅ (`ImageProcessorTest` 17); Playwright **78/78** ✅ (`npm run verifica`, di cui
  `posters.spec` 9), nessuna regressione su QA (7 larghezze) e axe.
- CI: frontend run 39429654838 ✅ su `d6ac3d4`; backend run 39426932068 ✅ su `5dd5972` (backend invariato dopo).
  Il run frontend 39426932093 su `5dd5972` era fallito per le miniature che allargavano la pagina sotto i 900 px
  (difetto introdotto dalla prima correzione, trovato da `qa.spec`, corretto in `d6ac3d4` e ora coperto anche da
  `posters.spec`).


## 6. Limitazioni e cose aperte
- **Invio reale di email e SMS**: bloccato in attesa di autorizzazione, provider, credenziali e testi (V-05).
- Periodi di conservazione (1 mese, 2 mesi, 90 giorni, 12 mesi) da confermare con il titolare/consulente (V-04).
- Locandina verticale di riferimento del committente non ancora ricevuta (`frontend/tests/fixtures/locandine/`).
- Calendario di oggi: aggiornamento periodico, non push in tempo reale.
- Restano aperte V-01…V-03 (PRE_DEPLOY).

## 7. Prossima fase (non avviata)
FASE 8 — hardening, monitoraggio, deploy: solo dopo l'approvazione di questa fase.
