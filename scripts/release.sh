#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/release.sh vMAJOR.MINOR.PATCH[-PRERELEASE]

Creates and pushes an annotated release tag from the latest origin/master.
The tag push triggers .github/workflows/release.yml, which publishes the
GitHub Release and GHCR image after verification passes.

Examples:
  scripts/release.sh v0.1.0
  scripts/release.sh v0.1.1-rc.1
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

release_tag="${1:-}"
if [[ -z "$release_tag" ]]; then
  usage >&2
  exit 64
fi

if [[ ! "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z-]*(\.[0-9A-Za-z][0-9A-Za-z-]*)*)?$ ]]; then
  echo "Release tag must use vMAJOR.MINOR.PATCH format, optionally with a prerelease suffix." >&2
  exit 64
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree must be clean before creating a release tag." >&2
  git status --short
  exit 65
fi

if git rev-parse -q --verify "refs/tags/$release_tag" >/dev/null; then
  echo "Local tag already exists: $release_tag" >&2
  exit 66
fi

if git ls-remote --exit-code --tags origin "refs/tags/$release_tag" >/dev/null 2>&1; then
  echo "Remote tag already exists on origin: $release_tag" >&2
  exit 66
fi

git fetch origin master --prune

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree changed unexpectedly after fetching origin/master." >&2
  git status --short
  exit 65
fi

release_sha="$(git rev-parse origin/master)"
git tag -a "$release_tag" -m "Release $release_tag" "$release_sha"
git push origin "$release_tag"

cat <<EOF
Release tag pushed: $release_tag
Source commit: $release_sha
GitHub Actions: https://github.com/JonHHH09/job-engine-spring/actions/workflows/release.yml
EOF
