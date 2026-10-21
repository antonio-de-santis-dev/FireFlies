# FIX2_FASCEORARIE_REPORT — orari di apertura come unica impostazione del calendario

> 21/10/2026 · branch `fix2/fasceorarie`, creato da `fix2/prenotatavolo` @ `ee3ba4c` (ultimo commit remoto) ·
> **nessun merge, nessun deploy, nessun reset**: in attesa del collaudo manuale del titolare.

## 1. Architettura precedente
Due configurazioni separate decidevano se un giorno era prenotabile:
- **A — Orari di apertura** (Impostazioni › Orari, `opening_hours`) e date straordinarie (`opening_exceptions`): aperto
  o chiuso. Gli orari di apertura **non** limitavano gli arrivi.
- **B — Orari di arrivo delle fasce** (Impostazioni › Capienza e calendario, `service_windows`): primo e ultimo arrivo,
  passo e permanenza per ogni giorno della settimana. Senza righe per un giorno, quel giorno non aveva orari
  (`groups: []`, `NO_SERVICE_WINDOWS`) anche se aperto: è il caso del mercoledì sui database nati prima della FASE 7,
  quando i dati dimostrativi non creavano fasce nei giorni di chiusura.

## 2. Dipendenze individuate (codice e schema reali, V1–V13)
| Dipendenza | Perché non si può togliere `service_windows` |
|---|---|
| `reservations.service_window_id` → `fk_res_window` (RESTRICT), `ck_res_target` | ogni prenotazione ordinaria deve riferirsi a una fascia |
| `fireflies_band()` e trigger `trg_res_band` (V13) | la fascia storica (budget, duplicati) deriva dal **nome** della fascia |
| `booking_exceptions.service_window_id` | chiusure di una singola fascia (create prima di FIX-1) |
| `BookingCalendar` (`windowsOn`, `windowFor`, `closedDays`/`unavailableDays`), `BookingOptionsService`, `ReservationService.webSlot`/`timing`, `OperationsService.summary`, `ReservationQueries`, `RetentionService` | arrivi, durata, raggruppamento per fascia, riepilogo, statistiche |
| `DemoContentLoader` | creava le fasce dei dati dimostrativi |
| Frontend: `WindowsPanel`, `createWindow/updateWindow/deleteWindow`, avviso «aperto ma senza fasce» | configurazione manuale (da rimuovere) |

## 3. Nuova architettura
```
Impostazioni › Orari ──► opening_hours ┐
Date straordinarie ────► opening_exceptions ├─► BookingCalendar.openingOn(data)  (unica fonte amministrativa)
                                            │
ArrivalRules (codice) ─► ArrivalCatalog ─► service_windows (catalogo interno, solo INSERT)
                                            │
             arrivi offerti = arrivi delle fasce interne ∩ apertura del giorno (arrivo + permanenza ≤ chiusura)
                                            │
   BookingOptionsService · ReservationService.webSlot · OperationsService.summary · unavailableDays
                                            │
   poi, invariati: anticipo minimo · budget per fascia · posti contemporanei · eventi esclusivi · duplicati
```
- `dining/ArrivalRules`: le fasce interne, centralizzate e verificabili.
- `dining/ArrivalCatalog.ensureStandardWindows()`: per ogni zona attiva e ogni giorno della settimana **senza** fasce
  attive inserisce quelle di `ArrivalRules`; non modifica né cancella mai fasce esistenti; idempotente; serializzato da
  `pg_advisory_xact_lock`. Chiamato all'avvio (`ApplicationRunner`), a ogni salvataggio degli orari
  (`ContentService.updateSettings`) e all'attivazione della capienza (`DiningService.setVenueCapacity`).
- `BookingCalendar.openingOn(date)` → `Opening(open, from, to)`: data straordinaria se c'è (chiusa; aperta con i suoi
  orari; aperta senza orari = tutto il giorno), altrimenti orario settimanale; chiusura prima dell'apertura o
  `closes_next_day` = giorno dopo; istanti in Europe/Rome con le regole dell'ora legale. `venueOpen` usa la stessa fonte.
- `Opening.admits(arrivo, durata)`: arrivo ≥ apertura **e** arrivo + permanenza ≤ chiusura.

## 4. Regole interne adottate
| Fascia | Arrivi | Passo | Permanenza prevista |
|---|---|---|---|
| Colazione | 07:30 – 10:30 | 30' | 60' |
| Pomeriggio | 15:00 – 17:00 | 60' | 60' |
| Aperitivo | 18:00 – 20:30 | 30' | 90' |
| Sera | 21:00 – 22:00 | 30' | 60' |

**Provenienza**: sono le fasce del modulo di prenotazione approvato, create per **ogni** giorno dai dati dimostrativi
dalla FASE 7 (`DemoContentLoader`, commit `57ad258`) e quindi già in uso in tutti i giorni configurati. Nessun orario
commerciale nuovo. Tra 11:00 e 15:00 non ci sono arrivi (come prima).

**Prenotazioni oltre la chiusura**: la permanenza prevista **non viene mai accorciata**; se arrivo + permanenza
supererebbe la chiusura, quell'arrivo non si propone (es. aperto fino alle 22:00: Aperitivo 20:30 + 90' sì, Sera 21:30 +
60' no). Il personale può ancora inserire a mano (telefono/email) anche fuori orario, con gli avvisi di sempre.

**Giorni già configurati diversamente**: le fasce esistenti restano com'erano (anche se diverse da quelle standard); il
catalogo riempie solo i giorni che non ne hanno. *Proposta di uniformazione* (non applicata): portare tutti i giorni alle
fasce standard solo dopo aver verificato che nessun giorno usi valori personalizzati — basta confrontare
`SELECT iso_day, name, first_start, last_start, slot_minutes, default_duration_minutes FROM service_windows WHERE active`
con la tabella sopra; sui database con i dati dimostrativi sono già identiche.

**Motivi dei giorni non prenotabili** (`unavailableDays`): `VENUE_CLOSED` (locale chiuso), `NO_ARRIVAL_TIMES` (aperto
ma nessun arrivo interno nell'orario, es. 11:00–14:00; sostituisce `NO_SERVICE_WINDOWS`, che non ha più senso),
`ARRIVALS_CLOSED` (fasce chiuse agli arrivi in quella data). Per gli orari restano `TOO_EARLY`, `DAY_CLOSED`, `FULL`,
`EVENT`. La mancanza di righe non chiude più un giorno aperto: il catalogo le aggiunge.

## 5. File modificati
**Backend**: `dining/ArrivalRules.java` (nuovo), `dining/ArrivalCatalog.java` (nuovo), `dining/BookingCalendar.java`
(`Opening`, `openingOn`, `arrivals`, `unavailableDays`, `venueOpen`), `dining/DiningStore.java` (`weeklyHours`),
`dining/DiningService.java` (catalogo all'attivazione della capienza), `content/ContentService.java` (catalogo al
salvataggio degli orari), `reservation/BookingOptionsService.java` (solo arrivi dentro l'apertura),
`reservation/ReservationService.java` (`SLOT_NOT_AVAILABLE` fuori apertura), `reservation/OperationsService.java`
(riepilogo coerente), `demo/DemoContentLoader.java` (fasce dal catalogo).
**Frontend**: `pages/admin/CapacitySettings.tsx` (rimossi «Orari di arrivo delle fasce» e l'avviso), `api/reservations.ts`
(rimosse le chiamate alle fasce, `NO_ARRIVAL_TIMES`), `pages/admin/Settings.tsx` (nota sugli arrivi negli Orari),
`components/BookingDialog.tsx`, `styles/admin.css` (stili inutilizzati rimossi).
**Test**: `ArrivalHoursIT` (nuovo), `CalendarOpeningIT`, `DemoContentLoaderIT`, `Fix2BookingIT`, `calendar.spec.ts`.
**Documenti**: questo report, `BOOKING_RULES` §13-ter, `API_CONTRACT`, `DATA_MODEL` §3.13, `DECISION_LOG` T-95,
`TEST_REPORT`, `WORK_PROGRESS`.

## 6. Migrazioni
**Nessuna migrazione Flyway** (lo schema resta alla V13). Le uniche scritture nuove sono gli `INSERT` di
`ArrivalCatalog` nei giorni senza fasce (con voce nel registro delle operazioni). Sul database del titolare: al primo
avvio del backend di questo branch il mercoledì (e ogni altro giorno senza fasce) riceve le 4 fasce interne; nient'altro
cambia. **Ripristino**: tornare al branch precedente; le fasce aggiunte possono restare (sono le stesse degli altri
giorni) oppure, se non usate da prenotazioni, si disattivano dall'API tecnica `DELETE /admin/service-windows/{id}`
(una fascia già usata viene solo disattivata). Prenotazioni, eventi, utenti, foto e menu non sono toccati.

## 7. Test realmente eseguiti
PostgreSQL 17 reale e isolato (Testcontainers per il backend, Docker Compose `fireflies-e2e` per Playwright); mai il
database del titolare.

| Suite | Comando | Esito |
|---|---|---|
| Backend unit | `./mvnw -o verify` | **82 / 82** |
| Backend integrazione | `./mvnw -o verify` | **357 / 358** al giro completo; il fallito è sotto |
| Riesecuzione del fallito | `Fix2BookingIT#duplicatiContemporaneiBloccatiDalDatabase` ×6 dopo l'aggiornamento | **6 / 6** |
| Frontend typecheck + Playwright | `npm run verifica` | **149 / 149** |

Il fallito: `Fix2BookingIT.duplicatiContemporaneiBloccatiDalDatabase` (FIX 2, codice non toccato da questo branch). Sei
invii **nello stesso istante** dallo stesso telefono e IP: sempre una sola richiesta creata e una sola riga; il test però
si aspettava cinque `409` e ne ha visti quattro. Rieseguito 6 volte stampando i codici: in 5 su 6 il sesto riceve `429`,
perché il limite di 5 richieste create all'ora prenota il posto prima del salvataggio e lo restituisce a transazione
chiusa. Comportamento corretto e già presente su `fix2/prenotatavolo` (là il test era passato per tempistica);
aggiornato il test ad accettare `409` o `429` per gli invii non creati. La suite completa non è stata rieseguita dopo
questa modifica del solo test.

Nuovi o aggiornati:
- `ArrivalHoursIT` (5): giorno chiuso → aperto → chiuso → riaperto senza fasce di partenza (arrivi 07:00–23:00 esatti,
  anche 4 settimane dopo; invio rifiutato da chiuso, accettato da riaperto); apertura 18:00–22:00 (solo arrivi
  compatibili; 08:00 e 21:30 rifiutati, 21:00 accettato); apertura 11:00–14:00 → `NO_ARRIVAL_TIMES`; chiusura dopo
  mezzanotte 18:00–02:00; chiusura straordinaria su una data; apertura straordinaria 17:00–23:00 in un giorno chiuso;
  budget della Sera esaurito, duplicato stesso telefono/giorno/fascia, evento esclusivo → `EVENT`, fascia `SERA`
  registrata; catalogo che aggiunge solo i giorni mancanti (24 fasce), idempotente, fascia personalizzata e
  prenotazione esistente invariate.
- `CalendarOpeningIT` (4): i 7 giorni chiusi/aperti/richiusi senza riavvio; giorno senza fasce ora subito prenotabile.
- `calendar.spec.ts` (6): mercoledì chiuso → aperto → chiuso a 1366 e 390 px senza riavvii; richiesta inviata in un
  mercoledì riaperto; area gestori senza «Orari di arrivo delle fasce» e riepilogo aggiornato subito; apertura
  18:00–22:00 con soli arrivi compatibili sul telefono, senza scorrimento orizzontale; etichetta «nessun orario
  prenotabile».
- Regressioni comprese nelle suite: capienza contemporanea (`ReservationConcurrencyIT`, `CapacityConcurrencyIT`),
  budget e duplicati (`Fix2BookingIT`), eventi (`EventApiIT`, `Fix2EventIT`, `EventConcurrencyIT`), migrazioni
  (`MigrationIT`), layout desktop/telefono (`fix1`, `fix2`, `qa`, `a11y`).

## 8. Incompatibilità e punti aperti
1. `NO_SERVICE_WINDOWS` → `NO_ARRIVAL_TIMES` in `unavailableDays` (campo aggiunto in FIX 2, usato solo dal sito).
2. Le API tecniche `/admin/service-windows` restano (owner) ma non sono nell'interfaccia; se qualcuno elimina via API
   tutte le fasce di un giorno, il catalogo le ricrea al successivo salvataggio degli orari o riavvio.
3. Le fasce già personalizzate su un giorno restano tali (vedi §4, proposta di uniformazione).
4. Gli inserimenti del personale fuori dall'orario di apertura sono ancora permessi (avviso `OUTSIDE_SLOTS` se fuori
   dagli arrivi), come prima.
5. Con 6 invii simultanei dallo stesso IP uno può ricevere `429` (limite IP) invece di `409` (§7).

## 9. Avvio locale
Come `docs/operations/LOCAL_SETUP.md`, sul branch `fix2/fasceorarie`: `cd infra && docker compose up -d --wait`, poi
`cd backend && ./mvnw spring-boot:run` e `cd frontend && npm run dev`. All'avvio il log può riportare «Fasce interne:
inserite N fasce standard nei giorni che non ne avevano» (solo la prima volta, se mancavano).

## 10. Collaudo manuale
1. Impostazioni: «Capienza e calendario» non contiene più «Orari di arrivo delle fasce»; negli Orari c'è la nota sugli
   arrivi.
2. Mercoledì «Aperto» 07:00–23:00 → Salva. Sul sito «Richiedi un tavolo»: il mercoledì è selezionabile e mostra
   Colazione 07:30–10:30, Pomeriggio 15–17, Aperitivo 18:00–20:30, Sera 21:00–22:00. Nessun riavvio.
3. Mercoledì 18:00–22:00 → Salva: il calendario propone solo 18:00–20:30 e 21:00.
4. Mercoledì «Chiuso» → Salva: i mercoledì risultano «chiuso» (con richieste attive chiede conferma); riaprilo: tornano.
5. Chiusura straordinaria su un mercoledì aperto: quella data «chiuso», le altre no. Apertura straordinaria su un giorno
   chiuso con orari: arrivi solo dentro quegli orari.
6. Invia una richiesta per un mercoledì riaperto: arriva in Richieste «In attesa»; conferma: il riepilogo settimanale
   (fascia corrispondente) mostra le persone confermate.
7. Telefono (360–430 px) e desktop: calendario e Impostazioni senza sovrapposizioni.
