# FASE 8 — Report tecnico (hardening, performance, UX responsive, preparazione al deploy)

> Branch `fase-8/hardening-deploy`, creato da `fase-7/notifiche` (`769cd1f`, FASE 7 approvata). 20/10/2026. Nessun merge
> su `main`, nessun force push, **nessuna pubblicazione**, nessun servizio acquistato o risorsa cloud creata, notifiche
> reali email/SMS **spente**. Decisioni T-71…T-77 e C-31 nel DECISION_LOG. **In attesa del collaudo del titolare.**

## 1. Modifiche completate

| # | Requisito | Stato | File principali | Test |
|---|---|---|---|---|
| 1 | Editor eventi senza «Ora fine» e senza «Coperti / Capienza evento» | **Fatto** | `EventService` (durata e quota ricavate), `application.yml` (`fireflies.events.default-duration`), `EventsAdmin.tsx` | `EventApiIT.senzaOraDiFineNeCopertiValoriRicavati`, `…senzaCapienzaDelLocaleNienteRichiestePerEventi`; `events.spec` «editor senza «Fine» e senza coperti» (con ricaricamento) |
| 2 | Filtri delle Richieste compatti sul telefono | **Fatto** | `Requests.tsx` (`ScrollChips`, toolbar compatta), `AdminLayout.tsx` (`AdminHead compact`), `admin.css` | `fase8.spec` a 360/375/390/430/768/1280/1920 |
| 3 | Foto più visibili in Menu e prezzi | **Fatto** | `MenuAdmin.tsx` (`ProductThumb`), `admin.css` | `fase8.spec` desktop e telefono, foto mancante/in errore |
| 4 | Telefono cliccabile e copiabile per eventi/compleanni | **Fatto** | `BookingDialog.tsx` (`PhoneCard`), `lib/phone.ts`, `public.css` | `fase8.spec` con numero (390 e 1366, tastiera), senza Clipboard API, senza numero |
| 5 | Audit di sicurezza e correzioni | **Fatto** | `SecurityConfiguration`, `application-prod.yml`, `.gitignore`, `.dockerignore` | `ProxyHeadersIT` (3), `ApiSecurityMatrixIT` (4) |
| 6 | Performance con misure prima/dopo | **Fatto** | `App.tsx` + `pages/admin/AdminArea.tsx` (caricamento separato), `V11__fase8_indice_worker.sql` | `perf.spec` (2), `MigrationIT`; `evidence/PERFORMANCE.md` |
| 7 | Backup e ripristino | **Fatto** (prova completa in isolamento) | `infra/scripts/backup.sh`, `restore.sh`, `restore-drill.sh`, `check-health.sh` | `evidence/RESTORE_DRILL.md` |
| 8 | Preparazione al deploy (senza pubblicare) | **Fatto** | `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/Caddyfile`, `infra/compose.prod.yaml`, `infra/.env.prod.example`, `.github/workflows/images.yml`, `docs/operations/DEPLOY.md` | CI `images` |
| 9 | Test e CI | **Fatto** | §9–10 | — |
| 10 | Documenti | **Fatto** | DECISION_LOG, BOOKING_RULES §7.5, API_CONTRACT, DATA_MODEL §3.10, SECURITY_PRIVACY, PRE_DEPLOY, DEPLOY (nuovo), LOCAL_SETUP, MASTER_PLAN, TEST_REPORT, WORK_PROGRESS, questo report | — |

### 1.1 Requisito 1 — regola dei valori ricavati (T-71, T-72, BOOKING_RULES §7.5)
- **Fine**: se l'evento aveva già orari conserva la **sua durata** (spostando l'inizio si sposta la fine); un evento nuovo
  dura **4 ore** (configurabile, 15′–12h a multipli di 15′, controllato all'avvio). 19:00 → 23:00; 23:00 → 03:00.
- **Quota per gli eventi con richieste dal sito**: quella **già salvata** se esiste (eventi esistenti invariati), altrimenti
  la **capienza fisica del locale**; mai sotto i confermati. Non è un secondo contatore: il registro dei posti della
  FASE 2 resta il limite vero, quindi il valore predefinito non può creare conflitti di capienza.
- **Informazione che non si può ricavare in sicurezza**: se il locale non ha una capienza configurata il backend non
  inventa numeri e risponde `422 VENUE_CAPACITY_MISSING` («imposta la capienza in Impostazioni»).
- Invariati: esclusività, `EVENT_CONFLICTS`, `EVENT_HAS_CONFIRMED_RESERVATIONS`, `CAPACITY_BELOW_CONFIRMED`; le API
  accettano ancora `endTime` e `capacityCovers` espliciti. Verificato dopo il ricaricamento della pagina (Playwright) e
  direttamente nel database (`EventApiIT`: `ends_at`, `capacity_covers`, 16 quanti riservati per 19:00–23:00).
- Le locandine restano intere (`posters.spec`, invariato).

### 1.2 Requisiti 2–4 — interfaccia
- **Richieste sul telefono**: Periodo e Origine affiancati, ricerca con icona, «Aggiorna» come pulsante a icona con nome
  accessibile «Aggiorna l'elenco», stati in una riga scorrevole con sfumatura e pulsante «Mostra gli altri filtri» quando
  ce ne sono altri, target ≥ 44 px; intestazione compatta: la prima scheda sta nel 60 % alto dello schermo. Desktop: una
  sola riga ordinata. Verificato a 360, 375, 390, 430, 768, 1280 e 1920 px.
- **Menu e prezzi**: miniature quadrate più grandi (88 px desktop, 96 px telefono) dalla variante ridotta già esistente
  (`srcset`), ritagliate solo nella miniatura (`object-fit: cover`, originale intatto); segnaposto con nome accessibile
  se la foto manca o non si carica. Desktop: stesse righe e colonne; telefono: schede con foto e nome in evidenza, switch
  e «Modifica» dentro la scheda. Le locandine degli eventi **non** sono ritagliate (componente diverso, `posters.spec`).
- **Organizza un evento / compleanno**: numero preso **solo** dalle impostazioni del sito; mostrato per intero, «Chiama
  ora» (`tel:+39…` normalizzato), «Copia numero» con conferma solo se la copia riesce; senza Clipboard API (contesto non
  sicuro o permesso negato) appare il numero selezionabile con l'istruzione, nessun permesso richiesto. Senza numero:
  avviso, nessun link `tel:` vuoto, «Scrivici su Instagram».

## 2. Bug trovati e corretti
| Bug | Dove | Correzione |
|---|---|---|
| Con un `X-Forwarded-For` falsificato un client poteva cambiare il proprio IP dietro un proxy (limiti per IP aggirabili) | configurazione di produzione | `RemoteIpValve` con proxy fidati, `ProxyHeadersIT` |
| Il loopback IPv6 non era tra i proxy fidati: dietro un proxy locale tutti i client avrebbero avuto lo stesso IP | `application-prod.yml` | aggiunto `0:0:0:0:0:0:0:1` |
| `.env.*` (es. `.env.prod`) non era escluso da git | `.gitignore` | esclusi tutti i `.env.*` tranne gli esempi |
| Il worker leggeva l'outbox con una scansione completa (25 ms con 100.000 righe, a ogni giro) | query di presa in carico | indice parziale V11 (0,1 ms) |
| Test: ricaricamento della pagina prima della fine del salvataggio (instabile) | `event-visibility.spec` | attesa del messaggio «salvato» |
| Prima scheda delle Richieste sotto la metà dello schermo sul telefono | `Requests.tsx` | intestazione compatta |

## 3. Sicurezza — audit
| Area | Esito | Evidenza / azione |
|---|---|---|
| Sessioni | cookie `HttpOnly`, `Secure` in prod, `SameSite=Lax`, sessione nuova al login, scadenza | FASE 3, invariato |
| CSRF | ogni `POST` senza token → 403, **tutti** gli endpoint registrati | `ApiSecurityMatrixIT.scrittureSenzaCsrf403` |
| Permessi OWNER/STAFF | ogni API dei gestori anonima → 401; le API del titolare con lo staff → 403 | `ApiSecurityMatrixIT` (matrice ricavata dagli endpoint, nessuno dimenticato) |
| Validazione input | Bean Validation + controlli di dominio; errori senza stack trace né messaggi interni | `GlobalExceptionHandler`, IT esistenti |
| Dati personali | mai nei log (unico dato: email del titolare al primo avvio), mai nelle API pubbliche, calendario di oggi senza telefoni | revisione dei `log.` del backend |
| Rate limiting | login per account e IP, richieste dal sito per IP e per telefono | IT esistenti + `ProxyHeadersIT` |
| IP dietro proxy | **corretto** (§2) | `ProxyHeadersIT`, PRE_DEPLOY V-01 |
| Header di sicurezza | **aggiunti** HSTS, `Permissions-Policy`, `Cross-Origin-Opener-Policy`; CSP nel Caddyfile | `ProxyHeadersIT.headerDiSicurezza` |
| CORS | non configurato: stessa origine, nessuna richiesta cross-origin accettata con credenziali | — |
| Upload | 5 MB, 50 megapixel, solo JPEG/PNG/WebP verificati dal contenuto, ricodifica | FASE 4 |
| Credenziali | nessun segreto nei file versionati; `.env.*` esclusi; `.dockerignore`; immagini controllate in CI; e2e e prova di ripristino con password casuali | `git grep`, workflow `images` |
| Dipendenze | produzione: `npm audit --omit=dev` **0 vulnerabilità**; sviluppo: corretti `braces` e `sharp` (0.35.5, solo test); restano `vite` 5/`esbuild` (solo server di sviluppo, §11) | `npm audit` |
| Endpoint di amministrazione | `/actuator` espone solo `health` e `info` senza dettagli; da fuori Caddy risponde 404 tranne `health` | Caddyfile |
| Concorrenza | conferme, pubblicazioni e pulizia serializzate dai lock (FASI 5–7) | IT di concorrenza invariati e passati |
| Operazioni distruttive | eliminazioni con conferma e controlli (`EVENT_HAS_HISTORY`…), ripristino rifiutato su database non vuoto | `restore.sh` |

## 4. Ottimizzazioni con misure (dettaglio in `docs/operations/evidence/PERFORMANCE.md`)
| Misura | Prima | Dopo |
|---|---|---|
| JavaScript del sito pubblico (gzip) | 108.224 B | **78.380 B (−27,6 %)** |
| DOMContentLoaded (telefono, rete lenta, CPU ×4) | 1.145 ms | **917 ms** |
| Titolo della home visibile | 2.190 ms | **1.970 ms** |
| Chiamate API all'avvio del sito / della panoramica | 4 / 12 | 4 / 12, **nessun duplicato** |
| Panoramica dei gestori pronta | 190 ms | 161–189 ms (precaricamento dall'accesso) |
| Presa in carico del worker (100.000 notifiche) | 25,4 ms | **0,1 ms** (V11) |
Le altre query (richieste, calendario, riepilogo, pulizia) restano sotto 70 ms con 200.000 prenotazioni: nessun altro
indice giustificato. Immagini già servite come varianti ridotte con `srcset` dalla FASE 4. Pool di connessioni: 8 in
produzione (una sola istanza, PostgreSQL con 512 MB).

## 5. UX desktop e mobile
Cambiamenti limitati a quanto richiesto, con la grafica approvata invariata (colori, font, componenti). Screenshot reali
di Playwright allegati ai test `fase8.spec` (Richieste alle 7 larghezze, Menu desktop/telefono, scheda telefono con e
senza numero) nel report HTML di Playwright e negli artifact della CI.

## 6. Nuove funzionalità
- Scheda telefono per eventi e compleanni (chiama, copia, alternativa Instagram).
- Durata e quota degli eventi ricavate automaticamente.
- Script operativi: backup verificato, ripristino sicuro, prova di ripristino completa, controllo giornaliero.

## 7. Controlli sul database
- V11 solo additiva (un indice); nessuna migrazione distruttiva; 11 migrazioni applicate e validate da Flyway.
- Vincoli esistenti verificati anche con dati sintetici: hanno rifiutato stati incoerenti (`ck_res_occupying`,
  `ck_outbox_skipped`, `ck_res_web_privacy`).
- Eventi esistenti: fine e quota conservate alla modifica (`EventApiIT`), riconciliazione del registro pulita dopo ogni IT.

## 8. Backup e ripristino
`infra/scripts/restore-drill.sh`: due PostgreSQL **isolati** (porte 55511/55512, eliminati alla fine; database di
sviluppo mai toccato) — dati reali di prova con foto e una richiesta dal sito, `backup.sh`, ripristino con `restore.sh`
su un database vuoto, backend riavviato sul database ripristinato. Esito in `evidence/RESTORE_DRILL.md`: tutte le tabelle
con le stesse righe, stessa foto (SHA-256 identico), prenotazione ritrovata in attesa, migrazioni Flyway valide, dati
demo non ricaricati; `check-health.sh` segnala una pulizia notturna mancante e passa quando tutto è in ordine.

## 9. Risultati dei test (eseguiti davvero, in sequenza, commit `9beade5`)
| Suite | FASE 7 | FASE 8 | Esito |
|---|---|---|---|
| Backend unità | 78 | 78 | ✅ 78/78 |
| Backend integrazione (PostgreSQL 17 reale) | 311 | **320** (+`EventApiIT` 2, `ProxyHeadersIT` 3, `ApiSecurityMatrixIT` 4) | ✅ 320/320 |
| Playwright con backend e DB reali | 93 | **111** (+`fase8.spec` 17, `events.spec` 1) | ✅ 111/111 |
| Performance (`perf.spec`) | — | 2 | ✅ 2/2 |
| Prova di ripristino | — | 1 scenario | ✅ |
Le 12 verifiche richieste dal mandato e dove sono coperte: TEST_REPORT «FASE 8». Tutte le suite sono passate al primo
giro completo.

## 10. Risultati della CI
| Workflow | Commit | Run | Esito |
|---|---|---|---|
| backend (ultimo commit con codice backend) | `07d6da9` | 39472456846 | ✅ |
| frontend | `9beade5` | 39473373201 | ✅ |
| images (build senza push, controlli su segreti, Caddy e utente) | `9beade5` | 39473373320 | ✅ |
Tutti i commit della FASE 8 hanno CI verde.

## 11. Problemi aperti
- **Vite 5 / esbuild** (avvisi solo del server di sviluppo, nessun effetto sui file pubblicati): il passaggio a Vite 8 è
  un aggiornamento maggiore dello strumento di build, da fare in una fase dedicata con la suite completa.
- **V-02** comportamento della dashboard con richieste concorrenti: da provare con i gestori nel collaudo.
- **V-03, V-07, V-10** dati reali (capienza, orari, numero di telefono, eventi), `index.html` senza `noindex` e
  «Prototipo»: solo con i contenuti approvati.
- **V-04** testo dell'informativa privacy da validare; **V-05** notifiche reali spente fino alla scelta del fornitore.
- Immagini Docker costruite e controllate solo in CI (Docker Hub limita i download in questo ambiente).
- La CSP consente `style-src 'unsafe-inline'` (stili in linea di React); nessuno script in linea è permesso.

## 12. Costi e proposta di hosting
Un VPS in UE (2 vCPU, 4 GB) con Docker Compose, Caddy (HTTPS gratuito), PostgreSQL nello stesso server, foto su
Cloudflare R2: **circa 6–15 €/mese più il dominio** (prezzi indicativi da riverificare). Dettaglio, alternative,
variabili, migrazioni, backup, monitoraggio, limiti e procedura in `docs/operations/DEPLOY.md`; decisioni D-1…D-6 al
titolare. **Nulla è stato acquistato o creato.**

## 13. Checklist finale prima della produzione
- [ ] Collaudo manuale del titolare su questa versione (desktop e telefono).
- [ ] Decisioni D-1…D-6 (server, dominio, R2, chiave dei backup, provider notifiche, informativa).
- [ ] Autorizzazione dei costi; creazione di server, dominio e bucket.
- [ ] `infra/.env.prod` sul server (600), mai nel repository.
- [ ] Primo titolare creato da terminale; capienza, orari, chiusure, numero di telefono, menu, foto ed eventi reali (V-03, V-10).
- [ ] `index.html` definitivo (V-07); informativa privacy validata (V-04).
- [ ] HTTPS attivo, `curl -I` con gli header attesi, `/actuator/*` chiuso, IP del client corretto (V-01).
- [ ] Cron di backup, copia fuori sede cifrata, `check-health.sh`, monitor esterno; un ripristino di prova sul server (V-06, V-09).
- [ ] Notifiche reali ancora spente finché non autorizzate (V-05).
- [ ] Merge su `main` e deploy **solo dopo** l'autorizzazione esplicita.
