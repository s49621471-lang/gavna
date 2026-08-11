#!/usr/bin/env bash
# Zyrex dumper — full build
#
# Produces a signed, installable copy of BLOCKPOST with the IL2CPP runtime
# dumper injected into UnityPlayerActivity.onCreate.
#
# Requires: Android SDK build-tools 34, NDK r26, apktool 2.12+, JDK 17+.

set -euo pipefail

: "${ANDROID_HOME:=/opt/android-sdk}"
: "${NDK:=$ANDROID_HOME/ndk/26.3.11579264}"
: "${APKTOOL:=/opt/tools/apktool.jar}"
BT="$ANDROID_HOME/build-tools/34.0.0"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-/work}"
SRC_APK="${1:?usage: build.sh <original.apk>}"

DECODED="$WORK/apk"
OUT="$WORK/out"
mkdir -p "$OUT" "$WORK/stage/lib/arm64-v8a"

# ---------------------------------------------------------------- native ----
echo ">> building libzyrexdump.so (arm64-v8a)"
cmake -S "$HERE/native" -B "$WORK/build-native" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$WORK/build-native" -j"$(nproc)"
"$LLVM/llvm-strip" --strip-unneeded "$WORK/build-native/libzyrexdump.so" \
    -o "$WORK/stage/lib/arm64-v8a/libzyrexdump.so"

# ------------------------------------------------------------------ java ----
echo ">> building Zyrex.dex"
rm -rf "$WORK/build-java"; mkdir -p "$WORK/build-java/classes"
javac -source 8 -target 8 -nowarn \
    -bootclasspath "$ANDROID_HOME/platforms/android-34/android.jar" \
    -d "$WORK/build-java/classes" \
    "$HERE/java/com/zyrex/dumper/Zyrex.java"
"$BT/d8" --min-api 24 --output "$WORK/build-java" \
    $(find "$WORK/build-java/classes" -name '*.class')

# ------------------------------------------------------------------ apk -----
# The decode + smali patch are done once by prepare.sh; this step assumes
# "$DECODED" already carries the injected call in
# smali_classes7/com/unity3d/player/UnityPlayerActivity.smali.
if [ ! -d "$DECODED" ]; then
    echo ">> decoding $SRC_APK"
    java -Xmx6g -jar "$APKTOOL" d -f -o "$DECODED" "$SRC_APK"
    python3 "$HERE/patch_activity.py" "$DECODED"
fi

echo ">> repacking"
java -Xmx8g -jar "$APKTOOL" b "$DECODED" -o "$OUT/zyrex-unsigned.apk"

# The dumper's own dex is appended rather than rebuilt into the existing ones:
# ART loads every contiguous classesN.dex, and the original APK stops at
# classes8.dex, so classes9.dex is picked up with no loader changes.
echo ">> injecting dex + native library"
cp "$WORK/build-java/classes.dex" "$WORK/stage/classes9.dex"
( cd "$WORK/stage" && zip -q -X "$OUT/zyrex-unsigned.apk" classes9.dex lib/arm64-v8a/libzyrexdump.so )

# --------------------------------------------------------------- signing ----
echo ">> aligning and signing"
KS="$WORK/zyrex.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -alias zyrex \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass zyrex123 -keypass zyrex123 \
        -dname "CN=Zyrex, OU=Zyrex, O=Zyrex, L=NA, S=NA, C=NA"
fi

"$BT/zipalign" -p -f 4 "$OUT/zyrex-unsigned.apk" "$OUT/zyrex-aligned.apk"
"$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias zyrex \
    --ks-pass pass:zyrex123 --key-pass pass:zyrex123 \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/BLOCKPOST-Zyrex-dumper.apk" \
    "$OUT/zyrex-aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/BLOCKPOST-Zyrex-dumper.apk"
ls -lh "$OUT/BLOCKPOST-Zyrex-dumper.apk"
echo ">> done"
