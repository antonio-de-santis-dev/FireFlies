// Client HTTP verso il backend (/api/v1, stessa origine: in sviluppo tramite il proxy di Vite).
// Sessione in cookie HttpOnly gestito dal browser; token CSRF letto dal cookie XSRF-TOKEN e rimandato nell'header.

const BASE = '/api/v1';
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

export type Problem = {
  status: number;
  code: string;
  title?: string;
  detail?: string;
  errors?: { field: string; code: string; message: string }[];
};

/** Errore dell'API con il codice stabile del backend (API_CONTRACT §1). status 0 = server non raggiungibile. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly problem?: Problem,
  ) {
    super(message);
  }
}

function readCookie(name: string): string | undefined {
  const entry = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`));
  return entry ? decodeURIComponent(entry.slice(name.length + 1)) : undefined;
}

async function ensureCsrf(force = false) {
  if (!force && readCookie(CSRF_COOKIE)) return;
  await fetch(`${BASE}/auth/csrf`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
}

/**
 * Chiamato quando un'API dell'area gestori risponde 401 (sessione scaduta o chiusa, anche in un'altra scheda):
 * lo store riporta alla pagina di accesso con un avviso invece di lasciare la pagina in uno stato incoerente.
 */
let unauthorizedHandler: ((code: string) => void) | null = null;
export function onUnauthorized(handler: ((code: string) => void) | null) {
  unauthorizedHandler = handler;
}

async function request<T>(method: string, path: string, body?: unknown, retryCsrf = true, extra: Record<string, string> = {}): Promise<T> {
  const write = method !== 'GET';
  const form = body instanceof FormData;
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json', ...extra };
  if (body !== undefined && !form) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    if (write) {
      await ensureCsrf();
      headers[CSRF_HEADER] = readCookie(CSRF_COOKIE) ?? '';
    }
    response = await fetch(`${BASE}${path}`, {
      method,
      credentials: 'same-origin',
      // letture pubbliche: sempre rivalidate con l'ETag (304 senza corpo se nulla è cambiato), così una modifica
      // dei gestori si vede al primo ricaricamento anche se la risposta precedente era in cache per 60 s
      cache: path.startsWith('/public/') ? 'no-cache' : 'default',
      headers,
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Il server non risponde. Controlla la connessione e riprova.');
  }

  if (response.status === 204) return undefined as T;
  const isJson = (response.headers.get('content-type') ?? '').includes('json');
  const data = isJson ? await response.json() : null;

  if (!response.ok) {
    const problem = data as Problem | null;
    // Token CSRF ruotato (es. dopo un nuovo login in un'altra scheda): se ne chiede uno nuovo e si riprova una volta.
    if (response.status === 403 && problem?.code === 'CSRF_INVALID' && retryCsrf) {
      await ensureCsrf(true);
      return request<T>(method, path, body, false, extra);
    }
    if (response.status === 401 && path.startsWith('/admin/')) unauthorizedHandler?.(problem?.code ?? 'UNAUTHENTICATED');
    throw new ApiError(
      response.status,
      problem?.code ?? `HTTP_${response.status}`,
      problem?.detail ?? 'Si è verificato un errore imprevisto. Riprova tra poco.',
      problem ?? undefined,
    );
  }
  return data as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>('POST', path, body ?? {}, true, headers),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
  del: (path: string) => request<void>('DELETE', path),
  /** multipart/form-data (foto): il browser imposta da solo il boundary. */
  upload: <T>(method: 'POST' | 'PUT', path: string, form: FormData) => request<T>(method, path, form),
};

/** Campo indicato dal backend per un errore di validazione (`context.field`), per evidenziarlo nel modulo. */
export const problemField = (error: unknown) =>
  error instanceof ApiError ? ((error.problem as { context?: { field?: string } } | undefined)?.context?.field ?? null) : null;
