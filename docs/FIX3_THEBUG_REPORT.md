# FIX 3 — «theBug»: report tecnico

> Branch `fix3/theBug`, creato da `origin/main` @ `0d43244` il 28/10/2026. **Nessun merge, nessun push su `main`, nessun
> deploy.** Il branch resta separato per il collaudo manuale del titolare.

## 1. Stato iniziale

| Voce | Valore |
|---|---|
| Repository | `antonio-de-santis-dev/FireFlies` (originale; la copia `Antonio-test-dev/FireFlies` non è stata toccata) |
| Branch di partenza | `origin/main` |
| Commit iniziale di `main` | `0d43244` «docs: FIX 2 approvato dal titolare e portato su main (C-35)» |
| Java / build | OpenJDK 21.0.12, Maven 3.9.11 (wrapper) |
| Backend | Spring Boot 4.1.1 (Spring Security 7, Jackson 3), JdbcClient (niente JPA), Flyway V1–V13 |
| Database | PostgreSQL 17 (`postgres:17-alpine`, digest fissato in `infra/compose*.yaml`), Testcontainers nei test |
| Frontend | React 18.3.1, TypeScript 5.6.3, Vite 5.4.21, Node 22.22.0 |
| Test E2E | Playwright 1.56.1 con backend e PostgreSQL reali (`frontend/scripts/e2e.mjs`) |
| Reverse proxy | Caddy 2 (`frontend/Caddyfile`), immagini in `backend/Dockerfile`, `frontend/Dockerfile` |

**Test iniziali su `main`** (worktree pulito, `./mvnw clean verify`): **82/82 unit, 358/358 integrazione, BUILD
SUCCESS in 5:50**. Frontend: `npm run build` riuscito in 11,9 s.

**Problema già presente nell'ambiente (non nel codice):** un primo `./mvnw verify` senza `clean` era fallito in 4 test di
`MigrationIT`, perché in `backend/target/classes` era rimasta la migrazione `V14__whatsapp_cloud_api.sql` di un altro
branch: Maven non cancella le risorse tolte da `src`. Rischio concreto: **cambiando branch senza `clean`, il backend
avviato in locale può applicare al database una migrazione di un altro branch.** Raccomandazione: `./mvnw clean …`
dopo ogni cambio di branch (vedi §9).

## 2. Skill analizzate

| Skill | Percorso | Disponibilità | Principi applicati in FIX 3 |
|---|---|---|---|
| java-spring-review | sessione + `Skill-programmazione-/Java spring review-v2` (identiche, confrontate) | letta | Passo 0 (pom, yml, convenzioni prima di scrivere); ordine di review sicurezza → correttezza → contratto → manutenibilità; test sui percorsi di errore; anti-overengineering (nessuna interfaccia o mapper nuovi) |
| react-development | sessione + `React development-v1` (identiche) | letta | ordine di review (stato ed effetti prima delle performance); `references/data-fetching.md` §race condition (F-03); `performance.md`: niente `useMemo`/`memo` senza misura |
| react-state-redux | `React state redux-v1` | letta (indice) | una sola fonte per i dati condivisi (F-01, F-02) |
| ui-ux-design | sessione + `Ui ux design-v1` | letta (`review-checklist.md`) | stati di caricamento/errore, conferma delle azioni distruttive (F-09), stato «in corso» visibile (F-06, F-08) |
| docker-kubernetes-devops | sessione + `Docker kubernetes devops-v2` | letta | review: segreti → riproducibilità → sicurezza runtime → correttezza operativa; nessuna modifica che tocchi gli ambienti distribuiti |
| microservices-architecture | `Microservices architecture-v2` | letta (in FASE 0) | outbox at-least-once: verificato che un errore di notifica non annulli la prenotazione |
| ui-ux-pro-max | `nextlevelbuilder/ui-ux-pro-max-skill` | clonata e letta (`SKILL.md`) | priorità 1–3 (accessibilità, interazione, performance). **Script di ricerca non eseguiti** (non necessari) |
| Emil Kowalski skills | `emilkowalski/skills` | clonata; lette `break-ui`, `animation-performance`, `review-animations` | `break-ui`: dati peggiori realistici (ingredienti con virgole, risposte lente, doppi clic); nessuna animazione modificata |
| taste-skill | `Leonxlnx/taste-skill`, `senlindesign/taste-skill` | clonate e lette | non applicabili: nessun redesign (identità grafica approvata da preservare) |
| Impeccable | `pbakaus/impeccable` | clonata; letti `SKILL.md`, `reference/audit.md`, `reference/harden.md` | checklist di audit e «harden» (errori di rete, concorrenza). **Il launcher scarica un binario: non eseguito** |
| Awesome DESIGN.md | `VoltAgent/awesome-design-md` | clonata | raccolta di DESIGN.md di altri prodotti: nessun uso (UI approvata) |
| Formazione | `antonio-de-santis-dev/Formazione` | clonata; letto `Sicurezza/Sicurezza-Permessi-JHipster.md` | doppio livello di autorizzazione (URL + metodo) → verificato in B-01 |
| Microsoft Playwright | strumento 1.56.1 | in uso | test di regressione E2E |
| Playwright Agent CLI | `playwright.dev/agent-cli` | **non raggiungibile** (proxy: connessione rifiutata) | non usata, contenuto non inventato |
| CLAUDE.md, AGENTS.md, `.claude/skills/` nel repository | — | **assenti** | — |

## 3. Bug individuati

Analisi: quattro revisioni indipendenti in sola lettura (sicurezza/configurazione, prenotazioni/capienza, frontend,
notifiche/contenuti/CI) e **verifica personale di ogni rilievo** prima di correggerlo. «Riprodotto su main» = il test di
regressione è stato eseguito anche sul codice di `main` ed è fallito con il sintomo descritto.

| ID | Gravità | Componente | Descrizione | Causa | Stato | Test di verifica |
|---|---|---|---|---|---|---|
| B-01 | media | sicurezza | Uno STAFF che apre `GET /admin/opening-exceptions` riceve **500** (stack trace nel log, nessun audit) invece di 403 | `@PreAuthorize` più stretto della regola sull'URL: l'`AccessDeniedException` nasce in Spring MVC e la prende `@ExceptionHandler(Exception.class)` | corretto: rilancio verso Spring Security (403 + `ACCESS_DENIED`) e regola sull'URL | `ApiSecurityMatrixIT` — **riprodotto su main: 500** |
| B-02 | media | login | Cinque errori di password **in giorni diversi** bloccano l'account e rivelano quali email esistono (429 contro 401) | il contatore sul database non scadeva mai | corretto: contano solo gli errori nella finestra di 15' (dall'audit, nessuna migrazione) | `AuthenticationIT.erroriDistantiNel…` — **riprodotto su main** |
| B-03 | bassa | login | Finito un blocco, **un solo** errore di battitura riblocca per 15' | contatore non azzerato alla fine del blocco | corretto | `AuthenticationIT.finitoIlBlocco…` — **riprodotto su main: 429** |
| B-04 | media-alta | prenotazioni | Un inserimento manuale fuori dagli arrivi (23:30, 12:30) finisce nella fascia **Colazione** e ne consuma il budget: la Sera può superare la capienza | ultimo ripiego di `windowFor` = prima fascia del giorno | corretto: ripiego sulla fascia dell'orario (`Band.at`) | `Fix3ReservationIT` — **riprodotto su main: COLAZIONE** |
| B-05 | media-alta | prenotazioni | Dopo l'arrivo il gestore non può allungare la durata né aggiungere persone (422 «orario già passato»), contro BOOKING_RULES §3.4 | `timing()` rifiuta qualsiasi inizio passato, anche quello invariato | corretto: se l'inizio non cambia basta che la confermata non sia finita | `Fix3ReservationIT` — **riprodotto su main: 422** |
| B-06 | alta (al primo deploy) | Caddy | `/`, `/menu`, `/eventi`… **senza Cache-Control**; un `/assets/vecchio.js` sparito dopo un deploy riceve `index.html` marcato `immutable` per un anno → **pagina bianca** | `header` valutato prima di `try_files` (ordine predefinito di Caddy) | corretto: blocco `route` (ordine scritto), asset mancanti 404 | Caddy 2.10.2 reale, prima/dopo in §6.4; `caddy validate` in CI |
| B-07 | media | eventi | Uno STAFF può annullare un evento con una prenotazione confermata in quel momento (regola «solo il titolare») | controllo letto senza lock dell'evento | corretto: controllo ripetuto sotto lock | revisione del codice + `EventApiIT` (nessuna regressione); la gara non è riproducibile in modo deterministico |
| B-08 | bassa | prenotazioni | Paginazione dell'elenco senza criterio unico: righe con stesso stato/orario/creazione possono ripetersi o mancare tra pagine | `ORDER BY` senza `id` | corretto (preventivo) | `Fix3ReservationIT` — **non riprodotto su main** (dipende dal piano di PostgreSQL), resta come guardia |
| B-09 | media | foto | Una foto alta e stretta nei limiti (3.200×12.000) si decodifica intera: ~150 MB (×2 per PNG a 16 bit) su ~540 MB di heap in produzione | passo di lettura calcolato solo sulla larghezza | corretto: tetto di 16 MP decodificati | `ImageProcessorTest.fotoAltaEStretta…` |
| F-01 | alta | area gestori | Pubblicare/salvare/annullare un evento **cancella dalla memoria** foto non in galleria, voci di menu nascoste, avvisi spenti e contenuti dell'area gestori (fino al ricaricamento) | `reloadPublic()` sostituiva tutti i dati con quelli pubblici | corretto: si ricaricano solo gli eventi | `fix3.spec.ts` — **riprodotto su main: 32 foto → 25** |
| F-02 | alta | impostazioni | Salvare le «Regole» **riapre le richieste dal sito** spente con l'interruttore (e viceversa l'interruttore rimanda indietro le regole) | due copie locali della stessa regola | corretto: `patchPolicy` cambia solo i propri campi partendo dal server | `fix3.spec.ts` — **riprodotto su main** |
| F-03 | media | «Oggi» | Cambiando fascia in fretta si vedono le prenotazioni della fascia precedente | nessun controllo sulle risposte vecchie | corretto (numero di sequenza) | `fix3.spec.ts` — **riprodotto su main** |
| F-04 | media | capienza | Qualunque azione della sezione cancella i campi non salvati degli altri pannelli | `key` comune che rimonta tutti i pannelli | corretto | `fix3.spec.ts` — **riprodotto su main: 95 → 60** |
| F-05 | media | menu | Negli ingredienti non si può scrivere la virgola («pane, burro» → «paneburro») | il campo si ricostruiva dall'elenco a ogni tasto | corretto | `fix3.spec.ts` — **riprodotto su main** |
| F-06 | media | prenotazione pubblica | Chiudendo il dialog durante l'invio il cliente perde il riferimento e un nuovo invio crea un **doppione** | Esc/X/sfondo attivi durante l'invio; nuova chiave di idempotenza alla riapertura | corretto: dialog non chiudibile durante l'invio | `fix3.spec.ts` — **riprodotto su main** |
| F-07 | bassa | richieste | Doppio clic su «Invia di nuovo» accoda due nuovi invii | pulsante non disabilitato durante la richiesta | corretto | revisione del codice (serve una notifica fallita: non coperto da E2E) |
| F-08 | media | foto | Due spostamenti rapidi: uno va perso | ogni ordine partiva dall'elenco del momento del clic | corretto: una modifica alla volta | `fix3.spec.ts` — **riprodotto su main** |
| F-09 | bassa | foto | Eliminazione definitiva con un clic | nessuna conferma (menu ed eventi la chiedono) | corretto: conferma in riga; foto in uso → messaggio subito | `fix3.spec.ts` |
| CI | media | GitHub Actions | I push su `fix3/**` non avviavano i test | filtri dei branch | corretto | run su `fix3/theBug` (§8) |

## 4. Ottimizzazioni backend

- **P-01 — elenco eventi** (`EventService.admins/pubs`, `EventStore.mediaOf/counts`): prima circa 6 query per evento nella
  vista dei gestori (foto lette due volte, tre `count(*)`) e 3–4 in quella pubblica; ora un numero **fisso** di query per
  tutto l'elenco (foto degli eventi, foto, conteggi con `count(*) FILTER`, regole lette una volta e solo se servono).
  La vista di un singolo evento usa lo stesso codice con un elenco di una riga: nessuna logica duplicata.
- **P-02 — elenco prenotazioni** (`ReservationViews.summaries`, `ReservationStore.activeTableCodes`): prima due query per
  riga (tavoli, titolo dell'evento), ora due query per pagina. Una pagina da 200 righe: da ~400 query a 2 + la pagina.
- **Transazioni:** le letture restano `@Transactional(readOnly = true)` come prima; nessuna annotazione nuova senza motivo.
  Non introdotti `@Cacheable` (nessuna strategia di invalidazione necessaria: i tempi sono già bassi) né `@Async`.
- **Gestione delle risorse:** tetto di pixel decodificati per le foto (B-09).
- **Migrazioni:** **nessuna**. La V14 è già usata dal branch `serviotelefonico` (WhatsApp): una seconda V14 avrebbe
  bloccato Flyway al merge. Le correzioni che richiederebbero una colonna nuova sono in §9.

## 5. Ottimizzazioni frontend

Le correzioni del frontend riguardano **correttezza e affidabilità**, non il peso: il bundle è praticamente invariato.

| Bundle (Vite, produzione) | `main` | `fix3/theBug` |
|---|---|---|
| JS pubblico `index-*.js` | 258,10 kB (79,51 gzip) | 258,73 kB (79,68 gzip) |
| JS area gestori `AdminArea-*.js` (già caricato a richiesta) | 127,56 kB (33,66 gzip) | 128,48 kB (33,87 gzip) |
| CSS | 101,68 kB (22,43 gzip) | 101,68 kB (22,43 gzip) |
| Tempo di `vite build` | 3,92 s | 3,48 s |

Valutate e **non applicate** (motivo):
- spostare `admin.css` (44 kB, ~8 kB gzip) fuori dalle pagine pubbliche: classi condivise col dialog di prenotazione
  (`.field`, `.input`, `.spin`…), rischio di regressioni visive superiore al guadagno; da fare con un audit delle classi;
- `sizes` delle miniature pubbliche (scaricano la variante 960/1600 px per riquadri piccoli): guadagno reale ma da
  misurare pagina per pagina con screenshot (§9);
- miniature `eager` nel selettore di foto: commento esplicito nel codice (con `lazy` restavano vuote nel riquadro scorrevole).

## 6. Performance

### 6.1 Metodo

Stesso jar compilato da `main` e da `fix3/theBug`, stesso dataset su un PostgreSQL **usa e getta** (progetto Compose
separato `fireflies-perf`, eliminato con il volume alla fine): dati dimostrativi + 40 eventi pubblicati + 399 prenotazioni
su 20 giorni (metà confermate). Per ogni API: 15 richieste di riscaldamento, poi 150 misurate in sequenza, p50/p95 in ms.
Script: `perf/bench.mjs` (scratchpad della sessione, non nel repository). Nessun dato reale.

### 6.2 Risultati

PERF_TABLE

### 6.3 Avvio, memoria, CPU

PERF_RUNTIME

### 6.4 Cache del sito (Caddy 2.10.2, `dist` reale)

| Percorso | Prima | Dopo |
|---|---|---|
| `/` | 200 text/html, **nessun Cache-Control** | 200 text/html, `no-cache` |
| `/menu` | 200 text/html, **nessun Cache-Control** | 200 text/html, `no-cache` |
| `/index.html` | 200, `no-cache` | 200, `no-cache` |
| `/assets/index-*.js` | 200 JS, `immutable` 1 anno | 200 JS, `immutable` 1 anno |
| `/assets/vecchio-ABC123.js` (sparito dopo un deploy) | **200 text/html, `immutable` 1 anno** | **404** |

### 6.5 Query lente

Nessuna query singola lenta trovata (gli endpoint più lenti erano lenti per **numero** di query, non per durata). Non è
stato abilitato `pg_stat_statements` (non presente nell'immagine): il conteggio delle query per richiesta deriva dalla
lettura del codice.

## 7. Sicurezza

Corrette: B-01 (500 invece di 403, nessun audit), B-02 (enumerazione degli account e blocco con errori vecchi), B-03,
B-06 (cache), B-07 (regola «solo il titolare» aggirabile in concorrenza), B-09 (memoria con una sola foto).

Verificati senza problemi: matrice URL + `@PreAuthorize` (nessuna API del titolare aperta allo STAFF), cambio dell'id di
sessione e del token CSRF al login, CSRF su tutte le scritture, `X-Forwarded-For` fidato solo dal proxy interno,
percorsi dei file normalizzati, nessun dettaglio interno negli errori, nessun segreto nei file di configurazione o nelle
immagini, actuator filtrato da Caddy, intestazioni di sicurezza su API e sito.

Rischi residui (non corretti, vedi §9): risposte più rapide per account disattivati (differenza di tempi misurabile),
tentativi di login paralleli oltre il limite (controllo e registrazione non atomici), upload paralleli che tengono in
memoria ciascuno il proprio file prima del semaforo, immagine Caddy eseguita come root e tag non fissati.

## 8. Test

| Suite | `main` (prima) | `fix3/theBug` (dopo) |
|---|---|---|
| Backend unit (`./mvnw clean verify`) | 82/82 | **83/83** (+1) |
| Backend integrazione (PostgreSQL reale, Testcontainers) | 358/358 | **363/363** (+5) |
| E2E Playwright (`npm run verifica`, backend e PostgreSQL reali) | non rieseguita su main (vedi sotto) | E2E_RESULT |
| Nuovi test di regressione eseguiti **anche su main** | — | IT: 5 su 6 falliscono su main (B-08 no, preventivo); E2E: 7 su 7 falliscono su main |
| CI GitHub Actions sul branch | — | CI_RESULT |

Test aggiunti: `ApiSecurityMatrixIT` (+1 endpoint), `AuthenticationIT` (+2), `Fix3ReservationIT` (3), `ImageProcessorTest`
(+1), `frontend/tests/fix3.spec.ts` (7, aggiunto alle suite `qa` e `verifica`). Nessun test esistente modificato per
farlo passare.

Non eseguiti: test sul database di sviluppo del titolare (vietato), F-07 in E2E (richiede una notifica fallita), gara di
B-07 (non deterministica), misure su dispositivi reali.

## 9. Problemi residui e proposte (non applicati)

| Tema | Rischio | Perché non ora / proposta |
|---|---|---|
| Lost update su categorie del menu e avvisi | due gestori: una categoria nascosta torna visibile | serve una colonna `version` (migrazione): da fare **dopo** aver deciso l'ordine con la V14 di WhatsApp |
| Branch e `target/` di Maven | migrazione di un altro branch applicata al DB locale | usare sempre `./mvnw clean …` dopo un cambio di branch |
| Prenotazione per evento confermata in un'altra zona dopo lo spostamento dell'evento | solo con più zone | validare la zona dell'evento alla conferma |
| Spostare un evento può dare l'errore «richiesta duplicata» pensato per il cliente | messaggio fuorviante | tradurre l'errore in quel contesto |
| Ordine dei lock fra zone (UUID Java con segno vs PostgreSQL) | deadlock → 409 pulito, solo con più zone | ordinare sempre in SQL |
| Regole con anticipo < scadenza | richieste che scadono subito | proporre al titolare la regola «anticipo ≥ scadenza» (cambia una regola di business) |
| NO_SHOW non restituisce la quota dell'evento (BOOKING_RULES §2 dice di sì) | contatore dell'evento più alto | decidere col titolare (cambia la logica della capienza) |
| Eventi tra 00:00 e 05:00 contati nel giorno dopo | budget del giorno sbagliato | intenzione da chiarire |
| Login: tempi diversi per account disattivati; tentativi paralleli | enumerazione / pochi tentativi in più | limitatore atomico e bcrypt fittizio anche per account disattivati |
| Upload paralleli prima del semaforo | memoria | spostare la lettura del file dopo il semaforo |
| Job di pulizia con 3 query per prenotazione | lento con molti dati, notturno | aggregare per lotto |
| Menu e sito pubblici con `max-age=60` | modifiche visibili fino a 60 s dopo | è voluto (test `MenuApiIT`); passare a `no-cache` se serve |
| Immagini: Caddy root, tag `2-alpine`/`22-alpine` non fissati, `web` senza attesa dello stato «healthy» del backend | riproducibilità, 502 all'avvio | cambiano l'ambiente distribuito: da applicare col titolare |
| `sizes` delle miniature pubbliche, `admin.css` sul sito | byte in più sul telefono | misurare con screenshot prima di cambiare |
| Avviso «sessione scaduta» sovrascrivibile; focus dopo il menu mobile; scheletro finto di 450 ms nel menu | UX | piccoli interventi separati |

## 10. Stato Git finale

GIT_FINAL
