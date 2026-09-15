#!/usr/bin/env bash
# Prepare the ignored local .env so a fresh checkout starts the persistent MCP
# service with the local operator MVC boundary already enabled.
#
# The script is idempotent and never weakens an existing configuration:
#   - creates a minimal .env holding only the operator keys when .env is missing;
#   - sets JOB_ENGINE_OPERATOR_ENABLED=true unless the caller already chose a value;
#   - generates a JOB_ENGINE_OPERATOR_BEARER_TOKEN of at least 32 characters
#     when the existing value is missing or too short.
#
# It deliberately does NOT copy the rest of .env.example. Compose already supplies
# safe defaults for every other key, and writing the template's placeholder database
# password into a deployment whose postgres-data volume was initialized with the
# Compose default would break authentication for an existing local database.
# Copy .env.example yourself when you want to change those other values.
#
# The generated token is written only to the git-ignored .env file (mode 0600) and
# is never printed to stdout or stderr.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${JOB_ENGINE_ENV_FILE:-$ROOT_DIR/.env}"
MIN_TOKEN_LENGTH=32

log() {
  printf '%s\n' "$1" >&2
}

generate_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
    return 0
  fi
  for python_command in python3 python; do
    if command -v "$python_command" >/dev/null 2>&1; then
      "$python_command" -c 'import secrets; print(secrets.token_hex(32))'
      return 0
    fi
  done
  if [[ -r /dev/urandom ]] && command -v od >/dev/null 2>&1; then
    od -An -tx1 -N32 /dev/urandom | tr -d ' \n'
    printf '\n'
    return 0
  fi
  log 'No usable random source found (need openssl, python3/python, or /dev/urandom).'
  return 1
}

read_env_value() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)"
  printf '%s' "${line#"${key}"=}"
}

set_env_value() {
  local key="$1"
  local value="$2"
  local temp_file
  temp_file="$(mktemp "${TMPDIR:-/tmp}/job-engine-env.XXXXXX")"
  chmod 600 "$temp_file"
  local replaced=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^${key}= ]]; then
      if [[ "$replaced" -eq 0 ]]; then
        printf '%s=%s\n' "$key" "$value" >>"$temp_file"
        replaced=1
      fi
      continue
    fi
    printf '%s\n' "$line" >>"$temp_file"
  done <"$ENV_FILE"
  if [[ "$replaced" -eq 0 ]]; then
    printf '%s=%s\n' "$key" "$value" >>"$temp_file"
  fi
  cat "$temp_file" >"$ENV_FILE"
  rm -f "$temp_file"
}

if [[ ! -f "$ENV_FILE" ]]; then
  umask 077
  cat >"$ENV_FILE" <<'ENV_HEADER'
# Local-only environment overrides for job-engine-spring. Never commit this file.
# Created by scripts/bootstrap-local-env.sh with only the operator keys set; every
# other setting keeps its Compose default. See .env.example for the full key list.
ENV_HEADER
  log 'Created a minimal .env with only the local operator settings.'
fi
chmod 600 "$ENV_FILE" 2>/dev/null || true

# Strip trailing carriage returns so values copied on Windows still compare correctly.
CURRENT_ENABLED="$(read_env_value JOB_ENGINE_OPERATOR_ENABLED | tr -d '\r')"
CURRENT_TOKEN="$(read_env_value JOB_ENGINE_OPERATOR_BEARER_TOKEN | tr -d '\r')"

if [[ -z "$CURRENT_ENABLED" || "$CURRENT_ENABLED" == "false" ]]; then
  set_env_value JOB_ENGINE_OPERATOR_ENABLED true
  log 'Set JOB_ENGINE_OPERATOR_ENABLED=true in .env.'
else
  log "Kept existing JOB_ENGINE_OPERATOR_ENABLED=$CURRENT_ENABLED in .env."
fi

if [[ "${#CURRENT_TOKEN}" -lt "$MIN_TOKEN_LENGTH" ]]; then
  NEW_TOKEN="$(generate_token | tr -d '\r\n')"
  if [[ "${#NEW_TOKEN}" -lt "$MIN_TOKEN_LENGTH" ]]; then
    log 'Generated operator token was too short; aborting without changing .env.'
    exit 1
  fi
  set_env_value JOB_ENGINE_OPERATOR_BEARER_TOKEN "$NEW_TOKEN"
  log 'Generated a private JOB_ENGINE_OPERATOR_BEARER_TOKEN in .env (value not printed).'
else
  log 'Kept the existing JOB_ENGINE_OPERATOR_BEARER_TOKEN in .env.'
fi

log 'Local operator boundary is configured. Read the token with: grep JOB_ENGINE_OPERATOR_BEARER_TOKEN .env'
