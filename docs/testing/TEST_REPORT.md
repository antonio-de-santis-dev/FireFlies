# Report dei test — backend e frontend

> Aggiornato in **FASE 3** (autenticazione) il 19/10/2026. In fondo resta il report della FASE 2.
> Ambiente: OpenJDK 21.0.12, Maven 3.9.11, Node 22, Playwright 1.56.1 (Chromium), Docker 29.8.2, PostgreSQL **17.11**.

## FASE 3 — Riepilogo

| Suite | Comando | Test | Esito |
|---|---|---|---|
| Backend unità e slice | `cd backend && ./mvnw -B clean verify` | 40 | ✅ 40/40 |
| Backend integrazione, sicurezza, concorrenza (PostgreSQL reale) | stesso comando | 194 | ✅ 194/194 |
| Frontend Playwright con backend e DB reali (funzionali 27 + accesso 10 + QA 9 + axe 2) | `cd frontend && npm run verifica` | 48 | ✅ 48/48 |
| **Totale** | | **282** | ✅ |

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
