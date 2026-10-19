# FireFlies Aperitif — web app

Sito e area gestori di FireFlies Aperitif (Via Giovanni XXIII 19, 73025 Martano LE).

Lo sviluppo procede per fasi, ognuna su un proprio branch, con approvazione del committente prima di ogni merge:

| Fase | Branch | Contenuto |
|---|---|---|
| 0 — Audit del progetto | `fase-0/audit` | analisi del mockup, delle skill e delle incompatibilità |
| 1 — Progetto del dominio e contratti | `fase-1/progettazione` | architettura, modello dati, regole di prenotazione, API, strategia di test |

Stack approvato: Java 21 + Spring Boot (monolite modulare), PostgreSQL + Flyway, frontend React + TypeScript + Vite (dal mockup approvato).
