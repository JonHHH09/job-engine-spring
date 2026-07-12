#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-job-engine-spring}"
MCP_IMAGE="${MCP_IMAGE:-job-engine-spring:local}"
DEFAULT_MCP_CONTAINER_NAME="job-engine-spring-mcp-stdio"
MCP_CONTAINER_NAME="${MCP_CONTAINER_NAME:-$DEFAULT_MCP_CONTAINER_NAME}"
MCP_CONTAINER_LABEL="org.instruct.job-engine-spring.role=mcp-stdio"
MCP_CONTAINER_INSTANCE_LABEL="org.instruct.job-engine-spring.mcp-container-name=$MCP_CONTAINER_NAME"
MCP_CONTAINER_BUILD="${MCP_CONTAINER_BUILD:-missing}"
POSTGRES_DB="${JOB_ENGINE_POSTGRES_DB:-job_engine}"
POSTGRES_USER="${JOB_ENGINE_POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${JOB_ENGINE_POSTGRES_PASSWORD:-postgres}"
DOCUMENT_IMPORT_ROOT="$ROOT_DIR/tmp/imports"
GENERATED_PDF_ROOT="$ROOT_DIR/tmp/generated-pdfs"

export COMPOSE_PROJECT_NAME MCP_IMAGE JOB_ENGINE_POSTGRES_DB="$POSTGRES_DB" JOB_ENGINE_POSTGRES_USER="$POSTGRES_USER" JOB_ENGINE_POSTGRES_PASSWORD="$POSTGRES_PASSWORD"

remove_container_id() {
  local container_id="$1"
  [[ -n "$container_id" ]] && docker rm -f "$container_id" >/dev/null 2>&1 || true
}

remove_containers() {
  local container_ids
  container_ids="$(docker ps -aq "$@" 2>/dev/null || true)"
  if [[ -n "$container_ids" ]]; then
    while IFS= read -r container_id; do
      remove_container_id "$container_id"
    done <<< "$container_ids"
  fi
}

container_instance_label() {
  local container_id="$1"
  local label
  label="$(docker inspect -f '{{ index .Config.Labels "org.instruct.job-engine-spring.mcp-container-name" }}' "$container_id" 2>/dev/null || true)"
  if [[ "$label" == "<no value>" ]]; then
    label=""
  fi
  printf '%s' "$label"
}

remove_containers_for_current_or_legacy_instance() {
  local container_ids
  container_ids="$(docker ps -aq "$@" 2>/dev/null || true)"
  if [[ -n "$container_ids" ]]; then
    while IFS= read -r container_id; do
      local instance_label
      instance_label="$(container_instance_label "$container_id")"
      if [[ -z "$instance_label" || "$instance_label" == "$MCP_CONTAINER_NAME" ]]; then
        remove_container_id "$container_id"
      fi
    done <<< "$container_ids"
  fi
}

remove_stale_mcp_containers() {
  docker rm -f "$MCP_CONTAINER_NAME" >/dev/null 2>&1 || true
  remove_containers --filter "label=$MCP_CONTAINER_INSTANCE_LABEL"

  if [[ "$MCP_CONTAINER_NAME" == "$DEFAULT_MCP_CONTAINER_NAME" ]]; then
    remove_containers_for_current_or_legacy_instance --filter "label=$MCP_CONTAINER_LABEL"
    remove_containers_for_current_or_legacy_instance \
      --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
      --filter "label=com.docker.compose.service=mcp"
  fi
}

# Allow focused unit tests to source helper functions without launching Docker.
if [[ "${MCP_CONTAINER_SOURCE_ONLY:-0}" == "1" ]]; then
  # shellcheck disable=SC2317 # Reached when tests source this file with MCP_CONTAINER_SOURCE_ONLY=1.
  return 0 2>/dev/null || exit 0
fi

mkdir -p "$DOCUMENT_IMPORT_ROOT" "$GENERATED_PDF_ROOT"

case "$MCP_CONTAINER_BUILD" in
  always)
    docker compose build mcp </dev/null >&2
    ;;
  missing)
    if ! docker image inspect "$MCP_IMAGE" >/dev/null 2>&1; then
      docker compose build mcp </dev/null >&2
    fi
    ;;
  never)
    ;;
  *)
    printf 'Invalid MCP_CONTAINER_BUILD=%s; expected always, missing, or never.\n' "$MCP_CONTAINER_BUILD" >&2
    exit 2
    ;;
esac

docker compose up -d --wait postgres </dev/null >&2
remove_stale_mcp_containers
POSTGRES_HOST="$(docker compose exec -T postgres hostname -i </dev/null | awk '{print $1}')"
if [[ -z "$POSTGRES_HOST" ]]; then
  printf 'Could not resolve PostgreSQL container IP.\n' >&2
  exit 1
fi

# shellcheck disable=SC2016 # Go template must be single-quoted so the shell does not expand Docker template variables.
NETWORK_NAME="$(docker compose ps -q postgres | xargs docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' | head -n 1)"
if [[ -z "$NETWORK_NAME" ]]; then
  printf 'Could not resolve Docker network for PostgreSQL container.\n' >&2
  exit 1
fi

exec docker run --rm -i \
  --name "$MCP_CONTAINER_NAME" \
  --label "$MCP_CONTAINER_LABEL" \
  --label "$MCP_CONTAINER_INSTANCE_LABEL" \
  --label "com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
  --label "com.docker.compose.service=mcp" \
  --network "$NETWORK_NAME" \
  --env SPRING_DOCKER_COMPOSE_ENABLED=false \
  --env JOB_ENGINE_POSTGRES_URL="jdbc:postgresql://$POSTGRES_HOST:5432/$POSTGRES_DB" \
  --env JOB_ENGINE_POSTGRES_USER="$POSTGRES_USER" \
  --env JOB_ENGINE_POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --env JOB_ENGINE_DOCUMENT_IMPORT_ROOT=/app/tmp/imports \
  --volume "$DOCUMENT_IMPORT_ROOT:/app/tmp/imports:ro" \
  --volume "$GENERATED_PDF_ROOT:/app/tmp/generated-pdfs" \
  "$MCP_IMAGE"
