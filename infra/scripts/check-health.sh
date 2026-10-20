#!/usr/bin/env bash
# Controllo giornaliero di FireFlies (FASE 8, monitoraggio): da cron sul server, esce con errore (e stampa il motivo)
# se qualcosa non va. Non legge segreti e non invia messaggi: l'esito lo raccoglie il cron/monitor scelto (DEPLOY.md §6).
#   - backend pronto (health/readiness tramite il proxy)
#   - pulizia notturna: nessun FAILED negli ultimi 2 giorni e un'esecuzione DONE nelle ultime 36 ore
#   - ultimo backup più recente di 26 ore e con manifest valido
# Uso: infra/scripts/check-health.sh <url-sito> <container-postgres> <cartella-backup>
set -euo pipefail
site="${1:?url del sito, es. https://example.org}"
container="${2:?container di PostgreSQL}"
backups="${3:?cartella dei backup}"
user="${FIREFLIES_DB_USER:-fireflies}"
db="${FIREFLIES_DB_NAME:-fireflies}"
problems=()

curl -fsS --max-time 10 "$site/actuator/health" >/dev/null || problems+=("backend non raggiungibile ($site/actuator/health)")

q() { docker exec "$container" psql -U "$user" -d "$db" -Atc "$1"; }
failed="$(q "select count(*) from maintenance_runs where status = 'FAILED' and run_date >= current_date - 2")" \
  || problems+=("database non interrogabile")
[ "${failed:-0}" = "0" ] || problems+=("pulizia notturna: $failed esecuzioni FAILED negli ultimi 2 giorni")
done_recent="$(q "select count(*) from maintenance_runs where status = 'DONE' and finished_at > now() - interval '36 hours'" || echo 0)"
[ "$done_recent" != "0" ] || problems+=("pulizia notturna: nessuna esecuzione completata nelle ultime 36 ore")

last="$(ls -1d "$backups"/*/ 2>/dev/null | sort | tail -1 || true)"
if [ -z "$last" ]; then
  problems+=("nessun backup in $backups")
else
  age=$(( $(date +%s) - $(stat -c %Y "$last") ))
  [ "$age" -lt $((26 * 3600)) ] || problems+=("ultimo backup vecchio di $((age / 3600)) ore ($last)")
  (cd "$last" && sha256sum -c --quiet MANIFEST.sha256) || problems+=("manifest del backup non valido ($last)")
fi

if [ ${#problems[@]} -gt 0 ]; then
  printf 'FireFlies: %s\n' "${problems[@]}" >&2
  exit 1
fi
echo "FireFlies: tutto in ordine"
