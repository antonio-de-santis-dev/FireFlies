#!/usr/bin/env bash
# Prova di ripristino completa in ambiente ISOLATO (FASE 8, req. 7). Non tocca il database di sviluppo:
# usa due progetti Compose separati (fireflies-drill-a / -b) su porte proprie e cartelle temporanee, poi li elimina.
#   1. A: PostgreSQL vuoto + backend reale (profilo local, dati demo e foto) + una richiesta di prenotazione via API
#   2. backup.sh di A (database + foto)
#   3. B: PostgreSQL vuoto, restore.sh, backend avviato su B (Flyway valida lo schema ripristinato)
#   4. confronto: righe di ogni tabella, stessa foto servita con lo stesso SHA-256, stessa prenotazione dall'API
# Uso: infra/scripts/restore-drill.sh   (richiede Docker, Java 21, curl, python3; il jar si compila se manca)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
pass="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
port_a=55511; port_b=55512; http_a=18181; http_b=18182
export FIREFLIES_DB_USER=fireflies FIREFLIES_DB_NAME=fireflies FIREFLIES_DB_PASSWORD="$pass"
pids=()
cleanup() {
  for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done
  FIREFLIES_DB_PORT=$port_a docker compose -p fireflies-drill-a -f "$root/infra/compose.yaml" down -v >/dev/null 2>&1 || true
  FIREFLIES_DB_PORT=$port_b docker compose -p fireflies-drill-b -f "$root/infra/compose.yaml" down -v >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

jar="$(ls "$root"/backend/target/fireflies-backend-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$jar" ]; then (cd "$root/backend" && ./mvnw -q -o -DskipTests package); jar="$(ls "$root"/backend/target/fireflies-backend-*.jar | head -1)"; fi
cp "$jar" "$work/app.jar" # copia: una compilazione in parallelo non cambia il jar in uso

start_db() { FIREFLIES_DB_PORT=$2 docker compose -p "$1" -f "$root/infra/compose.yaml" up -d --wait >/dev/null; }
start_backend() { # $1 porta db, $2 porta http, $3 cartella foto, $4 log
  FIREFLIES_DB_URL="jdbc:postgresql://localhost:$1/fireflies" FIREFLIES_MEDIA_DIR="$3" FIREFLIES_DEMO_SEED=true \
    FIREFLIES_DEMO_ASSETS_DIR="$root/frontend/public/assets/fireflies" FIREFLIES_RETENTION_ENABLED=false \
    FIREFLIES_NOTIFICATIONS_WORKER_ENABLED=false \
    java -jar "$work/app.jar" --server.port="$2" > "$4" 2>&1 &
  pids+=($!)
  for _ in $(seq 1 120); do curl -fsS "http://localhost:$2/actuator/health/readiness" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "backend non partito, vedi $4" >&2; tail -40 "$4" >&2; exit 1
}
counts() { docker exec "$1" psql -U fireflies -d fireflies -Atc "
  select string_agg(t || '=' || n, ' ' order by t) from (
    select table_name t, (xpath('/row/c/text()', query_to_xml(format('select count(*) c from %I', table_name), false, true, '')))[1]::text::int n
      from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'
       and table_name not in ('spring_session', 'spring_session_attributes')) x"; }

echo "▶ A: database e backend con dati reali di prova"
start_db fireflies-drill-a $port_a
start_backend $port_a $http_a "$work/media-a" "$work/a.log"
# una richiesta dal sito con CSRF, come il modulo pubblico
jar_c="$work/cookies"; curl -fsS -c "$jar_c" "http://localhost:$http_a/api/v1/auth/csrf" >/dev/null
token="$(awk '$6=="XSRF-TOKEN"{print $7}' "$jar_c")"
day="$(curl -fsS "http://localhost:$http_a/api/v1/public/booking-options" | python3 -c '
import json,sys,datetime
o=json.load(sys.stdin); closed=set(o.get("closedDates") or [])
d=datetime.date.fromisoformat(o["firstDate"])+datetime.timedelta(days=3)
while d.isoformat() in closed: d+=datetime.timedelta(days=1)
print(d.isoformat())')"
ref="$(curl -fsS -b "$jar_c" -H "X-XSRF-TOKEN: $token" -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' \
  -d "{\"date\":\"$day\",\"time\":\"18:00\",\"guests\":3,\"name\":\"Prova ripristino\",\"phone\":\"+39 300 000 0001\",\"privacyAccepted\":true}" \
  "http://localhost:$http_a/api/v1/public/reservations" | python3 -c 'import json,sys;print(json.load(sys.stdin)["reference"])')"
photo="$(curl -fsS "http://localhost:$http_a/api/v1/public/gallery" | python3 -c 'import json,sys;g=json.load(sys.stdin);g=g if isinstance(g,list) else g.get("items",g);print(g[0]["src"])')"
sha_a="$(curl -fsS "http://localhost:$http_a$photo" | sha256sum | cut -d' ' -f1)"
kill "${pids[-1]}"; wait "${pids[-1]}" 2>/dev/null || true
counts_a="$(counts fireflies-drill-a-postgres-1)"

echo "▶ backup"
"$root/infra/scripts/backup.sh" fireflies-drill-a-postgres-1 "$work/backups" "$work/media-a" >/dev/null
backup="$(ls -d "$work"/backups/* | head -1)"

echo "▶ B: database vuoto, ripristino, backend sul database ripristinato"
start_db fireflies-drill-b $port_b
"$root/infra/scripts/restore.sh" "$backup" fireflies-drill-b-postgres-1 "$work/media-b" >/dev/null
counts_b="$(counts fireflies-drill-b-postgres-1)"
start_backend $port_b $http_b "$work/media-b" "$work/b.log"
grep -q "Contenuti demo: database gi" "$work/b.log" || { echo "B ha ricaricato i dati demo: il ripristino non è stato riconosciuto" >&2; exit 1; }
sha_b="$(curl -fsS "http://localhost:$http_b$photo" | sha256sum | cut -d' ' -f1)"
status_b="$(docker exec fireflies-drill-b-postgres-1 psql -U fireflies -d fireflies -Atc "select status from reservations where public_ref = '$ref'")"
migrations="$(docker exec fireflies-drill-b-postgres-1 psql -U fireflies -d fireflies -Atc "select count(*) from flyway_schema_history where success")"

echo "▶ confronto"
[ "$counts_a" = "$counts_b" ] || { echo "righe diverse:"; diff <(tr ' ' '\n' <<<"$counts_a") <(tr ' ' '\n' <<<"$counts_b"); exit 1; }
[ "$sha_a" = "$sha_b" ] || { echo "foto diversa dopo il ripristino" >&2; exit 1; }
[ "$status_b" = "PENDING" ] || { echo "prenotazione $ref non ripristinata" >&2; exit 1; }
echo "OK: $(tr ' ' '\n' <<<"$counts_b" | wc -l) tabelle identiche ($counts_b)"
echo "OK: foto $photo identica (sha256 $sha_b), prenotazione $ref in attesa, $migrations migrazioni Flyway valide, backend avviato su B"
