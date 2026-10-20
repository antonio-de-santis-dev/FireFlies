# WORK_PROGRESS — stato del lavoro per la ripresa (MASTER_PLAN §19)

> Ultimo aggiornamento: 20/10/2026, FASE 7 avviata solo con il requisito 15 (locandine); **in attesa del prompt della FASE 7**.

## Fase e branch
- **FASE 7 — Notifiche** (branch `fase-7/notifiche`, creato da `fase-6/eventi-capienza` @ `6ad7e0e`). Nessun merge su `main`.
- Il committente ha inviato un'**integrazione** al prompt della FASE 7 (requisito 15, locandine intere). Il prompt
  principale della FASE 7 (requisiti 1–14) **non è presente** in questa sessione: va richiesto prima di proseguire.
  Il MASTER_PLAN dice solo «email avvisi; SMS solo previa conferma dei costi; fake in sviluppo; tracking stati, retry,
  deduplica; nessun messaggio reale senza dati veri e autorizzazione».

## Piano di lavoro
| # | Attività | Stato |
|---|---|---|
| 15 | Locandine degli eventi sempre intere (elenco, scheda, area gestori), test di regressione su 7 larghezze | fatto |
| 1–14 | Requisiti principali della FASE 7 (notifiche) | **bloccato: prompt da ricevere** |
| — | Locandina verticale di riferimento del committente | **da ricevere**: va messa in `frontend/tests/fixtures/locandine/` |
| — | Report finale della FASE 7 (con il requisito 15), CI, stop | dopo i punti sopra |

## File modificati non committati
- Nessuno dopo l'ultimo commit.

## Test
- FASE 6: 70 + 294 backend, 69 Playwright.
- FASE 7 finora: backend unità 70 → 74 (`ImageProcessorTest`, varianti mai ritagliate); Playwright 69 → 78 (`posters.spec`).

## Errori da risolvere
- Nessuno.

## Prossima azione esatta
Ricevere dal committente il prompt della FASE 7 (requisiti 1–14) e la locandina di riferimento; poi implementare le
notifiche secondo quel prompt. Nessun invio reale di email/SMS senza autorizzazione esplicita.
