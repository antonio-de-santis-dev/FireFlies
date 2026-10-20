# FASE 7 — Report tecnico (in corso)

> Branch `fase-7/notifiche`, creato da `fase-6/eventi-capienza` (`6ad7e0e`). 20/10/2026. Nessun merge su `main`.
> **Stato: parziale.** Completato il requisito 15 (integrazione del committente). I requisiti principali della fase
> (1–14) non sono ancora stati ricevuti in questa sessione: questo report verrà completato con quelli.

## Requisito 15 — Eventi: locandine sempre intere

### Problema
Tutte le immagini degli eventi usavano `object-fit: cover` dentro riquadri a proporzioni o altezze fisse (4:3, 16:10,
260 px minimi), più uno zoom al passaggio del mouse in contenitori con `overflow: hidden`: le locandine verticali
perdevano parte alta e bassa (date, orari, sponsor, numeri).

### Correzione (solo CSS e contenitori, stessa impaginazione)
| Dove | Prima | Ora |
|---|---|---|
| Elenco eventi (`#/eventi`), riga | `cover`, altezza 100% / min 260 px, 16:10 su smartphone | riquadro su fondo scuro, immagine `contain`, altezza automatica (massimo `min(70vh, 560px)` su desktop), intera su smartphone |
| Locandina in evidenza (Domeniche del Fire) | `cover`, 4:3 su smartphone | `contain`, altezza automatica (massimo `min(75vh, 620px)`) |
| Scheda evento (`#/eventi/:slug`) | `cover` 4:3 | `contain`; desktop e tablet: tutta nello schermo (affiancata alle informazioni da 900 px, sopra sotto i 900); smartphone (≤ 640 px): intera a tutta larghezza, poi le informazioni |
| Altre foto della scheda | quadrate `cover` | quadrate con l'immagine intera (`contain`), colonne vincolate |
| Area gestori: elenco eventi, anteprima nell'editor, foto della libreria nell'editor eventi | `cover` | `contain` su fondo scuro (`.editor__preview--poster`, `PhotoPicker contain`); le altre pagine (menu) non cambiano |
| Zoom al passaggio del mouse | `transform: scale(…)` (tagliava i bordi) | leggera luminosità, nessun ritaglio |

Il fondo dei riquadri è il token `--poster-bg` (= `--ff-ink-950`, il nero del tema). Nessuna altezza fissa, nessuna
deformazione. Il titolo della scheda va a capo se contiene parole lunghissime (prima allargava la pagina a 1366 px).

**Varianti del backend**: verificate, non ritagliano. `ImageProcessor` riduce solo in larghezza (480/960/1600 px) con
l'altezza proporzionale; nuovo test `locandineMaiRitagliateNelleVarianti` (1080×1920, 1240×1754, 1200×1200,
1920×1080). L'originale non viene modificato oltre alla ricodifica già prevista dalla FASE 4.

### Test di regressione (`frontend/tests/posters.spec.ts`)
- Genera tre locandine di prova (verticale 2:3, quadrata, orizzontale 16:9) con quattro bande colorate sui bordi e testo
  in alto (data, orario, telefono finti), le carica dalla libreria e pubblica una serata per ciascuna.
- Su **1920, 1440, 1366, 768, 390, 375, 360 px**, in elenco e in scheda: `object-fit` mai `cover`/`fill`, proporzioni
  mostrate = originali (±2%), nessun contenitore che tagli l'area disegnata, nessuno scorrimento orizzontale; screenshot
  Playwright dell'area disegnata (allegati al report di Playwright) con il controllo dei pixel: **le quattro bande dei
  bordi devono essere tutte visibili**. Scheda: desktop affiancata e alta al massimo lo schermo, tablet sopra le
  informazioni e nello schermo, smartphone a tutta larghezza.
- Miniature delle altre foto (Domeniche del Fire) su tutte le larghezze; area gestori (elenco, anteprima, libreria).
- Ogni immagine messa in `frontend/tests/fixtures/locandine/` viene verificata allo stesso modo: **la locandina
  verticale di riferimento indicata dal committente non è arrivata in questa sessione**; appena disponibile va copiata lì.

### Esito
`posters.spec` 9 test ✅, `qa.spec` e `a11y.spec` ✅ (nessuna regressione), `ImageProcessorTest` 17 ✅.

## Requisiti 1–14
Da ricevere. Vincoli già noti e rispettati: nessun invio reale di email o SMS senza dati veri e autorizzazione.
