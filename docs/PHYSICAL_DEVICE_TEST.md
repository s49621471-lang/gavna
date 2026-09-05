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

Thirty-six tests, run in order; each builds on the state the previous one left.

| Test | What a failure would mean |
|---|---|
| `t01` import, register, create an instance | The APK could not be read, or was left writable (W^X) |
| `t02` the app sees its own identity | The `LoadedApk` graft did not take |
| `t03` nothing written into UNIQUE's own directories | Storage redirection is leaking |
| `t04` data survives a full process kill | The instance directory is not where the app thinks it is |
| `t05` a second instance is fully independent | Two clones would share data |
| `t06` a crash kills neither UNIQUE nor a sibling | Process isolation is not real |
| `t07` the guest's Service runs, started **and** bound | Service routing, or the bind rename (§6.2.1) |
| `t08` the guest's manifest receiver gets broadcasts | Receiver bridging into a live process |
| `t09` the guest's ContentProvider answers its authority | Provider publication |
| `t10` the guest starts its own second Activity | `IActivityTaskManager` routing — every screen after the first |
| `t11` a `PendingIntent` the guest built fires into the guest | Stub intent identity (§6.4.1) |
| `t12` a runtime permission belongs to the instance | Permission answers, both routes |
| `t13` a grant survives the process being killed | Permission persistence |
| `t14` app ops accept the guest's identity | `checkPackage`, which gates half the platform |
| `t15` a notification is posted, and two instances do not collide | Identity, channel and id namespacing, icon flattening |
| `t16` the guest's foreground service starts | Android 14 FGS types, declared on the *stub* |
| `t17` the guest loads and runs its own native library | **ARM64 JNI.** The main reason this run matters |
| `t18` the guest's job is scheduled, and the system runs it | `JobScheduler` proxying and `jobFinished` |
| `t19` the guest sets alarms and uses the clipboard | Caller-identity hooks on two more services |
| `t20` the guest renders with OpenGL, and the pixel reads back | EGL and GLES under the graft |
| `t21` the guest reads its own signature, and the truth about GMS | `signingInfo` *and* legacy `signatures` |
| `t22` two instances have different device identities | `DeviceProfileProvider`, and `Settings.Secure` interception |
| `t23` an update keeps the instance's data | In-place update: data is keyed by vuid, never by version |
| `t24` an update signed by someone else is refused | Signature continuity |
| `t25` a *dead* guest is woken by a broadcast | `VirtualBroadcastRouter`, and the reserved cold-broadcast stub |
| `t26` UNIQUE itself reads a guest's provider | Cross-process provider routing, `:core` → `:vappN` |
| `t27` a guest reaches its own provider in another process | The same, `:vappN` → `:vappM`, via the host router |
| `t28` the guest brings up Vulkan if the device has it | **On a real GPU this is the first real Vulkan run.** Instance, physical device, logical device, graphics queue |
| `t29` cold start, warm start and memory are measured | Nothing, on its own — it records numbers. It fails only if the *ordering* is wrong (fork, then Application, then Activity) or a warm start forked a new process |
| `t30` the guest runs a WebView | The class loader or native loader could not cope with WebView's separate APK, or its data directory is not the instance's — which would mean a guest writing cookies into UNIQUE's storage. **Rendering is asserted only if the host can render**, checked in a process of its own |
| `t31` the guest starts its own activity implicitly | Implicit resolution against the guest's own filters. Also the prerequisite for browser-based OAuth (§9.5) |
| `t32` a provider process dying leaves UNIQUE working | Process isolation across the cross-process provider path |
| `t33` a native crash leaves a diagnostic record | The native signal handler. **On ARM64 this is its first real run** — the handler is architecture-independent but has only ever fired on x86_64 |
| `t34` a guest shares one of its own files with something outside it | Outgoing `content://` rewriting and the shared authority — the mechanism behind every share button in a virtual app |
| `t35` the Google routing decision follows this device | The router claiming a Google capability the device does not have. **On a phone with Play services this is the first run where the answers are not all "absent"** — see `docs/GOOGLE_DEVICE_TEST.md` |
| `t36` a guest reaching another app's provider | The request being diverted into the virtual provider table instead of going to the platform, or coming back as a hang rather than an answer. Whether it then *succeeds* is the device's answer and is recorded, not demanded |

An OEM build that diverges will usually fail *one* of these, and which one names the
subsystem. Send the whole run directory regardless — `engine.log` says more than the
table does.

### The three that only a phone can answer

`t17`, `t20` and `t28` are the reason this checklist exists.

- **`t17`** loads an ARM64 `.so` from the instance's own directory and calls into it. On
  the emulator the same test runs against x86_64, which proves the plumbing and nothing
  about the ABI.
- **`t20`** renders through a real GPU driver rather than a software rasteriser.
- **`t28`** creates a Vulkan instance, enumerates physical devices, and creates a logical
  device with a graphics queue. The emulator does declare
  `FEATURE_VULKAN_HARDWARE_VERSION` and the whole chain passes there — but on `llvmpipe`,
  a *software* device. A hardware ICD is a different code path and stays `NOT_TESTED`.
- **`t30`** creates a WebView. Rendering is `NOT_TESTED` on the emulator because Chromium's
  renderer crashes there outside virtualization too; on a phone the same test asserts the
  page loaded and its JavaScript ran.
- **`t33`** crashes a guest deliberately in native code and checks UNIQUE wrote a record.
  The signal handler is architecture-independent, but it has only ever fired on x86_64.

## Also worth doing by hand

The suite is headless; it never looks at the screen. Two minutes of manual checking cover
what it cannot:

1. `adb shell am start -n com.unique/.MainActivity` — UNIQUE's own interface should open,
   dark, with the mark in the app bar.
2. Add the probe through **Add App**. Both routes should work: *Installed* lists apps on
   the device, and *APK* opens the system file picker (select a base APK together with
   its splits — they are one import). Then launch it from Home. **Look at the screen**:
   the probe draws a black page of white `key = value` lines. If it renders, the
   virtualized activity is genuinely drawing through the normal Android surface path.
3. Rotate the device. The probe should survive it.
4. Pull down the shade. The probe's notification from `t15` should be there, **with a
   visible icon** — a blank or wrong icon means the flattening in §6.7 did not work on
   this OEM's SystemUI. Run two instances and check both notifications appear.
5. **Settings → Advanced → Export diagnostics.** It writes a zip into UNIQUE's own cache
   and shows the path. Pull it off the device and send it with the run:

   ```bash
   adb exec-out run-as com.unique tar c cache/diagnostics 2>/dev/null > diagnostics.tar
   # or, on a build where run-as is unavailable:
   adb shell am start -n com.unique/.MainActivity   # then use the path the UI shows
   ```

   It contains UNIQUE's structured log, the log pulled from every `:vappN` alive at the
   time, crash records pushed here by processes that already died, and a description of
   the device. It contains **nothing** from inside a virtualized app — no databases, no
   shared preferences, no cookies, no tokens — and every line has been through the
   redactor. Taking it *while the app that misbehaved is still running* is worth far more
   than taking it afterwards: the per-process logs are pulled live.
6. Note anything that looks wrong even if the suite passed — a flash of the wrong colour
   at launch, a wrong window size, a missing status bar. Those are the fidelity problems
   an assertion cannot see.

## Known limits before you start

- No Google flow has been implemented — those interfaces have no bodies yet, and `t21`
  asserts that UNIQUE *reports* that honestly rather than that anything works.
- A broadcast arriving while UNIQUE's own process is not running is missed: the
  registrations live there. A guest that is merely closed is woken fine (`t25`).
- Play Integrity, Play Games and Play Billing are expected not to work.
- There is no AOT: since Android 10 an app cannot invoke `dex2oat`, so virtual apps are
  JIT-only and cold start is slower than an installed app. This is a platform property,
  not something to report.
- On Xiaomi/HyperOS, aggressive background management may kill `:vappN` processes;
  if a test fails there, check `processes.txt` before assuming an engine bug.
- `t15` needs POST_NOTIFICATIONS. On an OEM build that refuses the `UiAutomation` grant,
  `t15` will fail with an empty active-notification list; grant it by hand and re-run
  before reporting it.

See `docs/STATUS.md` for exactly what is and is not implemented.
