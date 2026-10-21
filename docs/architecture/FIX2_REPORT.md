# FIX2_REPORT — prenotazioni, capienza ed eventi

> 21/10/2026 · branch `fix2/prenotatavolo` (creato da `main` @ `e558988`, ultimo commit di `fix-1`) · **nessun merge,
> nessun deploy**: in attesa del collaudo manuale del titolare.

## 1. Analisi
Lettura del codice (non solo dei documenti) di registro dei posti, conferme, eventi, duplicati, limiti e chiusure:

| Tema | Com'era (FIX-1) | Problema |
|---|---|---|
| Capienza | solo **posti contemporanei** per quanto da 15' (`CapacityLedger`, `capacity_slots`) | con il ricambio una fascia accoglieva più persone della capienza (100 alle 19 + 100 alle 21) |
| Duplicati | `ux_res_web_duplicate` = stesso telefono **e stesso orario** | lo stesso cliente poteva chiedere 19:00 e 19:30 nella stessa serata |
| Limite per IP | 10 richieste create all'ora, contate prima del salvataggio | valore diverso da quello chiesto (5); un salvataggio fallito restava contato |
| Chiusure | orari settimanali letti dal vivo (già corretti); date straordinarie salvate subito con un avviso | nessuna conferma consapevole davanti a prenotazioni attive |
| Eventi | `%15` in `EventService`, `ck_event_aligned`, `ck_res_aligned`, `OccupancyInterval` | impossibile 18:07 o 21:58 |
| Pubblicazione | interruttore «Visibile sul sito» nell'editor, un solo «Salva» | richiesto in elenco; nuovi eventi sempre bozza |
| Riepilogo | capienza e «disponibili» dai soli posti fisici | non mostrava il budget della fascia |

Verificato che: i giorni settimanali Chiuso→Aperto erano già letti dal vivo (`BookingCalendar.venueOpen`) — non riscritti,
coperti da un test; la durata automatica (4 ore o durata già salvata, FASE 8) non dipende dall'allineamento — invariata.

## 2. Modifiche
**Backend**
- `V13__fix2_budget_fasce_duplicati_eventi.sql` (incrementale, non distruttiva).
- `reservation/BandBudget` (nuovo): controllo del budget sotto lock; usato in conferma, modifica di una confermata,
  annullamento (solo lock), anteprima del sito, orari prenotabili, riepilogo.
- `ReservationService`: dedup per telefono/giorno/fascia, anteprima del budget, `OccupancyInterval.covering` per le
  prenotazioni di un evento, uscita anticipata con orari al minuto, reinvio contemporaneo con la stessa chiave.
- `ReservationController`: ripetizione anche sui vincoli `ux_res_web_band_duplicate`/`ux_res_web_duplicate`.
- `WebRequestLimiter`: 5 richieste create/ora, restituite se la transazione non va a buon fine.
- `capacity/OccupancyInterval.covering`, `CapacityLedger.read` dal quanto che contiene l'inizio.
- `EventService`: orari al minuto (`INVALID_TIME` solo per i secondi), riserva esclusiva con `covering`,
  `setVisibility`; `EventController`: `PUT /admin/events/{id}/visibility`.
- `DiningService`: capienza del locale anche contro il budget delle fasce future; chiusure straordinarie con conferma.
- `ContentService`/`ContentStore`: giorni settimanali che passano a chiuso con prenotazioni attive → conferma.
- `OperationsService`: riepilogo con budget, disponibilità fisica, arrivi riservati a eventi.
- `ConstraintViolationTranslator`: `ux_res_web_band_duplicate` → `409 DUPLICATE_REQUEST` generico.

**Frontend**
- `EventsAdmin.tsx`: interruttore «Sul sito» per riga (`aria-label="Pubblica sul sito: [titolo]"`, disabilitato durante
  il salvataggio, torna com'era se il backend rifiuta, conflitti elencati nella riga); editor senza interruttore, nuovi
  eventi bozza, orario con passo di 1 minuto.
- `CapacitySettings.tsx`: riepilogo «Confermate / capienza», «Restano nella fascia», «Disponibili adesso» con note
  (evento, posti contemporanei); conferma delle chiusure straordinarie con prenotazioni (`ClosureConfirm`).
- `Settings.tsx`: stessa conferma per i giorni della settimana chiusi.
- `api/events.ts`, `api/reservations.ts`, `api/content.ts`, `MenuAdmin.Switch` (stato disabilitato/occupato), CSS.

**CI**: i workflow partono anche sui branch `fix2/**`.

## 3. Logica della capienza
Tre controlli, tutti nel backend e nella stessa transazione PostgreSQL:
1. **Budget della fascia** (nuovo): persone `CONFIRMED`+`COMPLETED`+`NO_SHOW` del giorno e della fascia ≤ capienza del
   locale → altrimenti `409 BAND_CAPACITY_EXCEEDED`.
2. **Posti contemporanei** (invariato): `CapacityLedger`, quanti da 15' → `409 CAPACITY_EXCEEDED`.
3. **Eventi** (invariato): quota (`EVENT_QUOTA_EXCEEDED`) ed esclusività (`EVENT_BLOCKS_SLOT`, `EVENT_CONFLICTS`).

Transizioni: in attesa non conta; conferma consuma; annullamento di una confermata restituisce; rifiuto/annullamento di
una in attesa e scadenza nessun effetto; completata e non presentata **non** restituiscono (né la correzione tra le due);
cambio di giorno, fascia o persone: vecchio restituito e nuovo occupato in un'unica transazione, controllato prima.
Il contatore lo aggiorna un trigger: ogni cambio di riga aggiorna il contatore esattamente una volta, anche ripetendo
l'operazione o modificando con SQL. Il backend blocca la riga del contatore `FOR UPDATE` (le conferme sulla stessa
fascia si mettono in fila e rileggono il totale) dopo le zone `FOR SHARE` (un cambio di capienza non si accavalla).
Ordine globale: prenotazione → evento → zone → righe del budget (giorno, fascia) → quanti. L'annullamento blocca la riga
del budget prima di liberare i quanti, per non andare in stallo con una conferma contemporanea.

## 4. Migrazioni e integrità dei dati
V13, verificata su database vuoto e su un database V12 con prenotazioni di ogni stato (`MigrationIT`):
- aggiunge `reservations.service_band` (calcolata dalla funzione `fireflies_band`, la stessa regola di `dining/Band`) e
  `dedup_enforced`; crea `band_capacity_usage` inizializzata **solo** dalle prenotazioni reali che consumano
  (nessun dato storico inventato: annullate, rifiutate e in attesa non contano; le eliminate dalla pulizia non ci sono);
- non cancella né modifica prenotazioni, eventi o orari; i gruppi di richieste attive già duplicati restano com'erano
  e sono esclusi dal nuovo indice; le fasce future già oltre la capienza producono solo un `NOTICE` (restano valide,
  le nuove conferme sono bloccate) — la migrazione non si ferma perché non tocca quei dati;
- rilassa `ck_event_aligned` → `ck_event_minute` e `ck_res_aligned` (15' per le ordinarie, minuto per gli eventi).
Il `CapacityLedger` e `capacity_slots` restano invariati; la riconciliazione dei test controlla ora anche il budget.

## 5. Regola dei duplicati
Stesso telefono normalizzato (E.164) + stesso giorno + stessa fascia con una richiesta precedente `PENDING` o
`CONFIRMED` → `409 DUPLICATE_REQUEST`, anche a orari diversi della fascia e anche per una serata evento. Di nuovo
possibile se la precedente è annullata, rifiutata, scaduta, completata o non presentata. Il controllo applicativo guarda
anche le prenotazioni inserite dai gestori; quello vincolante è l'indice unico parziale `ux_res_web_band_duplicate`
(richieste dal sito): con due invii contemporanei il secondo fallisce al salvataggio, viene ripetuto e riceve la stessa
ricevuta (stessa `Idempotency-Key`) oppure il `409`. Il messaggio non contiene dati dell'altra prenotazione.
Interazione con il limite per telefono (3 al giorno): resta, viene controllato prima del duplicato; insieme impediscono
più di una richiesta attiva per fascia e più di tre richieste al giorno dallo stesso numero.

## 6. Limiti per IP
- 5 richieste **create** all'ora per IP; la sesta → `429` con `Retry-After` e l'attesa nel messaggio.
- Non contano: letture del calendario e degli orari (limite separato 120/min), rifiuti di validazione, duplicati,
  reinvii con la stessa chiave, errori di rete o del database (il posto si restituisce se la transazione fallisce).
- Restano i tentativi di invio (30 in 10 minuti) contro chi prova a raffica e i 3 al giorno per telefono; l'IP reale
  arriva da Caddy tramite `RemoteIpValve` (`X-Forwarded-For` falso non aggira il limite, `ProxyHeadersIT`).
- Un cliente che sbaglia il modulo non resta bloccato: dopo invii rifiutati la richiesta giusta passa (`WebRateLimitIT`).

## 7. Aggiornamento delle chiusure
- Chiuso → Aperto di un giorno della settimana: subito prenotabile (calendario e orari letti dal vivo); Aperto →
  Chiuso: subito bloccato; le date straordinarie prevalgono; il backend rivalida giorno e fascia a ogni invio.
- Chiudere un giorno (settimanale o straordinario) con prenotazioni attive → `409 CLOSURE_HAS_RESERVATIONS` con i giorni
  e i conteggi; l'interfaccia mostra il riquadro «Chiudi comunque / Non chiudere»; nessuna prenotazione annullata o
  rifiutata in automatico.

## 8. Orari arbitrari degli eventi
- Inizio e fine a qualunque minuto (18:07, 19:23, 20:40, 21:58, 23:05); solo i secondi sono rifiutati.
- Durata automatica invariata: 4 ore per i nuovi eventi, durata salvata per quelli esistenti; fine dopo mezzanotte
  gestita (21:58 → 01:58).
- Registro a quanti da 15': l'evento occupa/riserva ogni quanto toccato anche in parte (19:07–23:07 → 19:00–23:15).
  Ai confini non passa nulla: un'ordinaria che finisce alle 19:00 passa, una che finisce alle 19:15 no; un esclusivo
  alle 23:10 è in conflitto, alle 23:15 no. Riconciliazione e SQL dei test aggiornati alla stessa regola.
- Prenotazioni ordinarie invariate (passi di 15').

## 9. Pubblicazione dall'elenco
- Interruttore per evento nell'elenco Gestione › Eventi: spento = bozza, acceso = pubblicato; mostra lo stato salvato,
  disabilitato durante il salvataggio, torna com'era con l'errore nella riga, corretto dopo il ricaricamento.
- `PUT /admin/events/{id}/visibility` riusa `publishLocked`/`unpublishLocked`: conflitti esclusivi, quota, richieste in
  attesa da confermare (`Pubblica comunque`), niente ritorno a bozza con prenotazioni attive; nessuno stato a metà.
- Editor senza interruttore; un nuovo evento nasce bozza; salvare gli altri campi non cambia la visibilità.
- STAFF e titolare possono pubblicare (come prima); anonimi `401`.

## 10. Test con i risultati reali
_Compilato al termine delle esecuzioni (vedi sotto)._

## 11. Punti aperti
1. Una prenotazione per un evento conta nella fascia dell'**inizio** dell'evento (una serata 21:58–01:58 conta tutta in
   «Sera»). Da confermare con il titolare.
2. Spostare l'orario di un evento con sole richieste in attesa non controlla il budget (le richieste non consumano);
   con prenotazioni confermate lo spostamento resta vietato (`EVENT_HAS_CONFIRMED_RESERVATIONS`).
3. Il budget usa la capienza **attuale** del locale per ogni giorno: abbassarla sotto le confermate di una fascia futura
   è rifiutato; le fasce passate non si ricontrollano.
4. Il parametro `visible` di `POST/PUT /admin/events` resta nell'API (compatibilità, test della FASE 7); l'interfaccia
   non lo usa più.
5. Limiti in memoria (una sola istanza, come prima): con più istanze andrebbero su PostgreSQL.
6. Chiusure di una sola fascia (`booking-exceptions`) non chiedono conferma (non esposte nell'interfaccia dalla FIX-1).

## 12. Istruzioni per il collaudo manuale
Avvio locale come in `docs/operations/LOCAL_SETUP.md` (dati demo, capienza da impostare in Impostazioni).
1. **Budget della fascia**: capienza 10. Dal sito due richieste nella stessa fascia (es. 18:00 per 6 e 20:00 per 4, numeri
   diversi); confermale. Una terza alle 20:30: sul sito gli orari della fascia risultano pieni; se era già in attesa, la
   conferma risponde «Fascia al completo». Annulla la prima: la terza si conferma. Segna completata/non presentata una
   confermata: il riepilogo non restituisce i posti.
2. **Riepilogo settimanale** (Impostazioni › Capienza e calendario): fascia e settimana con le frecce; colonne
   «Confermate / capienza», «Restano nella fascia», «Disponibili adesso» (con le note su evento e posti contemporanei).
3. **Duplicati**: dallo stesso telefono due richieste nella stessa fascia dello stesso giorno → la seconda «Richiesta già
   inviata»; in un altro giorno o dopo il rifiuto della prima → accettata.
4. **Limite per IP**: dallo stesso dispositivo 6 richieste valide (telefoni diversi) in un'ora → la sesta è bloccata con
   l'attesa; gli errori del modulo non contano.
5. **Chiusure**: chiudi un mercoledì nelle Impostazioni › Orari con una prenotazione quel giorno → riquadro di conferma
   con i giorni; «Non chiudere» non salva; «Chiudi comunque» salva e la prenotazione resta. Riapri: il giorno torna
   prenotabile nel calendario del sito.
6. **Eventi al minuto**: Nuovo evento alle 21:58 → nell'elenco «21:58 – 01:58», stato Bozza. Prova 18:07 e 19:23.
7. **Pubblicazione dall'elenco**: accendi «Sul sito» → toast e stato Pubblicato, presente nella pagina Eventi anche dopo
   il ricaricamento; spegnilo → torna Bozza. Prova a pubblicare un esclusivo sovrapposto a un altro: l'interruttore torna
   spento e la riga spiega il conflitto. Con prenotazioni attive non si spegne.
8. Telefono (360–430 px): elenco eventi senza sovrapposizioni né scorrimento orizzontale.
