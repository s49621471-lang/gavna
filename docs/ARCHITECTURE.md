# UNIQUE — Architecture

> Application-level Android virtualization platform.
> Target: Android 12–16 (API 31–36), ARM64-v8a, no root, no unlocked bootloader.
> Status of this document: **PHASE 0 output**. It is normative for the codebase.

---

## 0. One-paragraph summary

UNIQUE runs unmodified Android applications inside processes owned by UNIQUE itself.
There is no CPU emulation and no separate ROM: virtual apps execute their own DEX and
their own ARM64 `.so` files directly, in real Android app processes, on the real ART
runtime, drawing to real `Surface`s on the real GPU. What is virtualized is the
*framework contract* — everything the app learns about "which package am I, where is my
data, who is installed, what is my device identity" — plus the file paths the app touches.

```
Host Android (unmodified, unrooted)
└── UNIQUE host application  (uid = u0_aNNN, one real Linux uid for everything)
    ├── :core     UNIQUE UI (Flutter) + orchestration
    ├── :server   VirtualCore server: VPM / VAM / VUM / profiles / permissions
    ├── :vapp0..N virtual app processes  ← virtual apps' DEX + .so run here
    └── :helper   notification / FCM / bridge helpers
```

---

## 1. Technical audit of candidate bases, and the decision

### 1.1 What was actually evaluated

| Candidate | State (checked 2026-09) | Usable ceiling | Verdict |
|---|---|---|---|
| **VirtualApp** (`asLody/VirtualApp`) | Public repo contains only the `app/` demo shell; the `lib/` engine was removed. README states the open code **stopped updating in December 2017**; the maintained code is a proprietary commercial licence. | Android 8.0 era | **Not forkable.** Reference only. |
| **BlackBox** (`FBlackBox/BlackBox`) | Repository **deleted by its author**; the remaining README says the project was dissolved after an abuse incident. No source. | Android 12/13 era | **Not forkable.** Design reference only, from memory/derivatives. |
| **NewBlackbox** and community forks | Scattered, unmaintained, mutually incompatible mirrors of the deleted BlackBox tree; all carry BlackBox's Java-reflection-first hook layer. | Android 13 with patches | **Not forkable.** |
| **LSPlant** (`LSPosed/LSPlant`) | Actively maintained ART inline-hook (method-level) library. | Android 8–16 | **Reuse as a library** (component, not base). |
| **android-inline-hook** / ShadowHook (`bytedance/android-inline-hook`) | Actively maintained ARM64 native inline hook. | Android 4.4–16 | **Reuse as a library**. |
| **whale** (`asLody/whale`) | Cross-platform inline hook; largely superseded by the two above. | — | Reference only. |

### 1.2 Decision

**UNIQUE is a clean-room engine.** It does not fork VirtualApp, BlackBox, or any of their
derivatives. It *does* deliberately adopt the four architectural inventions those projects
proved out, re-implemented for Android 12–16:

1. **Stub component pool** — pre-declared placeholder Activities/Services/Providers in the
   host manifest that stand in for the virtual app's real components.
2. **Binder / system-service interception** — replacing the cached `IInterface` singletons
   that `Context` APIs route through.
3. **`LoadedApk` grafting** — building a real `LoadedApk` + `ContextImpl` + `Application`
   for a package that the system never installed.
4. **Native IO redirection** — rewriting path arguments below libc so hard-coded absolute
   paths resolve inside the virtual space.

Rationale for clean-room rather than fork, in order of weight:

- **Both viable bases are legally and practically dead.** One is a 2017 snapshot whose
  maintained successor is proprietary; the other was deleted by its own author. Building a
  product on either means inheriting an unmaintainable, unlicensable tree.
- **Their hook layer has the wrong shape for 2026.** VirtualApp/BlackBox pin framework
  *AIDL signatures* (`IActivityManager.startActivity(...)` with a fixed arg list) and
  patch by argument index. Those signatures churn every release; that is precisely why
  every fork rots. UNIQUE replaces this with **signature-agnostic interception**
  (§5.2) — the single most important modernization in this design.
- **Their process/permission model predates Android 14–16.** Foreground-service types,
  `RECEIVER_EXPORTED`, `PendingIntent` mutability, package visibility, 16 KB pages,
  edge-to-edge enforcement, and per-uid `CompatChanges` all need first-class handling, not
  patches.
- **Google integration was an afterthought there.** In UNIQUE it is a designed subsystem
  with a policy router and its own test matrix (§9).

### 1.3 What is concretely reused, and from where

| Mechanism | Source of the idea | How UNIQUE does it |
|---|---|---|
| Stub activity pool + `ActivityInfo` swap | VirtualApp | Generated stubs, per-`taskAffinity` pools, `ClientTransaction` rewrite instead of `Handler.Callback` |
| `ServiceManager` service proxying | VirtualApp / BlackBox | `MethodShim` registry with declarative argument matchers, plus cached-singleton invalidation sweep |
| Native IO redirection | VirtualApp (`libva++`) | ShadowHook on the 30 path-taking libc entry points, 16 KB-page-safe |
| ART method hooking | Xposed lineage | **LSPlant** as a dependency (Apache-2.0), not a reimplementation |
| Native inline hooking | — | **ShadowHook** as a dependency (MIT), not a reimplementation |
| Hidden-API access | LSPosed `HiddenApiBypass` | Library dependency + native fallback (§4.1) |
| Virtual GMS space | Parallel Space / island-class apps | Mode A of the Google router, importing the *device's own* GMS (§9.3) |

Nothing is copy-pasted from a repository with an unclear licence. Every third-party
component is a declared Gradle/CMake dependency with a recorded licence, surfaced in
Settings → About → Open-source licences.

---

## 2. What UNIQUE is not

Stating the boundary precisely, because it drives every other decision:

- **Not a VM / not an emulator.** No QEMU, no separate kernel, no separate ROM.
- **Not a privilege escalation.** Everything runs as the host's single Linux uid. UNIQUE
  cannot give a virtual app a permission the host does not hold.
- **Not an attestation bypass.** Defeating Play Integrity / SafetyNet / DroidGuard is out
  of scope as a matter of policy. Whether they happen to work is a separate, untested
  question, recorded as `UNSUPPORTED_FOR_NOW` until measured (§9.7).
- **Not a way to run a different ABI.** ARM64-v8a only. A 32-bit-only app is
  **Unsupported** on a 64-bit-only device; on a device with a 32-bit runtime, a separate
  32-bit helper package would be required (roadmap, not MVP).

---

## 3. Process model

### 3.1 Processes

| Process | Purpose | Lifetime |
|---|---|---|
| `com.unique` (`:core`) | Flutter UI, launcher, settings. **No hooks installed.** | User-driven |
| `com.unique:server` | VirtualCore server — VPM, VAM, VUM, VPermission, DeviceProfile, Diagnostics sink. Owns the state database. Single writer. | Sticky while any vapp lives; `bindService`-pinned by `:core` while UI is foreground |
| `com.unique:vappN` (N = 0..15) | One virtual app process. Hooks installed at `attachBaseContext`. Hosts exactly one (virtual uid, process-name) pair at a time. | Pooled + recycled |
| `com.unique:helper` | Notification trampolines, FCM receiver, host-GMS bridge activities. Never loads virtual DEX. | Short-lived |

Crash isolation follows from this directly: a `:vappN` death is observed by `:server` via
`IBinder.DeathRecipient` and reported to `:core`; the UI shows *App stopped · [Restart]
[Details]* (§22 of the brief) and UNIQUE itself is unaffected.

### 3.2 Why a stub-process pool and not one process per app

Android does not let an app create processes on demand — a process only exists because a
manifest component declares `android:process`. So the pool is declared statically at build
time by a Gradle task (`:app:generateStubs`, §5.1) and assigned dynamically at runtime by
`VProcessManager`.

Assignment rule: a virtual app's manifest process name (`android:process`, defaulting to
the package name) plus its virtual user id forms a key; `VProcessManager` maps
`(vuid, processName) → :vappN`, so an app that declares three processes gets three slots,
exactly as it would when installed. This is required for correctness, not just fidelity:
apps rely on `:push`/`:remote` processes being genuinely separate.

Reclamation: LRU over empty slots, plus `onTrimMemory(TRIM_MEMORY_COMPLETE)` pressure
handling, plus explicit *Stop* from the UI.

---

## 4. Runtime access layer (the part that decides whether anything works at all)

### 4.1 Hidden API access

Android 9+ restricts reflective access to non-SDK interfaces; Android 10+ closed the
double-reflection bypass; Android 12+ narrowed the greylists further. UNIQUE needs deep
non-SDK access (`ActivityThread`, `LoadedApk`, `ServiceManager`, `ClientTransaction`, …),
so this is a hard prerequisite, resolved with defence in depth, in order:

1. **`HiddenApiBypass`** (LSPosed, Apache-2.0) — `sun.misc.Unsafe` +
   `Class.getDeclaredMethods` via a boot-classloader-trusted `Executable[]` path. Verified
   working through API 36.
2. **`VMRuntime.setHiddenApiExemptions(["L"])`** invoked from JNI, which sidesteps the
   caller-classloader check.
3. **Native fallback** — resolve `art::hiddenapi::` policy state through
   `libart.so` symbols for the exact ART version, gated by an ART-version table.

If all three fail on a device, UNIQUE refuses to launch virtual apps and says so plainly
in Diagnostics rather than half-working. This is checked once at `:server` start and
cached per (fingerprint, ART version).

### 4.2 Signature-agnostic interception — `MethodShim`

The central modernization. Instead of:

```java
// The VirtualApp/BlackBox shape — pinned to one AIDL signature. Rots every release.
if (method.getName().equals("startActivity")) { args[1] = hostPkg; /* index 1?? */ }
```

UNIQUE declares *what* to rewrite, never *where*:

```kotlin
shim("startActivity") {
  rewriteAll<String>(matching = VirtualPackages) { hostPackageName }   // callingPackage
  rewriteFirst<Intent> { intent -> intentBridge.outbound(intent) }
  rewriteAll<Int>(tagged = ArgTag.USER_ID) { hostUserId }
}
```

Argument *positions* are discovered at hook-install time by scanning the live
`Method.getParameterTypes()` of the interface actually present on the device, combined
with a per-API-level `ArgTag` table for the ambiguous primitive cases. The result:

- Adding an argument in Android 17 does not break the shim.
- One shim definition covers API 31–36 instead of six `if (SDK_INT >= …)` branches.
- Unknown/unmatched methods pass through untouched, which is the safe default.

Every shim carries `minApi`/`maxApi` and a `verify()` that runs at install time; a shim
that cannot bind logs a structured `HOOK_BIND_FAILED` diagnostic instead of throwing.

### 4.3 Interception points

| Layer | Point | Used for |
|---|---|---|
| Binder | `ServiceManager.sCache` + `sServiceManager` | `activity`, `package`, `window`, `notification`, `appops`, `alarm`, `jobscheduler`, `content`, `permissionmgr`, `telephony`, `wifi`, `media_session`, `clipboard`, `account`, `device_policy` |
| Binder | Cached singletons (`ActivityManager.IActivityManagerSingleton`, `ActivityTaskManager.IActivityTaskManagerSingleton`, `AppGlobals.sPackageManager`, `ContentResolver` provider cache, …) | The framework caches the *unwrapped* interface; a `ServiceManager`-only hook is not enough. A sweep re-wraps every known singleton after `ServiceManager` patching. |
| ART | `Instrumentation`, `ActivityThread.mAppThread`, `ClientTransactionHandler` | Activity/Service lifecycle (§6.1) |
| ART (LSPlant) | `Build` accessors, `System.loadLibrary`, `Runtime.loadLibrary0` | Reserved for phase 4/6: only for call sites genuinely unreachable through the Binder layer. `Settings.Secure.getString` is *not* one of them — it routes through `IContentProvider`, which the Binder shims already cover — so LSPlant is not yet a dependency. |
| Native (ShadowHook) | `open/openat/openat2, stat/lstat/fstatat, access/faccessat, mkdir/mkdirat, unlink/unlinkat, rename/renameat/renameat2, chmod/chown, link/symlink/readlink, opendir, statfs/statvfs, execve, dlopen/android_dlopen_ext, __system_property_get/find/read_callback` | IO redirection + property virtualization (§7.3, §11.4) |

---

## 5. Module tree

```
unique/
├── settings.gradle.kts
├── build-logic/                 convention plugins (one place for compileSdk/NDK flags)
├── app/                         host application: manifest, stubs, process entry points
│   └── build/generated/         :app:generateStubs output (stub components)
├── core/
│   ├── common/                  pure JVM. NO android.* deps. Unit-tested.
│   │                            AXML/ARSC parsing, APK+split model, path model,
│   │                            device-profile model, diagnostics model, compat model
│   ├── hook/                    MethodShim engine, ServiceManager patching, LSPlant glue,
│   │                            hidden-API access, singleton sweep
│   ├── vpm/                     VirtualPackageManager: install/import, splits, signatures,
│   │                            component resolution, IPackageManager shims
│   ├── vam/                     VirtualActivityManager: stub routing, ClientTransaction
│   │                            rewrite, tasks/back-stack, services, receivers, providers,
│   │                            jobs, alarms
│   ├── vprocess/                process pool, binding, death handling, memory pressure
│   ├── vstorage/                virtual filesystem model + redirection ruleset,
│   │                            scoped storage, FileProvider/URI grants
│   ├── vprofile/                DeviceProfileProvider — the single source of identity
│   ├── vpermission/             per-instance runtime permissions + AppOps shim
│   ├── google/                  Google Compatibility Layer: router + 5 bridges (§9)
│   ├── diagnostics/             structured logging, ring buffers, export package
│   ├── compat/                  CompatibilityProfile database + resolver
│   └── native/                  C++: IO redirect, property virtualization, ELF/linker
│                                helpers, 16 KB-safe. Depends on ShadowHook.
├── ui/                          Flutter application (Material You, dark-first)
│   └── pigeons/                 Pigeon interface definitions (typed Flutter↔Kotlin)
├── tools/                       stub generator, compat-db authoring, benchmark harness
└── docs/
```

Deviation from the brief's suggested layout, and why: the brief listed `ui/`, `core/`,
`virtual-*` as siblings. UNIQUE nests engine modules under `core/` so that
**`core/common` can be a pure-JVM module with no Android dependency**. That is what makes
the parser, the path model, the profile generator and the compat resolver unit-testable on
a laptop with no device — which is where most correctness bugs in this class of software
actually get caught. `app/` and `ui/` stay at top level because they are deliverables, not
engine parts.

### 5.1 Stub generation

`:app:generateStubs` (Gradle task, `tools/stubgen`) emits into the host manifest:

- `N × 4` stub activities per pool: `{standard, singleTop, singleTask, singleInstance}`,
  each with 8 `taskAffinity` variants → task separation works for multi-instance apps.
- `N` stub services, `N` stub `JobService`s (distinct `jobId` ranges per virtual uid).
- 2 stub `ContentProvider`s per process (one for the app's providers, one internal).
- Foreground-service type superset (§8.3) declared once on the stub services.

Generated, not hand-written, because the counts are configuration and hand-maintaining
~500 manifest entries is how these projects accumulate drift.

---

## 6. Android component virtualization

### 6.1 Activities

Launch path, as implemented:

```
:core          VirtualLauncher picks a :vappN slot for (vuid, manifest process)
               and starts a stub activity chosen by the target's launchMode.
               VirtualLaunchParams travel as intent extras - the only marshalling
               contract for a launch.
                 |
system_server  resolves and launches com.unique/.stub.ActivityStub_pN_mM_aA,
               creating the process and the window from the STUB's manifest entry.
                 |
:vappN         UniqueApplication.attachBaseContext installs LaunchInterceptor on
               ActivityThread.mH before any component runs.
                 |
               EXECUTE_TRANSACTION arrives. The interceptor finds the
               LaunchActivityItem, reads the parameters, and grafts the package
               (AppBootstrap) synchronously on the main thread.
                 |
               It then replaces the item's Intent and ActivityInfo with the real
               ones, and returns false so the framework proceeds normally.
                 |
               performLaunchActivity resolves LoadedApk from
               activityInfo.applicationInfo, instantiates the app's own Activity
               from the app's own class loader. The stub class is never
               constructed on this path.
```

Three decisions in that flow are load-bearing:

**Why the transaction and not `Instrumentation.newActivity`.** The activity's `Context`
is built from `ActivityInfo.applicationInfo`, so an interception that only swaps the class
leaves the activity reporting UNIQUE's package name and UNIQUE's data directory —
i.e. it looks like it works and silently corrupts the thing the product exists to get
right.

**Why fields are found by type, not by name.** `LaunchActivityItem`'s layout changes in
most releases. It has exactly one `Intent` field and exactly one `ActivityInfo` field, and
that has been true across every release UNIQUE targets. When the count is not exactly one,
the interceptor reports `LAUNCH_ITEM_SHAPE_UNKNOWN` and changes nothing, rather than
guessing.

**Why the graft happens at the transaction rather than at process start.** A `:vappN`
process is started by the system before anything says which instance it is for. Binding at
`attachBaseContext` would mean guessing; binding at the transaction means the parameters
are already in hand, and it guarantees `Application.onCreate` runs before the first
`Activity`.

Bootstrapping is once per process. A slot serves exactly one (instance, manifest process)
pair for its lifetime; a second bind is refused, because two instances have different data
directories and the first instance's objects would keep pointing at the wrong one.

#### 6.1.0 Implicit starts, resolved against the guest's own filters

**Implemented; `t31` covers it.** `PackageManager` resolves an implicit intent against
*installed* packages, so `startActivity(Intent("com.example.OPEN_SETTINGS"))` from a guest
matched nothing and did nothing visible. That is a large hole: it covers a great deal of
ordinary in-app navigation, and it is also why Mode C OAuth (§9.5) could not work — that
flow ends with a browser handing a deep link back, and a deep link is an implicit intent.

`VirtualIntentResolver` matches the intent against the guest's manifest filters using
`android.content.IntentFilter`. Deliberately the platform's own matcher rather than a
reimplementation: action, category, scheme, host, port, path and MIME interact in ways that
are easy to get *nearly* right, and nearly right here means opening the wrong screen.

The harder question is which side should win, because a guest may equally mean the host — a
share sheet, a browser, the dialer. The rule, in order:

1. **Scoped to itself** (`setPackage(ownName)`): the guest said so. Resolve against the
   guest only.
2. **Unscoped, and something installed can serve it**: the host wins. An `https` `VIEW`
   belongs in a browser and a `SEND` belongs in the chooser; pulling either into the guest
   would break behaviour that works today.
3. **Unscoped, and nothing on the device can serve it**: the guest wins, if it matches. A
   custom action only this app declares is certainly this app's.

What this does not reproduce is the platform's own behaviour, where an installed app's
matching activity is one candidate among all and the *user* picks. UNIQUE cannot put a
virtual activity into the system chooser, so it decides — and records which rule decided
(`ACTIVITY_IMPLICIT_ROUTED reason=scoped|onlyHandler`, or
`ACTIVITY_IMPLICIT_HOST_PREFERRED`), because "it opened the wrong thing" is a question that
needs an answer.

#### 6.1.1 Virtualizing PackageManager is a prerequisite, not a feature

`LoadedApk.initializeJavaContextClassLoader()` asks the real `PackageManagerService` for
the package and throws `IllegalStateException: ... is package not installed?` when it
comes back null. So an app that UNIQUE *imported* rather than installed cannot get a class
loader at all until `IPackageManager` is virtualized. `VirtualPackageManagerHook` therefore
runs inside the graft, before the application is created.

Every shim there is conditional: it answers for the virtual package and calls
`ShimCall.proceed()` for everything else. Answering for all packages would break the host
code sharing the process. Permission queries are the exception in the other direction —
they are rewritten to ask about the *host* package, because UNIQUE can only narrow what
the host actually holds and a locally invented "granted" is a lie the platform would then
refuse to honour.

#### 6.1.2 Identity points two ways

Inside a virtual process every `Context` reports the *virtual* package — that is the
product working. But the same name travels outward on every framework call as the
`callingPackage` argument, and `system_server` checks it against the caller's real uid:

```
SecurityException: Given calling package com.unique.probe does not match caller's uid 10109
```

So identity has to be virtual inward and real outward, simultaneously. Nothing works past
the very first framework call otherwise: `PhoneWindow`'s constructor reads one setting,
which acquires a content provider, which is rejected.

`VirtualActivityManagerHook` rewrites arguments equal to the virtual package name to the
host package name — but only on methods an app calls *on its own behalf*, identified by
an `IApplicationThread` parameter, which is the framework's own marker for that. The
asymmetry of the failure modes is what dictates the narrow rule:

- A method missed → a `SecurityException` naming both packages. Loud, readable, fixable.
- A method matched that should not be → a package name that was **data** is silently
  rewritten. `forceStopPackage(String, int)` takes one as data, and a guest calling it
  with UNIQUE's name would stop UNIQUE. It has no `IApplicationThread`, so the predicate
  excludes it, and a unit test pins that it stays excluded.

A short allowlist covers identity-bearing methods that carry no `IApplicationThread`, of
which `getIntentSender` is the one that matters. Enumerating every method by name instead
would be a list that goes stale each release — which is the same reason `MethodShim`
exists at all.

### 6.2 Services

**Implemented; `t07` covers both started and bound.** Services do **not** arrive as
`ClientTransaction`s. `ActivityThread.H` still delivers them as plain messages —
`CREATE_SERVICE`, `BIND_SERVICE`, `SERVICE_ARGS`, `UNBIND_SERVICE`, `STOP_SERVICE` —
whose `msg.obj` carries a `CreateServiceData` / `BindServiceData` holding a `ServiceInfo`
and an `Intent`. The same `Handler.Callback` already installed for activities therefore
covers services, and the same rule applies: locate the fields by *type*, replace the
`ServiceInfo` with the virtual one, and let `ActivityThread` instantiate the app's own
`Service` class.

`CreateServiceData` carries no `Intent` on every release, so which virtual service a
`CREATE_SERVICE` stands for is resolved from the *stub's identity* through
`VirtualServiceRouter` — which is why each concurrently-running virtual service needs a
stub of its own, and why the pool being exhausted is a refusal with a diagnostic rather
than a silently reused stub.

#### 6.2.1 The service family is matched structurally, not by name

The outbound half looked correct for a long time and was not. `Context.bindService`
reached `IActivityManager.bindService` through Android 10, `bindIsolatedService` in
Android 11, and **`bindServiceInstance` from Android 12 on**. A shim registered for
`bindService` still *binds* on API 34 — the method is on the interface, it is simply never
called — so the bind report said everything was fine while every bind left the process
unrewritten and `system_server` answered:

```
W ActivityManager: Unable to start service Intent { cmp=com.unique.probe/.ProbeService } U=0: not found
```

`startService` was unaffected, because that name never changed. The result is the worst
shape a bug can have: half the feature works, and the diagnostics agree with the working
half.

Two changes follow from it, and both generalise:

1. **Match the family, not the spelling.** One shim now claims every `IActivityManager`
   method that takes an `Intent` and is about a service. That covers `startService`,
   all three spellings of bind, `stopService`, `peekService`, and whatever Android 17
   renames them to. `unbindFinished` is named explicitly, being the only member whose
   name does not say "service"; methods keyed on a connection or a token rather than an
   `Intent` — `unbindService`, `stopServiceToken`, `serviceDoneExecuting` — are excluded
   because they need no rewrite.
2. **A binding report must name the concrete methods.** `ShimBindResult` now carries the
   real method names each shim matched, and `SERVICE_HOOKED` / `VAM_HOOK_INSTALLED` print
   them. "Bound" alone cannot distinguish a live interception from a dead one; the
   concrete list can, and it is what makes the next rename a one-line diff instead of an
   investigation.

The same rewrite deliberately covers the two *inbound-originated* members,
`publishService` and `unbindFinished`. `ActivityThread.handleBindService` hands back to
`ActivityManagerService` the very `Intent` object it was given, and AMS looks the binding
up by `Intent.FilterComparison` — so the guest sees its own component in `onBind`, and AMS
must see the stub's again, or the connection is never completed and `onServiceConnected`
never fires. Routing them through the same rewrite reproduces the stub intent exactly,
because `VirtualServiceRouter.outbound` returns the stub already reserved for that service
and `filterEquals` ignores the extras that differ.

**What the client side sees.** `onServiceConnected` used to receive the *stub's*
`ComponentName`, because that is the component AMS knows about:

```
onServiceConnected com.unique/.stub.ServiceStub_p0_s0
```

That is a real divergence, and not a harmless one: code that switches on
`name.getClassName()` to tell two of its own services apart takes the wrong branch every
time, silently. `t07` now asserts the guest's own name.

The interception is on the *receiving* side, and the obvious alternative is worth
recording because it looks right and does nothing. Wrapping the `IServiceConnection` on its
way out to `system_server` accomplishes exactly nothing: only `asBinder()` is marshalled,
so AMS ends up holding the real `InnerConnection` and calls back on that, straight past
the wrapper. The first version did this and the rename never fired once. The outgoing
argument is instead used only as a *handle*: `InnerConnection` holds a weak reference to
its `LoadedApk.ServiceDispatcher`, and the dispatcher holds the app's own
`ServiceConnection`, which is what gets wrapped. Nothing that crosses the Binder is
touched, and a callback naming something that is not one of UNIQUE's stubs is passed
through exactly as it arrived.

**Foreground services on Android 14+** are the sharpest edge:
`Service.startForeground(id, notification, type)` requires (a) the *type* be declared on
the manifest entry of the service that actually calls it — which is the *stub* — and (b)
the host hold `FOREGROUND_SERVICE_<TYPE>`. So:

- Stub services declare the **union** of supported types.
- `VAM` reads the virtual service's declared `foregroundServiceType`, intersects it with
  the host-declared superset, and passes the intersection.
- If the intersection is empty → the FGS start is refused with a clear
  `FGS_TYPE_UNSUPPORTED` diagnostic and the app is marked in the compat DB. It is not
  silently downgraded, because a silently-downgraded FGS produces a
  `ForegroundServiceDidNotStartInTimeException` crash the user cannot interpret.
- Android 15's 6-hour `dataSync` cap and `SHORT_SERVICE` timeout are surfaced as
  diagnostics events.

`ForegroundServiceTypes.HOST_SUPPORTED` is the code-side mirror of the manifest's
`uses-permission` list; a type present in one and absent from the other is a bug that
surfaces as an unexplainable crash inside the guest, so the two are reviewed together.

**Implemented; `t16` covers it.** One more thing had to be rewritten than the design
anticipated: `Service.startForeground` reaches `setServiceForeground` naming the component
the service thinks it is — which, because UNIQUE replaced the `ServiceInfo`, is the
*guest's*. `ActivityManagerService` finds the `ServiceRecord` by token and then checks the
name against it, and that record's name is the stub's. An unrewritten name is rejected with
"Service not registered", from inside `startForeground`, seconds before the platform kills
the app for not having called it. The notification travels here too, so it needs the same
channel namespacing and icon flattening as §6.7.

The three ints on that call — `(id, flags, foregroundServiceType)` — are the §6.7.1 hazard
again, so the shim pins the *shape* (a `ComponentName`, a `Notification`, at least three
ints) and declines to bind when it does not hold. A release that changes the shape gets the
platform's own behaviour, which is a visible failure, rather than a service started with a
mangled type.

### 6.3 Broadcast receivers

**Implemented for a running guest; `t08` covers it.** A manifest receiver cannot work the
way an activity or a service does: the system will not deliver to a component of a package
it never installed, and unlike a service there is no long-lived object to stand in for —
a receiver is instantiated, run and discarded. So UNIQUE registers a *dynamic* receiver of
its own for each action the guest declares, and on delivery instantiates the guest's
`BroadcastReceiver` from the guest's class loader and calls `onReceive` with the guest's
`Context`. A fresh instance per delivery, which is what the platform does.

Two limits remain, both real and neither hidden:

- **Android 8+ implicit-broadcast restrictions** apply to UNIQUE's registration just as
  they would to the guest's. Actions that cannot be registered are reported individually.
- **Android 14's `IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS`** (compat change
  229362273, on from `targetSdk` 34) matches an implicit intent — one with neither a
  component nor a package — only against *exported* filters. UNIQUE's own registrations
  are `RECEIVER_NOT_EXPORTED` (§6.3.1), so a **sender inside UNIQUE must scope its
  intent** with `setPackage`, and a system broadcast arriving implicitly does not reach a
  guest receiver.

Ordered broadcasts, sticky broadcasts and `BroadcastReceiver.PendingResult` (`goAsync`)
are not proxied yet.

#### 6.3.1 Waking a guest that is not running

**Implemented; `t25` covers it.** A dynamic registration lives only as long as the process
that made it, and a manifest receiver's whole point is usually to *wake* a process that is
not running. Earlier drafts of this document said that needed a `:server` process. It did
not: what it needed was *a* long-lived process holding the registration, and UNIQUE's own
main process is one. `:server` was only ever shorthand for that.

`VirtualBroadcastRouter` runs there. At import — and again whenever the instance set
changes — it reads every imported manifest and registers one dynamic receiver per declared
action, `RECEIVER_NOT_EXPORTED`, on behalf of every instance that declared it. On delivery
it finds the matching routes and, for each, brings that instance's process up carrying the
intent.

Three details are load-bearing.

**It starts a service, not an activity.** A broadcast must not put a window on screen, and
starting a service is the least intrusive way to make a process exist — a process cannot
be started by wishing for one.

**The stub that runs is reserved, not borrowed.** `VirtualServiceRouter` keeps the last
stub of each slot back (`COLD_BROADCAST_STUB_INDEX`) and never hands it out to a guest
service. That is what makes the rule in `LaunchInterceptor` sound: a `CREATE_SERVICE` for
that index is a cold broadcast, so the interceptor leaves the `ServiceInfo` alone and
`StubServiceBase` itself runs — the one path where reaching that class is intended rather
than a failure. An "index 5 is probably free" agreement would have held only until a guest
ran six services.

**The slot is leased at delivery, not at registration.** A registration made at import time
outlives every process it could name; a slot is a lease on one of the host's `:vappN`
processes, handed out by `VirtualLauncher` when something actually starts. Baking one into
a registration would name a process that, by the time a broadcast arrives, may belong to a
different app. `ProcessPool.acquire` is idempotent per occupant, so a guest that is already
running keeps the slot it has and the broadcast joins the live process instead of racing a
second one into a different slot.

Once the process exists the ordinary graft runs, and the guest's receiver is instantiated
from the guest's class loader with the guest's own `Context` — the same object a live
delivery would get. Nothing in the receiver's code can tell the two apart.

**And the delivery is tracked until the receiver acknowledges it.** Starting a process is
not the same as delivering to one. A cold wake is the longest thing UNIQUE asks a device to
do — fork, load UNIQUE's own code, install the interception layer, graft the guest, *then*
run the receiver — and all of it happens inside what ActivityManager considers a background
service start. On a loaded device that budget is exceeded and the process is killed:

```
ANR in com.unique:vapp0
Killing 12252:com.unique:vapp0 (adj 500): bg anr
```

The ANR is a property of a slow device. What was UNIQUE's own defect is what happened next:
the stub is started `START_NOT_STICKY`, nothing brought it back, and the broadcast was
**lost silently** — no error, no record, and a guest that has no way to know the broadcast
it exists to receive ever happened.

So each cold start is recorded in UNIQUE's main process and re-tried until the `:vappN`
reports the receiver actually ran, over the same router provider §6.4.0 uses. Three
attempts, ninety seconds apart: ninety because a cold graft is measured at forty (§17.1)
and a retry that fires while the first attempt is still working would double the load on
the device least able to carry it, which is how the original failure was produced. Three
because a delivery that has failed three times is failing for a reason a fourth will not
fix, and an unbounded retry against a process that cannot start is a battery drain that
looks like a hang. Every outcome is recorded: `COLD_BROADCAST_RETRY`,
`COLD_BROADCAST_ACKNOWLEDGED`, `COLD_BROADCAST_GIVEN_UP`.

The acknowledgement is sent only when the receiver *ran*. A wake that got as far as
grafting and then failed is exactly the case worth another attempt, and acknowledging it
would record a delivery that never happened. In the other direction the acknowledgement is
best-effort: losing it costs one duplicate delivery ninety seconds later, which a receiver
must already tolerate, because the platform does not promise exactly-once either.

This matters more on hardware than on the verification emulator. Aggressive OEM background
management produces the same kill for its own reasons.

What this still cannot do: UNIQUE's main process must be alive to hold the registration. A
broadcast arriving while the whole app is dead and unstarted is missed. Closing that needs
the registrations to be *static* in the host manifest, which means knowing the actions at
build time rather than at import time.

### 6.4 Content providers

**Implemented for same-process access; `t09` covers it.**
`ActivityManagerService` resolves an authority against installed packages, so a query for
a guest's authority comes back null and the caller sees
`IllegalArgumentException: Unknown URL`. Since the provider's code lives in the virtual
process anyway, `VirtualProviderRegistry` instantiates each declared provider from the
guest's class loader at bootstrap — *before* receivers, matching the platform, which
creates a process's providers before any other component — runs `attachInfo` (which runs
the provider's own `onCreate`), keeps the `IContentProvider` binder, and the
`getContentProvider` shim answers the acquisition itself with a
`ContentProviderHolder` carrying `noReleaseNeeded`, because UNIQUE owns the provider's
lifetime and not AMS.

Anything that is *not* a guest authority still goes to the real service and gets the
host's `AttributionSource` substituted (§6.1.2).

Only publication is process-scoped: a provider is published in the process its manifest
puts it in, and nowhere else. `android:process` on a `<provider>` is ordinary, and
publishing every provider in every process would put two live instances of the same class,
each holding its own handle to the same database, in two processes at once.

`FileProvider`, `DocumentsProvider` and URI permission grants are handled in §7.4.

#### 6.4.0 Reaching a provider from another process

**Implemented; `t26` and `t27` cover it.** A provider is normally a *cross-process*
interface — an app whose sync provider lives in `:remote` is an ordinary shape, and
UNIQUE's own UI reading a guest's `FileProvider` is the thing file sharing is made of.
Neither works if an authority only resolves inside the process that publishes it.

The obstacle is not routing but the platform's own check. `ContentProvider.Transport`
compares an incoming URI's authority against the provider's before it dispatches:

```
SecurityException: The authority com.example.app.files does not match the one of the
    contentProvider: com.unique.vprovider.2
```

So a stub provider cannot simply proxy for a guest: it has to *be* something the platform
agrees answers for that authority. The authorities are not known until an app is imported,
and two clones declare the same one, so they cannot be in the host manifest either.

The resolution has three moving parts and one trick.

1. **A router provider in UNIQUE's main process**, `<applicationId>.router`. Only that
   process holds the instance table and the process pool, so only it can answer "which
   slot serves `(vuid, authority)`" — and only it can lease one. A `ContentProvider` in
   the host manifest is the cheapest thing the platform will carry between two processes
   of one app; no AIDL had to be invented for a single question. The key is
   `(vuid, authority)`, never the authority alone: two clones of an app declare the same
   authority, and picking the first would hand a caller another instance's database.

2. **The slot's process is started out of band first, then `call()`ed.**
   `ActivityManagerService` gives a process it cold-starts *for a provider* ten seconds to
   publish, and kills it otherwise:

   ```
   Killing 4786:com.unique:vapp2 (adj 0): timeout publishing content providers
   ```

   A cold `:vappN` does not reliably make that. Before `handleBindApplication` returns it
   has to load UNIQUE's code, install the interception layer and graft the guest — work an
   ordinary app does not do, and work that happens *before* providers are published. The
   process was killed and restarted in a loop and every acquisition failed. So UNIQUE
   starts the reserved stub service first (the same one §6.3.1 uses, for the same reason),
   which takes that budget off the critical path: by the time the acquisition arrives the
   process is running and publishing is immediate.

   The graft runs on the main thread — a guest's `Application.onCreate` must, because apps
   create `Handler`s in it — and there is at most **one in flight per process**. Both of
   those were learned the hard way. `warm` posts and returns immediately, because it runs
   inside `Service.onStartCommand` and a service whose main thread does not return is
   ANR'd (`bg anr`). And a second caller waits on the graft already running rather than
   posting another, because a second post queues *behind* the first on the same thread —
   its timer counting down while the work it waits for has not started. That is what
   `BOOTSTRAP_TIMED_OUT … did not graft within 20s` actually meant: not a slow graft, but a
   timer racing a queue.

   Starting the process is asynchronous, so the caller has to know when to stop waiting,
   and **the slot says so itself**. Every graft — for an Activity, a Service, a receiver or
   a provider — ends with the `:vappN` calling `unique.slotReady` on the router with its
   slot, its vuid and its pid; a caller polls `unique.slotStatus` for the same pair. The
   router checks the recorded pid with `kill(pid, 0)` before answering yes, so a slot that
   grafted and then died reads as not ready rather than as ready — `ESRCH` is the one errno
   that means gone, and `EPERM` means a process is there.

   Announced from `AppBootstrap`, where every graft passes, rather than from the provider
   path that first needed it. When only the provider path announced, a caller acquiring
   from a guest that was already running — grafted for its Activity, minutes earlier —
   waited out its entire budget for a message nothing was going to send:

   ```
   PROVIDER_READY_NOT_SEEN slot=0 vuid=0 waitedMillis=45005
   PROVIDER_BIND_READY … slot=0                       (2.5 seconds later)
   ```

   And a warm request that finds the process *already bound* announces again rather than
   returning silently: a caller only warms a slot the router did not report ready, so
   arriving there at all means the two disagree — the announcement was lost, or UNIQUE's
   own process restarted and its table went with it.

   What the caller must **not** do is decide for itself, from
   `ActivityManager.getRunningAppProcesses`, that the process is already there. That was
   tried, as a cheap way to avoid a redundant service start, and it is how a killed
   provider process failed to come back at all:

   ```
   PROVIDER_SLOT_READY_STALE slot=2 pid=6236          (the router noticed the death)
   PROVIDER_READY_NOT_SEEN slot=2 vuid=0 waitedMillis=45078
   Killing 6989:com.unique:vapp2 (adj 0): timeout publishing content providers
   ```

   The list still named a process that was gone, so nothing was started, and the wait timed
   out against a process that was never coming — leaving ActivityManager to cold-start it
   for the provider and kill it three times over. Starting a process that is already
   running is harmless; believing a process list that a process has already left is not.

   The first version of this waited on `ActivityManager.getRunningAppProcesses` instead,
   which answers a weaker question and answers it unreliably: it has omitted a process
   that was already serving Binder calls and listed one `system_server` had buried minutes
   earlier. What that produced was

   ```
   PROVIDER_WARM_NOT_SEEN slot=2 waitedMillis=8000
   Killing 5216:com.unique:vapp2 (adj 0): timeout publishing content providers
   ```

   — a warm-up that had in fact started the process, a caller that stopped believing it
   after eight seconds, and ActivityManager cold-starting the very same process *for the
   provider*, back inside the ten-second budget this step exists to avoid. The budget for
   the new wait is forty-five seconds because a cold graft is measured at forty (§17.1); a
   timeout tighter than the work is a test of the machine.

3. **The trick: the stub's authority set is widened at runtime.**
   `ContentProvider.setAuthorities` has existed since API 21 and is what `attachInfo`
   itself calls. Once the guest's providers are published, the stub is told it answers to
   its own authority *plus* theirs. After that the platform's own dispatch does everything
   else, and what the caller holds is a genuine Binder to a genuine `ContentProvider` in
   the guest's process: cursors, `openFile` and `call` behave exactly as they would for an
   installed app, because nothing is re-marshalled by UNIQUE.

   `setAuthorities` *replaces* the set rather than adding to it, so the stub's own
   authority has to be included every time. A call that lists only the guest's authorities
   silently revokes the stub's, and the next bind — which is how every acquisition
   arrives — is refused by the platform naming `com.unique.vprovider.N`. The first
   acquisition then works and the second does not, which is a shape of bug that looks like
   anything except what it is.

On the caller's side the `getContentProvider` shim rewrites the authority argument to the
stub's, and afterwards adds the guest authority back onto `ContentProviderHolder.info` so
`ActivityThread` caches the holder under the name the caller asked for. Without that every
acquisition repeats the whole resolve-and-bind round trip — correct, and slow for no
visible reason. UNIQUE's own authorities are never redirected; that is what ends the
recursion.

**Provider stability is left exactly as the guest asked for it**, and the reasoning is
worth keeping because the obvious change here is wrong twice over.

Once cross-process providers existed, `ActivityManagerService` started doing this:

```
Killing 25752:com.unique:vapp0 (adj 0): depends on provider
    com.unique/.stub.ProviderStub_p2 in dying proc com.unique:vapp2 (adj 0)
```

which reads as one virtual app killed because another died — a §3 violation. It is not.
The two processes are one instance's main process and the *same instance's* `:alt`, and an
installed app with a provider in `android:process=":alt"` gets precisely this from the
platform. It is the contract the guest asked for by holding a stable reference, and
reproducing it is fidelity rather than a bug.

Forcing the acquisition unstable to avoid it was tried, and is worse than the thing it
avoided. `stable` is not only a message to ActivityManager: `ActivityThread` keeps its own
stable and unstable reference counts on the client side and hands them back on release.
Rewriting the flag in flight leaves the two ledgers disagreeing, ActivityManager drops a
connection the client still believes it holds, and the next query returns no cursor at all.
`t27` and `t32` both went red on it, and the symptom — an acquisition that works once and
then silently stops — pointed nowhere near the flag.

What §3 actually promises is narrower, and holds without touching anything: one *instance*
cannot kill another, and a guest cannot kill UNIQUE. Nothing a guest writes can name
another instance's authority, and UNIQUE's own process reaches guest providers through
`acquireUnstableContentProviderClient`. `t32` kills a provider's process and watches both.

#### 6.4.1 Sharing a file out of a virtual app

**Implemented; `t34` covers it.** A guest that shares a photo hands out
`content://com.example.app.fileprovider/…`, and nothing outside UNIQUE can do anything
with it — AMS resolves authorities against installed packages. The receiving app gets a
URI it cannot open, which is the failure mode of every share button in every virtualized
app that has not solved this.

Outgoing intents are therefore rewritten, at the last point UNIQUE sees them:

```
content://com.example.app.fileprovider/images/a.jpg
  →  content://com.unique.shared/0/com.example.app.fileprovider/images/a.jpg
```

`UniqueSharedProvider` owns that authority for real, and forwards every call back to the
guest's own provider over the path §6.4.0 built. The receiver sees an ordinary provider of
an installed app and gets an ordinary temporary grant — UNIQUE borrows the platform's
permission model rather than inventing one.

Three choices worth stating.

**Forwarded, not copied.** A copy would double the storage, go stale, and quietly turn a
revoked grant into a permanent one. Forwarding leaves the guest's provider in charge of
what it hands out, including refusing.

**Only the guest's *own* authorities are rewritten.** A guest passing on a `content://`
URI it received from elsewhere — a photo picker, another app — is passing on a grant it
was given, and rewriting that would break it.

**`exported="false"` with `grantUriPermissions="true"`.** Nothing may open the shared
authority by knowing its name; only by being handed a URI.

Not covered: an explicit `grantUriPermission(package, uri, flags)` call from a guest, which
names a package the platform must resolve, and the inbound direction — a host app returning
a URI to a guest. The latter should already work, because the grant is to UNIQUE's uid and
that is the uid the guest runs as, but it has not been exercised.

**What is not covered.** A slot is leased per (instance, manifest process), so a guest
whose provider process has been reaped and whose slot has been handed to another instance
is refused with a diagnostic rather than grafted twice — `SLOT_ALREADY_BOUND`. And nothing
here grants URI permissions across the boundary; `grantUriPermission` between two virtual
processes is still §7.4's problem.

### 6.4.1 Stub intents need an identity of their own

Every activity of a virtual process shares a pool of eight stub classes, so to the task
system two different guest activities are the **same component**. That is not cosmetic.
`ActivityStarter` compares an incoming intent against the tasks it already has using
`Intent.filterEquals`; with `FLAG_ACTIVITY_NEW_TASK` it finds a match and delivers
`onNewIntent` to whatever is already on top:

```
START u0 {flg=0x10000000 cmp=com.unique/.stub.ActivityStub_p0_m0_a0} … result code=3
```

`START_DELIVERED_TO_TOP` — the requested screen never starts. The same collision makes two
`PendingIntent`s for two different guest screens compare equal, so `FLAG_UPDATE_CURRENT`
silently overwrites one with the other, which is precisely how a notification opens the
wrong screen.

`Intent.setIdentifier` exists for this and has been part of `filterEquals` since API 29.
UNIQUE stamps every stub activity intent with `u<vuid>/<guest component>`: it gives the
intent an identity without touching action, data or categories, none of which are UNIQUE's
to invent, and two stub intents for the *same* guest component still compare equal, so
genuine `singleTop` and deliver-to-top behaviour is preserved. The guest's own identifier
is parked in an extra and restored when the intent is unwrapped. A pleasant side effect is
that `ActivityTaskManager`'s own logs then name the guest's activity:

```
START u0 {id=u0/com.unique.probe.ProbeSecondActivity cmp=…ActivityStub_p0_m0_a0} … result code=0
```

### 6.5 Jobs, alarms, clipboard

- **JobScheduler** — **implemented; `t18` covers it.** Virtual job ids are namespaced
  (`vuid << 20 | jobId`) onto host stub `JobService`s. Three things make it different from
  every other component path:

  - **It is the first path where the *system* starts the guest.** A job fires long after
    the process that scheduled it has gone, so there is nothing to intercept ahead of
    time. A routing record — instance, package, class, slot — is written to
    `runtime/jobs/<hostJobId>.properties` at schedule time and read back by whichever
    `:vappN` the system starts, which bootstraps from it. A record that is gone is
    reported (`JOB_RECORD_MISSING`); it never silently does nothing.
  - **`JobInfo` is copied through a `Parcel`, not rebuilt.** Its fields are final and it
    has no public copy constructor, and rebuilding one through its `Builder` means
    enumerating every constraint the platform has ever had — a list that goes stale
    exactly as fast as the AIDL signatures `MethodShim` exists to avoid pinning. A parcel
    round-trip copies whatever the release actually carries, including fields UNIQUE has
    never heard of. The copy is what keeps the *guest's* object untouched: it scheduled
    id 7 and must keep seeing id 7.
  - **The guest's service borrows the stub's engine.** `jobFinished` goes through a
    private `JobServiceEngine` the platform installs when it *binds* a service. The
    guest's instance is never bound — the system bound the stub — so its engine is null
    and `jobFinished` would throw inside the app's own code. It is handed the stub's.

  Everything read back (`getPendingJob`, `getAllPendingJobs`) is rewritten in the other
  direction, and `getAllPendingJobs` needs the `ParceledListSlice` unwrap of §6.7.1.
  UNIQUE's own jobs are filtered out of that list: an app asking for its pending jobs must
  not be shown the host's.

  One diagnostic distinction worth keeping: `onStartJob` returning false is the *ordinary*
  case — work finished synchronously — and is reported differently from "UNIQUE could not
  reach the guest". They are the same value to the platform and must not be the same event
  in the log.
- **AlarmManager** — **implemented; `t19` covers the inexact path.** The whole
  interception is the identity rewrite: the alarm's `PendingIntent` was already pointed at
  a stub when the guest built it (§6.4.1), so there is nothing left to route.

  Exact alarms are the interesting part, and the design decision here has been **reversed**
  from the original plan. UNIQUE declares `SCHEDULE_EXACT_ALARM` — user-grantable, and the
  permission an ordinary app would hold — and deliberately not `USE_EXACT_ALARM`, which is
  reserved for alarm-clock and calendar apps and needs a Play policy declaration. When the
  host does not hold it, the guest gets the platform's own `SecurityException`, which is
  exactly what it would get on a device where its own permission was revoked. The earlier
  plan to **downgrade to inexact and log it** is wrong: an alarm that fires up to an hour
  late looks like the app being broken, and the app has no way to find out. UNIQUE reports
  the capability once at bootstrap (`ALARM_EXACT_AVAILABLE` / `ALARM_EXACT_UNAVAILABLE`)
  rather than per alarm, because the answer is a property of the host's permission state.

  Not handled: `setAlarmClock` shows in the status bar as UNIQUE's alarm rather than the
  guest's.

- **Clipboard** — **implemented; `t19` covers writing.** `IClipboard` gets the same
  identity rewrite. Android 12+ clipboard access toasts name UNIQUE, not the virtual app —
  documented, not hidden.

  Reading is the platform's restriction, not UNIQUE's: since Android 10 `getPrimaryClip`
  answers only for the app holding input focus. Under virtualization the focused window is
  the stub's, which is UNIQUE's, and the read is checked against UNIQUE too — the two
  agree, so a focused guest can read. A headless emulator never focuses an activity at
  all, so that case is `NOT_TESTED` rather than claimed.

### 6.6 Runtime permissions

**Implemented; `t12` covers the check, the request and the rationale.** The rule in one
line: **the guest sees `host grant AND instance grant`.** UNIQUE narrows, never widens. A
stored grant for a permission the host lacks stays denied and is reported as blocked by the
host, because a local "granted" would be a lie the platform refuses to honour at the first
real call — and would show up as a `SecurityException` deep inside the app rather than as a
permission prompt.

Two consequences that are easy to get wrong:

- **UNIQUE must declare every runtime permission it is willing to forward.** A guest can
  never hold what the host has not asked the user for, so `AndroidManifest.template.xml`
  carries the union of `PermissionGroup`'s permissions, the same shape as the foreground
  service type superset.
- **The dialog says "UNIQUE".** That is correct, not a defect: the kernel checks the host's
  uid, so the host is the package the grant must land on. UNIQUE additionally records the
  grant against the instance, which is what lets two instances of one app differ.

#### 6.6.1 Which argument is the permission

This is the one place where argument identification is genuinely hard, because the platform
is not self-consistent: `IPackageManager.checkPermission` takes `(permission, package,
userId)` while `IPermissionManager.checkPermission` took `(package, permission, …)` — the
same two strings in the opposite order. Position is therefore unusable and so is "the first
String".

The permission is identified **semantically**: among the string arguments, the one the
guest declares in its own manifest, and only when there is exactly one such argument.
Anything else falls through to the platform, which is the safe direction — an unrecognised
query is answered exactly as it would have been without UNIQUE.

Asking the host whether it holds a permission goes through `Context.checkSelfPermission`,
which reaches `ActivityManagerService` through this very shim, so a thread-local guard
makes that one call fall through. Without it the class calls itself forever.

#### 6.6.2 Where the platform actually answers

Settled by measurement rather than assumption, after a hook that bound nothing:

| Question | Interface on API 34 |
|---|---|
| `Context.checkSelfPermission` | `IActivityManager.checkPermission` |
| `PackageManager.checkPermission` | `IPackageManager.checkPermission` / `checkUidPermission` |
| `shouldShowRequestPermissionRationale` | `IPermissionManager` |

`IPermissionManager` declares **no** `checkPermission` at all on API 34; its members are
grant/revoke, flags, allowlist and one-time sessions. `SystemServiceHook` now prints an
interface's whole method list under `HOOK_MATCHED_NOTHING` when a hook matches nothing,
which is how that was established — and is the general answer to "the hook installed but
nothing happens".

#### 6.6.3 Reading the request result

`Activity.requestPermissions` is a `startActivityForResult` at heart, and the answer comes
back as an ordinary activity result carrying two parallel arrays. `LaunchInterceptor`
already sees every `ClientTransaction`, so it reads `ResultInfo`'s `Intent` — located by
type, like everything else — and records the outcome against the instance. It is
**observed, never rewritten**: the result still reaches the guest exactly as the platform
sent it, so `onRequestPermissionsResult` sees the truth.

#### 6.6.4 A shim only sees calls that cross the process boundary

The permission layer worked once and then quietly stopped, and the reason generalises to
every interception in this project.

Since Android 12 both permission routes answer from a `PropertyInvalidatedCache` living in
the **app's own process**. The first `checkSelfPermission` for a given permission goes
through Binder — through UNIQUE's shim — and every later one is answered from that cache
with no Binder call at all. The cache is invalidated when the *platform's* permission state
changes; UNIQUE's per-instance state is invisible to it. So a guest denied before it asked
stayed denied afterwards, whatever the instance recorded:

```
cameraBefore=DENIED   result.CAMERA=GRANTED   cameraAfter=GRANTED
cameraViaPm=DENIED    …                       cameraViaPmAfter=DENIED
```

— and no `PERMISSION_CHECK` event for either "after", because the shim was never reached.
That absence is what identified it.

UNIQUE calls the platform's own per-process cache disables at bind. The *classes* that
hold them are named — `PermissionManager`, `ActivityManager`, `PackageManager`,
`ApplicationPackageManager` — and every static no-argument `disable…Cache()` on each is
discovered and called, because naming the methods is how the list went stale:
`disablePackageManagerCache` and `disableAppOpCache` were on it and on no release this has
run on, while `ApplicationPackageManager`'s caches, which nothing knew about, were
answering `getComponentEnabledSetting` and `getPackageInfo` from the process. Calling one
UNIQUE has not heard of is safe by construction — disabling a cache costs a Binder call
and cannot change an answer. What was found is reported:

```
PERMISSION_CACHE_DISABLED disabled=disablePackageNamePermissionCache,disablePermissionCache,
  disableApplicationInfoCache,disablePackageInfoCache,disableGetPackagesForUidCache
```

If none can be disabled, that is an error-level event rather than a silent regression to
stale answers. The cost is one Binder call per check — what an unhooked app pays on a cache
miss anyway.

#### 6.6.5 Where the decision is kept

Under `runtime/permissions/<vuid>/<package>.properties`, inside UNIQUE's app-private
storage — **not** in the guest's data directory. A guest that can rewrite its own grants
has none, and anything resembling a security decision belongs where the app being governed
cannot reach it. The file holds only decisions UNIQUE recorded and is still intersected
with the host's live grant on every check, so a stale `GRANTED` can never outlive the user
revoking it from UNIQUE.

It is restored at bootstrap, before the guest's `Application` exists. Without that a guest
is asked for every permission again on every cold start, which users read as the app being
broken.

#### 6.6.6 App ops

`AppOpsManager.checkPackage(uid, packageName)` throws when the name does not belong to the
uid, and the framework calls it on the way into a great many APIs — camera, microphone,
location, clipboard, notifications. A guest reaches it with its virtual package name, which
no uid on the device owns, so an unhandled app-op layer surfaces as a `SecurityException`
from an API with nothing obviously to do with app ops.

The rewrite here is broader than the one on `IActivityManager`, and deliberately so. There
the rule is limited to calls carrying an `IApplicationThread`, because `forceStopPackage`
takes a package name as *data*. `IAppOpsService` has no equivalent reachable by an ordinary
app: everything that acts on a different package — `setMode`, `setUidMode`,
`resetAllModes`, `setAudioRestriction` — is gated behind `MANAGE_APP_OPS_MODES`, which
UNIQUE does not hold and must never hold. So the general rule applies: **the virtual
package name is not a name the platform knows, so wherever it appears in an outbound call
it can only mean "me".** The privileged setters are excluded anyway, as defence in depth —
the safety of this must not depend on UNIQUE continuing not to hold that permission.

Ops are *attributed* to UNIQUE, because the uid is UNIQUE's. That is correct and permanent:
an op record is a kernel-level fact about which process touched the camera. Per-instance
denial happens a layer up, at the permission check, which is what apps actually consult.

### 6.7 Notifications

**Implemented; `t15` covers posting, channels and two instances.** Four things have to be
true for a virtual app's notification to appear and behave, and each fails differently:

1. **Identity.** `enqueueNotificationWithTag` takes the posting package and `system_server`
   checks it against the uid, so the virtual name is rejected outright and nothing appears.
2. **Channel.** Two instances of one app declare the same channel id, so they would share
   the user's sound, importance and Do Not Disturb settings under a single entry in
   Settings — the opposite of what a second instance is for.
3. **Notification id.** Apps pick small constants and both instances pick the same one, so
   instance 2 posting silently replaces instance 1's. Ids are namespaced the way job ids
   are.
4. **Icon.** A guest's small icon is a resource in an APK the system has never installed.
   SystemUI resolves it against the *posting* package — UNIQUE — and finds nothing, or
   worse an unrelated drawable at the same numeric id. The virtual process can load it, so
   it is rendered there and travels as a bitmap.

The content `PendingIntent` needs nothing here: it was routed onto a stub when the guest
built it (§6.4.1), including the identifier that keeps two instances' taps apart.

#### 6.7.1 Two traps worth keeping

Both cost a device run, and both generalise beyond notifications.

**An `Int` argument carries no evidence about itself.** Every package-name rewrite in
UNIQUE is guarded by value — `matching = { it == virtualPackage }` — so an unrelated string
is never touched. `rewriteAll<Int>` has no such guard, and on
`enqueueNotificationWithTag(pkg, opPkg, tag, id, notification, userId)` it namespaced the
*user id* as well:

```
SecurityException: enqueueNotification from com.unique asks to run as user 1048576
```

1048576 is `1 shl 20` — the namespacing applied to user 0. These AIDL methods all end with
`userId`, so a method carrying a single int has no notification id at all
(`cancelAllNotifications(pkg, userId)` is the trap); an id exists only when there are two,
and it is the first.

**A type-based rule cannot see through a container.**
`NotificationManager.createNotificationChannel` hands the channel to `system_server` inside
a `ParceledListSlice`, so a rule declared on `List<*>` never fires. The channel reached the
platform with the guest's own id while the notification pointed at the namespaced one, and
`NotificationManagerService` **silently dropped** the notification — every UNIQUE
diagnostic said it had been adapted and posted. The slice is unwrapped through `getList()`
and rebuilt.

**Known divergence:** `getNotificationChannel` returns the namespaced id rather than the
guest's own. Apps generally only test it for null.

---

## 7. Virtual storage

### 7.1 Layout

```
<host files>/virtual/
├── apk/<pkg>/<versionCode>/        base.apk, split_*.apk   (read-only, shared)
│   └── lib/arm64-v8a/*.so          extracted, mode 0555 (see §8.2)
├── users/<vuid>/
│   ├── data/<pkg>/                 → app dataDir
│   │   ├── files/ cache/ code_cache/ databases/ shared_prefs/ no_backup/
│   ├── media/<pkg>/                → Android/media
│   └── sdcard/                     → virtual external storage root
│       └── Android/{data,obb}/<pkg>/
├── shared/                         cross-instance imports (user-initiated only)
└── runtime/                        pids, sockets, profile cache, hook state
```

APKs are stored **once per (package, versionCode)** and shared read-only across instances;
only `users/<vuid>/` is per-instance. Three Telegram instances cost one APK plus three data
trees, exactly as the brief requires.

### 7.2 The path contract

Every one of these must be internally consistent, because apps cross-check them:

| API | Value |
|---|---|
| `ApplicationInfo.sourceDir` / `publicSourceDir` | `…/apk/<pkg>/<vc>/base.apk` |
| `ApplicationInfo.splitSourceDirs` | selected splits, in manifest order |
| `ApplicationInfo.nativeLibraryDir` | `…/apk/<pkg>/<vc>/lib/arm64-v8a` |
| `ApplicationInfo.dataDir` / `deviceProtectedDataDir` | `…/users/<vuid>/data/<pkg>` |
| `Context.getFilesDir()` | `<dataDir>/files` |
| `Context.getCacheDir()` | `<dataDir>/cache` |
| `Context.getCodeCacheDir()` | `<dataDir>/code_cache` |
| `Context.getNoBackupFilesDir()` | `<dataDir>/no_backup` |
| `Context.getDatabasePath(n)` | `<dataDir>/databases/n` |
| `Context.getExternalFilesDirs()` | `…/users/<vuid>/sdcard/Android/data/<pkg>/files` |
| `Context.getObbDirs()` | `…/users/<vuid>/sdcard/Android/obb/<pkg>` |
| `Context.getPackageCodePath()` | == `sourceDir` |
| `Context.getPackageResourcePath()` | == `publicSourceDir` |
| `Environment.getExternalStorageDirectory()` | `…/users/<vuid>/sdcard` |

`core/common` owns this table as data (`VirtualPathModel`) and it is **unit-tested**: a
test asserts that every accessor above resolves under the right root and that no two
instances can collide. Path bugs in this class of software are the single largest source
of silent data corruption, so they are tested off-device.

### 7.3 Native redirection

Java-level path correctness is not enough: apps hard-code
`/data/data/<pkg>/…` and `/sdcard/Android/data/<pkg>/…`, and native code bypasses Java
entirely. `core/native` installs ShadowHook stubs on the libc path entry points (§4.3) and
applies an ordered longest-prefix rewrite table published by `:server` at process start.

Rules are **prefix rewrites with an explicit ordering**, not regexes, and the table is
frozen after `attachBaseContext` so the hot path is a sorted-array scan (typically ≤ 12
entries, one `strncmp` each). Redirection is per-process and carries the process's vuid, so
`:vapp3` can never see `:vapp4`'s tree.

### 7.4 Scoped storage, MediaStore, URIs

- **Virtual external storage** is a private directory presented as `/sdcard`. It is *not*
  the real shared volume: a virtual app writing "to the SD card" writes inside UNIQUE.
  This is correct for isolation and is what makes *Clear data* meaningful.
- **MediaStore** is *not* virtualized. Reads/writes go to the host `MediaStore` with the
  host's identity, subject to the host's `READ_MEDIA_*` grants. A virtual app querying
  `MediaStore` sees the device's real media. Rationale: virtualizing MediaStore would break
  every gallery/share flow users actually want, and the isolation benefit is small.
  **This is an explicit, documented deviation from full isolation.**
- **`FileProvider` / `DocumentsProvider` URIs**: outbound `content://` URIs from a virtual
  app are rewritten to a UNIQUE relay authority that re-serves the file to the external
  consumer with a real, revocable `FLAG_GRANT_READ_URI_PERMISSION`. Inbound URIs from the
  system picker (SAF) are opened by `:helper` (which holds the grant) and passed into the
  vapp as an already-open FD, because the grant cannot be transferred to a fake package.

---

## 8. Native / ARM64

### 8.1 ABI policy

ARM64-v8a only. On import, `VPM` selects `lib/arm64-v8a/**` and the
`config.arm64_v8a` split. An APK with no arm64 slice is refused at import time with a clear
reason, not at first launch.

**Implemented; `t17` covers loading.** The ABI is the *device's*, chosen from
`Build.SUPPORTED_ABIS` in the platform's own preference order and intersected with what
the APK carries — the same choice the platform makes for an installed app. The importer
extracts exactly that one `lib/<abi>/`, and the graft picks the same directory by reading
what is on disk, so the two cannot disagree; a disagreement would surface as
`UnsatisfiedLinkError` inside the guest with the directory looking perfectly correct in
the diagnostics.

Hard-coding `arm64-v8a` was wrong for a reason worth recording: it meant a device that is
not ARM64 extracted nothing at all, so the native path could not be exercised on any
emulator, and therefore not in any automated test. `x86_64` is supported for that reason
and because a Chromebook is one. 32-bit is not, and an APK carrying no ABI this device can
execute is **refused at import** rather than started and left to fail at `dlopen` —
UNIQUE has no CPU emulation and will not pretend otherwise.

What `t17` proves and does not: a guest loads a library out of an APK the system has never
installed, the library runs in the guest's own process, and JNI works in both directions.
It proves that **on x86_64**. The mechanism is architecture-independent — it is
`ApplicationInfo.nativeLibraryDir` and a class loader — but the ARM64 answer is a physical
device's to give.

### 8.2 W^X applies to the APK, not only to `.so` files

Android 10+ refuses to load code from a writable file for apps targeting API 29+. The
part that is easy to miss — and that cost a full debugging cycle here — is that this
covers the **APK itself**, not just extracted native libraries. ART rejects a writable dex
with:

```
java.lang.SecurityException: Writable dex file '…/base.apk' is not allowed.
```

which surfaces as a failure to *create the Application* and reads nothing like a
permissions problem. Every imported APK and every extracted `.so` is therefore made
read-only before first launch, and the installer verifies it rather than trusting
`File.setWritable`, which returns false on some filesystems without throwing.

Extraction is atomic (staging directory + `rename`) so a killed install cannot leave a
half-populated lib directory that a later `dlopen` would load from partially. Removing or
updating a package makes the tree writable again first.

`System.loadLibrary` resolves through the vapp's `ClassLoader`'s
`nativeLibraryPathElements`, which is constructed from `nativeLibraryDir` when the
`LoadedApk` is built — so no hook is needed for the common case. `dlopen` with an absolute
path is covered by the native redirect table.

### 8.3 Compatibility surface for engines

Unity/IL2CPP, Unreal, and similar engines exercise: `AAssetManager` from JNI (works — the
`AssetManager` is built from the real APK paths), `ANativeWindow` from a `SurfaceView`
(works — real Surface, §10), `/proc/self/maps` scanning (works, but *sees UNIQUE's
mappings* — recorded as a fingerprinting surface, not hidden), `getauxval`, `pthread`,
`sigaction` (our native hooks preserve prior handlers and chain), and `dlopen` of
engine-internal `.so`s by absolute path (covered by redirection).

### 8.4 16 KB page size

Android 15 introduced 16 KB page-size devices; Android 16 makes 16 KB alignment mandatory
for apps targeting API 36. Consequences taken care of:

- All UNIQUE native code builds with `-Wl,-z,max-page-size=16384,-z,common-page-size=16384`.
- Inline-hook trampoline allocation and every `mprotect` in `core/native` use
  `sysconf(_SC_PAGESIZE)`, never a hard-coded `4096`.
- **Imported apps** are checked at install time: a `.so` that is not 16 KB-aligned will fail
  to load on a 16 KB device. `VPM` records this and the app is flagged
  `NATIVE_ALIGNMENT_16K` in the compat DB with an accurate "will not run on this device"
  message rather than a crash at launch.

### 8.5 The honest performance caveat: no AOT

Since Android 10, apps cannot invoke `dex2oat`. An installed app is AOT-compiled by the
system; a virtual app's DEX is loaded from UNIQUE's data directory and is therefore
**JIT-only** (with the class-verification cache in `code_cache`). Steady-state throughput
converges on AOT after warm-up, but **cold start is measurably slower and this cannot be
fixed at app level**. It is measured, reported by the benchmark harness (§19), and stated
in the README — not hidden.

---

## 9. Google Compatibility Layer

This is a designed subsystem, not a feature bolted on at the end.

### 9.1 The actual problem, stated precisely

Google Play services identifies its caller by `Binder.getCallingUid()` →
`PackageManager.getPackagesForUid()` → package name → signing certificate. Inside UNIQUE
every virtual app shares the **host's** uid. So the host's real GMS sees
`com.unique`, never `org.telegram.messenger`.

Consequences, by flow:

| Flow | Identity GMS checks | Result with host GMS, unassisted |
|---|---|---|
| Legacy `GoogleSignIn` (`Auth.GOOGLE_SIGN_IN_API`) | (package, SHA-1) must match an **Android OAuth client** | `DEVELOPER_ERROR (10)` — the app's client is registered for its own package |
| Credential Manager / `GetGoogleIdOption` | ID-token audience is a **web/server client id**; package is recorded but not the audience | *Hypothesis:* should succeed, since the token is issued for the app's backend. **Unverified** — see below |
| `AccountManager.getAuthToken` (web client scopes) | Account + scope consent | Can succeed, consent shown as UNIQUE |
| Firebase Auth `GoogleAuthProvider` | Consumes an ID token obtained by one of the above | Follows whichever path produced the token |
| WebView / Custom Tabs OAuth (AppAuth, etc.) | Nothing GMS-specific; redirect URI only | Works, given WebView + deep-link return |
| FCM | (package, cert) → "app id" | Token is issued for the host's app id |
| Play Games, Play Billing, Play Integrity | package + cert + **attestation** | Not achievable (§9.7) |

### 9.2 Router

```
Virtual app
   │  GMS call / AccountManager / Credential Manager / WebView OAuth
   ▼
GoogleCompatRouter        ← policy from CompatibilityProfile(pkg) + device capability probe
   ├── MODE A  VirtualGmsProvider   in-space GMS
   ├── MODE B  HostBridgeProvider   host GMS, five typed bridges
   └── MODE C  PassthroughProvider  no GMS involvement
```

Bridge interfaces (each independently testable, each with its own diagnostics channel):

```kotlin
interface GoogleAuthBridge   { suspend fun signIn(req: SignInRequest): SignInResult }
interface CredentialBridge   { suspend fun getCredential(req: CredentialRequest): CredentialResult }
interface FirebaseBridge     { suspend fun exchange(idToken: String, cfg: FirebaseConfig): FirebaseResult }
interface PlayGamesBridge    { suspend fun authenticate(): PlayGamesResult }   // expected: Unsupported
interface OAuthBridge        { suspend fun authorize(req: OAuthRequest): OAuthResult }
```

Selection is **per (package, flow)**, resolved from the compat DB, with an evidence-based
default: Mode C when the app's manifest shows an AppAuth/Custom-Tabs redirect activity;
Mode B for Credential Manager and web-client `AccountManager` scopes; Mode A only when
in-space GMS is present and the flow requires package-bound identity.

### 9.3 MODE A — Virtual GMS

GMS, GSF and Play Store are **imported from the device's own installation** — UNIQUE never
bundles or redistributes Google binaries. Inside the virtual space, `getPackagesForUid()`
is answered by `VPM`, so GMS sees the virtual app's real package name and real signing
certificate, and `(package, SHA-1)`-bound OAuth clients validate correctly. This is the
only path that makes legacy `GoogleSignIn` work.

Known, documented limitations of Mode A: no `signature|privileged` permissions, no shared
`com.google.uid.shared` uid, DroidGuard/attestation unavailable, checkin may fail on some
devices, and a real RAM cost (~150–250 MB for the GMS process set). Mode A is therefore
**opt-in per space**, not the default.

> **The Credential Manager route is the layer's central hypothesis, not a finding.**
> The reasoning in the table above is sound but has not been run against a real Google
> Sign-In sample on a device. Everything downstream of it — the default routing for
> `CREDENTIAL_MANAGER` and `FIREBASE_AUTH`, and the claim that a host-bridged token is
> usable — depends on it. It is labelled `Unverified` in the router's own rationale
> string, and phase 5 either confirms it or the routing table changes.

### 9.4 MODE B — Host bridge

The virtual app's request is captured, translated, and executed by `:helper` against the
host's real GMS, then the result is translated back. The bridge is explicit about what the
app receives:

- ID tokens with a **web/server** audience pass through unchanged and are fully valid.
- Tokens whose audience would be an Android OAuth client are **refused** with a typed
  error and a diagnostic, rather than returned as something that will fail server-side in a
  way the developer cannot debug.

### 9.5 MODE C — Passthrough

WebView and Custom Tabs OAuth need no Google-specific machinery — only a correct WebView
(the host's, unmodified) and a working deep-link return. `IntentBridge` registers the
virtual app's custom schemes and App Links on host trampoline activities and routes the
callback back into the right instance. This is the **highest-reliability** Google path and
the router prefers it whenever the app supports it.

### 9.6 FCM

Design: the host registers with the virtual app's sender id; inbound messages arrive at
`:helper` and are routed by `(sender id, virtual instance)` to the virtual app's
`FirebaseMessagingService`. No permanent foreground service — delivery rides the host's
existing GMS connection.

**Status: experimental.** It depends on GMS accepting a sender-id registration under the
host's app id, which varies by GMS version. It ships behind a flag, with the test matrix
entry that proves or disproves it per device, and is reported as `Experimental` in the UI
until a device passes the test. It is **not** claimed as working.

### 9.6.1 Two prerequisites that are not Google-specific

Both were missing, both break Google flows, and both break plenty of non-Google apps the
same way — so they are stated here rather than inside a bridge that does not exist yet.

**An app must be able to read its own signature.** `PackageInfo.signatures` and
`signingInfo` were never populated, so a guest asking for its own certificate got null.
Apps check this constantly: integrity checks, licence checks, and every Google API whose
key is bound to a signing certificate. Null is not a missing nicety — the app concludes it
has been tampered with and refuses to start, which looks like UNIQUE breaking it.

UNIQUE asks the *real* `PackageManager` to parse the APK on disk
(`getPackageArchiveInfo`) rather than parsing it itself. Signature verification is exactly
the wrong place to have a second implementation: the platform's answer is by definition
the one the app would have got if it were installed, including which of v1/v2/v3 it
honours and how it treats rotation.

Both forms are filled, always. From API 28 the archive parser populates `signingInfo` and
leaves the deprecated `signatures` array null — but the real `PackageManager` still fills
`signatures` for a caller that asked with `GET_SIGNATURES`, and a great many apps and
libraries still ask that way. Populating only what the parser returned would leave them
reading null, which is the same failure by a different route.

**What the guest believes about the Google stack must be true.** A guest asking whether
`com.google.android.gms` is installed gets the host's real answer, because an app told
Play services is present when it is not fails later and somewhere less obvious. `t21`
asserts the guest's answer equals the host's rather than asserting a fixed value, so the
test stays meaningful on a device that *does* have it.

### 9.7 Not supported yet, and not yet investigated

Play Integrity, SafetyNet, DroidGuard, Play Billing and Play Games all bind to an attested,
installed package identity, and the expectation is that none of them works inside
app-level virtualization. That expectation has **not been tested**, so these are recorded
as `UNSUPPORTED_FOR_NOW` rather than as impossible.

The distinction is deliberate. `UNSUPPORTED` is reserved for a result measured on a
device with the reason recorded; `UNSUPPORTED_FOR_NOW` says only that nobody has done the
work. Writing "impossible" into the codebase on reasoning alone is how a project talks
itself out of something it never actually tried, and it is unfalsifiable in exactly the
place where a measurement is cheap.

UNIQUE does not attempt to *defeat* attestation, and that is a scope decision rather than
a claim about feasibility.

### 9.8 What is actually implemented, and what is only decided

The layer has two halves, and only one of them has a body.

**Implemented and device-checked:** `GoogleEnvironment` reads the *host* — is
`com.google.android.gms` present, is it enabled, is its version code high enough to be
usable, is `com.android.vending` there, is there a browser that can serve a Custom Tab.
Present-but-disabled and present-but-a-stub are distinguished from available, because both
report as installed and answer nothing, and reading either as available produces a failure
much later and much less clearly. The result is shown in Settings → Google, read afresh
each time the screen opens.

**Implemented and unit-tested:** `GoogleCompatRouter` turns those capabilities plus the
app's own manifest into a mode per flow, and records the choice as
`GOOGLE_ROUTE flow=… mode=… why=…`. What UNIQUE *intended* is therefore always visible,
even when nothing was served.

**Not implemented:** every bridge body. All five return `GoogleResult.Unsupported` naming
the flow. Nothing pretends, and the UI says so in the same panel that reports the device.

The reason this is where it stops is environmental and worth stating plainly rather than
implying it was an oversight: the verification emulator is `aosp_atd` and has no Google
stack at all, so every claim made against it would be speculation. A Google APIs image
exists in the SDK and was not installed because the container has ~4 GB of writable disk
against an 8.2 GB existing image, and losing the environment that produces twenty-eight
device-proven results would be a bad trade for a partial Google answer.
`docs/GOOGLE_DEVICE_TEST.md` is the procedure that would settle each flow, in the order
that makes a failure in one explain the next.

---

## 10. Graphics, input, audio, media

No compositor, no framebuffer, no streaming layer. A virtual app's `SurfaceView` is a real
`SurfaceView` in a real host window; `EGL`/`Vulkan` contexts are created directly against
the device driver; `Choreographer` is the real one. FPS parity with a normal install is
therefore the expected outcome, and the benchmark harness (§19) measures frame time to
confirm it rather than assuming it.

Handled explicitly: high-refresh-rate mode (`Surface.setFrameRate`), orientation and
`configChanges` forwarding, cutout mode, immersive/`WindowInsetsController`, Android 15/16
edge-to-edge defaults (the stub's theme must equal the virtual activity's theme *before*
`onCreate`, §6.1).

Input, audio (`AudioTrack`/`AudioRecord`/`MediaPlayer`), camera (`camera2`) and
`MediaCodec` are **not virtualized** — the virtual app talks to the real services under the
host's permissions, gated by UNIQUE's per-instance permission state (§17). Virtualizing
these would cost latency and gain nothing.


**Status: EGL and GLES ES 3.0 verified inside a virtual process (`t20`).** Nothing had to
be virtualized for it, which is the interesting part: the guest's Activity is a real
Activity in a real process with a real window, so EGL, GLES and the driver see exactly
what they would in an installed app. The graft is invisible below the framework.

`t20` is deliberately self-verifying. It clears to 0.25/0.5/0.75 and reads the pixel back
with `glReadPixels`, because a graphics stack that reports success and draws nothing is
the usual failure and an assertion on `GL_VENDOR` would not catch it. It renders to a
pbuffer rather than the window surface, so the result does not depend on a window becoming
visible — which on a headless emulator it never does.

What that does **not** cover, and is left `NOT_TESTED` rather than assumed: a real GPU
driver (this rasterises in software) and the window/`SurfaceView` path.

### 10.1 Vulkan

`t28` creates a Vulkan instance, enumerates physical devices, and creates a logical device
with a graphics queue, from inside a virtual process. It does more than ask whether the
library exists on purpose: a "Vulkan works" claim resting on `dlopen` succeeding is worth
nothing, because `libvulkan.so` is present on essentially every Android 10+ device
including ones whose loader finds no ICD and whose `vkEnumeratePhysicalDevices` returns
zero. Creating a device and fetching a queue is the first point at which a driver has
committed to anything, and the probe also checks the queue handle came back non-null,
because a driver returning `VK_SUCCESS` and nothing is a real failure mode.

`libvulkan` is `dlopen`ed rather than linked, for two reasons. A device with no Vulkan must
still load the probe and *report that*, instead of failing at load time with nothing to
say. And the `dlopen` is itself a test of UNIQUE: the loader has to find a **system**
library while the process runs under redirected IO, so a redirect scope that was too wide
would show up here as a driver unable to open its own `/system` files.

**On the emulator this test asserts almost nothing, and says so.** The headless
software-rendered emulator declares no `FEATURE_VULKAN_HARDWARE_VERSION`, so `t28` asserts
only that the probe ran inside the virtual process and that the library loaded and resolved
its symbols. On a device that *does* declare the feature it asserts the whole chain —
instance, physical device, logical device, queue — and compares the guest's answer against
what the host claims, because UNIQUE cannot conjure a GPU and a guest must see exactly what
the host has. Until such a run exists, Vulkan stays `NOT_TESTED` in
`docs/COMPATIBILITY.md`. A test that quietly passes on hardware that cannot run it is how
`NOT_TESTED` gets written down as `SUPPORTED`.

### 10.2 WebView

WebView is the shape most likely to expose a virtualization layer, and none of the reasons
are about rendering.

It is a **separate APK** loaded into the app's process as a shared library, so the class
loader and the native loader both have to cope with a host application whose own package
the system never installed. It keeps its data in a directory derived from the app's data
directory, which UNIQUE redirects — if that redirect is wrong, a guest writes its cookies
into UNIQUE's storage. And since Android P it refuses to run in two processes against one
data directory:

```
java.lang.RuntimeException: Using WebView from more than one process at once with the
    same data directory is not supported.
```

That last one is not hypothetical. The check is per *process*, only the process whose name
equals the package name gets the default (empty) suffix, and **none** of UNIQUE's `:vappN`
processes does. So the first virtual process to create a WebView would work and the second
would throw — inside the guest, naming UNIQUE's directory, pointing at nothing the app's
author could act on. `AppBootstrap` therefore calls
`WebView.setDataDirectorySuffix("vapp<slot>")` before any guest code runs at all: it must
happen before WebView is used in the process and can only be called once.

Keyed by slot rather than by instance because slot and process are one-to-one and the
constraint is about processes; two instances in two slots also get separate suffixes, which
is correct for a different reason.

`t30` creates a WebView in a virtual process and checks the data directory is the
*instance's*. Then it does something the rest of the suite does not need to: it asks
whether **this device** can render at all, by loading the same page in UNIQUE's own
process, un-virtualized. On the verification emulator Chromium's renderer dies on its own —

```
E/chromium: Renderer process crash detected (code 5)
F/chromium: [FATAL:crashpad_client_linux.cc(732)] Render process's crash wasn't handled
    by all associated webviews, triggering application crash
```

— and it does so outside virtualization too. Recording that against UNIQUE would be the one
thing a compatibility matrix must never do, so rendering is `NOT_TESTED` on this
environment and the comparison is what establishes it. On a device where the host *can*
render, the same test asserts the guest does too, including that JavaScript in the page
ran: a document created and never executed still reports "loaded".

The probe reports twice — once as soon as the WebView exists, again when the load ends —
because that Chromium fatal handler takes the embedding process with it. A probe that only
reported at the end turned a renderer crash into a timeout: an empty file, and no way to
tell whether the WebView was created, which provider it used, or which data directory it
chose. Those are the facts virtualization is responsible for, and they are all decided
before the first byte is parsed.

---

## 11. Device Profile

### 11.1 Single source of truth

```kotlin
data class DeviceProfile(
  val profileId: String,       // stable id of this profile
  val androidId: String,       // 16 hex chars
  val instanceId: UUID,        // per virtual instance
  val installId: UUID,         // per (instance, package)
  val locale: String?, val timeZone: String?,
  val screen: ScreenProfile?,  val build: BuildOverrides?,
  val generation: Int          // ++ on Regenerate
)
```

`DeviceProfileProvider` is the **only** producer of identity values. No adapter may
generate an identifier locally; that rule is enforced by an architecture test that fails the
build if `UUID.randomUUID()` or `SecureRandom` appears outside `core/vprofile`.

### 11.2 Consistency

The same logical value must be identical across every API that can expose it. `ANDROID_ID`
alone is reachable through `Settings.Secure.getString`, `Settings.Secure.ANDROID_ID` via
`ContentResolver.call`, and a raw `content://settings/secure` query — all three route to
the same provider value. A consistency test enumerates every registered adapter for a given
`ProfileField` and asserts equality.

### 11.3 Lifetime

Generated when a space/instance is created, persisted in the state DB, stable across
process death, reboot, and UNIQUE upgrade. **Regenerate** bumps `generation` and writes new
values; the change takes effect at the virtual app's next cold start (never mid-process,
which would produce exactly the incoherence the brief forbids).

### 11.4 Coverage

`ANDROID_ID`, `Build.*` (via `SystemProperties.get` + native `__system_property_get` +
static-field overwrite before app code runs), `Settings.Secure/System/Global`, GSF id,
`MediaDrm` unique id, `/sys/class/net/*/address`, `TelephonyManager` identifiers (which
already return null/placeholder on modern Android), and GMS advertising id (routed through
the Google layer). Every field is declared in a table with its adapters; adding a field
means adding a row, not a hook.

---

## 12. Persistence

Room (SQLite) in `:server`, single-writer. Tables: `spaces`, `packages`, `package_versions`,
`splits`, `instances`, `device_profiles`, `profile_fields`, `permissions`, `appops`,
`compat_profiles`, `notification_channels`, `google_state`, `settings`, `diagnostic_index`.
Versioned migrations from v1 with migration tests. No JSON blob is authoritative for
anything the engine reads at runtime; JSON appears only inside the backup/export format and
the diagnostics package.

---

## 13. Compatibility database

```kotlin
data class CompatibilityProfile(
  val packageName: String, val versionRange: LongRange?,
  val flags: Set<CompatFlag>,               // e.g. FORCE_VIRTUAL_GMS, NO_NATIVE_REDIRECT_PROC
  val googlePolicy: Map<GoogleFlow, GoogleMode>,
  val workarounds: List<Workaround>,
  val support: SupportLevel                 // SUPPORTED | PARTIAL | EXPERIMENTAL | UNSUPPORTED
)
```

Ships as a signed asset, overridable locally, extensible without an app update. The
architecture test that forbids `packageName ==` comparisons anywhere outside
`core/compat` is what keeps rule 45.6 enforceable rather than aspirational.

---

## 14. Diagnostics and crash handling

Structured events (`timestamp, vuid, package, channel, level, code, fields`) into per-channel
ring buffers (`LAUNCH, PROCESS, NATIVE, STORAGE, GOOGLE, WEBVIEW, NOTIFICATION, CRASH`).
Ring buffers rather than unbounded lists because a misbehaving app can emit thousands of
events a second, and the diagnostics subsystem must never be the reason a device runs out
of memory.

### 14.1 Getting the events out of the process they happened in

The buffers are *per process*, which is right and also the whole difficulty: the events
that matter most are in `:vappN`, which the user cannot see, and the events from a process
that crashed are gone with it. An export assembled only from UNIQUE's own buffers shows the
launch request and never the launch.

Two directions, because the two cases are genuinely different.

**Pull, on export.** UNIQUE asks each live `:vappN` for its buffers through that slot's
stub provider (§6.4.0 built the channel; this reuses it rather than inventing a second
one). Nothing is copied while an app is merely running, so the cost is paid once, by the
person who pressed *Export*. It also means an export taken *while the misbehaving app is
still up* is worth far more than one taken afterwards, and the UI says so by reporting how
many processes it reached.

**Push, on crash.** A crashing process has seconds to live and nobody will ask it anything
afterwards, so `CrashGuard` sends its crash records to UNIQUE's main process itself before
letting the process die. This is what makes rule 10 — *every crash leaves a diagnostic
trace* — a statement about what the user can read afterwards rather than about what was
briefly in memory.

### 14.2 The export package

`environment.txt`, `device-report.txt`, `test-checklist.txt`, `instances.txt`,
`unique.log`, `logcat.txt`, `crash.log`, one `vappN.log` per slot reached, any
`native-crash-*.txt`, and a `README.txt` that says what the archive does and does not
contain. It is written into UNIQUE's own cache directory — app-private storage, never
anywhere world-readable — and goes further only if the user sends it, through the ordinary
share sheet.

Three of those entries exist because of a constraint worth stating plainly: **a tester has
a phone and nothing else.** No `adb`, no root, no computer. Everything the engine knows
has to be collectable by the app itself, or it will not be collected.

- `device-report.txt` is what the *device* is, measured on the spot: ABIs, page size, the
  host's own Vulkan probed by creating a real instance, device and queue, the WebView
  provider, the Google stack. It is the other half of every result in the package — "the
  guest could not bring Vulkan up" means one thing on a phone with a working driver and
  nothing at all on one whose host device type is `cpu`.
- `test-checklist.txt` is the physical-device sequence with the tester's own verdicts and
  notes, recorded on the device as they go. Deliberately not a gate: nothing in
  `docs/COMPATIBILITY.md` moves because a checklist says so. A verdict is an observation,
  and it travels with the run so it can be read *next to* the machine's record rather than
  substituted for it.
- `logcat.txt` is the part UNIQUE's own events cannot see. ART refusing to verify a class,
  the linker failing to map a library on a 16 KB-page device, ActivityManager's kill
  reason, the stack trace of an uncaught exception — all of it has needed `adb` until now.
  `logd` restricts a read to the caller's own uid, so this is UNIQUE and its `:vappN`
  processes and no other app on the device; that is a platform guarantee rather than a
  filter. But UNIQUE's uid *includes the guests*, and an app may log its own credentials,
  so only framework, runtime, linker and UNIQUE tags are kept and app-authored logging is
  dropped rather than reasoned about. The trade is real and worth naming: a guest's own
  `Log.d` is often what would explain its behaviour, and it is not here. Dropping it is the
  only version of this that belongs in a file a user is invited to mail to a stranger.

What is deliberately absent is a structural property, not a filter: **nothing from inside
an instance's data directory**. No databases, no `shared_prefs`, no cookies, no tokens. The
exporter never opens those directories, which is a stronger guarantee than remembering to
exclude them. The instance summary lists identity UNIQUE itself generated — `vuid`,
package, version, the virtual `ANDROID_ID` — because that is what a support conversation is
about, and nothing an app stored.

Every line still passes through `DiagRedactor` on the way out, which drops OAuth tokens,
cookies, `Authorization` headers, account names and file contents. Redaction happens where
a line *leaves* `Diagnostics`, not at each consumer: a line that has left that object must
never need trusting again, and putting the redactor at every consumer is how one consumer
ends up without it. The redactor has its own unit tests, because a leaky diagnostics export
is a security bug and not a cosmetic one.

**Including the line that leaves for logcat.** `Diagnostics.event` used to write the raw
event there and redact only on export, on the reasoning that logcat is a developer's
concern. It is not: anything written there is readable by `adb logcat`, ends up in a bug
report, and on many OEM builds is collected by a logging service — so a token printed there
has left the device by a route the export has no say over. Both paths now go through the
same `formatted()`.

`t37` is the regression test, and it is written as one: a marker is planted inside a
guest's own `shared_prefs` and `databases/`, and in a field UNIQUE itself logs, and every
byte of every entry in the package is searched for it. The structural guarantee — the
exporter never opens those directories — is the strong one, and it is exactly the kind a
later refactor removes without noticing.

### 14.3 Crashes

A Java `UncaughtExceptionHandler` in every `:vappN` writes a crash record, pushes it to
UNIQUE's main process, and lets the process die. It deliberately does not swallow the
exception: a virtual app process that survives an unhandled exception is in an undefined
state and produces worse failures later, and §3.1 depends on `:vappN` actually dying. The
UI shows `App stopped · [Restart] [Details]` with time, component and a short reason
derived from the exception chain's *root* cause rather than the outermost wrapper, which is
usually a framework `RuntimeException` that says nothing. Stack traces live only in
diagnostics.

A native signal handler is installed too, before any guest native code can run, and `t33`
covers it: a null dereference inside the guest's own `.so` leaves

```
signal=SIGSEGV
signalNumber=11
code=1
faultAddress=0x0
pid=22811
tid=22811
```

under that instance's diagnostics directory, and the diagnostics export picks it up. The
platform's tombstone is still produced — the previous handler is chained to rather than
replaced — because a process that survives a SIGSEGV is in an undefined state, and because
UNIQUE's job here is to *add* a record the user can hand to someone, not to take one away.

Three constraints shape that handler, and each was learned by getting it wrong.

**A signal handler may do almost nothing.** It runs on a thread whose state is undefined,
possibly holding the heap lock the crashing code just took, so `malloc`, `snprintf` and
`dladdr` are all unavailable — not stylistically, but because using one turns a crash that
would have produced a tombstone into a hang that produces nothing. The file is opened
*before* the handler is installed, the record is formatted into a stack buffer with
hand-rolled integer conversion, and it goes out in a single `write(2)`.

**Symbolisation is deliberately absent.** Resolving an address to a library and offset
means `dladdr`, which takes the linker's lock — and a crash *inside* the linker is not
rare. The addresses go out raw; the tombstone is where they get names.

**A record is per process, and an empty one is not a spent one.** The fd is held open from
the graft until the process dies, so anything that *unlinks* the path leaves the handler
writing into a nameless inode: the write succeeds and nothing appears. Two versions failed
this way — first the test deleting the file it was waiting for, then UNIQUE's own pruning
tidying away the still-empty record of a process that was about to crash. Records are
pruned by age, never by emptiness alone.

---

## 15. UI

Flutter for the entire UI, in `:core` only. Virtual app processes never load Flutter.
Bridge: **Pigeon** for typed request/response, a native `EventChannel` for the diagnostics
stream, and FFI reserved for anything high-frequency (currently nothing in the UI path
qualifies, so FFI is not used yet — noted so the decision is deliberate).

Design direction per the brief: Material You, dark-first (near-black background, slightly
lighter surfaces, one calm accent), compact cards, 150–300 ms transitions with shared-axis
and fade-through, one icon language throughout. Screens: Splash → Home (compact adaptive
grid) → Add App (Installed | APK) → App Details (General, Permissions, Storage, Device
Profile, Google, Compatibility, Diagnostics) → Settings.

---

## 16. Security

Everything lives in the host's app-private storage (`0700`), including virtual external
storage — so a virtual app's data is not readable by other installed apps. Extracted native
libraries are `0555` (required by W^X, §8.2) but still inside the private tree. Backups are
encrypted with a key in the Android Keystore. Secrets never enter diagnostics.

Threat model boundary stated plainly: UNIQUE isolates virtual apps *from other installed
apps* and *from each other*. It does **not** isolate a virtual app from UNIQUE, and a
malicious virtual app running native code inside a UNIQUE process can reach anything that
process can reach. Users are told this before adding an app.

---

## 17. Roadmap

| Phase | Content | Exit criterion |
|---|---|---|
| **0** | Research, base decision, this document, module skeleton, build pipeline | Project builds; docs merged |
| **1** | Branding, icon, theme, navigation, Home/Add/Details/Settings, animations (mock data) | UI complete and navigable on device |
| **2** | Core MVP: import, `VirtualPathModel`, `LoadedApk` graft, launch, persistence, process pool | A Kotlin sample app launches, writes, restarts with data intact |
| **3** | Components: activities, services (incl. FGS types), receivers, providers, intents, permissions, jobs, alarms | Foreground-service + notification sample passes |
| **4** | Native: extraction, redirection, `dlopen`, 16 KB checks, Unity/Unreal, Vulkan | Unity IL2CPP sample renders at parity FPS |
| **5** | Google: router, five bridges, Mode A import, Mode B, Mode C, FCM experiment | Google Sign-In matrix (§9) run and published |
| **6** | Device profiles: persistence, regenerate, multi-profile, consistency tests | Consistency suite green |
| **7** | Compatibility pass over the test matrix; fixes land in `core/compat` only | Matrix published with honest per-app status |
| **8** | Performance, RAM, diagnostics, crash UX, polish | Benchmarks vs. native install published |

### 17.1 What is measured, and what those numbers are worth

`t29` measures cold start, warm start and memory on every run, and records them on the
PROCESS channel (`PERF_COLD_START`, `PERF_WARM_START`, `PERF_MEMORY`) so they land in the
run's `engine.log`. It asserts almost nothing about the numbers themselves, on purpose.
This suite runs on a headless x86_64 emulator with no hardware acceleration, sharing a
machine with whatever else is building. A wall-clock budget asserted there would not
measure UNIQUE; it would measure the host, and it would go red on the day another job was
busy. What *is* asserted is the ordering — fork, then `Application.onCreate`, then the
Activity — and that a warm start really reused the process, which are the two things that
would be broken rather than merely slow.

Two details are load-bearing, and the first version of the test got both wrong.

**Cold is checked, not arranged.** Killing every `:vappN` does not guarantee the next
launch is cold: a `:vappN` publishes a stub ContentProvider (§6.4.0), so ActivityManager
may restart it for a client of its own between the kill and the launch. The first version
reported a six-second "cold start" into a process that had been up for three minutes. The
test now compares the guest's own `Process.getStartUptimeMillis()` against when it asked,
retries once, and reports `PERF_COLD_START_NOT_COLD` rather than a wrong number.

**The total is measured on the guest's clock.** `resultWritten - processStart` is
self-consistent whatever the test was doing; a total anchored to when the test happened to
call `startActivity` measures idle time as often as work.

Memory is reported as **PSS**, not RSS. A virtual process shares the whole framework and
UNIQUE's own code, and RSS counts shared pages in full in every process at once — which is
how a virtualization layer gets accused of using several times the memory it uses.

### Definition of done for the MVP

The 14 criteria in the brief (§43), each backed by a test or a published measurement rather
than a claim. Any criterion not met is listed as not met.

---

## 18. Engineering rules in force

1. No stub is described as implemented. Unfinished surface throws
   `UnsupportedOperationException` with a `TODO(scope)` marker and is listed by
   `tools/report-unimplemented`.
2. `core/common` stays free of `android.*` so it can be unit-tested on the JVM.
3. Package-specific behaviour lives only in `core/compat`; enforced by architecture test.
4. Identity values come only from `core/vprofile`; enforced by architecture test.
5. Google-specific calls go only through `core/google`; enforced by architecture test.
6. Every hook declares `minApi`/`maxApi` and verifies its binding at install time.
7. Every crash leaves a diagnostic record.
