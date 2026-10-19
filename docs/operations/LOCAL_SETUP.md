# Avvio locale — database, backend, frontend e primo titolare

> Aggiornato in FASE 3 · 19/10/2026. Tutti i comandi sono stati eseguiti davvero nel container di sviluppo
> (Linux, Java 21, Node 22, Docker 29, Compose v5).

## Requisiti
| Strumento | Versione | Note |
|---|---|---|
| Java (JDK) | 21 | Maven non serve: c'è il wrapper `./mvnw` (scarica Maven 3.9.11) |
| Node.js | 18+ (testato 22) | per il frontend |
| Docker + Docker Compose | recenti | PostgreSQL locale e test di integrazione |

## 1. Database locale (PostgreSQL 17)
```bash
cd infra
cp .env.example .env          # scrivi una password a scelta in FIREFLIES_DB_PASSWORD
docker compose up -d --wait   # PostgreSQL su 127.0.0.1:5432, dati nel volume "pgdata"
```
`infra/.env` è ignorato da git. Il database è raggiungibile solo dalla macchina locale.

## 2. Backend
```bash
cd backend
set -a && . ../infra/.env && set +a    # esporta FIREFLIES_DB_* nella shell
./mvnw spring-boot:run                 # profilo "local", porta 8080
```
All'avvio Flyway applica le migrazioni (`Successfully applied 8 migrations` su un database nuovo).
```bash
curl http://localhost:8080/actuator/health     # {"groups":["liveness","readiness"],"status":"UP"}
```

## 3. Primo titolare (procedura amministrativa, una volta sola)
Il primo account OWNER non si crea dal sito né dall'API: si crea dalla macchina che ha accesso al database.
Nessuna credenziale è scritta nel codice; la procedura si rifiuta se esiste già un titolare.

```bash
cd backend
./mvnw -q -DskipTests package                    # crea target/fireflies-backend-0.1.0-SNAPSHOT.jar
set -a && . ../infra/.env && set +a
java -jar target/fireflies-backend-0.1.0-SNAPSHOT.jar \
     --spring.main.web-application-type=none \
     --fireflies.bootstrap.create-owner=true \
     --fireflies.bootstrap.email=titolare@tuodominio.it \
     --fireflies.bootstrap.display-name="Nome Cognome"
```
Il programma chiede la password due volte (non compare a schermo), crea l'account, scrive nel log
`Primo titolare creato: …` e si chiude.

- **Password**: almeno 12 caratteri (va bene una frase), al massimo 72 byte, non ripetitiva, senza il nome dell'email.
- **Senza terminale interattivo** (script, CI): imposta la variabile d'ambiente `FIREFLIES_BOOTSTRAP_PASSWORD` prima del comando.
  **Non** passare la password come argomento (`--fireflies.bootstrap.password=…`): resterebbe nella cronologia della shell, e la procedura si rifiuta.
- In produzione: stesso comando con `--spring.profiles.active=prod` e le variabili `FIREFLIES_DB_*` del server.
- Gli altri account (staff o altri titolari) li crea il titolare dall'API `POST /api/v1/admin/users`; la schermata relativa arriverà con le fasi successive.

## 4. Frontend
```bash
cd frontend
npm ci
npm run dev        # http://localhost:5173 — /api e /actuator inoltrati al backend su :8080
```
Area gestori: `http://localhost:5173/#/gestione/login` (non è collegata dal sito pubblico). Accedi con l'account del passo 3.
Per un backend su un'altra porta: `FIREFLIES_BACKEND_URL=http://localhost:9090 npm run dev`.

`npm run build:static` produce una pagina apribile con doppio clic: contiene solo il sito pubblico, perché l'area gestori ha bisogno del backend.

## 5. Test
```bash
cd backend
./mvnw test        # 40 test unitari e di slice, senza Docker
./mvnw verify      # + 194 test di integrazione, sicurezza e concorrenza su PostgreSQL 17 reale (Testcontainers)

cd ../frontend
npm run verifica   # typecheck + 48 test Playwright (funzionali, accesso, QA 7 viewport, axe) con backend e DB reali
```
`npm run verifica` usa `scripts/e2e.mjs`: avvia un PostgreSQL separato (progetto Compose `fireflies-e2e`, porta 55432),
compila e avvia il backend sulla porta 18080, crea il titolare con la procedura del passo 3 e uno staff via API con
password **casuali generate a ogni esecuzione**, esegue Playwright e alla fine cancella database e volume.
Non tocca il database di sviluppo del passo 1.

## 6. Profili
| Profilo | Uso | Database | Cookie di sessione | Notifiche |
|---|---|---|---|---|
| `local` (predefinito) | sviluppo | `FIREFLIES_DB_URL/USER/PASSWORD` (password obbligatoria) | `FF_SESSION`, HttpOnly, SameSite=Lax, **non** Secure (HTTP su localhost) | solo finte |
| `test` | test automatici | Testcontainers | come `local` | solo finte; un provider reale blocca l'avvio |
| `prod` | produzione (FASE 8) | solo variabili d'ambiente | HttpOnly, SameSite=Lax, **Secure** (solo HTTPS) | solo finte finché non si approva un provider |

## 7. Spegnere e ripulire
```bash
# backend e frontend: Ctrl+C nelle rispettive shell
cd infra
docker compose down           # ferma il database e conserva i dati
docker compose down -v        # ferma e CANCELLA i dati locali (anche gli account)
```

## Problemi comuni
| Sintomo | Causa e rimedio |
|---|---|
| `Imposta FIREFLIES_DB_PASSWORD in infra/.env` | manca la password in `infra/.env` |
| Il backend non si collega al DB | variabili non esportate: ripeti `set -a && . ../infra/.env && set +a` |
| `Esiste già un titolare` | la procedura serve solo per il primo account; per gli altri usa l'area gestori/API |
| `Nessun terminale interattivo` | imposta `FIREFLIES_BOOTSTRAP_PASSWORD` |
| Login: «Il server non risponde» | backend spento o su un'altra porta (vedi `FIREFLIES_BACKEND_URL`) |
| Login: «Troppi tentativi» | 5 errori in 15 minuti per quell'email (o 20 dallo stesso IP): attendi o fai reimpostare la password dal titolare |
| Porta 5432 occupata | imposta `FIREFLIES_DB_PORT` e `FIREFLIES_DB_URL` in `infra/.env` |
| `429 Too Many Requests` da Docker Hub | limite di download anonimi: riprova più tardi o `docker login` |
