# WORK_PROGRESS — stato del lavoro per la ripresa (MASTER_PLAN §19)

> Ultimo aggiornamento: 21/10/2026, **FASE 8 — hardening, performance, UX, preparazione al deploy** (completata, **in attesa del collaudo del titolare**).

## Fase e branch
- **FASE 8** sul branch `fase-8/hardening-deploy`, creato da `fase-7/notifiche` @ `769cd1f` (FASE 7 approvata).
- **21/10: `main` allineato alla FASE 8 su autorizzazione del titolare (C-32, fast-forward).** Nessun force push, **nessuna pubblicazione**, nessun servizio a pagamento, notifiche reali spente.

## Piano di lavoro
| # | Requisito | Stato | Commit |
|---|---|---|---|
| 1 | Editor eventi senza «Fine» e coperti (valori ricavati nel backend) | fatto | `98568bb` |
| 2 | Filtri compatti delle Richieste sul telefono | fatto | `4e10a0d` |
| 3 | Foto più visibili in Menu e prezzi | fatto | `4e10a0d` |
| 4 | Telefono cliccabile e copiabile per eventi/compleanni | fatto | `4e10a0d` |
| 5 | Sicurezza: IP dietro proxy, header, matrice permessi, .gitignore dei segreti | fatto (audit in FASE8_REPORT) | `d5468ca`, `d3eaebc` |
| 6 | Performance: divisione del codice, indice V11, misure prima/dopo (`evidence/PERFORMANCE.md`) | fatto | `07d6da9` |
| 7 | Backup e ripristino | fatto: prova completa superata (`docs/operations/evidence/RESTORE_DRILL.md`) | `d3eaebc` |
| 8 | Deploy: Dockerfile, Caddy, compose di produzione, proposta di hosting | fatto (`DEPLOY.md`, nulla acquistato) | `d3eaebc`, `9beade5` |
| 9 | Test completi e CI | fatto: 78 + 320 + 111 + perf 2 + ripristino, CI verde | `9beade5` |
| 10 | Documenti e FASE8_REPORT | fatto | ultimo commit |

## Note per la ripresa
- **Non compilare il backend mentre gira l'e2e**: `scripts/e2e.mjs` usa `backend/target/*.jar`; un `mvnw verify` in
  parallelo lo sostituisce e i test falliscono a caso (successo il 20/10, suite fermata e rieseguita).
- Docker Hub qui risponde 429: le immagini di produzione si verificano nella CI `images` (nessun push).
- Dipendenze: produzione 0 avvisi; corretti braces e sharp (0.35.5); restano vite 5/esbuild (solo server di sviluppo, aggiornamento maggiore rinviato).

## File modificati non committati
- Nessuno.

## Prossima azione esatta
Nessuna: **fermo per il collaudo manuale del titolare**. `main` = FASE 8; nessun deploy, nessuna fase successiva senza
autorizzazione esplicita.
