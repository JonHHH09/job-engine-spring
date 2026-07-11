#!/usr/bin/env bash
# Preview or prune only checksum-valid, released-image-verified backup sets.
set -euo pipefail
umask 077
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/postgres-ops-common.sh"

usage() { printf 'Usage: %s [--backup-root PATH] [--daily N] [--weekly N] [--monthly N] [--confirm PRUNE]\n' "${0##*/}" >&2; }
requested_root="${JOB_ENGINE_BACKUP_ROOT:-$ROOT_DIR/backups/postgres}"; daily=7; weekly=4; monthly=12; confirmation=""
while (($#)); do
  case "$1" in
    --backup-root) requested_root="${2:-}"; shift 2 ;;
    --daily) daily="${2:-}"; shift 2 ;;
    --weekly) weekly="${2:-}"; shift 2 ;;
    --monthly) monthly="${2:-}"; shift 2 ;;
    --confirm) confirmation="${2:-}"; shift 2 ;;
    --help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done
[[ "$daily" =~ ^[0-9]+$ && "$weekly" =~ ^[0-9]+$ && "$monthly" =~ ^[0-9]+$ ]] || { printf 'retention values must be non-negative integers\n' >&2; exit 2; }
root="$(backup_root "$requested_root")"
acquire_lock "$root"
trap cleanup_postgres_ops EXIT INT TERM
mapfile -t remove_sets < <(python3 - "$root" "$daily" "$weekly" "$monthly" <<'PY'
import datetime as dt
import hashlib
import json
import os
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
limits = [int(value) for value in sys.argv[2:]]
valid = []
for child in root.iterdir():
    if not child.is_dir() or child.is_symlink() or not child.name[:8].isdigit():
        continue
    metadata, dump, verification = child / "backup.json", child / "database.dump", child / "verification-released.json"
    try:
        data = json.loads(metadata.read_text(encoding="utf-8"))
        check = data["dump"]["sha256"]
        digest = hashlib.sha256()
        with dump.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                digest.update(chunk)
        if not isinstance(check, str) or digest.hexdigest() != check:
            continue
        report = json.loads(verification.read_text(encoding="utf-8"))
        image = report.get("image")
        if report.get("status") != "verified" or report.get("backupSha256") != check:
            continue
        if not isinstance(image, str) or not __import__("re").fullmatch(r"[^\s@]+@sha256:[a-f0-9]{64}", image):
            continue
        created = dt.datetime.fromisoformat(data["createdAt"].replace("Z", "+00:00"))
        valid.append((created, child.name))
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        continue
valid.sort(reverse=True)
keep = {valid[0][1]} if valid else set()
for position, formatter in enumerate((lambda x: x.strftime("%Y-%m-%d"), lambda x: f"{x.isocalendar().year}-{x.isocalendar().week}", lambda x: x.strftime("%Y-%m"))):
    buckets = set()
    for created, name in valid:
        key = formatter(created)
        if len(buckets) < limits[position] and key not in buckets:
            buckets.add(key); keep.add(name)
for _, name in valid:
    if name not in keep:
        print(name)
PY
)
printf 'prune_preview\n' >&2
if [[ -z "$confirmation" ]]; then
  printf 'eligible_sets=%s mode=dry-run\n' "${#remove_sets[@]}"
  exit 0
fi
require_confirmation "$confirmation" PRUNE
for name in "${remove_sets[@]}"; do
  [[ "$name" =~ ^[0-9]{8}T[0-9]{6}Z-[a-f0-9]+$ ]] || { printf 'unsafe backup set name\n' >&2; exit 1; }
  candidate="$(safe_set_path "$root" "$root/$name")"
  [[ -d "$candidate" && ! -L "$candidate" ]] || { printf 'backup set changed during prune\n' >&2; exit 1; }
  rm -rf -- "$candidate"
done
python3 - "$root/index.json" "${#remove_sets[@]}" <<'PY'
import json, os, sys, tempfile
path = sys.argv[1]
fd, temporary = tempfile.mkstemp(prefix=".index.", dir=os.path.dirname(path))
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump({"format": 1, "prunedSets": int(sys.argv[2])}, handle, separators=(",", ":"))
    handle.write("\n")
os.chmod(temporary, 0o600)
os.replace(temporary, path)
PY
safe_log prune_complete
