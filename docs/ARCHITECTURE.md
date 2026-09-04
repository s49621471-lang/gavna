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
- **Not an attestation bypass.** Play Integrity / SafetyNet / DroidGuard will fail inside
  UNIQUE and this is not treated as a bug (§9.7). Apps that hard-require device
  attestation are recorded as **Unsupported** in the compatibility database, not "worked
  around".
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

Launch path:

```
vApp: startActivity(realIntent)
  → IActivityTaskManager shim: wrap into stubIntent
       component  = com.unique/.stub.ActivityStub_p3_aff2_singleTask
       extras     = { _unique_intent: realIntent, _unique_vuid, _unique_info: ActivityInfo }
  → system_server launches the stub in :vapp3
  → ClientTransaction (LaunchActivityItem) arrives
  → VAM rewrites LaunchActivityItem.mIntent / .mInfo back to the real ones
  → Instrumentation.newActivity() loads the real Activity class from the vApp classloader
  → the app sees exactly its own Activity, its own Intent, its own ActivityInfo
```

Why `ClientTransaction` and not `Handler.Callback(LAUNCH_ACTIVITY)`: since Android 9 the
lifecycle moved to `ClientTransaction`/`ClientTransactionItem`; since Android 14 the
transaction items are further restructured and `LAUNCH_ACTIVITY` no longer exists as a
message. Intercepting the transaction is the only version-durable point.

Fidelity items handled here: `launchMode`, `taskAffinity`, `theme` (must be applied to the
stub *before* `onCreate`, or Android 15 edge-to-edge enforcement uses the wrong window
insets), `screenOrientation`, `configChanges`, `windowSoftInputMode`, display cutout mode,
`android:exported`.

### 6.2 Services

`startService`/`bindService` → stub service in the target `:vappN` carrying the real
`Intent`; `VAM` maintains the real service record (start ids, bind connections, sticky
restarts). `bindService` returns the virtual app's real `IBinder` through the stub's
`onBind`, so in-process and cross-vapp binding both behave normally.

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

### 6.3 Broadcast receivers

- **Manifest receivers**: registered dynamically by `:server` on behalf of the virtual app
  for the actions in its manifest, then dispatched into the owning `:vappN`. Android 8+
  implicit-broadcast restrictions apply to the host too, so the set of actions that can be
  delivered is genuinely reduced; the unsupported subset is enumerated in Diagnostics
  rather than pretended.
- **Context receivers**: `registerReceiver` shim injects `RECEIVER_EXPORTED` /
  `RECEIVER_NOT_EXPORTED` (mandatory since Android 14 when the *host* targets 34+),
  choosing `NOT_EXPORTED` unless the virtual app explicitly requested exported behaviour.
- Ordered broadcasts, sticky broadcasts and `BroadcastReceiver.PendingResult` (`goAsync`)
  are proxied with the real result data.

### 6.4 Content providers

`ContentResolver.acquireProvider` → `IActivityManager.getContentProvider` shim →
`VAM` resolves the authority against the *virtual* provider table first. Cross-vapp
provider access is routed through `:server`, which holds an authority → (vuid, process)
map and returns a stub-backed `IContentProvider` that transparently forwards
`query/insert/update/delete/call/openFile`, including `AssetFileDescriptor` passing.

`FileProvider`, `DocumentsProvider` and URI permission grants are handled in §7.4.

### 6.5 Jobs, alarms, clipboard

- **JobScheduler**: virtual job ids are namespaced (`vuid << 20 | jobId`) onto host stub
  `JobService`s; constraints and backoff are preserved; over-quota conditions are reported.
- **AlarmManager**: `PendingIntent`s are rewritten to host trampolines with explicit
  mutability flags (mandatory since Android 12). Exact alarms require
  `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` at host level; if the host lacks it, the alarm
  is downgraded to inexact **and a diagnostic is emitted**.
- **Clipboard**: `IClipboard` shim rewrites the calling package; Android 12+ clipboard
  access toasts will name UNIQUE, not the virtual app — documented, not hidden.

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

### 8.2 Library extraction and W^X

Android 10+ refuses to `dlopen` code from a writable location for apps targeting API 29+.
Extracted `.so` files are therefore written to
`…/apk/<pkg>/<vc>/lib/arm64-v8a/` and `chmod`ed to `0555`, with the directory `0555` after
extraction completes. Extraction is atomic (temp dir + `rename`) so a killed install cannot
leave a half-populated lib dir that later `dlopen`s partially.

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
| Credential Manager / `GetGoogleIdOption` | ID-token audience is a **web/server client id**; package is recorded but not the audience | Can succeed — the token is valid for the app's backend |
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

### 9.7 What is out of scope, permanently

Play Integrity, SafetyNet, DroidGuard, Play Billing and Play Games all bind to an attested,
installed package identity. These cannot work inside app-level virtualization and UNIQUE
does not attempt to defeat them. Apps requiring them are marked **Unsupported** with the
reason shown to the user.

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
ring buffers (`LAUNCH, PROCESS, NATIVE, STORAGE, GOOGLE, WEBVIEW, NOTIFICATION, CRASH`),
spilled to disk per instance. Export produces
`diagnostics/{app.json, runtime.log, crash.log, gms.log, environment.json}` with a redaction
pass that drops OAuth tokens, cookies, `Authorization` headers, account names and file
contents; the redactor has its own unit tests, because a leaky diagnostics export is a
security bug.

Crashes: a native signal handler plus a Java `UncaughtExceptionHandler` in every `:vappN`
write a crash record and let the process die. The UI shows `App stopped · [Restart]
[Details]` with time, component and a short reason. Stack traces live only in diagnostics.

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
