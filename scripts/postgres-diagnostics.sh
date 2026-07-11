#!/usr/bin/env bash
# Read-only, sanitized operational status. It never starts or stops containers.
set -euo pipefail
umask 077
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/postgres-ops-common.sh"

requested_root="${JOB_ENGINE_BACKUP_ROOT:-$ROOT_DIR/backups/postgres}"
if [[ "${1:-}" == "--backup-root" ]]; then requested_root="${2:-}"; shift 2; fi
(($# == 0)) || { printf 'Usage: %s [--backup-root ABSOLUTE_PATH]\n' "${0##*/}" >&2; exit 2; }
root="$(backup_root "$requested_root")"
primary_project="$(primary_compose_project)"
postgres_health=unknown
container="$(compose_postgres_container "$primary_project" 2>/dev/null || true)"
if [[ -n "$container" ]] && require_healthy_postgres "$container" >/dev/null 2>&1; then postgres_health=healthy; else postgres_health=unknown; fi
primary_volume="${primary_project}_postgres-data"
if docker volume inspect "$primary_volume" >/dev/null 2>&1; then volume=present; else volume=unknown; fi
capacity_bytes="$(df -Pk "$root" 2>/dev/null | awk 'NR==2 {print $4 * 1024}' || true)"
[[ "$capacity_bytes" =~ ^[0-9]+$ ]] || capacity_bytes=unknown
latest_age=unknown
latest="$(find "$root" -mindepth 2 -maxdepth 2 -type f -name verification-released.json -print 2>/dev/null | sort | tail -n 1 || true)"
if [[ -n "$latest" ]]; then
  latest_age="$(python3 - "$latest" <<'PY'
import json, sys
from datetime import datetime, timezone
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        data = json.load(handle)
    if data.get("status") != "verified":
        raise ValueError
    created = datetime.fromtimestamp(__import__("os").stat(sys.argv[1]).st_mtime, timezone.utc)
    print(max(0, int((datetime.now(timezone.utc) - created).total_seconds())))
except (OSError, ValueError, json.JSONDecodeError):
    print("unknown")
PY
)"
fi
flyway=unknown
if [[ -n "$container" ]]; then
  if docker exec -i -u postgres "$container" psql -X -At -d "${JOB_ENGINE_POSTGRES_DB:-job_engine}" -c 'SELECT count(*) > 0 FROM profile.flyway_schema_history' 2>/dev/null | grep -qx t; then flyway=healthy; fi
fi
printf 'postgres=%s\nprimary_volume=%s\nbackup_capacity_bytes=%s\nlatest_verified_backup=%s\nflyway=%s\nmcp=unknown\n' "$postgres_health" "$volume" "$capacity_bytes" "$latest_age" "$flyway"
safe_log diagnostics_complete
