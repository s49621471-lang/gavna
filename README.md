# UNIQUE

Application-level Android virtualization: run an installed app or an APK inside UNIQUE,
with its own data, its own identity, and as many independent instances as you want.

No root, no unlocked bootloader, no CPU emulation — virtual apps execute their own DEX
and their own ARM64 `.so` files in real Android processes, on the real ART runtime,
drawing to real surfaces on the real GPU.

**Target:** Android 12–16 (API 31–36), ARM64-v8a.

> **Status: phases 0 and 1 of 8.** The architecture, the engine's testable core, the host
> application and the interface are in place. **No virtual application runs yet.**
> [`docs/STATUS.md`](docs/STATUS.md) lists exactly what works, what does not, and what is
> not possible at all.

## Documentation

| Document | What it covers |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | The decision record: why a clean-room engine, the Android 12–16 constraints and their consequences, the Google layer, device profiles, the module tree, the roadmap |
| [`docs/STATUS.md`](docs/STATUS.md) | What is implemented, what is stubbed, what is impossible |

## Layout

```
app/          host application: manifest template, generated stub pool, Flutter host
core/common   pure JVM - APK/manifest parsing, ELF, paths, profiles, shims, compat
core/hook     hidden-API access, system-service interception
core/vpm      package import and the state database
core/vam      stub routing, foreground-service types
core/vstorage virtual filesystem, redirection publishing
core/vprofile device identity, single source
core/google   the three-mode Google router and its bridges
core/native   C++ redirect table and JNI (16 KB-page safe)
ui/           Flutter interface (add-to-app module)
tools/        fixture generation, host-side native tests, status reporting
```

## Building

Requires JDK 17+, the Android SDK with **build-tools 36**, **platform 36**, **NDK r27**,
and the **Flutter SDK** (the UI is an add-to-app module).

```bash
# One-time: the Flutter module records its SDK path, which settings.gradle.kts reads.
(cd ui && flutter pub get)

./gradlew :app:assembleRelease
```

## Testing

```bash
./gradlew test                    # 84 JVM tests
./tools/native-test/run.sh        # 34 host-side native checks, no device needed
(cd ui && flutter test)           # 8 Dart tests
./tools/report-unimplemented.sh   # every deliberately unimplemented surface
```

The parser and ELF tests run against fixtures produced by real `aapt2` and real NDK
clang, regenerated with `tools/gen-fixtures.sh` — checked-in bytes that nobody can
reproduce are not evidence.

## Engineering rules

Enforced by review, and where possible by tests:

1. Nothing unfinished is described as done. Unimplemented surface is marked
   `TODO(phase-N)` and reports itself at runtime; `tools/report-unimplemented.sh` lists
   all of it.
2. `core/common` never depends on `android.*`, so the correctness-critical models stay
   testable without a device.
3. Package-specific behaviour lives only in `core/compat`.
4. Identity values come only from `core/vprofile`.
5. Google-specific calls go only through `core/google`.
6. Every shim declares its API range and verifies its binding at install time.
7. Every crash leaves a diagnostic record.

## Scope

UNIQUE isolates virtual apps from other installed apps and from each other. It does
**not** isolate a virtual app from UNIQUE: a malicious app running native code inside a
UNIQUE process can reach whatever that process can reach.

It is not a privilege escalation and not an attestation bypass. Apps that require Play
Integrity or a Play-attested identity cannot work here, and UNIQUE reports that rather
than working around it.
