# UNIQUE — status

Regenerate the "not implemented" section with `tools/report-unimplemented.sh`. This file
exists because ARCHITECTURE.md §18 rule 1 forbids describing unfinished work as done.

**Phases 0 and 1 complete. Phase 2: a virtual application launches, runs and persists.**

A real APK — not installed on the device — is imported, registered, given an instance, and
launched into a `:vappN` process where it believes it is itself. Evidence is checked in
under `docs/evidence/`.

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

Run `20260904-071253-7364`, Android 14 x86_64, probe **not installed on the device**:

| Test | Result |
|---|---|
| `t01` import, register, create an instance | **PASS** |
| `t02` launch, and the app sees its own identity | **PASS** |
| `t03` the app writes nothing into UNIQUE's own directories | **PASS** |
| `t04` data survives a full process kill and relaunch | **PASS** |

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

### Still to run

`t05` (a second instance is fully independent) and `t06` (a crashing instance kills
neither UNIQUE nor its sibling) are written and have not yet been executed.

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

**Caveat on rendering.** The suite asserts the activity ran and produced its observations;
it does not look at the screen. Confirming that pixels appear is a two-minute manual step
in `docs/PHYSICAL_DEVICE_TEST.md`.

## Deliberately not implemented

| Surface | Phase | What it does instead |
|---|---|---|
| Services, receivers, providers | 3 | Stubs log `*_NOT_IMPLEMENTED` and decline |
| Settings interception (ANDROID_ID to the guest) | 3 | Shims defined, not installed; `DeviceProfileStatus.settingsInterceptionActive` is false |
| Hidden-API native fallback | 3 | `HiddenApi.nativeFallbackAvailable` is a constant `false` |
| libc IO redirection | 4 | `InstallStatus.NOT_IMPLEMENTED`; the table is complete and tested, the ~30 hooks are not |
| Native property virtualization | 6/7 | `InstallStatus.NOT_IMPLEMENTED` |
| Every Google bridge body | 6 | Interfaces and routing exist and are tested; no bridge has an implementation |
| Device profile regenerate | 7 | UI row says it is not available yet |
| VirtualCore server interface | 3 | `onBind` returns null; the UI process owns the database for now |
| APK file picker | 3 | Import from an installed app works; the picker is not wired |

## Known limits that are not bugs

- **No AOT.** Since Android 10 an app cannot invoke `dex2oat`, so virtual apps are
  JIT-only and cold start is slower than an installed app. This is a platform property.
- **Attestation.** Play Integrity, Play Games and Play Billing are expected not to work
  and are recorded `UNSUPPORTED_FOR_NOW` — expected, not measured.
- **32-bit-only apps** on a 64-bit-only device.
- The Credential Manager route is the Google layer's **central hypothesis**, unverified.

## Next steps, in order

1. Re-run the acceptance suite with the W^X fix; get `t02` green — a virtual activity
   rendering is the gate for everything after it.
2. Run `t03`–`t06`: storage isolation, restart persistence, two independent instances,
   crash isolation with a surviving sibling.
3. Phase 3: services, receivers, providers, then permissions.
4. Phase 4: ARM64 native, which needs the physical device to mean anything.

See `docs/COMPATIBILITY.md` for the per-application matrix and
`docs/PHYSICAL_DEVICE_TEST.md` for the physical-device checklist.
