import '@fontsource-variable/bricolage-grotesque/index.css';
import '@fontsource-variable/manrope/index.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { StoreProvider } from './store';
import './styles/tokens.css';
import './styles/base.css';
import './styles/public.css';
import './styles/admin.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <StoreProvider>
      <App />
    </StoreProvider>
  </StrictMode>,
);
