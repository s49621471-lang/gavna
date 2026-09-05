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
#   UNIQUE_ABIS   ABIs to build (default: the ABI this device actually runs)
#   BUILD_TYPE    debug (default), verify or release
#                 verify  = what a physical-device tester is given: unminified engine,
#                           Flutter built ahead of time, signed
#                 release = the minified, signed build
#   TESTS         restrict to one test, e.g. TESTS=t02_launchesAndTheAppSeesItsOwnIdentity
#   SKIP_BUILD    reuse the APKs from the last build
#   RUN_ID        override the generated run id
set -uo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME}"
ADB="$ANDROID_HOME/platform-tools/adb"
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

# The ABI defaults to what the attached device runs, rather than to the release ABI.
# Getting this wrong costs a full build before adb says INSTALL_FAILED_NO_MATCHING_ABIS,
# and the run that produced that lesson had already spent four minutes compiling arm64
# for an x86_64 emulator. Release policy is arm64-first (§8.1) and is set by the release
# build, not by what a test happens to be attached to.
if [ -n "${UNIQUE_ABIS:-}" ]; then
    ABIS="$UNIQUE_ABIS"
else
    case ",$abi," in
        *,arm64-v8a,*) ABIS="arm64-v8a" ;;
        *,x86_64,*)    ABIS="x86_64" ;;
        *) fail "device reports no ABI UNIQUE builds for: $abi" ;;
    esac
fi
echo "   building for $ABIS"
# Which build the suite runs against. Debug by default, because that is the build being
# developed. `BUILD_TYPE=verify` points it at the build a physical-device tester is
# actually handed — same unminified engine, Flutter built ahead of time — because an
# artifact nobody ran the suite against is an artifact nobody has checked.
# `BUILD_TYPE=release` points it at the minified one, which is how the R8 keep rules would
# be established as correct rather than merely plausible. A virtualization engine is nearly
# all reflection, and a keep rule that is wrong produces an app that works everywhere except
# the build people actually install.
BUILD_TYPE="${BUILD_TYPE:-debug}"
case "$BUILD_TYPE" in
    debug)   ASSEMBLE=(:app:assembleDebug :app:assembleDebugAndroidTest)
             APP_APK="app/build/outputs/apk/debug/app-debug.apk"
             TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" ;;
    verify)  ASSEMBLE=(:app:assembleVerify :app:assembleVerifyAndroidTest)
             APP_APK="app/build/outputs/apk/verify/app-verify.apk"
             TEST_APK="app/build/outputs/apk/androidTest/verify/app-verify-androidTest.apk" ;;
    release) ASSEMBLE=(:app:assembleRelease :app:assembleReleaseAndroidTest)
             APP_APK="app/build/outputs/apk/release/app-release.apk"
             TEST_APK="app/build/outputs/apk/androidTest/release/app-release-androidTest.apk" ;;
    *) fail "unknown BUILD_TYPE=$BUILD_TYPE (expected debug, verify or release)" ;;
esac
echo "   build type $BUILD_TYPE"

{ echo "runId=$RUN_ID"; echo "model=$model"; echo "sdk=$sdk"; echo "abilist=$abi";
  echo "fingerprint=$(adb shell getprop ro.build.fingerprint | tr -d '\r')";
  echo "pagesize=$(adb shell getconf PAGE_SIZE | tr -d '\r')";
  echo "uniqueAbis=$ABIS"; echo "buildType=$BUILD_TYPE"; } > "$logs/device.properties"


if [ -z "${SKIP_BUILD:-}" ]; then
    echo "== build probe =="
    timeout 900 "$root/tools/testapp/build.sh" > "$logs/build-probe.log" 2>&1 || fail "probe build"
    echo "== build UNIQUE ($BUILD_TYPE, abis: $ABIS) =="
    ( cd "$root" && timeout 2400 ./gradlew "${ASSEMBLE[@]}" \
        -Punique.abis="$ABIS" -PuniqueTestBuildType="$BUILD_TYPE" --console=plain ) \
        > "$logs/build-unique.log" 2>&1 || fail "unique build"
fi
probe="$root/tools/testapp/build/probe.apk"

# The build daemons are stopped before the suite runs, rather than left resident.
#
# Gradle's daemon holds around 3 GB and Kotlin's around 1.8 GB, and on a machine sized for
# one emulator that is the emulator's memory. With both resident the verification emulator
# ran at load 10 and the platform killed processes before they could attach:
#
#   Process ProcessRecord{… 11888:com.unique:vapp2} failed to attach
#   Killing 11888:com.unique:vapp2 (adj -10000): start timeout
#
# That is the platform's own ten-second process-start timeout, expiring before a single
# line of UNIQUE's code has run — so it fails tests that have nothing to do with what they
# are testing. `com.android.bluetooth` was killed the same way in the same second, which is
# how it was finally identified as the machine rather than the engine. Stopping the daemons
# dropped the same emulator to load 1.
( cd "$root" && ./gradlew --stop ) > /dev/null 2>&1 || true
pkill -f KotlinCompileDaemon > /dev/null 2>&1 || true

echo "== install =="
# Piping to tail would swallow adb's exit status, and a failed install is invisible:
# the suite then runs against whatever was installed before and reports on stale code.
# INSTALL_FAILED_NO_MATCHING_ABIS is the one that bites, when UNIQUE_ABIS is left at its
# arm64-v8a default for an x86_64 emulator.
install_apk() {
    local apk="$1" out
    out="$(timeout 900 "$ADB" "${serial_args[@]}" install -r -g "$apk" 2>&1)" || {
        echo "$out" | tail -3
        fail "install of $(basename "$apk") failed"
    }
    case "$out" in
        *Success*) echo "   installed $(basename "$apk")" ;;
        *) echo "$out" | tail -3; fail "install of $(basename "$apk") did not report Success" ;;
    esac
}
install_apk "$root/$APP_APK"
install_apk "$root/$TEST_APK"

echo "== reset state =="
adb shell pm clear com.unique > /dev/null
adb shell am force-stop com.unique > /dev/null 2>&1

# The probe is deliberately NOT installed on the device.
#
# UNIQUE exists to run applications the device does not have installed, and having the
# probe installed would hide the platform's refusal to build a class loader for an
# unknown package. The APK travels inside the instrumentation APK as an asset, so nothing
# needs to be pushed and scoped storage is not in the way.
adb uninstall com.unique.probe > /dev/null 2>&1
if adb shell pm path com.unique.probe 2>/dev/null | grep -q package; then
    fail "com.unique.probe is still installed; the run would not prove anything"
fi
echo "   probe carried in the test APK, not installed on the device"

# Bluetooth off, on an emulator.
#
# Nothing in UNIQUE touches Bluetooth, but on this software-only emulator the stack
# cannot finish its own handshake under load: `AdapterState TURNING_ON :
# BREDR_START_TIMEOUT`, the process dies, `BluetoothManagerService` starts it again, and
# from then on `com.android.bluetooth` restarts every twenty seconds for the rest of the
# run. In the run before this line existed that loop began during `t36` and cost 45
# process starts; the guest launches after it took minutes instead of seconds and four
# tests timed out waiting for an app that was simply not being scheduled.
#
# Only on an emulator: a physical device's Bluetooth is the owner's to decide, and a run
# must not turn theirs off.
if adb shell getprop ro.build.characteristics | grep -q emulator; then
    adb shell svc bluetooth disable > /dev/null 2>&1 || true
    adb shell settings put global bluetooth_on 0 > /dev/null 2>&1 || true
    echo "   bluetooth disabled (emulator only; its stack crash-loops under load here)"
fi

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
