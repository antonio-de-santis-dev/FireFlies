# Report dei test — backend e frontend

> Aggiornato in **FIX-1** (branch `fix-1`) il 21/10/2026. Seguono le FASI 8, 7, 6, 5, 4, 3 e, in fondo, la FASE 2.
> Ambiente: OpenJDK 21.0.12, Maven 3.9.11, Node 22, Playwright 1.56.1 (Chromium), Docker 29.8.2, PostgreSQL **17.11**.

## FIX 2 — Audit del calendario (21/10/2026)
| Suite | Esito |
|---|---|
| Backend unit / integrazione | 82 / 82, 353 / 353 (nuovo `CalendarOpeningIT` 4) |
| Frontend `npm run verifica` | 148 / 148 (nuovo `calendar.spec.ts` 5) |
Dettaglio in `docs/architecture/FIX2_REPORT.md` §13.

## FIX 2 — Riepilogo
21/10/2026, branch `fix2/prenotatavolo`, PostgreSQL 17 reale.

| Suite | Esito |
|---|---|
| Backend unit (`./mvnw -o verify`) | 82 / 82 |
| Backend integrazione (Testcontainers) | 349 / 349 (nuovi: `Fix2BookingIT` 10, `Fix2EventIT` 4, `MigrationIT` V13) |
| Frontend `npm run verifica` (typecheck + Playwright) | 142 superati, 1 fallito al primo giro |
| Riesecuzione `fix1.spec.ts` «Animazione iniziale» ×3 | 6 / 6 |

Il fallito (`fix1.spec.ts:24`, animazione iniziale di FIX-1) dipendeva dai tempi: il velo spariva prima che il test ne
leggesse lo stile. Corretto il test, non l'animazione. Dettaglio in `docs/architecture/FIX2_REPORT.md` §10.

## FIX-1 — Riepilogo

Suite eseguite **in sequenza** (mai backend ed e2e insieme) sul branch `fix-1`.

| Suite | Comando | Test | Esito locale |
|---|---|---|---|
| Backend unità | `cd backend && ./mvnw -o verify` | 82 (+4 `WebRequestLimiterTest`) | ✅ 82/82 |
| Backend integrazione (PostgreSQL 17 reale) | stesso comando | 334 (+14: `WebRateLimitIT` 7, `OperationsIT` +4, `MigrationIT` +1, `DatabaseConstraintsIT` +1, altri estesi) | ✅ 334/334 |
| Frontend typecheck + Playwright con backend e DB reali | `cd frontend && npm run verifica` | 136 (+25 `fix1.spec`) | ✅ 136/136 al giro completo finale |

Dettaglio delle verifiche e regressioni: `docs/architecture/FIX1_REPORT.md` §7–8. Screenshot: allegati di
`fix1.spec` e `docs/architecture/evidence/fix1/`.

### CI (GitHub Actions, branch `fix-1`, commit `4cab96c`)
| Workflow | Run | Esito |
|---|---|---|
| backend (unità + integrazione su PostgreSQL 17) | 39583870591 | ✅ |
| frontend (typecheck + Playwright con backend reale) | 39583870669 | ✅ |
| images (build senza push, controlli su segreti, Caddy, utente) | 39583870584 | ✅ |
I workflow ora partono anche sui branch `fix-*` (prima solo `main` e `fase-*`).

### Difetti trovati dai test e corretti
- `ApiSecurityMatrixIT` esteso / `OperationsIT`: `403` dello staff su percorsi lunghi diventava `500` (audit).
- `fix1.spec`: un fotogramma senza velo tra la schermata d'attesa e la home → velo presente dal primo fotogramma.
- Revisione degli screenshot: orari di apertura troncati su desktop, buco nel riquadro delle chiusure senza voci,
  riepilogo troppo alto sul telefono → corretti.
- `navigation.spec` (2 prove): si aspettavano i chip del menu come filtri → aggiornate al nuovo comportamento.

## FASE 8 — Riepilogo

Tutte le suite eseguite **in sequenza** (mai backend ed e2e insieme) sul codice del commit `9beade5`.

| Suite | Comando | Test | Esito locale |
|---|---|---|---|
| Backend unità e slice | `cd backend && ./mvnw -o verify` | 78 (invariati) | ✅ 78/78 |
| Backend integrazione (PostgreSQL 17 reale) | stesso comando | 320 (+9: `EventApiIT` +2, `ProxyHeadersIT` 3, `ApiSecurityMatrixIT` 4; `MigrationIT` → 11 migrazioni) | ✅ 320/320 al primo giro completo |
| Frontend typecheck + Playwright con backend e DB reali | `cd frontend && npm run verifica` | 111 (+18: `fase8.spec` 17, `events.spec` +1) | ✅ 111/111 al primo giro completo (7,7 min) |
| Misure di performance | `node scripts/e2e.mjs tests/perf.spec.ts` | 2 | ✅ 2/2 (JS 78.407 B, DOMContentLoaded 951 ms, titolo 2.000 ms, panoramica 165 ms, 12 chiamate senza duplicati) |
| Prova di ripristino | `infra/scripts/restore-drill.sh` | 1 scenario completo | ✅ 36 tabelle identiche, foto identica, 11 migrazioni, `check-health.sh` verificato |

### Verifiche richieste dal mandato
| # | Verifica | Dove | Esito |
|---|---|---|---|
| 1 | Evento senza ora di fine | `EventApiIT.senzaOraDiFineNeCopertiValoriRicavati`, `events.spec` «editor senza «Fine» e senza coperti» | ✅ |
| 2 | Evento senza coperti | stessi test + `EventApiIT.senzaCapienzaDelLocaleNienteRichiestePerEventi` | ✅ |
| 3 | Integrità della capienza e degli eventi esistenti | `EventApiIT` (fine e quota conservate, 16 quanti riservati), riconciliazione del registro dopo ogni IT, IT di concorrenza FASI 5–7 | ✅ |
| 4 | Filtri compatti delle Richieste sul telefono | `fase8.spec` a 360/375/390/430/768/1280/1920 | ✅ |
| 5 | Foto del menu senza perdita di informazioni | `fase8.spec` desktop e telefono, foto mancante (`route.abort`) | ✅ |
| 6 | Chiamata e copia del numero | `fase8.spec` a 390 e 1366 (`tel:+39…`, appunti letti, tastiera), senza Clipboard API | ✅ |
| 7 | Numero non configurato | `fase8.spec` (nessun `tel:`, avviso, Instagram) | ✅ |
| 8 | Locandine non ritagliate | `posters.spec` (9) | ✅ |
| 9 | Backup e ripristino | `restore-drill.sh` | ✅ |
| 10 | Sicurezza delle API | `ApiSecurityMatrixIT`, `ProxyHeadersIT`, IT di sicurezza FASI 3–7 | ✅ |
| 11 | Connessioni lente | `fase8.spec` (API rallentata, rete e CPU limitate via CDP), `perf.spec` | ✅ |
| 12 | Nessuna regressione su prenotazioni, eventi, notifiche | suite complete sopra | ✅ |

Screenshot reali: allegati ai test `fase8.spec` (`test.info().attach`) nel report HTML di Playwright e negli artifact
della CI `frontend`.

### Difetti trovati dai test e corretti
- `event-visibility.spec`: ricaricamento prima della fine del salvataggio → attesa del messaggio «salvato».
- `fase8.spec`: prima scheda delle Richieste a 552 px sul telefono → intestazione compatta (`AdminHead compact`).
- `ProxyHeadersIT`: loopback IPv6 non fidato e barre rovesciate perse in `@TestPropertySource` → configurazione corretta.
- Esecuzione con `mvnw verify` in parallelo all'e2e (jar sostituito) → errori casuali; regola: suite in sequenza.

### CI (GitHub Actions, branch `fase-8/hardening-deploy`)
| Workflow | Commit | Run | Esito |
|---|---|---|---|
| backend (ultimo commit con codice backend) | `07d6da9` | 39472456846 | ✅ |
| frontend | `9beade5` | 39473373201 | ✅ |
| images (build senza push, nessun `.env`, Caddyfile valido, utente non root) | `9beade5` | 39473373320 | ✅ |

## FASE 7 — Riepilogo

| Suite | Comando | Test | Esito locale |
|---|---|---|---|
| Backend unità e slice | `cd backend && ./mvnw -B verify` | 78 (+8 dalla FASE 6: `ImageProcessorTest` +4, `RetentionJobTest` 4) | ✅ 78/78 |
| Backend integrazione (PostgreSQL 17 reale) | stesso comando | 311 (+17) | 310/311 al primo giro completo; l'unico fallimento (isolamento: modelli dei messaggi accesi dal caricamento demo) corretto e le due classi coinvolte rieseguite ✅ |
| Frontend Playwright con backend e DB reali | `cd frontend && npm run verifica` | 93 (+24: `posters` 9, `event-visibility` 2, `fase7` 13) | 80/93 al primo giro completo, 5 falliti e 8 non eseguiti; corretti e rieseguiti i 17 test coinvolti ✅ |
| **Totale** | | **482** | ✅ suite complete verdi in CI su `f9e3a8e` (qui sotto) |

### Verifiche richieste dal mandato (req. 13)
| Verifica | Test |
|---|---|
| Pubblicazione con lo switch e persistenza dopo il ricaricamento, nuova sessione e lettura pubblica | `EventApiIT.visibileSulSitoSalvataggioAtomico`; `event-visibility.spec` «switch acceso + Salva…» (ricarica, visitatore in un contesto nuovo, elenco e calendario) |
| Nessuna bozza orfana se la pubblicazione è rifiutata | `visibileSulSitoSalvataggioAtomico` (altro esclusivo, data passata); `event-visibility.spec` «pubblicazione rifiutata» |
| Nascondere ed eliminare un evento; pulizia degli eventi | `EventApiIT.nascondereDalSitoSoloSenzaPrenotazioniAttive`; `event-visibility.spec`; `RetentionIT.eventiDopoDueMesiSenzaPrenotazioni` |
| Visibilità iniziale ed eliminazione dei prodotti | `content.spec` «nuova voce…»; `MenuApiIT.creaModificaNascondiEliminaUnaVoce` |
| «Da richiamare» spento alla conferma | `ReservationLifecycleIT.daRichiamareSiSpegneAllaConfermaEAllaChiusura` |
| Conservazione delle prenotazioni senza perdere le statistiche | `RetentionIT.prenotazioniPassateDiventanoStatisticheAnonime`, `prenotazioniFutureEInCorsoMaiToccate`, `unaSolaEsecuzioneAlGiornoAncheInParallelo`; `RetentionJobTest` (ora legale) |
| Capienza per giorno e fascia senza overbooking | `OperationsIT.capienzaPerGiornoEFasciaSulRegistro`, `capienzaConcorrenteMaiOltreIlLimite`; `fase7.spec` «capienza per fascia…» |
| Calendario di oggi | `OperationsIT.calendarioDiOggiSoloConfermate`; `fase7.spec` (7 larghezze) |
| Blocco dei giorni chiusi | `OperationsIT.giorniDiChiusuraSettimanaliEStraordinari`; `fase7.spec` «giorni chiusi…» |
| Scelta tavolo / compleanno | `fase7.spec` «sito: prima la scelta…» (con e senza telefono) |
| Navigazione su telefono e due foto per riga | `fase7.spec` menu a 360/375/390, foto a 360/375/390 |
| OWNER/STAFF, CSRF | `OperationsIT` (403 staff, CSRF), `NotificationDispatcherIT` (401, 403), `reservations.spec` «staff: la capienza la cambia solo il titolare», `fase7.spec` ordine e permessi |
| Notifiche (outbox, ritentativi, incerti, dedupe, superate, audit) | `NotificationDispatcherIT` (6), `OutboxClaimIT` (4) |
| Larghezze 360, 375, 390, 768, 1366, 1440, 1920 | `qa.spec` (7), `posters.spec` (7), `fase7.spec` «pagine nuove senza scorrimento orizzontale» e calendario di oggi |
| Regressioni | tutte le classi e gli spec precedenti, aggiornati solo dove l'interfaccia è cambiata per mandato (dialog con la scelta, richieste a schede, Impostazioni, eventi senza «Pubblica») |

### Difetti trovati dai test e corretti
1. Menu del telefono invisibile: la regola base del pulsante veniva dopo quella per i telefoni (ordine nel CSS).
2. Logo dell'area gestori compresso a 14–23 px di larghezza sotto i 900 px (`qa.spec`, target touch).
3. Una prenotazione delle 21:00 risultava nella fascia «Aperitivo» (che copre fino alle 22) invece che «Sera»: ora vale
   la fascia in cui l'orario è un arrivo (T-70).
4. La pulizia teneva i quanti del registro sovrapposti a una richiesta in attesa (che non occupa posti).
5. Isolamento dei test: i modelli dei messaggi accesi dai dati demo restavano accesi per la classe successiva.

### CI (GitHub Actions, branch `fase-7/notifiche`)
| Workflow | Commit | Run | Esito |
|---|---|---|---|
| backend | `57ad258` (checkpoint) | 39441235196 | ❌ (difetto di isolamento n. 5, corretto in `f9e3a8e`) |
| frontend | `57ad258` (checkpoint) | 39441235090 | ❌ (difetti n. 2 e selettori, corretti in `f9e3a8e`) |
| backend (`./mvnw -B verify`, 78 + 311 test) | `f9e3a8e` | 39443071752 | ✅ success |
| frontend (`npm run verifica`, 93 test) | `f9e3a8e` | 39443071652 | ✅ success |

## FASE 6 — Riepilogo

| Suite | Comando | Test | Esito |
|---|---|---|---|
| Backend unità e slice | `cd backend && ./mvnw -B verify` | 70 | ✅ 70/70 |
| Backend integrazione, sicurezza, concorrenza, riavvio (PostgreSQL reale) | stesso comando | 294 (+29) | ✅ 294/294 |
| Frontend Playwright con backend e DB reali (funzionali 26 + accesso 10 + contenuti 12 + prenotazioni 7 + eventi 3 + QA 9 + axe 2) | `cd frontend && npm run verifica` | 69 (+3) | ✅ 69/69 |
| **Totale** | | **433** | ✅ 433/433 |

**Regressioni FASI 2–5**: tutti i test precedenti presenti e verdi. Attese cambiate per effetto voluto: `DemoContentLoaderIT`
verifica anche gli eventi d'esempio e i 14 quanti riservati alla serata demo; in `navigation.spec` il link della serata
demo si cerca nella riga dell'elenco (la data ora è reale, fra almeno due settimane, quindi spesso fuori dal mese del
calendario).

### Verifiche della FASE 6

| Verifica | Test | Esito |
|---|---|---|
| Creazione, modifica, pubblicazione, annullamento | `EventApiIT.bozzaValidazioniEPubblicazione` (validazioni, slug univoco, bozze non pubbliche, doppio clic, eliminazione solo bozze), `modificaDiUnEventoPubblicato`, `annullamentoDellEvento`, `annullamentoSenzaToccareLePrenotazioni`; `events.spec` «nuova serata… pubblicata e annullata» | ✅ |
| Locandine dalla libreria | `EventApiIT.locandinaEFotoDallaLibreria` (varianti e `srcset`, foto in uso non eliminabile, foto inesistente `422`) | ✅ |
| Prenotazioni per evento con conferma manuale e quota | `EventApiIT.prenotazioniPerEventoConQuota` (in attesa senza posti, conferma su registro e quota, quota superata `409`, `EVENT_FULL` dal sito, orario fisso, persone modificabili, quota restituita); `events.spec` «il cliente chiede un tavolo per l'evento, lo staff lo conferma» | ✅ |
| Esclusivi per impostazione predefinita | `bozzaValidazioniEPubblicazione` (12 quanti riservati, servizio ordinario bloccato dal sito e negli orari pubblici) | ✅ |
| Non esclusivi solo dal titolare, condivisione sicura | `permessiECsrf` (staff `403`), `modificaDiUnEventoPubblicato`, `eventoNonEsclusivoUsaLoStessoRegistro` (quota 15 ma locale da 20 con 10 ordinarie: la conferma che supererebbe 20 è rifiutata, avviso alla pubblicazione); `events.spec` «posti condivisi» | ✅ |
| Conflitti con le prenotazioni ordinarie confermate | `conflittiConLePrenotazioniOrdinarie` (confermata → `409` non forzabile senza dati personali; in attesa → da confermare; dopo la pubblicazione non confermabile; altro evento esclusivo); `events.spec` (riferimento mostrato nell'editor) | ✅ |
| Coerenza con il registro a 15 minuti | riconciliazione dopo **ogni** test: registro = prenotazioni, quota eventi = prenotazioni confermate dell'evento, nessun quanto esclusivo con ordinarie | ✅ |
| Concorrenza | `EventConcurrencyIT`: pubblicazione esclusiva contro conferma ordinaria nello stesso orario (10 ripetizioni, vince una sola), 6 conferme simultanee da 3 persone su quota 10 → esattamente 3 (5 ripetizioni), annullamento dell'evento contro una conferma (5 ripetizioni); nessun deadlock | ✅ |
| OWNER/STAFF, CSRF, audit | `permessiECsrf`, `annullamentoDellEvento` (staff `403` con prenotazioni attive), audit `EVENT_*` | ✅ |
| Regressioni | tutte le classi e gli spec precedenti | ✅ |

### Difetti trovati dai test e corretti
1. Una bozza nuova partiva con «Richiedi un tavolo» senza quota: il backend (e il vincolo del database) la rifiuterebbe.
   Ora una bozza nuova parte senza prenotazione e il gestore sceglie (trovato rileggendo `navigation.spec`).
2. Due test cercavano la serata demo nel calendario del mese corrente, valido solo con la data finta del mockup.

### CI (GitHub Actions, branch `fase-6/eventi-capienza`)
| Workflow | Commit | Run | Esito |
|---|---|---|---|
| frontend (`npm run verifica`, 69 test) | `91850b1` (ultimo commit con codice frontend) | 39402045306 | ✅ success (6m37s) |
| backend (`./mvnw -B verify`, 70 + 294 test) | `1900a87` — il codice backend non cambia dopo questo commit | 39400310406 | ✅ success (4m10s) |

Anche i run intermedi della fase (`9ed8490`, `1900a87`) sono verdi su entrambe le workflow. I commit successivi
toccano solo la documentazione.

## FASE 5 — Riepilogo

| Suite | Comando | Test | Esito |
|---|---|---|---|
| Backend unità e slice | `cd backend && ./mvnw -B verify` | 70 (+4) | ✅ 70/70 |
| Backend integrazione, sicurezza, concorrenza, riavvio (PostgreSQL reale) | stesso comando | 265 (+43) | ✅ 265/265 |
| Frontend Playwright con backend e DB reali (funzionali 26 + accesso 10 + contenuti 12 + prenotazioni 7 + QA 9 + axe 2) | `cd frontend && npm run verifica` | 66 (+6) | ✅ 66/66 |
| **Totale** | | **401** | ✅ 401/401 |

**Regressioni FASI 2–4**: tutti i test precedenti sono presenti e verdi. Attese cambiate per effetto voluto e dichiarate:
`DemoContentLoaderIT` verifica anche la configurazione demo delle prenotazioni; `PersistenceAfterRestartIT` aggiunge una
prenotazione confermata e i posti occupati dopo il riavvio; in Playwright il flusso «prenotazione in 6 passi… conferma
simulata» è diventato «richiesta tavolo in 5 passi… invio reale «in attesa»», il campo «Telefono o email» è diviso in
«Telefono» ed «Email», i due test sulle richieste dimostrative (cambio di stato con SMS simulato) sono sostituiti da
`reservations.spec.ts`.

### Verifiche obbligatorie della FASE 5

| Verifica | Test | Esito |
|---|---|---|
| Creazione e validazione | `WebReservationIT.richiestaValidaRestaInAttesaENonOccupaPosti` (PENDING, telefono E.164, nessun posto occupato, storico, audit, notifiche senza note del cliente), `validazioniDeiCampi`, `regoleDiPrenotabilita` (arrivo fuori fascia, giorno senza fasce, persone, troppo lontano, anticipo minimo, chiusura, richieste chiuse), `orariPrenotabiliDelGiorno`; `navigation.spec` «richiesta tavolo in 5 passi»; `reservations.spec` «il cliente chiede un tavolo…» | ✅ |
| Conferma e rifiuto | `ReservationLifecycleIT.confermaOccupaIPostiEPreparaLeNotifiche` (quanti occupati esattamente 19:00–21:00, doppio clic senza effetti, versione vecchia `409`), `rifiutoEAnnullamento` (motivo obbligatorio, stato finale); `reservations.spec` conferma e rifiuto dalla UI | ✅ |
| Modifica e annullamento | `ReservationLifecycleIT.modificaDiUnaConfermata` (posti insufficienti → nulla cambia; spostamento libera e rioccupa), `rifiutoEAnnullamento` (posti liberati, notifica di conferma superata), `arrivoAssenzaECorrezioneDelTitolare` (dopo l'inizio non si annulla, uscita anticipata, non presentato, correzione solo titolare entro 48 h); `reservations.spec` «rifiuto con motivo e annullamento: storico e posti liberati» | ✅ |
| Capienza e sovrapposizioni orarie | `ReservationLifecycleIT.capienzaESovrapposizioni` (18–20 e 19–21 non stanno in 15 posti, 20–22 sì; posti liberi minimi nel dettaglio; posti del giorno; capienza sotto i confermati `409`), `WebReservationIT.orarioCompletoNelRegistroBloccaLaRichiesta` (`FULLY_BOOKED` e orari barrati); `CapacityLedgerIT`, `CapacityConcurrencyIT` (FASE 2) | ✅ |
| Prenotazioni simultanee | `ReservationConcurrencyIT` (5 ripetizioni ciascuno): 8 conferme simultanee da 4 persone su 20 posti → esattamente 5; due gestori confermano/rifiutano la stessa richiesta → un solo cambio di stato e una sola notifica; stesso tavolo a due prenotazioni sovrapposte → una sola assegnazione, errore pulito, nessun deadlock; doppio invio simultaneo dal sito → una sola richiesta; conferma contro scadenza nello stesso istante. **Riconciliazione registro = prenotazioni dopo ogni test** (`Reconciliation`) | ✅ |
| Autorizzazioni e CSRF | `ReservationLifecycleIT.permessiECsrf` (anonimo `401`, staff legge la configurazione ma non la cambia `403`, token CSRF inventato `403`), `WebReservationIT.campoNascostoCompilatoEScrittureSenzaCsrf` (anche la richiesta pubblica senza token → `403`), `tavoliAssegnatiAMano` (eccezione sui posti solo titolare); `reservations.spec` «staff: vede i posti del giorno ma non può cambiare la configurazione» | ✅ |
| Duplicati e anti-spam | `WebReservationIT.idempotenzaEDuplicati` (stesso invio → stessa risposta e una riga; stessa chiave con dati diversi `422`; stesso telefono e orario `409`; chiave mancante o non valida), `campoNascosto…` (honeypot), `limitiPerIndirizzoEPerTelefono` (3 al giorno per telefono, 5 all'ora per indirizzo, `429` con `retryAfterSeconds`) | ✅ |
| Scadenza delle richieste | `WebReservationIT.richiesteNonGestiteScadonoPrimaDellArrivo` (orologio dei test spostato: REJECTED/EXPIRED una sola volta, notifica e audit) | ✅ |
| Persistenza dopo il riavvio | `PersistenceAfterRestartIT`: due avvii reali; la prenotazione confermata e i suoi posti (orario «completo» nelle opzioni pubbliche) sopravvivono; `reservations.spec` ricarica la pagina dopo la conferma | ✅ |
| Notifiche simulate ≠ consegnate | `confermaOccupaIPostiEPreparaLeNotifiche` (email `PENDING`, SMS `SKIPPED/SMS_DISABLED`, `delivered` falso, nessun tentativo d'invio); `reservations.spec` «Email preparata, non inviata», «SMS non previsto (canale spento)», nessuna «Inviata» | ✅ |
| Nessuna assegnazione automatica dei tavoli | `tavoliAssegnatiAMano` (dopo due conferme nessuna riga in `reservation_table_assignments`) | ✅ |
| Regressioni FASI 2, 3 e 4 | tutte le classi precedenti (capacità, vincoli DB, migrazioni, autenticazione, account, menu, foto, contenuti, demo) e gli spec `auth`, `content`, `navigation`, `qa`, `a11y` | ✅ |
| Playwright con frontend, backend e database reali | `scripts/e2e.mjs` + `reservations.spec.ts` (7 test: richiesta dal sito → conferma dello staff, rifiuto e annullamento con posti, inserimento da telefono, conflitto tra due gestori, tavolo creato dal titolare e assegnato con eccezione, permessi dello staff, richieste chiuse) | ✅ |

### Difetti trovati dai test e corretti
1. **Interruttore «Richieste tavolo attive» non salvava** (trovato da `reservations.spec`): la UI rimandava anche `id` ed
   `effectiveFrom` della regola e il backend, che rifiuta i campi non previsti (`fail-on-unknown-properties`), rispondeva
   `400`. Ora si inviano solo i campi della regola.
2. **Sezioni della home che potevano restare invisibili** (trovato da `qa.spec` «movimento attivo»): l'animazione
   d'ingresso registrava le sezioni 30 ms dopo il cambio di pagina; se i contenuti dal backend arrivavano più tardi, le
   sezioni non venivano mai osservate e restavano trasparenti per chi non ha il movimento ridotto. Esisteva già dalla
   FASE 4 e dipendeva dai tempi di risposta. Ora la registrazione si ripete quando i contenuti sono pronti.
3. **Contrasto dei pulsanti piccoli** (axe): i nuovi pulsanti fucsia in formato compatto avevano 4,48:1; usata la
   variante `btn--action` del design system (5,96:1), nessun colore cambiato.
4. **Area di tocco del telefono** nelle righe delle richieste a 360–390 px (23 px): portata a 24 px.
5. Nei test backend: la versione iniziale di una riga è 0, non 1 (attesa del test corretta); i limiti per IP vanno
   azzerati tra i blocchi di prova di `regoleDiPrenotabilita` perché ogni tentativo conta (comportamento voluto).

### CI (GitHub Actions, branch `fase-5/prenotazioni`)
| Workflow | Commit | Run | Esito |
|---|---|---|---|
| frontend (`npm run verifica`, 66 test) | `8008f78` (ultimo commit con codice) | 39395689994 | ✅ success (6m35s) |
| backend (`./mvnw -B verify`, 70 + 265 test) | `c344211` — il codice backend non cambia dopo questo commit; la workflow parte solo su `backend/**` | 39392817517 | ✅ success (3m14s) |

Run intermedi falliti e già risolti: `7c67958` backend (test non compilabile: `DemoContentLoaderIT` usava il vecchio
costruttore, corretto in `b991754`) e frontend; `c344211` frontend (contrasto, area di tocco e animazione d'ingresso,
corretti in `8008f78`, difetti 2–4 sopra).

## FASE 4 — Riepilogo

| Suite | Comando | Test | Esito |
|---|---|---|---|
| Backend unità e slice | `cd backend && ./mvnw -B clean verify` | 66 | ✅ 66/66 |
| Backend integrazione, sicurezza, concorrenza, riavvio (PostgreSQL reale) | stesso comando | 222 | ✅ 222/222 |
| Frontend Playwright con backend e DB reali (funzionali 27 + accesso 10 + contenuti 12 + QA 9 + axe 2) | `cd frontend && npm run verifica` | 60 | ✅ 60/60 (5,3 min) |
| **Totale** | | **348** | ✅ 348/348 |

CI su GitHub Actions dell'ultimo commit: vedi §«CI» in fondo a questa sezione.

**Nessuna regressione**: i 282 test della FASE 3 sono presenti e verdi. Attese cambiate per effetto voluto e dichiarate:
`MigrationIT` conta 9 migrazioni (V9); `UserAdministrationIT.staffAutenticatoRaggiunge…` ora si aspetta `200` su
`/admin/menu` (prima `404`: l'endpoint non esisteva) e verifica anche `403` su `site-content`/`site-settings`; i test
Playwright che modificano dati li ripristinano alla fine (`tests/baseline.ts`) e trovano le voci per nome (gli id sono UUID).

### Verifiche obbligatorie della FASE 4

| Verifica | Test | Esito |
|---|---|---|
| Autorizzazioni OWNER/STAFF | `MenuApiIT.permessi`, `MediaApiIT.senzaAccesso…`, `ContentApiIT.contenutiEImpostazioniSoloAlTitolare` (staff `403` su testi e orari, anonimo `401`), `UserAdministrationIT`; `content.spec` «staff: testi e orari… anche via API» e «foto in uso… aggirando la UI»; `auth.spec` | ✅ |
| CRUD del menu | `MenuApiIT.categorie` (slug dal nome, duplicato `409`, ordine, categoria non vuota `409`), `creaModificaNascondiEliminaUnaVoce` (versione vecchia `409`, voce nascosta fuori dal menu pubblico), `categorieNascoste…`; `content.spec` «nuova voce: creata, visibile, eliminata»; `navigation.spec` «menu: nascondere una voce…» | ✅ |
| Validazione prezzi | `MenuApiIT.validazioneDeiPrezzi` (negativo, oltre 1.000 €, `6.5` → `400` e non 6 centesimi, testo → `400`, 0 e 1.000 € ammessi, prezzo demo non scrivibile); `content.spec` «prezzo non valido» (`-3`, `2,505`, `1500`) | ✅ |
| Validazione allergeni | `MenuApiIT.allergeniDichiaratiSoloDopoLaVerifica` (codici senza verifica `422`, codice inesistente `422`, ordine del Regolamento, autore e data della verifica, «nessun allergene» ≠ «da dichiarare»); `content.spec` «staff: prezzo e allergeni salvati» | ✅ |
| Caricamento foto | `ImageProcessorTest` (13: formati, EXIF/GPS rimossi, orientamento, PNG8 con trasparenza, JPEG progressivo, mai ingrandite, WebP reali), `MediaApiIT.staffCaricaUnaFoto…`, `fileNonValidi…` (finto JPEG, senza testo alternativo, decompression bomb), `oltreCinqueMegabyte…` (`413`); `navigation.spec` «foto: caricamento…», `content.spec` «file finto anche se si chiama .jpg» | ✅ |
| Sostituzione foto | `MediaApiIT.sostituzioneMantieneIdEUsi…` (stesso id e testo, file vecchi cancellati); `content.spec` «sostituzione del file, la foto cambia ovunque» | ✅ |
| Eliminazione foto | `MediaApiIT.fotoInUsoNonSiElimina…` (`409 MEDIA_IN_USE` con usi, chiave esterna anche aggirando l'API, eliminazione e file rimossi), `ContentApiIT` (foto di titolare e testata bloccate); `navigation.spec` | ✅ |
| Persistenza dopo il riavvio | `PersistenceAfterRestartIT`: **due avvii reali** dell'applicazione sullo stesso PostgreSQL e sulla stessa cartella foto (menu, prezzo, allergeni, foto servita, avviso e perfino la sessione del gestore); `navigation.spec` «menu…» con ricaricamento della pagina; `content.spec` «visibili a un altro visitatore» | ✅ |
| Errori del servizio di storage | `MediaApiIT.storageIrraggiungibileAlCaricamento503…` (nessuna riga, `Retry-After`), `…InSostituzioneLasciaLaFotoVecchia`, `cancellazioneConStorageGuastoRiprovataDalJob` (orfani, nuovi tentativi, pulizia); `S3MediaStorageTest` (errore del provider ed endpoint irraggiungibile → `StorageException`); `MediaConfigurationTest` (configurazioni mancanti bloccano l'avvio) | ✅ |
| Visualizzazione responsive | `qa.spec` su 7 larghezze (360, 375, 390, 768, 1366, 1440, 1920) con i contenuti dal backend: nessun overflow orizzontale, nessuna immagine rotta, nessun errore console/rete, target ≥ 24 px; `a11y.spec` (axe) desktop e mobile | ✅ |
| Regressioni FASE 3 | tutti i test di `AuthenticationIT`, `UserAdministrationIT`, `OwnerBootstrapIT`, `auth.spec` | ✅ |
| Compilazione backend e frontend | `./mvnw -B clean verify`; `npm run typecheck`, `vite build`, `vite build --mode static` (pagina statica aperta da `file://`: 14 voci, nessun errore) | ✅ |
| Playwright con backend reale | `scripts/e2e.mjs`: PostgreSQL in Docker, backend con dati demo e cartella foto temporanea, password casuali | ✅ |

### Altre verifiche
- Sessione scaduta in una scheda aperta: salvataggio → accesso con avviso; ritorno sulla scheda → accesso (`content.spec`, 2 test).
- Modifica concorrente dei contenuti: chi salva per secondo riceve «Qualcun altro ha salvato…» e non sovrascrive (`ContentApiIT`, `content.spec`).
- Letture pubbliche con ETag: `304` con `If-None-Match`, ETag nuovo subito dopo una modifica, area gestori `no-store` (`MenuApiIT`).
- `srcset`/`sizes`: una miniatura da 104 px scarica la variante da 480 px; testata del menu `sizes="100vw"` (`content.spec`).
- Dati demo: caricatore assente nel profilo `test`, una sola volta su DB vuoto, 0 prezzi reali e 0 allergeni dichiarati inventati, database marcato; `DemoDataGuard` blocca `prod` (`DemoContentLoaderIT`, `DemoDataGuardTest`).
- Bundle di produzione del frontend senza i testi del menu e degli avvisi demo (ricerca nel file JS generato).
- OpenAPI `draft.4` valido (`npx @redocly/cli@2.1.0 lint`: 0 errori, 0 avvisi).
- Scansione segreti: secretlint 9.3.4 (preset recommend) su `backend/`, `frontend/src`, `frontend/tests`, `frontend/scripts`, `docs/`, `infra/`: **0 segnalazioni**; controprova con una chiave AWS finta in un file esca: rilevata.

### Difetti trovati dai test e corretti
1. **Dati vecchi dopo una modifica**: il browser riusava per 60 s la copia in cache di `/public/menu` e `/public/site`
   (`Cache-Control: max-age=60`), quindi ricaricando la pagina un gestore vedeva ancora il prezzo precedente. Ora il
   frontend rivalida sempre le letture pubbliche con l'ETag (`304` senza corpo se nulla è cambiato).
2. **Etichetta «Segnaposto» su una voce confermata**: una voce demo a cui lo staff dava un prezzo vero restava marcata
   come demo. Con un prezzo reale la voce ora è trattata come confermata.
3. **PNG trasparenti troppo pesanti** (~500 KB): introdotta la riduzione a 256 colori con un encoder PNG dedicato (~140 KB).
4. **Test intermittente della testata del menu** («foto che ruotano ogni 60 s»): `page.clock.install()` lascia scorrere il
   tempo simulato anche durante il caricamento della pagina; dalla FASE 4 la rotazione parte quando arrivano i dati dal
   backend e, con la macchina carica (suite backend in parallelo), il caricamento superava il margine di 1 s del test
   (58/59 in quell'esecuzione). L'app è corretta (il cambio avviene 60 s dopo l'avvio del timer): il test ora verifica
   «ferma a 55 s, cambiata entro 65 s». Esecuzione successiva completa: 59/59.
5. **Due modifiche ravvicinate a una foto si annullavano** (difetto reale, trovato dalla CI). Run frontend
   `39279505974` su `3e80851`: 58 superati, 1 fallito — `navigation.spec.ts` riga 302, «foto: caricamento con controlli,
   uso nel menu e in galleria», timeout di 90 s in attesa di `.picker__opt` con «Nuovo cocktail della casa».
   **Causa**: nella pagina Foto il testo alternativo si salva all'uscita dal campo e «In galleria» al clic, quindi
   partono due `PATCH` quasi insieme; il frontend sostituiva l'intera foto con ogni risposta. Se il server applicava
   prima la galleria (con la descrizione ancora vecchia) e quella risposta arrivava per ultima, lo stato del browser
   tornava alla descrizione vecchia (il database era corretto): il selettore del menu non trovava la foto. Il test era
   rimasto identico nelle esecuzioni verdi successive, che sono passate per l'ordine favorevole delle risposte.
   **Correzione** (`665d840`): ogni risposta aggiorna solo il campo che ha modificato (`media/merge`).
   **Verifica**: nuovo test `content.spec` «descrizione e galleria modificate di seguito…» che forza l'ordine sfavorevole
   con `page.route` — **fallisce sul codice precedente** (selettore del menu con la descrizione vecchia) e passa con la
   correzione; il test storico ora attende la conferma «Descrizione salvata» e sceglie la foto per ruolo e nome
   accessibile (`radio`), non per testo. Nessun timeout aumentato.
6. **Upload di prova S3** rifiutato dal finto endpoint: l'SDK usava il caricamento «aws-chunked», non accettato da R2;
   disattivato (corpo intero firmato).

### CI
| Workflow | Commit | Run | Esito |
|---|---|---|---|
| frontend (`npm ci`, Playwright Chromium, `npm run verifica` con backend e PostgreSQL reali) | `665d840` (ultimo commit con codice, correzione della gara) | 39386676958 | ✅ success |
| frontend | `0b1a065` | 39280557623 | ✅ success |
| frontend | `6406858` (ultima correzione di codice dei test) | 39280092627 | ✅ success |
| backend (`./mvnw -B verify`) | `3e80851` (ultimo push con codice backend: `ac9c5a3`) | 39279505939 | ✅ success |

| frontend | `3e80851` | 39279505974 | ❌ failure storica — 58/59, `navigation.spec.ts:302` (vedi difetto 5): causa individuata e corretta in `665d840` |

I commit successivi a `3e80851` non toccano `backend/`, quindi il workflow backend (filtrato per percorso) non riparte.

---

## FASE 3 — Riepilogo

| Suite | Comando | Test | Esito |
|---|---|---|---|
| Backend unità e slice | `cd backend && ./mvnw -B clean verify` | 40 | ✅ 40/40 |
| Backend integrazione, sicurezza, concorrenza (PostgreSQL reale) | stesso comando | 194 | ✅ 194/194 |
| Frontend Playwright con backend e DB reali (funzionali 27 + accesso 10 + QA 9 + axe 2) | `cd frontend && npm run verifica` | 48 | ✅ 48/48 |
| **Totale** | | **282** | ✅ |

Gli stessi comandi girano anche in CI su GitHub Actions (`ubuntu-24.04`): workflow `backend` e `frontend` **verdi** sul branch `fase-3/autenticazione`.

**Nessuna regressione**: i 190 test della FASE 2 sono tutti presenti e verdi. Due attese sono cambiate per effetto voluto della sicurezza
e sono dichiarate qui: `ApplicationIT.endpointActuatorNonPubbliciChiusi` ora si aspetta `401` invece di `404` (gli endpoint actuator
non pubblici sono chiusi dalla sicurezza prima ancora di cercarli) e `MigrationIT` si aspetta 8 migrazioni invece di 7 (V8: sessioni).
I 38 test del mockup (27 funzionali + 9 QA + 2 axe) girano ora contro il backend reale: cambiati solo i passi di accesso
(email e password vere al posto della scelta del ruolo).

### Verifiche obbligatorie della FASE 3

| Verifica | Test | Esito |
|---|---|---|
| Credenziali corrette e scorrette | `AuthenticationIT` (corrette, maiuscole/spazi, password errata = email inesistente = account disattivato), `auth.spec` «credenziali sbagliate» | ✅ |
| Sessioni scadute | `AuthenticationIT.sessioneScadutaPerInattivita` (9 h di inattività → `401 SESSION_EXPIRED`), `timeoutDellaSessioneDiOttoOre`, `auth.spec` «sessione scaduta» (ritorno all'accesso con avviso) | ✅ |
| Logout | `AuthenticationIT.logoutChiudeLaSessione…` (anche un cookie rubato non vale più, sessione cancellata dal DB), `auth.spec` «esci» | ✅ |
| CSRF | login senza token / con token falso → `403 CSRF_INVALID`; scritture autenticate senza token → `403`; token rinnovato dopo il login; `auth.spec` richiesta dal browser senza header → `403` | ✅ |
| Accesso anonimo agli endpoint riservati | `/auth/me`, `/admin/users`, `/admin/menu`, percorsi sconosciuti → `401`; `auth.spec` pagina riservata → login, API → `401` | ✅ |
| Ruoli OWNER e STAFF | `UserAdministrationIT` (staff `403` + audit `ACCESS_DENIED`, titolare `200`; staff autorizzato sull'area gestori ma non su quella del titolare), `auth.spec` titolare/staff, anche chiamando le API aggirando la UI | ✅ |
| Tentativi ripetuti | 5 errori → `429` con `Retry-After` anche con password giusta; blocco persistente dopo un riavvio; sblocco a scadenza; email inesistente limitata allo stesso modo; limite per IP (20); `LoginAttemptLimiterTest`; `auth.spec` «troppi tentativi» | ✅ |
| Creazione del primo OWNER | `OwnerBootstrapIT` (una sola volta, dati non validi, due procedure in parallelo → un solo titolare, procedura da riga di comando con variabile d'ambiente, password come argomento rifiutata) + prova manuale **interattiva** in un terminale (password non visibile, uscita 0) | ✅ |
| Impedimento dell'assegnazione arbitraria dei ruoli | login con campo `role` → `400`; nessuna scelta di ruolo nella pagina (`auth.spec`); staff che prova a promuoversi o a creare un titolare → `403`; ruolo cambiato dal titolare → sessioni dell'account chiuse | ✅ |
| Nessuna regressione dei 190 test precedenti | vedi sopra | ✅ |

### Altre verifiche di sicurezza
- Password salvate come `{bcrypt}$2a$12$…`; nessun hash nelle risposte; né password né email tentate nell'audit.
- Id di sessione imposto da un attaccante non adottato (session fixation).
- Cookie `FF_SESSION` `HttpOnly`, `SameSite=Lax`, non leggibile da JavaScript (verificato anche nel browser).
- Intestazioni `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy` con `frame-ancestors 'none'`, `Cache-Control: no-store`.
- Ultimo titolare non rimovibile; versione vecchia → `409`; reset e cambio password chiudono le sessioni.
- Scansione segreti (secretlint) e prova reale del flusso locale documentato (procedura interattiva, backend, `npm run dev`, login attraverso il proxy di Vite).

### Difetto trovato dai test end-to-end e corretto
La procedura del primo titolare rifiutava anche la variabile d'ambiente `FIREFLIES_BOOTSTRAP_PASSWORD`: Spring la espone anche come
proprietà `fireflies.bootstrap.password`, che il controllo «niente password negli argomenti» bloccava. Il test Java non lo vedeva perché
simulava la variabile con una proprietà di sistema. Corretto il controllo (solo argomenti della riga di comando) e il test, che ora usa
una vera sorgente di variabili d'ambiente.

---

# Report FASE 2 (storico)

> Eseguito il **19/10/2026** nel container di sviluppo: OpenJDK 21.0.12, Maven 3.9.11 (wrapper), Docker 29.8.2, PostgreSQL **17.11** (`postgres:17-alpine@sha256:b0f9560a…`) avviato da Testcontainers 2.0.5.
> Comando: `cd backend && ./mvnw -B clean verify` → **BUILD SUCCESS** in 39 s. Tutti i numeri qui sotto vengono da quella esecuzione.

## 1. Riepilogo

| Fase Maven | Test | Esito |
|---|---|---|
| `test` (Surefire, senza Docker) | 30 | ✅ 30 superati, 0 falliti, 0 saltati |
| `integration-test` (Failsafe, PostgreSQL 17 reale) | 160 (con le ripetizioni) | ✅ 160 superati, 0 falliti, 0 saltati |
| **Totale** | **190** | ✅ |

Dopo **ogni** test di integrazione gira la riconciliazione (TEST_STRATEGY §3.4): registro di occupazione = prenotazioni per ogni quanto, nessun quanto oltre capienza, nessun evento esclusivo con ordinarie, quote eventi coerenti, assegnazioni tavolo coerenti. Nessuna differenza rilevata.

## 2. Dettaglio per classe

| Classe | Test | Cosa verifica |
|---|---|---|
| `OccupancyIntervalTest` | 6 | quanti da 15 min, fine esclusa, intervalli oltre mezzanotte, durata reale al cambio d'ora (25/10: 01:00–03:00 = 3 h, 12 quanti), rifiuto orari non allineati e durate non valide |
| `CapacityLedgerDeltasTest` | 5 | differenze nette per spostamenti, cambio persone, cambio zona; ordine deterministico delle zone; arrotondamento al quanto |
| `BusinessTimeTest` | 4 | Europe/Rome ora legale/solare; 29/03/2026 02:30 → `422 INVALID_LOCAL_TIME`; 25/10/2026 02:30 → +02:00 |
| `GlobalExceptionHandlerTest` | 9 | problem+json: 400 JSON malformato, 422 con errori per campo, 409 con contesto, vincolo tavolo → `TABLE_CONFLICT` senza nome interno, vincolo sconosciuto generico, deadlock → `409 CONCURRENT_UPDATE`, 500 senza dettagli, 404 e 405 con codice |
| `NotificationGatewaysConfigurationTest` | 6 | solo gateway finti; provider reale con profilo `test`/`local` → avvio bloccato; anche in `prod` nessun adattatore reale in V1; email idem; il fake rispetta la chiave di idempotenza |
| `ApplicationIT` | 4 | avvio completo su PostgreSQL; health e readiness UP; dettagli health ed endpoint actuator non pubblici chiusi; bean SMS/email finti |
| `MigrationIT` | 5 | PostgreSQL 17 + `btree_gist`; 7 migrazioni applicate e valide; **migrazione su un database completamente vuoto** (e seconda esecuzione senza effetti); vincoli e indici delle invarianti presenti; dati iniziali solo confermati o spenti |
| `DatabaseConstraintsIT` | 11 | violazioni volute: capienza dei quanti, allineamento, conferma automatica vietata, coerenza prenotazioni (allineamento, telefono, zona obbligatoria se confermata, privacy web), doppio invio web bloccato ma non per il gestore, stesso tavolo sovrapposto vietato (adiacenze ammesse, tavolo liberato riutilizzabile), eccezione posti senza motivo, quota evento, foto in uso non cancellabile, outbox in invio senza lease, eccezioni di calendario uniche |
| `CapacityLedgerIT` | 11 | caso guida 19–21/20–22 (seconda rifiutata con `firstFullSlot` 20:00 e posti residui), capienza piena, annullamento, spostamento e modifica persone/durata, modifica che non sta (nulla cambia), cambio zona, uscita anticipata, riduzione capienza sotto i confermati (rifiutata, nulla cambia) e sopra (applicata), quanti di evento esclusivo, zona spenta, uso solo in transazione |
| `CapacityConcurrencyIT` | 95 | C-2 ×20, C-3 ×5 (60 conferme casuali), C-5 ×20, C-6 ×20, C-7 ×20, C-8 ×5, misto conferme/annullamenti/modifiche ×5 |
| `TableAssignmentConcurrencyIT` | 30 | con lock della riga tavolo ×20 (una sola assegnazione, errore `23P01` pulito, 0 deadlock); senza lock ×10 (il vincolo impedisce comunque la doppia assegnazione) |
| `OutboxClaimIT` | 4 | chiave di business unica; 4 worker su 200 messaggi senza doppie prese in carico; worker con lease scaduto non registra l'esito; tentativo registrato prima della chiamata con vincolo di coerenza esito/fine |

## 3. Verifiche obbligatorie richieste per la FASE 2

| Verifica | Esito | Evidenza |
|---|---|---|
| Nessun superamento della capienza configurata | ✅ | `CapacityLedgerIT`, `CapacityConcurrencyIT` (C-2, C-3, C-5, C-6, C-7), riconciliazione dopo ogni test, `ck_slot_not_overbooked` |
| Nessuna doppia assegnazione dello stesso tavolo | ✅ | `DatabaseConstraintsIT`, `TableAssignmentConcurrencyIT` (30 gare) |
| Coerenza dei contatori dopo annullamenti e modifiche | ✅ | `CapacityLedgerIT` (annullamento, spostamento, persone, zona, uscita anticipata), test misto ×5, riconciliazione |
| Compatibilità PostgreSQL/Flyway | ✅ | Flyway 12.4.0 su PostgreSQL 17.11: nessun avviso nel log, `validate` OK |
| Validazione delle migrazioni da database vuoto | ✅ | `MigrationIT.migrazioneDaUnDatabaseCompletamenteVuoto` (7 migrazioni, seconda esecuzione 0) |
| Nessun segreto nel repository | ✅ | secretlint 9.3.4 su `backend/`, `infra/`, `docs/`: 0 segnalazioni (controprova: rileva un file esca con chiavi finte); `infra/.env` ignorato da git |
| Nessuna dipendenza inutile | ✅ | 7 dipendenze di runtime e 4 di test, tutte usate (DECISION_LOG T-02, FASE2_REPORT §1.4); `dependency:analyze` segnala solo i noti falsi positivi degli starter |
| Nessun provider SMS reale in esecuzione | ✅ | `NotificationGatewaysConfigurationTest`, `ApplicationIT.nessunProviderRealeDiNotifiche` |
| Test automatici realmente eseguiti | ✅ | questo report, `backend/target/*-reports` |

## 4. Verifiche manuali
- **Avvio locale reale**: `docker compose up -d` + `./mvnw spring-boot:run` → migrazioni applicate, `GET /actuator/health` = `UP`, percorso inesistente → `404` problem+json in italiano.
- **Spike SQL** delle FASI 1 (`docs/architecture/evidence/`) ora coperti da test Java sul codice reale.

## 5. Limiti
- La CI GitHub Actions (`.github/workflows/backend.yml`) ha eseguito la stessa suite su `ubuntu-24.04` dopo il push: **verde** (run 39241713834, job `verify` in 1 min 9 s). I test non dipendono quindi da questo container.
- I test di concorrenza riducono ma non eliminano la probabilità di gare rarissime non osservate: per questo restano i vincoli dichiarativi del database.
- Non ci sono ancora endpoint applicativi: i test API completi (permessi, CSRF, rate limit) arrivano con la FASE 3.
