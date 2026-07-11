#!/usr/bin/env bash
# Restore a complete backup only into a separately labelled Compose target.
set -euo pipefail
umask 077
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/postgres-ops-common.sh"

usage() {
  printf 'Usage: %s --backup-set PATH --target-project NAME --target-volume NAME --database NAME --confirm RESTORE --confirm-existing OVERWRITE\n' "${0##*/}" >&2
}
backup_set=""; target_project=""; target_volume=""; database=""; confirmation=""; existing_confirmation=""
while (($#)); do
  case "$1" in
    --backup-set) backup_set="${2:-}"; shift 2 ;;
    --target-project) target_project="${2:-}"; shift 2 ;;
    --target-volume) target_volume="${2:-}"; shift 2 ;;
    --database) database="${2:-}"; shift 2 ;;
    --confirm) confirmation="${2:-}"; shift 2 ;;
    --confirm-existing) existing_confirmation="${2:-}"; shift 2 ;;
    --help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

[[ -n "$backup_set" && -n "$target_project" && -n "$target_volume" && -n "$database" ]] || { usage; exit 2; }
require_command docker
primary_project="$(primary_compose_project)"
require_non_primary_project "$primary_project" "$target_project"
require_confirmation "$confirmation" RESTORE
require_confirmation "$existing_confirmation" OVERWRITE
backup_set="$(cd "$backup_set" && pwd -P)"
dump="$backup_set/database.dump"; metadata="$backup_set/backup.json"
[[ -f "$dump" && -f "$metadata" && ! -L "$dump" && ! -L "$metadata" ]] || { printf 'backup set is incomplete\n' >&2; exit 2; }
expected_checksum="$(python3 - "$metadata" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle)
checksum = value.get("dump", {}).get("sha256")
if not isinstance(checksum, str) or len(checksum) != 64:
    raise SystemExit(2)
print(checksum)
PY
)"
[[ "$expected_checksum" == "$(sha256_file "$dump")" ]] || { printf 'backup checksum mismatch\n' >&2; exit 1; }
docker run --rm -i --entrypoint pg_restore "$(docker inspect -f '{{.Config.Image}}' "$(compose_postgres_container "$target_project")")" --list <"$dump" >/dev/null
require_distinct_target_volume "$primary_project" "$target_project" "$target_volume"
target_container="$(compose_postgres_container "$target_project")"
container_project="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$target_container")"
container_service="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$target_container")"
[[ "$container_project" == "$target_project" && "$container_service" == postgres ]] || { printf 'target container identity is unsafe\n' >&2; exit 1; }
require_target_container_volume "$target_container" "$target_volume"
require_healthy_postgres "$target_container"
safe_log restore_started
if ! docker exec -i -u postgres "$target_container" pg_restore --clean --if-exists --no-owner --no-privileges -d "$database" <"$dump"; then
  safe_log restore_refused
  exit 1
fi
safe_log restore_complete
