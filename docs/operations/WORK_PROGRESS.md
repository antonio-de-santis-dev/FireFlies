# WORK_PROGRESS — stato del lavoro per la ripresa (MASTER_PLAN §19)

> Ultimo aggiornamento: 21/10/2026, **FIX-1 — correzioni UX/UI, prenotazioni, impostazioni** (completato e **approvato**:
> portato su `main` il 21/10/2026, C-34).

## Fase e branch
- **FIX-1** sul branch `fix-1`, creato da `main` @ `395dcf5` (= FASE 8 completa, C-32).
- Branch di collaudo: **nessun merge su `main`** senza approvazione esplicita dopo i test manuali; nessun force push,
  nessun deploy, nessuna risorsa cloud, nessun dato del committente cancellato.

## Piano di lavoro
| # | Requisito | Stato |
|---|---|---|
| 1 | Animazione iniziale più fluida | fatto |
| 2 | Menu pubblico: navigazione categorie sul telefono | fatto |
| 3 | «Perché FireFlies» (pagina Il locale): foto adattata | fatto |
| 4 | Rate limiting equilibrato, doppio invio, idempotenza, 429 con attesa | fatto |
| 5 | Navbar area gestori sul telefono | fatto |
| 6.1–6.2 | Impostazioni: layout desktop, orari su telefono | fatto |
| 6.3 | Capienza unica del locale (V12, `PUT /admin/venue-capacity`) | fatto |
| 6.4 | Riepilogo settimanale con una fascia | fatto |
| 6.5 | Via «persone massime dal sito» e limiti per data/fascia | fatto |
| 6.6 | Chiusure/aperture su giorno o intervallo | fatto |
| 7 | BOOKING_FLOW_AUDIT.md | fatto |
| 8–9 | Test (82 + 334 + 136, tutti verdi), documenti, FIX1_REPORT | fatto |

## Note per la ripresa
- **Non compilare il backend mentre gira l'e2e** (il jar in `backend/target/` è quello usato da `scripts/e2e.mjs`).
- Docker: se non risponde, avviare `dockerd` (vedi FASE 8).
- Punti aperti da decidere con il titolare: FIX1_REPORT §9 (tetto di 30 persone dal sito, chiusure per singola fascia).

## Prossima azione esatta
FIX-1 approvato e su `main`. Creato `fix2/prenotatavolo` da `main`: in attesa delle istruzioni del titolare per il
nuovo lavoro (nessuna modifica avviata).
