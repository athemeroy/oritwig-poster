#!/usr/bin/env bash
# Package binaries with their complete corresponding source from the same commit.
set -euo pipefail
cd "$(dirname "$0")/.."
app=${1:?Usage: tools/package-artifacts.sh poster|journal [output-directory]}
case "$app" in poster) product=pocket-poster;; journal) product=field-journal;; *) exit 2;; esac
out=${2:-artifacts/$app}
root=$(pwd -P)
[[ $(git rev-parse --show-toplevel) == "$root" ]] || { echo 'Package from the Oritwig app Git checkout, not a parent workspace.' >&2; exit 2; }
[[ -z $(git status --porcelain --untracked-files=normal) ]] || { echo 'Commit the source changes before packaging a matching release.' >&2; exit 2; }
commit=$(git rev-parse HEAD)
# Rebuild/check even if old APKs exist, so a clean checkout cannot package stale binaries.
./tools/build-app.sh "$app"
[[ $(git rev-parse HEAD) == "$commit" && -z $(git status --porcelain --untracked-files=normal) ]] || { echo 'Source changed during build; retry from a clean checkout.' >&2; exit 2; }
mkdir -p "$out"
cp "apps/$app/build/outputs/apk/debug/$app-debug.apk" "$out/$product-debug.apk"
cp "apps/$app/build/outputs/apk/release/$app-release-unsigned.apk" "$out/$product-release-unsigned.apk"
git archive --format=tar --prefix=oritwig-poster/ HEAD | gzip -n > "$out/oritwig-poster-$commit-source.tar.gz"
{
  printf 'source_commit=%s\n' "$commit"
  printf 'product=%s\n' "$product"
  printf 'gradle=8.11.1\nandroid_gradle_plugin=8.9.3\ncompile_sdk=35\nbuild_tools=35.0.0\nmin_sdk=26\n'
  printf 'release_signing=unsigned\ndebug_signing=local_debug_key_for_testing_only\n'
  java -version 2>&1 | sed '/^Picked up JAVA_TOOL_OPTIONS:/d'
} > "$out/BUILD-INFO.txt"
(
  cd "$out"
  sha256sum "$product-debug.apk" "$product-release-unsigned.apk" oritwig-poster-*-source.tar.gz BUILD-INFO.txt > SHA256SUMS
)
printf 'Artifacts and complete source: %s\n' "$out"
