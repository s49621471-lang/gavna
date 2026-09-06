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
| **Off-device tests** | 204 JVM tests, 34 native checks, 15 Dart tests, 71 tool tests — all passing |
| **On-device suite** | **46 of 46** instrumented tests pass, on an Android 14 x86_64 emulator |
| **On a real phone** | Seven runs. The seventh launched 3 of 3 apps into their own Activity with no refused platform call and no poisoned slot — the first log in which the engine is not the story. What it found instead was that UNIQUE was telling every guest Play services was absent on a phone that has it, and that a game's expansion files need an all-files switch nothing had asked for. Six runs. The sixth launched 6 of 7 apps into their own Activity and none of them died of a launch fault — what broke instead was everything after it: a Unity game skipped its own 3 GB of expansion files 156 times for a permission the platform can never grant UNIQUE, UNIQUE's own PLT hook killed another game's protector, and one app died on a notification call nine milliseconds before the hook that would have prevented it. Fifty-eight causes found and fixed across the six runs and the emulator work between them; the last eight are not yet back on hardware |
| **Real applications** | Seven from F-Droid — Termux, Fossify Gallery, NewPipe, Shattered Pixel Dungeon, AntennaPod, KeePassDX, Aegis — imported and launched on the emulator. All seven reach their own main activity on the hardware renderer; seven faults were fixed to get there, none of which the probe could have found |

**A virtual app has never yet run to a usable screen on physical hardware.** That is the
single most important fact about this project's status, and every other claim here is
subordinate to it. The sixth run got closest: six of seven apps reached their own
Activity and stayed there, and what stopped them being *usable* was their own data —
a game with no assets and a game whose protector UNIQUE had broken. The emulator work is real and it caught real bugs, but three of the
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
| **Guests crash on a real Android 15 device** | Forty-two faults across six phone logs. Each run moved the failure further down: the third launched nothing, the fourth launched everything and every guest was rendering in software, the fifth launched 6 of 8 and then **Play services killed three of them**, and the sixth — the newest — launched 6 of 7 with no launch fault at all and found the *next* layer of them: a game with no expansion files, a game killed by UNIQUE's own PLT hook, and an app that died on a notification call nine milliseconds before the hook that covers it was installed. That one is a single fault with three victims: `GmsClient.getRemoteService` sends the guest's own package name to `com.google.android.gms`, which resolves the calling uid to UNIQUE's and answers `SecurityException: Unknown calling package name` on a `Handler`, where no app can catch it. The Google stack is hidden from a guest now, so an SDK that asks finds none and takes the path it already has for a phone without it. The same log said there had never been a **keyboard** — `EditorInfo.packageName` is checked against the calling uid before an IME is bound, and a mismatch binds nothing, silently — and that a guest's own `FileProvider` never published, because providers were installed before the identity hooks and `attachInfo` runs the app's own code. "The screen does not respond" turned out not to be a touch fault at all: those were the dead windows the three crashes left behind. `docs/STATUS.md` has each fault with its log line. **The last eight fixes are not yet back on hardware**, and several of them — the Google hiding, the keyboard, the expansion-file import, the protector exclusion — cannot be checked on the emulator at all, because it has no Play services, no IME, no `Android/obb` and no code-virtualization protector to break. |
| **No Google flow is implemented** | `core/google` decides and records how each flow *would* be routed and reports `Unsupported` for every one. Sign-In, Credential Manager, Firebase and FCM have interfaces and no bodies. The sixth run settled one of them for good: a browser OAuth redirect **cannot** come back into a guest, because `myapp://callback` is declared by a package the platform has never installed and an intent filter is fixed at build time. `SIGN_IN` and `OAUTH_WEB` say so now instead of reporting `PASSTHROUGH`. |
| **A guest cannot sign in *as itself* with Google** | Play services is visible to a guest again — an app whose client library is 18 or newer is told the truth about the device, and takes its native path instead of a browser. What that path returns is the unsolved half: Play services resolves the calling uid to UNIQUE, so a token comes back for UNIQUE's identity and not the app's, and an OAuth client registered for the app rejects it. Nothing UNIQUE can do from outside answers this — what Play services sees for a uid is decided inside Play services' own process. **In-space Google Play services is the architecture that does**, and it is the next large piece of work; `GoogleMode.VIRTUAL_GMS` and the `FORCE_VIRTUAL_GMS` flag are the shape of it and have no implementation behind them yet. |
| **A game's expansion files are not automatic on every device** | `GuestAssetImport` copies `Android/obb/<pkg>` into the instance, and since Android 11 that directory is guarded: it needs all-files access, which App Details offers and the user grants. Without it the import reports `SOURCE_UNREADABLE` and the `.obb` has to be handed to UNIQUE directly — picked beside the APK, or added to the instance afterwards. |
| **A native protector may have to be excluded by hand** | UNIQUE's path redirection writes a pointer into each guest library's GOT, and a code-virtualization protector treats that as tampering. `libgrave.so` is excluded by name; one UNIQUE has not met yet is named in `runtime/native/<vuid>/<package>.exclude`, and the log's `native` check says which library to write there. |
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
| [`docs/EMULATOR.md`](docs/EMULATOR.md) | The verification emulator: exactly which one, how it is created and launched, and what it cannot answer |
| [`dist/README.md`](dist/README.md) | The downloadable APKs, which to install and why |
| [`tools/device-log/README.md`](tools/device-log/README.md) | Reading a run from a phone: eleven checks over a device log, no SDK and no device |
| [`tools/apk-survey/README.md`](tools/apk-survey/README.md) | Which platform APIs real apps actually call, and which of them UNIQUE still does not proxy |
| [`tools/real-app-smoke.sh`](tools/real-app-smoke.sh) | Runs applications the project did not write, and reads what happened out of `logcat` |

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
./gradlew test                    # 204 JVM tests
./tools/native-test/run.sh        # 34 host-side native checks, no device needed
(cd ui && flutter test)           # 15 Dart tests
./tools/device-log/self_test.py   # 54 tests for the device-log analyzer, no toolchain
./tools/apk-survey/self_test.py   # 17 tests for the APK survey, no toolchain
./tools/check-translations.py     # every engine failure has a sentence in both languages
./tools/report-unimplemented.sh   # every deliberately unimplemented surface

# Real applications, not the probe. Not a gate: an APK downloaded at run time can change
# under a test, so this is a report a person reads.
adb shell mkdir -p /data/local/tmp/unique-real
./tools/real-app-smoke.sh some-app.apk another-app.apk

# The on-device suite: builds, installs, runs 46 instrumented tests, saves everything.
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
guest's name and get refused — and *which service to hook* when one did. It reads a
recorder app's log or `adb logcat` alike, and it is regression-tested
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
9. **Every crash leaves a diagnostic trace**, captured by any log recorder on the phone.

---

## Privacy and scope

Everything UNIQUE records goes to `logcat` under one tag, so a user's own log capture is
the whole diagnostics surface. It contains **nothing from inside a virtualized app** — no
databases, no shared preferences, no cookies, no tokens — and that is a structural
property: no code in UNIQUE opens those directories for any diagnostic purpose. Every line
passes a redactor on the way out, which drops OAuth tokens, cookies, `Authorization`
headers, account names and file contents, and has its own unit tests.

UNIQUE isolates virtual apps from other installed apps and from each other. It does **not**
isolate a virtual app from UNIQUE: an app running native code inside a UNIQUE process can
reach whatever that process can reach.

It is not a privilege escalation and not an attestation bypass. Apps that require Play
Integrity or a Play-attested identity cannot work here, and UNIQUE says so rather than
working around it.
