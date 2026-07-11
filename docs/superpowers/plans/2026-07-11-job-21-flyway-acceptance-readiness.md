# JOB-21 Flyway Acceptance Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Docker-backed PostgreSQL recovery acceptance harness wait for completed MCP/Flyway startup before seeding and backing up the source database.

**Architecture:** Replace the detached MCP container plus `flyway_schema_history` existence poll with the repository's existing bounded MCP STDIO smoke helper. A successful JSON-RPC `initialize` and `tools/list` exchange becomes the readiness boundary; production backup and restore scripts remain unchanged.

**Tech Stack:** Bash, Python 3 MCP smoke helper, Docker Compose, Maven/Java 25.

## Global Constraints

- Work in the primary checkout on the Linear-generated JOB-21 branch; do not create another worktree.
- Change only the acceptance harness and its deterministic regression coverage.
- Preserve uniquely owned Compose projects and cleanup behavior.
- Timeout, early MCP exit, and malformed JSON-RPC must fail closed without emitting private data.
- Require two consecutive Docker-backed acceptance passes before integration into PR #61.

---

### Task 1: Replace the Flyway table heuristic with MCP readiness

**Files:**
- Modify: `scripts/tests/postgres-ops-test.sh`
- Modify: `scripts/tests/postgres-ops-integration.sh`

**Interfaces:**
- Consumes: `python3 scripts/smoke-mcp-stdio.py --timeout 120 -- <MCP command>` and the existing `MCP_IMAGE`/`COMPOSE_PROJECT_NAME` Compose environment.
- Produces: a source-database readiness gate that returns only after the exact immutable MCP image completes JSON-RPC initialization and tool discovery.

- [ ] **Step 1: Write the failing deterministic regression**

Add source-contract assertions to `scripts/tests/postgres-ops-test.sh`:

```bash
integration_script="$ROOT_DIR/scripts/tests/postgres-ops-integration.sh"
if ! grep -Fq 'scripts/smoke-mcp-stdio.py' "$integration_script"; then
  printf 'FAIL: acceptance does not use MCP readiness smoke\n' >&2
  failures=$((failures + 1))
fi
if grep -Fq "to_regclass('profile.flyway_schema_history')" "$integration_script"; then
  printf 'FAIL: acceptance still uses Flyway table existence as readiness\n' >&2
  failures=$((failures + 1))
fi
```

- [ ] **Step 2: Run the regression and verify RED**

Run: `bash scripts/tests/run-postgres-ops-tests.sh`

Expected: exit 1 with both readiness assertions failing against the current harness.

- [ ] **Step 3: Implement the minimal readiness change**

Replace the detached `docker compose run -d`, schema-table polling loop, and `docker rm -f` with:

```bash
MCP_IMAGE="$immutable_mcp_image" COMPOSE_PROJECT_NAME="$primary_project" \
  python3 "$ROOT_DIR/scripts/smoke-mcp-stdio.py" --timeout 120 -- \
  docker compose --profile manual-mcp -p "$primary_project" run --rm -T mcp
```

Run it from `$ROOT_DIR` so Compose resolves the repository configuration. Keep stdout reserved for the smoke helper's normal sanitized result and allow any nonzero exit to stop the acceptance script through `set -e`.

- [ ] **Step 4: Verify GREEN and static quality**

Run:

```bash
bash scripts/tests/run-postgres-ops-tests.sh
shellcheck scripts/tests/postgres-ops-test.sh scripts/tests/postgres-ops-integration.sh
git diff --check
```

Expected: deterministic tests pass, ShellCheck has no findings, and `git diff --check` exits 0.

- [ ] **Step 5: Verify runtime behavior twice**

Build and run:

```bash
docker build -t job-engine-spring:ci .
POSTGRES_OPS_TEST_MODE=1 MCP_IMAGE=job-engine-spring:ci bash scripts/tests/postgres-ops-integration.sh
POSTGRES_OPS_TEST_MODE=1 MCP_IMAGE=job-engine-spring:ci bash scripts/tests/postgres-ops-integration.sh
./mvnw test
```

Expected: both Docker runs print `postgres backup acceptance passed`; Maven prints `Tests run: 300, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Review and commit**

Stage only the plan, acceptance harness, and deterministic test:

```bash
git add docs/superpowers/plans/2026-07-11-job-21-flyway-acceptance-readiness.md scripts/tests/postgres-ops-test.sh scripts/tests/postgres-ops-integration.sh
git commit -m "fix(ops): wait for MCP recovery readiness"
```

Expected: one conventional commit on the JOB-21 branch with no unrelated files.

### Task 2: Integrate into the existing PR and verify GitHub

**Files:**
- No additional source files.

**Interfaces:**
- Consumes: the verified JOB-21 implementation commit.
- Produces: PR #61 head branch `job-20-postgres-backup-recovery` containing the JOB-21 design and implementation commits.

- [ ] **Step 1: Switch the primary checkout to the PR branch and merge JOB-21**

```bash
git switch job-20-postgres-backup-recovery
git merge --no-ff jhysaj2000/job-21-stabilize-postgresql-recovery-acceptance-after-flyway
```

Expected: a clean merge containing only JOB-21 documentation, test, and harness changes.

- [ ] **Step 2: Push and monitor PR #61**

```bash
git push origin job-20-postgres-backup-recovery
gh pr checks 61 --watch --interval 10
```

Expected: required PR checks complete successfully.

- [ ] **Step 3: Update Linear and report readiness**

Mark JOB-21 complete only after the pushed PR head is cleanly mergeable and required checks pass. Report skipped GitHub jobs separately and cite the two local Docker acceptance passes as their replacement evidence.
