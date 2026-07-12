#!/usr/bin/env bash
# Diagnostic / ad-hoc STDIO MCP launcher that never uses Hermes' default container name.
#
# Why: the default STDIO name is reserved for exclusive CI/package verification.
# A unique diagnostic name prevents concurrent STDIO checks from killing each other
# and remains isolated from the persistent Streamable HTTP Compose service.
#
# Usage:
#   ./scripts/run-mcp-stdio-diag.sh
#   MCP_CONTAINER_BUILD=never ./scripts/run-mcp-stdio-diag.sh
#   python3 scripts/smoke-mcp-stdio.py -- ./scripts/run-mcp-stdio-diag.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_MCP_CONTAINER_NAME="job-engine-spring-mcp-stdio"

# Prefer unique names always. Even if a caller exports the Hermes default name,
# refuse unless they explicitly opt in (should almost never happen).
if [[ -z "${MCP_CONTAINER_NAME:-}" || "$MCP_CONTAINER_NAME" == "$DEFAULT_MCP_CONTAINER_NAME" ]]; then
  if [[ "${MCP_DIAG_ALLOW_DEFAULT:-0}" == "1" ]]; then
    printf 'WARNING: MCP_DIAG_ALLOW_DEFAULT=1 keeps the Hermes default MCP container name; this will kill an active Hermes STDIO session.\n' >&2
    export MCP_CONTAINER_NAME="$DEFAULT_MCP_CONTAINER_NAME"
  else
    # Assign then export so shellcheck does not warn about masking return values (SC2155).
    MCP_CONTAINER_NAME="job-engine-spring-mcp-diag-$$-$(date +%s)"
    export MCP_CONTAINER_NAME
  fi
fi

if [[ "$MCP_CONTAINER_NAME" == "$DEFAULT_MCP_CONTAINER_NAME" && "${MCP_DIAG_ALLOW_DEFAULT:-0}" != "1" ]]; then
  printf 'Refusing diagnostic launch with Hermes default MCP_CONTAINER_NAME=%s. Unset it or set MCP_DIAG_ALLOW_DEFAULT=1 intentionally.\n' \
    "$MCP_CONTAINER_NAME" >&2
  exit 2
fi

# Diagnosis should never rebuild by default; callers can still override.
export MCP_CONTAINER_BUILD="${MCP_CONTAINER_BUILD:-never}"

printf 'job-engine-spring MCP diagnostic launcher: MCP_CONTAINER_NAME=%s MCP_CONTAINER_BUILD=%s\n' \
  "$MCP_CONTAINER_NAME" "$MCP_CONTAINER_BUILD" >&2

exec "$ROOT_DIR/scripts/run-local-mcp-container.sh"
