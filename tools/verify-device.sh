#!/usr/bin/env bash
# UNIQUE on-device acceptance run.
#
# Builds UNIQUE and the probe app, installs both, runs the acceptance suite and saves
# every artifact under a run directory named by a unique run id. Nothing is ever read
# from a previous run: stale readiness files are how a failing build gets reported as a
# pass.
#
#   ANDROID_HOME=/opt/android-sdk ./tools/verify-device.sh
#
# Env:
#   UNIQUE_ABIS   ABIs to build (default arm64-v8a; use x86_64 for an emulator)
#   TESTS         restrict to one test, e.g. TESTS=t02_launchesAndTheAppSeesItsOwnIdentity
#   SKIP_BUILD    reuse the APKs from the last build
#   RUN_ID        override the generated run id
set -uo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME}"
ADB="$ANDROID_HOME/platform-tools/adb"
ABIS="${UNIQUE_ABIS:-arm64-v8a}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%d-%H%M%S)-$$}"
root="$(cd "$(dirname "$0")/.." && pwd)"
logs="$root/build/device-verification/$RUN_ID"
rm -rf "$logs"; mkdir -p "$logs"
ln -sfn "$RUN_ID" "$root/build/device-verification/latest"

serial_args=()
[ -n "${ANDROID_SERIAL:-}" ] && serial_args=(-s "$ANDROID_SERIAL")
adb() { "$ADB" "${serial_args[@]}" "$@"; }

fail() { echo "RESULT: FAIL   $*   (logs in $logs)"; exit 1; }

echo "== run $RUN_ID =="
echo "== device =="
timeout 120 "$ADB" "${serial_args[@]}" wait-for-device || fail "no device"
abi=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
model=$(adb shell getprop ro.product.model | tr -d '\r')
echo "   $model  API $sdk  $abi"
{ echo "runId=$RUN_ID"; echo "model=$model"; echo "sdk=$sdk"; echo "abilist=$abi";
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')";
  echo "pagesize=$(adb shell getconf PAGE_SIZE | tr -d '\r')";
  echo "uniqueAbis=$ABIS"; } > "$logs/device.properties"

if [ -z "${SKIP_BUILD:-}" ]; then
    echo "== build probe =="
    timeout 900 "$root/tools/testapp/build.sh" > "$logs/build-probe.log" 2>&1 || fail "probe build"
    echo "== build UNIQUE (abis: $ABIS) =="
    ( cd "$root" && timeout 2400 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
        -Punique.abis="$ABIS" --console=plain ) > "$logs/build-unique.log" 2>&1 || fail "unique build"
fi
probe="$root/tools/testapp/build/probe.apk"

echo "== install =="
timeout 900 "$ADB" "${serial_args[@]}" install -r -g "$root/app/build/outputs/apk/debug/app-debug.apk" | tail -1
timeout 900 "$ADB" "${serial_args[@]}" install -r "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" | tail -1

echo "== reset state =="
adb shell pm clear com.unique > /dev/null
adb shell am force-stop com.unique > /dev/null 2>&1

# The probe is deliberately NOT installed on the device.
#
# UNIQUE exists to run applications the device does not have installed, and having the
# probe installed would hide the fact that the platform refuses to build a class loader
# for an unknown package. The APK is pushed into UNIQUE's own external files directory,
# which the app can read without any permission, and imported from there.
adb uninstall com.unique.probe > /dev/null 2>&1
probe_on_device="/sdcard/Android/data/com.unique/files/probe.apk"
adb shell mkdir -p /sdcard/Android/data/com.unique/files > /dev/null 2>&1
timeout 300 "$ADB" "${serial_args[@]}" push "$probe" "$probe_on_device" > /dev/null || fail "probe push"
if adb shell pm path com.unique.probe 2>/dev/null | grep -q package; then
    fail "com.unique.probe is still installed; the run would not prove anything"
fi
echo "   probe pushed to $probe_on_device, not installed"

echo "== run acceptance suite =="
adb logcat -c 2>/dev/null
adb logcat -v time > "$logs/logcat.txt" 2>&1 &
logcat_pid=$!
trap 'kill $logcat_pid 2>/dev/null' EXIT

# `am instrument` takes a comma-separated list of class#method entries, so a TESTS value
# like "t01_x,t02_y" is expanded into fully-qualified entries here.
cls="com.unique.app.VirtualLaunchTest"
if [ -n "${TESTS:-}" ]; then
    spec=""
    IFS=',' read -ra methods <<< "$TESTS"
    for m in "${methods[@]}"; do
        [ -n "$spec" ] && spec="$spec,"
        spec="$spec$cls#$m"
    done
else
    spec="$cls"
fi
filter=(-e class "$spec")

timeout 3600 "$ADB" "${serial_args[@]}" shell am instrument -w -r "${filter[@]}" \
    com.unique.test/androidx.test.runner.AndroidJUnitRunner \
    > "$logs/instrumentation.txt" 2>&1
status=$?

sleep 3
kill $logcat_pid 2>/dev/null

# Keep only the engine's own lines: a 5 MB logcat is not something anyone reads.
grep -a "Unique\|UniqueProbe\|UniqueNative" "$logs/logcat.txt" > "$logs/engine.log" 2>/dev/null
grep -a -A 20 "FATAL EXCEPTION" "$logs/logcat.txt" > "$logs/crashes.log" 2>/dev/null
adb shell ps -A 2>/dev/null | grep -E "unique|probe" > "$logs/processes.txt"

echo
echo "== per-test results =="
awk '/INSTRUMENTATION_STATUS: test=/ {t=$0; sub(/.*test=/,"",t)}
     /INSTRUMENTATION_STATUS_CODE: 0/  {print "  PASS  " t}
     /INSTRUMENTATION_STATUS_CODE: -2/ {print "  FAIL  " t}
     /INSTRUMENTATION_STATUS_CODE: -3/ {print "  SKIP  " t}' "$logs/instrumentation.txt" | sort -u

echo
echo "== engine events =="
sed -E 's/^.*\/Unique(Native|Probe)? *\( *[0-9]+\): //' "$logs/engine.log" | cut -c1-200 | tail -40

if grep -q "OK (" "$logs/instrumentation.txt"; then
    echo; echo "RESULT: PASS   run=$RUN_ID   (logs in $logs)"
    exit 0
fi
echo; echo "RESULT: FAIL   run=$RUN_ID   (logs in $logs)"
exit ${status:-1}
