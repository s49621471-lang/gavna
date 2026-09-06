# UNIQUE — status

Regenerate the "not implemented" section with `tools/report-unimplemented.sh`. This file
exists because ARCHITECTURE.md §18 rule 1 forbids describing unfinished work as done.

**Phases 0-5 complete. Phases 6 and 7 begun: signatures, per-instance device identity, splits and updates, cross-process components, and a compatibility pass driven by five runs on a real phone.**

A real APK — not installed on the device — is imported, registered, given an instance, and
launched into a `:vappN` process where it believes it is itself. Its Activity, Service,
manifest BroadcastReceiver and ContentProvider all run as the guest, in the guest's
storage, in the guest's process. Evidence is checked in under `docs/evidence/`.

## What each environment can prove

| Environment | Proves | Cannot prove |
|---|---|---|
| Build machine (JVM + host C++) | Parsers, path contract, ELF checks, shim engine, Google routing table, redactor | Anything about a running Android system |
| **Android 14 x86_64 emulator** (`aosp_atd`, software rendering, no KVM) | The engine graft — it is pure Java and architecture-independent — and, through `tools/real-app-smoke.sh`, applications the project did not write | ARM64 native code, real GPU paths, OEM framework forks, Android 15/16 behaviour, 16 KB pages, **anything involving Play services or an IME** — the image has neither |
| ARM64 Android 15 phone | Everything above, for real | **Five runs so far**, each a capture read by `tools/device-log/analyze.py`; three are checked in as fixtures. See `docs/PHYSICAL_DEVICE_TEST.md` |

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
| Settings screens a guest opens about itself | 6 | Which half of the intent names the app, and every case that must be left alone |
| Device profile model | 9 | Shape, stability, regeneration, RFC 4122 |
| Compatibility resolver | 5 | Local-override merging |
| Diagnostics redactor | 7 | JWTs, `ya29.`, bearer headers, emails, key names |
| Google routing table | 10 | Every flow's decision pinned |
| Stub / job / channel namespacing | 8 | Two instances cannot collide |
| Flutter UI | 15 | Includes both shapes the engine's Google status arrives in, and that the reason an app cannot be added is a key both languages carry |
| Runtime vs install-time permissions | 7 | Every dangerous group enumerated; the three a device run found denied are install-time |
| Process slot pool | 12 | Release ends the process; a dead slot is reclaimed; a full pool refuses rather than evicts |
| Per-instance permission store | 12 | Undecided install-time is granted, undecided runtime is not, and neither can exceed the host |
| Device-log analyzer | 35 | Against a real Android 15 run, plus a synthetic healthy one |
| APK survey (DEX reader, service map) | 17 | The reader is checked against a real checked-in APK |
| Which packages a guest may see | 6 | The Google stack hidden, `com.android.vending` not, a prefix match not enough, and both shapes intent resolution answers in — the emulator has no Play services, so this is where the decision is pinned |
| Window and task attributes | 9 | `hardwareAccelerated` at both levels including the `targetSdk >= 14` default, orientation, config changes, the task flags, typed meta-data, and a provider's own grant flag — against real `aapt2` output |

**185 JVM tests, 15 Dart tests, 34 native checks, 59 off-device tool tests — all passing.**

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

Android 14 x86_64, probe **not installed on the device**. The suite is **46 tests**, and
the newest run — `RUN_ID=ru-pass-1`, the `debug` build — passes all of them.

It was 47 until this pass: `t37` covered the diagnostics export, the device report and the
checklist, and all three were removed with the UI that reached them.

The last run of the `verify` build a tester is actually handed, `20260905-125829-4200`,
was **38 of 38** against the suite as it stood then; everything after `t38` was added later
and has only been run on `debug`. Full output of that run in
`docs/evidence/phase3-4-instrumentation.txt`.

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
| `t38` a guest reads a setting, and is called by its own name | **PASS** |
| `t39` the guest's window is hardware-accelerated, read after it was attached | **PASS** |
| `t40` the guest's declared `screenOrientation` reaches the platform | **PASS** |
| `t41` the guest reads its own meta-data, with a resource reference resolved | **PASS** |
| `t42` the guest resolves its own components through its own PackageManager | **PASS** |
| `t43` the guest sees its own process name, and not UNIQUE's | **PASS** |
| `t44` the guest's external storage is its own, and can be written to | **PASS** |
| `t45` a launch redelivered to a running activity arrives as the guest's own intent | **PASS** |
| `t46` the guest starts its own service by action, with no class named | **PASS** |
| `t47` the guest turns one of its own components off, and it stops matching intents | **PASS** |

### What it costs, on this emulator

Recorded by `t29` on every run. **Software-emulated x86_64 with no hardware acceleration**,
so these are not device numbers and no budget is asserted against them (§17.1) — they are
a baseline a physical-device run is compared against.

| Measurement | Run `20260904-221502-8465` | Run `20260905-093631-18438` | Run `ru-pass-1` |
|---|---|---|---|
| Cold start, fork → the app's first screen ready | 37.5 s | 12.1 s | 23.6 s |
| ... of which fork → guest `Application.onCreate` (the graft) | 27.6 s | 8.2 s | 21.7 s |
| Warm start, request → ready, into a live process | 5.4 s | 2.3 s | 3.6 s |
| Virtual process memory, total PSS | 30.2 MB | 33.1 MB | 37.9 MB |

The three columns are the same engine on the same emulator, three times apart in every
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

### The third run: what a whole session on the phone looks like

The first two runs each read one crash. This one is a whole session — seven apps imported
and launched on a Redmi Note 12 (Android 15, `sea_ru`, HyperOS), 26,950 lines of log, of
which 545 are UNIQUE's own. It is checked in at
`tools/device-log/fixtures/redmi-android15.log`, and
[`tools/device-log/analyze.py`](../tools/device-log/README.md) is what reads it, because
finding thirty lines in twenty-seven thousand by eye is not a method.

The reported symptom was **"Gemini started, and after that nothing launches at all."**
Both halves have a cause and neither is the app's.

**Nothing launched after Gemini.** `ProcessPool.release()` freed a slot's record and
killed nothing. Removing an instance called it, the `:vapp0` process kept running with
Gemini's graft still installed, and the next three apps were each assigned slot 0:

```
BOOTSTRAP_FAILED package=clear.una        code=SLOT_ALREADY_BOUND
    message=Slot 0 already serves com.google.android.apps.bard (u0)
BOOTSTRAP_FAILED package=com.f0x1d.logfox code=SLOT_ALREADY_BOUND
BOOTSTRAP_FAILED package=bin.mt.plus      code=SLOT_ALREADY_BOUND
```

Slot 0 is the first free slot, so it was picked every time and every launch from then on
failed identically. The pool now ends a slot's process when it releases it, checks
liveness before handing a slot to a new occupant, and reclaims slots whose process has
died — the death observation the doc comment claimed and nothing performed. A `:vappN`
that still finds itself double-booked ends itself rather than refusing forever, which
costs one tap instead of every launch after it.

**ChatGPT crashed on its first screen**, twice, and a third refusal killed its network
thread:

```
SecurityException: Only system may: get application restrictions for other user/app com.openai.chatgpt
    at android.content.RestrictionsManager.getApplicationRestrictions
    at com.openai.chatgpt.MainActivity.onCreate
SecurityException: getApplicationLocales: Neither user 10300 nor current process has READ_APP_SPECIFIC_LOCALES.
SecurityException: Package com.openai.chatgpt does not belong to 10300
    at android.net.ConnectivityManager.getNetworkCapabilities
```

Three spellings of one fault: a call carrying a package name that does not belong to
UNIQUE's uid, to a service that was not proxied. `restrictions`, `locale` and
`connectivity` are now in `SystemServiceHook.TARGETS`, along with the rest of the set an
ordinary app touches while starting — each listed with what a guest loses without it in
`VirtualIdentityHooks.CALLER_PACKAGE_SERVICES`. A guest asking to set its *own* app locale
is refused rather than aimed at UNIQUE's, which is the only other place that call could
land.

**And every guest was running with no network permission.** Not from any of the crashes —
from the permission model:

```
PERMISSION_CHECK permission=android.permission.ACCESS_NETWORK_STATE result=DENIED   (x36)
PERMISSION_CHECK permission=android.permission.INTERNET              result=DENIED   (x10)
PERMISSION_CHECK permission=android.permission.WAKE_LOCK             result=DENIED   (x4)
```

`PermissionStore` treated every undecided permission as denied. That is the right rule for
a runtime permission and meaningless for an install-time one: `INTERNET` has no dialog,
no app requests it, and there was no action any user could have taken to grant it. Install-
time permissions are now granted because the manifest asked for them, as at install, and
only dangerous ones are the user's decision. A permission the guest defines itself is
granted too — the host cannot hold one only the guest declares.

Two more, from the same log:

- **UNIQUE's own App Details screen was dead.** `GoogleStatus.fromMap` threw
  `type 'String' is not a subtype of type 'bool?' in type cast` on the first field it read,
  fifteen times. The engine sends `Map<String, String>` because the same map goes into
  Diagnostics; the Dart was written to tolerate that and `as bool?` *throws* on a `String`
  rather than evaluating to null, so the `??` fallback meant to catch it never ran.
- **`IO_REDIRECT_INSTALLED status=NOT_IMPLEMENTED`** on the Unity app and on Gemini, which
  read as a missing subsystem and was not one. It means zero PLT slots were patched
  because the guest had not loaded its libraries yet — Unity loads them from its own
  initialiser, long after `Application.onCreate` — and `watch=OK` says the dlopen watch
  will catch them. That state is now `NOTHING_TO_HOOK`, and the watch matching nothing is
  `kFailed`, which is what it always was.

**Still not confirmed working on hardware.** Every fix above is reasoned from this log and
none has been back on the phone. `docs/COMPATIBILITY.md` is unchanged for the same reason
it was unchanged after the second run.

### The fourth run: the fixes hold, and the ordering was wrong all along

The third run's fixes, back on the same Redmi. What they were meant to do, they did:

| | Run 3 | Run 4 |
|---|---|---|
| Launches reaching the guest's Activity | 7 of 10 | **8 of 8** |
| `SLOT_ALREADY_BOUND` | 3 | **0** |
| `INTERNET` / `ACCESS_NETWORK_STATE` denied | 46 times | **0** |

`PERMISSIONS_BOUND` now says what it grants: ChatGPT, 31 declared — 24 at install, 7 for
the user. The Play Store, 145 declared — 129 at install, 16 for the user.

And ChatGPT still died, on a call to a service that **was** proxied:

```
SecurityException: Caller not system or systemui or same package: uid 10302 does not have
    android.permission.STATUS_BAR_SERVICE
  at NotificationManager.areNotificationsEnabled
SecurityException: Package com.openai.chatgpt does not belong to 10302
  at ConnectivityManager.getNetworkCapabilities                    (statsig, on a worker)
```

Both services are in `TARGETS` and both hooks install. The hooks just arrived too late.
`makeApplication` ran the guest's `Application.onCreate` at line 349 of `AppBootstrap`;
the identity hooks land at 416 and the notification hook at 434. A guest's `onCreate` is
not a quiet moment — it starts analytics, opens a network stack, asks whether
notifications are enabled — and `NotificationManager.sService` is a **static** field, so
the raw interface it captured there outlived the hook that came sixty lines later.

The same ordering explains a provider failure nobody had connected to it:

```
PROVIDER_PUBLISH_FAILED provider=androidx.startup.InitializationProvider
    error=IllegalStateException: WorkManager is already initialized.
```

`androidx.startup` is built on the platform's guarantee that providers are published
*before* `Application.onCreate`. UNIQUE was publishing them after.

Both are one fix. `makeApplicationInner` uses its `Instrumentation` argument for exactly
one thing — `callApplicationOnCreate` — so it is passed null, and `onCreate` is called
explicitly at the end of the graft. That is `handleBindApplication`'s own order: make the
Application, install the providers, then start it.

Three more faults from the same log, all now closed:

- **`IStorageManager.getVolumeList`** — `callingPackage does not match UID`, reached from
  `Environment.isExternalStorageManager`, killed `clear.una` on its first frame. `mount`
  is proxied.
- **`IAccountManager`** — declared in `TARGETS` since forever and installed by nothing, so
  the Play Store died asking about accounts. Now installed.
- **`search`** — the caller-package shim bound to *nothing*, nine times per run:
  `ISearchManager` on API 35 declares seven methods and not one takes a String. It carries
  no caller identity, so it is removed rather than left looking installed.

**"The screen is laggy"**, reported by the tester, is a launch with both of the things
that normally cover a launch switched off. `Theme.Unique.Stub` set `windowDisablePreview`
to true and `windowAnimationStyle` to `@null`, neither ever explained — so a tap produced
no starting window, then content snapped in with no transition, after however long a
JIT-only cold start takes. Nothing was dropping frames: that run has **one** skipped-frame
event in 37,775 lines. Both overrides are gone; the platform's own behaviour is back.
Unconfirmed on hardware, like everything else here.

### Reading the fourth run's log again: what was still wrong with every launch

The fourth run's log was read once for the two crashes it names above. Read end to end —
39,958 lines, four applications, ten launches — it says four more things, and the first of
them was true of **every application UNIQUE has ever run**.

**Every guest was rendering in software.** `AppBootstrap.buildActivityInfo` builds the
`ActivityInfo` the platform launches a guest with and never set `flags`. That field has
exactly one consumer at attach time:

```java
// Activity.attach
mWindow.setWindowManager(…, mToken, mComponent.flattenToString(),
        (info.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0);
```

so every virtual activity was created with the hardware renderer switched off. `android:
hardwareAccelerated` was not read by the manifest parser at all — neither the activity
attribute nor the `<application>` default of `targetSdk >= 14` that every modern app
inherits. Two symptoms, one cause:

- Everything drew slowly. That is the second half of "the screen is laggy"; the first half
  was the stub theme, and both were real.
- Anything drawing through a `RenderNode` — Compose, any hardware layer, most modern view
  code — died on its first frame:

```
CRASH u1 clear.una UNCAUGHT_EXCEPTION thread=main
  reason=IllegalArgumentException: Software rendering doesn't support drawRenderNode
    at android.view.RecordingCanvas.drawRenderNode
    at …DecorView.draw / ViewRootImpl.drawSoftware
```

`drawSoftware` in that stack is the platform saying so in as many words.

**A Play-protected app killed itself before drawing anything.** `1Tap Cleaner` is signed by
Google's PAIRIP, and PAIRIP checks the licence on startup:

```
E LicenseClient: Not allowed to bind with the licensing service:
    com.android.vending.licensing.ILicensingService
  Caused by: java.lang.SecurityException: Not allowed to bind to service
      Intent { act=com.android.vending.licensing.ILicensingService pkg=com.android.vending }
I System.exit called, status: 0
```

The service is exported and Play was installed; what was missing is that Play guards it
with `com.android.vending.CHECK_LICENSE`, and a permission is checked against the *calling
uid*, which is UNIQUE's. The guest declared it and UNIQUE did not. Every application
Google re-signs — which is every paid app and a large share of free ones — performs this
check, treats a bind failure as tampering, and exits. The log line UNIQUE printed beside it
pointed the other way: `SERVICE_INTENT_IMPLICIT`, thirty times, for an intent that was
scoped to `com.android.vending` and perfectly ordinary.

**Google Play services was told the app had no manifest.** `ApplicationInfo.metaData` was
never populated, so:

```
E FA: Task exception on worker thread: java.lang.IllegalStateException: A required
  meta-data tag in your app's AndroidManifest.xml does not exist. You must have the
  following declaration within the <application> element:
  <meta-data android:name="com.google.android.gms.version" …/>
```

on both the Unity game and ChatGPT. The apps declare it. `android:value` there is a
*reference* into the app's resource table, which is why carrying the manifest's text was
not enough: the type and the compiled datum have to travel together and be resolved
against the guest's own resources, which only a virtual process has.

**A landscape game opened portrait.** `screenOrientation` was parsed and put on the
substituted `ActivityInfo`, where nothing reads it: the platform takes a window's
orientation from the `ActivityRecord` it built in `system_server` from the *stub's*
manifest entry, and the stub says `unspecified`. `vri size:1080x2400` on a Unity game that
declares landscape is the whole of "игры запускаются вертикально".

Two smaller things from the same log:

- **`service download was not proxied: service not available`, ten times.**
  `Context.DOWNLOAD_SERVICE` is the string `"download"` and there is no binder service of
  that name — `DownloadManager` is built from a `ContentResolver`. The APK survey counted
  the *manager*. Removed, which is rule 8.
- **A 1.6 GB APK stalled the launch for 2.5 seconds on the main thread**, inside
  `getPackageArchiveInfo`, which digests every byte of the APK to verify its signing block.
  The OEM's hang watchdog saw it before the user could:
  `MIUIScout App: Enter APP_SCOUT_WARNING State (duration=2501ms … w=159)`. Nothing in the
  graft needs the signature, so it is loaded on a thread of its own and waited for only by
  a caller that asked for it.

### What that pass changed, and one bug it found on the way

Everything above is fixed, and six things around them that the same reading turned up:

| Found | Fixed by |
|---|---|
| The `ActivityInfo` carried no `flags`, `softInputMode`, `uiOptions`, resize mode or aspect-ratio clamp — the manifest parser read none of them | `WindowAttributes` on every component entry, composed into `ActivityInfo` with the platform's own constants. `WindowAttributesTest` holds the two-level `hardwareAccelerated` default against real `aapt2` output |
| `ApplicationInfo` claimed none of the screen-support flags, so `CompatibilityInfo` was entitled to put every guest in screen-compatibility mode — a scaled, letterboxed window with the density lied about | The flags `PackageParser` sets for a modern target SDK, plus `FLAG_HARDWARE_ACCELERATED`, `FLAG_LARGE_HEAP`, `FLAG_SUPPORTS_RTL` and legacy-storage |
| `getPackageInfo` ignored its `flags`, so `GET_ACTIVITIES`, `GET_SERVICES`, `GET_PROVIDERS` and `GET_META_DATA` all came back null | `GuestComponents`, which also answers `getServiceInfo`, `getReceiverInfo` and `getProviderInfo` — three siblings of `getActivityInfo` that were never hooked |
| `getLaunchIntentForPackage(getPackageName())` returned null, and so did `resolveActivity` for the guest's own component: the platform has never installed the package being asked about | `GuestIntentResolution`, scoped strictly to intents that name the guest — an `https` VIEW still belongs to the device's browser |
| A guest enumerating installed packages did not find *itself* and did find `com.unique` | Both corrected in `getInstalledPackages`/`getInstalledApplications`; nothing else in the list is touched |
| `getExternalFilesDir()` returned a path scoped storage will not let UNIQUE create, because it is named for the guest's package and UNIQUE's is `com.unique` | The `mount` proxy rewrites `getVolumeList`, which is the one place `Environment` builds every external path from |
| `/proc/self/cmdline` read `com.unique:vapp0`, and `getRunningAppProcesses` showed UNIQUE's processes and sibling instances | `Process.setArgV0`, as `handleBindApplication` does it, and a process-table rewrite that also stops one instance seeing another |
| A permission the *host* lacks could never be granted to an instance: the switch in App Details was disabled, and the row explained a problem with nothing to press | The switch asks Android for the permission on UNIQUE's behalf, and offers the settings page when the platform has stopped asking |

The bug found on the way is the one worth naming, because it is a *launch* failure and it
was reachable on any slow device:

```
PROVIDER_READY_NOT_SEEN slot=0 vuid=0 waitedMillis=45147 rewarms=2
ANR in com.unique:vapp0   Reason: executing service com.unique/.stub.ServiceStub_p0_s6
Killing 5384:com.unique:vapp0/u0a108 (adj 0): bg anr
```

A caller waiting for a slot re-warms it only when the slot is *not* already grafting —
there is a whole announcement, `slotStarting`, whose stated purpose is to prevent exactly
this. It never fired. `AppBootstrap.announce` begins `val host = hostPackage ?: return`,
and `hostPackage` is set part-way through the graft, after the announcement is made. On a
cold process it was always null, so the announcement was a no-op precisely in the case it
exists for, the caller re-warmed a busy process twice, and ActivityManager killed the
process it was waiting for. Before the graft the context still is UNIQUE's, so its own
package name is both available and correct.

### Putting it on the emulator: five more faults the reading could not have found

The pass above was reasoned from a phone log. Running it produced five faults that no
amount of re-reading would have:

| Found on the emulator | Fixed by |
|---|---|
| `windowHardwareAccelerated=false` **with** `activityInfoFlags=512`. The `ActivityInfo` carried the bit, `Activity.attach` is where the platform reads it, and the window still came up without it | The window is told directly, with `Window.setFlags` from `onActivityPreCreated` — before `setContentView` builds the decor, and recorded as a *forced* flag so `generateLayout` cannot clear it. The `ActivityInfo` keeps the bit as well |
| `metaDataNumber` came back as the resource id rather than `240508`: `LoadedApk.getResources()` is null that early, so a `@integer` reference had nothing to resolve against | The `metaData` bundle is rebuilt from the `Application`'s own resources once `makeApplication` has run |
| `getExternalFilesDir()` threw `SecurityException: callingPackage does not match UID` — the `mount` guard shadows the generic caller-package rewrite, because the first shim that binds to a method wins | The guard carries the rewrite itself, and says why at [`VirtualExternalStorage.shims`] |
| With that fixed, external storage still pointed at `/storage/emulated/0`: `StorageVolume.mPath` is a `File`, and writing a `String` into it is an `IllegalArgumentException` that the surrounding `runCatching` turned into a quiet false | The field's declared type is read and the value built to match; the repoint is now reported (`EXTERNAL_VOLUME_REPOINTED`) rather than only its failure |
| A second launch of a running app produced nothing at all. `FLAG_ACTIVITY_NEW_TASK` onto a task already running the component is `START_DELIVERED_TO_TOP`, and the intent that arrives is UNIQUE's *stub* intent — which `ActivityThread` then assigns to `Activity.mIntent`, so `getIntent()` answers with it for the rest of that activity's life | `NewIntentItem` is rewritten in place, keeping the `ReferrerIntent` the platform reads the referrer from (`t45`) |

Two more came from reading the fourth log once more with the emulator's answers in hand:

| Found | Fixed by |
|---|---|
| An implicit service start scoped to the guest — `new Intent(ACTION).setPackage(getPackageName())`, how a great many SDKs reach their own worker — resolved to nothing, because the platform has no filters for a package it never installed | Resolved against the guest's own manifest, and only when exactly one service matches; more than one is refused rather than guessed, as `bindService` refuses it (`t46`) |
| A Settings screen an app opens about itself named the guest in a `package:` URI, so the device's Settings had nothing to open. In the fourth log a cleaner app sent `MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` and it left the guest for `com.android.settings`, which could only fail | The URI is retargeted to UNIQUE, whose uid is the one that would actually hold the access — so the switch the user is sent to is the switch that works |

And one thing that was not UNIQUE at all, but was making every run after `t36` unreadable:
this emulator's Bluetooth stack cannot finish its own handshake under load
(`AdapterState TURNING_ON : BREDR_START_TIMEOUT`), dies, is restarted by
`BluetoothManagerService`, and from then on `com.android.bluetooth` restarts every twenty
seconds. One run cost 45 process starts and four tests timed out waiting for an app that
was simply not being scheduled. `tools/verify-device.sh` now turns Bluetooth off **on an
emulator only** — a physical device's is the owner's to decide.

### Seven real applications, and the seven faults only they could find

Everything above was found with `tools/testapp`, which is deliberately ordinary and
deliberately *cooperative*: it writes down what it observed, so every assertion about it is
a fact the app itself reported. That is what makes those tests precise, and it is also
their limit — the probe was written after the engine, and it only does what the engine
already supports.

Seven apps from F-Droid were then imported and launched with nothing written for them:
**Termux** (0.119.0-beta.3, 115 MB, native, a foreground service), **Fossify Gallery**
(1.13.1, twelve native libraries, a home-screen widget), **NewPipe** (0.29.1),
**Shattered Pixel Dungeon** (3.3.8, a real game), **AntennaPod** (3.12.0), **KeePassDX**
(4.5.2, native crypto and its own keyboard) and **Aegis** (3.4.2). Four of the seven
failed, each in a different way, and none of the seven causes was reachable from the
probe:

| Found | Fixed by |
|---|---|
| **Termux died in `TermuxActivity.onStart`**: `SecurityException: com.unique: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified`. That rule is a compat change and `ActivityManagerService` evaluates it against the **calling uid**, which is UNIQUE's — so an app built against Android 9, using the two-argument `registerReceiver` it has always used, is held to UNIQUE's target SDK | `registerReceiver*` supplies `RECEIVER_EXPORTED` when the guest passed neither flag and the guest's own target SDK predates the rule. Exported rather than not-exported, because that is what the platform itself does for a pre-34 app |
| **Fossify Gallery died in `MainActivity.onCreate`**: `SecurityException: Package org.fossify.gallery does not belong to 10108`, from `AppWidgetManager.getAppWidgetIds`. `appwidget` was not proxied — and proxying it alone changed nothing, because the package it checks is inside a **`ComponentName`**, which the generic caller rewrite does not touch | `appwidget` added to the proxied services, with a guard that rewrites `ComponentName` packages as well as strings. The guest then gets an empty array, which is the truth: its widget provider is not installed, so no home screen can be showing one |
| **Termux died again, deeper**: `IllegalArgumentException: com.termux: Targeting S+ … requires that one of FLAG_IMMUTABLE or FLAG_MUTABLE be specified`. Same shape, different enforcement: this one is checked **inside the app's own process**, by the compat config `ActivityThread` installs at bind time — which is UNIQUE's, because the process was bound as UNIQUE | `GuestCompatChanges` disables, for that process, the changes the guest's own target SDK predates. The change *ids* are read from the platform classes that declare them rather than transcribed; what UNIQUE states is the SDK each is gated at, which `@EnabledAfter` does not keep at runtime |
| **Termux died a third time**: `SecurityException: Starting FGS with type microphone`. `startForeground(id, notification)` — every app written before Android 10 — sends `FOREGROUND_SERVICE_TYPE_MANIFEST`, meaning "the type on this service's manifest entry". The entry `ActivityManagerService` reads is the **stub's**, which declares every type UNIQUE can host, so the two-argument call asked for all of them at once: `requested=0xffffffff … granted=0x400008ff` | The manifest type is resolved against the *guest's* own entry for the service that is starting. A guest that declares none — an app older than the attribute — gets `specialUse`, which is the type that exists for work the taxonomy does not name and the one the host manifest already carries a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` for |
| **KeePassDX died in `FileDatabaseSelectActivity.onCreate`**: `SecurityException: Attempt to change component state`, from `setComponentEnabledSetting` on its own keyboard service. An app turning one of *its own* components on or off is ordinary — an `<activity-alias>` enabled and its sibling disabled is how a launcher icon is changed — and `PackageManagerService` checks the component against the calling uid, so there was nothing for it to store and no way for it to agree | `GuestComponentState`, a properties file beside the instance's permission record. `get*EnabledSetting` answers from it, `ComponentInfo.enabled` carries it, and both intent resolvers filter on it — so a disabled alias really does stop matching |
| **Aegis's graft failed with `message=java.lang.reflect.InvocationTargetException`** and nothing else. The guest's `Application.onCreate` is called reflectively, so anything it throws arrives wrapped, and that one line ended the investigation instead of starting it | The chain is unwrapped to its root and the first frames carried, and the stack goes to `logcat` under the same tag a device capture is filtered on. The next run said `SecurityException: Shortcut package name mismatch` and named the call |
| **Which was the real fault**: `ShortcutInfo` carries its own package name and `ShortcutService` checks each one against the caller, so the caller-package rewrite was not enough. Rewriting it would then fail the next check — the shortcut's activity must be a *main* activity of that package, and UNIQUE's stubs are neither exported nor launcher-filtered | Publishing is refused, with `false` — a value `setDynamicShortcuts` already returns when the caller is rate-limited, so the app sees a documented outcome rather than an exception. Reading is left alone and answers with UNIQUE's own, which is empty and true. Making them work means routing each shortcut's intents onto stubs the way `PendingIntent`s are; that is a feature, not a fix |

After those seven, **all seven run**: each reaches its own main activity, on the hardware
renderer, and stays up. `tools/real-app-smoke.sh` is what does this, and it is deliberately
*not* part of the acceptance suite — an APK downloaded at run time can change under the
test, and a failure would then be a fact about F-Droid rather than about UNIQUE. It is a
report a person reads:

```
== real-app smoke ==
  PASS  com.termux                                 ACTIVITY_HARDWARE_ACCELERATED … applied=true
  PASS  org.fossify.gallery
  PASS  org.schabi.newpipe
  PASS  com.shatteredpixel.shatteredpixeldungeon
  PASS  de.danoeh.antennapod
  PASS  com.kunzisoft.keepass.libre
  PASS  com.beemdevelopment.aegis
```

What it does *not* say is that the apps are usable: nothing here looks at the screen, and
"reached its own main activity and stayed up for five minutes" is the whole claim.

### The fifth run: everything launched, and Play services killed three of them

The log that came back from the phone after the pass above is the first one in which the
engine is not the story. Eight launches, six of which reached the guest's own Activity, and
the report was: *"some apps seem to launch, some don't, in some the screen does not
respond, games crash and there is no keyboard."*

`tools/device-log/analyze.py` on that capture, which is checked in as
`tools/device-log/fixtures/redmi-android15-run5.log`:

```
[FAIL] launch       6/8 launches reached the guest's Activity
[FAIL] crash        com.gordey.standarling crashed on main: SecurityException: Unknown calling package name
                    com.a0soft.gphone.acc.free crashed on main: SecurityException: Unknown calling package name
                    com.Chillow.CustomRise crashed on main: SecurityException: Unknown calling package name
[FAIL] providers    androidx.core.content.FileProvider did not publish
[FAIL] permissions  android.permission.SYSTEM_ALERT_WINDOW denied 3x, but it is an install-time permission
[ok  ] render       (the fourth run's fault, and it stayed fixed)
```

| Found | Fixed by |
|---|---|
| **Three guests died of one thing.** `GmsClient.getRemoteService` sends `context.getPackageName()` to `com.google.android.gms`, which resolves the *calling* uid — UNIQUE's — and answers `SecurityException: Unknown calling package name '<the guest>'`. It arrives on a `Handler`, so it is fatal and uncatchable, and it fired seconds after each app reached its own screen. Every one of the three stacks is Firebase's `dynamite_measurementdynamite` module | `com.google.android.gms` and `com.google.android.gsf` are hidden from a guest: absent from `getPackageInfo`, `getApplicationInfo`, `getPackageUid`, `resolveContentProvider`, the installed list and both intent resolvers. An SDK that asks finds nothing and takes the path it already has for a phone without Play services. `com.android.vending` stays visible — Play's licence check is an ordinary bind that works, and hiding it would break the PAIRIP case fixed one run earlier |
| **No guest has ever had a keyboard.** `EditorInfo.packageName` is the app's own, and `InputMethodManagerService.startInputOrWindowGainedFocus` checks it against the calling uid: a mismatch is `InputBindResult.INVALID_PACKAGE_NAME`, and *no IME is bound*. Nothing is logged in the app's process — the field takes focus, shows a caret, and accepts nothing | `input_method` proxied, with a guard that rewrites the `EditorInfo`'s own field rather than an argument. Two `$Stub` spellings, because the interface moved from `com.android.internal.view` to `com.android.internal.inputmethod` in Android 14, and two singleton caches for the same reason |
| **A guest came up with no `FileProvider`.** `ContentProvider.attachInfo` runs the app's own code, and `FileProvider`'s reads external storage while parsing its `<paths>`. The registry installed providers *before* the identity hooks, so that call went out under the guest's name: `PROVIDER_PUBLISH_FAILED … SecurityException: callingPackage does not match UID`. `FileProvider` is how an app shares a file with anything outside itself | Providers are published after `VirtualIdentityHooks`. They still precede every *guest* component and its `Application.onCreate`, which is the ordering apps rely on; what changed is that they no longer precede UNIQUE's own plumbing |
| **"In some apps the screen does not respond."** Touch was never broken. `ACTION_DOWN` and `ACTION_UP` reach every guest that is alive, in the log, for every app. The unresponsive screens are the windows left behind by the three crashes above — still drawn, with no process behind them | Nothing, directly. Fixing the crashes is the fix |
| **`SYSTEM_ALERT_WINDOW` denied three times**, and it is an install-time permission: no dialog exists for it, so no user could have refused it. UNIQUE did not declare it, and Android's overlay screen does not list an app that does not | Declared — which grants nothing by itself; `SYSTEM_ALERT_WINDOW` is granted on the Settings screen, and the declaration is what makes UNIQUE appear on it. App Details now lists it, with exact alarms and unrestricted background work, each with its real state and the screen that grants it |
| **`bin.mt.plus` never reached its `Application`**: `UnsatisfiedLinkError: No implementation found for void l.ۢ.<clinit>()`, thrown by its own packer's static initialiser before UNIQUE's graft could finish. Unexplained | Nothing. It is reported as `NO_APPLICATION` and the analyzer's fifth-run tests assert that it keeps being reported. A commercial packer that decrypts its own classes in a `<clinit>` is a class of app UNIQUE does not claim |

And what the same message asked for that was not a fault:

- **The *Advanced* section is gone** — the device test, the checklist, the diagnostics
  screen, the export, and the engine code behind all four. §14.2 of ARCHITECTURE.md says
  what went and what survives it.
- **Everything the interface shows is translated.** Engine failures carry a code the
  interface translates, with the engine's English prose as the fallback for a code it does
  not know; an automatic profile name is written in the reader's own language rather than
  stored in English. `tools/check-translations.py` fails when a code has no sentence in
  both languages, or a sentence has no code.

Two of the three engine fixes cannot be *observed* on the verification emulator: it is an
AOSP image with no Play services, so the crash they prevent cannot be reproduced there, and
it has no IME of its own to bind. What the emulator does say about the keyboard is that the
proxy installs and the guard binds to exactly the method that matters:

```
HOOK SERVICE_HOOKED service=input_method cache=true singletons=1
     bound=callerPackage,editorIdentity
     matched=… editorIdentity=startInputOrWindowGainedFocus
HOOK IDENTITY_HOOKS_INSTALLED package=com.unique.probe
     installed=…,appwidget,input_method skipped=
```

The `com.android.internal.inputmethod.IInputMethodManagerGlobalInvoker` singleton is
reported skipped on this image and the `android.view.inputmethod` one patched, which is
what the two spellings are there for. The hiding decision is unit-tested instead
(`VirtualPackageManagerHookTest`, six tests), and both rows say `NOT_TESTED` on ARM64 in
`docs/COMPATIBILITY.md` until a phone says otherwise.

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
| **A released slot's process was never ended, so every app launched after it got `SLOT_ALREADY_BOUND` and never started.** Three apps in a row on a real phone | fixed (the pool ends the process, checks liveness before reassigning, and reclaims dead slots) |
| Nothing ever called `ProcessPool.release` on a crash or a low-memory kill, despite the doc saying so, so a crashed guest kept its slot for as long as UNIQUE stayed up | fixed (liveness is re-checked at allocation instead of relying on a callback) |
| **Install-time permissions were denied to every guest** — `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` — with no user action that could have granted them | fixed (`PlatformPermissions` splits runtime from install-time; only dangerous ones are the user's decision) |
| A permission the guest's own manifest defines was denied, because the host does not hold a permission only the guest declares | fixed (self-defined non-dangerous permissions are granted, as at install) |
| `RestrictionsManager.getApplicationRestrictions` refused, killing ChatGPT in `Activity.onCreate` | fixed (`restrictions` proxied) |
| `LocaleManager.getApplicationLocales` refused, killing ChatGPT's network thread | fixed (`locale` proxied; `setApplicationLocales` refused rather than applied to UNIQUE) |
| `ConnectivityManager.getNetworkCapabilities` refused, so a guest saw no network | fixed (`connectivity` proxied, with eleven more services that validate the same way) |
| UNIQUE's own App Details screen threw on every open: `as bool?` throws on a `String` in Dart, so the tolerant fallback beside it never ran | fixed (type-test instead of cast, with tests for both shapes) |
| A guest with no libraries loaded yet reported `IO_REDIRECT_INSTALLED status=NOT_IMPLEMENTED`, which reads as a missing subsystem | fixed (`NOTHING_TO_HOOK`; a failed dlopen watch is `kFailed`) |
| The 131 JVM tests could not be run without a 1 GB Flutter SDK, because `settings.gradle.kts` refused to configure without it | fixed (a build without Flutter configures `core/` and says loudly that `:app` is not in it) |
| **The guest's `Application.onCreate` ran in the middle of the graft**, before the identity hooks and before its own providers, so anything it touched cached an unproxied interface — which is why `connectivity` and `notification` were still refused after both were proxied | fixed (null `Instrumentation` into `makeApplicationInner`; `callApplicationOnCreate` last, as `handleBindApplication` does it) |
| `androidx.startup`'s `InitializationProvider` failed with "WorkManager is already initialized", because providers were published after `onCreate` rather than before | fixed by the same reordering |
| `IStorageManager.getVolumeList` refused, killing a guest through `Environment.isExternalStorageManager` | fixed (`mount` proxied) |
| `IAccountManager` was declared in `TARGETS` and installed by nothing, so the Play Store died asking about accounts — and a guest saw the host's real Google accounts | fixed (installed; the accounts a guest *should* see is a separate open question) |
| The `search` hook bound to nothing nine times a run: `ISearchManager` takes no String on API 35 | fixed (removed, and recorded as a deliberate omission the survey no longer nags about) |
| A guest's launch had no starting window and no transition animation, from two undocumented overrides on the stub theme | fixed (both removed; the platform's own behaviour) |
| **Every virtual activity rendered in software**: the substituted `ActivityInfo` carried no `flags`, and `Activity.attach` reads exactly that bit to decide. Slow for everything, fatal for anything drawing through a `RenderNode` | fixed (`android:hardwareAccelerated` parsed with the platform's two-level default; `WindowAttributesTest`, `t39`) |
| A landscape game opened portrait: the platform takes the orientation from the *stub's* manifest entry | fixed (`setRequestedOrientation` before the guest's `onCreate`; `t40`) |
| PAIRIP-signed apps killed themselves at startup: Play's licensing service is guarded by `com.android.vending.CHECK_LICENSE`, which the guest declared and UNIQUE did not | fixed (declared, with `READ_GSERVICES`, the FCM receive permission and the install-referrer bind) |
| `ApplicationInfo.metaData` was always null, so Play services threw `A required meta-data tag … does not exist` on an app that declares it | fixed (typed meta-data entries, resolved against the guest's own resources; `t41`) |
| `getPackageInfo` ignored its flags — `GET_ACTIVITIES`, `GET_SERVICES`, `GET_META_DATA` all returned null | fixed (`GuestComponents`; `t42`) |
| `getLaunchIntentForPackage` and `resolveActivity` for the guest's own components returned nothing | fixed (`GuestIntentResolution`, scoped to intents that name the guest; `t42`) |
| `getExternalFilesDir()` named a scoped-storage directory UNIQUE may not create, so external storage read as unavailable | fixed (the `mount` proxy rewrites `getVolumeList`, which `Environment` builds every external path from; `t44`) |
| `/proc/self/cmdline` said `com.unique:vapp0`, and the process table showed UNIQUE's own processes and sibling instances | fixed (`Process.setArgV0` as `handleBindApplication` does it, plus a process-table rewrite; `t43`) |
| A permission UNIQUE does not hold could never be granted to an instance — the switch was disabled and the row explained a problem the user could not act on | fixed (the switch asks Android for it, and offers the settings page when the platform has stopped asking) |
| **The `slotStarting` announcement was a no-op on a cold process** (`hostPackage` is set later in the graft), so a waiting caller re-warmed a process that was mid-graft and ActivityManager killed it: `bg anr`, forty-five seconds into a launch | fixed (the announcement falls back to the context's own package, which before the graft is UNIQUE's) |
| A 1.6 GB APK's signature was verified on the main thread during the graft, 2.5 s, tripping the OEM hang watchdog | fixed (loaded on its own thread, waited for only by a caller that asked for signatures) |
| `service download was not proxied: service not available`, ten times a run — there is no binder service called `download` | fixed (removed; `DownloadManager` is a `ContentResolver` client) |
| The hardware-acceleration bit was on the `ActivityInfo` and the window still came up without it on an Android 14 device | fixed (`Window.setFlags` before the guest's `onCreate`, which also records it as forced so `generateLayout` cannot clear it; `t39`) |
| A `@integer` reference in the guest's meta-data resolved to its resource id, because `LoadedApk.getResources()` is null during the graft | fixed (the bundle is rebuilt from the `Application`'s own resources once `makeApplication` has run; `t41`) |
| `getVolumeList` was refused with `callingPackage does not match UID` **after** `mount` was proxied: the external-storage guard shadows the generic caller-package rewrite, because the first shim that binds to a method wins | fixed (the guard carries the rewrite itself, and says so where a reader will look) |
| With that fixed, external storage still pointed at `/storage/emulated/0`: `StorageVolume.mPath` is a `File`, and writing a `String` into it is an `IllegalArgumentException` the surrounding `runCatching` turned into a quiet false | fixed (the field's declared type is read and the value built to match; the repoint is reported, not only its failure; `t44`) |
| **A second launch of an app that was already running produced nothing.** `FLAG_ACTIVITY_NEW_TASK` onto a task already running the component is `START_DELIVERED_TO_TOP`, and the intent delivered was UNIQUE's *stub* intent — which `ActivityThread` assigns to `Activity.mIntent`, so `getIntent()` answered with it from then on | fixed (`NewIntentItem` rewritten in place, keeping the `ReferrerIntent` the platform reads the referrer from; `t45`) |
| An implicit service start scoped to the guest resolved to nothing, because the platform has no filters for a package it never installed — the shape a great many SDKs use to reach their own worker | fixed (resolved against the guest's own manifest, and only when exactly one service matches; `t46`) |
| A Settings screen an app opens about itself named the guest in a `package:` URI, so the device's Settings had nothing to open and a cleaner app's onboarding dead-ended | fixed (retargeted to UNIQUE, whose uid is the one that would hold the access) |
| `getInstallerPackageName` threw `IllegalArgumentException: Unknown package` for the guest — unchecked, and called by nearly every analytics and update SDK on launch | fixed (answered as null, which is what a sideloaded app gets and what UNIQUE is; `t42`) |
| A Chromium renderer crash from the WebView test arrived two seconds into the *next* test and killed the process it had just started, reporting a Chromium fault as `t31` failing to resolve an implicit intent | fixed in the suite (`t30` retires the process that hosted the WebView, so the abort has nothing left to take with it) |
| A guest was held to **UNIQUE's** target SDK by two different mechanisms: `ActivityManagerService` compat checks against the calling uid, and the compat config `ActivityThread` installs in the process at bind time. An app built against Android 9 threw on `registerReceiver` and on `PendingIntent` | fixed (the export flag is supplied at its call site; `GuestCompatChanges` disables in-process changes the guest's target SDK predates, with the ids read from the platform) |
| `startForeground(id, notification)` asked for **every** foreground-service type UNIQUE declares, because `FOREGROUND_SERVICE_TYPE_MANIFEST` resolves against the stub's manifest entry | fixed (resolved against the guest's own entry; a guest that declares none gets `specialUse`) |
| `AppWidgetManager.getAppWidgetIds` was refused: `appwidget` was unproxied, and the package it checks is inside a `ComponentName` rather than a string | fixed (proxied, with a `ComponentName` rewrite in the guard) |
| An app enabling or disabling one of its *own* components — how a launcher icon is changed, and how a keyboard is turned on — was refused with `Attempt to change component state`, because the component belongs to a package the platform never installed | fixed (`GuestComponentState`, per instance and on disk; both intent resolvers and `ComponentInfo.enabled` read it) |
| A failed graft reported `message=java.lang.reflect.InvocationTargetException` and nothing else, which names UNIQUE's calling convention and nothing about the app | fixed (the chain is unwrapped to its root with the first frames, and the stack goes to `logcat` under the tag a device capture is filtered on) |
| A guest publishing a shortcut killed its own `Application.onCreate`: a `ShortcutInfo` carries its own package name and the platform checks each against the caller | refused honestly, not fixed (`SHORTCUT_PUBLISH_UNSUPPORTED`, returning the `false` the API already defines for a rate-limited caller). Routing a published shortcut back into the guest is a feature and is listed as not implemented |
| Three of the four client-side framework caches UNIQUE thought it was disabling did not exist under those names, and the one that mattered was not on the list at all — `ApplicationPackageManager` answers `getComponentEnabledSetting`, `getPackageInfo` and `getApplicationInfo` from the process, where no Binder shim can see them | fixed (the *classes* are named and every static no-argument `disable…Cache()` on each is discovered and called; three more were found this way, and the list can no longer go stale when a release renames one) |
| **Play services killed three guests seconds after they launched.** `GmsClient.getRemoteService` sends the guest's own package name to `com.google.android.gms`, which resolves the calling uid to UNIQUE's packages and answers `SecurityException: Unknown calling package name`, on a `Handler`, fatally | fixed (`com.google.android.gms` and `com.google.android.gsf` are hidden from everything a guest can ask the package manager, so an SDK takes its own "no Play services" path; `com.android.vending` stays visible. `VirtualPackageManagerHookTest`) |
| **No guest could type.** `EditorInfo.packageName` is checked against the calling uid before an IME is bound, and a mismatch is `INVALID_PACKAGE_NAME` — silently, with nothing logged in the app's process | fixed (`input_method` proxied under either of the two `$Stub` package names the interface has had, with a guard that rewrites the `EditorInfo` field rather than an argument) |
| **A guest's own `FileProvider` never published.** Providers were installed before the identity hooks, and `FileProvider.attachInfo` reads external storage while parsing its `<paths>` — under the guest's name, which the platform refuses | fixed (the registry installs after `VirtualIdentityHooks`, still before every guest component) |
| `SYSTEM_ALERT_WINDOW` was denied to every guest that asked, and it is install-time: no dialog exists, so no user refused it. UNIQUE did not declare it, and Android's overlay screen does not offer an app that does not | fixed (declared, which is what puts UNIQUE on that screen; App Details lists overlay, exact alarms and unrestricted background work with their real state and opens each one's screen) |

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
| Publishing a shortcut from a guest | 10 | A `ShortcutInfo` carries its own package name and the platform checks it against the caller; rewriting it fails the next check, which is that the shortcut's activity is a *main* activity of that package. Making them work means routing each shortcut's intents onto stubs, the way `PendingIntent`s are, and surviving UNIQUE's own updates. Refused with `false` — which `setDynamicShortcuts` already returns for a rate-limited caller — and reported as `SHORTCUT_PUBLISH_UNSUPPORTED` |
| Device profile regenerate | 7 | UI row says it is not available yet |
| WebView rendering on this environment | 6 | A WebView is created correctly in the guest with the instance's own data directory, but Chromium's renderer crashes on this emulator *outside* virtualization too, so rendering is `NOT_TESTED` rather than attributed to UNIQUE (`t30`) |
| URI permission grants between virtual processes | 3 | `grantUriPermission` across `:vappN` is not implemented |
| VirtualCore server interface | 3 | `onBind` returns null; the UI process owns the database for now |
| Play services, to a guest | 6 | Hidden entirely rather than half-served. Every SDK path that reaches `com.google.android.gms` ends in a uid check UNIQUE cannot satisfy, and the failure is a fatal `SecurityException` on a `Handler` — so an app that asks now finds no Play services and takes the path it already has for a phone without them. Making this work means implementing the bridges (row above), not hiding less |
| A diagnostics UI inside the app | 8 | Removed at the request of the person it was written for. Everything UNIQUE records goes to `logcat` under one tag, and `tools/device-log/analyze.py` reads a capture from any recorder app |

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


- **A guest is never relaunched for a config change it did not declare.**
  `ActivityRecord.shouldRelaunchLocked` reads the *stub's* `android:configChanges`, and the
  stub declares every change it can, so a guest that did not declare `orientation` keeps
  the layout it loaded in the other one after a rotation. Calling `Activity.recreate()` for
  an undeclared change is the obvious answer and was tried: in the acceptance suite it
  resurrected an activity from an earlier test, which re-applied its landscape orientation
  and rotated the display around a permission request, destroying the activity that was
  waiting for the result. A stale layout is a cosmetic fault; a lost activity result is
  not. It stays a limit until there is a way to do it that cannot touch an activity with
  work in flight.
- **An implicit service intent with no package at all cannot be helped.**
  `ContextImpl.validateServiceIntent` throws in the *app's* own process, before any call
  reaches ActivityManager, so UNIQUE never sees it. What UNIQUE can resolve — and now
  does — is the far commoner shape, `setPackage(getPackageName())` with no component.
- **No AOT.** Since Android 10 an app cannot invoke `dex2oat`, so virtual apps are
  JIT-only and cold start is slower than an installed app. This is a platform property.
- **Attestation.** Play Integrity, Play Games and Play Billing are expected not to work
  and are recorded `UNSUPPORTED_FOR_NOW` — expected, not measured.
- **32-bit-only apps** on a 64-bit-only device.
- The Credential Manager route is the Google layer's **central hypothesis**, unverified.

## What a survey of real apps says is missing

`tools/apk-survey/survey.py` reads the `method_ids` table out of real APKs and reports
which system services they call. Across 63 apps from F-Droid, nine services real apps use
are not proxied — and three of those are the worse kind, named in
`SystemServiceHook.TARGETS` with nothing installing them, so they read as done:

| Service | Manager | Apps | State |
|---|---|---|---|
| `window` | `WindowManager` | 60/63 | declared, never installed |
| `phone` | `TelephonyManager` | 34/63 | not in `TARGETS` |
| `download` | `DownloadManager` | 5/63 | not in `TARGETS` |
| `account` | `AccountManager` | 3/63 | declared, never installed |
| `media_session` | `MediaSessionManager` | 3/63 | declared, never installed |
| `device_policy` | `DevicePolicyManager` | 3/63 | not in `TARGETS` |
| `media.camera` | `CameraManager` | 3/63 | not in `TARGETS` |
| `telecom` | `TelecomManager` | 1/63 | not in `TARGETS` |
| `media_router` | `MediaRouter` | 1/63 | not in `TARGETS` |

`account` matters beyond compatibility: until it is installed, `AccountManager.getAccounts()`
from inside a guest returns the **host's real Google accounts**, which is a per-instance
identity leak rather than a missing feature.

None of this is a claim that these apps fail — a DEX reference is not a call. It is a
ranking of what to proxy before a phone finds it, and it is the check that would have
caught `restrictions`, `locale` and `connectivity` before one did.

## Next steps, in order

1. **The sixth phone run**, which is the only place three of this pass's fixes can be
   observed at all: the verification emulator has no Play services to refuse a guest's
   identity and no IME to bind, so neither the hiding nor the keyboard can be reproduced
   there. What to watch for: a guest with a text field brings up the phone's own keyboard;
   no `Unknown calling package name` anywhere in the capture; `PROVIDER_PUBLISH_FAILED` gone.
   `docs/PHYSICAL_DEVICE_TEST.md` is the sequence — start a log recorder, work the twelve
   steps, send the capture.
2. ARM64 native code, a real GPU driver, a hardware Vulkan ICD, WebView rendering and a
   real engine app — five things only a phone can answer, and every one of them is
   `NOT_TESTED` until it does.
3. A temporary URI grant handed *into* a guest — the case a photo picker actually uses.
   Sharing outward works (`t34`) and the inbound request is at least well-formed (`t36`),
   but arranging a real grant needs a third APK: instrumentation runs under the target
   app's uid and can neither write another app's files nor grant for its authority.
4. Google, which needs a device with a Google stack. `docs/GOOGLE_DEVICE_TEST.md` is the
   procedure, in the order that makes one failure explain the next.
5. A real engine sample (Unity/Unreal). None is available in this environment, and no
   claim will be made without one.
6. **Re-running the `verify` build.** Everything after `t38` has only been run on
   `debug`, and the `verify` build is what a tester installs — the difference is not
   cosmetic: it is unminified but non-debuggable, so ActivityManager holds it to the
   ordinary ten-second process-start budget.
7. **Verifying the minified release build.** The artifact a tester is actually given is
   already covered: `BUILD_TYPE=verify ./tools/verify-device.sh` runs the whole suite
   against the exact APK in `dist/` — not a near neighbour of it — and passed 38 of 38
   against the suite as it stood then.
   What is left is R8. The minified build assembles and signs, and its keep rules hold
   structurally: the stub pool, the router and shared providers, `UniqueNative` and the
   native entry points all survive, checked on the artifact. But the suite cannot be
   pointed at it — `androidx.tracing.Trace` reaches `AndroidJUnitRunner.onCreate` from
   R8's *classpath* rather than from program input, so no `-keep` rule applies — and a
   class surviving is not the same as a reflective lookup succeeding.

See `docs/COMPATIBILITY.md` for the per-application matrix and
`docs/PHYSICAL_DEVICE_TEST.md` for the physical-device checklist.
