#!/usr/bin/env bash
# Regenerates core/common's test fixtures with real Android tooling.
#
# The parser tests are only meaningful if they run against what current tooling actually
# emits, so the fixtures are aapt2 and NDK output rather than hand-assembled bytes.
set -euo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
PLATFORM="${PLATFORM:-$ANDROID_HOME/platforms/android-36/android.jar}"
NDK="${NDK:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"

root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/core/common/src/test/resources/fixtures"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$dest"

echo "Fixtures -> $dest"
# APK manifests are compiled from the sources under tools/fixtures/.
for name in base split-abi split-feature; do
    src="$root/tools/fixtures/$name.xml"
    "$BUILD_TOOLS/aapt2" link -o "$work/$name.apk" --manifest "$src" -I "$PLATFORM"
    cp "$work/$name.apk" "$dest/sample-$name.apk"
    echo "  sample-$name.apk"
done

# One more with a *resource table*, because `android:label` in a real app is a reference
# into it and every fixture above spells its name out as a literal. That difference is the
# whole bug: a reader with no table hands back `@7f010000`, which is what a phone showed
# for every imported app.
"$BUILD_TOOLS/aapt2" compile --dir "$root/tools/fixtures/labelled-res" -o "$work/labelled-res.zip"
"$BUILD_TOOLS/aapt2" link -o "$work/labelled.apk" \
    --manifest "$root/tools/fixtures/labelled.xml" -I "$PLATFORM" "$work/labelled-res.zip"
cp "$work/labelled.apk" "$dest/sample-labelled.apk"
echo "  sample-labelled.apk"

# Two arm64 libraries differing only in page alignment: one loadable on a 16 KB-page
# device, one not. That difference is what ElfInspector exists to detect.
cat > "$work/probe.c" <<'C'
int unique_probe(int x) { return x * 3 + 1; }
C
for pair in "16k:16384" "4k:4096"; do
    tag="${pair%%:*}"; size="${pair##*:}"
    "$CLANG" -shared -fPIC -o "$dest/libprobe$tag.so" "$work/probe.c" \
        -Wl,-z,max-page-size="$size" -Wl,-soname,"libprobe$tag.so"
    echo "  libprobe$tag.so (max-page-size=$size)"
done
echo "Done."
