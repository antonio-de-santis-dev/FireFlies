// Router minimale basato su hash: funziona con `npm run dev`, con la build e con il file aperto da disco.
import { useEffect, useSyncExternalStore, type AnchorHTMLAttributes } from 'react';

const getPath = () => {
  const raw = window.location.hash.replace(/^#/, '') || '/';
  return raw.startsWith('/') ? raw : `/${raw}`;
};

const subscribe = (cb: () => void) => {
  window.addEventListener('hashchange', cb);
  return () => window.removeEventListener('hashchange', cb);
};

export function usePath() {
  return useSyncExternalStore(subscribe, getPath, () => '/');
}

export function navigate(to: string) {
  window.location.hash = to;
}

/** Confronta `/eventi/:id` con il percorso corrente e restituisce i parametri. */
export function match(pattern: string, path: string): Record<string, string> | null {
  const p = pattern.split('/').filter(Boolean);
  const s = path.split('?')[0].split('/').filter(Boolean);
  if (p.length !== s.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < p.length; i++) {
    if (p[i].startsWith(':')) params[p[i].slice(1)] = decodeURIComponent(s[i]);
    else if (p[i] !== s[i]) return null;
  }
  return params;
}

type LinkProps = AnchorHTMLAttributes<HTMLAnchorElement> & { to: string };

export function Link({ to, ...rest }: LinkProps) {
  const path = usePath();
  const current = path === to || (to !== '/' && to !== '/gestione' && path.startsWith(`${to}/`));
  return <a href={`#${to}`} aria-current={current ? 'page' : undefined} {...rest} />;
}

/** Dopo ogni cambio vista: titolo, scroll in alto e focus sull'h1 (annunciato dagli screen reader). */
export function usePageMeta(title: string) {
  useEffect(() => {
    document.title = `${title} · FireFlies Aperitif — Prototipo`;
    window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
    const h1 = document.querySelector<HTMLElement>('main h1');
    h1?.setAttribute('tabindex', '-1');
    h1?.focus({ preventScroll: true });
  }, [title]);
}
