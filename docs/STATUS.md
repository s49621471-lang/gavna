# UNIQUE — status

Regenerate the "not implemented" section with `tools/report-unimplemented.sh`. This file
exists because ARCHITECTURE.md §18 rule 1 forbids describing unfinished work as done.

**Phases 0-5 complete. Phases 6, 7 and 8 begun: signatures, per-instance device identity, splits and updates, cross-process components, and diagnostics export.**

A real APK — not installed on the device — is imported, registered, given an instance, and
launched into a `:vappN` process where it believes it is itself. Its Activity, Service,
manifest BroadcastReceiver and ContentProvider all run as the guest, in the guest's
storage, in the guest's process. Evidence is checked in under `docs/evidence/`.

## What each environment can prove

| Environment | Proves | Cannot prove |
|---|---|---|
| Build machine (JVM + host C++) | Parsers, path contract, ELF checks, shim engine, Google routing table, redactor | Anything about a running Android system |
| **Android 14 x86_64 emulator** (`aosp_atd`, software rendering, no KVM) | The engine graft — it is pure Java and architecture-independent | ARM64 native code, real GPU paths, OEM framework forks, Android 15/16 behaviour, 16 KB pages |
| ARM64 Android 15 phone | Everything above, for real | **Not yet run.** See `docs/PHYSICAL_DEVICE_TEST.md` |

Every device claim below names the environment. Nothing is marked working on reasoning.

## Off-device: done and tested

| Area | Tests | Notes |
|---|---|---|
| Binary XML / manifest decoding | 16 | Against real `aapt2` (build-tools 36) output |
| APK bundle + split selection | 8 | Found a real de-duplication bug |
| ELF: ARM64 + 16 KB alignment | 7 | Against real NDK r27 `.so`, 4 KB and 16 KB |
| Virtual path contract | 12 | Every accessor and every alias pinned |
| Native redirect table (C++) | 34 checks | Host-side binary, no device needed |
| Signature-agnostic shim engine | 12 | Includes one shim bound to two different signatures, and conditional `proceed()` |
| Device profile model | 9 | Shape, stability, regeneration, RFC 4122 |
| Compatibility resolver | 5 | Local-override merging |
| Diagnostics redactor | 7 | JWTs, `ya29.`, bearer headers, emails, key names |
| Google routing table | 10 | Every flow's decision pinned |
| Stub / job / channel namespacing | 8 | Two instances cannot collide |
| Flutter UI | 8 | `flutter analyze` clean |

**92 JVM tests, 8 Dart tests, 34 native checks — all passing.**

## On device (EMU34): verified working

| Step | Evidence |
|---|---|
| APK import from a file, package **not installed** on the host | `PACKAGE_IMPORTED package=com.unique.probe versionCode=7 bytes=12709` |
| Imported APKs are read-only (W^X) | Asserted by the suite: `baseApk.canWrite()` is false |
| Virtual package registered in the state database | `t01` passes |
| Instance created with directories and a device profile | `INSTANCE_PREPARED created=13`, `INSTANCE_CREATED androidId=…` |
| `:vappN` process starts and is identified | `PROCESS_START process=com.unique:vapp0 kind=VAPP` |
| Hidden-API access obtained | `HIDDEN_API_GRANTED via=HiddenApiBypass` |
| Launch interceptor installs on `ActivityThread.mH` | `INTERCEPTOR_INSTALLED executeTransaction=159` |
| Launch transaction reaches the interceptor | `CALLBACK_ALIVE what=159 obj=…ClientTransaction` |
| `LaunchActivityItem` located and its shape understood | No `LAUNCH_ITEM_SHAPE_UNKNOWN`; sibling transactions correctly ignored |
| Virtual `PackageManager` installed | `SERVICE_HOOKED service=package cache=true singletons=1` — 7 methods bound |
| Native library loads in the virtual process | `libunique_native loaded (page size 4096)` |
| `LoadedApk` graft completes | `BOOTSTRAP_OK … applicationClass=com.unique.probe.ProbeApplication` |
| Launch transaction rewritten to the guest's own component | `TRANSACTION_REWRITTEN activity=com.unique.probe.ProbeActivity` |
| **The guest's own `Application` subclass runs** | `UniqueProbe: Application.onCreate package=com.unique.probe class=com.unique.probe.ProbeApplication filesDir=…/virtual/users/0/…` |
| The platform launches the guest's real Activity | `Unable to start activity ComponentInfo{com.unique.probe/com.unique.probe.ProbeActivity}` — the component is the guest's, not a stub |
| Outbound identity accepted by system_server | `VAM_HOOK_INSTALLED package=com.unique.probe host=com.unique` |
| A crashing virtual process leaves a diagnostic | `CRASH UNCAUGHT_EXCEPTION` recorded by `CrashGuard` |

## On device (EMU34): the acceptance suite

Run `20260905-125829-4200`, the `verify` build a tester is handed, Android 14 x86_64,
probe **not installed on the device**. **38 of 38 pass.**
Full output in `docs/evidence/phase3-4-instrumentation.txt`.

| Test | Result |
|---|---|
| `t01` import, register, create an instance | **PASS** |
| `t02` launch, and the app sees its own identity | **PASS** |
| `t03` the app writes nothing into UNIQUE's own directories | **PASS** |
| `t04` data survives a full process kill and relaunch | **PASS** |
| `t05` a second instance is fully independent | **PASS** |
| `t06` a crashing instance kills neither UNIQUE nor its sibling | **PASS** |
| `t07` the guest's own Service runs, started *and* bound | **PASS** |
| `t08` the guest's manifest BroadcastReceiver gets broadcasts | **PASS** |
| `t09` the guest's ContentProvider answers its own authority | **PASS** |
| `t10` the guest starts its own second Activity | **PASS** |
| `t11` a `PendingIntent` the guest built fires into the guest | **PASS** |
| `t12` a runtime permission belongs to the instance, not to UNIQUE | **PASS** |
| `t13` a grant survives the virtual process being killed | **PASS** |
| `t14` app ops accept the guest's identity | **PASS** |
| `t15` the guest's notification is posted, and two instances do not collide | **PASS** |
| `t16` the guest's foreground service starts | **PASS** |
| `t17` the guest loads and runs its own native library | **PASS** |
| `t18` the guest's job is scheduled, and the system runs it | **PASS** |
| `t19` the guest sets alarms and uses the clipboard | **PASS** |
| `t20` the guest renders with OpenGL, and the pixel reads back | **PASS** |
| `t21` the guest reads its own signature, and the truth about the Google stack | **PASS** |
| `t22` two instances have different device identities | **PASS** |
| `t23` an update keeps the instance's data | **PASS** |
| `t24` an update signed by someone else is refused | **PASS** |
| `t25` a *dead* guest is woken by a broadcast, and its receiver runs | **PASS** |
| `t26` UNIQUE itself reads a guest's provider, across the process boundary | **PASS** |
| `t27` a guest reaches its own provider in another of its processes | **PASS** |
| `t28` the guest creates a Vulkan instance, device and graphics queue | **PASS** |
| `t29` cold start, warm start and memory are measured and recorded | **PASS** |
| `t30` the guest runs a WebView, in its own data directory | **PASS** |
| `t31` the guest starts its own activity by an implicit intent | **PASS** |
| `t32` killing a guest's provider process leaves UNIQUE alive and still able to read another | **PASS** |
| `t33` a native crash leaves a diagnostic record UNIQUE wrote | **PASS** |
| `t34` a guest shares one of its own files with something outside it | **PASS** |
| `t35` the Google routing decision is real, and follows this device | **PASS** |
| `t36` a guest reaching another app's provider gets a well-formed answer | **PASS** |
| `t37` the device report, the checklist and an export that carries no app data | **PASS** |
| `t38` a guest reads a setting, and is called by its own name | **PASS** |

### What it costs, on this emulator

Recorded by `t29` on every run. **Software-emulated x86_64 with no hardware acceleration**,
so these are not device numbers and no budget is asserted against them (§17.1) — they are
a baseline a physical-device run is compared against.

| Measurement | Run `20260904-221502-8465` | Run `20260905-093631-18438` |
|---|---|---|
| Cold start, fork → the app's first screen ready | 37.5 s | 12.1 s |
| ... of which fork → guest `Application.onCreate` (the graft) | 27.6 s | 8.2 s |
| ... of which `Application.onCreate` → `Activity.onCreate` | 7.5 s | 3.3 s |
| Warm start, request → ready, into a live process | 5.4 s | 2.3 s |
| Virtual process memory, total PSS | 30.2 MB | 33.1 MB |

The two columns are the same engine on the same emulator, three times apart in every
timing, and the difference is not UNIQUE: the first was measured with Gradle's and Kotlin's
daemons resident, holding about five gigabytes between them on a machine sized for one
emulator. That is what a wall-clock number on this environment is worth, and why none is
asserted against (§17.1). `tools/verify-device.sh` now stops the daemons before it
instruments, because at load 10 the platform was killing processes before they could
attach — `Killing …:com.unique:vapp2 (adj -10000): start timeout`, and
`com.android.bluetooth` in the same second, which is how the machine was finally
distinguished from the engine.

The graft dominates cold start, which is what a JIT-only process loading UNIQUE's
interception layer before any guest code looks like. PSS rather than RSS: a virtual process
shares the whole framework and UNIQUE's own code, and RSS would count those pages in full
in every process at once.

What the guest's non-Activity components reported
(`docs/evidence/phase4-native-engine.log`):

```
Service.onCreate       package=com.unique.probe process=13624
Service.onStartCommand startId=1 count=1
Service.onBind         count=1 component=com.unique.probe/.ProbeService
onServiceConnected     com.unique/.stub.ServiceStub_p0_s0
Receiver.onReceive     action=com.unique.probe.PING package=com.unique.probe
Provider.onCreate      package=com.unique.probe
ACTIVITY_INTENT_ROUTED activity=…ProbeSecondActivity stub=…ActivityStub_p0_m0_a0
TRANSACTION_REWRITTEN  activity=com.unique.probe.ProbeSecondActivity
PERMISSION_CHECK       permission=android.permission.CAMERA result=DENIED
PERMISSION_RESULT_RECORDED permission=…CAMERA granted=true blockedByHost=false
cameraAfter            = GRANTED   (the host held it all along; the instance did not)
rowCount               = 3
provider.packageName   = com.unique.probe
provider.filesDir      = …/virtual/users/0/data/com.unique.probe/files
provider.pid           = 13624   (the same process as the Activity)

PACKAGE_IMPORTED       package=com.unique.probe versionCode=22 libs=1 abi=x86_64
loaded                 = true
arch                   = x86_64      (the device's own instruction set; nothing emulated)
nativePid              = 17583       (= the Activity's pid)
echo                   = native:hello
nativeLibraryDir       = …/virtual/apk/com.unique.probe/22/lib/x86_64
libcWrite              = ok:…/virtual/users/0/data/com.unique.probe/files/probe-libc.txt

io_redirect installed  1 slot(s) in 1/318 libraries, 8 rule(s), page size 4096
libcRawWrite           = ok:/data/data/com.unique.probe/files/probe-libc-raw.txt
libcRawLandedInInstance= true    (a path hard-coded in native code, redirected by libc)
```

What the guest itself reported (`docs/evidence/phase2-first-launch-engine.log`):

```
packageName            = com.unique.probe
applicationClass       = com.unique.probe.ProbeApplication
applicationOnCreateRan = true
applicationBeforeActivity = true
activityClass          = com.unique.probe.ProbeActivity
componentName          = com.unique.probe/com.unique.probe.ProbeActivity
dataDir                = …/virtual/users/0/data/com.unique.probe
filesDir               = …/virtual/users/0/data/com.unique.probe/files
codeCacheDir           = …/virtual/users/0/data/com.unique.probe/code_cache
packageCodePath        = …/virtual/apk/com.unique.probe/7/base.apk
appInfoNativeLibraryDir= …/virtual/apk/com.unique.probe/7/lib/arm64-v8a
targetSdk              = 34
uid                    = 10109   (UNIQUE's own — see below)
```

Three things are worth reading carefully:

- **`applicationBeforeActivity = true`.** The guest's `Application.onCreate` ran before its
  `Activity`, which is the ordering apps depend on and the reason the graft happens at the
  launch transaction rather than at process start.
- **`packageCodePath` is the shared read-only APK** while every writable path is under
  `users/0/`. That split is what makes a second instance cheap.
- **`uid` is UNIQUE's.** This is correct and permanent: UNIQUE is not a privilege
  boundary. The guest's *package identity* is virtual; its *Linux identity* is the host's.


## The first physical-device run

**Xiaomi Redmi 23030RAC7Y, Android 15 (API 35), HyperOS, arm64-v8a, 4 KB pages.** Run by
the phone's owner on 2026-09-05 and captured with a logcat app — no `adb`, no root, no
computer, which is what the artifact was built for.

**No app launched.** Two faults, both invisible to the verification emulator, both now
fixed and neither re-proven on hardware.

**A settings read from inside a guest was refused.**

```
SecurityException: Package com.gordey.standarling does not belong to 10300
    at android.provider.Settings$NameValueCache.getStringForUser
    at android.database.sqlite.SQLiteCompatibilityWalFlags.initIfNeeded
    at android.database.sqlite.SQLiteDatabase.<init>
```

A settings read is a content-provider `call` carrying an `AttributionSource`, and the
provider checks it against the uid. UNIQUE wraps the provider precisely to substitute the
host's — but nothing acquires the settings provider *after* the graft. The framework reads
one during `handleBindApplication`, before a line of guest code exists (`GraphicsEnvironment:
Global.Settings values are invalid` is that read, in the log, at the top of the process's
life), and the raw binder it gets is cached in `ActivityThread.mProviderMap` and in the
static `NameValueCache` inside each of `Settings.Secure`, `Global` and `System`. The
wrapper never got in front of them. Since `SQLiteDatabase` reads a setting before it opens
anything, the guest could not open a database, could not attach an Activity, and never
started. Fixed by evicting both caches at the end of the graft, before the guest's
`Application.onCreate`.

**`getHistoricalProcessExitReasons` went out under the guest's own name.**

```
SecurityException: Permission Denial: getHistoricalProcessExitReasons
    from pid=22773, uid=10300 requires android.permission.DUMP
```

It needs `DUMP` only for a package that is not the caller's own, so a guest asking why it
died last time was asking about a stranger — and Crashlytics-style startup code took the
application down with it. The identity rewrite's allowlist did not name it. It now has a
structural rule for the interrogative half of `IActivityManager` (`get`, `is`, `check`,
`has`, `query`, `report`), with an explicit exclusion for verbs that *act* on a package,
because rewriting `forceStopPackage` would turn "stop me" into "stop UNIQUE".

**A guest could not read its own name either**, for the same reason one level deeper:
`ApplicationInfo.labelRes` was never set, because the manifest reader kept `android:label`
as text and not as a resource id, so `getApplicationLabel` fell back to the package name.
An app's own about screen, notification title or share sheet would have shown
`com.example.app`. Both halves are carried now.

**Two things that were not launch failures but were plainly wrong.** Every imported app
was listed as `@7f010000` — `android:label` is a reference into the APK's resource table
and UNIQUE's binary-XML reader has none — and none of them had an icon, because
`getApplicationIcon(packageName)` answers only for packages the device has installed.
Both are now read from the stored APK through the platform's own parser, which also gets
the label in the phone's language for free.

**And the log was nearly empty of UNIQUE's own events.** The build a tester installs is not
debuggable, `Diagnostics.verbose` was `BuildConfig.DEBUG`, and so a logcat capture from the
phone held only warnings and errors: no `PROCESS_START`, no hook report, none of the trace
before the failure. That is now a build-type flag of its own, on for the `verify` build.

`t38` covers the settings read and the label, and the probe was given a real resource table
and a localized name so both are exercised rather than asserted.

### The second run: further in, still not launching

Same phone, same day, with the fixes above installed and full logging on. The graft now
**completes**: `BOOTSTRAP_OK`, fifteen of ChatGPT's providers published, the launch
transaction rewritten to `com.openai.chatgpt.MainActivity`. Then the guest crashes, and it
crashes reading a setting again:

```
SecurityException: Package com.openai.chatgpt does not belong to 10300
  at android.content.ContentProviderProxy.call        ← the raw binder, not UNIQUE's wrapper
  at android.provider.Settings$NameValueCache.getStringForUser
  at android.database.sqlite.SQLiteCompatibilityWalFlags.initIfNeeded
```

Emptying the caches was not enough, and the log says why by what is *missing* from the
stack: no wrapper frame. `ActivityThread.installProvider` does this with whatever
`getContentProvider` hands back:

```java
IBinder jBinder = provider.asBinder();
ProviderRefCount prc = mProviderRefCountMap.get(jBinder);
if (prc != null) {
    provider = prc.holder.provider;   // the wrapper is dropped here
}
```

UNIQUE's wrapper answers `asBinder()` with the *raw* binder — it has to, that is what makes
it a usable `IContentProvider` — so a provider this process acquired before the graft is
found in that second map by its own binder and the wrapper is discarded in favour of the
record already there. `mProviderRefCountMap` is now evicted alongside `mProviderMap`, and
the wrapper is additionally installed into each `Settings` holder **by hand** rather than
arranged for, so nothing depends on which path a later acquisition takes.

The log also settled a question that had been guesswork: `hostSource=com.unique
hostSourceUid=10108`. The identity being substituted was right all along; it was simply
never reaching the call.

Two more faults from the same log:

- **`FATAL EXCEPTION: nfz Dispatcher`** — Conscrypt dereferencing a null
  `NetworkSecurityPolicy` while closing a TLS socket. A `:vappN` inherits the policy the
  platform installed for *UNIQUE* during `handleBindApplication`, because the guest did not
  exist yet. That is wrong even when nothing crashes: cleartext rules and certificate
  pinning are the guest's security decisions, and running its traffic under the host's
  either relaxes them or breaks them. The guest's own config is now installed after its
  Application is created.
- **`ClassNotFoundException: Invalid name: com.openai.chatgpt.`**, six times per launch. A
  `<provider>` with no `android:name` — real APKs contain them, left behind by a manifest
  merge — and qualifying an empty name against the package produces `<pkg>.`. Components
  with a blank name are now dropped at parse time, as the platform drops them.

**Still not confirmed working on hardware.** None of this is re-marked in
`docs/COMPATIBILITY.md`: an emulator that never reproduced these cannot confirm them.

## Previously blocking, now fixed

Each device run moved the failure further down the launch path. None of these were
visible to unit tests:

| Blocker | Status |
|---|---|
| `hostContext` null during `attachBaseContext` → silent return | fixed |
| `makeApplication` failure reason swallowed | fixed (chain now reported) |
| `SecurityException: Writable dex file … is not allowed` (W^X on the APK) | fixed |
| `SecurityException: Given calling package … does not match caller's uid` | fixed (outbound identity rewrite) |
| `SecurityException: Package … does not belong to <uid>` — the `AttributionSource` on provider calls | fixed |
| **Re-importing a package deleted every instance of it.** `@Insert(onConflict = REPLACE)` is DELETE-then-INSERT in SQLite, and `instances` cascades on `packages.packageName` | fixed (`@Upsert`, which never issues a DELETE) |
| An update deleted the old APK while a task still named that version, so an updated app could not be reopened from Recents | fixed (the launch falls back to the version on disk; superseded APKs are reclaimed at launch instead) |
| The permission layer answered once and then stopped: since Android 12 both routes answer from a `PropertyInvalidatedCache` **inside the app's own process**, so later checks never crossed Binder and never reached the shim | fixed (the platform's own per-process cache disables, called at bind) |
| `awaitFile` returned the first non-empty version of a file the probe writes several times per launch, so a test could assert on state that was not finished yet | fixed (`awaitFileWhere`, which waits for a named stage) |
| `t06` passed vacuously: `/proc` is `hidepid=invisible`, so the pid check saw nothing | fixed (`ActivityManager.getRunningAppProcesses`) |
| `t06`: the crash extra never arrived — the task was recreated from its *stored* intent | fixed (`FLAG_ACTIVITY_CLEAR_TASK`) |
| Every bind reached AMS unrewritten: `ContextImpl` calls `bindServiceInstance` from Android 12 on, and the shim was registered for `bindService`, which still exists and so bound cleanly | fixed (structural service matcher; the bind report now names the concrete methods) |
| The same shape again: `activity_task` was declared as a hook target and never installed, so a guest could not open a second screen | fixed (`VirtualActivityTaskManagerHook`) |
| All of a guest's activities share one stub pool, so with `FLAG_ACTIVITY_NEW_TASK` the task system saw the *same component* and answered `START_DELIVERED_TO_TOP` instead of starting the requested screen — and two `PendingIntent`s for two screens compared equal | fixed (`Intent.setIdentifier` on the stub intent; the guest's own identifier is restored on unwrap) |
| `stageProbeApk` declared no inputs, so the suite ran an old probe against a new engine while reporting green | fixed (probe sources are inputs; the APK is rebuilt, not reused) |
| `verify-device.sh` piped `adb install` into `tail`, discarding its exit status — a failed install ran the suite against whatever was installed before | fixed |

**Caveat on rendering.** The suite asserts the activity ran and produced its observations;
it does not look at the screen. Confirming that pixels appear is a two-minute manual step
in `docs/PHYSICAL_DEVICE_TEST.md`.

## Deliberately not implemented

| Surface | Phase | What it does instead |
|---|---|---|
| A broadcast arriving while UNIQUE itself is not running | 3 | `VirtualBroadcastRouter` holds the registrations in UNIQUE's main process, so it must be alive. Static registrations in the host manifest would close it, and need the actions known at build time |
| Implicit system broadcasts to a guest receiver | 3 | Android 14's `IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS` matches implicit intents only against exported filters, and UNIQUE's own registrations are `RECEIVER_NOT_EXPORTED`. A sender inside UNIQUE must scope its intent |
| Hidden-API native fallback | 3 | `HiddenApi.nativeFallbackAvailable` is a constant `false` |
| Native property virtualization | 6/7 | `InstallStatus.NOT_IMPLEMENTED` |
| Every Google bridge body | 6 | Interfaces, routing and host-environment detection exist and are tested; no bridge has an implementation. `docs/GOOGLE_DEVICE_TEST.md` is the procedure that would settle each flow |
| Device profile regenerate | 7 | UI row says it is not available yet |
| WebView rendering on this environment | 6 | A WebView is created correctly in the guest with the instance's own data directory, but Chromium's renderer crashes on this emulator *outside* virtualization too, so rendering is `NOT_TESTED` rather than attributed to UNIQUE (`t30`) |
| URI permission grants between virtual processes | 3 | `grantUriPermission` across `:vappN` is not implemented |
| VirtualCore server interface | 3 | `onBind` returns null; the UI process owns the database for now |

## Known limits that are not bugs

- **Any UNIQUE process started to publish a provider must finish inside the platform's
  publish timeout**, and that now includes UNIQUE's own `:core`, which publishes the
  router and shared authorities. On a badly loaded emulator `system_server` has killed it:
  `Killing …:com.unique (adj 0): timeout publishing content providers`. The window is far
  larger on hardware, but the ceiling belongs to the platform and grows with every
  provider added to a process.
- **A `:vappN` started to publish a provider must finish inside the same timeout.** On the verification emulator a cold virtual process can take tens of seconds
  to reach the graft, and `system_server` has been seen to give up on it:
  `Killing …:com.unique:vapp2 (adj 0): timeout publishing content providers`. On hardware
  the margin is far larger, but the ceiling is real and belongs to the platform.
- **Background ANRs on a loaded machine.** `Killing …:com.unique:vapp0 (adj 0): bg anr`
  appears when the device is thrashing: a cold wake is fork, load UNIQUE's code, install
  the interception layer, graft the guest, *then* run the component, and all of it inside
  what ActivityManager considers a background service start. It is the reason no test
  asserts a wall-clock budget (§17.1).

  What was a bug rather than a limit is what happened next: the stub is started
  `START_NOT_STICKY`, so nothing brought it back and the broadcast was **lost silently**.
  A cold delivery is now tracked in UNIQUE's main process and re-tried until the guest's
  receiver acknowledges it, up to three attempts ninety seconds apart
  (`COLD_BROADCAST_RETRY`, `COLD_BROADCAST_ACKNOWLEDGED`, `COLD_BROADCAST_GIVEN_UP`). The
  ANR itself remains a property of a loaded device; losing the broadcast to it does not.
  OEM background management on a real phone produces the same kill, which is why this is
  worth carrying to hardware.


- **No AOT.** Since Android 10 an app cannot invoke `dex2oat`, so virtual apps are
  JIT-only and cold start is slower than an installed app. This is a platform property.
- **Attestation.** Play Integrity, Play Games and Play Billing are expected not to work
  and are recorded `UNSUPPORTED_FOR_NOW` — expected, not measured.
- **32-bit-only apps** on a 64-bit-only device.
- The Credential Manager route is the Google layer's **central hypothesis**, unverified.

## Next steps, in order

1. **The physical-device run.** ARM64 native code, a real GPU driver, a hardware Vulkan
   ICD, WebView rendering and a real application — five things only a phone can answer,
   and every one of them is `NOT_TESTED` until it does.
   `docs/PHYSICAL_DEVICE_TEST.md` is the sequence, and it needs no `adb`, no root and no
   computer: *Settings → Advanced → Device test* holds the device report and the twelve
   steps, and the last step shares one diagnostics package out through the share sheet.
2. A temporary URI grant handed *into* a guest — the case a photo picker actually uses.
   Sharing outward works (`t34`) and the inbound request is at least well-formed (`t36`),
   but arranging a real grant needs a third APK: instrumentation runs under the target
   app's uid and can neither write another app's files nor grant for its authority.
3. Google, which needs a device with a Google stack. `docs/GOOGLE_DEVICE_TEST.md` is the
   procedure, in the order that makes one failure explain the next.
4. A real engine sample (Unity/Unreal). None is available in this environment, and no
   claim will be made without one.
5. **Verifying the minified release build.** The artifact a tester is actually given is
   already covered: `BUILD_TYPE=verify ./tools/verify-device.sh` runs the whole suite
   against the exact APK in `dist/` — not a near neighbour of it — and passes 37 of 37.
   What is left is R8. The minified build assembles and signs, and its keep rules hold
   structurally: the stub pool, the router and shared providers, `UniqueNative` and the
   native entry points all survive, checked on the artifact. But the suite cannot be
   pointed at it — `androidx.tracing.Trace` reaches `AndroidJUnitRunner.onCreate` from
   R8's *classpath* rather than from program input, so no `-keep` rule applies — and a
   class surviving is not the same as a reflective lookup succeeding.

See `docs/COMPATIBILITY.md` for the per-application matrix and
`docs/PHYSICAL_DEVICE_TEST.md` for the physical-device checklist.
