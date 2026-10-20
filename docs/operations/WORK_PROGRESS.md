# Avanzamento del lavoro — checkpoint di recupero

> Regola: MASTER_PLAN §19. Aggiornato durante il lavoro, non solo a fine sessione.
> Ultimo aggiornamento: 20/10/2026, **FASE 5 completata in locale**, in attesa della CI e dell'approvazione del committente.

## Fase e branch
- **FASE 5 — Prenotazioni V1** (autorizzata dal committente il 20/10/2026, dopo l'approvazione definitiva della FASE 4).
- Branch: `fase-5/prenotazioni`, creato da `fase-4/menu-media` @ `7775032`. Nessun merge su `main`.

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
| 4 | Frontend: richiesta tavolo reale, pagina Richieste, inserimento manuale, capienza e tavoli; Playwright | fatto (`c344211` + correzioni QA) |
| 5 | Documentazione, CI, report finale; stop in attesa di approvazione | documenti fatti; CI da verificare sull'ultimo commit |

## File modificati non committati
- Nessuno dopo il commit finale della fase.

## Test (locali, tutti verdi)
- Backend: 70 unità + 265 integrazione (`./mvnw -B verify`).
- Frontend: 66 Playwright con backend e database reali (`npm run verifica`).
- Totale 401 (FASE 4: 348).

## Errori da risolvere
- Nessuno noto. Difetti trovati e corretti in TEST_REPORT §«FASE 5».

## Prossima azione esatta
Verificare l'esito delle due workflow GitHub Actions sull'ultimo commit di `fase-5/prenotazioni` e riportarlo in
TEST_REPORT §CI; poi **fermarsi**: la FASE 6 (eventi) richiede l'approvazione del committente.
