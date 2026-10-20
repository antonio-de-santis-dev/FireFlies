# FASE 6 — Report tecnico (Eventi e capienza)

> Branch `fase-6/eventi-capienza`, creato dall'ultimo commit di `fase-5/prenotazioni` (`911c5be`). 20/10/2026.
> Nessun merge su `main`, nessun branch approvato modificato. **La FASE 7 non è iniziata.**

## 1. Cosa è stato fatto

### 1.1 Obiettivi della fase
| # | Obiettivo del mandato | Stato | Dove |
|---|---|---|---|
| 1 | Eventi reali su PostgreSQL | ✅ tabelle `events` / `event_media` della V4 (nessuna migrazione nuova) | `event/EventStore`, `EventService` |
| 2 | Creazione, modifica, pubblicazione, annullamento | ✅ più eliminazione delle bozze; versione ottimistica, audit di ogni passaggio | `/admin/events/**` |
| 3 | Immagini e locandine dalla libreria della FASE 4 | ✅ locandina e foto scelte dalla libreria (`MEDIA_NOT_FOUND` se non pronte); in uso → non eliminabili (`MEDIA_IN_USE`) | `MediaCatalog`, `PhotoPicker` |
| 4 | Prenotazioni per eventi con conferma manuale | ✅ dal sito e dai gestori, sempre «in attesa» fino alla conferma | `ReservationService`, `EventBooking` |
| 5 | Capienza configurabile per evento | ✅ quota `capacity_covers` (1 … capienza fisica), controllata alla conferma e alla modifica | T-48 |
| 6 | Eventi esclusivi per impostazione predefinita | ✅ `exclusive = true` di default; alla pubblicazione i quanti dell'evento sono riservati | T-47 |
| 7 | Non esclusivi solo se il titolare li abilita e la condivisione è sicura | ✅ solo OWNER (`403 EXCLUSIVITY_OWNER_ONLY` per lo staff); sicuri per costruzione: evento e servizio ordinario contano sugli stessi quanti, mai oltre la capienza; avviso se la quota supera i posti liberi | T-50, T-51 |
| 8 | Conflitti con le prenotazioni ordinarie confermate | ✅ `409 EVENT_CONFLICTS` non forzabile (elenco senza dati personali); richieste in attesa da confermare esplicitamente | T-51 |
| 9 | Coerenza con il registro a quanti di 15 minuti | ✅ nessun secondo contatore: esclusività = colonna del registro, posti = registro, quota = limite in più; riconciliazione registro/eventi dopo ogni test | `CapacityLedger.markExclusive/occupyForEvent` |
| 10 | Pagina Eventi pubblica e dashboard gestori | ✅ stessa impaginazione; dati dal backend | §1.4 |
| 11 | OWNER/STAFF, CSRF, audit, concorrenza | ✅ | §1.3, `EventConcurrencyIT` |

### 1.2 Backend
- **Registro (FASE 2, esteso)**: `markExclusive(zone, evento, intervallo)` con lo stesso schema di lock delle conferme —
  se un quanto ha persone confermate o è di un altro evento non marca nulla e restituisce i conflitti;
  `unmarkExclusive(evento)` blocca i quanti in ordine di zona e orario e li libera; `occupyForEvent` / `changeForEvent`
  ammettono solo i quanti esclusivi di *quell'*evento. Una conferma ordinaria in un quanto esclusivo resta
  `EVENT_BLOCKS_SLOT`.
- **`event/`**: `EventStore` (SQL), `EventService` (bozza, modifica, pubblicazione, annullamento, eliminazione, viste
  pubblica e gestori), `EventBooking` (quota per il modulo prenotazioni, con la riga evento bloccata prima della zona),
  `EventController`.
- **Prenotazioni per evento**: `eventId` nella richiesta dal sito e nell'inserimento manuale; giorno e orario
  dell'evento; conferma = quota + registro nella stessa transazione (`EVENT_QUOTA_EXCEEDED`, `CAPACITY_EXCEEDED`);
  annullamento = quota e posti restituiti; persone modificabili, orario fisso (`EVENT_TIME_FIXED`).
- **Annullamento di un evento**: con `cancelReservations` tutte le prenotazioni attive passano ad «Annullata»
  (motivo `EVENT_CANCELLED`) con notifiche accodate; i quanti esclusivi tornano liberi quando non restano confermate
  (anche più tardi, all'annullamento dell'ultima).
- **Dati demo** (solo `local`): format, serata prenotabile e bozza del mockup, con date calcolate da oggi (T-55).

### 1.3 Permessi (verificati dal backend)
| Operazione | Anonimo | STAFF | OWNER |
|---|---|---|---|
| `GET /public/events[/{slug}]`, richiesta per una serata (con CSRF) | ✅ | ✅ | ✅ |
| Elenco, bozza, modifica, pubblicazione di eventi esclusivi, eliminazione bozze | `401` | ✅ | ✅ |
| Evento non esclusivo (creare o renderlo tale) | `401` | `403` | ✅ |
| Annullare un evento con prenotazioni attive | `401` | `403` | ✅ |
| Annullare un evento senza prenotazioni | `401` | ✅ | ✅ |

### 1.4 Frontend
- **Eventi** (sito): elenco, calendario e scheda dal backend, stesse classi; ora di fine; serate annullate con
  l'etichetta «Annullata»; «Richiedi un tavolo per questa serata» solo se la serata è prenotabile, altrimenti il testo
  dei posti spiega perché (posti esauriti, richieste chiuse) — mai numeri di posti.
- **Richiesta per una serata**: lo stesso dialog parte dal passo «Persone» con giorno e orario della serata (non
  modificabili nel riepilogo) e invia `eventId`.
- **Gestione eventi**: elenco con stato, esclusiva/posti condivisi, posti confermati sulla quota e richieste in attesa;
  editor con bozza, pubblica, modifica, annulla (con o senza le prenotazioni), elimina bozza, locandina dalla libreria,
  quota, interruttore «Esclusiva / Posti condivisi» attivo solo per il titolare, conflitti spiegati con i riferimenti e
  il pulsante «Pubblica comunque» solo quando è consentito.
- **Richieste**: la serata compare nella riga e nel dettaglio; «Nuova prenotazione» può essere per una serata.
- Panoramica e barra dell'area gestori senza più la dicitura «eventi dimostrativi».

## 2. Verifiche aperte annotate (non risolte in questa fase, come richiesto)
V-01…V-04 in DECISION_LOG §4-bis e `docs/operations/PRE_DEPLOY.md`: IP del client dietro reverse proxy e rate limiting
in produzione; richieste in attesa che competono per gli stessi posti; sostituzione dei dati demo; privacy e
conservazione. Nessun componente nuovo (Redis, Kafka) introdotto.

## 3. Decisioni
T-47 … T-55 (DECISION_LOG §2): esclusività nel registro, quota, ordine dei lock dell'annullamento, permessi, conflitti,
prenotazioni per evento, sito, orari, dati demo. C-27 … C-29 (§1). API_CONTRACT §6 aggiornato agli endpoint reali.

## 4. File principali
| Area | File |
|---|---|
| Backend | `event/*` (nuovo), `capacity/CapacityLedger`, `capacity/CapacitySlotStore`, `reservation/ReservationService`, `ReservationStore`, `ReservationViews`, `ReservationController`, `audit/AuditAction`, `common/ConstraintViolationTranslator`, `demo/DemoContentLoader` |
| Test backend | `event/EventApiIT` (9), `event/EventConcurrencyIT` (3 × ripetizioni = 20), `DemoContentLoaderIT`, `ReservationRulesTest`, `ReservationTestSupport` (pubblico) |
| Frontend | `api/events.ts` (nuovo), `api/content.ts`, `api/reservations.ts`, `store.tsx`, `data/events.ts`, `data/demo.ts`, `components/BookingDialog.tsx`, `pages/public/Events.tsx`, `pages/admin/EventsAdmin.tsx`, `Requests.tsx`, `Dashboard.tsx`, `AdminLayout.tsx` |
| Test frontend | `tests/events.spec.ts` (nuovo, 3), `navigation.spec.ts`, `package.json` |
| Documenti | questo report, `TEST_REPORT.md`, `LOCAL_SETUP.md`, `WORK_PROGRESS.md`, `PRE_DEPLOY.md` (nuovo), `DECISION_LOG.md`, `API_CONTRACT.md` |

## 5. Risultati dei test
| Suite | Test | Esito |
|---|---|---|
| Backend unità | 70 | ✅ |
| Backend integrazione (PostgreSQL 17 reale) | 294 (+29) | ✅ |
| Playwright con frontend, backend e database reali | 69 (+3) | ✅ |
| **Totale** | **433** (FASE 5: 401) | ✅ |

Dettaglio e CI in `docs/testing/TEST_REPORT.md` §«FASE 6».

## 6. Limitazioni e problemi aperti
- **Una sola zona nella UI**: il backend supporta eventi limitati a una zona separata (`zoneId`, `ZONE_NOT_SEPARATE` con
  una sola zona) ma l'editor non offre la scelta della zona; con più zone la zona di ogni prenotazione per un evento
  «su tutto il locale» si sceglie alla conferma via API.
- **Galleria della serata**: l'editor gestisce la locandina; le altre foto restano quelle già collegate (l'API accetta
  l'elenco completo `mediaIds`).
- **Eventi ricorrenti** («format» con date ripetute) non generano serate automaticamente: ogni data è un evento.
- **Nessun invio reale** di email/SMS (FASE 7 da autorizzare): le notifiche di annullamento restano nell'outbox.
- Restano aperte V-01…V-04 (§2) e le schermate «Account e accessi» e «Categorie» (proposte separate).

## 7. Prossima fase (non avviata)
FASE 7 — Email / SMS / outbox, da avviare solo dopo l'approvazione di questa fase.
