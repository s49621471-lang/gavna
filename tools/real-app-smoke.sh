#!/usr/bin/env bash
#
# Imports and launches real, shipping applications, and reads what happened out of logcat.
#
# The acceptance suite proves the engine against an app written for it. This proves nothing
# so tidy: it takes APKs the project did not write, runs each one, and reports the first
# thing that went wrong. Every fault it has found so far was invisible to the probe, because
# the probe does not call the APIs that broke.
#
# Usage:
#
#   tools/real-app-smoke.sh                     # every APK already staged on the device
#   tools/real-app-smoke.sh app1.apk app2.apk   # only these, staged from the host
#
# APKs live in /data/local/tmp/unique-real on the device. Anything given on the command
# line is pushed there first. The debug and instrumentation APKs must already be installed
# — `tools/verify-device.sh` does that, or:
#
#   ./gradlew :app:installDebug :app:installDebugAndroidTest
#
# It is *not* a gate and is not run by `verify-device.sh`. An app downloaded from F-Droid
# can change under it, and a failure would then be a fact about F-Droid rather than about
# UNIQUE. What it produces is a report a person reads.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
STAGE=/data/local/tmp/unique-real
RUNNER=com.unique.test/androidx.test.runner.AndroidJUnitRunner
CLASS=com.unique.app.RealAppSmokeTest
logs="$root/build/real-app-smoke"
mkdir -p "$logs"

$ADB shell mkdir -p "$STAGE" > /dev/null 2>&1

for apk in "$@"; do
    [ -f "$apk" ] || { echo "no such file: $apk" >&2; exit 1; }
    echo "== pushing $(basename "$apk")"
    $ADB push "$apk" "$STAGE/" > /dev/null || exit 1
done

names=$($ADB shell ls "$STAGE" 2>/dev/null | tr -d '\r' | grep '\.apk$')
if [ -z "$names" ]; then
    echo "nothing staged in $STAGE — pass APKs on the command line" >&2
    exit 1
fi

fail=0
summary=""
for name in $names; do
    echo "== $name"
    $ADB logcat -c > /dev/null 2>&1
    $ADB logcat -v time > "$logs/$name.log" 2>&1 &
    logcat_pid=$!

    $ADB shell am instrument -w -r -e class "$CLASS" -e apk "$name" "$RUNNER" \
        > "$logs/$name.instrumentation.txt" 2>&1
    sleep 3
    kill $logcat_pid 2>/dev/null

    log="$logs/$name.log"
    pkg=$(grep -ao "REAL_APP import ok package=[^ ]*" "$log" | head -1 | cut -d= -f2)
    pkg=${pkg:-"?"}

    verdict=""
    if grep -qa "import rejected" "$logs/$name.instrumentation.txt"; then
        verdict="import rejected"
    elif ! grep -qa "TRANSACTION_REWRITTEN" "$log"; then
        # The stub ran instead of the app, or the process never got that far.
        reason=$(grep -aom1 "BOOTSTRAP_FAILED[^\"]*" "$log")
        verdict="the launch transaction was never rewritten${reason:+ — $reason}"
    elif grep -qa "UNCAUGHT_EXCEPTION" "$log"; then
        verdict="crashed: $(grep -aom1 'UNCAUGHT_EXCEPTION.*' "$log" | cut -c1-200)"
    elif grep -qa "Fatal signal" "$log"; then
        # libc's own line, which appears whether or not UNIQUE's handler got to write a
        # record. `NATIVE_CRASH_HANDLER` is *not* this: that one says the handler installed.
        verdict="native crash: $(grep -aom1 'Fatal signal.*' "$log" | cut -c1-200)"
    else
        # `System.exit` is worth catching — it is what a failed licence or integrity check
        # looks like from outside, with no crash and no message — but only when the *guest*
        # called it. The instrumentation runner exits the same way at the end of every run,
        # and reading that as the app's was the first version's mistake.
        vpid=$(grep -aom1 "Unique  ( *[0-9]*): .*PROCESS_START process=com.unique:vapp" "$log"             | sed -n 's/.*Unique  ( *\([0-9]*\)).*/\1/p')
        if [ -n "$vpid" ] && grep -qa "( *$vpid): System.exit called" "$log"; then
            verdict="the app called System.exit — usually a licence or integrity check"
        fi
    fi

    if [ -n "$verdict" ]; then
        fail=1
        summary="$summary\n  FAIL  $pkg  ($name)\n          $verdict"
    else
        accel=$(grep -aom1 "ACTIVITY_HARDWARE_ACCELERATED.*applied=[a-z]*" "$log" | cut -c1-120)
        summary="$summary\n  PASS  $pkg  ($name)${accel:+\n          $accel}"
    fi
done

echo
echo "== real-app smoke =="
printf '%b\n' "$summary"
echo
echo "logs in $logs"
exit $fail
