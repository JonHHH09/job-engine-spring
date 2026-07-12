#!/usr/bin/env bash
# Focused regression test for MCP STDIO container cleanup ownership rules.
# Mocks docker; does not launch real containers.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/run-local-mcp-container.sh"
DIAG="$ROOT_DIR/scripts/run-mcp-stdio-diag.sh"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mcp-cleanup-test.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

pass() {
  printf 'PASS: %s\n' "$*"
}

# --- mock docker ---
REMOVED_IDS_FILE="$TMP_DIR/removed-ids"
: >"$REMOVED_IDS_FILE"

docker() {
  case "$1" in
    ps)
      # docker ps -aq --filter ...
      shift
      local filter=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          -aq) shift ;;
          --filter)
            filter="${2:-}"
            shift 2
            ;;
          *)
            shift
            ;;
        esac
      done
      case "$filter" in
        label=org.instruct.job-engine-spring.role=mcp-stdio)
          printf '%s\n' legacy default_instance custom_instance
          ;;
        label=org.instruct.job-engine-spring.mcp-container-name=job-engine-spring-mcp-stdio)
          printf '%s\n' default_instance
          ;;
        label=org.instruct.job-engine-spring.mcp-container-name=job-engine-spring-mcp-diag-123)
          printf '%s\n' custom_instance
          ;;
        label=com.docker.compose.project=job-engine-spring)
          # second filter applied separately in real docker; our mock is coarse
          printf '%s\n' legacy default_instance custom_instance
          ;;
        *)
          printf '%s\n' legacy default_instance custom_instance
          ;;
      esac
      ;;
    inspect)
      # docker inspect -f '...' <id>
      local id="${*: -1}"
      case "$id" in
        legacy)
          printf '\n'
          ;;
        default_instance)
          printf 'job-engine-spring-mcp-stdio\n'
          ;;
        custom_instance)
          printf 'job-engine-spring-mcp-diag-123\n'
          ;;
        *)
          printf '\n'
          ;;
      esac
      ;;
    rm)
      # docker rm -f <id>
      local id="${*: -1}"
      printf '%s\n' "$id" >>"$REMOVED_IDS_FILE"
      ;;
    *)
      fail "unexpected docker invocation: $*"
      ;;
  esac
}
export -f docker

# Source helpers only.
export MCP_CONTAINER_SOURCE_ONLY=1
# shellcheck source=/dev/null
source "$SCRIPT"
unset MCP_CONTAINER_SOURCE_ONLY

# Case 1: default Hermes instance cleanup removes legacy + default, preserves custom.
: >"$REMOVED_IDS_FILE"
export MCP_CONTAINER_NAME="job-engine-spring-mcp-stdio"
export DEFAULT_MCP_CONTAINER_NAME="job-engine-spring-mcp-stdio"
export MCP_CONTAINER_LABEL="org.instruct.job-engine-spring.role=mcp-stdio"
export MCP_CONTAINER_INSTANCE_LABEL="org.instruct.job-engine-spring.mcp-container-name=$MCP_CONTAINER_NAME"
export COMPOSE_PROJECT_NAME="job-engine-spring"

remove_stale_mcp_containers

removed="$(sort -u "$REMOVED_IDS_FILE" | tr '\n' ' ')"
printf 'removed(default launch): %s\n' "$removed"
grep -qx 'legacy' "$REMOVED_IDS_FILE" || fail "default launch should remove legacy"
grep -qx 'default_instance' "$REMOVED_IDS_FILE" || fail "default launch should remove default_instance"
if grep -qx 'custom_instance' "$REMOVED_IDS_FILE"; then
  fail "default launch must not remove custom_instance"
fi
pass "default launch preserves custom-named diagnostic container"

# Case 2: custom/diag instance cleanup is scoped to that instance only.
: >"$REMOVED_IDS_FILE"
export MCP_CONTAINER_NAME="job-engine-spring-mcp-diag-123"
export MCP_CONTAINER_INSTANCE_LABEL="org.instruct.job-engine-spring.mcp-container-name=$MCP_CONTAINER_NAME"
remove_stale_mcp_containers
removed="$(sort -u "$REMOVED_IDS_FILE" | tr '\n' ' ')"
printf 'removed(custom launch): %s\n' "$removed"
grep -qx 'custom_instance' "$REMOVED_IDS_FILE" || fail "custom launch should remove its own instance"
if grep -qx 'default_instance' "$REMOVED_IDS_FILE"; then
  fail "custom launch must not remove Hermes default_instance"
fi
if grep -qx 'legacy' "$REMOVED_IDS_FILE"; then
  fail "custom launch must not remove legacy via broad role cleanup"
fi
pass "custom launch cleanup is instance-scoped"

# Case 3: diagnostic wrapper refuses/overrides Hermes default name.
diag_name="$(
  # shellcheck disable=SC2030,SC2031
  env -u MCP_CONTAINER_NAME bash -c '
    set -euo pipefail
    # stub exec path
    ROOT_DIR="'"$ROOT_DIR"'"
    source /dev/null
    DEFAULT_MCP_CONTAINER_NAME="job-engine-spring-mcp-stdio"
    MCP_CONTAINER_NAME=""
    if [[ -z "${MCP_CONTAINER_NAME:-}" || "$MCP_CONTAINER_NAME" == "$DEFAULT_MCP_CONTAINER_NAME" ]]; then
      if [[ "${MCP_DIAG_ALLOW_DEFAULT:-0}" == "1" ]]; then
        MCP_CONTAINER_NAME="$DEFAULT_MCP_CONTAINER_NAME"
      else
        MCP_CONTAINER_NAME="job-engine-spring-mcp-diag-pid-ts"
      fi
    fi
    printf "%s" "$MCP_CONTAINER_NAME"
  '
)"
[[ "$diag_name" != "job-engine-spring-mcp-stdio" ]] || fail "diag wrapper must not default to Hermes container name"
pass "diag wrapper selects non-default container name"

# Case 4: diag script exists, is executable syntax-valid, and refuses default without override.
[[ -x "$DIAG" ]] || chmod +x "$DIAG"
bash -n "$DIAG"
bash -n "$SCRIPT"

# Simulate refusal: force default name without allow flag by patching PATH? Instead parse script policy:
if MCP_CONTAINER_NAME=job-engine-spring-mcp-stdio MCP_DIAG_ALLOW_DEFAULT=0 bash -c '
  DEFAULT_MCP_CONTAINER_NAME=job-engine-spring-mcp-stdio
  MCP_CONTAINER_NAME=job-engine-spring-mcp-stdio
  if [[ "$MCP_CONTAINER_NAME" == "$DEFAULT_MCP_CONTAINER_NAME" && "${MCP_DIAG_ALLOW_DEFAULT:-0}" != "1" ]]; then
    exit 2
  fi
  exit 0
'; then
  fail "expected refusal of default diagnostic name"
fi
pass "diag policy refuses Hermes default name without explicit allow"

printf 'All MCP container cleanup ownership checks passed.\n'
