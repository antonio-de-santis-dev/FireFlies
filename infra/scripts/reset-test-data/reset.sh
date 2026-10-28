#!/usr/bin/env bash
# Reset dei dati di PROVA di prenotazioni ed eventi nel PostgreSQL LOCALE di sviluppo (FIX 2).
# Guida completa: docs/operations/RESET_TEST_DATA.md.
#
#   infra/scripts/reset-test-data/reset.sh --preview          # sola lettura: tabelle e righe coinvolte (predefinito)
#   infra/scripts/reset-test-data/reset.sh --backup-only      # anteprima + backup completo verificato, nessuna modifica
#   infra/scripts/reset-test-data/reset.sh --execute          # anteprima + backup verificato + conferma + reset
#
# Opzioni: --container NOME (predefinito: il PostgreSQL del progetto Compose locale «fireflies»)
#          --backup-dir CARTELLA (predefinito: ~/fireflies-backups)   --media-dir CARTELLA (predefinito: backend/data/media)
#          --keep-stats (non toglie le statistiche anonime)
#
# Sicurezza: si rifiuta di partire fuori dallo sviluppo locale (profilo prod, progetto Compose diverso da «fireflies»,
# porta non legata a 127.0.0.1, schema non FIX 2); --execute si rifiuta se il backend è ancora collegato al database.
# Elimina solo prenotazioni, eventi e dati derivati; conserva account, menu, foto, contenuti, capienza, orari, fasce,
# regole e migrazioni. Nessun TRUNCATE, nessun vincolo disattivato, nessun DROP, nessun «compose down -v».
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
mode=preview
container=""
backup_root="${HOME}/fireflies-backups"
media_dir="$root/backend/data/media"
keep_stats=false
db="${FIREFLIES_DB_NAME:-fireflies}"
user="${FIREFLIES_DB_USER:-fireflies}"
phrase="AZZERA I DATI DI PROVA"

die() { echo "ERRORE: $*" >&2; exit 1; }
while [ $# -gt 0 ]; do
  case "$1" in
    --preview) mode=preview ;;
    --backup-only) mode=backup ;;
    --execute) mode=execute ;;
    --container) container="${2:?}"; shift ;;
    --backup-dir) backup_root="${2:?}"; shift ;;
    --media-dir) media_dir="${2:?}"; shift ;;
    --keep-stats) keep_stats=true ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) die "opzione sconosciuta: $1 (vedi --help)" ;;
  esac
  shift
done

# ---- 1. solo sviluppo locale ------------------------------------------------------------------------------------
case "${SPRING_PROFILES_ACTIVE:-}${FIREFLIES_ENV:-}" in *prod*) die "ambiente di produzione: reset vietato" ;; esac
command -v docker >/dev/null || die "docker non trovato"
if [ -z "$container" ]; then
  container="$(docker ps --filter label=com.docker.compose.project=fireflies --filter label=com.docker.compose.service=postgres \
    --format '{{.Names}}' | head -n1)"
  [ -n "$container" ] || die "PostgreSQL locale non avviato: cd infra && docker compose up -d --wait"
fi
project="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$container" 2>/dev/null)" \
  || die "container «$container» non trovato"
[ "$project" = "fireflies" ] || die "il container «$container» appartiene al progetto «${project:-nessuno}», non al PostgreSQL locale «fireflies»"
case "$container" in *prod*) die "container di produzione: reset vietato" ;; esac
host_ips="$(docker inspect -f '{{range $p, $b := .NetworkSettings.Ports}}{{range $b}}{{.HostIp}} {{end}}{{end}}' "$container")"
[ -n "${host_ips// /}" ] || die "nessuna porta pubblicata: non sembra il PostgreSQL di sviluppo (in produzione non ne ha)"
for ip in $host_ips; do [ "$ip" = "127.0.0.1" ] || die "porta pubblicata su $ip: il database locale deve ascoltare solo su 127.0.0.1"; done

psql_in() { docker exec -i "$container" psql -X -q -U "$user" -d "$1" -v ON_ERROR_STOP=1 "${@:2}"; }
# le query brevi non leggono lo standard input (la conferma arriva da lì)
q() { docker exec "$container" psql -X -q -U "$user" -d "$db" -v ON_ERROR_STOP=1 -At -c "$1" < /dev/null; }
[ "$(q "SELECT current_database()")" = "$db" ] || die "database «$db» non trovato"
[ "$(q "SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success")" = "1" ] \
  || die "lo schema non è quello di FIX 2 (manca la migrazione V13 riuscita): avvia prima il backend del branch fix2/prenotatavolo"
[ "$(q "SELECT count(*) FROM flyway_schema_history WHERE NOT success")" = "0" ] || die "ci sono migrazioni fallite: non si tocca nulla"

# ogni tabella dello schema deve essere classificata: se ne compare una nuova lo script si ferma invece di indovinare
known="allergens announcements app_users audit_logs band_capacity_usage booking_exceptions booking_policies capacity_slots
capacity_zones dining_areas dining_tables event_media events flyway_schema_history hero_slides maintenance_runs media_assets
media_orphans media_variants menu_categories menu_item_allergens menu_items notification_attempts notification_outbox
notification_templates opening_exceptions opening_hours owners reservation_stats whatsapp_webhook_events reservation_status_history
reservation_table_assignments reservations service_windows site_content site_sections site_settings spring_session
spring_session_attributes staff_members"
for t in $(q "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1"); do
  case " $(echo $known) " in *" $t "*) ;; *) die "tabella non prevista «$t»: aggiorna lo script prima di usarlo" ;; esac
done

stats_section=ELIMINA; $keep_stats && stats_section=CONSERVA
counts() { psql_in "$1" -v stats_section="$stats_section" < "$here/counts.sql"; }
show() { awk -F'|' '{ printf "  %-9s %-31s %-52s %8s\n", $1, $2, $3, $4 }'; }

echo "== Reset dati di prova FireFlies — modalità: $mode"
echo "   container: $container   database: $db   (progetto Compose: $project, porte: $host_ips)"
echo
echo "== Anteprima (righe per tabella; ELIMINA = verrebbero eliminate, CONSERVA = restano invariate)"
before="$(counts "$db")"
echo "$before" | show
echo
echo "   Foto: restano tutte (media_assets e i file); si tolgono solo i collegamenti evento-foto (event_media)."
echo "   Registro delle operazioni (audit_logs): resta come traccia, senza dati personali dei clienti."
blocked="$(psql_in "$db" < "$here/blocked-slots.sql")"
if [ -n "$blocked" ]; then
  echo "   Orari futuri oggi riservati a eventi esclusivi (data|dalle|ultimo quarto d'ora):"
  echo "$blocked" | sed 's/^/     /'
fi
[ "$mode" = preview ] && { echo; echo "Anteprima completata: nessuna modifica."; exit 0; }

# ---- 2. backup completo e verificato ----------------------------------------------------------------------------
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dest="$backup_root/reset-$stamp"
umask 077
mkdir -p "$dest"
echo "$before" > "$dest/counts-before.txt"
echo "$blocked" > "$dest/blocked-slots-before.txt"
psql_in "$db" < "$here/fingerprints.sql" > "$dest/fingerprints-before.txt"
echo
echo "== Backup in $dest"
docker exec "$container" pg_dump -U "$user" -d "$db" -Fc --no-owner > "$dest/db.dump"
docker exec -i "$container" pg_restore --list < "$dest/db.dump" > "$dest/db.list"
[ -s "$dest/db.list" ] || die "backup illeggibile"
# prova di ripristino in un database temporaneo separato: stessi conteggi e stesse impronte dell'originale
verify="fireflies_reset_verify_$(date +%s)"
psql_in postgres -c "CREATE DATABASE $verify" >/dev/null < /dev/null
trap 'psql_in postgres -c "DROP DATABASE IF EXISTS $verify" >/dev/null 2>&1 < /dev/null || true' EXIT
docker exec -i "$container" pg_restore -U "$user" -d "$verify" --no-owner --exit-on-error < "$dest/db.dump"
[ "$(counts "$verify")" = "$before" ] || die "il ripristino di prova non coincide con l'originale: backup non affidabile, nulla modificato"
[ "$(psql_in "$verify" < "$here/fingerprints.sql")" = "$(cat "$dest/fingerprints-before.txt")" ] \
  || die "impronte del ripristino di prova diverse: backup non affidabile, nulla modificato"
psql_in postgres -c "DROP DATABASE $verify" >/dev/null < /dev/null
trap - EXIT
if [ -d "$media_dir" ]; then
  tar -C "$media_dir" -czf "$dest/media.tar.gz" .
  tar -tzf "$dest/media.tar.gz" > /dev/null || die "archivio delle foto illeggibile"
fi
(cd "$dest" && sha256sum ./* > MANIFEST.sha256)
echo "   db.dump verificato con un ripristino di prova completo (conteggi e impronte identici)"
[ -f "$dest/media.tar.gz" ] && echo "   foto archiviate da $media_dir in media.tar.gz"
echo "   impronte SHA-256 in MANIFEST.sha256"
echo "   per ripristinare: docs/operations/RESET_TEST_DATA.md §Ripristino"
[ "$mode" = backup ] && { echo; echo "Backup completato: nessuna modifica ai dati."; exit 0; }

# ---- 3. conferma e reset in una transazione ---------------------------------------------------------------------
others="$(q "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'")"
[ "$others" = "0" ] || die "$others connessioni ancora aperte al database (backend avviato?): fermalo e riprova. Il backup resta in $dest"
echo
echo "Verranno ELIMINATE le righe marcate ELIMINA qui sopra. Backup: $dest"
printf 'Per procedere scrivi esattamente «%s»: ' "$phrase"
answer=""
if [ -t 0 ]; then read -r answer < /dev/tty || true; else read -r answer || true; fi
echo
[ "$answer" = "$phrase" ] || die "conferma non data: nessuna modifica"

psql_in "$db" --single-transaction -v keep_stats="$keep_stats" < "$here/reset.sql"

after="$(counts "$db")"
echo "$after" > "$dest/counts-after.txt"
psql_in "$db" < "$here/fingerprints.sql" > "$dest/fingerprints-after.txt"
echo
echo "== Dopo il reset"
echo "$after" | show
if diff -q "$dest/fingerprints-before.txt" "$dest/fingerprints-after.txt" >/dev/null; then
  echo "   Tabelle conservate: contenuto identico a prima (impronte uguali per $(wc -l < "$dest/fingerprints-after.txt") tabelle)"
else
  echo "ATTENZIONE: impronte diverse:"; diff "$dest/fingerprints-before.txt" "$dest/fingerprints-after.txt" || true; exit 2
fi
{
  echo "# Reset dati di prova — $stamp"
  echo; echo "Container \`$container\`, database \`$db\`. Backup: \`$dest\`."
  echo; echo "| Sezione | Tabella | Dettaglio | Prima | Dopo |"; echo "|---|---|---|---|---|"
  awk -F'|' 'NR == FNR { a[$1"|"$2"|"$3] = $4; next }
             { k = $1"|"$2"|"$3; printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, (k in a ? a[k] : 0) }' \
      "$dest/counts-after.txt" "$dest/counts-before.txt"
} > "$dest/report.md"
echo
echo "Reset completato. Report: $dest/report.md"
echo "Ora avvia il backend e controlla: node infra/scripts/reset-test-data/check-api.mjs http://localhost:8080 $dest/blocked-slots-before.txt"
