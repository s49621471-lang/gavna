# UNIQUE — status

Generated against the tree at the time of writing; regenerate the second half with
`tools/report-unimplemented.sh`. This file exists because ARCHITECTURE.md §18 rule 1
forbids describing unfinished work as done, and a roadmap alone does not say which
parts of it have actually landed.

**Phases 0 and 1 are complete. Phase 2 onwards is not started.** In particular:
**no virtual application runs yet.**

---

## What is done and verified

| Area | State | How it was verified |
|---|---|---|
| Architecture decision record | Done | `docs/ARCHITECTURE.md` |
| Gradle build, 13 modules, ARM64-only, API 31–36 | Done | `./gradlew assembleRelease` |
| Binary XML / manifest decoding | Done | 16 tests against real `aapt2` (build-tools 36.0.0) output |
| APK bundle + split classification and selection | Done | 8 tests, incl. a case that caught a real de-duplication bug |
| ELF inspection: ARM64 + 16 KB page alignment | Done | 7 tests against real NDK r27 `.so` files, 4 KB and 16 KB |
| Virtual path contract | Done | 12 tests pinning every accessor and every alias |
| Native redirect table (C++) | Done | 34 host-side checks, no device needed |
| Signature-agnostic shim engine | Done | 10 tests, incl. the same shim bound to two different signatures |
| Device profile model + generator | Done | 9 tests: shape, stability, regeneration, RFC 4122 |
| Compatibility database + resolver | Done | 5 tests incl. local-override merging |
| Diagnostics redactor | Done | 7 tests: JWTs, `ya29.`, bearer headers, emails, key names |
| Google routing table | Done | 10 tests pinning every flow's decision |
| Stub component generation | Done | Built APK contains 129 activities, 34 services, 17 providers |
| State database (Room, v1, schema exported) | Done | Compiles; schema JSON checked in |
| Application icon | Done | Adaptive + monochrome, safe-zone geometry checked |
| Flutter interface | Done | `flutter analyze` clean, 8 tests |
| 16 KB page-size compliance | Done | Every shipped `.so` ≥ 16 KB aligned; `zipalign -c -P 16` passes |

Totals: **84 JVM tests, 8 Dart tests, 34 native checks — all passing.**
(`./gradlew test` reports 94 executions: `core:google`'s 10 tests run for both build variants.)
Release APK: **17.8 MB**.

## What is deliberately not implemented

Each of these reports itself at runtime rather than failing quietly.

| Surface | Phase | Behaviour today |
|---|---|---|
| Virtual app process bootstrap | 2 | `:vappN` refuses to start and logs `VAPP_BOOTSTRAP_NOT_IMPLEMENTED`. Starting half-configured would run the app under UNIQUE's identity and write into UNIQUE's own data directory. |
| VirtualCore server interface | 2 | `onBind` returns null; a caller fails immediately rather than on the first transaction. |
| APK import from the picker | 2 | `PackageInstaller` is written and compiles; the picker is not wired, and the button is disabled rather than a no-op. |
| Activity hand-off (`ClientTransaction` rewrite) | 3 | Stub finishes and logs `ACTIVITY_HANDOFF_NOT_IMPLEMENTED`. |
| Service / job / provider forwarding | 3 | Stubs log and decline. |
| Settings interception (ANDROID\_ID) | 3 | Shims are defined and unit-testable but not installed; `DeviceProfileStatus.settingsInterceptionActive` is false. A virtual app would read the host's real ANDROID\_ID. |
| Hidden-API native fallback | 3 | `HiddenApi.nativeFallbackAvailable` is a constant `false`. |
| libc IO redirection | 4 | `InstallStatus.NOT_IMPLEMENTED`. The table is complete and tested; the ~30 libc hooks are not. |
| Native property virtualization | 6 | `InstallStatus.NOT_IMPLEMENTED`. The store works; the interception does not. |
| Every Google bridge implementation | 5 | Interfaces and the routing table exist and are tested. No bridge has a body. |

## What is known to be impossible, not merely unfinished

- **Play Integrity, SafetyNet, DroidGuard, Play Billing, Play Games.** These bind to an
  attested, installed package identity. App-level virtualization cannot provide one and
  UNIQUE does not try to defeat them.
- **AOT compilation of virtual apps.** Since Android 10 an app cannot invoke `dex2oat`.
  Virtual apps are JIT-only, so cold start will be measurably slower than an installed
  app. This is a property of the platform, not a bug to fix.
- **32-bit-only applications** on a 64-bit-only device.

## Not yet measured

Nothing in this repository has run on a physical device. Every claim above is from a
build-machine test or an inspection of build output. In particular there are **no**
benchmark numbers, **no** Google Sign-In matrix results, and **no** compatibility results
for any real application — the compatibility database ships architectural facts only, and
marks nothing as SUPPORTED.
