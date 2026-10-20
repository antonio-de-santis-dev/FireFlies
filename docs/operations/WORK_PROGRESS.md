# WORK_PROGRESS — stato del lavoro per la ripresa (MASTER_PLAN §19)

> Ultimo aggiornamento: 20/10/2026, **FASE 6 completata in locale**; in attesa della CI e dell'approvazione.

## Fase e branch
- **FASE 6 — Eventi e capienza** (autorizzata il 20/10/2026 con l'approvazione della FASE 5, C-27).
- Branch: `fase-6/eventi-capienza`, creato da `fase-5/prenotazioni` @ `911c5be`. Nessun merge su `main`.

## Perimetro (dal mandato)
- Eventi reali su PostgreSQL: creazione, modifica, pubblicazione, annullamento; locandina e foto dalla libreria FASE 4.
- Prenotazioni per eventi con conferma manuale; quota configurabile per evento (mai un secondo contatore: registro a
  quanti della FASE 2 + quota `events.confirmed_covers`).
- Eventi esclusivi per impostazione predefinita; non esclusivi solo se il titolare li abilita (C-18, C-28).
- Conflitti con le prenotazioni ordinarie confermate (`EVENT_CONFLICTS`), pagina Eventi pubblica e dashboard reali.
- OWNER/STAFF, CSRF, audit, test di concorrenza. Fuori perimetro: tavoli automatici, email/SMS reali, FASE 7.
- Verifiche aperte V-01…V-04 annotate in DECISION_LOG §4-bis e `docs/operations/PRE_DEPLOY.md` (non da risolvere ora).

## Piano di lavoro
| # | Attività | Stato |
|---|---|---|
| 1 | Registro: marcatura/smarcatura esclusiva dei quanti, occupazione per le prenotazioni di un evento | fatto (`9ed8490`) |
| 2 | Backend eventi: CRUD, pubblicazione con conflitti, annullamento, quota, API pubbliche, audit, media | fatto (`9ed8490`) |
| 3 | Prenotazioni per eventi (sito e gestori), quota alla conferma, rilasci | fatto (`9ed8490`) |
| 4 | Test backend (ciclo di vita, conflitti, concorrenza, permessi) | fatto (`1900a87`) |
| 5 | Frontend: pagina Eventi pubblica, dialog per evento, gestione eventi, richieste; Playwright | fatto (`91850b1`) |
| 6 | Documentazione, CI, report; stop in attesa di approvazione | documenti fatti; CI da verificare |

## File modificati non committati
- (aggiornato a ogni checkpoint)

## Test
- Punto di partenza: 70 + 265 backend, 66 Playwright (FASE 5).
- FASE 6 in locale: 70 + 294 backend, 69 Playwright, tutti verdi (totale 433).

## Errori da risolvere
- Nessuno.

## Prossima azione esatta
Verificare le due workflow GitHub Actions sull'ultimo commit e riportarle in TEST_REPORT §CI; poi **fermarsi**:
la FASE 7 richiede l'approvazione del committente.
