#!/usr/bin/env bash
# Compiles the ESP payload and injects it into a BLOCKPOST APK.
#
#   ./build.sh <input.apk> [output.apk]
#
# Needs, exported or discoverable under ./tools:
#   ANDROID_NDK   r26+ with the arm64 clang toolchain
#   BUILD_TOOLS   android build-tools (d8, zipalign, apksigner)
#   ANDROID_JAR   platform android.jar
set -euo pipefail

IN="${1:?usage: build.sh <input.apk> [output.apk]}"
OUT="${2:-${IN%.apk}-esp.apk}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$ROOT/.build"

TOOLS="${TOOLS:-$ROOT/tools}"
ANDROID_NDK="${ANDROID_NDK:-$TOOLS/android-ndk-r26d}"
BUILD_TOOLS="${BUILD_TOOLS:-$TOOLS/android-14}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android-34/android.jar}"
CXX="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++"

for t in "$CXX" "$BUILD_TOOLS/d8" "$ANDROID_JAR"; do
    [ -e "$t" ] || { echo "missing: $t"; exit 1; }
done

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/dex" "$ROOT/out"

echo "[native] libesp.so"
"$CXX" -std=c++17 -O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
    -Wall -Wextra -Wno-unused-parameter \
    -shared -o "$ROOT/out/libesp.so" \
    "$ROOT/native/esp.cpp" "$ROOT/native/il2cpp.cpp" \
    -llog -ldl -Wl,--gc-sections -Wl,-z,max-page-size=16384

echo "[java] classes"
javac -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" \
    $(find "$ROOT/java" "$ROOT/stubs" -name '*.java')

echo "[dex] payload.dex"
# the multidex superclass stub is compile-time only and must not reach the dex
rm -rf "$WORK/classes/androidx"
"$BUILD_TOOLS/d8" --min-api 24 --lib "$ANDROID_JAR" --output "$WORK/dex" \
    $(find "$WORK/classes" -name '*.class')
cp "$WORK/dex/classes.dex" "$ROOT/out/payload.dex"
rm -rf "$WORK"

exec "$ROOT/apply.sh" "$IN" "$OUT"
