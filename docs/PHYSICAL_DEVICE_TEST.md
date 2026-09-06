# Physical device test — ARM64, Android 15

Everything verified so far ran on an **x86_64 Android 14 emulator without hardware
acceleration**. That exercises the engine's Java-level graft faithfully, because the
`LoadedApk`/`ActivityThread` machinery is architecture-independent. It proves **nothing**
about ARM64 native code, real GPU paths, OEM framework forks (HyperOS, One UI), or
Android 15/16 behaviour changes.

This is what closes that gap. It needs **no `adb`, no root and no computer** to *run* —
eleven of the twelve steps are things a person does with the phone in their hand — and one
log capture to be read afterwards. UNIQUE writes every event it records to `logcat` under a
single tag, so any recorder app on the phone captures the whole run; `tools/device-log/`
reads what comes back. §3 says how.

UNIQUE used to carry the checklist and an export inside the app, under *Settings →
Advanced*. That section was removed at the request of the person it was built for, who did
not use it: every fault found in this project so far arrived as an ordinary logcat capture,
which is what §3 now describes.

There is still an automated suite that needs a PC. It is at the end, and it is optional.

---

## 1. Install UNIQUE

One APK, in `dist/`, downloadable straight to the phone:

https://github.com/erundltd/gavna/raw/claude/unique-app-virtualization-bn35b2/dist/unique-arm64-v8a.apk

`arm64-v8a` only, `minSdk 31`, signed with Android's debug key. `dist/README.md` has the
checksums and says what the second APK there is.

Take **that** one, not the minified build. It is the build the acceptance suite runs
against — `BUILD_TYPE=verify ./tools/verify-device.sh` points the whole suite at this exact
artifact — and it is unminified, so no R8 keep rule can be wrong. The minified build is
**not device-verified** (see *Known limits*), so a failure in it cannot be told apart from
a bad keep rule.

Building it yourself, if you have the SDK:

```bash
export ANDROID_HOME=/path/to/android-sdk
(cd ui && flutter pub get)
UNIQUE_ABIS=arm64-v8a ./gradlew :app:assembleVerify -Ptree-shake-icons=true
./tools/check-abi.sh app/build/outputs/apk/verify/app-verify.apk
```

`check-abi.sh` is worth the ten seconds. It lists the ABIs, prints each native library's
LOAD alignment and runs `zipalign -P 16`. A library whose segments are only 4 KB aligned
will not load on a 16 KB-page Android 15 device, and the failure arrives at `dlopen` time
inside the app with a message that names the library and not the cause.

```
== ABIs ==
  arm64-v8a
== 16 KB page alignment ==
  lib/arm64-v8a/libunique_native.so  0x4000  OK
  ...
RESULT: OK
```

Copy the APK to the phone and open it. Android will ask about installing from an unknown
source; that is expected for a sideloaded build.

---

## 2. The twelve steps

Start the log capture first (§3), then work down the table. Write your own verdict for
each step — Pass / Fail / Blocked / Skipped — and a sentence about what you saw; that
observation is read *next to* the machine's record, never instead of it, and it is the only
half of the run a log cannot contain.

Two of the device's own facts change how half the steps below should be read, and
**Settings → Engine** in UNIQUE reports both: whether the native library loaded, and the
memory page size. A `dlopen` failure means something quite specific on a phone with 16 KB
pages, and a Vulkan failure means nothing at all on a phone with no hardware driver.

| # | Step | What it establishes |
|---|---|---|
| **s01** | Install UNIQUE | It installs on this Android version and ABI at all. A failure here is usually a signature or 16 KB-page problem. |
| **s02** | Launch UNIQUE | Settings → Engine shows platform access **Granted** and the native library **Loaded**. If either is not, nothing after this can work, and the reason is on that screen. |
| **s03** | Import a simple installed app | *Add App → Installed*. Pick something small and ordinary. The import reads the APK the system already has; it downloads nothing. |
| **s04** | Launch it | **Look at the screen.** The automated suite reads files; it never looks at pixels. This is the first genuine check that a virtualized activity draws through the normal surface path. |
| **s05** | Second instance, and isolation | Clone it, launch both, store something in one — a login, a setting. The other must not see it, and *App Details* must show a **different Android ID** for each. |
| **s06** | WebView | Open something in the app that shows a web page. One of the four things only a phone can answer: the verification emulator's Chromium renderer crashes even outside virtualization, so **WebView rendering has never actually been observed**. |
| **s07** | Native / JNI | Use a feature backed by native code. **First ARM64 run of the whole native path** — library loading, JNI and the libc PLT/GOT redirection have only ever executed on x86_64. |
| **s08** | OpenGL / Vulkan | Anything that renders: a game, a map, a video. Check the Device report first — `vulkanDeviceType` and `vulkanIsHardware` say whether this phone has a hardware driver at all. |
| **s09** | Notifications and background | Make the app post a notification; check the icon is **visible and correct**, not a blank square. Then close the app and make something arrive — a message, an alarm — to see whether a dead guest wakes. |
| **s10** | Google Play services, and Sign-In | *App Details → Google* shows what this device has and how each flow *would* be routed. **No sign-in flow is implemented**, so the expected result is a clean refusal, not a login. Record exactly what happens; that is the measurement. |
| **s11** | A real Unity / IL2CPP app | The hardest case: a large native engine, its own asset loading, its own threads. Nothing is claimed about this today. Note load time and whether it renders. |
| **s12** | Save and send the log | Stop the recorder, and send what it captured. See §3. |

Do them in that order. It is chosen so that a failure explains the ones after it — nothing
can be said about WebView in a guest until a guest launches, and nothing about Unity until
native code runs.

### s10 in more detail

Google is the one area where the honest answer is more interesting than a pass. `docs/GOOGLE_DEVICE_TEST.md`
has the full procedure; the short version is that *App Details → Google* reports, per
capability, what this device has and what UNIQUE would do with it, and every flow that is
not implemented says so by name. A screen full of `absent` on a phone that plainly has Play
services is a bug worth reporting; an implemented-looking sign-in that silently fails is
the outcome this design exists to avoid.

---

## 3. The log

Everything UNIQUE records — from its own process and from every `:vappN` — goes to
`logcat`, redacted, one line per event, under a tag a capture can filter on. So the whole
run is collectable by any log-recorder app on the phone; no `adb`, no root, no computer.

Start the recorder **before** step s01 and leave it running to the end. A `:vappN` that
crashes takes its own buffers with it, and what it logged before dying is the only record
of why — a capture started afterwards has none of it.

Send the file the recorder produced. It carries:

| What | Why it matters |
|---|---|
| UNIQUE's own structured events | Every import, launch, graft, hook, rewrite and refusal, in order |
| Every `:vappN`'s events | The half UNIQUE's main process cannot see: what happened inside the guest |
| Crash records | Including ones pushed to UNIQUE's process by a `:vappN` on its way out |
| The framework's own logging | ART refusing to verify a class, the linker failing to map a library, ActivityManager's kill reason — none of which UNIQUE can observe about itself |

It contains **nothing from inside a virtualized app** — no databases, no shared
preferences, no cookies, no tokens. That is not a filter applied at the end: no code in
UNIQUE opens those directories for any diagnostic purpose. Every line still passes through
the redactor on the way out, because an app is free to log its own secrets and UNIQUE
records what apps log.

---

## 3b. Reading what came back

Send the capture. Whoever reads it runs:

```bash
tools/device-log/analyze.py capture.log
```

Ten checks, exit status 0 or 1, no SDK and no device needed. It answers the questions a
person scrolling a 27,000-line log is trying to answer and usually cannot: did every
launch reach the guest's own Activity, was every process slot handed over clean, did any
call go out under the guest's name and get refused — and *which system service to hook*
when one did.

That last one is why this exists. A `SecurityException` inside a guest names the guest's
package, so it reads as the app misbehaving; it almost never is. It means a call carried a
package name that does not belong to UNIQUE's uid, and the tool reads the
`IRestrictionsManager$Stub$Proxy` frame out of the stack to say which service was missing.

It reads a recorder app's export, an `adb logcat -v threadtime` dump and a raw
single-tag capture alike, so a run can be diagnosed from a phone with no computer near it.
`tools/device-log/README.md` has the details.

**It does not look at pixels**, which is why `s04` below is a step a person performs and
not a check. A guest that draws a black screen and logs nothing passes all ten.

---

## 4. What a phone can prove that the emulator could not

Five things, and they are the reason this document exists.

- **ARM64 native code** (`s07`). The engine's `.so` loading, JNI and libc redirection have
  run thousands of times — always on x86_64.
- **A real GPU** (`s08`). OpenGL is device-proven on a software rasteriser. That proves the
  EGL plumbing under the graft and nothing about a driver.
- **Hardware Vulkan** (`s08`). The emulator *does* declare `FEATURE_VULKAN_HARDWARE_VERSION`
  and the whole chain passes there — on `llvmpipe`, a **software** device. A hardware ICD is
  a different code path.
- **WebView rendering** (`s06`). Chromium's renderer crashes on the verification emulator
  outside virtualization too, so the automated test asserts only that the WebView was
  created and its data directory is the instance's.
- **A real application** (`s11`, and everything before it). The probe app is written to be
  probed. A shipping app is not.

Until your run says otherwise, all five stay `NOT_TESTED` in `docs/COMPATIBILITY.md`. A
a person's own verdict does not move them either — a verdict is an observation, and it
travels with the run so it can be read, not substituted for evidence.

---

## 5. Optional: the automated suite (needs a PC)

Thirty-six instrumented tests, all currently green on the emulator. On a phone they are
worth running because five of them are answering a genuinely different question there.

```bash
export ANDROID_HOME=/path/to/android-sdk
UNIQUE_ABIS=arm64-v8a ./tools/verify-device.sh
```

It builds, installs UNIQUE and the suite, keeps the probe app **uninstalled** (it travels
inside the test APK's assets, so the platform's refusal to build a class loader for an
unknown package is actually exercised), runs everything and prints a per-test PASS/FAIL
table. Artifacts land in `build/device-verification/<run-id>/`. Set `ANDROID_SERIAL` if
several devices are attached.

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
| `t17` the guest loads and runs its own native library | **ARM64 JNI.** The main reason a phone run matters |
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
| `t28` the guest brings up Vulkan if the device has it | **On a real GPU this is the first hardware Vulkan run.** Instance, physical device, logical device, graphics queue |
| `t29` cold start, warm start and memory are measured | Nothing on its own — it records numbers. It fails only if the *ordering* is wrong (fork, then Application, then Activity) or a warm start forked a new process |
| `t30` the guest runs a WebView | The class loader or native loader could not cope with WebView's separate APK, or its data directory is not the instance's — which would mean a guest writing cookies into UNIQUE's storage. **Rendering is asserted only if the host can render**, checked in a process of its own |
| `t31` the guest starts its own activity implicitly | Implicit resolution against the guest's own filters. Also the prerequisite for browser-based OAuth (§9.5) |
| `t32` a provider process dying leaves UNIQUE working | Process isolation across the cross-process provider path |
| `t33` a native crash leaves a diagnostic record | The native signal handler. **On ARM64 this is its first real run** — the handler is architecture-independent but has only ever fired on x86_64 |
| `t34` a guest shares one of its own files with something outside it | Outgoing `content://` rewriting and the shared authority — the mechanism behind every share button in a virtual app |
| `t35` the Google routing decision follows this device | The router claiming a Google capability the device does not have. **On a phone with Play services this is the first run where the answers are not all "absent"** |
| `t36` a guest reaching another app's provider | The request being diverted into the virtual provider table instead of going to the platform, or coming back as a hang rather than an answer. Whether it then *succeeds* is the device's answer, and is recorded rather than demanded |

An OEM build that diverges will usually fail *one* of these, and which one names the
subsystem. Send the whole run directory regardless — `engine.log` says more than the table
does. `device.properties → pagesize` is the single most valuable line in it.

Doing it by hand instead:

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb uninstall com.unique.probe        # it must NOT be installed
adb shell pm clear com.unique
adb logcat -c && adb logcat -v time > logcat.txt &
adb shell am instrument -w -r -e class com.unique.app.VirtualLaunchTest \
  com.unique.test/androidx.test.runner.AndroidJUnitRunner
kill %1
grep -a "Unique\|UniqueProbe" logcat.txt > engine.log
```

`pm clear` revokes UNIQUE's runtime permissions, which `t12` and `t15` need; both grant
what they require through `UiAutomation`, so no manual `pm grant` is necessary.

---

## Known limits before you start

- **The release build is not device-verified.** It builds, minifies and signs, and its keep
  rules hold structurally — the stub pool, the router and shared providers, `UniqueNative`
  and the native entry points all survive R8, checked on the artifact. But the instrumented
  suite cannot run against it (`androidx.tracing.Trace` resolves from R8's *classpath*
  rather than program input, so no keep rule reaches it), and a class surviving is not the
  same as a reflective lookup succeeding. A virtualization engine is nearly all reflection;
  an unverified minified build is exactly the one to be suspicious of. **Test
  `unique-arm64-v8a.apk`**, which the suite does run against. Install the minified one only
  to see whether it starts at all, and if it behaves differently, that difference is the
  finding.
- **No Google flow is implemented.** Those interfaces have no bodies. `t21` and `s10` assert
  that UNIQUE *reports* that honestly, not that anything works. Play Integrity, Play Games
  and Play Billing are expected not to work.
- **A broadcast arriving while UNIQUE's own process is not running is missed** — the
  registrations live there. A guest that is merely closed is woken fine (`t25`, `s09`).
- **There is no AOT.** Since Android 10 an app cannot invoke `dex2oat`, so virtual apps are
  JIT-only and cold start is slower than an installed app. That is a platform property, not
  a defect to report.
- **Aggressive OEM background management** (Xiaomi/HyperOS especially) may kill `:vappN`
  processes. If something dies in the background, check whether the OEM killed it before
  assuming an engine bug — `unique.log` will show the process going away without an error.
- **`s09` needs POST_NOTIFICATIONS.** Grant it when asked; on an OEM build that hides the
  prompt, grant it in system settings before running the step.

See `docs/STATUS.md` for exactly what is and is not implemented, and
`docs/COMPATIBILITY.md` for what is claimed.
