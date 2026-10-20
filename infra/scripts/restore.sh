#!/usr/bin/env bash
# Ripristino di un backup creato da backup.sh in un database VUOTO (mai sopra quello in uso: DEPLOY.md §Ripristino).
# Uso: infra/scripts/restore.sh <cartella-backup> <container-postgres-destinazione> [cartella-foto-destinazione]
set -euo pipefail
src="${1:?cartella del backup}"
container="${2:?container di destinazione}"
media="${3:-}"
db="${FIREFLIES_DB_NAME:-fireflies}"
user="${FIREFLIES_DB_USER:-fireflies}"
(cd "$src" && sha256sum -c MANIFEST.sha256 --quiet)
tables="$(docker exec "$container" psql -U "$user" -d "$db" -Atc "select count(*) from information_schema.tables where table_schema='public'")"
if [ "$tables" != "0" ]; then
  echo "Il database di destinazione non è vuoto ($tables tabelle): ripristino annullato." >&2
  exit 1
fi
docker exec -i "$container" pg_restore -U "$user" -d "$db" --no-owner --exit-on-error < "$src/db.dump"
if [ -n "$media" ] && [ -f "$src/media.tar.gz" ]; then
  mkdir -p "$media"
  tar -C "$media" -xzf "$src/media.tar.gz"
fi
echo "Ripristino completato in $container"
