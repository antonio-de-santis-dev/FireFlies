# Contratti API REST V1

> FASE 1 · 19/10/2026 · Stato: **proposta da approvare**. Specifica macchina dei flussi principali: [`api/openapi.yaml`](api/openapi.yaml) (OpenAPI 3.1).
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
  "type": "https://fireflies.example/problems/capacity-exceeded",
  "title": "Posti esauriti",
  "status": 409,
  "code": "CAPACITY_EXCEEDED",
  "detail": "Per questa fascia restano 2 coperti, la richiesta è per 4 persone.",
  "instance": "/api/v1/admin/reservations/6f1c…/status",
  "errors": [],
  "context": { "capacityCovers": 40, "confirmedCovers": 38, "remainingCovers": 2 }
}
```
Per errori di validazione `errors` contiene `{ "field": "guestCount", "code": "MAX", "message": "Al massimo 12 persone dal sito." }`. I messaggi sono in italiano e pronti per la UI; `code` è stabile per il codice frontend. (`fireflies.example` è un segnaposto: il dominio reale si decide in FASE 8.)

### Codici HTTP

| Codice | Quando | `code` tipici |
|---|---|---|
| 400 | JSON malformato, parametro di tipo errato, header obbligatorio mancante | `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_REQUIRED` |
| 401 | non autenticato / credenziali errate (messaggio generico) | `UNAUTHENTICATED`, `INVALID_CREDENTIALS` |
| 403 | autenticato senza permesso, CSRF mancante/errato | `FORBIDDEN`, `CSRF_INVALID` |
| 404 | risorsa inesistente o non visibile | `NOT_FOUND` |
| 409 | conflitto con lo stato attuale | `CAPACITY_EXCEEDED`, `TABLE_CONFLICT`, `VERSION_CONFLICT`, `INVALID_TRANSITION`, `DUPLICATE_REQUEST`, `EVENT_CONFLICTS`, `CAPACITY_BELOW_CONFIRMED`, `MEDIA_IN_USE` |
| 422 | dati ben formati ma non accettabili per le regole | `VALIDATION_FAILED`, `SLOT_NOT_AVAILABLE`, `DAY_CLOSED`, `TOO_EARLY`, `TOO_FAR`, `PARTY_TOO_LARGE`, `EVENT_BLOCKS_SLOT`, `FULLY_BOOKED`, `WEB_BOOKING_DISABLED`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_LOCAL_TIME` |
| 429 | troppe richieste | `RATE_LIMITED` (+ `Retry-After`) |
| 503 | dipendenza non disponibile (es. storage) | `SERVICE_UNAVAILABLE` |

## 2. Endpoint pubblici

| Metodo e percorso | Descrizione | Cache |
|---|---|---|
| `GET /public/site` | impostazioni pubbliche, orari pubblici + eccezioni, sezioni attive, contenuti home/storia, titolari, staff, avvisi attivi, hero del menu | ETag, 60 s |
| `GET /public/menu` | categorie e voci visibili, prezzo (`priceCents` o `null`), allergeni (`declared` + elenco), foto con varianti | ETag, 60 s |
| `GET /public/events?from=&to=` | eventi pubblicati | ETag, 60 s |
| `GET /public/events/{slug}` | dettaglio evento; `booking: { mode, open }` senza numeri di posti esatti (P-26) | ETag, 60 s |
| `GET /public/gallery` | foto in galleria con varianti | ETag, 300 s |
| `GET /public/booking-options?date=YYYY-MM-DD[&eventId=]` | fasce e slot del giorno con stato: `OPEN`, `FULL`, `CLOSED`, `EVENT`, `TOO_EARLY`; regole (max persone, messaggi) | no-store |
| `POST /public/reservations` | crea una richiesta `PENDING` | no-store |

### `GET /public/booking-options?date=2026-10-24`
```json
{
  "date": "2026-10-24",
  "webBookingEnabled": true,
  "maxPartySize": 12,
  "message": "Ti confermiamo il tavolo appena possibile. La richiesta non è ancora una prenotazione.",
  "windows": [
    { "windowId": "0b6e…", "name": "Aperitivo",
      "slots": [ { "time": "18:00", "state": "OPEN" }, { "time": "18:30", "state": "OPEN" },
                 { "time": "19:00", "state": "FULL" } ] },
    { "windowId": null, "name": "DJ set di esempio", "eventId": "a41c…",
      "slots": [ { "time": "22:00", "state": "EVENT" } ] }
  ]
}
```
Nessuna promessa di disponibilità: `OPEN` significa «puoi inviare una richiesta». Le fasce del dialog del mockup oggi codificate (Colazione/Pomeriggio/Aperitivo/Sera) verranno da qui.

### `POST /public/reservations`
Header: `Idempotency-Key: 3a9e5d0c-…` (UUID, obbligatorio).
```json
{
  "date": "2026-10-24",
  "time": "19:30",
  "guestCount": 4,
  "eventId": null,
  "areaPreferenceId": null,
  "customer": { "name": "Cliente demo", "phone": "+39 000 000 0001", "email": null },
  "notes": "Tavolo fuori se possibile",
  "privacyNoticeVersion": "2026-10",
  "privacyAcknowledged": true,
  "website": ""
}
```
`201 Created`
```json
{ "publicRef": "FF-7K3Q9M", "status": "PENDING", "date": "2026-10-24", "time": "19:30", "guestCount": 4,
  "message": "Richiesta inviata. Non è ancora una prenotazione: ti contatteremo per confermare." }
```
Ripetizione con stessa chiave e stessi dati → `200` con lo stesso corpo. Errori: `400 IDEMPOTENCY_KEY_REQUIRED`, `409 DUPLICATE_REQUEST`, `422 …` (vedi tabella), `429`.

## 3. Autenticazione

| Metodo e percorso | Descrizione |
|---|---|
| `GET /auth/csrf` | imposta il cookie `XSRF-TOKEN` e restituisce `{ headerName, token }` |
| `POST /auth/login` | `{ email, password }` → `200 { user: { id, displayName, role } }`; errori `401 INVALID_CREDENTIALS` (generico), `429 RATE_LIMITED` |
| `POST /auth/logout` | `204`, sessione invalidata |
| `GET /auth/me` | `200 { id, displayName, role }` oppure `401` |
| `POST /auth/password` | cambio password autenticato `{ currentPassword, newPassword }` → `204` |

Login: dopo 5 errori per account → blocco 15 minuti; limite anche per IP. Rigenerazione dell'id di sessione al login (anti session fixation). Sessione: scadenza per inattività 8 h (P-27). Reset password: OWNER reimposta quella dello STAFF; reset self-service via email solo quando il provider email è attivo (FASE 7).

## 4. Area gestori — prenotazioni

Tutte richiedono sessione; ruolo minimo STAFF salvo dove indicato.

| Metodo e percorso | Descrizione |
|---|---|
| `GET /admin/reservations?dateFrom&dateTo&status&source&eventId&q&needsCallback&page&size&sort` | elenco filtrato (q = riferimento, nome o telefono) |
| `GET /admin/reservations/{id}` | dettaglio + storico + tavoli + stato notifiche |
| `POST /admin/reservations` | inserimento manuale (`source: PHONE|EMAIL`, `confirmNow` facoltativo); header `Idempotency-Key` |
| `PATCH /admin/reservations/{id}` | modifica dati (`version` obbligatoria) |
| `PATCH /admin/reservations/{id}/status` | transizione di stato |
| `PUT /admin/reservations/{id}/tables` | sostituisce i tavoli assegnati (`[]` = nessuno) |
| `GET /admin/capacity?date=` | per ogni fascia/evento del giorno: capienza, confermati, in attesa, residui |

### `PATCH /admin/reservations/{id}/status`
```json
{ "version": 3, "status": "CONFIRMED", "tableIds": ["c2d1…"], "reason": null, "note": null }
```
`200` → prenotazione aggiornata:
```json
{
  "id": "6f1c…", "publicRef": "FF-7K3Q9M", "status": "CONFIRMED", "version": 4,
  "source": "WEB", "date": "2026-10-24", "time": "19:30", "endTime": "21:30", "guestCount": 4,
  "customer": { "name": "Cliente demo", "phone": "+390000000001", "email": null },
  "notes": "Tavolo fuori se possibile", "internalNote": null, "needsCallback": false,
  "window": { "id": "0b6e…", "name": "Aperitivo" }, "event": null,
  "tables": [ { "id": "c2d1…", "code": "T4", "area": "Dehors", "maxSeats": 4 } ],
  "notifications": [ { "channel": "SMS", "state": "SKIPPED", "reason": "SMS_DISABLED" } ],
  "warnings": []
}
```
Errori: `409 CAPACITY_EXCEEDED` (con `context`), `409 TABLE_CONFLICT` (`context.conflictingRef`), `409 VERSION_CONFLICT`, `409 INVALID_TRANSITION`, `422 VALIDATION_FAILED` (es. motivo mancante su REJECTED), `403` (correzione NO_SHOW↔COMPLETED da STAFF).

### `POST /admin/reservations` (telefono)
```json
{ "source": "PHONE", "date": "2026-10-25", "time": "20:00", "guestCount": 6, "eventId": null,
  "customer": { "name": "Cliente demo B", "phone": "+39 000 000 0002", "email": null },
  "notes": null, "internalNote": "Ha chiamato alle 11", "confirmNow": true, "tableIds": [] }
```
`201` con la prenotazione; `warnings` può contenere `OUTSIDE_SLOTS`, `SHORT_NOTICE`, `EVENT_OVERLAP`, `SEATS_BELOW_PARTY_SIZE`.

## 5. Area gestori — configurazione prenotazioni (OWNER)

| Metodo e percorso | Descrizione |
|---|---|
| `GET/POST /admin/areas`, `PUT/DELETE /admin/areas/{id}` | aree (`DELETE` → `409` se ha tavoli) |
| `GET/POST /admin/tables`, `PUT/DELETE /admin/tables/{id}` | tavoli (`DELETE` → disattiva se ha storico) |
| `GET/POST /admin/service-windows`, `PUT/DELETE /admin/service-windows/{id}` | fasce; cambio capienza → `409 CAPACITY_BELOW_CONFIRMED` con date in conflitto |
| `GET /admin/booking-policy`, `PUT /admin/booking-policy` | regole generali (nuova riga con `effectiveFrom`) |
| `GET/POST /admin/booking-exceptions`, `DELETE /admin/booking-exceptions/{id}` | chiusure e capienze speciali per data |

## 6. Area gestori — eventi

| Metodo e percorso | Ruolo | Descrizione |
|---|---|---|
| `GET /admin/events`, `GET /admin/events/{id}` | STAFF | elenco/dettaglio con contatore |
| `POST /admin/events`, `PUT /admin/events/{id}` | STAFF | bozza/modifica (`version`) |
| `POST /admin/events/{id}/publish` | STAFF | `{ version, acknowledgeConflicts }` → `409 EVENT_CONFLICTS` con elenco se ci sono sovrapposizioni non riconosciute |
| `POST /admin/events/{id}/cancel` | OWNER | `{ version, cancelReservations: true|false }` |

`409 EVENT_CONFLICTS`:
```json
{ "status": 409, "code": "EVENT_CONFLICTS", "title": "Ci sono prenotazioni nello stesso orario",
  "context": { "conflicts": [ { "publicRef": "FF-2M8R4T", "time": "21:30", "guestCount": 4, "status": "CONFIRMED" } ] } }
```

## 7. Area gestori — contenuti, menu, media, notifiche, utenti

| Area | Endpoint | Ruolo |
|---|---|---|
| Menu | `GET/POST /admin/menu/categories`, `PUT/DELETE …/{id}`; `GET/POST /admin/menu/items`, `PUT/DELETE …/{id}`, `PATCH …/{id}/visibility` | STAFF |
| Media | `POST /admin/media/upload-intents` → `{ mediaId, uploadUrl, method: "PUT", headers, expiresAt }` (URL firmato 5 min, limite 5 MB e content-type); `POST /admin/media/{id}/finalize` `{ altText, credit }` → verifica tipo reale, dimensioni, ricodifica → `READY` o `422 MEDIA_INVALID`; `GET /admin/media`, `PATCH /admin/media/{id}`, `DELETE /admin/media/{id}` (`409 MEDIA_IN_USE` con elenco usi) | STAFF |
| Galleria | `PUT /admin/gallery` (ordine e presenza) | STAFF |
| Avvisi | `GET/POST /admin/announcements`, `PUT/DELETE …/{id}` | STAFF |
| Contenuti sito | `GET/PUT /admin/site-content`, `PUT /admin/site-sections`, `PUT /admin/hero-slides/{page}`, `GET/POST/PUT/DELETE /admin/owners`, `/admin/staff-members` | OWNER |
| Impostazioni | `GET/PUT /admin/site-settings`, `PUT /admin/opening-hours`, `GET/POST/DELETE /admin/opening-exceptions` | OWNER |
| Notifiche | `GET /admin/notifications?reservationId=&state=`; `POST /admin/notifications/{id}/retry` (solo `DEAD`/`FAILED`) | STAFF |
| Template | `GET/PUT /admin/notification-templates/{code}/{channel}` | OWNER |
| Utenti | `GET/POST /admin/users`, `PUT /admin/users/{id}`, `POST /admin/users/{id}/password-reset` | OWNER |
| Audit | `GET /admin/audit?objectType=&objectId=&from=&to=` | OWNER |

## 8. Corrispondenza con il mockup

| Mockup (store in memoria) | API |
|---|---|
| `request/status` | `PATCH /admin/reservations/{id}/status` |
| stato `da-contattare` | campo `needsCallback` |
| `notice` (SMS simulato) | `notifications[]` nella prenotazione |
| `menu/save`, `menu/toggle` | `PUT /admin/menu/items/{id}`, `PATCH …/visibility` |
| `media/add` (blob URL) | `upload-intents` → PUT diretto → `finalize` |
| `media/remove` bloccato se in uso | `DELETE /admin/media/{id}` → `409 MEDIA_IN_USE` |
| `settings/save`, `content/save` | `PUT /admin/site-settings`, `PUT /admin/site-content` |
| `session {role}` | `POST /auth/login` + `GET /auth/me` |
| fasce fisse nel `BookingDialog` | `GET /public/booking-options` |
