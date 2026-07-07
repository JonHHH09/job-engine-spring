#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAR="$ROOT_DIR/target/job-engine-spring-0.0.1-SNAPSHOT.jar"
JAR_REL="target/job-engine-spring-0.0.1-SNAPSHOT.jar"
MCP_SERVER_NAME="${MCP_SERVER_NAME:-job-engine-spring}"
RUN_TESTS="${RUN_TESTS:-false}"
MODE="restart"
SMOKE_TEST="${SMOKE_TEST:-false}"

usage() {
  cat <<EOF
Usage: $0 [--foreground] [--run-tests] [--smoke-test]

Restart helper for the local job-engine-spring STDIO MCP server.

Options:
  --foreground  Exec the STDIO MCP server in this shell after preparing the jar.
  --run-tests   Run unit tests before packaging.
  --smoke-test  Run 'hermes mcp test' after restart when Hermes is available.
  -h, --help    Show this help.

Default mode stops stale local jar processes and rebuilds the jar.
For an already-running MCP client, run its reload command after this script.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --foreground)
      MODE="foreground"
      ;;
    --run-tests)
      RUN_TESTS="true"
      ;;
    --smoke-test)
      SMOKE_TEST="true"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

PIDS=()
while read -r pid command; do
  if [[ -z "${pid:-}" || -z "${command:-}" ]]; then
    continue
  fi
  if [[ "$command" == *"java"* && "$command" == *"-jar"* && ( "$command" == *"$JAR"* || "$command" == *"$JAR_REL"* ) ]]; then
    PIDS+=("$pid")
  fi
done < <(ps -axo pid=,command=)
if [[ "${#PIDS[@]}" -gt 0 ]]; then
  printf 'Stopping stale %s process(es): %s\n' "$MCP_SERVER_NAME" "${PIDS[*]}" >&2
  kill "${PIDS[@]}"
  for pid in "${PIDS[@]}"; do
    for _ in {1..20}; do
      if ! kill -0 "$pid" 2>/dev/null; then
        break
      fi
      sleep 0.25
    done
    if kill -0 "$pid" 2>/dev/null; then
      printf 'Process %s is still running after SIGTERM.\n' "$pid" >&2
    fi
  done
fi

printf 'Packaging %s (RUN_TESTS=%s)\n' "$MCP_SERVER_NAME" "$RUN_TESTS" >&2
RUN_TESTS="$RUN_TESTS" RUN_MCP_TEST=false MCP_SERVER_NAME="$MCP_SERVER_NAME" ./scripts/rebuild-local-mcp-jar.sh

if [[ "$MODE" == "foreground" ]]; then
  printf 'Starting %s in foreground STDIO mode.\n' "$MCP_SERVER_NAME" >&2
  exec java -jar "$JAR"
fi

if [[ "$SMOKE_TEST" == "true" ]] && command -v hermes >/dev/null 2>&1; then
  printf 'Running Hermes MCP smoke test for %s.\n' "$MCP_SERVER_NAME" >&2
  hermes mcp test "$MCP_SERVER_NAME"
elif [[ "$SMOKE_TEST" == "true" ]]; then
  printf 'Hermes CLI not found; skipped MCP smoke test.\n' >&2
fi

cat <<EOF

Prepared local STDIO MCP jar: $JAR
Stopped any stale matching Java subprocesses.
Reload the MCP client now, for example /reload-mcp in Hermes or restart the Codex session using this repo config.
EOF
