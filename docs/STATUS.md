# UNIQUE — status

Regenerate the "not implemented" section with `tools/report-unimplemented.sh`. This file
exists because ARCHITECTURE.md §18 rule 1 forbids describing unfinished work as done.

**Phases 0 and 1 complete. Phase 2 in progress.**

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

## On device (EMU34): not yet passing

`t02` — the activity hand-off — is the current work, and each run has moved the failure
further down the launch path:

| Blocker | Status |
|---|---|
| `hostContext` null during `attachBaseContext` → silent return | fixed |
| `makeApplication` failure reason swallowed | fixed (chain now reported) |
| `SecurityException: Writable dex file … is not allowed` (W^X on the APK) | fixed |
| `SecurityException: Given calling package … does not match caller's uid` | fixed (outbound identity rewrite) |
| `SecurityException: Package … does not belong to <uid>` — the `AttributionSource` on provider calls | fixed, awaiting re-run |

The guest's `Application` runs and the platform launches the guest's real Activity, but
**no virtual application has rendered yet**: the failures above all occur inside
`Activity.attach`. The remaining acceptance tests (`t03`–`t06`) have not been exercised.

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
