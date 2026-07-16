#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_version="${1:-0.0.0-reproducible}"
revision="${BUILD_REVISION:-$(git -C "$repo_root" rev-parse HEAD)}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

build_artifacts() {
  "$repo_root/gradlew" \
    -p "$repo_root" \
    clean jar sourcesJar \
    --no-daemon \
    "-PreleaseVersion=$release_version" \
    "-PbuildRevision=$revision"
}

build_artifacts
cp "$repo_root"/build/libs/*.jar "$temporary_directory"/

build_artifacts
for artifact in "$repo_root"/build/libs/*.jar; do
  baseline="$temporary_directory/$(basename "$artifact")"
  if ! cmp --silent "$baseline" "$artifact"; then
    echo "Artifact is not reproducible: $(basename "$artifact")" >&2
    exit 1
  fi
done

echo "Reproducible artifacts verified for revision $revision"
