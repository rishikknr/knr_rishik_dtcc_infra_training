#!/usr/bin/env bash
# Build in a separate directory: never overwrite a JAR mapped by a running JVM.
set -euo pipefail
trace_root=$(cd "$(dirname "$0")/../.." && pwd)
trace_build=$(mktemp -d "${TMPDIR:-/tmp}/trace-release.XXXXXX")
trap 'rm -rf "$trace_build"' EXIT
mkdir "$trace_build/backend"
rsync -a --exclude target --exclude releases "$trace_root/backend/" "$trace_build/backend/"
ln -s "$trace_root/processor" "$trace_build/processor"
ln -s "$trace_root/demo" "$trace_build/demo"
(cd "$trace_build/backend" && ./mvnw package)
trace_jar="$trace_build/backend/target/backend-0.0.1-SNAPSHOT.jar"
trace_sha=$(shasum -a 256 "$trace_jar" | cut -d' ' -f1)
mkdir -p "$trace_root/backend/releases"
trace_release="$trace_root/backend/releases/trace-v18-$trace_sha.jar"
if [[ ! -f "$trace_release" ]]; then
  cp -n "$trace_jar" "$trace_release"
  chmod 444 "$trace_release"
fi
printf 'Immutable release: %s\nSHA256: %s\n' "$trace_release" "$trace_sha"
