# FIX-1 — Report (correzioni UX/UI, prenotazioni, impostazioni)

> Branch di collaudo `fix-1`, creato da `main` @ `395dcf5` (= FASE 8). 21/10/2026. **Nessun merge in `main`**, nessun
> force push, nessun deploy, nessuna risorsa cloud, nessun dato del committente cancellato. Decisioni T-78…T-87 e C-33
> nel DECISION_LOG; flusso di prenotazione ricostruito dal codice in `BOOKING_FLOW_AUDIT.md`.
> **In attesa del collaudo manuale del titolare.**

## 1. Analisi iniziale
- `main` conteneva tutto il lavoro delle FASI 0–8 (CI verde su `395dcf5`); stato Git pulito, nessun `fix-1` esistente.
- Letti codice e documenti (MASTER_PLAN, DECISION_LOG, BOOKING_RULES, DATA_MODEL, SECURITY_PRIVACY, API_CONTRACT,
  TEST_REPORT, FASE8_REPORT) e il rendering reale con Playwright (screenshot prima delle modifiche).
- **Riferimenti mancanti**: gli screenshot citati nel mandato (sezione «Perché FireFlies», navbar dei gestori) non sono
  disponibili in questo ambiente: non li ho esaminati. Ho analizzato il rendering reale. Inoltre la sezione «Perché
  «FireFlies»» non è nella homepage ma nella pagina **Il locale** (`pages/public/Locale.tsx`, titolo da
  `content.storyTitle`): è quella che ho corretto. Se il riferimento era un'altra sezione della home, va indicata.

## 2. Bug individuati e cause
| # | Bug | Causa | Correzione |
|---|---|---|---|
| B-1 | «Hai inviato molte richieste in poco tempo» a clienti legittimi | il limite per IP (5/ora) si consumava **prima** dei controlli: ogni invio rifiutato (orario non valido, giorno chiuso, privacy dimenticata, gruppo troppo grande, completo) contava; con IP condivisi o prove in locale si arrivava subito al blocco | limiti separati; le richieste si contano solo quando vengono salvate (§6) |
| B-2 | Doppio tocco su «Invia»: a volte errore «Richiesta già inviata… attendi» pur con la richiesta salvata | protezione solo con lo stato React (due tocchi nello stesso istante); il secondo invio perdeva sul vincolo della chiave e riceveva `409` | blocco sincrono nel modulo; il backend ripete il secondo invio in una nuova transazione e restituisce la stessa ricevuta |
| B-3 | Staff su un'API del titolare con percorso lungo (es. `PUT /admin/service-windows/{id}`): `500` invece di `403` | l'audit dell'accesso negato salvava il percorso in `object_id varchar(64)` | percorso troncato in `object_id`, intero nei metadati; test nella matrice dei permessi |
| B-4 | Animazione d'avvio con stacco netto | la schermata con il logo spariva in un fotogramma e il logo «saltava» in basso; sfondo della pagina trasparente prima del CSS; il hero ripartiva a ogni ritorno sulla home; doppia dissolvenza (pagina + hero) | velo che sfuma sulla pagina già pronta, sfondo scuro in linea, ingresso del hero solo alla prima apertura (§4) |
| B-5 | Foto di «Perché FireFlies» tagliata | foto verticale (3:4) costretta in un riquadro orizzontale 4:3 ancorato in alto: visibile solo il 56 % superiore, persone tagliate al petto | riquadro verticale 4:5 (visibile il 94 %), soggetto centrato, colonna proporzionata |
| B-6 | Orari di apertura su desktop: ora troncata («0») nella colonna di metà pagina (trovato durante FIX-1) | griglia a una riga con colonne strette | disposizione che dipende dalla larghezza del riquadro (container query) |
| B-7 | Capienza configurabile in tre posti, valori incoerenti (demo: colazione 20, resto 40) | `service_windows.max_covers` + eccezioni `CAPACITY_OVERRIDE` + capienza della zona | capienza unica (§5) |

## 3. Modifiche backend
- `dining/DiningService`: `setVenueCapacity` (unica capienza, stessi lock e `CAPACITY_BELOW_CONFIRMED`; `MULTIPLE_ZONES`
  se più zone attive); via tabella giorno × fascia e limiti per data; date straordinarie su intervalli con
  `add/update/deleteOpeningException`, controllo sovrapposizioni (`EXCEPTION_OVERLAPS`) e conflitti con prenotazioni.
- `dining/DiningSlotCapacityPolicy`: capienza del quanto = capienza del locale. `dining/BookingCalendar`: intervalli
  nelle chiusure, `venueCapacity()`, `webPartyLimit()` (tetto fisso 30).
- `dining/DiningController`: `PUT /admin/venue-capacity`, `GET/POST/PUT/DELETE /admin/opening-exceptions`; rimossi
  `GET/PUT /admin/capacity-matrix`, `maxCovers`, `maxPartySizeWeb`, `capacityOverride`.
- `reservation/WebRequestLimiter`: tre finestre (letture, tentativi, richieste create) con pulizia della memoria.
  `ReservationService.createWeb`: ordine dei controlli corretto, `waitText` per i messaggi. `ReservationController`:
  limite sulle letture, gestione del doppio invio contemporaneo. `OperationsService.summary(…, band)`.
- `security/SecurityProblemHandlers`: audit degli accessi negati sempre salvabile (B-3).
- `common/ConstraintViolationTranslator`: `ex_opening_exception_overlap` → `409 EXCEPTION_OVERLAPS`.

## 4. Modifiche frontend
- **Animazione iniziale** (`App.tsx`, `index.html`, `base.css`, `public.css`, `Home.tsx`, `PublicLayout.tsx`): sfondo
  `#101010` in linea; la schermata d'attesa diventa un velo (`.intro-veil`) presente già nel primo fotogramma con i
  contenuti, che sfuma in ~0,5 s con sola opacità/scala (nessun ricalcolo di layout, adatto ai telefoni), non blocca i
  tocchi e si toglie da solo; ingresso del hero (zoom lento e testi che salgono) solo alla prima apertura; nessuna
  dissolvenza di pagina sulla prima pagina; con «riduci movimento» niente velo né animazioni. Peso del sito pubblico: JavaScript 79.241 B gzip (+834 B rispetto alla
  FASE 8, compresa la navigazione del menu), CSS 22.116 B (+756 B).
- **Menu pubblico** (`Menu.tsx`, `public.css`): la ricerca scorre via, resta in alto solo la barra dei chip; il chip
  porta alla sezione (scorrimento morbido, titolo subito sotto la barra e con il focus), la categoria in vista è
  evidenziata mentre si scorre e il suo chip resta visibile nella riga; sfumature ai lati quando ci sono altri chip;
  nomi lunghi con puntini; `?cat=` apre la categoria. Prodotti, prezzi, allergeni, foto, visibilità e ordine invariati.
- **«Perché FireFlies»** (`Locale.tsx`, `public.css`): riquadro 4:5, `object-position: 50% 55%`, colonne 7/5 su
  desktop (foto al massimo 560 px), `sizes` corretto per scaricare la variante giusta.
- **Menu dei gestori sul telefono** (`AdminLayout.tsx`, `admin.css`): intestazione di 60 px con logo, pagina attuale e
  pulsante «Menu/Chiudi»; pannello con voci da 52 px, voce attiva marcata, «Vedi il sito» ed «Esci» (bordo fucsia) in
  fondo; sfondo scurito, pagina ferma; chiusura con voce, Esc, tocco fuori; focus sulla voce attiva e poi sul pulsante.
  Desktop invariato. Le notifiche restano sopra il menu.
- **Impostazioni** (`Settings.tsx`, `CapacitySettings.tsx`, `admin.css`): orari con una riga per giorno
  (Aperto/Chiuso evidente, «Dalle … alle …», nessun campo vuoto per i giorni chiusi); sezione «Capienza e calendario»
  su griglia senza buchi (capienza 5/12 e regole 7/12 della stessa altezza, poi riepilogo, chiusure, fasce); un solo
  campo «Capienza massima del locale (persone)»; regole senza «persone massime dal sito»; riepilogo di una settimana con
  frecce, intervallo, «Questa settimana», giorno di oggi evidenziato e una select per la fascia (schede compatte sul
  telefono); chiusure e aperture con scelta «Un giorno / Più giorni», elenco con modifica ed eliminazione confermata,
  avviso con i conteggi quando ci sono prenotazioni nei giorni chiusi.
- **Modulo di prenotazione** (`BookingDialog.tsx`): blocco sincrono del doppio invio, pulsante «Invio in corso…» con
  indicatore, messaggi per rete assente, errori temporanei e `429`.

## 5. Migrazioni e nuove regole di capienza
`V12__fix1_capienza_unica_chiusure.sql` (provata da un database V11 con dati della vecchia configurazione:
`MigrationIT.v12DaUnDatabaseConLimitiPerFasciaEPerData`):
- via `service_windows.max_covers`, `booking_exceptions.capacity_override` (e le righe `CAPACITY_OVERRIDE`),
  `booking_policies.max_party_size_web`; `ck_exception_kind` ora solo `CLOSED`;
- quanti del registro riallineati alla capienza del locale (solo dove i confermati ci stanno: mai sovraprenotati);
- `opening_exceptions.end_day` + `ck_opening_exception_range` + `ex_opening_exception_overlap`.

**Regola**: «Capienza massima del locale» = persone presenti nello stesso momento, uguale per ogni giorno e fascia;
l'occupazione la fanno solo le conferme (con sovrapposizioni di orario ed eventi). Ridurla sotto i confermati è
rifiutato con data, ora e persone. Effetto sui dati demo: la colazione passa da 20 a 40 posti (come il resto). Nessun
dato reale del committente esiste ancora; su un database reale la migrazione toglie solo limiti che abbassavano la
capienza.

## 6. Rate limiting
| Contatore (per IP, in memoria) | Prima | FIX-1 |
|---|---|---|
| Letture della disponibilità | nessun limite | 120 al minuto |
| Tentativi di invio | (lo stesso contatore delle richieste) | 30 in 10 minuti, anche se rifiutati |
| Richieste salvate | 5 all'ora, **contate anche se rifiutate** | 10 all'ora, contate solo al salvataggio |
| Per telefono (database) | 3 in 24 ore | invariato |
Tutti con `429`, `Retry-After` e attesa nel messaggio («riprova tra 12 minuti o chiamaci»); i reinvii con la stessa
chiave non contano mai. Nessun CAPTCHA né servizio esterno (motivazione: volume di un locale, conferma manuale,
trappola `website`; da rivalutare solo con abusi reali). Capienza e limiti sono sistemi separati: il controllo dei
posti resta solo alla conferma, sotto lock (BOOKING_FLOW_AUDIT §16).

## 7. Test e risultati (eseguiti davvero, in sequenza, mai backend ed e2e insieme)
| Suite | FASE 8 | FIX-1 | Esito |
|---|---|---|---|
| Backend unità | 78 | **82** (+`WebRequestLimiterTest` 4) | ✅ 82/82 |
| Backend integrazione, PostgreSQL 17 reale | 320 | **334** (+`WebRateLimitIT` 7, `OperationsIT` +4, `MigrationIT` +1, `DatabaseConstraintsIT` +1, `ApiSecurityMatrixIT` esteso; test riscritti per la capienza unica) | ✅ 334/334 |
| Playwright con backend e database reali | 111 | **136** (+`fix1.spec` 25) | ✅ 136/136 al primo giro completo finale |

Verifiche richieste dal mandato:
| Verifica | Dove | Esito |
|---|---|---|
| Animazione iniziale e «riduci movimento» | `fix1.spec` «velo…», «riduci movimento» | ✅ |
| Navigazione categorie del menu sul telefono | `fix1.spec` 360/390/430, nomi lunghi e tastiera, `?cat=`; `navigation.spec` | ✅ |
| Rendering «Perché FireFlies» | `fix1.spec` 390/1366/1920 (proporzioni 4:5, ritaglio < 10 %, nessun overflow) | ✅ |
| Navbar dei gestori: apertura/chiusura | `fix1.spec` 360/375/390/430/768 + staff; `fase7.spec` menu del telefono | ✅ |
| Rate limiting normale e abuso | `WebRateLimitIT` (8 invii rifiutati poi uno valido passa; 10 create → 11ª `429` con `Retry-After`; 30 tentativi → `429`; 120 letture → `429`, invio ancora possibile), `WebRequestLimiterTest`, `ProxyHeadersIT`; `fix1.spec` messaggio `429` | ✅ |
| Doppio invio | `WebRateLimitIT` (6 invii paralleli = 1 riga, stessa ricevuta), reinvii con la stessa chiave; `fix1.spec` doppio tocco = 1 sola `POST` | ✅ |
| Richieste concorrenti senza overbooking | `OperationsIT.capienzaConcorrenteMaiOltreIlLimite`, `ReservationConcurrencyIT`, `EventConcurrencyIT` | ✅ |
| Capienza unica | `OperationsIT.capienzaUnicaDelLocaleSulRegistro`, `fase7.spec`, `fix1.spec` | ✅ |
| Riduzione della capienza con conferme attive | `OperationsIT` (`CAPACITY_BELOW_CONFIRMED`) e **`riduzioneDellaCapienzaConConfermeContemporanee`**, `fase7.spec` | ✅ |
| Riepilogo settimanale, navigazione settimane, select fascia | `OperationsIT.riepilogoSettimanaleDiUnaFascia`, `fix1.spec` | ✅ |
| Chiusure e aperture di un giorno e su intervalli | `OperationsIT.chiusureEApertureSuUnIntervallo`, `intervalliContemporaneiNonSiSovrappongono`, `DatabaseConstraintsIT`, `fix1.spec` | ✅ |
| Conflitti con prenotazioni esistenti | `OperationsIT.chiusuraConPrenotazioniEsistentiLeSegnalaSenzaCancellarle`, `fix1.spec` | ✅ |
| Responsività PC e telefono | `fix1.spec` 360, 375, 390, 430, 768, 1366, 1440, 1920; `qa.spec`; `fase7.spec`; `fase8.spec` | ✅ |
| Regressioni eventi e notifiche | suite complete (`EventApiIT`, `EventConcurrencyIT`, `NotificationDispatcherIT`, `OutboxClaimIT`, `events.spec`, `event-visibility.spec`, `posters.spec`, `fase7.spec`) | ✅ |
| Migrazione V12 da dati V11 | `MigrationIT.v12DaUnDatabaseConLimitiPerFasciaEPerData` | ✅ |

## 8. Regressioni
- Nessuna nelle suite complete finali. Durante lo sviluppo i test hanno trovato e fatto correggere: due prove di
  navigazione (`navigation.spec`) che si aspettavano i chip come filtri — aggiornate al nuovo comportamento richiesto;
  le prove FASE 7 della tabella giorno × fascia — riscritte per la capienza unica; un `500` al posto del `403` (B-3).
- Comportamenti cambiati di proposito (da verificare nel collaudo): i chip del menu non nascondono più le altre
  categorie; il numero massimo di persone dal sito non è più impostabile (capienza del locale, al massimo 30); la
  capienza della colazione nei dati demo passa da 20 a 40.

## 9. Punti ancora aperti
- Riferimenti visivi del mandato non disponibili qui (§1): «Perché FireFlies» corretta sulla pagina Il locale.
- «Impostazioni del riepilogo della richiesta dal sito»: nel codice non esiste un'impostazione con questo nome. Ho
  rimosso le sole impostazioni ridondanti con la capienza unica (persone massime dal sito, limiti per giorno e fascia e
  per data). Restano anticipo minimo, giorni di anticipo massimi e scadenza: sono usati dal backend e non duplicano
  la capienza. Se ne va tolta un'altra, va indicata.
- Le chiusure agli arrivi di una sola fascia in una data (`booking_exceptions` `CLOSED`) restano nel backend e si
  vedono/rimuovono in Impostazioni solo se esistono; non si creano più dall'interfaccia (le chiusure si fanno per
  giorni interi). Se servono per fascia, si può riaggiungere un controllo.
- Limiti in memoria (una sola istanza, come in DEPLOY.md); tetto di 30 persone per richiesta dal sito: valore da
  confermare con il titolare.

## 10. Screenshot di verifica
In `docs/architecture/evidence/fix1/` (Playwright reale): `intro-prima.png` / `intro-dopo.png` (fotogrammi dell'avvio
su telefono con CPU ×4), `perche-fireflies-1366.png`, `perche-fireflies-390.png`, `menu-categorie-390.png`,
`menu-gestori-390.png`, `orari-390.png`, `impostazioni-orari-contatti-1366.png`, `capienza-e-calendario-1366.png`,
`riepilogo-settimana-360.png`. Altri screenshot sono allegati ai test `fix1.spec.ts` (report di Playwright e artifact
della CI).

## 11. Istruzioni per il collaudo manuale
1. **Avvio**: aprire il sito da telefono (meglio con rete lenta): logo su sfondo scuro, poi la home compare con una
   dissolvenza, senza lampi bianchi; andare su Menu e tornare in Home: l'animazione non riparte.
2. **Menu**: sul telefono toccare «Food», «Cocktail»…: la pagina va alla sezione, il chip resta evidenziato; scorrendo
   a mano il chip attivo cambia; la ricerca filtra ancora le voci.
3. **Il locale › Perché «FireFlies»**: la foto mostra la scritta e le due persone al tavolo, su telefono e PC.
4. **Prenotazione**: inviare una richiesta, poi provare più volte con errori (orario, privacy): la richiesta corretta
   passa sempre; toccare due volte «Invia»: una sola richiesta in Richieste.
5. **Area gestori dal telefono**: pulsante «Menu», voce attiva evidenziata, Esc/tocco fuori per chiudere, «Esci» in
   fondo al pannello.
6. **Impostazioni**: impostare la capienza (es. 100) e salvarla; provare a ridurla sotto i confermati di un orario (deve
   rifiutare dicendo dove); riepilogo: frecce avanti/indietro, select della fascia; chiusure: un giorno (es. 25/12) e
   un intervallo (es. 10–17/8), un'apertura straordinaria su più giorni; modificarle ed eliminarle; nel calendario del
   sito quei giorni non sono prenotabili; orari di apertura comodi da telefono.
7. Dopo l'approvazione esplicita: merge di `fix-1` in `main`.
