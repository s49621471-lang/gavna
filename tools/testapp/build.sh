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

echo "[1/6] javac"
# --release with an explicit classpath rather than -bootclasspath: modern javac rejects
# -bootclasspath for target 9+, and android.jar on the classpath is what the platform's
# own build does anyway.
javac --release 17 -nowarn -classpath "$PLATFORM" \
    -d "$out/classes" $(find "$here/src" -name '*.java')

echo "[2/6] d8"
"$BUILD_TOOLS/d8" --min-api 26 --output "$out/dex" \
    $(find "$out/classes" -name '*.class')

echo "[3/6] native (arm64-v8a + x86_64)"
# Built with the NDK's own clang for both 64-bit ABIs, so the same probe exercises the
# native path on a phone and on an emulator. 32-bit is not built: UNIQUE runs 64-bit
# guests only and does not emulate.
#
# -Wl,-z,max-page-size=16384 is what makes the library loadable on an Android 15 device
# configured for 16 KB pages. Without it the failure arrives at dlopen, inside the app,
# naming the library and not the cause.
ndk="${ANDROID_NDK:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort | tail -1)}"
if [ -n "$ndk" ] && [ -d "$ndk" ]; then
    clang="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
    for pair in "arm64-v8a:aarch64-linux-android26" "x86_64:x86_64-linux-android26"; do
        abi="${pair%%:*}"; target="${pair##*:}"
        mkdir -p "$out/lib/$abi"
        "$clang" --target="$target" -shared -fPIC -O2 -Wall \
            -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
            -o "$out/lib/$abi/libprobenative.so" "$here/native/probe_native.c"
        # A second library, loaded late on purpose - see native/probe_late.c.
        "$clang" --target="$target" -shared -fPIC -O2 -Wall \
            -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
            -o "$out/lib/$abi/libprobelate.so" "$here/native/probe_late.c"
    done
else
    echo "  no NDK found; the probe will carry no native code" >&2
fi

echo "[4/6] aapt2 link"
"$BUILD_TOOLS/aapt2" link -o "$out/unsigned.apk" \
    --manifest "$here/AndroidManifest.xml" -I "$PLATFORM" \
    --min-sdk-version 26 --target-sdk-version 34

echo "[5/6] package dex and native libraries"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
# Stored, not deflated: the platform maps an uncompressed library straight out of the APK
# and UNIQUE's importer extracts it either way, but a compressed one hides alignment
# problems until extraction.
if [ -d "$out/lib" ]; then
    (cd "$out" && zip -q -0 -r "$out/unsigned.apk" lib)
fi
"$BUILD_TOOLS/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"

echo "[6/6] sign"
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
