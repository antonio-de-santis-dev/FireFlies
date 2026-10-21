# Contratti API REST V1

> FASE 1 · rev. 2 del 19/10/2026 (revisione finale) · Stato: **proposta da approvare**. Rev. 2: zone di capienza e occupazione per intervalli, eventi esclusivi, eccezione posti OWNER, notifiche con esiti incerti.
> Specifica macchina dei flussi principali: [`api/openapi.yaml`](api/openapi.yaml) (OpenAPI 3.1).
> Base: `https://<dominio>/api/v1`. JSON UTF-8, campi in `camelCase`, date `YYYY-MM-DD`, orari `HH:mm` (ora locale Europe/Rome), istanti ISO-8601 con offset.

## 1. Regole trasversali

| Tema | Regola |
|---|---|
| Versione | prefisso `/api/v1`; modifiche incompatibili → `/api/v2` |
| DTO | record separati dalle entity; le risposte pubbliche non contengono mai dati personali di clienti |
| Autenticazione | cookie di sessione `FF_SESSION` (`HttpOnly; Secure; SameSite=Lax; Path=/`) dopo `POST /auth/login` |
| CSRF | obbligatorio su ogni `POST/PUT/PATCH/DELETE` dell'area gestori e su login/logout: cookie `XSRF-TOKEN` leggibile dal frontend + header `X-XSRF-TOKEN` |
| Endpoint pubblici in scrittura | solo `POST /public/reservations`: niente sessione, protetto da `Idempotency-Key`, rate limit, honeypot |
| Concorrenza | i comandi di modifica dei gestori portano `version`; mismatch → `409 VERSION_CONFLICT` |
| Paginazione | `?page=0&size=25` (max 100); risposta `{ items, page, size, totalItems }` |
| Cache | GET pubbliche di contenuti: `ETag` + `Cache-Control: public, max-age=60`; tutto ciò che riguarda prenotazioni e area gestori: `Cache-Control: no-store` |
| Limiti | corpo JSON ≤ 64 KB; testi con lunghezze massime come in DATA_MODEL |
| Errori | `application/problem+json` (RFC 9457) |

### Modello di errore

```json
{
  "type": "urn:fireflies:problem:capacity-exceeded",
  "title": "Posti esauriti",
  "status": 409,
  "code": "CAPACITY_EXCEEDED",
  "detail": "Alle 20:00 restano 2 posti, la richiesta è per 4 persone.",
  "instance": "/api/v1/admin/reservations/6f1c…/status",
  "errors": [],
  "context": { "zoneId": "5d0a…", "firstFullSlot": "2026-10-24T20:00:00+02:00", "capacityCovers": 40, "occupiedCovers": 38, "remainingCovers": 2 }
}
```
Per errori di validazione `errors` contiene `{ "field": "guestCount", "code": "MAX", "message": "Al massimo 12 persone dal sito." }`. I messaggi sono in italiano e pronti per la UI; `code` è stabile per il codice frontend. Il campo `type` è un URN stabile `urn:fireflies:problem:<codice>` (DECISION_LOG T-07), indipendente dal dominio.

### Codici HTTP

| Codice | Quando | `code` tipici |
|---|---|---|
| 400 | JSON malformato, parametro di tipo errato, header obbligatorio mancante | `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_REQUIRED` |
| 401 | non autenticato / credenziali errate (messaggio generico) | `UNAUTHENTICATED`, `INVALID_CREDENTIALS` |
| 403 | autenticato senza permesso, CSRF mancante/errato | `FORBIDDEN`, `CSRF_INVALID`, `SEATS_OVERRIDE_FORBIDDEN` |
| 404 | risorsa inesistente o non visibile | `NOT_FOUND` |
| 409 | conflitto con lo stato attuale | `CAPACITY_EXCEEDED`, `BAND_CAPACITY_EXCEEDED` (FIX 2), `CLOSURE_HAS_RESERVATIONS` (FIX 2), `EVENT_BLOCKS_SLOT`, `EVENT_QUOTA_EXCEEDED`, `TABLE_CONFLICT`, `VERSION_CONFLICT`, `INVALID_TRANSITION`, `DUPLICATE_REQUEST`, `EVENT_CONFLICTS`, `EVENT_HAS_CONFIRMED_RESERVATIONS`, `CAPACITY_BELOW_CONFIRMED`, `MEDIA_IN_USE`, `CATEGORY_SLUG_TAKEN`, `CATEGORY_NOT_EMPTY` |
| 413 | foto oltre 5 MB | `MEDIA_TOO_LARGE` |
| 422 | dati ben formati ma non accettabili per le regole | `VALIDATION_FAILED`, `SLOT_NOT_AVAILABLE`, `DAY_CLOSED`, `TOO_EARLY`, `TOO_FAR`, `PARTY_TOO_LARGE`, `EVENT_BLOCKS_SLOT` (dal sito), `FULLY_BOOKED`, `WEB_BOOKING_DISABLED`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_LOCAL_TIME`, `NOT_ALIGNED_15_MIN`, `ZONE_REQUIRED`, `ZONE_NOT_SEPARATE`, `TABLE_OUTSIDE_ZONE`, `SEATS_BELOW_PARTY_SIZE`, `OVERRIDE_REASON_REQUIRED`, `MEDIA_INVALID`, `MEDIA_NOT_FOUND` |
| 429 | troppe richieste | `RATE_LIMITED` (+ `Retry-After`) |
| 503 | dipendenza non disponibile (es. storage) | `SERVICE_UNAVAILABLE` |

## 2. Endpoint pubblici

| Metodo e percorso | Descrizione | Cache |
|---|---|---|
| `GET /public/site` | **(FASE 4)** `{ settings, content, announcements, demoContent }`: indirizzo, contatti, mappa, orari dei 7 giorni (`isoDay`, `closed`, `opensAt`, `closesAt`), sezioni della home, testi home/storia, titolari e staff **visibili**, testata del menu (`menuHero.photos`, `intervalSec`), avvisi attivi non scaduti | ETag, 60 s |
| `GET /public/menu` | **(FASE 4)** `{ categories: [{ slug, name, hint, items }], allergens }`: solo categorie e voci visibili; voce = `{ id, name, description, ingredients[], priceCents\|null, demoPriceCents\|null, allergens: { declared, codes[], verifiedAt }, photo }` | ETag, 60 s |
| `GET /public/events?from=&to=` | eventi pubblicati | ETag, 60 s |
| `GET /public/events/{slug}` | dettaglio evento; `booking: { mode, open }` senza numeri di posti esatti (P-26) | ETag, 60 s |
| `GET /public/gallery` | **(FASE 4)** foto in galleria, nell'ordine dei gestori | ETag, 300 s |

Ogni foto (`MediaRef`) è `{ id, alt, width, height, src, srcset }`: `src` è la variante da 960 px (o la più grande
disponibile), `srcset` elenca tutte le varianti (`… 480w, … 960w, … 1600w`). URL pubblici costruiti da
`FIREFLIES_MEDIA_PUBLIC_URL` (in sviluppo `/media`, serviti dal backend; in produzione il dominio CDN del bucket).
| `GET /public/booking-options?date=YYYY-MM-DD[&guests=]` | **(FASE 5)** orari richiedibili del giorno, senza numeri di posti | no-store |
| `POST /public/reservations` | **(FASE 5)** crea una richiesta `PENDING` (mai confermata dall'API pubblica) | no-store |

### `GET /public/booking-options?date=2026-10-24&guests=4` (implementato in FASE 5)
```json
{
  "webBookingEnabled": true, "maxPartySize": 12, "maxAdvanceDays": 60, "minLeadMinutes": 60,
  "firstDate": "2026-10-20", "lastDate": "2026-12-19", "date": "2026-10-24",
  "groups": [
    { "name": "Aperitivo",
      "slots": [ { "time": "18:00:00", "available": true,  "reason": null },
                 { "time": "19:00:00", "available": false, "reason": "FULL" } ] }
  ],
  "closedDates": ["2026-10-28"],
  "unavailableDays": [ { "date": "2026-10-28", "reason": "VENUE_CLOSED" } ]
}
```
- **FIX 2 (audit del calendario)**: `closedDates` (invariato) sono i giorni non selezionabili; `unavailableDays` dà il
  motivo di ciascuno: `VENUE_CLOSED` locale chiuso (orario settimanale o chiusura straordinaria, che prevale),
  `NO_SERVICE_WINDOWS` locale aperto ma nessuna fascia prenotabile per quel giorno della settimana, `ARRIVALS_CLOSED`
  fasce chiuse agli arrivi in quella data. Calcolati a ogni richiesta (`no-store`): una modifica degli orari vale subito.
- Senza `date` (o fuori dai giorni prenotabili) restituisce solo le regole con `groups: []`; con le richieste chiuse
  `webBookingEnabled: false` e nient'altro.
- `reason`: `FULL` (in almeno un quanto della permanenza standard i posti **confermati** liberi sono meno di `guests`,
  default 1), `TOO_EARLY` (meno dell'anticipo minimo), `DAY_CLOSED` (chiusura agli arrivi), `EVENT` (evento esclusivo).
- Nessuna promessa: `available` significa «puoi inviare una richiesta». Il controllo vincolante avviene alla conferma.

### `POST /public/reservations` (implementato in FASE 5)
Header: `Idempotency-Key: <UUID>` (obbligatorio) e `X-XSRF-TOKEN` (come ogni scrittura, T-40).
```json
{ "date": "2026-10-24", "time": "19:30", "guests": 4, "name": "Cliente di prova", "phone": "333 123 4567",
  "email": null, "notes": "Tavolo fuori se possibile", "privacyAccepted": true, "website": "" }
```
`201 Created`
```json
{ "reference": "FF-7K3Q9M", "status": "PENDING", "date": "2026-10-24", "time": "19:30:00", "guests": 4,
  "message": "Richiesta ricevuta: non è ancora una prenotazione. Il locale ti risponderà al più presto al telefono indicato." }
```
- Telefono obbligatorio (normalizzato in E.164, prefisso +39 se manca), email facoltativa, note max 500, nome 2–80.
- `website` è il campo trappola: se compilato → `422`.
- Stessa chiave e stessi dati → di nuovo `201` con lo stesso corpo (nessuna seconda riga); stessa chiave e dati diversi →
  `422 IDEMPOTENCY_KEY_REUSED`; due invii simultanei con la stessa chiave → **entrambi `201` con la stessa ricevuta** (FIX-1; prima il secondo riceveva `409 DUPLICATE_REQUEST`).
- Errori: `400 IDEMPOTENCY_KEY_REQUIRED`, `403 CSRF_INVALID`, `409 DUPLICATE_REQUEST` (FIX 2: stesso telefono con una
  richiesta attiva nello stesso giorno e fascia, anche a un altro orario; messaggio senza dati dell'altra prenotazione), `422 VALIDATION_FAILED` (`context.field`), `422 WEB_BOOKING_DISABLED`, `422 PARTY_TOO_LARGE`
  (`context.maxPartySize`), `422 TOO_FAR`, `422 SLOT_NOT_AVAILABLE`, `422 DAY_CLOSED`, `422 TOO_EARLY`,
  `422 FULLY_BOOKED` (anche budget della fascia esaurito, FIX 2), `422 EVENT_BLOCKS_SLOT`, `429 RATE_LIMITED` (`Retry-After` e `context.retryAfterSeconds`, attesa
  scritta nel messaggio). **FIX-1/FIX 2**: per IP 30 tentativi in 10 minuti e **5** richieste **create** all'ora (gli invii
  rifiutati e i reinvii con la stessa chiave non le consumano), 3/24 h per telefono; `PARTY_TOO_LARGE` oltre la capienza
  del locale o oltre 30 persone.
- `GET /public/booking-options`: limite separato di 120 letture al minuto per IP (`429 RATE_LIMITED` + `Retry-After`).

## 3. Autenticazione (implementata in FASE 3)

| Metodo e percorso | Accesso | Descrizione |
|---|---|---|
| `GET /auth/csrf` | pubblico | imposta il cookie `XSRF-TOKEN` (leggibile dal frontend) e restituisce `{ headerName: "X-XSRF-TOKEN", cookieName: "XSRF-TOKEN" }`; il valore del cookie va rimandato nell'header in ogni scrittura |
| `POST /auth/login` | pubblico + CSRF | `{ email, password }` → `200 { user: { id, email, displayName, role } }` e cookie `FF_SESSION`. Campi in più (es. `role`) → `400 MALFORMED_REQUEST`. Errori: `401 INVALID_CREDENTIALS` (stesso testo per email inesistente, password errata, account disattivato), `429 RATE_LIMITED` con `Retry-After`, `403 CSRF_INVALID` |
| `GET /auth/session` | pubblico | sempre `200`: `{ authenticated, user, reason }`; `reason = "SESSION_EXPIRED"` se il browser presentava una sessione scaduta o chiusa (usato dal frontend senza generare errori in console) |
| `GET /auth/me` | autenticato | `200 { id, email, displayName, role }`; altrimenti `401 UNAUTHENTICATED` o `401 SESSION_EXPIRED` |
| `POST /auth/logout` | CSRF | `204`; sessione cancellata dal database, cookie eliminato |
| `POST /auth/password` | autenticato + CSRF | `{ currentPassword, newPassword }` → `204`; chiude le altre sessioni dell'account. Errori: `422 CURRENT_PASSWORD_WRONG`, `422 WEAK_PASSWORD` |

**Regole**: il ruolo non si sceglie mai al login, viene dall'account. Nuovo id di sessione e nuovo token CSRF a ogni login (anti session fixation). Sessione scaduta dopo 8 ore di inattività. 5 tentativi falliti per email (anche inesistente) o 20 per IP in 15 minuti → blocco di 15 minuti; l'account resta bloccato anche dopo un riavvio (colonna `locked_until`). Password: almeno 12 caratteri, massimo 72 byte, non ripetitiva, senza il nome dell'email; hash bcrypt costo 12.

### Gestione degli account (solo OWNER)

| Metodo e percorso | Descrizione |
|---|---|
| `GET /admin/users` | elenco `{ id, email, displayName, role, active, lastLoginAt, lockedUntil, version }` (mai hash) |
| `POST /admin/users` | `{ email, displayName, role, password }` → `201`. Errori: `409 EMAIL_TAKEN`, `422 WEAK_PASSWORD`, `400` ruolo inesistente |
| `PATCH /admin/users/{id}` | `{ version, displayName?, role?, active? }`; cambio di ruolo o stato → **tutte le sessioni dell'account chiuse**. Errori: `409 VERSION_CONFLICT`, `409 LAST_OWNER` (deve restare almeno un titolare attivo) |
| `POST /admin/users/{id}/password` | `{ newPassword }` → `204`; sblocca l'account e chiude le sue sessioni |

STAFF su questi endpoint → `403 FORBIDDEN` (registrato nell'audit come `ACCESS_DENIED`). Il primo titolare non si crea via API: procedura amministrativa (LOCAL_SETUP §3).

## 4. Area gestori — prenotazioni (implementato in FASE 5)

Tutte richiedono sessione e CSRF sulle scritture; ruolo minimo STAFF salvo dove indicato.

| Metodo e percorso | Descrizione |
|---|---|
| `GET /admin/reservations?from&to&status&source&q&needsCallback&page&size` | elenco: `{ items, total, page, size, counts }`; `status` accetta più valori separati da virgola; `q` cerca riferimento, nome, email e cifre del telefono; `counts` per stato con gli stessi filtri **escluso** lo stato; ordine: in attesa prima, poi per orario; `size` max 200 |
| `GET /admin/reservations/{id}` | dettaglio (`Detail`, sotto) |
| `POST /admin/reservations` | inserimento manuale (`source: PHONE\|EMAIL`, `confirmNow`) |
| `PATCH /admin/reservations/{id}` | modifica dati, orario, durata, persone, zona (`version` obbligatoria); se CONFIRMED rilascia e rioccupa in una transazione (posti insufficienti → `409 CAPACITY_EXCEEDED`, nulla cambia). Chiusa → solo note e recapiti |
| `PATCH /admin/reservations/{id}/status` | transizione di stato |
| `PUT /admin/reservations/{id}/tables` | `{ version, tableIds, overrideSeats, overrideReason }`; sostituisce i tavoli (`[]` = nessuno); solo CONFIRMED |
| `GET /admin/capacity?date=` | per ogni zona attiva, quanto per quanto nelle fasce del giorno: `{ time, capacity, confirmed, pending, event }` |

**Summary** (elemento dell'elenco): `{ id, reference, status, statusReason, source, date, time, endTime, guests,
customerName, customerPhone, customerEmail, notes, needsCallback, zoneId, tables[], createdAt, version }`.

**Detail**: `{ summary, notes, internalNote, zoneName, durationMinutes, createdBy, handledBy, confirmedAt, closedAt,
updatedAt, tables[], history[], changes[], notifications[], capacity, actions[], warnings[] }`
- `history`: `{ fromStatus, toStatus, reason, note, by, at }`; `changes`: voci dell'audit della prenotazione.
- `notifications`: `{ template, channel, recipient, state, skipReason, delivered, createdAt }` — `delivered` è vero solo
  per un invio reale (`SENT`), mai in V1.
- `capacity` (solo PENDING/CONFIRMED): `{ zoneId, capacity, minFree, pendingOthers, eventBlocks, fits }`.
- `actions`: quelle possibili adesso tra `CONFIRM, REJECT, CANCEL, EDIT, TABLES, COMPLETE, NO_SHOW, CORRECT, NOTES`
  (`CORRECT` = NO_SHOW↔COMPLETED, solo OWNER entro 48 h).

### `PATCH /admin/reservations/{id}/status`
```json
{ "version": 3, "status": "CONFIRMED", "zoneId": null, "tableIds": ["c2d1…"],
  "overrideSeats": false, "overrideReason": null, "reason": null, "note": null }
```
- Stesso stato già raggiunto → `200` senza effetti (doppio clic). Versione diversa → `409 VERSION_CONFLICT`.
- `reason` obbligatorio per `REJECTED` (`NO_CAPACITY`, `VENUE_DECISION`, `DUPLICATE`, `OTHER`) e `CANCELLED`
  (`CUSTOMER_REQUEST`, `VENUE_DECISION`, `DUPLICATE`, `OTHER`).
- `zoneId`: obbligatorio solo se il locale ha più zone e la richiesta non ne indica una (`422 ZONE_REQUIRED`).
- `tableIds`: facoltativo; tavoli attivi della stessa zona (`422 TABLE_OUTSIDE_ZONE`).
- `overrideSeats` + `overrideReason`: eccezione posti tavolo, **solo OWNER** (`403 SEATS_OVERRIDE_FORBIDDEN` per STAFF;
  motivo mancante → `422 OVERRIDE_REASON_REQUIRED`); registrata nell'audit (`TABLE_SEATS_OVERRIDE`).

`200` → `Detail` aggiornato. Errori: `409 CAPACITY_EXCEEDED`, `409 EVENT_BLOCKS_SLOT`, `409 TABLE_CONFLICT`,
`409 VERSION_CONFLICT`, `409 INVALID_TRANSITION` (es. annullare dopo l'inizio, completare prima dell'arrivo,
«non presentato» prima di 15 minuti dall'arrivo, confermare un orario passato), `422 SEATS_BELOW_PARTY_SIZE`
(`context.seats`, `context.guests`), `422 VALIDATION_FAILED`, `403 FORBIDDEN` (correzione da STAFF).

COMPLETED / NO_SHOW: i quanti futuri (dal quarto d'ora successivo) tornano liberi e le assegnazioni dei tavoli si accorciano.

### `POST /admin/reservations` (telefono o email)
```json
{ "source": "PHONE", "date": "2026-10-25", "time": "20:00", "guests": 6, "name": "Cliente al telefono",
  "phone": "0836 000 000", "email": null, "durationMinutes": 120, "zoneId": null,
  "notes": null, "internalNote": "Ha chiamato alle 11", "confirmNow": true, "needsCallback": false, "tableIds": [] }
```
`201` con il `Detail`; `warnings` può contenere `OUTSIDE_SLOTS`, `DAY_CLOSED`, `SHORT_NOTICE`. Orario a passi di 15 minuti
(`422 NOT_ALIGNED_15_MIN`), nel futuro, in un giorno con almeno una fascia della zona (`422 NO_SERVICE_WINDOW`).
`durationMinutes` (multiplo di 15, 15–720) è facoltativo: default la permanenza standard della fascia. Con `confirmNow`
valgono tutti i controlli della conferma e tutto si annulla se i posti non bastano.

## 5. Area gestori — configurazione prenotazioni (implementata in FASE 5)

`GET /admin/booking-config` (STAFF e OWNER): `{ zones, areas, tables, windows, policy, exceptions, venueCapacity, openingExceptions }` (FIX-1: ultimi due). Tutte le scritture
solo OWNER (regola sull'URL e `@PreAuthorize`).


| Metodo e percorso | Descrizione |
|---|---|
| `PUT /admin/venue-capacity` **(FIX-1)** | `{ capacity: 1…1000 }`: «Capienza massima del locale», unica capienza, uguale in ogni giorno e fascia; sotto i confermati → `409 CAPACITY_BELOW_CONFIRMED` (`context.conflicts[{ slotStart, occupiedCovers, newCapacity }]`; FIX 2: anche una fascia futura con più confermate della nuova capienza, `context.bandConflicts[{ date, band, confirmed }]`), nulla cambia; più zone attive → `409 MULTIPLE_ZONES` |
| `POST /admin/capacity-zones`, `PUT /admin/capacity-zones/{id}` | zone e capienza fisica (`version`); riduzione sotto i confermati → `409 CAPACITY_BELOW_CONFIRMED` con i quanti in conflitto; nome già usato → `409 NAME_TAKEN` |
| `POST /admin/areas`, `PUT/DELETE /admin/areas/{id}` | aree, ognuna in una zona (`DELETE` con tavoli → `409`) |
| `POST /admin/tables`, `PUT/DELETE /admin/tables/{id}` | tavoli con posti min/max; `DELETE` → `204`, oppure `200 { deactivated: true }` se ha storico |
| `POST /admin/service-windows`, `PUT/DELETE /admin/service-windows/{id}` | fasce per giorno della settimana: primo e ultimo arrivo (multipli di 15'), passo 15/30/60, permanenza 30–360', validità (FIX-1: niente più `maxCovers`); mai oltre la mezzanotte; la capienza dei quanti futuri viene ricalcolata (stesso controllo `409`) |
| `PUT /admin/booking-policy` | regole: `webBookingEnabled` (aprirle richiede una zona attiva), `minLeadMinutes`, `maxAdvanceDays`, `pendingExpiryMinutesBeforeStart` (FIX-1: via `maxPartySizeWeb`, il limite segue la capienza del locale, mai oltre 30) |
| `POST /admin/booking-exceptions`, `DELETE /admin/booking-exceptions/{id}` | `CLOSED` (chiusura agli arrivi) per data, di una fascia o di tutto il giorno (FIX-1: `CAPACITY_OVERRIDE` non esiste più → `422`) |
| `GET /admin/opening-exceptions`, `POST /admin/opening-exceptions`, `PUT/DELETE /admin/opening-exceptions/{id}` (OWNER) **(FASE 7, FIX-1)** | chiusura o apertura straordinaria di un giorno o di un intervallo con estremi compresi `{ from, to?, closed, opensAt?, closesAt?, note? }` (`day` = nome della FASE 7 per il giorno singolo, accettato) → `201/200 { exception: { id, day, endDay, … }, activeReservations, conflicts: [{ date, pending, confirmed, confirmedGuests }] }`: le prenotazioni restano valide, si gestiscono a mano; **FIX 2:** una chiusura su giorni con prenotazioni attive senza `confirmReservations: true` → `409 CLOSURE_HAS_RESERVATIONS` `{ conflicts, pending, confirmed }` e nulla salvato; data passata, fine prima dell'inizio, intervallo oltre un anno, apertura senza orari → `422`; giorni sovrapposti a un'altra eccezione → `409 EXCEPTION_OVERLAPS` (anche con salvataggi contemporanei) |
| `GET /admin/capacity-summary?from=&to=&band=` (STAFF) **(FASE 7, `band` FIX-1)** | righe per giorno e fascia (con `band` una sola fascia e una riga per ogni giorno) `{ date, band, label, closed, requests, pending, confirmed, guests, capacity, available, fromStatistics, bandCapacity, bandConfirmed, bandRemaining, physicalAvailable, eventArrivals, arrivals, limitedByPhysical }` (al massimo 62 giorni, `no-store`); **FIX 2:** `available` = minimo tra il gruppo più grande che sta fisicamente in un orario della fascia (`physicalAvailable`) e il budget restante della fascia (`bandRemaining`) |
| `GET /admin/reservations/today?band=` (STAFF) **(FASE 7)** | solo `CONFIRMED` di oggi (Europe/Rome): `{ date, band, items: [{ id, reference, time, name, guests, band, bandLabel, event }], bands: [{ band, label, reservations, guests }], reservations, guests }`; niente telefono, email o note; `no-store` |

## 6. Eventi (implementato in FASE 6)

### Pubblico
| Metodo e percorso | Descrizione |
|---|---|
| `GET /public/events` | eventi pubblicati e annullati, format senza data e serate non ancora finite; `Cache-Control: no-cache` (FASE 7: prima `max-age=60`, un evento appena pubblicato poteva non comparire per un minuto) |
| `GET /public/events/{slug}` | scheda; bozza o inesistente → `404` |

`PublicEvent`: `{ id, slug, title, kind: SERATA|FORMAT, status: PUBLISHED|CANCELLED, date, startTime, endTime, artist,
summary, body[] (paragrafi), poster, media[], booking: { mode: NONE|REQUEST, open, reason } }` — `reason`: `NONE`, `FULL`,
`CLOSED` (richieste dal sito chiuse), `TOO_LATE` (oltre l'anticipo minimo), `CANCELLED`. Nessun numero di posti.

Una richiesta per la serata usa `POST /public/reservations` con `eventId` (giorno e orario presi dall'evento). Errori in
più: `422 EVENT_NOT_BOOKABLE`, `422 EVENT_FULL`, `422 TOO_EARLY`.

### Gestori (STAFF e OWNER, CSRF sulle scritture)
| Metodo e percorso | Descrizione |
|---|---|
| `GET /admin/events`, `GET /admin/events/{id}` | elenco/dettaglio: `AdminEvent` con `confirmedCovers`, `pendingCovers`, `confirmedReservations`, `pendingReservations`, `version`, `warnings` |
| `POST /admin/events` | `{ title, kind, date, startTime, endTime, zoneId, exclusive (default true), bookingMode, capacityCovers, artist, summary, body, posterMediaId, mediaIds[], visible (default false), acknowledgePending }`; `slug` dal titolo (univoco). **FASE 8:** `endTime` e `capacityCovers` facoltativi (`null` = ricavati: durata già salvata o 4 ore; quota già salvata o capienza fisica del locale, mai sotto i confermati — BOOKING_RULES §7.5); `date` e `startTime` vanno indicati insieme. **FASE 7:** `visible: true` = pubblicato nella stessa transazione, con gli stessi errori di `publish`; se fallisce non resta nulla |
| `PUT /admin/events/{id}` | modifica `{ …, version, acknowledgePending, visible }` (`visible` assente = stato invariato; `true` su una bozza = pubblica; `false` su un pubblicato = torna bozza, solo senza prenotazioni attive → `409 EVENT_HAS_ACTIVE_RESERVATIONS`). Pubblicato: cambio di orario/zona/esclusività con confermate → `409 EVENT_HAS_CONFIRMED_RESERVATIONS`, senza → riserva spostata con gli stessi controlli della pubblicazione; quota sotto i confermati → `409 CAPACITY_BELOW_CONFIRMED` |
| `PUT /admin/events/{id}/visibility` **(FIX 2)** | interruttore dell'elenco `{ version, visible, acknowledgePending? }` → `AdminEvent`; già nello stato chiesto → stessa risposta; stessi errori di `publish` e del ritorno a bozza (`EVENT_CONFLICTS`, `EVENT_HAS_ACTIVE_RESERVATIONS`, `VERSION_CONFLICT`, annullato → `409 INVALID_TRANSITION`) |
| `POST /admin/events/{id}/publish` | `{ version, acknowledgePending }` → `409 EVENT_CONFLICTS` (sotto); `422 EVENT_DATE_REQUIRED`, `422 EVENT_IN_PAST`; `warnings`: `PENDING_REQUESTS_BLOCKED`, `EVENT_QUOTA_NOT_GUARANTEED` |
| `POST /admin/events/{id}/cancel` | `{ version, cancelReservations }`; con prenotazioni attive solo OWNER (`403`); con `true` le prenotazioni passano ad «Annullata» motivo `EVENT_CANCELLED`, posti e quota rilasciati, notifiche accodate |
| `DELETE /admin/events/{id}` | **FASE 7:** qualunque stato senza prenotazioni → `204` (riserva esclusiva liberata); prenotazioni attive → `409 EVENT_HAS_ACTIVE_RESERVATIONS`; solo storico chiuso → `409 EVENT_HAS_HISTORY` |

Validazioni: `INVALID_TIME` (FIX 2: orari a qualunque minuto, mai con secondi; prima `NOT_ALIGNED_15_MIN`), `VALIDATION_FAILED` (titolo, data senza ora di inizio o viceversa, «su richiesta» senza data),
`VENUE_CAPACITY_MISSING` (`422`, FASE 8: quota da ricavare ma capienza del locale non configurata),
`QUOTA_ABOVE_CAPACITY`, `ZONE_NOT_SEPARATE` (zona indicata con una sola zona attiva), `EXCLUSIVITY_OWNER_ONLY` (`403`),
`MEDIA_NOT_FOUND`, `NAME_TAKEN` (slug in gara).

`409 EVENT_CONFLICTS` — `context`: `{ confirmed: [{ reference, date, time, endTime, guests, status }], pending: [...],
otherEvent: bool, acknowledgeable: bool }`; mai nomi, telefoni o email.

## 7. Area gestori — contenuti, menu, media, notifiche, utenti

| Area | Endpoint | Ruolo |
|---|---|---|
| Menu **(FASE 4)** | `GET /admin/menu` → `{ categories, items, allergens }` (anche voci e categorie nascoste); `POST /admin/menu/categories` `{ name, hint, slug?, visible }` (slug dal nome se assente; `409 CATEGORY_SLUG_TAKEN`), `PUT …/categories/{id}`, `DELETE …/categories/{id}` (`409 CATEGORY_NOT_EMPTY`), `PUT …/categories/order` `{ categoryIds }`; `POST /admin/menu/items`, `PUT …/items/{id}` (con `version`), `PATCH …/items/{id}/visibility` `{ version, visible }`, `DELETE …/items/{id}`. Voce: `{ categoryId, name, description, ingredients[], priceCents (intero 0…100000 o null), allergens: { declared, codes[] }, photoMediaId, visible }`; codici allergeni UE (`GLUTEN`, `MILK`, …); `codes` non vuoto con `declared: false` → `422`; `6.5` in `priceCents` → `400`; `demoPriceCents` non scrivibile | STAFF |
| Media **(FASE 4, revisione T-26)** | `GET /admin/media` (libreria con `usages`); `POST /admin/media` multipart `file` + `altText` (5…250 caratteri) + `credit?` → `201` con varianti, `422 MEDIA_INVALID`, `413 MEDIA_TOO_LARGE`, `503 SERVICE_UNAVAILABLE` (storage) + `Retry-After`; `PUT /admin/media/{id}/file` multipart → stesso id, file nuovo; `PATCH /admin/media/{id}` `{ altText?, credit?, inGallery? }`; `PUT /admin/media/order` `{ mediaIds }`; `DELETE /admin/media/{id}` → `204` o `409 MEDIA_IN_USE` con `context.usages[{ type, label }]` | STAFF |
| Avvisi **(FASE 4)** | `GET/POST /admin/announcements`, `PUT/DELETE …/{id}`; `{ text (5…140), tone: INFO\|IMPORTANT, active, visibleUntil (data, inclusa, ora italiana) }` | STAFF |
| Contenuti sito **(FASE 4, T-31)** | `GET/PUT /admin/site-content`: un unico documento con `version`, `heroClaim`, `heroLead`, `heroMediaId`, `sections{codice: bool}`, `storyTitle`, `storyText`, `storyConfirmed`, `owners[]` (`id` null = nuovo; mancanti = rimossi), `ownersConfirmed`, `staff[]`, `staffConfirmed`, `menuHeroMediaIds[]` (max 10), `menuHeroIntervalSec` (10…600) | OWNER |
| Impostazioni **(FASE 4, T-31)** | `GET/PUT /admin/site-settings`: `version`, `addressLine`, `addressConfirmed`, `mapQuery`, `mapMode: CONSENT\|AUTO`, `phone`, `email`, `instagramUrl`, `hoursConfirmed`, `hours[7]` (chiusura prima dell'apertura = dopo mezzanotte), `confirmClosures` (FIX 2: un giorno che passa a chiuso con prenotazioni attive future → `409 CLOSURE_HAS_RESERVATIONS` finché non è `true`). Eccezioni di apertura: fasi successive | OWNER |
| Notifiche **(FASE 7, implementato)** | stato nel dettaglio della prenotazione (`notifications[]` con `id`, `state`, `skipReason`, `delivered`, `simulated`, `resendable`); `POST /admin/notifications/{id}/resend` → `202`, solo `FAILED`/`DEAD`/`UNCERTAIN`, al massimo 3 volte (`409 RESEND_LIMIT`), altrimenti `409 INVALID_TRANSITION`; stessa chiave di idempotenza; audit `NOTIFICATION_RESENT`. **Non implementati** (proposta della FASE 1): elenco `GET /admin/notifications`, `mark-handled`, conferma del rischio di doppio invio | STAFF |
| Template | `GET/PUT /admin/notification-templates/{code}/{channel}` — **non implementato** (FASE 7: modelli nel seed V7, attivi solo nei dati demo) | OWNER |
| SMS | `PUT /admin/site-settings` campo `smsEnabled` — efficace solo con profilo `prod` e provider configurato (ADR-0005); in `local`/`test` resta sempre `fake` | OWNER |
| Utenti | `GET/POST /admin/users`, `PUT /admin/users/{id}`, `POST /admin/users/{id}/password-reset` | OWNER |
| Audit | `GET /admin/audit?objectType=&objectId=&from=&to=` | OWNER |

## 8. Corrispondenza con il mockup

| Mockup (store in memoria) | API |
|---|---|
| `request/status` | `PATCH /admin/reservations/{id}/status` |
| stato `da-contattare` | campo `needsCallback` |
| `notice` (SMS simulato) | `notifications[]` nella prenotazione (stati `PENDING/SENDING/SENT/DELIVERED/UNCERTAIN/FAILED/DEAD/SKIPPED`) |
| `menu/save`, `menu/toggle` | `PUT /admin/menu/items/{id}`, `PATCH …/visibility` |
| `media/add` (blob URL) | `POST /admin/media` multipart (T-26) |
| `media/remove` bloccato se in uso | `DELETE /admin/media/{id}` → `409 MEDIA_IN_USE` |
| `settings/save`, `content/save` | `PUT /admin/site-settings`, `PUT /admin/site-content` |
| `session {role}` | `POST /auth/login` + `GET /auth/me` |
| fasce fisse nel `BookingDialog` | `GET /public/booking-options` |
