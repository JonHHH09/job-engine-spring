#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$ROOT_DIR/target/job-engine-spring-0.0.1-SNAPSHOT.jar"
BUILD_LOG="${MCP_BUILD_LOG:-$ROOT_DIR/target/local-mcp-build.log}"

cd "$ROOT_DIR"

if [[ ! -f "$JAR" ]] || find pom.xml src scripts/rebuild-local-mcp-jar.sh -newer "$JAR" | grep -q .; then
  RUN_TESTS="${RUN_TESTS:-false}" ./scripts/rebuild-local-mcp-jar.sh >"$BUILD_LOG" 2>&1
fi

exec java -jar "$JAR"
