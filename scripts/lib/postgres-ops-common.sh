#!/usr/bin/env bash
# Shared primitives for host-side PostgreSQL operations. This file is sourced.
set -euo pipefail
umask 077

POSTGRES_OPS_LOCK_DIR=""
POSTGRES_OPS_TEMP_DIR=""

postgres_ops_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd
}

primary_compose_project() {
  if [[ "${POSTGRES_OPS_TEST_MODE:-}" == 1 && "${POSTGRES_OPS_TEST_PRIMARY_PROJECT:-}" =~ ^job-engine-backup-acceptance-source-[a-z0-9-]+$ ]]; then
    printf '%s\n' "$POSTGRES_OPS_TEST_PRIMARY_PROJECT"
    return
  fi
  local root
  root="$(postgres_ops_root)"
  # The primary identity is repository configuration, never a caller override.
  (cd "$root" && env -u COMPOSE_PROJECT_NAME docker compose config --format json | python3 -c 'import json, sys; print(json.load(sys.stdin)["name"])')
}

safe_log() {
  case "${1:-}" in
    backup_started|backup_created|backup_failed|restore_started|restore_complete|restore_refused|verification_started|verification_complete|verification_failed|prune_preview|prune_complete|diagnostics_complete|cleanup_complete)
      printf '%s\n' "$1" >&2
      ;;
    *)
      return 2
      ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'required command unavailable\n' >&2
    return 127
  }
}

backup_root() {
  local candidate="${1:-}"
  [[ -n "$candidate" && "$candidate" = /* ]] || {
    printf 'backup root must be an absolute path\n' >&2
    return 2
  }
  mkdir -p "$candidate"
  (cd "$candidate" && pwd)
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

protected_metadata_query() {
  cat <<'SQL'
SELECT json_build_object('tables', json_build_object('profiles', (SELECT count(*) FROM profile.profiles), 'jobs', (SELECT count(*) FROM job_schema.jobs), 'documents', (SELECT count(*) FROM document.documents)), 'flyway', json_build_object('applied', (SELECT count(*) > 0 FROM profile.flyway_schema_history), 'fingerprint', (SELECT md5(coalesce(string_agg(installed_rank::text || ':' || coalesce(version,'') || ':' || description || ':' || type || ':' || script || ':' || coalesce(checksum::text,'') || ':' || success::text, ',' ORDER BY installed_rank), '')) FROM profile.flyway_schema_history)));
SQL
}

acquire_lock() {
  local root="$1"
  local lock_dir="$root/.postgres-ops.lock"
  if ! mkdir "$lock_dir" 2>/dev/null; then
    printf 'another PostgreSQL operation is already active for this backup root\n' >&2
    return 1
  fi
  POSTGRES_OPS_LOCK_DIR="$lock_dir"
}

release_lock() {
  if [[ -n "$POSTGRES_OPS_LOCK_DIR" ]]; then
    rmdir "$POSTGRES_OPS_LOCK_DIR" 2>/dev/null || true
    POSTGRES_OPS_LOCK_DIR=""
  fi
}

make_private_temp_dir() {
  local parent="$1"
  POSTGRES_OPS_TEMP_DIR="$(mktemp -d "$parent/.postgres-ops.XXXXXX")"
  printf '%s\n' "$POSTGRES_OPS_TEMP_DIR"
}

cleanup_postgres_ops() {
  release_lock
  if [[ -n "$POSTGRES_OPS_TEMP_DIR" && -d "$POSTGRES_OPS_TEMP_DIR" ]]; then
    rm -rf "$POSTGRES_OPS_TEMP_DIR"
  fi
}

require_confirmation() {
  [[ "${1:-}" == "${2:-}" && -n "${1:-}" ]] || {
    printf 'explicit confirmation is required\n' >&2
    return 2
  }
}

require_non_primary_project() {
  local primary="$1" target="$2"
  [[ -n "$target" && "$target" != "$primary" ]] || {
    printf 'target identity is unsafe\n' >&2
    return 2
  }
}

require_digest_image() {
  if [[ "${1:-}" =~ ^[^[:space:]@]+@sha256:[a-f0-9]{64}$ ]]; then
    return
  fi
  if [[ "${POSTGRES_OPS_TEST_MODE:-}" == 1 && "${1:-}" =~ ^sha256:[a-f0-9]{64}$ ]]; then
    return
  fi
  {
    printf 'MCP image must use an immutable sha256 digest reference\n' >&2
    return 2
  }
}

compose_postgres_container() {
  local project="$1"
  local root
  root="$(postgres_ops_root)"
  local container
  container="$(cd "$root" && COMPOSE_PROJECT_NAME="$project" docker compose ps -q postgres)"
  [[ -n "$container" ]] || {
    printf 'target PostgreSQL service is not running\n' >&2
    return 1
  }
  printf '%s\n' "$container"
}

require_healthy_postgres() {
  local container="$1" health
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container" 2>/dev/null || true)"
  [[ "$health" == "healthy" ]] || {
    printf 'PostgreSQL service is not healthy\n' >&2
    return 1
  }
}

volume_identity() {
  local volume="$1"
  docker volume inspect -f '{{.Name}}|{{index .Labels "com.docker.compose.project"}}|{{index .Labels "com.docker.compose.volume"}}' "$volume" 2>/dev/null
}

require_distinct_target_volume() {
  local primary_project="$1" target_project="$2" target_volume="$3"
  require_non_primary_project "$primary_project" "$target_project"
  local identity name project label
  identity="$(volume_identity "$target_volume")" || {
    printf 'target volume identity cannot be proven\n' >&2
    return 1
  }
  IFS='|' read -r name project label <<<"$identity"
  [[ -n "$name" && "$project" == "$target_project" && "$label" == "postgres-data" ]] || {
    printf 'target volume labels are unsafe\n' >&2
    return 1
  }
  [[ "$project" != "$primary_project" ]] || {
    printf 'target volume is primary\n' >&2
    return 1
  }
}

require_target_container_volume() {
  local container="$1" expected_volume="$2" mounted
  mounted="$(docker inspect -f '{{range .Mounts}}{{if and (eq .Type "volume") (eq .Destination "/var/lib/postgresql")}}{{.Name}}{{end}}{{end}}' "$container" 2>/dev/null)" || {
    printf 'target container mount identity cannot be proven\n' >&2
    return 1
  }
  [[ -n "$mounted" && "$mounted" == "$expected_volume" ]] || {
    printf 'target container does not mount the requested target volume\n' >&2
    return 1
  }
}

safe_set_path() {
  local root="$1" candidate="$2"
  local canonical_root canonical_candidate
  canonical_root="$(cd "$root" && pwd -P)"
  canonical_candidate="$(cd "$(dirname "$candidate")" && pwd -P)/$(basename "$candidate")"
  [[ "$canonical_candidate" == "$canonical_root"/* ]] || {
    printf 'path is outside backup root\n' >&2
    return 2
  }
  printf '%s\n' "$canonical_candidate"
}

finalize_verification_report() {
  local pending="$1" final="$2" baseline="$3" after="$4"
  if ! python3 - "$pending" "$baseline" "$after" <<'PY'
import json
import os
import sys
import tempfile

path, baseline_json, after_json = sys.argv[1:]
before, after = json.loads(baseline_json), json.loads(after_json)
if before != after:
    before_tables, after_tables = before.get("tables", {}), after.get("tables", {})
    changed_tables = sorted(key for key in set(before_tables) | set(after_tables) if before_tables.get(key) != after_tables.get(key))
    flyway_changed = before.get("flyway") != after.get("flyway")
    detail = ",".join(changed_tables) if changed_tables else "none"
    raise SystemExit(f"protected state changed during verification: tables={detail} flyway={str(flyway_changed).lower()}")
with open(path, encoding="utf-8") as handle:
    report = json.load(handle)
if report.get("status") != "mcp-verified":
    raise SystemExit("MCP verification report has an invalid intermediate status")
report["status"] = "verified"
fd, temporary = tempfile.mkstemp(prefix=".verification-final.", dir=os.path.dirname(path))
try:
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(report, handle, sort_keys=True, separators=(",", ":"))
        handle.write("\n")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
except BaseException:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
    raise
PY
  then
    rm -f -- "$pending"
    return 1
  fi
  if ! ln "$pending" "$final"; then
    rm -f -- "$pending"
    return 1
  fi
  rm -f -- "$pending"
}
