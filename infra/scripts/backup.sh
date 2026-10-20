#!/usr/bin/env bash
# Backup di FireFlies (FASE 8): database in formato custom di pg_dump + cartella delle foto (solo storage locale) +
# manifest con le impronte SHA-256. Non legge né scrive segreti: la password la prende il container di PostgreSQL.
#
# Uso: infra/scripts/backup.sh <container-postgres> <cartella-destinazione> [cartella-foto]
#   es. infra/scripts/backup.sh fireflies-prod-postgres-1 /var/backups/fireflies
# In produzione le foto stanno su R2: si copiano con il versioning del bucket o `rclone sync` (DEPLOY.md §Backup).
set -euo pipefail
container="${1:?container di PostgreSQL}"
dest_root="${2:?cartella di destinazione}"
media="${3:-}"
db="${FIREFLIES_DB_NAME:-fireflies}"
user="${FIREFLIES_DB_USER:-fireflies}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dest="$dest_root/$stamp"
umask 077
mkdir -p "$dest"
docker exec "$container" pg_dump -U "$user" -d "$db" -Fc --no-owner > "$dest/db.dump"
# verifica che il dump sia leggibile prima di dichiararlo valido
docker exec -i "$container" pg_restore --list < "$dest/db.dump" > "$dest/db.list"
if [ -n "$media" ]; then
  tar -C "$media" -czf "$dest/media.tar.gz" .
fi
(cd "$dest" && sha256sum ./* > MANIFEST.sha256)
echo "Backup completato: $dest"
