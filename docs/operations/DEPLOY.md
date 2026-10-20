# DEPLOY — proposta di infrastruttura e procedura di messa in produzione

> FASE 8, 20/10/2026. **Nulla è stato acquistato, creato o pubblicato.** Questo documento è la proposta da approvare:
> ogni passo che costa denaro o rende il sito pubblico richiede l'autorizzazione del titolare (sezione 9).
> Notifiche reali email/SMS **spente** (`FIREFLIES_NOTIFICATIONS_WORKER_ENABLED=false`, V-05).

## 1. Proposta per un piccolo locale
Un solo server virtuale in UE con Docker Compose: è sufficiente per il traffico di un locale (decine di richieste di
prenotazione al giorno, pochi gestori collegati), costa poco e si mantiene con pochi comandi. Niente Kubernetes,
Redis o microservizi (MASTER_PLAN, C-29): il rate limiting in memoria è corretto proprio perché c'è **una sola istanza**
del backend (V-01).

```
Internet ──443/80──► web (Caddy: HTTPS automatico, file statici, CSP)
                         │ rete interna Docker (nessuna altra porta pubblica)
                         ├──► backend (Spring Boot, utente non root) ──► postgres (volume pgdata)
                         │                                  └──────────► R2 (foto: originali e varianti)
cron del server: backup.sh (05:30) · check-health.sh (07:00) · copia fuori sede dei backup
```

| Componente | Scelta | Motivo |
|---|---|---|
| Server | VPS 2 vCPU, 4 GB RAM, 40 GB SSD, datacenter UE (es. Hetzner CX22/CX32, OVHcloud VPS, Aruba Cloud) | limiti del compose: PostgreSQL 512 MB + backend 768 MB + Caddy 128 MB ≈ 1,4 GB, il resto per sistema e cache del disco; 2 GB sarebbero al limite |
| Sistema | Debian 12 o Ubuntu 24.04 LTS, aggiornamenti automatici di sicurezza (`unattended-upgrades`) | supporto lungo |
| Reverse proxy / HTTPS | Caddy 2 (immagine `frontend/Dockerfile`) | certificati Let's Encrypt automatici, HSTS, CSP, `/api` → backend, `/actuator/*` chiuso tranne `health` |
| Database | PostgreSQL 17 nello stesso compose, volume locale, nessuna porta esposta | un database gestito costerebbe 5–10 volte tanto senza benefici a questo volume |
| Foto | Cloudflare R2 (S3 compatibile), bucket privato in scrittura, lettura pubblica tramite dominio dei media | nessun costo di traffico in uscita, piano gratuito sufficiente (§5) |
| DNS | quello del registrar, o Cloudflare (gratuito) | record A/AAAA del dominio verso il server, `media.` verso R2 |

Alternativa più semplice da gestire ma più cara: PaaS (Render, Railway, Fly.io) con database gestito, circa 25–40 €/mese
e con l'IP del client da ricontrollare (V-01 dipende dal proxy della piattaforma). Non consigliata per questo volume.

## 2. Immagini e configurazione (già pronte, verificate in CI senza push)
- `backend/Dockerfile`: build con JDK 21, esecuzione su JRE 21 Alpine, utente `fireflies` non root, healthcheck su
  `/actuator/health/readiness`, `-XX:MaxRAMPercentage=70` (la JVM rispetta il limite di 768 MB).
- `frontend/Dockerfile`: build Vite, poi Caddy con `frontend/Caddyfile` e i soli file di `dist/`.
- `.dockerignore` in entrambi: nessun `.env`, `target/`, `node_modules/`, credenziali locali o dati di test nelle immagini;
  il workflow `images` lo controlla a ogni push (cerca file `.env*` nelle immagini, valida il Caddyfile, verifica l'utente).
- `infra/compose.prod.yaml`: progetto `fireflies-prod`, rete `internal`, solo `web` pubblica 80/443, limiti di memoria e
  CPU, `restart: unless-stopped`, dati demo spenti, worker delle notifiche spento, pulizia notturna accesa.

## 3. Variabili d'ambiente e segreti
File `infra/.env.prod` sul server (copiato da `infra/.env.prod.example`), proprietario root, `chmod 600`, **mai nel
repository** (`.gitignore` esclude `.env.*` tranne gli esempi). Il compose rifiuta di partire se una variabile manca
(`${VAR:?}`).

| Variabile | Contenuto | Dove nasce |
|---|---|---|
| `FIREFLIES_DOMAIN` | dominio del sito | registrar |
| `FIREFLIES_ACME_EMAIL` | email per gli avvisi di Let's Encrypt | titolare |
| `FIREFLIES_DB_USER`, `FIREFLIES_DB_PASSWORD` | credenziali di PostgreSQL | generata sul server: `openssl rand -base64 32` |
| `FIREFLIES_S3_ENDPOINT`, `FIREFLIES_S3_BUCKET` | endpoint dell'account R2, nome del bucket | pannello R2 |
| `FIREFLIES_S3_ACCESS_KEY_ID`, `FIREFLIES_S3_SECRET_ACCESS_KEY` | token R2 limitato **al solo bucket**, permesso lettura/scrittura oggetti | pannello R2 |
| `FIREFLIES_MEDIA_ORIGIN` | origine pubblica delle foto (es. `https://media.dominio`), entra anche nella CSP | DNS/R2 |
| `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES` | facoltativa: restringe i proxy fidati alla sola subnet della rete Docker | `docker network inspect` |

La password del primo titolare **non** va in nessun file: si crea una volta con il comando interattivo di bootstrap
(§7, passo 6), che la chiede da terminale.

## 4. Migrazioni
Flyway parte con il backend e applica V1…V11 su un database vuoto; su un database esistente applica solo le nuove e
**rifiuta di partire** se una migrazione già applicata è stata modificata (checksum). Regole:
- mai modificare una migrazione rilasciata, solo nuove `V12__…`;
- **backup subito prima** di ogni aggiornamento che contiene migrazioni (§7, «Aggiornamenti»);
- in caso di errore il backend non parte e il sito resta in manutenzione: si ripristina il backup e la versione precedente.

## 5. Costi indicativi (prezzi pubblici di ottobre 2026, IVA esclusa, **da riverificare all'acquisto**)
| Voce | Costo | Note |
|---|---|---|
| VPS 2 vCPU / 4 GB in UE | 4–8 €/mese | Hetzner tra i più economici; OVH e Aruba simili, Aruba con datacenter in Italia |
| Dominio `.it` o `.com` | 10–20 €/anno | se il locale non ne ha già uno |
| Cloudflare R2 | 0 € con meno di 10 GB e entro le operazioni gratuite | stima: ~400 foto × 4 varianti ≈ 1–2 GB |
| Copia fuori sede dei backup | 0–4 €/mese | secondo bucket R2 (gratuito entro 10 GB in totale) oppure uno storage box |
| Snapshot del server (facoltativo) | ~20 % del costo del VPS | utile prima degli aggiornamenti importanti |
| Certificati HTTPS, Caddy, monitor esterno di base | 0 € | Let's Encrypt; monitor gratuiti (es. UptimeRobot, Healthchecks.io) |
| Email/SMS | **non attivi** | da preventivare con il fornitore scelto (V-05, Q-07) |
| **Totale** | **circa 6–15 €/mese** | più il dominio |

## 6. Backup, ripristino e monitoraggio
**Backup** (`infra/scripts/backup.sh`, provato con un ripristino completo isolato: `evidence/RESTORE_DRILL.md`):
- cron giornaliero alle 05:30 Europe/Rome (`CRON_TZ=Europe/Rome`), dopo l'ultimo tentativo della pulizia notturna
  (che prova alle 02, 03, 04 e 05):
  `infra/scripts/backup.sh fireflies-prod-postgres-1 /var/backups/fireflies`
- conservazione **30 giorni** (coerente con la cancellazione delle prenotazioni dopo un mese, V-04):
  `find /var/backups/fireflies -mindepth 1 -maxdepth 1 -mtime +30 -exec rm -rf {} +`
- copia fuori sede **cifrata** (i dump contengono nomi e telefoni): es. `age -r <chiave pubblica>` sul `db.dump`, poi
  `rclone copy` verso un bucket diverso da quello delle foto; la chiave privata la tiene il titolare offline.
- foto su R2: `rclone sync` giornaliero del bucket dei media verso il bucket dei backup (sono le stesse foto pubbliche,
  nessun dato personale).

**Ripristino** (`infra/scripts/restore.sh`): verifica le impronte, rifiuta un database non vuoto, `pg_restore
--exit-on-error`, poi si avvia il backend (Flyway valida lo schema). Prova consigliata ogni 3 mesi con
`infra/scripts/restore-drill.sh` su una macchina di prova, mai sul database in uso.

**Monitoraggio** (senza servizi a pagamento):
- monitor esterno gratuito su `https://<dominio>/actuator/health` ogni 5 minuti, avviso email al titolare;
- `infra/scripts/check-health.sh https://<dominio> fireflies-prod-postgres-1 /var/backups/fireflies` da cron alle 07:00:
  esce con errore se il backend non risponde, se la pulizia notturna ha esecuzioni `FAILED` o non è terminata nelle
  ultime 36 ore (`maintenance_runs`, V-06), se l'ultimo backup ha più di 26 ore o il manifest non torna. L'esito si
  inoltra a un monitor «heartbeat» gratuito (es. `check-health.sh … && curl -fsS <url-del-ping>`);
- log: `docker compose logs` con rotazione di Docker (`max-size` 10 MB, `max-file` 5 in `/etc/docker/daemon.json`);
  i log non contengono dati dei clienti (audit FASE 8, FASE8_REPORT §3);
- spazio disco: avviso sotto il 20 % libero (lo segnala anche il monitor del provider, di solito gratuito).

## 7. Procedura di messa in produzione (solo dopo l'autorizzazione)
1. Acquisto VPS e dominio (autorizzazione dei costi), accesso SSH solo con chiave, firewall: 22 (solo IP dei gestori se
   possibile), 80, 443.
2. Installazione di Docker Engine e del plugin Compose; rotazione dei log; `unattended-upgrades`.
3. R2: bucket dei media e bucket dei backup, token limitato al bucket, dominio `media.<dominio>`.
4. `git clone` del tag approvato; `infra/.env.prod` dai valori reali, `chmod 600`.
5. DNS del dominio verso il server; `docker compose -f infra/compose.prod.yaml --env-file infra/.env.prod up -d --build`;
   Caddy ottiene il certificato.
6. Creazione del primo titolare (una sola volta, interattiva, password mai in un file):
   `docker compose … run --rm -it backend --fireflies.bootstrap.create-owner=true --fireflies.bootstrap.email=… --fireflies.bootstrap.display-name="…"`
7. Prima dell'apertura (V-03): il titolare inserisce capienza per giorno e fascia, orari, chiusure, contatti
   (**numero di telefono del locale**, usato da «Chiama ora»), menu, foto ed eventi reali; nessun dato demo.
8. `index.html`: togliere `noindex` e la dicitura di prototipo, titolo e descrizione definitivi (PRE_DEPLOY).
9. Collaudo sul dominio reale: prenotazione di prova cancellata subito, accesso dei gestori, foto, intestazioni
   (`curl -I`), `check-health.sh`, primo backup e ripristino di prova.
10. Solo allora: comunicazione del sito ai clienti.

**Aggiornamenti**: `backup.sh` → `git pull` del nuovo tag → `docker compose … up -d --build` → controllo di
`/actuator/health` e dei log. Ritorno indietro: tag precedente e, se c'erano migrazioni, `restore.sh` dell'ultimo backup.

## 8. Cosa NON è previsto
Nessuna replica del database, nessun bilanciatore, nessuna seconda istanza del backend (il rate limiting in memoria
presuppone un'istanza sola, V-01). Se un giorno servisse, i contatori andranno su PostgreSQL prima di scalare.

## 9. Decisioni richieste al titolare
| # | Decisione | Proposta |
|---|---|---|
| D-1 | Provider del server | Hetzner (prezzo) o Aruba Cloud (datacenter in Italia) |
| D-2 | Dominio | nome e registrar; se esiste già, accesso al DNS |
| D-3 | Account Cloudflare R2 | intestato al locale, con budget e avviso di spesa |
| D-4 | Chi tiene la chiave di cifratura dei backup | il titolare, su chiavetta/password manager |
| D-5 | Provider email/SMS | rinviato: notifiche spente fino alla scelta (V-05) |
| D-6 | Testo dell'informativa privacy | da validare con il consulente (V-04) |
