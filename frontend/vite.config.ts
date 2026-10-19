import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';

// `npm run build`        → dist/ (da servire con `npm run preview` o un web server)
// `npm run build:static` → dist-static/index.html con JS/CSS/font inline: apribile con doppio clic (file://)
export default defineConfig(({ mode }) => ({
  base: './',
  plugins: mode === 'static' ? [react(), viteSingleFile()] : [react()],
  build: {
    outDir: mode === 'static' ? 'dist-static' : 'dist',
    assetsInlineLimit: mode === 'static' ? 100_000_000 : 4096,
  },
  server: { port: 5173 },
  preview: { port: 4173 },
}));
