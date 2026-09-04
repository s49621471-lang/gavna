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

Before copying an APK to the phone, check what is inside it:

```bash
./tools/check-abi.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

It lists the ABIs, prints each native library's LOAD alignment, and runs
`zipalign -P 16`. A library whose segments are only 4 KB aligned will not load on a
16 KB-page Android 15 device, and the failure arrives at `dlopen` time inside the app
with a message that names the library and not the cause. Expected output:

```
== ABIs ==
  arm64-v8a
== 16 KB page alignment ==
  lib/arm64-v8a/libunique_native.so  0x4000  OK
  ...
RESULT: OK
```

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
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# The probe must NOT be installed: it travels inside the test APK's assets, so
# that the platform's refusal to build a class loader for an uninstalled package
# is actually exercised.
adb uninstall com.unique.probe

adb shell pm clear com.unique
adb logcat -c
adb logcat -v time > logcat.txt &

adb shell am instrument -w -r \
  -e class com.unique.app.VirtualLaunchTest \
  com.unique.test/androidx.test.runner.AndroidJUnitRunner

kill %1
grep -a "Unique\|UniqueProbe" logcat.txt > engine.log
```

`pm clear` revokes UNIQUE's runtime permissions, which `t12` and `t15` need; both
grant what they require through `UiAutomation`, so no manual `pm grant` is needed.

## What the suite checks

Fifteen tests, run in order; each builds on the state the previous one left.

| Test | What a failure would mean |
|---|---|
| `t01` import, register, create an instance | The APK could not be read, or was left writable (W^X) |
| `t02` the app sees its own identity | The `LoadedApk` graft did not take |
| `t03` nothing written into UNIQUE's own directories | Storage redirection is leaking |
| `t04` data survives a full process kill | The instance directory is not where the app thinks it is |
| `t05` a second instance is fully independent | Two clones would share data |
| `t06` a crash kills neither UNIQUE nor a sibling | Process isolation is not real |
| `t07` the guest's Service runs, started **and** bound | Service routing, or the bind rename (§6.2.1) |
| `t08` the guest's manifest receiver gets broadcasts | Receiver bridging |
| `t09` the guest's ContentProvider answers its authority | Provider publication |
| `t10` the guest starts its own second Activity | `IActivityTaskManager` routing — every screen after the first |
| `t11` a `PendingIntent` the guest built fires into the guest | Stub intent identity (§6.4.1) |
| `t12` a runtime permission belongs to the instance | Permission answers, both routes |
| `t13` a grant survives the process being killed | Permission persistence |
| `t14` app ops accept the guest's identity | `checkPackage`, which gates half the platform |
| `t15` a notification is posted, and two instances do not collide | Identity, channel and id namespacing, icon flattening |

An OEM build that diverges will usually fail *one* of these, and which one names the
subsystem. Send the whole run directory regardless — `engine.log` says more than the
table does.

## Also worth doing by hand

The suite is headless; it never looks at the screen. Two minutes of manual checking cover
what it cannot:

1. `adb shell am start -n com.unique/.MainActivity` — UNIQUE's own interface should open,
   dark, with the mark in the app bar.
2. Add the probe through **Add App**, then launch it from Home. **Look at the screen**:
   the probe draws a black page of white `key = value` lines. If it renders, the
   virtualized activity is genuinely drawing through the normal Android surface path.
3. Rotate the device. The probe should survive it.
4. Pull down the shade. The probe's notification from `t15` should be there, **with a
   visible icon** — a blank or wrong icon means the flattening in §6.7 did not work on
   this OEM's SystemUI. Run two instances and check both notifications appear.
5. Note anything that looks wrong even if the suite passed — a flash of the wrong colour
   at launch, a wrong window size, a missing status bar. Those are the fidelity problems
   an assertion cannot see.

## Known limits before you start

- Nothing native has been exercised yet: the probe has no `.so` files. ARM64 JNI loading
  is phase 4, and is the main reason this run matters.
- No Google flow has been implemented — those interfaces have no bodies yet.
- A guest only receives broadcasts while it is already running; a dead one cannot be
  woken. A provider is only served inside its own virtual process.
- Foreground services are designed and not implemented, so an app that starts one will
  fail on this build.
- On Xiaomi/HyperOS, aggressive background management may kill `:vappN` processes;
  if a test fails there, check `processes.txt` before assuming an engine bug.
- `t15` needs POST_NOTIFICATIONS. On an OEM build that refuses the `UiAutomation` grant,
  `t15` will fail with an empty active-notification list; grant it by hand and re-run
  before reporting it.

See `docs/STATUS.md` for exactly what is and is not implemented.
