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
| Activity start by the guest itself (explicit) | `SUPPORTED` | `NOT_TESTED` | Routed onto a stub matching the target's `launchMode`; correct component, extras, process and task (`t10`) |
| Activity start by the guest itself (implicit) | `NOT_TESTED` | `NOT_TESTED` | Passed through to the platform untouched and reported; resolving against the guest's own intent filters is not implemented |
| `PendingIntent` to a guest activity or service | `SUPPORTED` | `NOT_TESTED` | The stub is baked in at creation; `Intent.setIdentifier` keeps two screens' PendingIntents distinct (`t11`) |
| `PendingIntent` broadcast to a guest receiver | `BROKEN` | `NOT_TESTED` | A dynamic receiver is matched by filter, never by component; needs a host stub receiver that re-dispatches. Reported as `PENDING_INTENT_RECEIVER_UNSUPPORTED`, never silently mis-pointed |
| Instance data isolation | `SUPPORTED` | `NOT_TESTED` | Every accessor resolves under `users/<vuid>/`; nothing leaks into UNIQUE's own dirs |
| Persistence across restart | `SUPPORTED` | `NOT_TESTED` | SharedPreferences, file and SQLite all continued after a process kill |
| Multiple instances | `SUPPORTED` | `NOT_TESTED` | Two instances of the same APK, independent data, both alive at once (`t05`) |
| Crash isolation | `SUPPORTED` | `NOT_TESTED` | A deliberate uncaught exception in one instance kills neither UNIQUE nor the sibling (`t06`) |
| Services — started | `SUPPORTED` | `NOT_TESTED` | `onCreate` + `onStartCommand` in the guest's own process and storage (`t07`) |
| Services — bound | `PARTIAL` | `NOT_TESTED` | `onBind` runs and the client connects with the guest's own binder; `onServiceConnected` receives the **stub's** `ComponentName`, which is the name AMS holds (`t07`) |
| Services — foreground | `SUPPORTED` | `NOT_TESTED` | `startForeground` with a type: the component is rewritten to the stub AMS knows, and the type is the guest's declaration intersected with the stub's superset. `FGS_TYPE_RESOLVED requested=0x1 declared=0x1 granted=0x1` (`t16`) |
| Foreground service type the host does not declare | `PARTIAL` | `NOT_TESTED` | Refused with `FGS_REFUSED` and an exception the app can see, never silently downgraded — a downgraded FGS dies later with a `ForegroundServiceDidNotStartInTimeException`. The refusal path itself is unit-tested, not device-tested |
| Broadcast receivers — manifest, guest running | `PARTIAL` | `NOT_TESTED` | Delivered to the guest's own receiver class (`t08`). Under `IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS`, a sender must scope the intent |
| Broadcast receivers — waking a dead guest | `SUPPORTED` | `NOT_TESTED` | `VirtualBroadcastRouter` holds the registrations in UNIQUE's main process and starts the instance's process carrying the intent; the guest's receiver runs cold in a process that did not exist when the broadcast was sent (`t25`) |
| Broadcast receivers — UNIQUE itself not running | `BROKEN` | `NOT_TESTED` | The registrations live in UNIQUE's main process, so a broadcast arriving while the whole app is dead is missed. Closing it needs the actions known at build time, so static registrations in the host manifest |
| Content providers — same virtual process | `SUPPORTED` | `NOT_TESTED` | Acquired through `ContentResolver`, answered by UNIQUE; `onCreate` before any other component; correct package, storage and pid (`t09`) |
| Content providers — from UNIQUE's own process | `SUPPORTED` | `NOT_TESTED` | UNIQUE resolves `(vuid, authority)` through its router provider, binds the owning slot and holds a real Binder to the guest's provider; correct package, storage and a different pid (`t26`) |
| Content providers — guest to another virtual process | `SUPPORTED` | `NOT_TESTED` | A guest whose provider is declared `android:process=":alt"` reaches it: the acquisition is redirected onto that slot's stub, whose authority set is widened at runtime so the platform accepts the guest's URIs (`t27`) |
| Content providers — `getType` cross-process | `PARTIAL` | `NOT_TESTED` | Answered, but `ContentResolver.getType` re-acquires rather than reusing the cached holder, so it costs a second resolve-and-bind round trip (`t27`) |
| URI permission grants across virtual processes | `NOT_TESTED` | `NOT_TESTED` | `grantUriPermission` between two `:vappN` is not implemented; §7.4 |
| `JobScheduler` | `SUPPORTED` | `NOT_TESTED` | Scheduled onto a host stub with a namespaced id; the system starts the guest's own `JobService`, in the guest's storage and process, and `getPendingJob` hands back the id and class the app chose (`t18`) |
| `JobScheduler` — job outliving the instance | `PARTIAL` | `NOT_TESTED` | The routing record is on disk, so a job fires after a cold start; a job whose record is gone is reported (`JOB_RECORD_MISSING`) rather than silently doing nothing. Not device-tested across a reboot |
| `AlarmManager` — inexact | `SUPPORTED` | `NOT_TESTED` | Set and cancelled through the guest's own API; the `PendingIntent` was already routed onto a stub when the guest built it (`t19`) |
| `AlarmManager` — exact | `PARTIAL` | `NOT_TESTED` | Depends on the *host* holding `SCHEDULE_EXACT_ALARM`, which Android 14 denies by default for targetSdk 33+. UNIQUE declares it and reports the state (`ALARM_EXACT_UNAVAILABLE`); a guest asking for an exact alarm gets the platform's own `SecurityException`, never a silent downgrade to inexact |
| `AlarmManager` — alarm clock | `NOT_TESTED` | `NOT_TESTED` | `setAlarmClock` would show in the status bar as UNIQUE's alarm, not the guest's |
| Clipboard — write | `SUPPORTED` | `NOT_TESTED` | `setPrimaryClip` accepted with the guest's identity rewritten (`t19`) |
| Clipboard — read | `NOT_TESTED` | `NOT_TESTED` | Android 10 restricts `getPrimaryClip` to the app holding input focus, and this headless emulator never gives an activity focus. `t19` records the result and asserts it only when `hadFocus` is true |
| Runtime permissions — check | `SUPPORTED` | `NOT_TESTED` | Answered from the instance's state through both routes the platform offers (`Context.checkSelfPermission` → `IActivityManager`, `PackageManager.checkPermission` → `IPackageManager`); host grant ∧ instance grant (`t12`) |
| Runtime permissions — request | `PARTIAL` | `NOT_TESTED` | The guest's request result is read from the activity-result transaction and recorded against the instance (`t12`). Proven for a permission the host already holds, which needs no dialog; a request the *host* does not hold needs the system dialog and is `NOT_TESTED` |
| Runtime permissions — rationale | `PARTIAL` | `NOT_TESTED` | `shouldShowRequestPermissionRationale` follows the instance's record. The false cases are asserted; the true case needs a recorded denial, so a dialog, and is `NOT_TESTED` |
| Runtime permissions — persistence | `SUPPORTED` | `NOT_TESTED` | Written under `runtime/permissions/<vuid>/`, restored at bootstrap; `PERMISSIONS_BOUND … restored=1` after a kill (`t13`) |
| AppOps | `PARTIAL` | `NOT_TESTED` | `checkPackage` and the op checks accept the guest's identity (`t14`). Ops are *attributed* to UNIQUE, because the uid is UNIQUE's — per-instance denial happens at the permission check instead |
| Native library loading (JNI) | `SUPPORTED` | `NOT_TESTED` | `System.loadLibrary` from an APK the system never installed; the library runs in the guest's process and JNI works both directions (`t17`). **On x86_64** — the mechanism is architecture-independent, the ARM64 answer is not |
| Native ABI selection | `SUPPORTED` | `NOT_TESTED` | The device's own `SUPPORTED_ABIS` order, as the platform does; an APK with no executable ABI is refused rather than started (`PACKAGE_IMPORTED … abi=x86_64`) |
| Native ARM64 specifically | `NOT_TESTED` | `NOT_TESTED` | This emulator is x86_64. `docs/PHYSICAL_DEVICE_TEST.md` exists for exactly this |
| 16 KB page size | `NOT_TESTED` | `NOT_TESTED` | Checked at import and at build time (`tools/check-abi.sh`), but this emulator reports 4096 so the large-page path is unexercised |
| Native IO redirection | `PARTIAL` | `NOT_TESTED` | PLT/GOT hooking of the guest's own libraries. A path hard-coded as `/data/data/<pkg>/files/…` in native code lands inside the instance (`t17`). Limited to libraries loaded by the time the guest's `Application.onCreate` finishes, and to calls that cross a PLT — libc calling itself is invisible, correctly so |
| Native IO redirection — late-loaded libraries | `SUPPORTED` | `NOT_TESTED` | `android_dlopen_ext` is watched in `libnativeloader`, so a library loaded from an Activity is hooked as it arrives: `1/318` → `2/319` libraries, and its hard-coded path lands in the instance (`t17`) |
| Diagnostics — events from a virtual process | `SUPPORTED` | `NOT_TESTED` | Pulled from each live `:vappN` through that slot's stub provider when an export is written; crash records are pushed to UNIQUE's main process by the dying process itself |
| Diagnostics — export package | `SUPPORTED` | `NOT_TESTED` | Written to UNIQUE's app-private cache. Contains the structured logs, the per-slot logs and the device; contains **nothing** from inside an instance's data directory, and every line passes the redactor |
| APK import from a file picker | `PARTIAL` | `NOT_TESTED` | The system document picker is wired, multi-select for base + splits, and the chosen files are copied into app-private storage before the importer reads them. Exercised by hand, not by the suite: the suite imports from an asset |
| OpenGL ES — EGL context and rendering | `SUPPORTED` | `NOT_TESTED` | EGL initialised, context made current and a frame rasterised inside the virtual process; the pixel is read back with `glReadPixels` and matches what was drawn (`t20`). **Software rasteriser** — a real GPU driver is a different code path |
| OpenGL ES — window surface | `NOT_TESTED` | `NOT_TESTED` | `t20` uses a pbuffer, so it does not depend on a visible window. The window path is covered by the manual step in `docs/PHYSICAL_DEVICE_TEST.md` |
| Vulkan — instance, device and queue | `SUPPORTED` | `NOT_TESTED` | `libvulkan.so` is `dlopen`ed from inside the virtual process, an instance is created, one physical device enumerated (`llvmpipe (LLVM 21.0.0, 256 bits)`, type `cpu`, API 1.3), a logical device created and a graphics queue acquired (`t28`). **Software driver** — a hardware ICD is a different code path, and the emulator declares `android.hardware.vulkan.version` so the test asserts the whole chain rather than skipping |
| Vulkan — surface, swapchain, presentation | `NOT_TESTED` | `NOT_TESTED` | `t28` creates no `VkSurfaceKHR`: it does not depend on a window, which this headless emulator never gives an activity |
| App reads its own signature | `SUPPORTED` | `NOT_TESTED` | Both `signingInfo` and the deprecated `signatures` array, parsed by the platform itself from the APK on disk (`t21`). Needed by integrity checks and by every Google API whose key is bound to a certificate |
| Guest's view of installed packages | `SUPPORTED` | `NOT_TESTED` | Matches the host's, including the *absence* of Play services (`t21`). An app told GMS is present when it is not fails later, somewhere less obvious |
| Google — host environment detection | `SUPPORTED` | `NOT_TESTED` | `GoogleEnvironment` reads the host: GMS present/enabled/version, Play Store, a Custom Tabs browser. Present-but-disabled and present-but-a-stub are distinguished from available. On this emulator every answer is correctly "absent" |
| Google — routing decision | `PARTIAL` | `NOT_TESTED` | `GoogleCompatRouter` chooses a mode per flow and records it as `GOOGLE_ROUTE`. Unit-tested; the decision has never been acted on because no bridge has a body |
| Google flows (sign-in, Credential Manager, Firebase, FCM) | `NOT_TESTED` | `NOT_TESTED` | Every bridge returns `Unsupported` naming the flow. **Unverifiable in this environment**: the emulator is `aosp_atd` with no Google stack, and a Google APIs image was not installed because the container has ~4 GB free against an 8.2 GB existing image. `docs/GOOGLE_DEVICE_TEST.md` is the procedure that would settle each flow |
| Notifications — post | `SUPPORTED` | `NOT_TESTED` | Posted as UNIQUE with the guest's title and text; the icon is rendered from the guest's resources and travels as a bitmap (`t15`) |
| Notifications — two instances | `SUPPORTED` | `NOT_TESTED` | Both instances post id 4711 and both survive, on separate channels the user can configure independently (`t15`) |
| Notifications — tap routing | `PARTIAL` | `NOT_TESTED` | The content `PendingIntent` is routed onto a stub at creation and carries the instance's vuid; the tap itself is not driven by the suite |
| `getNotificationChannel` returns the guest's own id | `BROKEN` | `NOT_TESTED` | It returns the namespaced id. Apps generally only test it for null |

## Applications

| Application | Kind | EMU34 | ARM64 | Notes |
|---|---|---|---|---|
| `com.unique.probe` | Plain Java. Activity, Service, manifest Receiver, Provider, SharedPreferences + file + SQLite | `SUPPORTED` | `NOT_TESTED` | `tools/testapp`. Rendering not asserted — the suite reads the app's observations, it does not look at the screen |
| Multi-activity sample | Task/back-stack | `NOT_TESTED` | `NOT_TESTED` | Phase 3 |
| Foreground-service sample | FGS types | `NOT_TESTED` | `NOT_TESTED` | Phase 3 |
| Provider sample | Cross-process provider | `SUPPORTED` | `NOT_TESTED` | The probe's own `ProbeAltProvider`, declared `android:process=":alt"` (`t27`) |
| Notification sample | Channels, tap routing | `NOT_TESTED` | `NOT_TESTED` | Phase 10 |
| WebView sample | WebView, deep-link return | `NOT_TESTED` | `NOT_TESTED` | Phase 6 |
| NDK sample | Java → JNI → `.so` → callback | `SUPPORTED` | `NOT_TESTED` | The probe's own `libprobenative.so` and a second library loaded late (`t17`); x86_64 only here |
| Split-APK sample | base + ABI/density/language splits | `SUPPORTED` | `NOT_TESTED` | The probe's own feature split; a class that exists only in the split is reachable, so `splitSourceDirs` reached the class loader |
| SQLite-heavy sample | Write throughput | `NOT_TESTED` | `NOT_TESTED` | Phase 14 |
| Camera / microphone samples | Permission-gated hardware | `NOT_TESTED` | `NOT_TESTED` | Phase 14 |
| OpenGL / Vulkan samples | Rendering path | `PARTIAL` | `NOT_TESTED` | The probe covers both (`t20`, `t28`), on software drivers. A real engine sample is still `NOT_TESTED` |
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
