# WORK_PROGRESS — stato del lavoro per la ripresa (MASTER_PLAN §19)

> Ultimo aggiornamento: 21/10/2026, **FIX-1 — correzioni UX/UI, prenotazioni, impostazioni** (in corso).

## Fase e branch
- **FIX-1** sul branch `fix-1`, creato da `main` @ `395dcf5` (= FASE 8 completa, C-32).
- Branch di collaudo: **nessun merge su `main`** senza approvazione esplicita dopo i test manuali; nessun force push,
  nessun deploy, nessuna risorsa cloud, nessun dato del committente cancellato.

## Piano di lavoro
| # | Requisito | Stato |
|---|---|---|
| 1 | Animazione iniziale più fluida | da fare |
| 2 | Menu pubblico: navigazione categorie sul telefono | da fare |
| 3 | «Perché FireFlies» (pagina Il locale): foto adattata | da fare |
| 4 | Rate limiting equilibrato, doppio invio, idempotenza | backend fatto (WebRateLimitIT, WebRequestLimiterTest); frontend da fare |
| 5 | Navbar area gestori sul telefono | da fare |
| 6.1–6.2 | Impostazioni: layout desktop, orari su telefono | da fare |
| 6.3 | Capienza unica del locale (V12, `PUT /admin/venue-capacity`) | backend fatto; frontend da fare |
| 6.4 | Riepilogo settimanale con una fascia (`capacity-summary?band=`) | backend fatto; frontend da fare |
| 6.5 | Via «persone massime dal sito» e limiti per data | backend fatto (V12); frontend da fare |
| 6.6 | Chiusure/aperture su giorno o intervallo (V12, vincolo di esclusione) | backend fatto; frontend da fare |
| 7 | BOOKING_FLOW_AUDIT.md | da fare |
| 8–9 | Test completi, documenti, FIX1_REPORT | da fare |

## Note per la ripresa
- **Non compilare il backend mentre gira l'e2e** (il jar in `backend/target/` è quello usato da `scripts/e2e.mjs`).
- Docker: se non risponde, avviare `dockerd` (vedi FASE 8).
- Bug pre-esistente trovato e corretto: 403 dello staff su percorsi lunghi diventava 500 (audit `object_id` > 64).

## Prossima azione esatta
Frontend: Impostazioni (capienza unica, chiusure a intervallo, riepilogo settimanale, orari su telefono), modulo
pubblico (doppio invio, 429), poi animazione, menu, «Perché FireFlies», navbar gestori; test Playwright; documenti.
