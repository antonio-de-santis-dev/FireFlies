# FASE 3 — Report tecnico (autenticazione e sicurezza dei gestori)

> 19/10/2026 · Branch `fase-3/autenticazione` (derivato da `fase-2/backend-foundation`) · Stato: **completata, in attesa di approvazione**. FASE 4 non avviata.

## 1. Report tecnico

### 1.1 Cosa è stato fatto
| Richiesta | Implementazione |
|---|---|
| Spring Security | Spring Security 7.1.1 (Boot 4.1.1): regole per URL + `@PreAuthorize`, nessun form login né HTTP Basic, errori 401/403 in problem+json |
| Sessioni server-side, Spring Session JDBC, PostgreSQL | Spring Session 4.1.1 su PostgreSQL; tabelle create dalla migrazione **V8** (schema ufficiale della libreria); timeout 8 h di inattività; pulizia ogni 5 minuti |
| Cookie HttpOnly, Secure in HTTPS, SameSite | `FF_SESSION`: `HttpOnly`, `SameSite=Lax`, `Path=/`; `Secure` in `prod`, disattivato solo nei profili `local`/`test` (HTTP su localhost) |
| Protezione CSRF | `csrf.spa()`: cookie `XSRF-TOKEN` + header `X-XSRF-TOKEN`, obbligatorio anche per login e logout; token rigenerato a ogni login |
| Hash robusto | bcrypt costo 12 con prefisso `{bcrypt}` (passaggio futuro ad Argon2id senza migrare i dati) |
| Ruoli OWNER e STAFF | ruolo salvato sull'account; il titolare ha anche i permessi dello staff; `/api/v1/admin/users/**` solo OWNER, `/api/v1/admin/**` STAFF e OWNER, tutto il resto non previsto è chiuso |
| Login, logout, sessione corrente | `POST /auth/login`, `POST /auth/logout`, `GET /auth/session` (sempre 200), `GET /auth/me`, più `POST /auth/password` (cambio della propria password) |
| Primo OWNER | procedura amministrativa da riga di comando (§4), una sola volta, con lock PostgreSQL contro esecuzioni parallele |
| Gestione autorizzazioni | API del titolare per creare account, cambiare ruolo/stato/nome, reimpostare password; ultimo titolare non rimovibile; cambio di ruolo o disattivazione **chiudono tutte le sessioni** dell'account |
| Protezione endpoint amministrativi | anonimo → `401`, ruolo insufficiente → `403` (registrato nell'audit), scritture senza CSRF → `403` |
| Limite dei tentativi | 5 per email (anche inesistente) e 20 per IP in 15 minuti → `429` con `Retry-After`; blocco anche sul database (resiste ai riavvii) |
| Sessioni scadute | `401 SESSION_EXPIRED` sulle API e `reason: SESSION_EXPIRED` su `/auth/session`; il frontend torna all'accesso con un avviso |
| Audit | `LOGIN_SUCCEEDED/FAILED/BLOCKED`, `ACCOUNT_LOCKED`, `LOGOUT`, `ACCESS_DENIED`, `PASSWORD_CHANGED`, `OWNER_BOOTSTRAPPED`, `USER_CREATED/UPDATED/PASSWORD_RESET`; mai password né email tentate |
| Ruolo non sceglibile al login | il login accetta solo email e password (campi in più → `400`); nella pagina non c'è più la scelta del ruolo |

### 1.2 Integrazione del frontend
- Il mockup approvato è stato copiato in `frontend/` **senza modifiche** in un commit separato (`e763c3e`); il diff successivo contiene solo l'integrazione.
- **Modificati**:
  - client API (`src/api/client.ts`: cookie di sessione, CSRF, errori problem+json) e `src/api/auth.ts`;
  - pagina di accesso: email e password reali, errori dal backend, pulsante «Accedi», nota informativa aggiornata;
  - verifica della sessione con il backend a ogni pagina dell'area gestori, con ritorno all'accesso se la sessione è scaduta;
  - logout reale;
  - testi della barra dell'area gestori.
- **Tolti** (perché in contrasto con la richiesta): la scelta «Entra come Titolare/Staff» e il pulsante «Compila con dati demo».
- **Invariati**: grafica, layout, colori, responsive, animazioni e componenti. Stesse classi CSS, nessun file di stile toccato. Il confronto visivo prima/dopo della pagina di accesso è nella consegna.
- Nessun link pubblico all'area gestori (test dedicato). La protezione vera è sul backend: i test chiamano le API anche aggirando l'interfaccia.
- I contenuti dell'area gestori (menu, eventi, foto…) restano in memoria fino alla FASE 4.

### 1.3 Cosa NON è stato fatto (per regola)
Nessuna gestione delle prenotazioni, nessun SMS reale, nessuna integrazione R2, nessuna API di menu/contenuti/media, nessuna modifica estetica. La schermata per gestire gli account non c'è: per ora la gestione avviene via API (§6).

## 2. Elenco dei file modificati

### Backend
| File | Stato |
|---|---|
| `pom.xml` | + `spring-boot-starter-security`, `spring-boot-starter-session-jdbc`, `spring-boot-starter-security-test` (test) |
| `src/main/resources/db/migration/V8__sessioni_gestori.sql` | nuovo: tabelle Spring Session, indice audit per autore, vincolo email normalizzata |
| `src/main/resources/application.yml`, `application-local.yml`, `application-test.yml` | cookie di sessione, timeout, CSRF, campi JSON sconosciuti → 400, limiti dei tentativi |
| `security/` | nuovi: `SecurityConfiguration`, `SecurityProblemHandlers`, `PasswordConfiguration`, `PasswordPolicy`, `LoginService`, `LoginAttemptLimiter`, `SecurityProperties`, `TooManyAttemptsException`, `AuthController`, `UserAdminController`, `UserAccountService`, `UserStore`, `AppUser`, `Role`, `GestoreDetails`, `GestoreDetailsService`, `SessionRevoker`, `OwnerBootstrap`, `OwnerBootstrapRunner` |
| `audit/AuditLog.java`, `audit/AuditAction.java` | nuovi |
| `common/ClockConfiguration.java` | nuovo |
| `common/GlobalExceptionHandler.java`, `common/ConstraintViolationTranslator.java` | `Retry-After` sui 429, email già usata → `409 EMAIL_TAKEN` |
| `src/test/…/security/` | nuovi: `AuthenticationIT`, `UserAdministrationIT`, `OwnerBootstrapIT`, `LoginAttemptLimiterTest`, `PasswordPolicyTest` |
| `src/test/…/support/` | nuovi `ApiClient`, `SecurityIntegrationTest`; `PostgresIntegrationTest` con server HTTP reale e pulizia di sessioni/limitatore |
| `ApplicationIT`, `MigrationIT`, `GlobalExceptionHandlerTest` | attese aggiornate (actuator chiuso → 401; 8 migrazioni; slice senza filtri di sicurezza) |

### Frontend (`frontend/`, rispetto al mockup importato)
| File | Stato |
|---|---|
| `src/api/client.ts`, `src/api/auth.ts` | nuovi |
| `src/pages/admin/Login.tsx` | login reale, senza scelta del ruolo |
| `src/App.tsx` | verifica della sessione con il backend |
| `src/pages/admin/AdminLayout.tsx` | logout reale, testo della barra |
| `src/pages/admin/OwnerOnly.tsx`, `src/store.tsx` | commenti; stato utente e sessione |
| `vite.config.ts` | proxy `/api` e `/actuator` verso il backend |
| `package.json`, `README.md` | script di test con backend reale |
| `scripts/e2e.mjs` | nuovo: database e backend reali per Playwright |
| `tests/auth.spec.ts` | nuovo: 10 test di accesso |
| `tests/helpers.ts`, `navigation.spec.ts`, `screenshots.spec.ts`, `demo.spec.ts` | accesso con credenziali vere generate a ogni esecuzione |

### Repository e documentazione
`.github/workflows/frontend.yml` (nuova CI frontend) · `README.md` · `docs/architecture/FASE3_REPORT.md` (nuovo) · `DECISION_LOG.md` (C-22…C-26, T-12…T-24) · `API_CONTRACT.md` e `api/openapi.yaml` (autenticazione e account, validato con Redocly) · `docs/operations/LOCAL_SETUP.md` · `docs/testing/TEST_REPORT.md`.

## 3. Comandi per avviare frontend, backend e database
```bash
cd infra && cp .env.example .env            # scrivi una password in FIREFLIES_DB_PASSWORD
docker compose up -d --wait                 # PostgreSQL 17 su 127.0.0.1:5432

cd ../backend
set -a && . ../infra/.env && set +a
./mvnw spring-boot:run                      # backend su :8080

cd ../frontend                              # in un altro terminale
npm ci
npm run dev                                 # http://localhost:5173/#/gestione/login
```
Guida completa: [LOCAL_SETUP](../operations/LOCAL_SETUP.md). Ho verificato questo flusso nel container: login attraverso il proxy di Vite e risposta di `/auth/me` con il ruolo `OWNER`.

## 4. Istruzioni per creare il primo OWNER
```bash
cd backend
./mvnw -q -DskipTests package
set -a && . ../infra/.env && set +a
java -jar target/fireflies-backend-0.1.0-SNAPSHOT.jar \
     --spring.main.web-application-type=none \
     --fireflies.bootstrap.create-owner=true \
     --fireflies.bootstrap.email=titolare@tuodominio.it \
     --fireflies.bootstrap.display-name="Nome Cognome"
```
- Il programma chiede la password due volte senza mostrarla, crea l'account e si chiude.
- **Requisiti della password**: almeno 12 caratteri, al massimo 72 byte.
- **Senza terminale**: usare la variabile d'ambiente `FIREFLIES_BOOTSTRAP_PASSWORD`.
- **Mai come argomento**: la password passata sulla riga di comando viene rifiutata.
- **Una sola volta**: se esiste già un titolare la procedura si rifiuta. Gli altri account li crea il titolare.
- **Verifica**: provata in un vero terminale interattivo (password non visibile, uscita 0) e nei test automatici.

## 5. Risultati dei test
| Suite | Esito |
|---|---|
| Backend `./mvnw -B clean verify` | **234/234**: 40 unitari + 194 di integrazione, sicurezza e concorrenza su PostgreSQL 17 reale |
| Frontend `npm run verifica` | typecheck OK + **48/48** Playwright con backend e database reali: 27 funzionali, 10 di accesso, 9 di QA su 7 viewport (126 viste, 0 overflow, 0 errori), 2 axe (0 violazioni) |
| Regressioni | nessuna: i 190 test della FASE 2 sono verdi; 2 attese cambiate per effetto voluto (TEST_REPORT) |
| Segreti | secretlint: 0 segnalazioni; credenziali dei test generate a caso a ogni esecuzione |

Dettaglio per verifica obbligatoria: [TEST_REPORT](../testing/TEST_REPORT.md).

### Problemi incontrati e risolti
| Problema | Soluzione |
|---|---|
| **Bug trovato dai test end-to-end**: la procedura del primo titolare rifiutava la variabile d'ambiente consentita. Spring la espone anche come proprietà `fireflies.bootstrap.password`, che il controllo bloccava | il controllo ora guarda solo gli argomenti della riga di comando; il test usa una vera sorgente «variabili d'ambiente» (prima la simulava male con una proprietà di sistema) |
| Il client HTTP Java dei test conservava due cookie con lo stesso nome | la session fixation è verificata sul database e con un secondo client «attaccante» |
| I contatori dei tentativi sono in memoria e condivisi tra i test | svuotati dopo ogni test |
| Riavvio del processo di lavoro durante la fase | lavoro già committato in locale; branch caricato subito su GitHub; Docker riavviato |

## 6. Limitazioni e decisioni aperte
| ID | Tema | Stato / proposta |
|---|---|---|
| L-1 | Contatori dei tentativi in memoria | adatti a **una sola istanza** del backend. Dopo un riavvio, un account già bloccato risponde `429` mentre un'email inesistente risponde `401`: è una piccola differenza, solo in quel caso. Il blocco su DB resta. Con più istanze servirebbe un contatore condiviso (FASE 8) |
| L-2 | Nessuna schermata per gestire gli account | API pronte; **proposta**: pagina «Account» per il titolare nella FASE 4 o 5, con lo stile esistente (va approvata) |
| L-3 | Password dimenticata | in V1 la reimposta il titolare; il reset via email richiede il provider email (FASE 7) |
| L-4 | 2FA per il titolare | non implementata (era «consigliata» in ADR-0003): **da decidere** se in FASE 8 |
| L-5 | Build statica apribile con doppio clic | solo sito pubblico; per l'area gestori serve il backend (indicato nella pagina se il server non risponde) |
| L-6 | IP del client dietro proxy | in `prod` si usano le intestazioni `X-Forwarded-*` del proxy (`forward-headers-strategy: framework`); da configurare con l'hosting (FASE 8) |
| L-7 | Durata della sessione 8 h, blocco 5 tentativi / 15 min | valori proposti (P-21, P-27) e già attivi: **confermare** |
| L-8 | CI su GitHub Actions | **verde** su `fase-3/autenticazione`: backend 234 test in ~1 min 15 s, frontend 48 test end-to-end con backend e PostgreSQL reali in ~4 min 10 s (run 39267771250). Consuma minuti del piano se la repository è privata |
| L-9 | Screenshot e video del mockup | gli script sono aggiornati al login reale ma **non rigenerati** in questa fase (non richiesto) |

## 7. Prossima fase (non avviata)
FASE 4 — Menu, contenuti, media sul branch `fase-4/menu-media` (da `fase-3/autenticazione`): API e persistenza di menu, contenuti e foto (adattatore S3 compatibile, fake in locale), integrazione progressiva delle pagine dell'area gestori senza cambiare la grafica.
