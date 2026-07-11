#!/usr/bin/env bash
# Docker-backed acceptance for backup/restore isolation. Requires an explicit immutable MCP image for full verification.
set -euo pipefail
umask 077
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
backup_root="$(mktemp -d)"
[[ "${POSTGRES_OPS_TEST_MODE:-}" == 1 ]] || { printf 'acceptance requires POSTGRES_OPS_TEST_MODE=1\n' >&2; exit 2; }
[[ -n "${MCP_IMAGE:-}" ]] || { printf 'acceptance requires MCP_IMAGE\n' >&2; exit 2; }
immutable_mcp_image="$(docker image inspect -f '{{.Id}}' "$MCP_IMAGE" 2>/dev/null)"
[[ "$immutable_mcp_image" =~ ^sha256:[a-f0-9]{64}$ ]] || { printf 'acceptance MCP image is unavailable\n' >&2; exit 2; }
primary_project="job-engine-backup-acceptance-source-$(date -u +%s)-$RANDOM"
target_project="job-engine-backup-acceptance-$(date -u +%s)-$RANDOM"
target_volume="${target_project}_postgres-data"
cleanup() {
  (cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$target_project" docker compose down -v --remove-orphans >/dev/null 2>&1) || true
  (cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$primary_project" docker compose down -v --remove-orphans >/dev/null 2>&1) || true
  rm -rf "$backup_root"
}
trap cleanup EXIT INT TERM

(cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$primary_project" docker compose up -d --wait postgres >/dev/null)
# Run the MCP long enough for Flyway against the disposable source, then remove only that run container.
mcp_container="$(cd "$ROOT_DIR" && MCP_IMAGE="$immutable_mcp_image" COMPOSE_PROJECT_NAME="$primary_project" docker compose --profile manual-mcp -p "$primary_project" run -d -T mcp)"
source_container="$(cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$primary_project" docker compose ps -q postgres)"
for _ in $(seq 1 60); do
  if docker exec -i -u postgres "$source_container" psql -X -At -d "${JOB_ENGINE_POSTGRES_DB:-job_engine}" -c "SELECT to_regclass('profile.flyway_schema_history') IS NOT NULL" 2>/dev/null | grep -qx t; then
    break
  fi
  sleep 1
done
docker exec -i -u postgres "$source_container" psql -X -At -d "${JOB_ENGINE_POSTGRES_DB:-job_engine}" -c "SELECT to_regclass('profile.flyway_schema_history') IS NOT NULL" | grep -qx t || { printf 'disposable source schema was not initialized\n' >&2; exit 1; }
docker rm -f "$mcp_container" >/dev/null
docker exec -i -u postgres "$source_container" psql -X -v ON_ERROR_STOP=1 -d "${JOB_ENGINE_POSTGRES_DB:-job_engine}" >/dev/null <<'SQL'
BEGIN;
INSERT INTO profile.profiles (id, full_name, email, summary, created_at, updated_at)
VALUES ('10000000-0000-0000-0000-000000000001', 'Recovery Fixture', 'recovery-fixture@example.invalid', 'Disposable acceptance fixture', now(), now());
INSERT INTO document.blobs (id, sha256, byte_size, content, created_at)
VALUES ('20000000-0000-0000-0000-000000000001', '39607063c304f56d1a35640a7bfd7af491be39363c33a10106d776481980b397', 13, decode('89504e470d0a1a0a00010203ff', 'hex'), now());
INSERT INTO document.documents (id, blob_id, original_file_name, media_type, created_at, updated_at)
VALUES ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'recovery-fixture.bin', 'application/octet-stream', now(), now());
INSERT INTO job_schema.jobs (id, source_method, source_label, title, company, description, canonical_fingerprint, created_at, updated_at)
VALUES ('40000000-0000-0000-0000-000000000001', 'text', 'acceptance', 'Recovery Fixture Role', 'Example Organization', 'Disposable recovery acceptance fixture', '83a370ae54bfdf1461ea399270d92c0ad84803fb29aa699cd3d0a5a02e0444d4', now(), now());
INSERT INTO job_schema.job_text_ingestions (id, job_id, source_label, input_text_hash, created_at)
VALUES ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'acceptance', '49fde1db85fcefdf953f35b42423e8d98d85cba91562d85384d0a07d1a5345a3', now());
COMMIT;
SQL
POSTGRES_OPS_TEST_MODE=1 POSTGRES_OPS_TEST_PRIMARY_PROJECT="$primary_project" "$ROOT_DIR/scripts/postgres-backup.sh" --backup-root "$backup_root"
backup_set="$(find "$backup_root" -mindepth 1 -maxdepth 1 -type d -name '*Z-*' -print -quit)"
[[ -n "$backup_set" ]] || { printf 'backup was not atomically published\n' >&2; exit 1; }
(cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$target_project" docker compose up -d --wait postgres >/dev/null)
COMPOSE_PROJECT_NAME="$primary_project" "$ROOT_DIR/scripts/postgres-restore.sh" --backup-set "$backup_set" --target-project "$target_project" --target-volume "$target_volume" --database "${JOB_ENGINE_POSTGRES_DB:-job_engine}" --confirm RESTORE --confirm-existing OVERWRITE
target_container="$(cd "$ROOT_DIR" && COMPOSE_PROJECT_NAME="$target_project" docker compose ps -q postgres)"
before="$(docker exec -i -u postgres "$target_container" psql -X -At -d "${JOB_ENGINE_POSTGRES_DB:-job_engine}" -c 'SELECT count(*) FROM profile.flyway_schema_history')"
docker restart "$target_container" >/dev/null
until [[ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$target_container")" == healthy ]]; do sleep 1; done
after="$(docker exec -i -u postgres "$target_container" psql -X -At -d "${JOB_ENGINE_POSTGRES_DB:-job_engine}" -c 'SELECT count(*) FROM profile.flyway_schema_history')"
[[ "$before" == "$after" ]] || { printf 'restored data did not persist restart\n' >&2; exit 1; }
POSTGRES_OPS_TEST_MODE=1 POSTGRES_OPS_TEST_PRIMARY_PROJECT="$primary_project" \
  "$ROOT_DIR/scripts/postgres-verify-backup.sh" --backup-set "$backup_set" --image "$immutable_mcp_image"
printf 'postgres backup acceptance passed\n'
