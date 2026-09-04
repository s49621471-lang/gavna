# Compatibility matrix

Status vocabulary, used strictly:

| Status | Means |
|---|---|
| `SUPPORTED` | Exercised on a device and observed working, with the run recorded |
| `PARTIAL` | Works with a stated limitation, observed on a device |
| `BROKEN` | Exercised on a device and observed failing, with the reason recorded |
| `UNSUPPORTED_FOR_NOW` | Expected not to work, **not yet investigated** |
| `NOT_TESTED` | No attempt made |

`NOT_TESTED` is never reported as `SUPPORTED`, and `BROKEN` is never used for something
that was merely reasoned about. The difference between "we tried and it failed" and "we
never tried" is the difference between a fact and a guess.

## Environments

| Id | Environment |
|---|---|
| `EMU34` | Android 14 (API 34), x86_64, `aosp_atd` emulator, software rendering, no KVM |
| `ARM64` | Physical ARM64 Android 15 phone — **not yet run**, see `docs/PHYSICAL_DEVICE_TEST.md` |

## Engine capabilities

| Capability | EMU34 | ARM64 | Notes |
|---|---|---|---|
| APK import (single APK) | `SUPPORTED` | `NOT_TESTED` | Probe imported from a file, package not installed on the host |
| Virtual package registration | `SUPPORTED` | `NOT_TESTED` | Room-backed, survives process death |
| Instance creation | `SUPPORTED` | `NOT_TESTED` | Directories created, device profile generated |
| `:vappN` process start | `SUPPORTED` | `NOT_TESTED` | Slot assigned per (instance, manifest process) |
| Hidden-API access | `SUPPORTED` | `NOT_TESTED` | via `HiddenApiBypass` |
| Transaction interception | `SUPPORTED` | `NOT_TESTED` | `LaunchActivityItem` found and rewritten |
| Virtual `PackageManager` | `SUPPORTED` | `NOT_TESTED` | 7 methods bound; required for uninstalled packages |
| Outbound identity to system services | `SUPPORTED` | `NOT_TESTED` | Calling package and `AttributionSource` rewritten to the host |
| `LoadedApk` graft | `SUPPORTED` | `NOT_TESTED` | Guest's own Application instantiated, `onCreate` before the Activity |
| Activity launch | `SUPPORTED` | `NOT_TESTED` | Guest's real Activity class, correct `componentName` |
| Instance data isolation | `SUPPORTED` | `NOT_TESTED` | Every accessor resolves under `users/<vuid>/`; nothing leaks into UNIQUE's own dirs |
| Persistence across restart | `SUPPORTED` | `NOT_TESTED` | SharedPreferences, file and SQLite all continued after a process kill |
| Multiple instances | `NOT_TESTED` | `NOT_TESTED` | `t05` written, not yet run |
| Crash isolation | `NOT_TESTED` | `NOT_TESTED` | `t06` written, not yet run |
| Services | `NOT_TESTED` | `NOT_TESTED` | Not implemented (phase 3) |
| Broadcast receivers | `NOT_TESTED` | `NOT_TESTED` | Not implemented (phase 3) |
| Content providers | `NOT_TESTED` | `NOT_TESTED` | Not implemented (phase 3) |
| Runtime permissions | `NOT_TESTED` | `NOT_TESTED` | Store exists, not wired to the guest |
| Native ARM64 / JNI | `NOT_TESTED` | `NOT_TESTED` | Not implemented (phase 4); the emulator is x86_64 and could not prove it anyway |
| Native IO redirection | `NOT_TESTED` | `NOT_TESTED` | Table implemented and unit-tested; libc interception not implemented |
| Surface / OpenGL / Vulkan | `NOT_TESTED` | `NOT_TESTED` | Phase 5 |
| Google flows | `NOT_TESTED` | `NOT_TESTED` | Interfaces only, no implementations |
| Notifications | `NOT_TESTED` | `NOT_TESTED` | Namespacing implemented and unit-tested; bridge not implemented |

## Applications

| Application | Kind | EMU34 | ARM64 | Notes |
|---|---|---|---|---|
| `com.unique.probe` | Plain Java, one Activity, SharedPreferences + file + SQLite | `SUPPORTED` | `NOT_TESTED` | `tools/testapp`. Rendering not asserted — the suite reads the app's observations, it does not look at the screen |
| Multi-activity sample | Task/back-stack | `NOT_TESTED` | `NOT_TESTED` | Phase 3 |
| Foreground-service sample | FGS types | `NOT_TESTED` | `NOT_TESTED` | Phase 3 |
| Receiver sample | Manifest + context receivers | `NOT_TESTED` | `NOT_TESTED` | Phase 3 |
| Provider sample | Cross-process provider | `NOT_TESTED` | `NOT_TESTED` | Phase 3 |
| Notification sample | Channels, tap routing | `NOT_TESTED` | `NOT_TESTED` | Phase 10 |
| WebView sample | WebView, deep-link return | `NOT_TESTED` | `NOT_TESTED` | Phase 6 |
| NDK sample | Java → JNI → `.so` → callback | `NOT_TESTED` | `NOT_TESTED` | Phase 4 |
| Split-APK sample | base + ABI/density/language splits | `NOT_TESTED` | `NOT_TESTED` | Selection logic unit-tested only |
| SQLite-heavy sample | Write throughput | `NOT_TESTED` | `NOT_TESTED` | Phase 14 |
| Camera / microphone samples | Permission-gated hardware | `NOT_TESTED` | `NOT_TESTED` | Phase 14 |
| OpenGL / Vulkan samples | Rendering path | `NOT_TESTED` | `NOT_TESTED` | Phase 5 |
| Firebase Auth sample | Google | `NOT_TESTED` | `NOT_TESTED` | Phase 6 |
| Google Sign-In sample | Google | `NOT_TESTED` | `NOT_TESTED` | Phase 6 |
| Unity / Unreal sample | Native engine | `NOT_TESTED` | `NOT_TESTED` | Phase 4; no claim will be made without a real sample |

## How to reproduce

```bash
export ANDROID_HOME=/path/to/android-sdk
UNIQUE_ABIS=x86_64 ./tools/verify-device.sh     # emulator
UNIQUE_ABIS=arm64-v8a ./tools/verify-device.sh  # physical device
```

Each run writes `build/device-verification/<run-id>/` containing the device properties,
per-test results, the engine's structured events, any crash, and the process list.
