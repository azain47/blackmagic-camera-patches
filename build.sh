#!/usr/bin/env bash
# Builds the Blackmagic Camera patch bundle as a Morphe `.mpp`.
#
# The `.mpp` is a zip that contains:
#   - classes.dex              : the patches as Dalvik bytecode (loaded on-device
#                                by the Morphe / ReVanced Manager app)
#   - app/.../*.class          : the same patches as JVM bytecode (loaded by
#                                morphe-desktop on a computer)
#   - META-INF/MANIFEST.MF     : bundle name/description/version shown in Manager
#
# No Gradle / GitHub Packages auth required: patches are compiled directly against
# the Morphe patcher API that ships inside the morphe-desktop fat jar.
#
# Requirements:
#   - JDK 21+
#   - kotlinc on PATH (or $KOTLINC)
#   - d8 on PATH (or $D8), from the Android SDK build-tools
#   - morphe-desktop-*-all.jar ($MORPHE_JAR), from
#     https://github.com/MorpheApp/morphe-desktop/releases
#
# Output: build/blackmagic-camera-patches.mpp
set -euo pipefail
cd "$(dirname "$0")"

KOTLINC="${KOTLINC:-kotlinc}"
D8="${D8:-d8}"
MORPHE_JAR="${MORPHE_JAR:-}"
VERSION="${VERSION:-1.0.0}"
MIN_API="${MIN_API:-26}"

NAME="Blackmagic Camera Patches"
SOURCE="https://github.com/azain47/blackmagic-camera-patches"
JAR="build/blackmagic-camera-patches.jar"
MPP="build/blackmagic-camera-patches.mpp"

if [[ -z "$MORPHE_JAR" || ! -f "$MORPHE_JAR" ]]; then
  echo "error: set MORPHE_JAR to the path of morphe-desktop-*-all.jar" >&2
  exit 1
fi

rm -rf build && mkdir -p build/dex

# 1. Compile Kotlin patches -> JVM classes (jar). The patcher API is newer Kotlin
#    metadata than some stable kotlinc releases; these flags let kotlinc read it
#    and enable the context-parameter feature the DSL relies on.
"$KOTLINC" \
  -no-stdlib \
  -Xskip-metadata-version-check \
  -Xskip-prerelease-check \
  -Xcontext-parameters \
  -classpath "$MORPHE_JAR" \
  -d "$JAR" \
  $(find patches/src -name '*.kt')

# 2. Convert the patch classes -> classes.dex (patcher API stays on the classpath,
#    so only our own classes end up in the dex).
"$D8" --min-api "$MIN_API" --classpath "$MORPHE_JAR" --output build/dex "$JAR"

# 3. Assemble the .mpp: jar contents + classes.dex + a clean bundle manifest.
#    Written with exact bytes (Morphe reads `Name:` as a main-section attribute,
#    which the `jar` tool would otherwise mangle).
MPP_NAME="$NAME" MPP_VERSION="$VERSION" MPP_SOURCE="$SOURCE" \
  python3 - "$JAR" build/dex/classes.dex "$MPP" <<'PY'
import os, sys, time, zipfile
src, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
manifest = (
    "Manifest-Version: 1.0\r\n"
    f"Name: {os.environ['MPP_NAME']}\r\n"
    "Description: Bitrate tiers and license-check bypass.\r\n"
    f"Version: {os.environ['MPP_VERSION']}\r\n"
    f"Timestamp: {int(time.time()*1000)}\r\n"
    f"Source: {os.environ['MPP_SOURCE']}\r\n"
    "Author: azain47\r\n"
    "Contact: na\r\n"
    f"Website: {os.environ['MPP_SOURCE']}\r\n"
    "License: MIT\r\n"
    "Patcher-Version: 2.0.0\r\n\r\n"
)
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zo:
    # MANIFEST.MF must be the first entry so streaming readers (the on-device
    # Manager) find the bundle metadata; otherwise it reports "metadata not found".
    zo.writestr("META-INF/MANIFEST.MF", manifest)
    zo.writestr("classes.dex", open(dex, "rb").read())
    for n in zin.namelist():
        if n != "META-INF/MANIFEST.MF":
            zo.writestr(n, zin.read(n))
PY

echo "built: $MPP"
