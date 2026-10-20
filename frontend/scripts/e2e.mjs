#!/usr/bin/env node
// Test end-to-end con il backend VERO: PostgreSQL 17 (Docker Compose, progetto separato «fireflies-e2e»),
// backend Spring Boot, primo titolare creato con la procedura amministrativa, uno staff creato dal titolare via API.
// Le password sono casuali a ogni esecuzione: nessuna credenziale è scritta nel codice.
// Uso: node scripts/e2e.mjs [argomenti per playwright test]
import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createWriteStream, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = resolve(frontend, '..');
const backendDir = join(root, 'backend');
const secret = () => randomBytes(18).toString('base64url');

const BACKEND_PORT = process.env.E2E_BACKEND_PORT ?? '18080';
// Foto caricate durante i test: cartella temporanea, cancellata alla fine come il database.
const mediaDir = mkdtempSync(join(tmpdir(), 'fireflies-e2e-media-'));
const DB_PORT = process.env.E2E_DB_PORT ?? '55432';
const env = {
  ...process.env,
  FIREFLIES_DB_NAME: 'fireflies_e2e',
  FIREFLIES_DB_USER: 'fireflies',
  FIREFLIES_DB_PASSWORD: secret(),
  FIREFLIES_DB_PORT: DB_PORT,
  FIREFLIES_DB_URL: `jdbc:postgresql://localhost:${DB_PORT}/fireflies_e2e`,
  FIREFLIES_BACKEND_URL: `http://localhost:${BACKEND_PORT}`,
  // contenuti dimostrativi del mockup caricati dal backend (profilo local) con le foto reali del locale
  FIREFLIES_MEDIA_DIR: mediaDir,
  FIREFLIES_DEMO_SEED: 'true',
  FIREFLIES_DEMO_ASSETS_DIR: join(frontend, 'public', 'assets', 'fireflies'),
  E2E_OWNER_EMAIL: 'titolare.e2e@example.invalid',
  E2E_OWNER_PASSWORD: secret(),
  E2E_STAFF_EMAIL: 'staff.e2e@example.invalid',
  E2E_STAFF_PASSWORD: secret(),
};

const compose = (...args) =>
  execFileSync('docker', ['compose', '-p', 'fireflies-e2e', '-f', join(root, 'infra', 'compose.yaml'), ...args], { env, stdio: 'inherit' });

let backend;
function cleanup() {
  if (backend && backend.exitCode === null) backend.kill('SIGTERM');
  try {
    compose('down', '-v');
  } catch {
    /* già fermo */
  }
  rmSync(mediaDir, { recursive: true, force: true });
}
process.on('SIGINT', () => {
  cleanup();
  process.exit(130);
});

async function waitForHealth(url, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try {
      if ((await fetch(url)).ok) return;
    } catch {
      /* non ancora pronto */
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`Backend non pronto su ${url}`);
}

/** Piccolo «browser»: cookie di sessione e token CSRF come nel frontend. */
async function apiSession(base) {
  const jar = new Map();
  const call = async (method, path, body) => {
    if (method !== 'GET' && !jar.has('XSRF-TOKEN')) await call('GET', '/api/v1/auth/csrf');
    const headers = { Accept: 'application/json', Cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') };
    if (body) headers['Content-Type'] = 'application/json';
    if (method !== 'GET') headers['X-XSRF-TOKEN'] = decodeURIComponent(jar.get('XSRF-TOKEN') ?? '');
    const res = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
    for (const c of res.headers.getSetCookie()) {
      const [pair] = c.split(';');
      const [name, ...value] = pair.split('=');
      if (value.join('=') === '') jar.delete(name);
      else jar.set(name, value.join('='));
    }
    if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${await res.text()}`);
    return res;
  };
  return call;
}

function run(cmd, args, options) {
  return new Promise((done) => spawn(cmd, args, { stdio: 'inherit', ...options }).on('exit', (code) => done(code ?? 1)));
}

try {
  console.log('▶ PostgreSQL e2e (porta ' + DB_PORT + ')');
  compose('up', '-d', '--wait');

  console.log('▶ Build del backend');
  execFileSync(join(backendDir, 'mvnw'), ['-q', '-B', '-DskipTests', 'package'], { cwd: backendDir, env, stdio: 'inherit' });
  const jar = join(backendDir, 'target', 'fireflies-backend-0.1.0-SNAPSHOT.jar');

  console.log('▶ Procedura amministrativa: primo titolare');
  execFileSync('java', ['-jar', jar, '--spring.main.web-application-type=none', '--fireflies.bootstrap.create-owner=true',
    `--fireflies.bootstrap.email=${env.E2E_OWNER_EMAIL}`, '--fireflies.bootstrap.display-name=Titolare E2E'],
  { env: { ...env, FIREFLIES_BOOTSTRAP_PASSWORD: env.E2E_OWNER_PASSWORD }, stdio: 'inherit' });

  console.log('▶ Backend su porta ' + BACKEND_PORT);
  // Fuori da test-results/, che Playwright svuota all'avvio.
  mkdirSync(join(frontend, 'e2e-logs'), { recursive: true });
  const log = createWriteStream(join(frontend, 'e2e-logs', 'backend.log'));
  // tutte le richieste dei test arrivano da 127.0.0.1: il limite per indirizzo (5 all'ora) è provato dai test del backend
  backend = spawn('java', ['-jar', jar, `--server.port=${BACKEND_PORT}`, '--fireflies.reservations.web-requests-per-hour=1000'], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  backend.stdout.pipe(log);
  backend.stderr.pipe(log);
  // «readiness» diventa UP solo dopo il caricamento dei contenuti demo (ApplicationRunner)
  await waitForHealth(`${env.FIREFLIES_BACKEND_URL}/actuator/health/readiness`, 180_000);

  console.log('▶ Il titolare crea lo staff dall\'API');
  const owner = await apiSession(env.FIREFLIES_BACKEND_URL);
  await owner('POST', '/api/v1/auth/login', { email: env.E2E_OWNER_EMAIL, password: env.E2E_OWNER_PASSWORD });
  await owner('GET', '/api/v1/auth/csrf');
  await owner('POST', '/api/v1/admin/users', { email: env.E2E_STAFF_EMAIL, displayName: 'Staff E2E', role: 'STAFF', password: env.E2E_STAFF_PASSWORD });

  console.log('▶ Playwright');
  process.exitCode = await run('npx', ['playwright', 'test', ...process.argv.slice(2)], { cwd: frontend, env });
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  cleanup();
}
