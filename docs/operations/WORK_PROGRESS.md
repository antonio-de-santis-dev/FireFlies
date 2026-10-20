# WORK_PROGRESS — stato del lavoro per la ripresa (MASTER_PLAN §19)

> Ultimo aggiornamento: 20/10/2026, **FASE 7 — completamento obbligatorio** (requisiti 1–14 + 15 già fatto).

## Fase e branch
- **FASE 7** sul branch `fase-7/notifiche` (creato da `fase-6/eventi-capienza` @ `6ad7e0e`). Nessun merge su `main`,
  nessun force push, FASE 8 non avviata.
- Mandato: «COMPLETAMENTO OBBLIGATORIO FASE 7» (requisiti 1–14), requisito 15 (locandine intere) già fatto e preservato.

## Piano di lavoro (ordine del mandato)
| Passo | Requisiti | Stato |
|---|---|---|
| 1. Confronto requisiti ↔ codice | 1–15 | fatto (FASE7_REPORT §2) |
| 2. Bug funzionali e persistenza | 4 (switch «Visibile sul sito», salvataggio atomico, nascondere, eliminare), 3 (prodotto visibile, eliminazione), 5 («Da richiamare») | fatto — commit `870450f`, `e33df45` |
| 3. Capienza, conservazione, calendario | 8, 9, 10, 5/4/12 (pulizia notturna, statistiche anonime) | fatto (V10, `OperationsService`, `RetentionService`) |
| 4. UX desktop e telefono | 1, 2, 5 (schede), 6 (via «Capienza e tavoli»), 7 (foto), 10 (scelta tavolo/evento) | fatto |
| 5. Outbox e notifiche non reali | 11 | fatto (worker con gateway finti, nuovo invio manuale) |
| 6. Test di regressione e CI | 13 | in corso |
| 7. Documenti, report, commit, push | 14 | in corso |

## Test (eseguiti davvero, vedi TEST_REPORT §«FASE 7»)
- Backend IT nuovi: `RetentionIT` (4), `OperationsIT` (4), `NotificationDispatcherIT` (6), `EventApiIT` +2,
  `ReservationLifecycleIT` +1; unità: `RetentionJobTest` (4: ora legale, fasce).
- Playwright nuovi: `event-visibility.spec` (2), `fase7.spec` (13); spec esistenti aggiornate al nuovo dialog, alle schede
  delle richieste e alle Impostazioni.

## File modificati non committati
- Capienza, calendario, pulizia, chiusure, UX, notifiche (dal commit `e33df45` in poi): da committare a fine verifica.

## Errori da risolvere
- In verifica con la suite completa del frontend (vedi TEST_REPORT).

## Prossima azione esatta
Completare la suite Playwright e il verify del backend, aggiornare i documenti elencati nel mandato (req. 14), commit e
push sul branch, attendere la CI, poi fermarsi per l'approvazione con lo stato reale dei 15 requisiti.
