# UNIQUE

Application-level Android virtualization: run an installed app or an APK inside UNIQUE,
with its own data, its own device identity, and as many independent instances as you want.

No root, no unlocked bootloader, no CPU emulation. A virtual app executes its own DEX and
its own ARM64 `.so` files in a real Android process, on the real ART runtime, drawing to
real surfaces on the real GPU. UNIQUE is not a sandbox around the app; it is the app's
*world* — the package manager it asks, the paths it writes to, the identity it reports.

**Target:** Android 12–16 (API 31–36), ARM64-v8a.

---

## The goal

One sentence: **two copies of an app should be able to run at once, on an unrooted phone,
and neither should be able to tell.**

Everything else follows from that. "Neither should be able to tell" is what rules out the
easy versions — a data-directory swap that an app defeats by reading `ANDROID_ID`, or a
re-signed APK that breaks every certificate check. It is also why the compatibility matrix
is written the way it is: a claim that an app "works" is worth nothing unless someone
watched it work.

The second goal, which the first depends on: **be honest about what does not work.** A
virtualization engine has an unusually large number of ways to be subtly wrong, and almost
all of them look like the app's fault. `docs/COMPATIBILITY.md` uses five words —
`SUPPORTED`, `PARTIAL`, `BROKEN`, `UNSUPPORTED_FOR_NOW`, `NOT_TESTED` — and `NOT_TESTED` is
never written as `SUPPORTED` because something ought to work.

---

## Where the project actually is

| | |
|---|---|
| **Off-device tests** | 162 JVM tests, 34 native checks, 15 Dart tests, 41 tool tests — all passing |
| **On-device suite** | **38 of 38** instrumented tests pass, on an Android 14 x86_64 emulator, against the exact APK in `dist/` |
| **On a real phone** | Four runs. The fourth launched **8 of 8** apps into their own Activity with no slot failures and no permission denials — and one guest still crashed, on a service that was proxied too late. Fifteen causes found and fixed across the four; the last six are not yet back on hardware |

**A virtual app has never yet run to a usable screen on physical hardware.** That is the
single most important fact about this project's status, and every other claim here is
subordinate to it. The emulator work is real and it caught real bugs, but three of the
things that matter most — ARM64 guest code, a real GPU, and an app that was not written to
be tested — only a phone can answer.

### What is device-proven (on the emulator)

An APK that the device has **not** installed is imported, given an instance, and launched
into a `:vappN` process where it believes it is itself. Inside that process:

- Its own `Application` subclass runs, before its first `Activity`, from its own class loader.
- Its Activities, Services (started, bound and foreground), manifest BroadcastReceivers and
  ContentProviders all run — as the guest, in the guest's storage, in the guest's process.
- It loads and calls its own native libraries, including ones loaded late, and libc path
  redirection puts hard-coded `/data/data/<pkg>/...` paths inside the instance.
- It renders with OpenGL and brings up Vulkan; it creates a WebView with the instance's own
  data directory.
- It sees its own signature, its own `ANDROID_ID`, its own permission grants — and a second
  instance sees a different set of all three.
- Jobs, alarms, notifications and `PendingIntent`s route back to it and not to UNIQUE.
- A dead guest is woken by a broadcast, and the delivery is retried until its receiver
  acknowledges.
- A crash in one instance kills neither UNIQUE nor a sibling.

`docs/STATUS.md` has the evidence line for each of these; `docs/COMPATIBILITY.md` has the
per-capability matrix.

### What does not work, and why

| Problem | Where it stands |
|---|---|
| **Guests crash on a real Android 15 device** | Fifteen faults across four phone logs. The fourth run confirms the big ones are gone — 8 of 8 launches reached the guest's Activity, no slot failures, no denied install-time permissions — and found the reason two proxied services were still refused: the guest's `Application.onCreate` ran *in the middle* of the graft, before the hooks it needed, and `NotificationManager` caches its interface in a static field. `onCreate` now runs last, which is the platform's own order. `docs/STATUS.md` has each fault with its log line. **The last six fixes are not yet back on hardware.** |
| **No Google flow is implemented** | `core/google` decides and records how each flow *would* be routed and reports `Unsupported` for every one. Sign-In, Credential Manager, Firebase and FCM have interfaces and no bodies. |
| **Play Integrity, Play Games, Play Billing** | Expected not to work. UNIQUE is not an attestation bypass and will not pretend to be one. |
| **A broadcast arriving while UNIQUE itself is not running is missed** | The registrations live in UNIQUE's main process. Closing this needs static registrations in the host manifest, which needs the actions known at build time. |
| **`PendingIntent` broadcast to a guest receiver** | Reported as unsupported rather than silently mis-pointed. Needs a host stub receiver that re-dispatches. |
| **A temporary URI grant handed *into* a guest** | The case a photo picker uses. Untested: arranging a real grant needs a third APK, because instrumentation runs under the target app's own uid. |
| **The minified release build is unverified** | It assembles, signs, and its keep rules hold structurally — but the instrumented suite cannot be pointed at it, so `dist/` ships an unminified build as the one to test. |
| **No AOT** | Since Android 10 an app cannot invoke `dex2oat`, so virtual apps are JIT-only and start slower than installed ones. A platform property, not a defect. |
| **32-bit-only apps** | Not supported on a 64-bit-only device. No emulation is planned. |

---

## How it works, in one page

A `:vappN` process starts as UNIQUE's own. Before the guest's first component runs, UNIQUE
**grafts** the guest into it:

1. A `LoadedApk` is built for the guest's `ApplicationInfo` and published where
   `ActivityThread` looks for it, so the guest's `Context` reports the guest's package,
   files directory and resources.
2. System-service interfaces are proxied — `IActivityManager`, `IPackageManager`,
   `IActivityTaskManager`, AppOps, notifications, jobs, alarms, clipboard — so that calls
   going out carry UNIQUE's identity, which is the only one the platform will accept, while
   calls coming in are rewritten back to the guest's components.
3. The guest's real components are launched through a pool of **stubs** declared in
   UNIQUE's own manifest, because the platform will only start components it has installed.
   A launch transaction is intercepted on `ActivityThread.mH` and rewritten from the stub to
   the guest's real class.
4. Native `libc` path calls are redirected with PLT/GOT hooking, so a library that
   hard-codes `/data/data/<pkg>/files` writes inside the instance.

The interception is written to survive Android's renaming habits: shims bind
*structurally* (a method that carries an `Intent` and is about a service) rather than by
name, and every shim reports what it actually bound to at install time. That rule exists
because a shim that binds to nothing looks exactly like one that works.

`docs/ARCHITECTURE.md` is the long version, written as a decision record: what was tried,
what broke, and why the current shape is the one that survived.

---

## Documentation

| Document | What it covers |
|---|---|
| [`docs/STATUS.md`](docs/STATUS.md) | What is implemented and what is not, with the evidence line for each; the physical-device findings; what is next |
| [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) | The per-capability and per-application matrix, in five words used strictly |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | The decision record: every mechanism, and the failure that produced it |
| [`docs/PHYSICAL_DEVICE_TEST.md`](docs/PHYSICAL_DEVICE_TEST.md) | The twelve-step sequence for testing on a phone, with no `adb`, no root and no computer |
| [`docs/GOOGLE_DEVICE_TEST.md`](docs/GOOGLE_DEVICE_TEST.md) | The procedure for a device with a Google stack, in the order that makes one failure explain the next |
| [`dist/README.md`](dist/README.md) | The downloadable APKs, which to install and why |
| [`tools/device-log/README.md`](tools/device-log/README.md) | Reading a run from a phone: eleven checks over a device log, no SDK and no device |
| [`tools/apk-survey/README.md`](tools/apk-survey/README.md) | Which platform APIs real apps actually call, and which of them UNIQUE still does not proxy |

---

## Layout

```
app/            host application: manifest template, the generated stub pool, the Flutter host
core/common     pure JVM — APK and binary-XML parsing, ELF checks, the path contract,
                device-profile model, the signature-agnostic shim engine, the redactor
core/hook       hidden-API access, system-service interception
core/diagnostics structured per-process event buffers and crash records
core/vpm        package import, split selection, the state database
core/vam        the graft, stub routing, every system-service hook, provider routing
core/vstorage   the virtual filesystem and its redirection table
core/vprofile   per-instance device identity — the single source for it
core/vpermission per-instance runtime permissions
core/google     the three-mode Google router and its (unimplemented) bridges
core/native     C++ redirect table, PLT/GOT hooking, crash handler, Vulkan probe
ui/             Flutter interface (add-to-app module), English and Russian
tools/          the probe application, fixture generation, host-side native tests,
                the on-device verification harness, the device-log analyzer
dist/           the ARM64 APKs a tester installs
```

`core/common` never depends on `android.*`. That is what keeps the correctness-critical
models — manifests, paths, profiles, the redactor — testable without a device.

---

## Building

Requires JDK 17+, the Android SDK with **build-tools 36**, **platform 36**, **NDK 27**, and
the **Flutter SDK** (the UI is an add-to-app module).

```bash
# One-time: the Flutter module records its SDK path, which settings.gradle.kts reads.
(cd ui && flutter pub get)

# The build a tester installs: unminified engine, Flutter built ahead of time.
UNIQUE_ABIS=arm64-v8a ./gradlew :app:assembleVerify -Ptree-shake-icons=true
./tools/check-abi.sh app/build/outputs/apk/verify/app-verify.apk
```

Three build types, and the difference matters:

- `debug` — what is developed against.
- `verify` — what `dist/` ships and what the acceptance suite runs against. Same engine as
  `debug`, no R8, Flutter AOT, signed, a quarter the size.
- `release` — minified. **Not device-verified**; see `dist/README.md`.

---

## Testing

```bash
./gradlew test                    # 162 JVM tests
./tools/native-test/run.sh        # 34 host-side native checks, no device needed
(cd ui && flutter test)           # 15 Dart tests
./tools/device-log/self_test.py   # 26 tests for the device-log analyzer, no toolchain
./tools/apk-survey/self_test.py   # 15 tests for the APK survey, no toolchain
./tools/report-unimplemented.sh   # every deliberately unimplemented surface

# The on-device suite: builds, installs, runs 38 instrumented tests, saves everything.
export ANDROID_HOME=/path/to/android-sdk
BUILD_TYPE=verify ./tools/verify-device.sh
```

`./gradlew test` needs the Android SDK but **not** Flutter: without `ui/.android/local.properties`
the build configures `core/` alone and says so, rather than refusing. `:app` and the
instrumented suite still need the Flutter SDK, and a build that has left them out announces
it instead of reporting a green that covers nothing UNIQUE ships.

### Deciding what to fix before a phone does

The third device run found nothing that needed a phone. Every fault it produced was plain
Java logic that would have reproduced anywhere; what the emulator suite lacked was not
hardware but a *real application* — it runs against a probe written to be probed, and the
probe calls none of the APIs that killed ChatGPT.

So the platform surface real apps use is read directly out of their DEX:

```bash
tools/apk-survey/survey.py /path/to/apks/*.apk
```

It reports, per system service, how many real apps call it and whether UNIQUE proxies it —
distinguishing "proxied", "named in `TARGETS` but never installed", and "not there at all".
Across 63 apps from F-Droid it finds nine services UNIQUE does not proxy, three of them
declared and dead, including `window`, which 60 of the 63 use. See
[`tools/apk-survey/README.md`](tools/apk-survey/README.md).

### Reading a run from a phone

The four things that matter most — ARM64 guest code, a real GPU, an OEM framework fork, an
app that was not written to be tested — only a phone can answer, and what comes back from
one is a log of tens of thousands of lines in which about thirty matter. Those thirty are
found mechanically:

```bash
tools/device-log/analyze.py recorded.log --device device.txt
```

Ten checks, exit status 0 or 1, no SDK and no device: did every launch reach the guest's
own Activity, was every process slot handed over clean, did any call go out under the
guest's name and get refused — and *which service to hook* when one did. It reads UNIQUE's
own diagnostics export, a recorder app's log, or `adb logcat`, and it is regression-tested
against a real Android 15 run in which three apps in a row failed to launch. See
[`tools/device-log/README.md`](tools/device-log/README.md).

The parser and ELF tests run against fixtures produced by real `aapt2` and real NDK clang,
regenerated with `tools/gen-fixtures.sh` — checked-in bytes nobody can reproduce are not
evidence. The probe application the device suite drives is built by the raw Android
toolchain, deliberately not as a Gradle module: it must be an ordinary third-party app that
UNIQUE imports, not part of UNIQUE's own build.

---

## Engineering rules

Enforced by review, and where possible by tests:

1. **Nothing unfinished is described as done.** Unimplemented surface marks itself and
   reports at runtime; `tools/report-unimplemented.sh` lists all of it.
2. **`NOT_TESTED` is never written as `SUPPORTED`.** A capability moves in the matrix when
   something was observed, and the observation is named.
3. **After a serious change: build it, test it, read the logs.** Most of this engine's bugs
   are invisible to unit tests and visible in one line of `logcat`.
4. A working module is not rewritten without a reason.
5. Compatibility quirks live only in `core/compat` — never as a growing `if (packageName)`.
6. Identity values come only from `core/vprofile`.
7. Google-specific calls go only through `core/google`.
8. Every shim declares its API range and verifies its binding at install time.
9. **Every crash leaves a diagnostic trace**, readable on the device without `adb`.

---

## Privacy and scope

The diagnostics package a user can export contains UNIQUE's own structured logs, the system
log filtered to framework tags, and a description of the device. It contains **nothing from
inside a virtualized app** — no databases, no shared preferences, no cookies, no tokens —
and that is a structural property: the exporter never opens those directories. Every line
still passes a redactor on the way out, on both the export path and the logcat path, and
`t38` plants a marker inside a guest's storage and searches every byte of the package for it.

UNIQUE isolates virtual apps from other installed apps and from each other. It does **not**
isolate a virtual app from UNIQUE: an app running native code inside a UNIQUE process can
reach whatever that process can reach.

It is not a privilege escalation and not an attestation bypass. Apps that require Play
Integrity or a Play-attested identity cannot work here, and UNIQUE says so rather than
working around it.
