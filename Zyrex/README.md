# Zyrex

Mod framework for **BLOCKPOST** (`com.skullcapstudios.bps`), built in two passes.

This repository is pass one: a runtime IL2CPP dumper injected into the game.
It exists because the target is protected in a way that defeats static
analysis, and the offsets an ESP / aimbot / triggerbot needs cannot be
recovered without running the game once.

---

## Target profile

| | |
|---|---|
| Package | `com.skullcapstudios.bps` |
| Version | `1.00f3` (versionCode 350) |
| Engine | Unity **2021.3.45f2**, IL2CPP backend |
| ABI | **arm64-v8a only** — no armeabi-v7a, no x86 |
| minSdk / targetSdk | 24 / 35 |
| Launcher | `com.unity3d.player.UnityPlayerActivity` (stock) |
| Application | `androidx.multidex.MultiDexApplication` |
| Dex files | `classes.dex` … `classes8.dex` |
| Signature | self-signed `META-INF/MY_KEY.RSA` — a repack, not a Play original |

Notable third-party code: AppLovin (ads), Dissonance + Opus (voice chat),
androidx datastore/lifecycle. No Photon — networking is custom.

---

## Why a dumper first

Two protections stack on this build:

**1. The IL2CPP metadata is encrypted.**
`assets/bin/Data/Managed/Metadata/global-metadata.dat` has no `AF1BB1FA`
magic, and the ~2.1 MB names table between `0x20000` and `0x220000` measures
8.0 bits/byte of entropy — fully scrambled. The string `global-metadata.dat`
is itself encrypted inside `libil2cpp.so`, so this is a deliberate protector
rather than a Unity quirk. Il2CppDumper and every other static tool produce
nothing.

**2. `Assembly-CSharp` is name-obfuscated.**
Beebyte-style: types come out as `AagnEi`, `AbvveiVkiphjof`, members as
`aimkw1pv`. Even a clean static dump would hand back meaningless names.

**3. The runtime enumeration entry point has been stripped.**
`il2cpp_domain_get_assemblies` — the function every runtime dumper calls
first — is absent from the export table.

What survives, and what the whole approach rests on: `libil2cpp.so` still
exports the other **236 IL2CPP C API functions**, including
`il2cpp_class_get_field_from_name`, `il2cpp_field_get_offset` and
`il2cpp_image_get_class`. The runtime decrypts its own metadata during
startup, so once the process is live, everything is readable by name. Two
enumeration paths replace the stripped one:

* `mono_domain_get_assemblies_iter` — the Mono compatibility shim, still exported
* `il2cpp_domain_assembly_open` — opened against a built-in list of the
  assembly names a Unity player build can contain

Both run and the results are merged and de-duplicated, so losing either one
does not cost coverage.

---

## What the dumper produces

Written to `/sdcard/Android/data/com.skullcapstudios.bps/files/zyrex/`
(app-scoped external storage — no runtime permission needed):

| File | Contents |
|---|---|
| `00_summary.txt` | module base/size, assembly list, class counts, absorbed faults |
| `01_candidates.txt` | player-class candidates ranked by structural shape, plus static-collection holders (entity-list candidates) |
| `02_game.cs` | full dump of the game's own assemblies — fields with offsets, methods with RVAs |
| `03_unity.cs` | `UnityEngine.*` / `Unity.*` — needed for `Camera`, `Transform`, `Physics`, `Animator` |
| `04_other.cs` | mscorlib, System, third-party |
| `_status.txt` | `started` → `dumping` → `done`, polled by the Java side to drive the on-screen toast |

Because the type names are obfuscated, `01_candidates.txt` scores classes by
shape instead. An FPS player script is a `MonoBehaviour` that owns a
`Transform`, carries a float (health), an int (team or kills), booleans
(dead / local) and usually a string (nickname). Classes are ranked on that
signature; classes holding a **static** collection field are listed
separately, since that is where an entity list normally lives.

Method pointers are recorded as RVAs relative to the `libil2cpp.so` load
base, read from `/proc/self/maps`, so they stay meaningful across launches
under ASLR.

---

## Crash engineering

The dumper walks metadata that a protector has already tampered with, so a
malformed class handing back a bad pointer is a realistic outcome, and an
unguarded fault means the game dies on launch.

* **Fault guard** — `SIGSEGV`/`SIGBUS` handlers installed only for the
  duration of the dump. Every class walk runs inside `sigsetjmp`; a fault
  skips that one class, notes it in the output, and the dump continues.
* **Handlers stay narrow** — they act only on faults raised by the dumper's
  own thread (checked with `pthread_equal`). Anything else is forwarded to
  the handler that was installed before us, so the game's own crash reporter
  keeps working. The previous disposition is restored when the dump ends.
* **Alternate signal stack** — 64 KiB via `sigaltstack`, so a fault raised
  when the normal stack is unusable can still be caught.
* **Nothing blocks startup** — `JNI_OnLoad` only registers. All work happens
  on a detached worker thread that sleeps 20 s before touching the runtime,
  because metadata registration is not complete until well into the first
  scene load.
* **Every iterator is bounded** — fields 4096, methods 8192, properties 2048,
  assemblies 1024. A corrupted iterator terminates instead of spinning.
* **Every pointer is range-checked** — `MethodInfo::methodPointer` is only
  trusted when it lands inside the mapped `libil2cpp.so` image; otherwise the
  RVA is recorded as 0.
* **Required vs optional symbols** — the resolver distinguishes them. A
  missing optional symbol degrades one column of output; a missing required
  symbol aborts the dump cleanly rather than proceeding into a null call.
* **The Java side cannot throw into the game** — every path in `Zyrex.init`
  is wrapped in `try/catch (Throwable)`. Failure logs and returns, and the
  game continues unmodified.
* **Single-shot** — an atomic CAS on the native side and a `synchronized`
  flag on the Java side make double-injection a no-op.

Known residual risks are documented in [`REVIEW.md`](REVIEW.md) rather than
hidden — most notably that `il2cpp_class_get_fields` triggers class
initialization internally, which is the one operation here with real side
effects.

---

## Layout

```
Zyrex/
  README.md
  REVIEW.md                    static review: findings, residual risks
  dumper/
    build.sh                   full build: native -> dex -> repack -> sign
    patch_activity.py          smali injection into UnityPlayerActivity.onCreate
    native/
      CMakeLists.txt
      src/
        main.cpp               JNI entry, worker thread, lifecycle
        il2cpp_api.h/.cpp      symbol resolution, module range, RVA helpers
        dumper.h/.cpp          enumeration, emission, candidate scoring
        guard.h/.cpp           SIGSEGV/SIGBUS fault guard
        log.h
    java/
      com/zyrex/dumper/Zyrex.java   bootstrap, output dir, status watcher
```

---

## Build

```bash
export ANDROID_HOME=/opt/android-sdk
export NDK=$ANDROID_HOME/ndk/26.3.11579264
./dumper/build.sh "BLOCKPOST SERVER_1.00f3.apk"
```

Needs SDK build-tools 34, NDK r26, apktool 2.12+, JDK 17+.

The injected dex is appended as `classes9.dex` rather than merged into an
existing one: ART loads every contiguous `classesN.dex`, the original stops
at `classes8.dex`, so it is picked up with no loader changes.

---

## Running it

1. Uninstall the original — signatures differ, so it will not install over it.
2. Install the built APK.
3. Launch. A toast confirms the output path.
4. Play for a minute or two, ideally joining a match so the player classes are
   actually instantiated.
5. A second toast reports `dump complete`.
6. Pull the folder:

```bash
adb pull /sdcard/Android/data/com.skullcapstudios.bps/files/zyrex/
```

`02_game.cs` and `01_candidates.txt` are the two that matter for pass two.

---

## Pass two

With the dump in hand the obfuscated player class stops being a guess, and
the real build follows: the Zyrex overlay, ESP, aimbot and triggerbot wired
to actual offsets.

The architecture is already settled by the recon:

* **Per-frame main-thread tick** — hook `UnityEngine.Input::get_touchCount`.
  It is polled every frame by the game's input handler, on the Unity main
  thread, which is where scene access has to happen. It doubles as the
  injection point for synthetic look/fire input.
* **World-to-screen** — `UnityEngine.Camera::WorldToScreenPoint` off
  `Camera.main`.
* **Visibility** — `UnityEngine.Physics::Linecast`.
* **Bones** — `Animator.GetBoneTransform` where the rig is humanoid.
* **Rendering** — native computes screen-space boxes into a double buffer;
  an Android `View` over the Activity's `DecorView` draws them on the UI
  thread. No GL hook, no overlay permission.
* **Menu** — the Zyrex panel as designed, opened by the white bar pinned to
  the bottom of the screen.
