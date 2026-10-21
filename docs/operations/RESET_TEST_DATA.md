# Reset dei dati di prova — prenotazioni ed eventi (solo sviluppo locale)

Script: `infra/scripts/reset-test-data/reset.sh` (con `counts.sql`, `fingerprints.sql`, `blocked-slots.sql`,
`reset.sql`, `check-api.mjs`). Serve a ripartire da zero con i **test manuali** sul PostgreSQL locale di
`infra/compose.yaml`, senza toccare account, menu, foto, contenuti e configurazione. **Mai in produzione.**

## Cosa elimina e cosa conserva
Ricavato dallo schema reale (migrazioni V1–V13, foreign key e trigger letti dal catalogo di PostgreSQL).

| Elimina (in quest'ordine, in una sola transazione) | Perché |
|---|---|
| `notification_attempts` delle notifiche di prenotazione, poi `notification_outbox` con `aggregate_type = 'RESERVATION'` | l'outbox non ha foreign key verso le prenotazioni: senza questo passo resterebbero notifiche orfane |
| `reservation_table_assignments`, `reservation_status_history` | figli delle prenotazioni (foreign key `RESTRICT`) |
| `reservations` | il trigger `trg_res_band_usage` scala il budget di ogni confermata, completata o non presentata |
| `band_capacity_usage` | dopo il passo precedente ogni riga è a zero (se non lo è, la transazione si annulla) |
| `capacity_slots` | quanti occupati e riserve degli eventi esclusivi (`fk_slot_event`); il backend li ricrea al bisogno con la capienza attuale |
| `event_media`, poi `events` | collegamenti evento-foto, poi gli eventi; **le foto (`media_assets`, `media_variants`, file) restano** |
| `reservation_stats` (salvo `--keep-stats`) | statistiche anonime ricavate da prenotazioni già pulite: sul database locale sono solo prove |

Conserva tutto il resto: `app_users` (account, password, ruoli), `spring_session`, menu (`menu_categories`,
`menu_items`, `menu_item_allergens`, `allergens`), foto (`media_assets`, `media_variants`, `media_orphans`), contenuti
(`site_content`, `site_sections`, `owners`, `staff_members`, `hero_slides`, `announcements`), impostazioni
(`site_settings`, `opening_hours`, `opening_exceptions`, `booking_exceptions`, `booking_policies`, `capacity_zones`,
`service_windows`, `dining_areas`, `dining_tables`), `notification_templates`, `audit_logs` (registro delle operazioni:
nessun dato personale dei clienti, solo riferimenti e numeri), `maintenance_runs`, `flyway_schema_history`.
Nessun `TRUNCATE`, nessun vincolo o trigger disattivato, nessun `DROP`, nessun `docker compose down -v`.
I contenuti demo non si ricaricano al riavvio: il caricamento parte solo su un database senza foto e senza menu.

## Protezioni
- Rifiuta di partire con profilo `prod` (`SPRING_PROFILES_ACTIVE` o `FIREFLIES_ENV`), con un container che non sia il
  `postgres` del progetto Compose locale **`fireflies`** (la produzione è `fireflies-prod`), con un nome che contiene
  `prod`, senza porta pubblicata o con una porta non legata a `127.0.0.1` (in produzione il database non ne ha).
- Rifiuta uno schema che non sia FIX 2 (manca la V13 riuscita) o con migrazioni fallite, e qualunque tabella non
  prevista (lo script va aggiornato prima di usarlo su uno schema nuovo).
- `--execute` rifiuta se il database ha altre connessioni aperte (backend avviato): niente scritture a metà reset.
- Backup completo prima di cancellare, verificato con un **ripristino di prova** in un database temporaneo dello stesso
  container (conteggi e impronte identici), poi rimosso; foto archiviate; impronte SHA-256.
- Conferma esplicita: bisogna scrivere esattamente `AZZERA I DATI DI PROVA`.
- Transazione unica con `LOCK TABLE … IN EXCLUSIVE MODE` e controllo finale: se qualcosa non torna, `ROLLBACK` di tutto.
- Dopo il reset confronta le impronte (md5 del contenuto) di 24 tabelle da conservare: devono essere identiche.
- Stampa solo numeri, stati, date e impronte: mai password, nomi, telefoni o email.

## Esecuzione
Dalla cartella del repository, branch `fix2/prenotatavolo`, con il PostgreSQL locale avviato
(`cd infra && docker compose up -d --wait`). Se in `infra/.env` hai cambiato nome del database o utente, esporta
`FIREFLIES_DB_NAME`/`FIREFLIES_DB_USER` prima di lanciare lo script.

```bash
# 1. anteprima (sola lettura): tabelle e righe che verrebbero eliminate o conservate
infra/scripts/reset-test-data/reset.sh --preview

# 2. backup completo e verificato, nessuna modifica (cartella predefinita ~/fireflies-backups/reset-<data>)
infra/scripts/reset-test-data/reset.sh --backup-only

# 3. SOLO dopo aver controllato anteprima e backup: ferma il backend (Ctrl+C su ./mvnw spring-boot:run), poi
infra/scripts/reset-test-data/reset.sh --execute        # nuovo backup verificato + conferma + reset

# 4. riavvia il backend (Flyway valida le migrazioni all'avvio) e controlla le API pubbliche
cd backend && ./mvnw spring-boot:run                     # in un altro terminale, con le variabili di LOCAL_SETUP
node infra/scripts/reset-test-data/check-api.mjs http://localhost:8080 ~/fireflies-backups/reset-<data>/blocked-slots-before.txt
```

Opzioni: `--container NOME`, `--backup-dir CARTELLA`, `--media-dir CARTELLA` (predefinita `backend/data/media`),
`--keep-stats`. Nella cartella del backup: `db.dump`, `db.list`, `media.tar.gz`, `MANIFEST.sha256`,
`counts-before.txt`, `counts-after.txt`, `fingerprints-*.txt`, `blocked-slots-before.txt`, `report.md` (prima/dopo).

Dopo il reset, a mano: accedi come titolare e come staff; Gestione › Menu e foto invariati; Impostazioni: capienza,
orari, fasce e date straordinarie come prima; Richieste ed Eventi vuoti.

## Ripristino (se serve tornare a prima del reset)
Riporta il database **esattamente** allo stato del backup (anche account, menu e impostazioni di quel momento).
Ferma il backend, poi:

```bash
B=~/fireflies-backups/reset-<data>
(cd "$B" && sha256sum -c MANIFEST.sha256)
docker exec -i fireflies-postgres-1 pg_restore -U fireflies -d fireflies --clean --if-exists --no-owner \
  --single-transaction --exit-on-error < "$B/db.dump"
```
Una sola transazione: se qualcosa fallisce il database resta com'era. Le foto non vengono toccate dal reset; se servisse,
`media.tar.gz` si estrae in `backend/data/media`.

## Verifica fatta durante la preparazione (21/10/2026)
In un PostgreSQL di laboratorio **usa e getta** nel container di sviluppo (progetto Compose `fireflies`, dati
dimostrativi e prenotazioni ed eventi di prova creati via API). Il database del titolare non è stato toccato.
- Anteprima e backup verificato: nessuna modifica; il database temporaneo di verifica viene rimosso.
- Guardie: rifiutati backend ancora collegato, container di un altro progetto e profilo `prod`; conferma sbagliata → nulla.
- Errore simulato a metà (budget incoerente): la transazione si annulla, nessuna riga eliminata.
- Reset: 7 prenotazioni, 14 righe di storico, 1 assegnazione tavolo, 4 eventi, 8 collegamenti foto, 51 quanti (31 riservati
  a eventi), 3 righe di budget, 13 notifiche, 1 riga di statistiche → tutto a 0; 24 tabelle conservate con impronte
  identiche (account, menu, 32 foto e 87 varianti, capienza 40, 7 orari, 28 fasce, regole).
- Dopo il riavvio: Flyway «Successfully validated 13 migrations»; contenuti demo non ricaricati; login titolare e staff
  `200`; nessun evento pubblico; gli orari prima riservati all'evento esclusivo (2/11 21:00–23:45) di nuovo prenotabili;
  una nuova richiesta in quell'orario si conferma (quanti ricreati con capienza 40, budget della Sera 4).
- Ripristino del backup con `pg_restore --clean --single-transaction`: conteggi e impronte identici a prima del reset.
