# FireFlies Aperitif — web app

Sito e area gestori di FireFlies Aperitif (Via Giovanni XXIII 19, 73025 Martano LE).

Lo sviluppo procede per fasi, ognuna su un proprio branch derivato dalla fase precedente approvata, senza merge automatici (strategia completa: [MASTER_PLAN §18](docs/architecture/MASTER_PLAN.md)):

| Fase | Branch | Contenuto |
|---|---|---|
| 0 — Audit del progetto | `fase-0/audit` | analisi del mockup, delle skill e delle incompatibilità |
| 1 — Progetto del dominio e contratti | `fase-1/progettazione` | architettura, modello dati, regole di prenotazione, API, strategia di test |
| 2 — Backend foundation | `fase-2/backend-foundation` | scheletro Spring Boot, migrazioni PostgreSQL, registro di occupazione, test su PostgreSQL reale |
| 3 — Autenticazione | `fase-3/autenticazione` | login reale dei gestori, sessioni server-side, CSRF, ruoli OWNER/STAFF, primo titolare, frontend integrato |
| 4 — Menu, contenuti e foto | `fase-4/menu-media` | API di menu, prezzi, allergeni, testi, orari e avvisi; foto verificate e ridimensionate su storage locale o S3/R2; sito e area gestori collegati al backend |

Stack: Java 21 + Spring Boot 4.1 (monolite modulare), PostgreSQL 17 + Flyway, frontend React + TypeScript + Vite (dal mockup approvato).

## Struttura
```text
backend/   API Spring Boot (vedi docs/operations/LOCAL_SETUP.md)
frontend/  sito pubblico e area gestori React (dal mockup approvato)
infra/     Docker Compose per il PostgreSQL locale
docs/      architettura, decisioni, test, operazioni
```

## Avvio rapido
```bash
cd infra && cp .env.example .env && docker compose up -d   # imposta prima FIREFLIES_DB_PASSWORD in .env
cd ../backend && set -a && . ../infra/.env && set +a && ./mvnw spring-boot:run
# primo titolare (una volta sola): docs/operations/LOCAL_SETUP.md §3
cd ../frontend && npm ci && npm run dev                    # http://localhost:5173/#/gestione/login
./mvnw verify   ·   npm run verifica                       # test backend · test frontend con backend reale (serve Docker)
```

Documenti principali: [DECISION_LOG](docs/architecture/DECISION_LOG.md) · [FASE4_REPORT](docs/architecture/FASE4_REPORT.md) · [TEST_REPORT](docs/testing/TEST_REPORT.md) · [LOCAL_SETUP](docs/operations/LOCAL_SETUP.md).
