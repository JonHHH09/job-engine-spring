#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/postgres-ops-common.sh"

failures=0
assert_eq() {
  local expected="$1" actual="$2" message="$3"
  if [[ "$expected" != "$actual" ]]; then
    printf 'FAIL: %s\n' "$message" >&2
    failures=$((failures + 1))
  fi
}
assert_fails() {
  local message="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf 'FAIL: %s\n' "$message" >&2
    failures=$((failures + 1))
  fi
}

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
root_with_spaces="$work_dir/backup root"
mkdir -p "$root_with_spaces"

assert_eq "$root_with_spaces" "$(backup_root "$root_with_spaces")" "backup root supports spaces"
assert_fails "relative backup root is rejected" backup_root "relative/path"

payload="$work_dir/payload"
printf 'payload' >"$payload"
checksum="$(sha256_file "$payload")"
assert_eq "$(sha256_file "$payload")" "$checksum" "checksum is deterministic"

lock_root="$work_dir/lock root"
mkdir -p "$lock_root"
acquire_lock "$lock_root"
assert_fails "second lock acquisition fails closed" bash -c "source '$ROOT_DIR/scripts/lib/postgres-ops-common.sh'; acquire_lock '$lock_root'"
release_lock

assert_fails "unsafe project equal to primary is rejected" require_non_primary_project "primary" "primary"
assert_fails "empty confirmation is rejected" require_confirmation "" "DELETE"
require_confirmation "DELETE" "DELETE"
assert_fails "mutable image is rejected" require_digest_image "job-engine:latest"
require_digest_image "registry.example/job-engine@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

safe_log "backup_created"
if safe_log 'sensitive value'; then
  printf 'FAIL: dynamic log category was accepted\n' >&2
  failures=$((failures + 1))
fi

metadata_query="$(protected_metadata_query)"
if ! python3 - "$metadata_query" <<'PY'
import sys

query = sys.argv[1]
depth = 0
quoted = False
for char in query:
    if char == "'":
        quoted = not quoted
    elif not quoted and char == "(":
        depth += 1
    elif not quoted and char == ")":
        depth -= 1
        if depth < 0:
            raise SystemExit(1)
if quoted or depth != 0 or not query.rstrip().endswith(";"):
    raise SystemExit(1)
PY
then
  printf 'FAIL: protected metadata query is not syntactically complete\n' >&2
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  exit 1
fi
printf 'postgres ops deterministic tests passed\n'
