#!/usr/bin/env bash
#
# Builds a single, installable, signed APK of Snake.io with the gavna menu in it.
#
#   ./build.sh [path/to/original.apks]
#
# With no argument the bundle is downloaded from the Drive link. Everything else
# (SDK, NDK, APKEditor) is expected in the locations set below, which is what
# tools/setup_toolchain.sh produces.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK_DIR:-$HERE/build}"
OUT="${OUT_DIR:-$HERE/dist}"

SDK="${ANDROID_SDK:-/opt/android-tools/sdk}"
NDK="${ANDROID_NDK:-$SDK/ndk/27.2.12479018}"
# 35.0.0 or newer: the d8 in build-tools 34 crashes on anonymous classes emitted
# by a JDK 21 javac.
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
APKEDITOR="${APKEDITOR:-/opt/android-tools/APKEditor.jar}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"

DRIVE_ID="1d9Ho01DQ5n6FP930cH1Ul-snZE41rzfR"
APP_NAME="SnakeIO-2.2.160-gavna"
KEYSTORE="$WORK/gavna.keystore"
KEY_ALIAS="gavna"
KEY_PASS="gavnagavna"

# The mod's Application class name must stay exactly as long as the game's, so
# the binary manifest can be patched by a byte-for-byte string swap.
ORIGINAL_APPLICATION="com.kooapps.unity.UnityApplication"
PATCHED_APPLICATION="com.gavna.snakeio.GavnaApplication"

say() { printf '\n=== %s\n' "$*"; }

mkdir -p "$WORK" "$OUT"

# ---------------------------------------------------------------- 1. sources
BUNDLE="${1:-$WORK/snakeio-original.apks}"
if [[ ! -f "$BUNDLE" ]]; then
    say "downloading the original bundle from Drive"
    python3 -m gdown "https://drive.google.com/uc?id=$DRIVE_ID" -O "$BUNDLE"
fi
ls -l "$BUNDLE"

# ------------------------------------------------- 2. splits -> single APK
MERGED="$WORK/merged.apk"
if [[ ! -f "$MERGED" ]]; then
    say "merging split APKs into one universal APK"
    java -Xmx4g -jar "$APKEDITOR" m -i "$BUNDLE" -o "$MERGED"
fi
ls -l "$MERGED"

# ------------------------------------------------------- 3. native library
say "building libgavna.so (arm64-v8a)"
cd "$HERE/native"
"$NDK/ndk-build" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=./Android.mk \
    NDK_APPLICATION_MK=./Application.mk \
    NDK_LIBS_OUT="$WORK/libs" \
    NDK_OUT="$WORK/obj"
cd "$HERE"
SO="$WORK/libs/arm64-v8a/libgavna.so"
ls -l "$SO"

# ------------------------------------------------------------ 4. mod classes
say "compiling the menu classes"
CLASSES="$WORK/classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES"
find "$HERE/java" -name '*.java' > "$WORK/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
    -d "$CLASSES" @"$WORK/sources.txt"

# The game ships the real UnityApplication; ours was only a compile-time stub, so
# it moves out of the way into a classpath-only jar.
STUBS="$WORK/stubs"
rm -rf "$STUBS"
mkdir -p "$STUBS"
mv "$CLASSES/com/kooapps" "$STUBS/"
(cd "$STUBS" && jar cf "$WORK/stubs.jar" .)
(cd "$CLASSES" && jar cf "$WORK/mod.jar" .)

say "dexing"
DEXDIR="$WORK/dex"
rm -rf "$DEXDIR"
mkdir -p "$DEXDIR"
"$BUILD_TOOLS/d8" --min-api 23 \
    --lib "$ANDROID_JAR" \
    --classpath "$WORK/stubs.jar" \
    --output "$DEXDIR" "$WORK/mod.jar"
ls -l "$DEXDIR"

# The game already ships classes.dex .. classes11.dex, so ours has to be the
# next one in the sequence for the runtime to load it.
NEXT_DEX=$(python3 - "$MERGED" <<'PY'
import re, sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
indexes = []
for name in names:
    match = re.fullmatch(r'classes(\d*)\.dex', name)
    if match:
        indexes.append(int(match.group(1) or 1))
print('classes%d.dex' % (max(indexes) + 1))
PY
)
echo "mod dex will be installed as $NEXT_DEX"

# ---------------------------------------------------------- 5. manifest swap
say "pointing <application> at the mod class"
unzip -o -q "$MERGED" AndroidManifest.xml -d "$WORK/manifest"
python3 "$HERE/tools/patch_manifest.py" \
    "$WORK/manifest/AndroidManifest.xml" "$WORK/manifest/AndroidManifest.patched.xml" \
    --old "$ORIGINAL_APPLICATION" --new "$PATCHED_APPLICATION"

# ------------------------------------------------------------- 6. repackage
say "repackaging"
UNSIGNED="$WORK/$APP_NAME-unsigned.apk"
rm -f "$UNSIGNED"
python3 "$HERE/tools/repack.py" "$MERGED" "$UNSIGNED" \
    --replace "AndroidManifest.xml=$WORK/manifest/AndroidManifest.patched.xml" \
    --add "$NEXT_DEX=$DEXDIR/classes.dex" \
    --add "lib/arm64-v8a/libgavna.so=$SO"

say "aligning"
ALIGNED="$WORK/$APP_NAME-aligned.apk"
rm -f "$ALIGNED"
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$ALIGNED"
echo "alignment ok"

# ---------------------------------------------------------------- 7. signing
if [[ ! -f "$KEYSTORE" ]]; then
    say "creating a signing key"
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -dname "CN=gavna, OU=gavna, O=gavna, L=-, S=-, C=-"
fi

say "signing"
FINAL="$OUT/$APP_NAME.apk"
rm -f "$FINAL"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$FINAL" "$ALIGNED"

"$BUILD_TOOLS/apksigner" verify --print-certs -v "$FINAL" | head -20

say "done"
ls -l "$FINAL"
sha256sum "$FINAL" | tee "$FINAL.sha256"
