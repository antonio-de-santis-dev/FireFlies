import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';

// `npm run dev` / `npm run preview` → le chiamate a /api e /actuator vanno al backend (stessa origine per il browser:
//                                     cookie di sessione e CSRF funzionano senza CORS).
// `npm run build`        → dist/ (in produzione frontend e /api stanno sullo stesso dominio)
// `npm run build:static` → dist-static/index.html apribile con doppio clic (file://): solo sito pubblico,
//                          l'area gestori richiede il backend.
const backend = process.env.FIREFLIES_BACKEND_URL ?? 'http://localhost:8080';
const proxy = {
  '/api': { target: backend },
  '/actuator': { target: backend },
};

export default defineConfig(({ mode }) => ({
  base: './',
  plugins: mode === 'static' ? [react(), viteSingleFile()] : [react()],
  build: {
    outDir: mode === 'static' ? 'dist-static' : 'dist',
    assetsInlineLimit: mode === 'static' ? 100_000_000 : 4096,
  },
  server: { port: 5173, proxy },
  preview: { port: 4173, proxy },
}));
