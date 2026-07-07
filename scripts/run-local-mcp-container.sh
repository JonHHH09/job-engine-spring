#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-job-engine-spring}"
MCP_IMAGE="${MCP_IMAGE:-job-engine-spring:local}"
MCP_CONTAINER_BUILD="${MCP_CONTAINER_BUILD:-missing}"
POSTGRES_DB="${JOB_ENGINE_POSTGRES_DB:-job_engine}"
POSTGRES_USER="${JOB_ENGINE_POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${JOB_ENGINE_POSTGRES_PASSWORD:-postgres}"
DOCUMENT_IMPORT_ROOT="$ROOT_DIR/tmp/imports"
GENERATED_PDF_ROOT="$ROOT_DIR/tmp/generated-pdfs"

export COMPOSE_PROJECT_NAME MCP_IMAGE JOB_ENGINE_POSTGRES_DB="$POSTGRES_DB" JOB_ENGINE_POSTGRES_USER="$POSTGRES_USER" JOB_ENGINE_POSTGRES_PASSWORD="$POSTGRES_PASSWORD"

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
  --network "$NETWORK_NAME" \
  --env SPRING_DOCKER_COMPOSE_ENABLED=false \
  --env JOB_ENGINE_POSTGRES_URL="jdbc:postgresql://$POSTGRES_HOST:5432/$POSTGRES_DB" \
  --env JOB_ENGINE_POSTGRES_USER="$POSTGRES_USER" \
  --env JOB_ENGINE_POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --env JOB_ENGINE_DOCUMENT_IMPORT_ROOT=/app/tmp/imports \
  --env JOB_ENGINE_JOB_ALLOWED_HOSTS="${JOB_ENGINE_JOB_ALLOWED_HOSTS:-}" \
  --volume "$DOCUMENT_IMPORT_ROOT:/app/tmp/imports:ro" \
  --volume "$GENERATED_PDF_ROOT:/app/tmp/generated-pdfs" \
  "$MCP_IMAGE"
