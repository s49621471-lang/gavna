#!/usr/bin/env bash
# Checks an APK's native libraries for ARM64-only packaging and 16 KB page alignment.
#
# Android 15 introduced devices with a 16 KB page size, and a shared library whose LOAD
# segments are only 4 KB aligned will not load on them. The failure is at dlopen time,
# inside the app, with a message that names the library and not the cause - so this is
# checked at build time instead.
set -euo pipefail

apk="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 1; }

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null | head -1)}}"
[ -n "$sdk" ] || { echo "set ANDROID_HOME, or sdk.dir in local.properties" >&2; exit 1; }

# Pinned to one NDK deliberately: a glob here expands to every installed NDK, and the
# extra paths become *input files*, so the tool happily reports the alignment of another
# copy of itself. That reads as a 4 KB failure and wasted a debugging round.
readelf="$(find "$sdk/ndk" -path '*/linux-x86_64/bin/llvm-readelf' 2>/dev/null | sort | tail -1)"
[ -n "$readelf" ] || { echo "no llvm-readelf under $sdk/ndk" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -oq "$apk" 'lib/*' -d "$work" || true

libs="$(find "$work/lib" -name '*.so' 2>/dev/null | sort)"
if [ -z "$libs" ]; then
    echo "no native libraries in $(basename "$apk")"
    exit 0
fi

echo "== ABIs =="
find "$work/lib" -mindepth 1 -maxdepth 1 -type d -printf '  %f\n' | sort
unexpected="$(find "$work/lib" -mindepth 1 -maxdepth 1 -type d ! -name 'arm64-v8a' -printf '%f ' || true)"

echo "== 16 KB page alignment =="
failed=0
for f in $libs; do
    aligns="$("$readelf" -l "$f" 2>/dev/null | awk '$1=="LOAD" {print $NF}' | sort -u)"
    smallest="$(printf '%s\n' $aligns | sort | head -1)"
    if [ -z "$smallest" ]; then
        echo "  ${f#$work/}  no LOAD segments — not an ELF?"
        failed=1
        continue
    fi
    if [ "$((smallest))" -ge 16384 ]; then
        echo "  ${f#$work/}  $(printf '%s ' $aligns) OK"
    else
        echo "  ${f#$work/}  $(printf '%s ' $aligns) TOO SMALL (needs >= 0x4000)"
        failed=1
    fi
done

echo "== zip alignment =="
zipalign="$(find "$sdk/build-tools" -name zipalign 2>/dev/null | sort | tail -1)"
if [ -n "$zipalign" ]; then
    if "$zipalign" -c -P 16 -v 4 "$apk" > /dev/null 2>&1; then
        echo "  zipalign -P 16: OK"
    else
        echo "  zipalign -P 16: FAILED"
        failed=1
    fi
else
    echo "  zipalign not found; skipped"
fi

if [ -n "$unexpected" ]; then
    echo
    echo "NOTE: ABIs other than arm64-v8a are present: $unexpected"
    echo "      Expected for an emulator build (UNIQUE_ABIS=x86_64); not for a release."
fi

[ "$failed" -eq 0 ] || { echo; echo "RESULT: FAIL"; exit 1; }
echo
echo "RESULT: OK"
