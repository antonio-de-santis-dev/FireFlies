# FASE 2 — Report tecnico (backend foundation)

> 19/10/2026 · Branch `fase-2/backend-foundation` (derivato da `fase-1/progettazione`) · Stato: **completata, in attesa di approvazione**. FASE 3 non avviata.

## 1. Report tecnico

### 1.1 Cosa è stato fatto
| Attività autorizzata | Risultato |
|---|---|
| Scheletro backend Java 21 + Spring Boot | `backend/` Maven con wrapper, applicazione `it.fireflies.FirefliesApplication`, 11 moduli (`common`, `security`, `audit`, `media`, `content`, `menu`, `dining`, `capacity`, `event`, `reservation`, `notification`) secondo SYSTEM_CONTEXT §4 |
| Verifica definitiva versioni e dipendenze | §2 |
| PostgreSQL e Flyway | PostgreSQL 17, Flyway 12.4 con modulo PostgreSQL, `validate-on-migrate`, `clean` disattivato |
| Migrazioni iniziali | 7 migrazioni (656 righe SQL), 33 tabelle + 1 vista, tutte quelle di DATA_MODEL rev. 2 tranne Spring Session (FASE 3) |
| Vincoli di integrità | tutti quelli di DATA_MODEL §4 (I-1…I-13) come `CHECK`, `EXCLUDE`, `UNIQUE` parziali e FK `RESTRICT`, con nomi stabili |
| Moduli applicativi | `capacity` implementato (registro di occupazione, è la base delle verifiche richieste); `common` (errori, fuso); `notification` solo gateway finti e blocco provider reali; gli altri moduli sono pacchetti documentati, vuoti fino alla loro fase |
| Profili local/test/prod | `application.yml` + 3 profili; nessun segreto; `local` predefinito |
| Gestione degli errori | `GlobalExceptionHandler`: problem+json con `code` stabile, messaggi italiani, vincoli DB tradotti senza esporre nomi interni, deadlock → `409 CONCURRENT_UPDATE`, 500 senza dettagli |
| Infrastruttura di test | base `PostgresIntegrationTest` (Testcontainers, PostgreSQL 17 a digest fissato), `Concurrency` (thread che partono insieme), `Reconciliation` (dopo ogni test), `BookingFixtures` |
| Test di migrazione e concorrenza su PostgreSQL reale | 190 test, tutti verdi (§3) |
| Docker Compose locale | `infra/compose.yaml` (solo PostgreSQL, porta solo su 127.0.0.1, healthcheck, volume) + `infra/.env.example` |
| Documentazione comandi | `docs/operations/LOCAL_SETUP.md` |
| Extra | CI minima GitHub Actions; MASTER_PLAN con strategia Git (§18); DECISION_LOG aggiornato |

### 1.2 Il registro di occupazione (`capacity`)
`CapacityLedger` espone `occupy`, `release`, `change`, `changePhysicalCapacity`. Ogni metodo **deve** girare dentro la transazione del chiamante (`Propagation.MANDATORY`, verificato da test), così prenotazione e posti si salvano insieme o per niente. Procedura: lock condiviso della zona → creazione dei quanti mancanti → lock dei quanti in ordine di orario → controllo → aggiornamento. `change` controlla solo la differenza netta (spostare di un'ora una prenotazione non «conta due volte» i quanti comuni). La capienza effettiva di un quanto passa da `SlotCapacityPolicy`: in FASE 2 è la capienza fisica; in FASE 5 `dining` aggiungerà fasce ed eccezioni senza toccare il registro.

### 1.3 Cosa NON è stato fatto (per regola)
Nessun endpoint applicativo, nessun login, nessuna entità JPA, nessun worker dell'outbox, nessun adattatore storage, nessuna modifica al frontend o al design. Le tabelle di quelle funzioni esistono già (migrazioni), il codice arriverà nelle rispettive fasi.

### 1.4 Dipendenze (tutte usate)
Runtime: `spring-boot-starter-webmvc` (API ed errori), `-validation` (Bean Validation), `-actuator` (health), `-jdbc` (`JdbcClient`), `-flyway` + `flyway-database-postgresql` (migrazioni), `postgresql` (driver; scope compile perché l'handler legge il nome del vincolo violato).
Test: `spring-boot-starter-test`, `-webmvc-test` (`@WebMvcTest`), `spring-boot-testcontainers`, `testcontainers-postgresql`.
Nessuna libreria esterna fuori dal BOM di Spring Boot. Niente Lombok, MapStruct, Redis, Kafka, JPA (per ora). Versioni in §1.5.

### 1.5 Versioni verificate (19/10/2026)
| Componente | Versione | Fonte |
|---|---|---|
| Spring Boot | **4.1.1** | Maven Central (ultima release); linea 3.5 fuori supporto OSS dal 30/06/2026, linea 4.1 supportata fino a metà 2027 secondo [HeroDevs](https://www.herodevs.com/blog-posts/spring-boot-3-5-eol-what-the-migration-really-takes), [endoflife.ai](https://endoflife.ai/article-spring-june-30-eol-wave), [Dan Vega](https://www.danvega.dev/blog/spring-boot-end-of-life) — la pagina ufficiale spring.io non è leggibile da questo container: data esatta da confermare |
| Spring Framework / Security | 7.0.9 / 7.1.1 | BOM Boot 4.1.1 |
| Flyway | 12.4.0 | BOM; riconosce PostgreSQL 17.11 senza avvisi |
| Driver PostgreSQL | 42.7.13 | BOM |
| Testcontainers | 2.0.5 | BOM (package `org.testcontainers.postgresql`) |
| PostgreSQL | 17.11 (`postgres:17-alpine@sha256:b0f9560a…`) | immagine fissata per digest |
| Java / Maven | 21.0.12 / 3.9.11 | container, wrapper |

Nota: la skill `java-spring-review` documenta fino a Boot 3.x; le differenze di Boot 4 incontrate (starter `webmvc`, pacchetti `org.springframework.boot.webmvc.test`, `HttpStatus.UNPROCESSABLE_CONTENT`, modulo `spring-boot-flyway`) sono state verificate sui jar reali e compilando.

## 2. Elenco dei file creati

**Backend** (`backend/`)
| File | Contenuto |
|---|---|
| `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | build Maven con wrapper |
| `src/main/java/it/fireflies/FirefliesApplication.java` | avvio |
| `common/ApiException.java`, `Problems.java`, `GlobalExceptionHandler.java`, `ConstraintViolationTranslator.java`, `BusinessTime.java` | errori problem+json, traduzione vincoli, fuso Europe/Rome |
| `capacity/CapacityLedger.java`, `CapacitySlotStore.java`, `Occupancy.java`, `OccupancyInterval.java`, `SlotCapacityPolicy.java`, `CapacityConfiguration.java`, `CapacityExceptions.java` | registro di occupazione |
| `notification/SmsGateway.java`, `EmailGateway.java`, `FakeSmsGateway.java`, `FakeEmailGateway.java`, `SendResult.java`, `NotificationProperties.java`, `NotificationGatewaysConfiguration.java` | gateway finti e blocco provider reali |
| `*/package-info.java` (11) | responsabilità di ogni modulo e fase di implementazione |
| `src/main/resources/application.yml`, `application-local.yml`, `application-test.yml`, `application-prod.yml` | profili |
| `src/main/resources/db/migration/V1__fondamenta_utenti_audit.sql` … `V7__viste_e_dati_iniziali.sql` | 7 migrazioni Flyway |
| `src/test/java/it/fireflies/support/` (5 file) | Testcontainers, base dei test, concorrenza, riconciliazione, fixture |
| `src/test/java/…` (12 classi di test) | vedi TEST_REPORT §2 |

**Infrastruttura e repository**
| File | Contenuto |
|---|---|
| `infra/compose.yaml`, `infra/.env.example` | PostgreSQL 17 locale |
| `.github/workflows/backend.yml` | CI minima |
| `.gitignore` | segreti locali, build |
| `README.md` | aggiornato con la FASE 2 |

**Documentazione**
| File | Stato |
|---|---|
| `docs/architecture/MASTER_PLAN.md` | nuovo: master plan + §18 strategia Git |
| `docs/architecture/FASE2_REPORT.md` | nuovo: questo report |
| `docs/operations/LOCAL_SETUP.md` | nuovo |
| `docs/testing/TEST_REPORT.md` | nuovo |
| `docs/architecture/DECISION_LOG.md` | aggiornato: C-15…C-21, strategia Git, decisioni T-01…T-11 |
| `BOOKING_RULES.md`, `SYSTEM_CONTEXT.md`, `ADR/0002`, `API_CONTRACT.md` | aggiornati: ordine dei lock con i tavoli, `type` URN |

## 3. Risultati dei test
`cd backend && ./mvnw -B clean verify` → **BUILD SUCCESS**: **30** test unitari + **160** di integrazione/concorrenza su PostgreSQL 17 reale = **190, tutti superati**, 0 saltati. Dettaglio per classe e per verifica obbligatoria in [TEST_REPORT](../testing/TEST_REPORT.md).

## 4. Comandi per avviare backend e database in locale
```bash
cd infra && cp .env.example .env     # scrivi una password in FIREFLIES_DB_PASSWORD
docker compose up -d                 # PostgreSQL 17 su 127.0.0.1:5432
cd ../backend
set -a && . ../infra/.env && set +a
./mvnw spring-boot:run               # http://localhost:8080/actuator/health → UP
./mvnw verify                        # tutti i test (serve Docker)
```
Guida completa: [LOCAL_SETUP](../operations/LOCAL_SETUP.md). Verificato davvero nel container: migrazioni applicate, health `UP`.

## 5. Problemi incontrati e risolti
| Problema | Soluzione |
|---|---|
| **Deadlock tra assegnazioni concorrenti dello stesso tavolo**: due INSERT controllati dal vincolo di esclusione si aspettano a vicenda (3 casi su 20 al primo run). Il vincolo non è mai stato violato, ma l'errore era un deadlock dopo 1 s | procedura con lock delle righe dei tavoli in ordine di id prima dell'assegnazione (0 deadlock su 20 gare, conflitto pulito `23P01`); ordine globale dei lock aggiornato in BOOKING_RULES, SYSTEM_CONTEXT, ADR-0002 (DECISION_LOG T-05); rete di sicurezza `409 CONCURRENT_UPDATE` |
| Hikari non accetta `10s` per `connection-timeout` | valore in millisecondi |
| Il driver PostgreSQL era solo `runtime` ma l'handler degli errori ne usa le classi | scope compile, motivato nel `pom.xml` |
| Messaggio 404 di Spring con dettagli tecnici («No static resource…») | titolo e dettaglio italiani generici |
| Docker spento nel container, poi limite `429` di Docker Hub su immagini nuove | daemon avviato; immagine PostgreSQL già presente e fissata per digest; scansione segreti fatta con secretlint (npm) invece dell'immagine gitleaks |
| `maven-central` risponde `429` a richieste ravvicinate | download con ritentativi e 2 thread (`MAVEN_OPTS`), nessun impatto sul progetto |
| Endpoint `endoflife.date` bloccato dal proxy | date di supporto ricavate da fonti pubbliche via ricerca web (§2) |
| Un mio script di modifica aveva troncato `DECISION_LOG.md` (prima del commit) | ripristinato da git e modifiche riapplicate con controlli; nessuna perdita |

## 6. Decisioni ancora aperte
| ID | Decisione | Proposta |
|---|---|---|
| A-1 | Date esatte di fine supporto di Spring Boot 4.1 sul calendario ufficiale | confermare prima del deploy; aggiornamento a 4.2 pianificabile in FASE 8 |
| A-2 | Nomi dei branch FASE 6–8 | `fase-6/eventi-capienza`, `fase-7/notifiche`, `fase-8/hardening-deploy` |
| A-3 | CI su GitHub Actions: se la repository è privata consuma minuti del piano gratuito | tenerla (pochi minuti per push); disattivabile |
| A-4 | Valori di esempio già nel database: scadenza richieste 60 min, max 12 persone dal web, anticipo 60 min, 60 giorni in avanti | confermarli o sostituirli con quelli dei titolari (Q-01) |
| A-5 | Testi delle notifiche (bozze inattive nella migrazione V7) | revisione del titolare in FASE 7 |
| A-6 | Spostare il mockup in `frontend/` (P-28) | in FASE 3, quando il login reale lo richiede, senza cambiare la grafica |
| A-7 | Dati dei titolari Q-01…Q-07 (capienza fisica, fasce, tavoli, orari, email) | non bloccano FASE 3–4 |

## 7. Prossima fase (non avviata)
FASE 3 — Autenticazione gestori sul branch `fase-3/autenticazione` (da `fase-2/backend-foundation`): Spring Security + Spring Session JDBC, utenti OWNER/STAFF, login/logout/me, CSRF, blocco tentativi, primo OWNER da comando, test permessi; login del mockup trasformato senza cambiare la grafica.
