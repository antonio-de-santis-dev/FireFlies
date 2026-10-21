# WORK_PROGRESS — stato del lavoro per la ripresa (MASTER_PLAN §19)

> Ultimo aggiornamento: 21/10/2026, **FIX 2 — prenotazioni, capienza ed eventi** (in collaudo sul branch
> `fix2/prenotatavolo`, **non** portato su `main`). FIX-1 approvato e su `main` dal 21/10/2026 (C-34).

## Fase e branch
- **FIX 2** sul branch `fix2/prenotatavolo`, creato da `main` @ `e558988` (= ultimo commit di `fix-1`).
- Branch di collaudo: **nessun merge su `main` o `fix-1`** senza approvazione esplicita dopo i test manuali; nessun
  force push, nessun deploy, nessun servizio acquistato, nessuna notifica reale, nessun dato esistente cancellato.

## Piano di lavoro
| # | Requisito | Stato |
|---|---|---|
| 1 | Budget di persone confermate per giorno e fascia (V13, `BandBudget`, trigger) | fatto |
| 2 | Duplicati: un'attiva per telefono, giorno e fascia (indice unico) | fatto |
| 3 | Limite per IP: 5 richieste create all'ora | fatto |
| 4 | Aperture/chiusure settimanali e straordinarie con conferma dei conflitti | fatto |
| 5 | Eventi a qualunque minuto (quanti toccati in parte riservati) | fatto |
| 6 | Interruttore «Pubblica sul sito» nell'elenco eventi, editor senza interruttore | fatto |
| 7 | Riepilogo settimanale con budget della fascia e disponibilità reale | fatto |
| 8 | Test backend e frontend con PostgreSQL reale | vedi FIX2_REPORT §10 |
| 9 | Documenti e FIX2_REPORT | fatto |
| 10 | Commit e push solo su `fix2/prenotatavolo`, report finale | fatto |

## Reset dei dati di prova (21/10/2026)
- Script `infra/scripts/reset-test-data/reset.sh` e guida `docs/operations/RESET_TEST_DATA.md`: anteprima, backup
  verificato, conferma esplicita, transazione unica. Provato solo su un PostgreSQL di laboratorio usa e getta.
- **Il reset sul database locale del titolare NON è stato eseguito**: in attesa della sua seconda conferma dopo
  anteprima e backup.

## Branch fix2/fasceorarie (21/10/2026)
- Creato da `fix2/prenotatavolo` @ `ee3ba4c`. Orari di apertura come unica impostazione del calendario, fasce interne
  (`ArrivalRules`/`ArrivalCatalog`), pannello «Orari di arrivo delle fasce» rimosso. Nessuna migrazione.
- Report: `docs/architecture/FIX2_FASCEORARIE_REPORT.md`. In attesa del collaudo; nessun merge.

## Note per la ripresa
- **Non compilare il backend mentre gira l'e2e** (il jar in `backend/target/` è quello usato da `scripts/e2e.mjs`).
- Docker: se non risponde, avviare `dockerd` (vedi FASE 8).
- La CI ora parte anche sui branch `fix2/**`.
- Punti aperti da decidere con il titolare: FIX2_REPORT §11.

## Prossima azione esatta
Collaudo manuale del titolare (FIX2_REPORT §12). Merge o deploy solo dopo approvazione esplicita.
