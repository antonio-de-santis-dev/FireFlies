# Avanzamento del lavoro — checkpoint di recupero

> Regola: MASTER_PLAN §19. Aggiornato durante il lavoro, non solo a fine sessione.
> Ultimo aggiornamento: 20/10/2026, FASE 5 avviata.

## Fase e branch
- **FASE 5 — Prenotazioni V1** (autorizzata dal committente il 20/10/2026, dopo l'approvazione definitiva della FASE 4).
- Branch: `fase-5/prenotazioni`, creato da `fase-4/menu-media` @ `7775032`. Nessun merge su `main`.
- Ultimo commit pubblicato: `7775032` (punto di partenza).

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
| 1 | Backend configurazione: zone, fasce, aree e tavoli, regole, eccezioni; capienza effettiva per quanto | da fare |
| 2 | Backend prenotazioni: opzioni pubbliche, richiesta web, inserimento manuale, transizioni, modifica, tavoli, outbox, scadenza | da fare |
| 3 | Test backend (creazione, ciclo di vita, capienza, concorrenza, permessi, CSRF, duplicati, riavvio) | da fare |
| 4 | Frontend: richiesta tavolo reale, pagina Richieste, inserimento manuale, capienza e tavoli; Playwright | da fare |
| 5 | Documentazione, CI, report finale; stop in attesa di approvazione | da fare |

## File modificati non committati
- (aggiornato a ogni checkpoint)

## Test
- Punto di partenza: 66 + 222 backend, 60 Playwright, tutti verdi (FASE 4).

## Errori da risolvere
- Nessuno.

## Prossima azione esatta
Implementare il modulo `dining` (configurazione) e la `SlotCapacityPolicy` con fasce ed eccezioni.
