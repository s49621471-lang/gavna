#!/usr/bin/env bash
# Builds the *next* version of the probe: same code, same signing key, versionCode + 1.
#
# A genuine second build rather than a renamed copy, because the update path checks the
# version and the signing certificate and both have to be real for the check to mean
# anything.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="${OUT:-$here/build}"
current="$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$here/AndroidManifest.xml" | head -1)"
next=$((current + 1))

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
sed "s/android:versionCode=\"$current\"/android:versionCode=\"$next\"/" \
    "$here/AndroidManifest.xml" > "$work/AndroidManifest.xml"

# Build into a scratch directory so the current probe.apk is not disturbed.
cp -r "$here/src" "$here/native" "$work/"
cp "$here/build.sh" "$work/"
mkdir -p "$work/.keystore" && cp "$here/.keystore/probe.keystore" "$work/.keystore/"
# ANDROID_HOME passed explicitly: build.sh falls back to reading local.properties
# relative to its own location, and from a scratch directory there is no repository above
# it. Without this it fails, and silently if the output is discarded.
if [ -z "${ANDROID_HOME:-}" ]; then
    root="$(cd "$here/../.." && pwd)"
    ANDROID_HOME="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" 2>/dev/null | head -1)}"
fi
export ANDROID_HOME
OUT="$work/out" bash "$work/build.sh" > "$work/build.log" 2>&1 || {
    echo "next-version build failed:" >&2
    tail -20 "$work/build.log" >&2
    exit 1
}

cp "$work/out/probe.apk" "$out/probe-next.apk"
echo "Built: $out/probe-next.apk (versionCode $next, $(stat -c%s "$out/probe-next.apk") bytes)"
