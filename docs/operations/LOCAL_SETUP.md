# Avvio locale — database, backend, frontend, foto e primo titolare

> Aggiornato in FASE 5 · 20/10/2026. Tutti i comandi sono stati eseguiti davvero nel container di sviluppo
> (Linux, Java 21, Node 22, Docker 29, Compose v5).

## Requisiti
| Strumento | Versione | Note |
|---|---|---|
| Java (JDK) | 21 | Maven non serve: c'è il wrapper `./mvnw` (scarica Maven 3.9.11) |
| Node.js | 18+ (testato 22) | per il frontend |
| Docker + Docker Compose | recenti | PostgreSQL locale e test di integrazione |

## 1. Database locale (PostgreSQL 17)
```bash
cd infra
cp .env.example .env          # scrivi una password a scelta in FIREFLIES_DB_PASSWORD
docker compose up -d --wait   # PostgreSQL su 127.0.0.1:5432, dati nel volume "pgdata"
```
`infra/.env` è ignorato da git. Il database è raggiungibile solo dalla macchina locale.

## 2. Backend
```bash
cd backend
set -a && . ../infra/.env && set +a    # esporta FIREFLIES_DB_* nella shell
./mvnw spring-boot:run                 # profilo "local", porta 8080
```
All'avvio Flyway applica le migrazioni (`Successfully applied 9 migrations` su un database nuovo).
Al **primo** avvio su un database vuoto il backend carica anche i **contenuti dimostrativi** del mockup
(menu con prezzi «demo», foto del locale, titolari, avvisi d'esempio): ~15 secondi, nel log
`Contenuti DEMO caricati (32 foto, 15 voci di menu)`. Vedi §8.
```bash
curl http://localhost:8080/actuator/health            # {"groups":["liveness","readiness"],"status":"UP"}
curl http://localhost:8080/actuator/health/readiness  # UP solo dopo il caricamento dei contenuti demo
curl http://localhost:8080/api/v1/public/menu         # menu pubblico (JSON)
```

### Foto in sviluppo
Le foto caricate (e quelle demo) finiscono in `backend/data/media/` (ignorata da git): il backend le verifica, le
ridimensiona in tre varianti (480, 960, 1600 px), toglie i dati EXIF/GPS e le serve su `http://localhost:8080/media/…`.
Per usare un'altra cartella: `FIREFLIES_MEDIA_DIR=/percorso`. Nel database ci sono solo metadati e chiavi, mai i byte.

## 3. Primo titolare (procedura amministrativa, una volta sola)
Il primo account OWNER non si crea dal sito né dall'API: si crea dalla macchina che ha accesso al database.
Nessuna credenziale è scritta nel codice; la procedura si rifiuta se esiste già un titolare.

```bash
cd backend
./mvnw -q -DskipTests package                    # crea target/fireflies-backend-0.1.0-SNAPSHOT.jar
set -a && . ../infra/.env && set +a
java -jar target/fireflies-backend-0.1.0-SNAPSHOT.jar \
     --spring.main.web-application-type=none \
     --fireflies.bootstrap.create-owner=true \
     --fireflies.bootstrap.email=titolare@tuodominio.it \
     --fireflies.bootstrap.display-name="Nome Cognome"
```
Il programma chiede la password due volte (non compare a schermo), crea l'account, scrive nel log
`Primo titolare creato: …` e si chiude.

- **Password**: almeno 12 caratteri (va bene una frase), al massimo 72 byte, non ripetitiva, senza il nome dell'email.
- **Senza terminale interattivo** (script, CI): imposta la variabile d'ambiente `FIREFLIES_BOOTSTRAP_PASSWORD` prima del comando.
  **Non** passare la password come argomento (`--fireflies.bootstrap.password=…`): resterebbe nella cronologia della shell, e la procedura si rifiuta.
- In produzione: stesso comando con `--spring.profiles.active=prod` e le variabili `FIREFLIES_DB_*` del server.
- Gli altri account (staff o altri titolari) li crea il titolare dall'API `POST /api/v1/admin/users`; la schermata «Account» è proposta in FASE4_REPORT §7 (da approvare).

## 4. Frontend
```bash
cd frontend
npm ci
npm run dev        # http://localhost:5173 — /api, /actuator e /media inoltrati al backend su :8080
```
Area gestori: `http://localhost:5173/#/gestione/login` (non è collegata dal sito pubblico). Accedi con l'account del passo 3.
Per un backend su un'altra porta: `FIREFLIES_BACKEND_URL=http://localhost:9090 npm run dev`.

Il sito pubblico legge menu, testi, foto, orari e avvisi dal backend: se il backend è spento compare «Il sito non
riesce a caricare i contenuti» con il pulsante **Riprova**.

`npm run build:static` produce una pagina apribile con doppio clic: contiene solo il sito pubblico **con i dati
dimostrativi del mockup** (nessuna chiamata al backend); l'area gestori ha bisogno del backend. La build normale
(`npm run build`) non contiene i dati demo.

## 5. Test
```bash
cd backend
./mvnw test        # 70 test unitari e di slice, senza Docker (foto, storage S3 simulato, configurazione, regole prenotazioni)
./mvnw verify      # + 265 test di integrazione, sicurezza, concorrenza e riavvio su PostgreSQL 17 reale (Testcontainers)

cd ../frontend
npm run verifica   # typecheck + 66 test Playwright (funzionali, accesso, contenuti, prenotazioni, QA 7 viewport, axe) con backend e DB reali
```
`npm run verifica` usa `scripts/e2e.mjs`: avvia un PostgreSQL separato (progetto Compose `fireflies-e2e`, porta 55432),
compila e avvia il backend sulla porta 18080 con i contenuti demo e una cartella foto temporanea, crea il titolare con
la procedura del passo 3 e uno staff via API con password **casuali generate a ogni esecuzione**, esegue Playwright e
alla fine cancella database, volume e foto. Non tocca il database di sviluppo del passo 1.
I test che modificano menu, foto o testi riportano i dati com'erano alla fine (`tests/baseline.ts`); le prenotazioni
create dai test usano nomi, giorni e numeri di prova sempre diversi. Il backend e2e alza il limite per IP delle richieste
dal sito (`--fireflies.reservations.web-requests-per-hour=1000`) perché tutte arrivano da 127.0.0.1: il limite reale
(5 all'ora) è verificato dai test del backend.

## 6. Profili
| Profilo | Uso | Database | Cookie di sessione | Notifiche | Foto | Dati demo |
|---|---|---|---|---|---|---|
| `local` (predefinito) | sviluppo | `FIREFLIES_DB_URL/USER/PASSWORD` (password obbligatoria) | `FF_SESSION`, HttpOnly, SameSite=Lax, **non** Secure (HTTP su localhost) | solo finte | cartella `backend/data/media` | caricati su DB vuoto (`FIREFLIES_DEMO_SEED=false` per non caricarli) |
| `test` | test automatici | Testcontainers | come `local` | solo finte; un provider reale blocca l'avvio | cartella temporanea | mai |
| `prod` | produzione (FASE 8) | solo variabili d'ambiente | HttpOnly, SameSite=Lax, **Secure** (solo HTTPS) | solo finte finché non si approva un provider | solo S3/R2 (`FIREFLIES_S3_*`); la cartella locale blocca l'avvio | **vietati**: l'avvio si ferma se presenti |

## 7. Storage S3 / Cloudflare R2 (facoltativo in sviluppo, obbligatorio in produzione)
Nessun account cloud è stato creato in questa fase. Per provare lo storage S3 serve un bucket (R2, oppure MinIO in locale)
e le variabili d'ambiente — mai nei file del repository:
```bash
export FIREFLIES_MEDIA_STORAGE=s3
export FIREFLIES_S3_ENDPOINT=https://<id-account>.r2.cloudflarestorage.com   # MinIO: http://localhost:9000
export FIREFLIES_S3_BUCKET=<nome-bucket>
export FIREFLIES_S3_ACCESS_KEY_ID=<chiave>        # dal pannello R2 → «Manage API tokens»
export FIREFLIES_S3_SECRET_ACCESS_KEY=<segreto>
export FIREFLIES_MEDIA_PUBLIC_URL=https://<dominio-pubblico-del-bucket>
```
Se una variabile manca l'avvio si ferma con il nome della variabile da impostare. Il caricamento passa dal backend
(nessuna regola CORS da configurare sul bucket). Le varianti hanno `Cache-Control: public, max-age=31536000, immutable`.

## 8. Dati dimostrativi
- Esistono solo con il profilo `local`, solo su database vuoto: menu del mockup con **prezzi dimostrativi** (etichetta
  «demo» sul sito, colonna `demo_price_cents`, mai `price_cents`), allergeni «da dichiarare», foto del locale, avvisi d'esempio.
- Il database viene marcato con `site_settings.demo_content = true`. Con il profilo `prod` l'applicazione **non parte**
  se il segnale è presente o se si imposta `FIREFLIES_DEMO_SEED=true`.
- Per ricominciare da zero in sviluppo: `docker compose down -v` (§9) e cancellare `backend/data/media`.
- **Prenotazioni (FASE 5)**: sullo stesso database vuoto vengono impostate una zona «Locale» da 40 posti, le aree
  «Sala» (T1–T6) e «Dehors» (D1–D4), le fasce del modulo approvato (Colazione 07:30–10:30, Pomeriggio 15–17,
  Aperitivo 18–20:30, Sera 21–22) in tutti i giorni tranne il mercoledì e le richieste dal sito aperte. **Nessuna
  prenotazione finta**: per provarle si usa «Richiedi un tavolo» sul sito e poi «Richieste» nell'area gestori. Tutto si
  cambia da «Capienza e tavoli» (titolare). Le notifiche vengono solo preparate: **nessuna email o SMS parte**.
- Un database già creato con la FASE 4 non riceve la configurazione delle prenotazioni (il caricamento avviene solo su
  database vuoto): impostarla dalla pagina «Capienza e tavoli» oppure ripartire da zero (§9).
- Un database demo non va mai copiato in produzione: si parte da un database nuovo e i contenuti veri li inseriscono i
  titolari dall'area gestori.

## 9. Spegnere e ripulire
```bash
# backend e frontend: Ctrl+C nelle rispettive shell
cd infra
docker compose down           # ferma il database e conserva i dati
docker compose down -v        # ferma e CANCELLA i dati locali (anche gli account)
```

## Problemi comuni
| Sintomo | Causa e rimedio |
|---|---|
| `Imposta FIREFLIES_DB_PASSWORD in infra/.env` | manca la password in `infra/.env` |
| Il backend non si collega al DB | variabili non esportate: ripeti `set -a && . ../infra/.env && set +a` |
| `Esiste già un titolare` | la procedura serve solo per il primo account; per gli altri usa l'area gestori/API |
| `Nessun terminale interattivo` | imposta `FIREFLIES_BOOTSTRAP_PASSWORD` |
| Login: «Il server non risponde» | backend spento o su un'altra porta (vedi `FIREFLIES_BACKEND_URL`) |
| Login: «Troppi tentativi» | 5 errori in 15 minuti per quell'email (o 20 dallo stesso IP): attendi o fai reimpostare la password dal titolare |
| Porta 5432 occupata | imposta `FIREFLIES_DB_PORT` e `FIREFLIES_DB_URL` in `infra/.env` |
| «Il sito non riesce a caricare i contenuti» | backend spento o ancora in avvio (al primo avvio carica i dati demo per ~15 s): **Riprova** |
| Foto rifiutata: «non è una foto JPG, PNG o WebP» | il tipo si riconosce dal contenuto, non dal nome: le foto HEIC dell'iPhone vanno esportate in JPG |
| Foto: «Archivio foto non raggiungibile» | storage S3 irraggiungibile o credenziali errate: nulla è stato salvato, riprova |
| `Il database di produzione contiene dati DIMOSTRATIVI` | si sta avviando `prod` su un database di sviluppo: usa un database nuovo (§8) |
| `In produzione le foto vanno su uno storage S3-compatibile` | in `prod` imposta `FIREFLIES_MEDIA_STORAGE=s3` e le variabili `FIREFLIES_S3_*` (§7) |
| `429 Too Many Requests` da Docker Hub | limite di download anonimi: riprova più tardi o `docker login` |
