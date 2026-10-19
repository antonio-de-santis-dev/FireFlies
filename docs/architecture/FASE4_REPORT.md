# FASE 4 — Report tecnico (menu, contenuti e gestione delle immagini)

> Branch `fase-4/menu-media`, creato dall'ultimo commit di `fase-3/autenticazione` (`62eb460`). 19/10/2026.
> Nessun merge su `main`, nessun branch approvato modificato. **La FASE 5 non è iniziata.**

## 1. Cosa è stato fatto

### 1.1 Obiettivi della fase
| # | Obiettivo | Stato | Dove |
|---|---|---|---|
| 1 | API REST per categorie, prodotti, prezzi e allergeni | ✅ | `menu/` · `GET /public/menu`, `/admin/menu/**` |
| 2 | Persistenza definitiva di menu e contenuti in PostgreSQL | ✅ | tabelle V2–V3 + migrazione **V9**; `PersistenceAfterRestartIT` (due avvii reali) |
| 3 | Foto su storage S3-compatibile, Cloudflare R2 come candidato | ✅ adattatore pronto e testato contro un finto endpoint S3; **nessun account R2 creato** | `media/S3MediaStorage` |
| 4 | Storage locale per sviluppo e test, senza account a pagamento | ✅ | `media/FileSystemMediaStorage` (cartella, servita dal backend su `/media/**`) |
| 5 | Integrazione progressiva del frontend React | ✅ menu, foto, galleria, testi della home e della storia, titolari, staff, testata del menu, orari e contatti, avvisi · ⏳ eventi e richieste restano dimostrativi (FASI 5–6) | `frontend/src/api/content.ts`, `store.tsx`, pagine dell'area gestori |
| 6 | Grafica, responsive, animazioni e componenti approvati invariati | ✅ stesse pagine, stessi componenti, stesse classi CSS; aggiunti solo: schermata di attesa/errore dei contenuti, pulsanti «Sostituisci» ed «Elimina voce», messaggi d'errore del server | §4; confronto visivo prima/dopo allegato alla consegna (non salvato nel repository) |
| 7 | Gestori autorizzati modificano menu, testi, foto e contenuti secondo i permessi | ✅ | tabella §1.3 |

### 1.2 Backend
- **Menu** (`menu/`): categorie (slug stabile per i link `/menu?cat=`, nome, suggerimento, ordine, visibilità), voci con
  prezzo in **centesimi** (`null` = «Prezzo da definire», 0…1.000 €), ingredienti, foto, visibilità, versione ottimistica.
  Allergeni dei 14 del Reg. UE 1169/2011: **dichiarati solo dopo una verifica esplicita** (chi e quando restano salvati);
  «nessun allergene» è distinto da «da dichiarare». Il prezzo **dimostrativo** ha una colonna a parte (`demo_price_cents`)
  che le API dei gestori non possono scrivere.
- **Foto** (`media/`): caricamento multipart dal backend (5 MB), riconoscimento del tipo dai primi byte, dimensioni lette
  prima di decodificare, orientamento EXIF applicato, varianti 480/960/1600 px mai ingrandite (JPEG progressivo, o PNG a
  256 colori per le immagini trasparenti), **nessun metadato (EXIF/GPS) nelle varianti**, originale mai conservato.
  Sostituzione del file con lo stesso id (cambia ovunque è usata), testo alternativo obbligatorio, galleria e ordine,
  eliminazione solo se non usata (`409 MEDIA_IN_USE` con l'elenco degli usi; chiave esterna come ultima difesa).
- **Contenuti** (`content/`): testi della home e della storia, sezioni, titolari, staff e testata del menu salvati in
  **un'unica richiesta** con una versione (il pulsante «Salva e pubblica» del mockup); indirizzo, contatti, mappa e orari
  dei 7 giorni (anche chiusura dopo mezzanotte); avvisi con scadenza «fino al giorno» in ora italiana.
- **Sito pubblico**: tre letture con **ETag** (`/public/site`, `/public/menu`, `/public/gallery`), solo dati visibili,
  nessun dato interno (versioni, autori, usi).
- **Dati demo**: solo profilo `local`, solo su database vuoto, tramite le stesse API interne dei gestori; database marcato
  come demo; **con `prod` l'avvio si ferma** se il segnale è presente o se si chiede il caricamento (`DemoDataGuard`).
- **Audit**: caricamento/sostituzione/modifica/ordine/eliminazione foto, creazione/modifica/eliminazione di categorie e
  voci (con prezzo precedente e verifica allergeni), contenuti, impostazioni, avvisi, caricamento demo.

### 1.3 Permessi (verificati dal backend su ogni richiesta, non solo nascosti nella UI)
| Area | Anonimo | STAFF | OWNER |
|---|---|---|---|
| Sito pubblico (`GET /public/*`, `GET /media/*`) | ✅ lettura | ✅ | ✅ |
| Menu, prezzi, allergeni (`/admin/menu/**`) | `401` | ✅ | ✅ |
| Foto e galleria (`/admin/media/**`) | `401` | ✅ | ✅ |
| Avvisi (`/admin/announcements/**`) | `401` | ✅ | ✅ |
| Testi, titolari, staff, testata (`/admin/site-content`) | `401` | `403` | ✅ |
| Orari, contatti, mappa (`/admin/site-settings`) | `401` | `403` | ✅ |
| Account (`/admin/users/**`, FASE 3) | `401` | `403` | ✅ |
| Qualunque scrittura pubblica (`POST /public/*`) | chiusa finché la FASE 5 non apre le richieste tavolo | | |

### 1.4 Frontend
- `src/api/content.ts` traduce le risposte del backend nei **tipi già usati dalle pagine** del mockup: il markup non è
  cambiato. `store.tsx` carica il sito pubblico all'avvio (attesa di pochi millisecondi; se il server non risponde:
  «Il sito non riesce a caricare i contenuti» + **Riprova**).
- Area gestori: menu (visibilità, modifica, nuova voce, eliminazione in due passaggi), foto (caricamento, testo
  alternativo salvato all'uscita dal campo, galleria, ordine, **sostituzione**, eliminazione con gli usi dal server),
  «Personalizza il sito», «Orari e contatti», «Comunicazioni»: tutto salvato sul server, errori del server mostrati sul
  campo (prezzo, nome, frase principale) o in un avviso; conflitto di versione spiegato.
- Immagini: `srcset` + `sizes` su ogni foto della libreria (una miniatura da 104 px scarica la variante da 480 px), lazy
  loading sotto la piega, dimensioni dichiarate (nessun salto di layout), JPEG progressivi.
- La build normale **non contiene più i dati del mockup** (verificato cercando i testi demo nel bundle); restano solo
  nella build statica `npm run build:static`.
- Proxy di sviluppo anche per `/media`.

## 2. Miglioramenti richiesti

| Richiesta | Esito |
|---|---|
| Sezione «Account» del titolare | **proposta in §7, non implementata**: come chiesto, prima la posizione nella dashboard e il perimetro |
| Sessione scaduta in schede già aperte | ✅ ogni `401` di un'API dei gestori riporta all'accesso con l'avviso «La sessione è scaduta… Le modifiche non salvate sono andate perse»; al ritorno sulla scheda (`visibilitychange`) la sessione viene ricontrollata subito. Test: `content.spec` «Sessione scaduta in una scheda già aperta» (2 casi) |
| Limite del rate limiter in memoria | documentato di nuovo in §6: i contatori per IP/email vivono nella memoria del processo (si azzerano a ogni riavvio e non si condividono tra più istanze); il **blocco dell'account resta su DB** (`locked_until`). Va bene con una sola istanza (V1); con più istanze serve un contatore condiviso (tabella PostgreSQL, non Redis) |
| Dati demo mai in produzione | ✅ caricatore solo `local` + database vuoto; segnale `site_settings.demo_content`; `DemoDataGuard` blocca `prod`; prezzi demo in una colonna separata e non scrivibile dalle API; build di produzione del frontend senza dati demo |
| Ottimizzazione delle immagini | ✅ varianti 480/960/1600, JPEG progressivo q=0,82, PNG8 per le trasparenze (titolari da ~500 KB a ~140 KB per variante), `srcset`/`sizes`, lazy loading, cache `immutable` di 1 anno con chiavi nuove a ogni sostituzione. WebP/AVIF in uscita rimandati (servono codec nativi) |
| Sicurezza degli upload | ✅ tipo reale dai byte, HEIC/AVIF con messaggio dedicato, limiti di peso (5 MB, `413`) e dimensione (50 MP, 12.000 px, min 200 px) prima di decodificare, ricodifica completa, metadati eliminati, una ricodifica alla volta, solo STAFF/OWNER con CSRF, cancellazioni con nuovi tentativi (`media_orphans`) |

## 3. Decisioni (dettaglio in DECISION_LOG §2-quater)
- **T-26 — da approvare**: upload attraverso il backend invece dell'URL firmato verso il bucket (revisione di ADR-0004).
  Motivo: nessun byte non verificato raggiunge lo storage pubblico, niente CORS, niente upload pendenti. L'interfaccia
  `MediaStorage` non cambia; tornare all'URL firmato resta possibile senza migrazioni.
- T-25 JDBC esplicito confermato (niente JPA) · T-27 formati e varianti · T-28 verifiche sugli upload · T-29 errori dello
  storage · T-30 storage per profilo · T-31 salvataggio unico dei contenuti · T-32 allergeni · T-33 dati demo ·
  T-34 ETag · T-35 bundle senza demo · T-36 sessione scaduta.
- API_CONTRACT §7 e `openapi.yaml` (draft.4, validato con `@redocly/cli@2.1.0 lint`) aggiornati agli endpoint reali.

## 4. File modificati

### Backend
| File | Cosa |
|---|---|
| `pom.xml` | `imageio-webp`/`imageio-jpeg` 3.15.2 (lettura WebP e JPEG CMYK), AWS SDK S3 2.55.8 con client HTTP del JDK (esclusi Netty e Apache) |
| `db/migration/V9__menu_media_contenuti_fase4.sql` | formato delle varianti, `media_orphans`, `demo_price_cents`, conferme di titolari/staff, `demo_content` |
| `media/*` | `MediaStorage`, `FileSystemMediaStorage`, `S3MediaStorage`, `MediaConfiguration`, `MediaProperties`, `ImageProcessor`, `PaletteQuantizer`, `MediaStore`, `MediaService`, `MediaCatalog`, `MediaRef`, `MediaOrphans`, `MediaController` |
| `menu/*` | `MenuStore`, `MenuService`, `MenuController` |
| `content/*` | `ContentStore`, `ContentService`, `ContentController` |
| `demo/*` | `DemoContentLoader`, `DemoDataGuard`, `DemoProperties`, `resources/demo/contenuti-demo.json` (generato dai dati del mockup) |
| `common/*` | `ApiErrors`, `PublicCacheConfiguration` (ETag), `GlobalExceptionHandler` (413), `ConstraintViolationTranslator` (slug duplicato) |
| `security/SecurityConfiguration` | `GET /media/**` pubblico, `site-content`/`site-settings` solo OWNER, `/public/**` solo in lettura |
| `audit/AuditAction` | 18 nuove azioni |
| `application*.yml` | limiti multipart, storage per profilo, `accept-float-as-int: false`, dati demo in `local` |
| test | `ImageProcessorTest`, `S3MediaStorageTest`, `MediaConfigurationTest`, `DemoDataGuardTest`, `MediaApiIT`, `MenuApiIT`, `ContentApiIT`, `DemoContentLoaderIT`, `PersistenceAfterRestartIT`, supporto (`TestImages`, `SwitchableMediaStorage`, `ApiClient` PUT/DELETE/multipart), 2 WebP reali in `test/resources/media` |

### Frontend
| File | Cosa |
|---|---|
| `src/api/client.ts` | PUT/PATCH/DELETE/multipart, rivalidazione delle letture pubbliche, gestore globale dei `401` |
| `src/api/content.ts` (nuovo) | chiamate e traduzione delle risposte nei tipi del mockup |
| `src/store.tsx` | dati dal backend, indice delle foto, stato di caricamento |
| `src/App.tsx`, `styles/base.css` | attesa/errore dei contenuti, ricontrollo della sessione al ritorno sulla scheda |
| `src/components/ui.tsx`, `MenuHero.tsx` | `srcset`/`sizes` |
| `src/pages/admin/*` | menu, foto, personalizza, orari, comunicazioni collegati alle API; barra dell'area gestori aggiornata |
| `src/pages/public/*` | categorie dal backend, stati vuoti (nessun titolare/staff/storia), etichette demo solo sui dati demo |
| `src/data/*` | tipi estesi; `data/demo.ts` solo per la build statica |
| `vite.config.ts`, `scripts/e2e.mjs` | proxy `/media`; backend e2e con dati demo, cartella foto temporanea, attesa della readiness |
| `tests/baseline.ts` (nuovo), `tests/content.spec.ts` (nuovo), `tests/*.spec.ts` | ripristino dei dati dopo ogni test che li modifica; 13 nuovi test; test esistenti adattati (id UUID, foto servite dal backend) |

### Documentazione
`FASE4_REPORT.md` (questo file), `DECISION_LOG.md` (T-25…T-36), `ADR/0004` (revisione proposta), `API_CONTRACT.md`,
`api/openapi.yaml`, `docs/operations/LOCAL_SETUP.md` (§2 foto, §7 S3/R2, §8 dati demo, problemi comuni),
`docs/testing/TEST_REPORT.md`, `README.md`.

## 5. Risultati dei test
Vedi `docs/testing/TEST_REPORT.md` (sezione FASE 4) per il dettaglio e i risultati della CI sull'ultimo commit.

## 6. Limitazioni note
- **R2 non provato dal vivo**: nessun account creato (vincolo della fase). L'adattatore è verificato contro un finto
  endpoint S3 (firma SigV4, percorso, header, errori); la prova su R2 va fatta in FASE 8 con un bucket del cliente.
- **Rate limiter in memoria** (FASE 3, P-21): contatori azzerati a ogni riavvio e non condivisi tra istanze; il blocco
  dell'account è invece su DB. Sufficiente con una sola istanza; da rivedere prima di scalare orizzontalmente.
- **Cache del CDN** (FASE 8): il browser rivalida sempre, ma un CDN davanti all'API potrebbe servire fino a 60 s di dati
  vecchi; in quel caso va configurato per rispettare l'ETag o con TTL più basso.
- Eventi, richieste tavolo e interruttore «Richieste tavolo attive» restano **dimostrativi** (FASI 5–6); la barra
  «Prototipo dimostrativo» del sito resta per questo motivo.
- Le **categorie** si gestiscono via API (creazione, rinomina, ordine, eliminazione) ma non hanno ancora una schermata:
  l'area gestori usa quelle esistenti. Proposta: un pannello «Categorie» nella pagina Menu (da approvare).
- Le eccezioni di apertura (chiusure straordinarie) non hanno ancora API né schermata (tabella pronta dalla V2).
- Titolari e staff: la UI non ha ancora l'interruttore «visibile»; il backend lo supporta.
- Nessun limite di frequenza specifico sugli upload (sono riservati ai gestori autenticati e serializzati uno alla volta).

## 7. Proposta: sezione «Account» del titolare (non implementata, da approvare)

**Perimetro**: rientra nella FASE 4 solo come schermata — le API esistono già dalla FASE 3 (`/api/v1/admin/users`,
`POST /auth/password`), sono testate e non cambiano. Nessuna tabella, dipendenza o endpoint nuovo.

**Posizione nella dashboard**
```text
Area gestori (barra laterale)            Panoramica (azioni rapide del titolare)
  Panoramica                               [Personalizza il sito] [Menu e prezzi] [Foto] …
  Menu e prezzi                            [Account e accessi]  ← solo titolare
  Eventi
  Richieste
  Foto
  Comunicazioni
  ── solo titolare ──
  Personalizza il sito
  Orari e contatti
  Account e accessi        ← nuova voce, icona «persone», ultima del gruppo del titolare
  ────────────────
  Vedi il sito · La mia password · Esci   ← «La mia password» per tutti (anche lo staff)
```

**Pagina `#/gestione/account`** (stesso stile delle tabelle di «Menu e prezzi»):
- elenco: nome, email, ruolo (Titolare/Staff), stato (attivo/disattivato/bloccato fino a…), ultimo accesso;
- «Nuovo account»: nome, email, ruolo, password iniziale (con le stesse regole e il generatore di una frase casuale da
  comunicare a voce; nessuna email automatica in V1);
- azioni per riga: cambia ruolo, disattiva/riattiva, reimposta password (sblocca anche l'account). Ogni cambio chiude
  le sessioni di quell'account (già così nel backend) e la pagina lo dice;
- protezioni già presenti nel backend: ultimo titolare attivo non rimovibile (`409 LAST_OWNER`), staff `403`, CSRF, audit.

**Per lo staff**: nessuna voce «Account»; solo «La mia password» (cambio della propria password).

Stima: una pagina e un piccolo dialogo, ~1 giornata con test Playwright. Se approvata la implemento sul branch della
fase indicata.

## 8. Prossima fase (non avviata)
FASE 5 — Prenotazioni V1, da avviare solo dopo l'approvazione di questa fase.
