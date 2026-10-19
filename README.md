# FireFlies Aperitif — web app

Sito e area gestori di FireFlies Aperitif (Via Giovanni XXIII 19, 73025 Martano LE).

Lo sviluppo procede per fasi, ognuna su un proprio branch derivato dalla fase precedente approvata, senza merge automatici (strategia completa: [MASTER_PLAN §18](docs/architecture/MASTER_PLAN.md)):

| Fase | Branch | Contenuto |
|---|---|---|
| 0 — Audit del progetto | `fase-0/audit` | analisi del mockup, delle skill e delle incompatibilità |
| 1 — Progetto del dominio e contratti | `fase-1/progettazione` | architettura, modello dati, regole di prenotazione, API, strategia di test |
| 2 — Backend foundation | `fase-2/backend-foundation` | scheletro Spring Boot, migrazioni PostgreSQL, registro di occupazione, test su PostgreSQL reale |

Stack: Java 21 + Spring Boot 4.1 (monolite modulare), PostgreSQL 17 + Flyway, frontend React + TypeScript + Vite (dal mockup approvato).

## Struttura
```text
backend/   API Spring Boot (vedi docs/operations/LOCAL_SETUP.md)
infra/     Docker Compose per il PostgreSQL locale
docs/      architettura, decisioni, test, operazioni
```

## Avvio rapido
```bash
cd infra && cp .env.example .env && docker compose up -d   # imposta prima FIREFLIES_DB_PASSWORD in .env
cd ../backend && set -a && . ../infra/.env && set +a && ./mvnw spring-boot:run
./mvnw verify                                              # test (serve Docker)
```

Documenti principali: [DECISION_LOG](docs/architecture/DECISION_LOG.md) · [FASE2_REPORT](docs/architecture/FASE2_REPORT.md) · [TEST_REPORT](docs/testing/TEST_REPORT.md).
