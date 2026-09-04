#!/usr/bin/env bash
# Builds a *feature split* for the probe, plus a bumped-version base.
#
# A real second APK rather than a renamed copy: the point is to exercise the code path
# where a class lives in a split and the class loader has to be given splitSourceDirs, and
# where an update replaces the base while keeping the instance's data.
set -euo pipefail

if [ -z "${ANDROID_HOME:-}" ]; then
    root="$(cd "$(dirname "$0")/../.." && pwd)"
    ANDROID_HOME="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null | head -1)}"
fi
: "${ANDROID_HOME:?set ANDROID_HOME, or sdk.dir in local.properties}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
PLATFORM="${PLATFORM:-$ANDROID_HOME/platforms/android-36/android.jar}"

here="$(cd "$(dirname "$0")" && pwd)"
out="${OUT:-$here/build}"
split_src="$here/split"
mkdir -p "$out/split-classes" "$out/split-dex"

echo "[split 1/4] javac"
javac --release 17 -nowarn -classpath "$PLATFORM" \
    -d "$out/split-classes" $(find "$split_src/src" -name '*.java')

echo "[split 2/4] d8"
"$BUILD_TOOLS/d8" --min-api 26 --output "$out/split-dex" \
    $(find "$out/split-classes" -name '*.class')

echo "[split 3/4] aapt2 link"
"$BUILD_TOOLS/aapt2" link -o "$out/split-unsigned.apk" \
    --manifest "$split_src/AndroidManifest.xml" -I "$PLATFORM" \
    --min-sdk-version 26 --target-sdk-version 34

echo "[split 4/4] package and sign"
(cd "$out/split-dex" && zip -q "$out/split-unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$out/split-unsigned.apk" "$out/split-aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$here/.keystore/probe.keystore" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias probe \
    --out "$out/split_feature.apk" "$out/split-aligned.apk"
rm -f "$out/split-unsigned.apk" "$out/split-aligned.apk"
echo "Built: $out/split_feature.apk ($(stat -c%s "$out/split_feature.apk") bytes)"
