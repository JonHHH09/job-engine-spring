#!/usr/bin/env bash
# Docker-free regression for scripts/bootstrap-local-env.sh.
# Verifies default-on behavior, token generation, idempotence, and that an
# explicitly disabled or already-strong configuration is preserved.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/bootstrap-local-env.sh"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bootstrap-env-test.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

pass() {
  printf 'PASS: %s\n' "$*"
}

env_value() {
  local key="$1"
  local file="$2"
  local line
  line="$(grep -E "^${key}=" "$file" | tail -n 1 || true)"
  printf '%s' "$(printf '%s' "${line#"${key}"=}" | tr -d '\r')"
}

run_bootstrap() {
  JOB_ENGINE_ENV_FILE="$1" "$SCRIPT" >/dev/null 2>&1
}

# --- creates a minimal .env and enables the operator boundary ---
ENV_FILE="$TMP_DIR/created.env"
run_bootstrap "$ENV_FILE"
[[ -f "$ENV_FILE" ]] || fail 'bootstrap did not create the env file'
[[ "$(env_value JOB_ENGINE_OPERATOR_ENABLED "$ENV_FILE")" == "true" ]] \
  || fail 'operator boundary was not enabled by default'
TOKEN="$(env_value JOB_ENGINE_OPERATOR_BEARER_TOKEN "$ENV_FILE")"
[[ "${#TOKEN}" -ge 32 ]] || fail "generated token is shorter than 32 characters (${#TOKEN})"
if grep -qE '^JOB_ENGINE_POSTGRES_(DB|USER|PASSWORD)=' "$ENV_FILE"; then
  fail 'bootstrap wrote database credentials; that would break an existing postgres volume'
fi
[[ "$(grep -cE '^JOB_ENGINE_OPERATOR_ENABLED=' "$ENV_FILE")" -eq 1 ]] \
  || fail 'duplicate JOB_ENGINE_OPERATOR_ENABLED entries were written'
pass 'creates a minimal .env with the operator boundary enabled and a strong token'

# --- preserves unrelated keys already present in .env ---
KEEP_FILE="$TMP_DIR/keep.env"
printf 'JOB_ENGINE_POSTGRES_PASSWORD=existing-secret\nJOB_ENGINE_MCP_PORT=9090\n' >"$KEEP_FILE"
run_bootstrap "$KEEP_FILE"
[[ "$(env_value JOB_ENGINE_POSTGRES_PASSWORD "$KEEP_FILE")" == "existing-secret" ]] \
  || fail 'an existing database password was modified'
[[ "$(env_value JOB_ENGINE_MCP_PORT "$KEEP_FILE")" == "9090" ]] \
  || fail 'an existing unrelated key was modified'
[[ "$(env_value JOB_ENGINE_OPERATOR_ENABLED "$KEEP_FILE")" == "true" ]] \
  || fail 'operator boundary was not enabled in an existing env file'
pass 'preserves unrelated keys in an existing env file'

# --- idempotent: a second run keeps the same strong token ---
run_bootstrap "$ENV_FILE"
[[ "$(env_value JOB_ENGINE_OPERATOR_BEARER_TOKEN "$ENV_FILE")" == "$TOKEN" ]] \
  || fail 'second run rotated an already-strong token'
[[ "$(env_value JOB_ENGINE_OPERATOR_ENABLED "$ENV_FILE")" == "true" ]] \
  || fail 'second run changed the enabled flag'
pass 'is idempotent for an already-configured env file'

# --- replaces a token that is too short ---
SHORT_FILE="$TMP_DIR/short.env"
printf 'JOB_ENGINE_OPERATOR_ENABLED=true\nJOB_ENGINE_OPERATOR_BEARER_TOKEN=too-short\n' >"$SHORT_FILE"
run_bootstrap "$SHORT_FILE"
SHORT_TOKEN="$(env_value JOB_ENGINE_OPERATOR_BEARER_TOKEN "$SHORT_FILE")"
[[ "${#SHORT_TOKEN}" -ge 32 ]] || fail 'short token was not replaced'
pass 'replaces a token shorter than 32 characters'

# --- respects an explicit opt-out ---
OPTOUT_FILE="$TMP_DIR/optout.env"
printf 'JOB_ENGINE_OPERATOR_ENABLED=disabled\nJOB_ENGINE_OPERATOR_BEARER_TOKEN=%s\n' "$TOKEN" >"$OPTOUT_FILE"
run_bootstrap "$OPTOUT_FILE"
[[ "$(env_value JOB_ENGINE_OPERATOR_ENABLED "$OPTOUT_FILE")" == "disabled" ]] \
  || fail 'explicit non-default operator setting was overwritten'
pass 'preserves an explicitly chosen non-default operator setting'

# --- never prints the token ---
PRINT_FILE="$TMP_DIR/print.env"
OUTPUT="$(JOB_ENGINE_ENV_FILE="$PRINT_FILE" "$SCRIPT" 2>&1)"
PRINT_TOKEN="$(env_value JOB_ENGINE_OPERATOR_BEARER_TOKEN "$PRINT_FILE")"
if printf '%s' "$OUTPUT" | grep -qF "$PRINT_TOKEN"; then
  fail 'bootstrap leaked the generated token to its output'
fi
pass 'never prints the generated token'

printf 'All bootstrap-local-env regressions passed.\n'
