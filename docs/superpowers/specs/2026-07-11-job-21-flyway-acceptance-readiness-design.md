# JOB-21 Flyway Acceptance Readiness Design

## Goal

Remove the startup race from the disposable PostgreSQL recovery acceptance harness so it never backs up a source database before the exact MCP image has completed Spring and Flyway initialization.

## Current failure

`scripts/tests/postgres-ops-integration.sh` starts the source MCP container in detached mode and stops it as soon as `profile.flyway_schema_history` exists. Flyway creates that table before all migration work is necessarily complete. A backup taken at that boundary can later gain a different Flyway fingerprint when the same image starts against the restored database, producing the intermittent failure `protected state changed during verification: tables=none flyway=true`.

## Design

Run the existing `scripts/smoke-mcp-stdio.py` helper against `docker compose run --rm -T mcp` in the uniquely owned source Compose project, with `MCP_IMAGE` set to the already resolved immutable image ID. A successful JSON-RPC `initialize` and `tools/list` response is the readiness boundary: Spring has started, Flyway has completed, and the MCP transport is operational.

The acceptance harness will no longer create, poll, and forcibly remove a detached MCP container. The smoke helper owns the bounded process lifecycle and returns nonzero on timeout, early exit, malformed JSON-RPC, or tool-list failure. Its sanitized error behavior remains unchanged.

## Testing

- Add a deterministic source check that the integration harness invokes the smoke helper for readiness and no longer uses `to_regclass('profile.flyway_schema_history')` as its gate.
- Run the shell and Python operational tests plus ShellCheck.
- Build the exact local MCP image and require two consecutive disposable PostgreSQL backup/restore acceptance runs to pass.
- Run `./mvnw test`, then integrate the verified commit into PR #61 and monitor its GitHub checks.

## Scope

Only the acceptance harness and its deterministic regression coverage change. Production backup, restore, verification, pruning, Compose, and application behavior remain unchanged.
