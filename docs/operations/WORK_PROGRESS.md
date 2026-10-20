# Avanzamento del lavoro — checkpoint di recupero

> Regola: MASTER_PLAN §19. Aggiornato durante il lavoro, non solo a fine sessione.
> Ultimo aggiornamento: 20/10/2026, FASE 5: backend e test backend completati, frontend in corso.

## Fase e branch
- **FASE 5 — Prenotazioni V1** (autorizzata dal committente il 20/10/2026, dopo l'approvazione definitiva della FASE 4).
- Branch: `fase-5/prenotazioni`, creato da `fase-4/menu-media` @ `7775032`. Nessun merge su `main`.
- Punto di partenza: `7775032`. Ultimo commit pubblicato: `b991754` (test backend prenotazioni).

## Perimetro (dal mandato)
- Richieste dal sito (data, ora, persone, nome, telefono obbligatorio, email facoltativa, note), orari prenotabili,
  validazione e anti-spam, stato PENDING («in attesa»), messaggio «non è ancora una conferma».
- Area gestori: elenco reale con filtri, dettaglio, conferma/rifiuto/annullamento/modifica, inserimento manuale
  (telefono/email), storico e audit, tavoli e zone secondo i permessi, controllo automatico della capienza.
- Registro di occupazione della FASE 2 (unico contatore), transazioni e lock ordinati, outbox senza invii reali.
- Fuori perimetro: assegnazione automatica dei tavoli, SMS reali, eventi (FASE 6), schermate «Account» e «Categorie».

## Piano di lavoro
| # | Attività | Stato |
|---|---|---|
| 1 | Backend configurazione: zone, fasce, aree e tavoli, regole, eccezioni; capienza effettiva per quanto | fatto (`c9079d6`) |
| 2 | Backend prenotazioni: opzioni pubbliche, richiesta web, inserimento manuale, transizioni, modifica, tavoli, outbox, scadenza | fatto (`7c67958`) |
| 3 | Test backend (creazione, ciclo di vita, capienza, concorrenza, permessi, CSRF, duplicati, riavvio) | fatto (`b991754`) |
| 4 | Frontend: richiesta tavolo reale, pagina Richieste, inserimento manuale, capienza e tavoli; Playwright | in corso |
| 5 | Documentazione, CI, report finale; stop in attesa di approvazione | da fare |

## File modificati non committati
- (aggiornato a ogni checkpoint)

## Test
- Punto di partenza: 66 + 222 backend, 60 Playwright, tutti verdi (FASE 4).
- FASE 5 (parziale): nuovi IT WebReservationIT (9), ReservationLifecycleIT (9), ReservationConcurrencyIT (25 ripetizioni),
  ReservationRulesTest (4); DemoContentLoaderIT e PersistenceAfterRestartIT estesi. Tutti verdi in locale.

## Errori da risolvere
- Nessuno.

## Prossima azione esatta
Frontend: `api/reservations.ts`, BookingDialog collegato alle opzioni e all'invio reale, pagina Richieste reale
(filtri, dettaglio, azioni, inserimento manuale), pagina «Capienza e tavoli» del titolare, test Playwright.
