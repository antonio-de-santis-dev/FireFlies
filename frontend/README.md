# FireFlies — mockup interattivo

Prototipo React 18 + TypeScript + Vite 5. CSS moderno con token (nessun framework CSS), icone Lucide, font self-hosted (Bricolage Grotesque, Manrope). Nessun backend: tutti i dati sono dimostrativi e restano in memoria.

## Avvio

Requisito: Node.js 18+ (testato con Node 22).

```bash
npm install
npm run dev            # http://localhost:5173 — sul telefono: indirizzo «Network» (stessa rete Wi-Fi)
```

| Comando | Cosa fa |
|---|---|
| `npm run build` | build di produzione in `dist/` |
| `npm run preview` | serve `dist/` su http://localhost:4173 |
| `npm run build:static` | `dist-static/index.html` con JS/CSS/font inclusi, apribile con doppio clic (lasciare `assets/` accanto) |
| `npm run typecheck` | TypeScript strict |
| `npm test` | 20 test funzionali Playwright |
| `npm run qa` | QA su 7 viewport (360→1920) + audit accessibilità axe-core |
| `npm run verifica` | typecheck + test + QA |
| `npm run screenshots` | screenshot reali in `../mockup-grafici` + cornici + collage |
| `npm run demo` | video WebM in `../demo` (per gli MP4: `ffmpeg -i X.webm -c:v libx264 -pix_fmt yuv420p X.mp4`) |

I test usano la build di produzione (`vite build` + `vite preview`, avviati da Playwright) e il Chromium installato da Playwright.

## Struttura

```
src/
  main.tsx, App.tsx        rotte (hash router) e protezione area gestori per ruolo
  router.tsx               router minimale, titolo pagina e focus sull'h1 a ogni cambio vista
  store.tsx                stato in memoria: menu, eventi, richieste, impostazioni, contenuti, foto, avvisi, ruolo
  data/                    contenuti demo (assets, site, menu, events, requests, content)
  components/              layout pubblico, dialog accessibile, prenotazione a passi, mappa, titolari, lucciole
  pages/public/            Home, Menu, Eventi, Galleria, Il locale, Contatti
  pages/admin/             Login, Panoramica, Menu, Eventi, Richieste, Foto, Comunicazioni, Personalizza, Impostazioni
  styles/                  tokens.css, base.css, public.css, admin.css
public/assets/fireflies/   foto e logo ottimizzati (WebP/PNG) + manifest.json
tests/                     navigation, qa, a11y, screenshots, demo (Playwright) + frames.mjs, overview.mjs (sharp)
```

Documentazione completa in [`../documentazione`](../documentazione).
