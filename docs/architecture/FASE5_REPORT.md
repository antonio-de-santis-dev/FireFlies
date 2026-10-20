# FASE 5 — Report tecnico (Prenotazioni V1)

> Branch `fase-5/prenotazioni`, creato dall'ultimo commit di `fase-4/menu-media` (`7775032`). 20/10/2026.
> Nessun merge su `main`, nessun branch approvato modificato. **La FASE 6 non è iniziata.**

## 1. Cosa è stato fatto

### 1.1 Obiettivi della fase
| # | Obiettivo del mandato | Stato | Dove |
|---|---|---|---|
| 1 | Richiesta dal sito: data e ora, persone, nome, **telefono obbligatorio**, email facoltativa, note | ✅ | `POST /public/reservations`, `BookingDialog.tsx` |
| 2 | Orari prenotabili, validazione, anti-spam | ✅ fasce e arrivi dal backend, anticipo minimo, giorni massimi, chiusure, posti; Idempotency-Key, campo trappola, CSRF, 5/ora per IP, 3/24 h per telefono, doppio invio bloccato | `GET /public/booking-options`, `ReservationService.createWeb` |
| 3 | Salvataggio «in attesa» e messaggio chiaro al cliente | ✅ stato `PENDING`; esito «Richiesta ricevuta: non è ancora una prenotazione… Il tavolo è riservato solo dopo la conferma del locale», con il codice `FF-XXXXXX` | `Receipt`, dialog |
| 4 | Dashboard: elenco reale con filtri per giorno, stato e origine; dettaglio | ✅ filtri periodo/giorno, stato (con conteggi), origine, ricerca; dettaglio con posti, tavoli, notifiche, storico | `pages/admin/Requests.tsx`, `GET /admin/reservations[/{id}]` |
| 5 | Conferma, rifiuto, annullamento, modifica | ✅ più «Completata», «Non presentato» e la correzione del titolare entro 48 h | `PATCH …/status`, `PATCH /admin/reservations/{id}` |
| 6 | Inserimento manuale (telefono, email) | ✅ con «conferma subito», «da richiamare», avvisi fuori orario/chiusura/poco preavviso | `POST /admin/reservations`, dialog «Nuova prenotazione» |
| 7 | Storico e audit | ✅ storico degli stati con autore, motivo e nota; audit di creazione, modifiche, stati, tavoli, eccezioni sui posti, scadenze, configurazione | `reservation_status_history`, `audit_logs` |
| 8 | Tavoli e zone secondo i permessi | ✅ configurazione solo titolare; assegnazione manuale per staff e titolare; eccezione sui posti solo titolare con motivo | `dining/`, pagina «Capienza e tavoli» |
| 9 | Controllo automatico della capienza, **nessuna assegnazione automatica dei tavoli** | ✅ controllo preventivo all'invio, vincolante alla conferma/modifica; nessun tavolo assegnato dal sistema (verificato in `ReservationLifecycleIT.tavoliAssegnatiAMano`) | |
| 10 | Registro a quanti di 15 minuti della FASE 2, **nessun secondo contatore** | ✅ `CapacityLedger` unico; capienza effettiva per quanto da `DiningSlotCapacityPolicy`; le richieste in attesa non occupano posti | T-37 |
| 11 | Niente overbooking, conflitti tavoli, rilascio corretto, transazioni e conferme simultanee | ✅ lock prenotazione → zona → quanti → tavoli in ordine di id; vincolo `ck_slot_not_overbooked` e di esclusione come ultima difesa; riconciliazione registro = prenotazioni dopo **ogni** test | `ReservationConcurrencyIT` |
| 12 | Eventi di notifica nell'outbox, **nessun invio reale**, simulato ≠ consegnato | ✅ email `PENDING` = «preparata, non inviata»; SMS `SKIPPED` = «non previsto (canale spento)»; `delivered` vero solo per `SENT` (mai in V1); nessun worker di invio | T-41 |
| 13 | Frontend: grafica approvata, modulo pubblico e dashboard collegati, caricamento/errori/conferme/sessione scaduta | ✅ stesse classi e componenti; aggiunti solo gli stati necessari (orario non disponibile barrato, dettaglio richiesta, pagina «Capienza e tavoli» con i componenti esistenti) | §1.4 |

### 1.2 Backend
- **`dining/`** — configurazione: zone di capienza (capienza fisica, attiva), aree, tavoli (posti min/max, disattivati se
  hanno storico), fasce per giorno della settimana (primo/ultimo arrivo, passo, permanenza, limite di presenze, validità,
  mai oltre la mezzanotte), regole (richieste dal sito aperte, anticipo minimo, giorni massimi, persone massime, scadenza),
  eccezioni per data (chiusura agli arrivi, limite di presenze). Ogni modifica che cambia la capienza ricalcola i quanti
  futuri e viene **rifiutata** se scenderebbe sotto i confermati (`409 CAPACITY_BELOW_CONFIRMED`).
- **`reservation/`** — `ReservationService` (comandi, una transazione ciascuno), `ReservationStore` (SQL),
  `ReservationViews`/`ReservationQueries` (letture), `BookingOptionsService` (orari pubblici senza numeri),
  `PhoneNumbers` (E.164), `WebRequestLimiter`, `PendingExpiryJob` (ogni 5 minuti, `SKIP LOCKED`), `ReservationController`.
- **Macchina a stati** (BOOKING_RULES §2): PENDING → CONFIRMED / REJECTED (motivo) / CANCELLED (motivo);
  CONFIRMED → CANCELLED prima dell'arrivo, COMPLETED dall'arrivo, NO_SHOW da 15 minuti dopo; NO_SHOW ↔ COMPLETED solo
  titolare entro 48 h. Stesso stato ripetuto = nessun effetto; versione vecchia = `409 VERSION_CONFLICT`. Uscita
  anticipata e assenza liberano i quanti futuri e accorciano i tavoli.
- **Notifiche** (`NotificationOutbox`): accodate nella stessa transazione (ricevuta, avviso al locale, conferma, rifiuto,
  annullamento, modifica, scadenza), chiave di business univoca, mai le note del cliente nel payload; una conferma poi
  annullata marca i messaggi non partiti come superati.
- **Sicurezza**: `POST /public/reservations` è l'unica scrittura pubblica e passa da CSRF; tutto il resto richiede
  sessione; configurazione solo OWNER (regola sull'URL + `@PreAuthorize`).
- **Dati demo** (solo profilo `local`): zona «Locale» da 40 posti, sala e dehors con 10 tavoli, le fasce del modulo
  approvato nei giorni di apertura, richieste aperte; nessuna prenotazione finta (T-45).

### 1.3 Permessi (verificati dal backend su ogni richiesta)
| Area | Anonimo | STAFF | OWNER |
|---|---|---|---|
| `GET /public/booking-options`, `POST /public/reservations` (con CSRF) | ✅ | ✅ | ✅ |
| Elenco, dettaglio, posti del giorno, configurazione in lettura | `401` | ✅ | ✅ |
| Conferma, rifiuto, annullamento, modifica, inserimento manuale, tavoli | `401` | ✅ | ✅ |
| Correzione NO_SHOW ↔ COMPLETED, eccezione sui posti del tavolo | `401` | `403` | ✅ |
| Zone, aree, tavoli, fasce, regole, eccezioni (scrittura) | `401` | `403` | ✅ |

### 1.4 Frontend
- **Richiedi un tavolo** (stesso dialog a 5 passi): orari e disponibilità dal backend per il giorno e le persone scelte
  (orari non disponibili barrati e non selezionabili, con il motivo per gli screen reader), persone massime dalle regole,
  telefono obbligatorio ed email facoltativa in due campi, campo trappola invisibile, chiave di idempotenza rinnovata
  quando cambiano i dati, «Invio in corso…», errori del server riportati al passo e al campo giusto (orario occupato nel
  frattempo → si torna agli orari aggiornati), richieste chiuse → invito a telefonare.
- **Richieste**: filtri, conteggi, «Gestisci» apre il dettaglio con posti liberi nell'orario, azioni consentite adesso,
  motivi, modifica, tavoli, notifiche con il loro stato reale, storico; «Nuova prenotazione» per telefono/email.
- **Capienza e tavoli** (nuova voce del menu): posti del giorno quarto d'ora per quarto d'ora per tutti; regole, capienza,
  fasce, aree e tavoli, chiusure e limiti modificabili solo dal titolare (per lo staff in sola lettura).
- **Panoramica** e contatore «Richieste» del menu dalle richieste reali (aggiornato ogni minuto e al ritorno sulla scheda);
  le richieste dimostrative del mockup sono state rimosse. «Orari e contatti»: l'interruttore «Richieste tavolo attive»
  ora salva subito la regola sul server.
- Sessione scaduta: invariato dalla FASE 4 (ogni `401` riporta all'accesso con l'avviso); conflitto di versione
  spiegato nel dettaglio con i dati ricaricati.

## 2. Decisioni (dettaglio in DECISION_LOG T-37…T-46)
T-37 contatore unico e capienza effettiva · T-38 ordine dei controlli e idempotenza · T-39 limiti per IP e telefono ·
T-40 CSRF anche sulla scrittura pubblica · T-41 outbox senza invii · T-42 scadenza · T-43 tavoli · T-44 formato orari ·
T-45 dati demo delle prenotazioni · T-46 interruttore e pagina «Capienza e tavoli». API_CONTRACT §2, §4, §5 e
`openapi.yaml` (draft.5) aggiornati agli endpoint reali.

## 3. File principali
| Area | File |
|---|---|
| Backend | `dining/*` (nuovo), `reservation/*` (nuovo), `notification/NotificationOutbox`, `capacity/CapacityLedger` (`read`, `recomputeCapacities`), `capacity/CapacitySlotStore`, `audit/AuditAction`, `common/ConstraintViolationTranslator`, `security/SecurityConfiguration`, `demo/DemoContentLoader`, `application-test.yml` |
| Test backend | `WebReservationIT`, `ReservationLifecycleIT`, `ReservationConcurrencyIT`, `ReservationRulesTest`, `ReservationTestSupport`, `support/TestClock`, `ApiClient` (header), `PostgresIntegrationTest` (pulizia di zona, regole e limitatore), `DemoContentLoaderIT`, `PersistenceAfterRestartIT` |
| Frontend | `api/reservations.ts` (nuovo), `api/client.ts` (header), `components/BookingDialog.tsx`, `pages/admin/Requests.tsx`, `pages/admin/Capacity.tsx` (nuovo), `Dashboard.tsx`, `AdminLayout.tsx`, `Settings.tsx`, `store.tsx`, `App.tsx`, CSS (stati nuovi), `data/requests.ts` (rimosso) |
| Test frontend | `tests/reservations.spec.ts` (nuovo), `navigation.spec.ts`, `helpers.ts`, `a11y.spec.ts`; `screenshots`/`demo` allineati ai testi nuovi |
| Documenti | questo report, `TEST_REPORT.md`, `LOCAL_SETUP.md`, `WORK_PROGRESS.md`, `API_CONTRACT.md`, `openapi.yaml`, `DECISION_LOG.md` |

## 4. Risultati dei test
Vedi `docs/testing/TEST_REPORT.md` §«FASE 5» (numeri, verifiche obbligatorie, difetti trovati, CI).

## 5. Limitazioni e problemi aperti
- **Nessun invio reale** di email o SMS (vincolo del mandato): le notifiche restano nell'outbox. Serve l'autorizzazione e
  un fornitore (Q-07) per la FASE 7.
- **Dati veri da chiedere ai titolari** prima dell'attivazione (Q-01…Q-05): fasce reali, capienza fisica, tavoli, orari,
  email del locale (senza, l'avviso «nuova richiesta» resta «non preparato: manca l'email del locale»).
- **Limite per IP in memoria** (come i login): si azzera al riavvio, non condiviso tra istanze; il limite per telefono è
  sul database.
- **Una sola zona** gestita dalla UI (il backend ne supporta più d'una; la scelta della zona in conferma è prevista solo
  via API `zoneId`). Le fasce si aggiungono ed eliminano; per cambiarne una si elimina e si ricrea.
- **Eventi** (FASE 6): non toccati; il dialog aperto da una serata usa gli orari normali del giorno.
- Gli screenshot e il video dimostrativo della FASE 1 non sono stati rigenerati (script allineati ai nuovi testi).
- Le schermate «Account e accessi» e «Categorie» restano proposte separate, non implementate (come da mandato).

## 6. Prossima fase (non avviata)
FASE 6 — Eventi, da avviare solo dopo l'approvazione di questa fase.
