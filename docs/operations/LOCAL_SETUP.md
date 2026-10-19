# Avvio locale — backend e database

> FASE 2 · 19/10/2026. Comandi verificati nel container di sviluppo (Linux, Java 21, Docker 29, Compose v5).

## Requisiti
| Strumento | Versione | Note |
|---|---|---|
| Java (JDK) | 21 | Maven non serve: c'è il wrapper `./mvnw` (scarica Maven 3.9.11) |
| Docker + Docker Compose | recenti | per PostgreSQL locale e per i test di integrazione |

## 1. Database locale (PostgreSQL 17)
```bash
cd infra
cp .env.example .env          # poi scrivi una password a scelta in FIREFLIES_DB_PASSWORD
docker compose up -d          # PostgreSQL su 127.0.0.1:5432, dati nel volume "pgdata"
docker compose ps             # attendi lo stato "healthy"
```
`infra/.env` è ignorato da git. Il database è raggiungibile solo dalla macchina locale.

## 2. Backend
```bash
cd backend
set -a && . ../infra/.env && set +a    # esporta FIREFLIES_DB_* nella shell
./mvnw spring-boot:run                 # profilo "local" predefinito, porta 8080
```
All'avvio Flyway applica le migrazioni (`Successfully applied 7 migrations`).

Verifica:
```bash
curl http://localhost:8080/actuator/health            # {"groups":["liveness","readiness"],"status":"UP"}
curl http://localhost:8080/actuator/health/readiness  # {"status":"UP"}
```
In FASE 2 non ci sono ancora endpoint applicativi: arrivano dalla FASE 3.

## 3. Test
```bash
cd backend
./mvnw test      # 30 test unitari e di slice, senza Docker (~10 s)
./mvnw verify    # + 160 test di integrazione e concorrenza su PostgreSQL 17 reale (Testcontainers, ~40 s)
```
I test di integrazione avviano da soli un container `postgres:17-alpine` (stessa immagine, fissata per digest, di `infra/compose.yaml`): non usano il database locale.

Report: `backend/target/surefire-reports` (unità) e `backend/target/failsafe-reports` (integrazione).

## 4. Profili
| Profilo | Uso | Database | Notifiche |
|---|---|---|---|
| `local` (predefinito) | sviluppo | variabili `FIREFLIES_DB_URL/USER/PASSWORD` (password obbligatoria) | solo finte |
| `test` | test automatici | Testcontainers | solo finte; un provider reale blocca l'avvio |
| `prod` | produzione (FASE 8) | solo variabili d'ambiente, nessun valore predefinito | solo finte finché non si approva un provider (FASE 7) |

## 5. Spegnere e ripulire
```bash
# backend: Ctrl+C nella shell di spring-boot:run
cd infra
docker compose down           # ferma il database e conserva i dati
docker compose down -v        # ferma e CANCELLA i dati locali
```

## Problemi comuni
| Sintomo | Causa e rimedio |
|---|---|
| `Imposta FIREFLIES_DB_PASSWORD in infra/.env` | manca la password in `infra/.env` |
| Il backend non si collega al DB | variabili non esportate: ripeti `set -a && . ../infra/.env && set +a` |
| Porta 5432 occupata | imposta `FIREFLIES_DB_PORT` e `FIREFLIES_DB_URL` in `infra/.env` con un'altra porta |
| `429 Too Many Requests` da Docker Hub | limite di download anonimi: riprova più tardi o fai `docker login` |
| `Could not find a valid Docker environment` nei test | Docker non è avviato |
