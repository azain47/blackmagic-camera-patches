#!/usr/bin/env bash
# Builds the Blackmagic Camera patch bundle (MPP jar) from the Kotlin sources.
#
# No Gradle / GitHub Packages auth required: the patches are compiled directly
# against the Morphe patcher API that ships inside the morphe-desktop fat jar.
#
# Requirements:
#   - JDK 21+
#   - A Kotlin command-line compiler (kotlinc) on PATH, or set $KOTLINC
#   - morphe-desktop-*-all.jar (set $MORPHE_JAR), from
#     https://github.com/MorpheApp/morphe-desktop/releases
#
# Output: build/blackmagic-camera-patches.jar (a jar loadable by morphe-desktop)
set -euo pipefail

cd "$(dirname "$0")"

KOTLINC="${KOTLINC:-kotlinc}"
MORPHE_JAR="${MORPHE_JAR:-}"
OUT="build/blackmagic-camera-patches.jar"

if [[ -z "$MORPHE_JAR" || ! -f "$MORPHE_JAR" ]]; then
  echo "error: set MORPHE_JAR to the path of morphe-desktop-*-all.jar" >&2
  exit 1
fi

mkdir -p build

# The patcher API is compiled with a newer Kotlin metadata version than some
# stable kotlinc releases ship; these flags let an older kotlinc read it, and
# enable the context-parameter feature the patcher DSL relies on.
"$KOTLINC" \
  -no-stdlib \
  -Xskip-metadata-version-check \
  -Xskip-prerelease-check \
  -Xcontext-parameters \
  -classpath "$MORPHE_JAR" \
  -d "$OUT" \
  $(find patches/src -name '*.kt')

echo "built: $OUT"
