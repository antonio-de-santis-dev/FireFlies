# FireFlies — frontend

React 18 + TypeScript + Vite 5, dal mockup approvato (grafica invariata). CSS con token, icone Lucide, font self-hosted.
Dalla FASE 3 l'**accesso all'area gestori è reale** (backend Spring Boot, sessione server-side); i contenuti dell'area gestori
sono ancora dimostrativi e in memoria fino alla FASE 4.

## Avvio

Requisiti: Node.js 18+ (testato con Node 22). Per l'area gestori serve il backend (vedi `docs/operations/LOCAL_SETUP.md`).

```bash
npm ci
npm run dev            # http://localhost:5173 — /api e /actuator inoltrati al backend (FIREFLIES_BACKEND_URL, default http://localhost:8080)
```

| Comando | Cosa fa |
|---|---|
| `npm run build` | build di produzione in `dist/` |
| `npm run preview` | serve `dist/` su http://localhost:4173 (stesso proxy verso il backend) |
| `npm run build:static` | `dist-static/index.html` apribile con doppio clic: **solo sito pubblico** (l'area gestori richiede il backend) |
| `npm run typecheck` | TypeScript strict |
| `npm test` | test funzionali + test di accesso, con backend e PostgreSQL **reali** (`scripts/e2e.mjs`, serve Docker e Java 21) |
| `npm run qa` | QA su 7 viewport + accessibilità axe-core, con backend reale |
| `npm run verifica` | typecheck + test + QA |
| `npm run screenshots` / `npm run demo` | screenshot e video del mockup (con backend reale) |

`scripts/e2e.mjs` avvia un PostgreSQL usa-e-getta (progetto Compose `fireflies-e2e`, porta 55432), compila e avvia il
backend (porta 18080), crea il primo titolare con la procedura amministrativa e uno staff via API, con **password casuali
a ogni esecuzione**, poi lancia Playwright e alla fine cancella tutto.

## Struttura

```
src/
  main.tsx, App.tsx        rotte (hash router); area gestori: verifica della sessione col backend a ogni pagina
  api/                     client HTTP (cookie di sessione, CSRF, errori problem+json) e API di accesso
  router.tsx               router minimale, titolo pagina e focus sull'h1 a ogni cambio vista
  store.tsx                stato: ruolo e utente dal backend; menu, eventi, richieste, contenuti, foto, avvisi ancora in memoria
  data/                    contenuti demo (assets, site, menu, events, requests, content)
  components/              layout pubblico, dialog accessibile, prenotazione a passi, mappa, titolari, lucciole
  pages/public/            Home, Menu, Eventi, Galleria, Il locale, Contatti
  pages/admin/             Login, Panoramica, Menu, Eventi, Richieste, Foto, Comunicazioni, Personalizza, Impostazioni
  styles/                  tokens.css, base.css, public.css, admin.css
public/assets/fireflies/   foto e logo ottimizzati (WebP/PNG) + manifest.json
tests/                     navigation, auth, qa, a11y, screenshots, demo (Playwright) + frames.mjs, overview.mjs (sharp)
scripts/e2e.mjs            avvia database e backend reali per i test
```

Documentazione: [`../docs`](../docs).
