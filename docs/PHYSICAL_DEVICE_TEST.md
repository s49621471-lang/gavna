# Physical device test — ARM64, Android 15

Everything verified so far ran on an **x86_64 Android 14 emulator without hardware
acceleration**. That exercises the engine's Java-level graft faithfully, because the
`LoadedApk`/`ActivityThread` machinery is architecture-independent. It proves **nothing**
about ARM64 native code, real GPU paths, OEM framework forks (HyperOS, One UI), or
Android 15/16 behaviour changes.

This checklist is what closes that gap. It should take about fifteen minutes.

## What you need

- An ARM64 phone running Android 15 (or 14/16 — note which).
- USB debugging enabled.
- `adb` on your machine.
- The two APKs from this branch (see *Getting the APKs* below).

## Getting the APKs

Either take them from the CI artifacts, or build them:

```bash
export ANDROID_HOME=/path/to/android-sdk
(cd ui && flutter pub get)

# The product build: arm64-v8a only.
./gradlew :app:assembleRelease

# The acceptance suite, and the probe application it drives.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./tools/testapp/build.sh
```

Artifacts:

| File | What it is |
|---|---|
| `app/build/outputs/apk/release/app-release-unsigned.apk` | The product, ARM64 only. Sign it before installing. |
| `app/build/outputs/apk/debug/app-debug.apk` | Same engine, debuggable, with diagnostics on. |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | The acceptance suite. |
| `tools/testapp/build/probe.apk` | The application under test. **Do not install it.** |

## The one command

```bash
export ANDROID_HOME=/path/to/android-sdk
UNIQUE_ABIS=arm64-v8a ./tools/verify-device.sh
```

It builds, installs UNIQUE, pushes the probe **without installing it**, runs the suite,
and prints a per-test PASS/FAIL table. Everything lands in
`build/device-verification/<run-id>/`.

If you have several devices attached, set `ANDROID_SERIAL=<serial>`.

## What to send back

One directory:

```bash
zip -r unique-device-run.zip build/device-verification/<run-id>/
```

It contains:

| File | Why it matters |
|---|---|
| `device.properties` | Model, API level, ABI list, build fingerprint, **page size** |
| `instrumentation.txt` | Per-test results |
| `engine.log` | UNIQUE's own structured events — the useful part |
| `crashes.log` | Any `FATAL EXCEPTION`, with stack |
| `processes.txt` | Which `:vappN` processes existed at the end |
| `logcat.txt` | Full log, for anything the above misses |

None of these contain tokens, passwords or file contents. `engine.log` is structured
diagnostic events only.

**`device.properties` → `pagesize` is the single most valuable line.** If it reads
`16384`, this device is one of the 16 KB-page Android 15+ devices, and it exercises the
alignment handling that no emulator here could.

## Doing it by hand instead

If the script cannot run:

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# The probe must NOT be installed.
adb uninstall com.unique.probe
adb shell mkdir -p /sdcard/Android/data/com.unique/files
adb push tools/testapp/build/probe.apk /sdcard/Android/data/com.unique/files/probe.apk

adb shell pm clear com.unique
adb logcat -c
adb logcat -v time > logcat.txt &

adb shell am instrument -w -r \
  -e class com.unique.app.VirtualLaunchTest \
  com.unique.test/androidx.test.runner.AndroidJUnitRunner

kill %1
grep -a "Unique\|UniqueProbe" logcat.txt > engine.log
```

## Also worth doing by hand

The suite is headless; it never looks at the screen. Two minutes of manual checking cover
what it cannot:

1. `adb shell am start -n com.unique/.MainActivity` — UNIQUE's own interface should open,
   dark, with the mark in the app bar.
2. Add the probe through **Add App**, then launch it from Home. **Look at the screen**:
   the probe draws a black page of white `key = value` lines. If it renders, the
   virtualized activity is genuinely drawing through the normal Android surface path.
3. Rotate the device. The probe should survive it.
4. Note anything that looks wrong even if the suite passed — a flash of the wrong colour
   at launch, a wrong window size, a missing status bar. Those are the fidelity problems
   an assertion cannot see.

## Known limits before you start

- Nothing native has been exercised yet: the probe has no `.so` files. ARM64 JNI loading
  is phase 4.
- No Google flow has been implemented — those interfaces have no bodies yet.
- Only activities are virtualized. Services, receivers and providers are not.
- On Xiaomi/HyperOS, aggressive background management may kill `:vappN` processes;
  if a test fails there, check `processes.txt` before assuming an engine bug.

See `docs/STATUS.md` for exactly what is and is not implemented.
