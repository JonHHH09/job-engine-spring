#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

RUN_TESTS="${RUN_TESTS:-true}"
MCP_SERVER_NAME="${MCP_SERVER_NAME:-job-engine-spring}"
JAR_PATH="target/job-engine-spring-0.0.1-SNAPSHOT.jar"

if [[ "$RUN_TESTS" == "true" ]]; then
  ./mvnw test
fi

./mvnw -DskipTests package

test -f "$JAR_PATH"

if command -v hermes >/dev/null 2>&1; then
  hermes mcp test "$MCP_SERVER_NAME"
else
  printf 'Hermes CLI not found on PATH; built %s but skipped MCP connection test.\n' "$JAR_PATH" >&2
fi

cat <<EOF

Built local STDIO MCP jar: $ROOT_DIR/$JAR_PATH
If a Hermes session is already running, run /reload-mcp to reconnect to the rebuilt jar.
If tool names or schemas changed, start a fresh Hermes session with /reset after /reload-mcp.
EOF
