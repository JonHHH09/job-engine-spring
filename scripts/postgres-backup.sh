#!/usr/bin/env bash
# Create a transaction-consistent, custom-format backup outside Docker volumes.
set -euo pipefail
umask 077
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/postgres-ops-common.sh"

usage() {
  printf 'Usage: %s [--backup-root ABSOLUTE_PATH]\n' "${0##*/}" >&2
}

requested_root="${JOB_ENGINE_BACKUP_ROOT:-$ROOT_DIR/backups/postgres}"
while (($#)); do
  case "$1" in
    --backup-root) requested_root="${2:-}"; shift 2 ;;
    --help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

for command in docker python3; do require_command "$command"; done
backup_root="$(backup_root "$requested_root")"
primary_project="$(primary_compose_project)"
acquire_lock "$backup_root"
trap cleanup_postgres_ops EXIT INT TERM
safe_log backup_started
container="$(compose_postgres_container "$primary_project")"
require_healthy_postgres "$container"
database="${JOB_ENGINE_POSTGRES_DB:-job_engine}"
schema_ready="$(docker exec -i -u postgres "$container" psql -qXAt -v ON_ERROR_STOP=1 -d "$database" -c "SELECT to_regclass('profile.flyway_schema_history') IS NOT NULL AND to_regclass('profile.profiles') IS NOT NULL AND to_regclass('job_schema.jobs') IS NOT NULL AND to_regclass('document.documents') IS NOT NULL" 2>/dev/null || true)"
[[ "$schema_ready" == t ]] || { printf 'application schema is not initialized\n' >&2; exit 1; }
make_private_temp_dir "$backup_root" >/dev/null
staging="$POSTGRES_OPS_TEMP_DIR"
set_name="$(date -u +%Y%m%dT%H%M%SZ)-$(openssl rand -hex 4 2>/dev/null || printf '%08x' "$RANDOM")"
final_set="$backup_root/$set_name"

snapshot_input="$staging/.snapshot-input"
snapshot_output="$staging/.snapshot-output"
mkfifo "$snapshot_input" "$snapshot_output"
docker exec -i -u postgres "$container" psql -qXAt -v ON_ERROR_STOP=1 -d "$database" <"$snapshot_input" >"$snapshot_output" &
snapshot_pid=$!
exec 3>"$snapshot_input"
exec 4<"$snapshot_output"
printf 'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();\n' >&3
IFS= read -r snapshot_id <&4
[[ "$snapshot_id" =~ ^[0-9A-F-]+$ ]] || { printf 'could not establish database snapshot\n' >&2; exit 1; }

if ! docker exec -i -u postgres "$container" pg_dump --format=custom --snapshot="$snapshot_id" -d "$database" >"$staging/database.dump"; then
  safe_log backup_failed
  exit 1
fi
docker exec -i -u postgres "$container" pg_restore --list <"$staging/database.dump" >/dev/null
printf '%s\n' "$(protected_metadata_query)" >&3
IFS= read -r protected_counts <&4
printf 'COMMIT;\n' >&3
exec 3>&-
exec 4<&-
wait "$snapshot_pid"
rm -f "$snapshot_input" "$snapshot_output"

checksum="$(sha256_file "$staging/database.dump")"
python3 - "$staging/backup.json" "$set_name" "$checksum" "$protected_counts" <<'PY'
import json
import sys
from datetime import datetime, timezone
path, set_name, checksum, counts = sys.argv[1:]
try:
    protected_counts = json.loads(counts)
except json.JSONDecodeError as exc:
    raise SystemExit("database snapshot metadata was invalid") from exc
metadata = {
    "format": 1,
    "set": set_name,
    "createdAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "dump": {"file": "database.dump", "sha256": checksum, "format": "postgres-custom"},
    "protected": protected_counts,
}
with open(path, "x", encoding="utf-8") as handle:
    json.dump(metadata, handle, sort_keys=True, separators=(",", ":"))
    handle.write("\n")
PY
chmod 600 "$staging/database.dump" "$staging/backup.json"
if command -v sync >/dev/null 2>&1; then sync "$staging" 2>/dev/null || sync; fi
mv "$staging" "$final_set"
# shellcheck disable=SC2034 # Cleanup trap consumes this sourced-library state.
POSTGRES_OPS_TEMP_DIR=""
safe_log backup_created
