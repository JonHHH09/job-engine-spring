#!/usr/bin/env bash
# Restore and validate a backup in a disposable Compose project only.
set -euo pipefail
umask 077
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/postgres-ops-common.sh"

usage() {
  printf 'Usage: %s --backup-set PATH --image IMAGE@sha256:... [--candidate-image IMAGE@sha256:...]\n' "${0##*/}" >&2
}
backup_set=""; image=""; candidate_image=""
while (($#)); do
  case "$1" in
    --backup-set) backup_set="${2:-}"; shift 2 ;;
    --image) image="${2:-}"; shift 2 ;;
    --candidate-image) candidate_image="${2:-}"; shift 2 ;;
    --help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done
[[ -n "$backup_set" && -n "$image" ]] || { usage; exit 2; }
require_command docker
require_command python3
require_digest_image "$image"
[[ -z "$candidate_image" ]] || require_digest_image "$candidate_image"
backup_set="$(cd "$backup_set" && pwd -P)"
metadata="$backup_set/backup.json"
[[ -f "$metadata" ]] || { printf 'backup metadata unavailable\n' >&2; exit 2; }
backup_checksum="$(python3 - "$metadata" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle: print(json.load(handle)["dump"]["sha256"])
PY
)"
expected_protected="$(python3 - "$metadata" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    protected = json.load(handle).get("protected")
if not isinstance(protected, dict):
    raise SystemExit(2)
print(json.dumps(protected, sort_keys=True, separators=(",", ":")))
PY
)"
verify_project="job-engine-backup-verify-$(date -u +%s)-$RANDOM"
verify_volume="${verify_project}_postgres-data"
database="${JOB_ENGINE_POSTGRES_DB:-job_engine}"
cleaned=false
pending_report=""
cleanup() {
  if [[ -n "$pending_report" ]]; then
    rm -f -- "$pending_report"
    pending_report=""
  fi
  if [[ "$cleaned" == false && "$verify_project" == job-engine-backup-verify-* ]]; then
    (cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$verify_project" docker compose --profile manual-mcp down -v --remove-orphans >/dev/null 2>&1) || true
    cleaned=true
  fi
}
trap cleanup EXIT INT TERM
safe_log verification_started
(cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$verify_project" docker compose up -d --wait postgres >/dev/null)
"$ROOT_DIR/scripts/postgres-restore.sh" --backup-set "$backup_set" --target-project "$verify_project" --target-volume "$verify_volume" --database "$database" --confirm RESTORE --confirm-existing OVERWRITE
container="$(compose_postgres_container "$verify_project")"
protected_query="$(protected_metadata_query)"
baseline="$(docker exec -i -u postgres "$container" psql -X -At -v ON_ERROR_STOP=1 -d "$database" -c "$protected_query")"
python3 - "$expected_protected" "$baseline" <<'PY'
import json, sys
if json.loads(sys.argv[1]) != json.loads(sys.argv[2]):
    raise SystemExit('restored protected counts or Flyway state differ from backup')
PY
document_id="$(docker exec -i -u postgres "$container" psql -X -At -v ON_ERROR_STOP=1 -d "$database" -c 'SELECT id FROM document.documents ORDER BY id LIMIT 1' 2>/dev/null || true)"
run_image_verification() {
  local subject_image="$1" report_name="$2" final_report after
  final_report="$backup_set/$report_name"
  pending_report="$backup_set/.${report_name}.pending.$$.$RANDOM"
  [[ ! -e "$final_report" && ! -L "$final_report" ]] || { printf 'verification report already exists\n' >&2; return 1; }
  if [[ "$document_id" =~ ^[0-9a-fA-F-]{36}$ ]]; then
    MCP_IMAGE="$subject_image" COMPOSE_PROJECT_NAME="$verify_project" python3 "$ROOT_DIR/scripts/verify-mcp-restored-data.py" --report "$pending_report" --image "$subject_image" --backup-sha256 "$backup_checksum" --document-id "$document_id" -- docker compose --profile manual-mcp -p "$verify_project" run --rm -T mcp
  else
    MCP_IMAGE="$subject_image" COMPOSE_PROJECT_NAME="$verify_project" python3 "$ROOT_DIR/scripts/verify-mcp-restored-data.py" --report "$pending_report" --image "$subject_image" --backup-sha256 "$backup_checksum" -- docker compose --profile manual-mcp -p "$verify_project" run --rm -T mcp
  fi
  after="$(docker exec -i -u postgres "$container" psql -X -At -v ON_ERROR_STOP=1 -d "$database" -c "$protected_query")"
  finalize_verification_report "$pending_report" "$final_report" "$baseline" "$after"
  pending_report=""
}
run_image_verification "$image" "verification-released.json"
if [[ -n "$candidate_image" ]]; then
  run_image_verification "$candidate_image" "verification-candidate.json"
fi
chmod 600 "$backup_set"/verification-*.json
safe_log verification_complete
cleanup
