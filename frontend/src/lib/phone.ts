// Numero di telefono del locale (FASE 8, req. 4): solo dalle impostazioni, mai numeri di prova o scritti nel codice.

/**
 * Forma per il link `tel:` (E.164 quando possibile): solo cifre con il «+» iniziale; «00» → «+»; un numero italiano
 * senza prefisso internazionale (fisso 0…, cellulare 3…) riceve «+39». Restituisce null se non sembra un numero
 * chiamabile (meno di 6 o più di 15 cifre): in quel caso non si mostra nessun pulsante di chiamata.
 */
export function telHref(raw: string | null | undefined): string | null {
  if (!raw) return null;
  let s = raw.trim().replace(/[^\d+]/g, '');
  if (s.startsWith('00')) s = `+${s.slice(2)}`;
  if (s.includes('+', 1)) return null;
  if (!s.startsWith('+') && /^[03]/.test(s)) s = `+39${s}`;
  const digits = s.replace(/\D/g, '');
  if (digits.length < 6 || digits.length > 15) return null;
  return `tel:${s}`;
}

/** Forma leggibile: quella scritta dal titolare, con gli spazi normalizzati. */
export function displayPhone(raw: string | null | undefined): string | null {
  const s = raw?.trim().replace(/\s+/g, ' ');
  return s ? s : null;
}

/** Copia negli appunti senza chiedere permessi: Clipboard API, altrimenti false (l'interfaccia offre la selezione manuale). */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // permesso negato o contesto non sicuro: si passa alla selezione manuale
  }
  return false;
}
