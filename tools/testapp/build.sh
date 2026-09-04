#!/usr/bin/env bash
# Builds the UNIQUE probe APK with the raw Android toolchain.
#
# No Gradle on purpose: the probe must be an ordinary third-party app that UNIQUE imports,
# not a module of UNIQUE's own build. Keeping it out of settings.gradle.kts also keeps it
# out of `./gradlew assembleRelease`, where it does not belong.
set -euo pipefail

# ANDROID_HOME is not always exported - Gradle invokes this script too, and the Android
# plugin resolves the SDK from local.properties rather than the environment.
if [ -z "${ANDROID_HOME:-}" ]; then
    root="$(cd "$(dirname "$0")/../.." && pwd)"
    ANDROID_HOME="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null | head -1)}"
fi
: "${ANDROID_HOME:?set ANDROID_HOME, or sdk.dir in local.properties}"
export ANDROID_HOME
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
PLATFORM="${PLATFORM:-$ANDROID_HOME/platforms/android-36/android.jar}"

here="$(cd "$(dirname "$0")" && pwd)"
out="${OUT:-$here/build}"
rm -rf "$out"; mkdir -p "$out/classes" "$out/dex"

echo "[1/5] javac"
# --release with an explicit classpath rather than -bootclasspath: modern javac rejects
# -bootclasspath for target 9+, and android.jar on the classpath is what the platform's
# own build does anyway.
javac --release 17 -nowarn -classpath "$PLATFORM" \
    -d "$out/classes" $(find "$here/src" -name '*.java')

echo "[2/5] d8"
"$BUILD_TOOLS/d8" --min-api 26 --output "$out/dex" \
    $(find "$out/classes" -name '*.class')

echo "[3/5] aapt2 link"
"$BUILD_TOOLS/aapt2" link -o "$out/unsigned.apk" \
    --manifest "$here/AndroidManifest.xml" -I "$PLATFORM" \
    --min-sdk-version 26 --target-sdk-version 34

echo "[4/5] package dex"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"

echo "[5/5] sign"
# The keystore lives outside the build directory and is created once. Regenerating it
# per build changes the signing certificate, which Android rejects as an incompatible
# update - and which would also defeat any future signature checking in the importer.
keydir="$here/.keystore"
keystore="$keydir/probe.keystore"
if [ ! -f "$keystore" ]; then
    mkdir -p "$keydir"
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias probe -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=UNIQUE Probe, OU=Testing, O=UNIQUE, C=US" >/dev/null 2>&1
fi
"$BUILD_TOOLS/apksigner" sign --ks "$keystore" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias probe \
    --out "$out/probe.apk" "$out/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "$out/probe.apk" | head -2

rm -f "$out/unsigned.apk" "$out/aligned.apk"
echo "Built: $out/probe.apk ($(stat -c%s "$out/probe.apk") bytes)"
