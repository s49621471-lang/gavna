#!/usr/bin/env bash
# Injects prebuilt ESP artifacts into a BLOCKPOST APK and signs the result.
#
#   ./apply.sh <input.apk> [output.apk]
#
# Expects out/libesp.so and out/payload.dex next to this script — build.sh
# produces both. No NDK or JDK compiler needed here, only the android
# build-tools (zipalign, apksigner) and keytool.
set -euo pipefail

IN="${1:?usage: apply.sh <input.apk> [output.apk]}"
OUT="${2:-${IN%.apk}-esp.apk}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$ROOT/.work"

TOOLS="${TOOLS:-$ROOT/tools}"
BUILD_TOOLS="${BUILD_TOOLS:-$TOOLS/android-14}"

OLD_APP="androidx.multidex.MultiDexApplication"
NEW_APP="com.skullcapstudios.esp.EspOverlayApp"

for f in "$ROOT/out/libesp.so" "$ROOT/out/payload.dex" \
         "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner"; do
    [ -e "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf "$WORK"; mkdir -p "$WORK"
cp "$IN" "$WORK/patched.apk"
cd "$WORK"

echo "[1/4] manifest"
unzip -o -q patched.apk AndroidManifest.xml
python3 - "$OLD_APP" "$NEW_APP" <<'PY'
import sys
old, new = sys.argv[1], sys.argv[2]
assert len(old) == len(new), "replacement class name must match the original length"
p = "AndroidManifest.xml"
d = open(p, "rb").read()
o, n = old.encode("utf-16-le"), new.encode("utf-16-le")
if d.count(n):
    print("    already patched")
elif d.count(o) == 1:
    open(p, "wb").write(d.replace(o, n))
    print("    application class ->", new)
else:
    raise SystemExit("expected exactly one %r in the manifest, found %d" % (old, d.count(o)))
PY

echo "[2/4] repack"
# Next free multidex slot. Deliberately pipeline-free: `cmd | grep -q` leaves cmd
# killed by SIGPIPE, which under `pipefail` reads as "not found" and would
# silently overwrite one of the game's own dex files.
LIST="$(unzip -Z1 patched.apk)"
N=2
while [[ "$LIST" == *"classes$N.dex"* ]]; do N=$((N + 1)); done
[[ "$LIST" != *"classes$N.dex"* ]] || { echo "refusing to overwrite classes$N.dex"; exit 1; }
cp "$ROOT/out/payload.dex" "classes$N.dex"
echo "    payload dex -> classes$N.dex"

mkdir -p lib/arm64-v8a
cp "$ROOT/out/libesp.so" lib/arm64-v8a/libesp.so
zip -q patched.apk AndroidManifest.xml "classes$N.dex" lib/arm64-v8a/libesp.so
zip -qd patched.apk 'META-INF/*.RSA' 'META-INF/*.SF' 'META-INF/*.DSA' >/dev/null 2>&1 || true

echo "[3/4] align + sign"
"$BUILD_TOOLS/zipalign" -p -f 4 patched.apk aligned.apk
KS="$ROOT/out/esp.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass espesp -keypass espesp \
        -alias esp -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=esp" >/dev/null 2>&1
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass pass:espesp --key-pass pass:espesp \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT" aligned.apk

cd "$ROOT"
echo "[4/4] verify"
python3 - "$IN" "$OUT" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
a = {i.filename: i.CRC for i in zipfile.ZipFile(src).infolist()}
b = {i.filename: i.CRC for i in zipfile.ZipFile(dst).infolist()}
skip = lambda n: n.startswith("META-INF/") or n == "AndroidManifest.xml"
missing = [n for n in a if n not in b and not skip(n)]
changed = [n for n in a if n in b and a[n] != b[n] and not skip(n)]
added   = sorted(n for n in b if n not in a and not skip(n))
if missing: raise SystemExit("    LOST %d entries: %s" % (len(missing), missing[:5]))
if changed: raise SystemExit("    ALTERED %d entries: %s" % (len(changed), changed[:5]))
print("    %d entries carried over byte-identical" % len(a))
print("    added: %s" % ", ".join(added))
PY

rm -rf "$WORK"
ls -lh "$OUT"
echo "done -> $OUT"
