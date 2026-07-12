#!/usr/bin/env bash
# Deploy a published job-engine-spring release as the persistent local HTTP MCP service.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MCP_IMAGE="${1:-${MCP_IMAGE:-}}"
if [[ ! "$MCP_IMAGE" =~ ^ghcr\.io/jonhhh09/job-engine-spring:(v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?)$ \
      && ! "$MCP_IMAGE" =~ ^ghcr\.io/jonhhh09/job-engine-spring@sha256:[0-9a-f]{64}$ ]]; then
  printf 'Usage: %s ghcr.io/jonhhh09/job-engine-spring:vMAJOR.MINOR.PATCH|@sha256:DIGEST\n' "${0##*/}" >&2
  exit 2
fi

export MCP_IMAGE
docker pull "$MCP_IMAGE"
docker compose up -d --no-build --force-recreate --wait postgres mcp
printf 'Persistent job-engine-spring MCP is ready at http://127.0.0.1:%s/mcp\n' \
  "${JOB_ENGINE_MCP_PORT:-8080}"
