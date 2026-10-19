import { useEffect, useState } from 'react';

/** Simula un breve caricamento (dati «dal server») per mostrare lo stato skeleton. Solo al primo ingresso nella vista. */
const seen = new Set<string>();
export function useDemoLoading(key = 'menu', ms = 450) {
  const [loading, setLoading] = useState(!seen.has(key));
  useEffect(() => {
    if (!loading) return;
    const t = window.setTimeout(() => {
      seen.add(key);
      setLoading(false);
    }, ms);
    return () => window.clearTimeout(t);
  }, [key, ms, loading]);
  return loading;
}
