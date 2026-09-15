#!/usr/bin/env bash
# Build-from-source launcher for the persistent local deployment.
# Bootstraps the ignored .env (operator MVC boundary enabled with a private token),
# then builds and starts PostgreSQL plus the Streamable HTTP MCP service.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JOB_ENGINE_SKIP_ENV_BOOTSTRAP="${JOB_ENGINE_SKIP_ENV_BOOTSTRAP:-false}"
if [[ "$JOB_ENGINE_SKIP_ENV_BOOTSTRAP" != "true" ]]; then
  ./scripts/bootstrap-local-env.sh
fi

docker compose up -d --build --force-recreate --wait postgres mcp
printf 'Persistent job-engine-spring MCP is ready at http://127.0.0.1:%s/mcp\n' \
  "${JOB_ENGINE_MCP_PORT:-8080}"
printf 'Local operator boundary is ready at http://127.0.0.1:%s/operator/ (API token in .env)\n' \
  "${JOB_ENGINE_MCP_PORT:-8080}"
