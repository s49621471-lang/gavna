# bpesp — BLOCKPOST ESP, no root

Player ESP for BLOCKPOST (`com.skullcapstudios.bps`, built against 1.00f4,
arm64-v8a). Ships as a patched APK: no root, no Xposed/LSPosed, no Frida, no
separate injector process. Everything runs inside the game's own process
because it *is* part of the app after patching.

1.00f4 ships the same game code as 1.00f3 — `global-metadata.dat`,
`libil2cpp.so` and all eight dex files are byte-identical between the two, so
every offset here carries over untouched. What the update actually changed is
the manifest version, a repacked `libmain.so`, and an added armeabi-v7a ABI.

## What it draws

Boxes (full or corner), name, health bar, armor bar, health number, distance,
K/D, snap line, look-direction line, dead-player toggle. All of it toggled live
from a draggable on-screen menu, persisted in `SharedPreferences`.

## How it hooks in

Three changes to the APK — nothing else in the archive is touched, and the
build verifies that (1622 entries carried over byte-identical):

1. **`AndroidManifest.xml`** — `application android:name` is repointed from
   `androidx.multidex.MultiDexApplication` to
   `com.skullcapstudios.esp.EspOverlayApp`.

   This is a raw byte edit inside the binary AXML string pool, not an apktool
   round-trip. Both names are exactly 37 characters, so the pool's length
   prefixes and every downstream offset stay valid and `resources.arsc` is
   never re-encoded. That is the whole reason the replacement class has that
   slightly odd name.

2. **`classes9.dex`** — the overlay classes, dropped into the next free
   multidex slot. ART loads every `classesN.dex` in the archive as long as the
   sequence has no gaps.

3. **`lib/arm64-v8a/libesp.so`** — the reader.

`EspOverlayApp` extends the multidex application it replaced, so multidex still
initialises exactly as before. It registers an activity lifecycle callback, and
when the Unity activity resumes it calls `addContentView` with a full-screen
transparent `View`. Unity's `SurfaceView` does not set `setZOrderOnTop`, so a
view added afterwards composites above it. Same window means unconsumed touches
still reach the game — `onTouchEvent` returns `false` unless the touch is on the
handle or inside an open menu.

## How it reads the game

`libil2cpp.so` exports the full il2cpp runtime API (236 symbols), so nothing
here parses `global-metadata.dat`. That matters: this build ships an encrypted
metadata header (magic `FF5ABA69` instead of `FAB11BAF`) and the game
assembly's name strings are not in plaintext. Going through the runtime API
sidesteps all of it — the runtime has already decrypted whatever it needed.

One native thread does the work:

1. Waits for `libil2cpp.so`, binds the API, `il2cpp_thread_attach`.
2. **Finds the entity class by layout, not by name.** The obfuscated names
   rotate every build, so the fingerprint is structural: instance size
   ≥ `0x168`, `System.String` at `0x18` and `0x20`, `System.Int32` at five of
   `{0x58,0x5C,0x60,0x68,0x6C,0x160}`, `UnityEngine.Vector3` at three of
   `{0x98,0xA4,0xB0,0xF0}`. If the dumped names *did* survive the update they
   short-circuit the match and re-resolve every offset by name, so a build that
   only shifts the layout keeps working without a recompile.
3. **Finds the entity list by walking static field data.** Every class in
   `Assembly-CSharp` with an allocated static block is scanned for a field
   typed `T[]` or `List<T>` where `T` is the entity class; failing that, a
   second pass follows `static Manager instance` → instance field. No
   hardcoded manager name.
4. Pulls `Camera.main`, `worldToCameraMatrix` and `projectionMatrix` through
   `il2cpp_runtime_invoke`, multiplies them, and projects feet and head
   (`+1.80` on Y) itself. The overlay never calls into managed code.
5. Publishes a mutex-guarded snapshot. The UI thread only reads floats.

Local player is identified by `NMAMove @ 0x158` being non-null — it is the one
entity whose Unity component references are populated. Entities with the
`0x28` "not replicated" flag, or a zeroed position, are skipped.

Field map lives in `native/offsets.h`.

## Build

```sh
export TOOLS=/path/with/android-ndk-r26d,android-14,android-34
./build.sh "BLOCKPOST SERVER_1.00f3.apk" BLOCKPOST-ESP.apk
```

`build.sh` compiles `out/libesp.so` and `out/payload.dex`, then hands off to
`apply.sh`. Both prebuilt artifacts are committed, so patching a fresh APK
needs no NDK and no JDK compiler:

```sh
./apply.sh "BLOCKPOST SERVER_1.00f3.apk"
```

Requires `zipalign`, `apksigner`, `keytool`, `python3`, `zip`/`unzip`. A
throwaway keystore is generated into `out/` on first run.

## Install

Uninstall the store build first — different signing key, so it will not upgrade
in place. Then `adb install BLOCKPOST-ESP.apk` or sideload it.

Tap the green **P** handle to open the menu; drag it anywhere. `adb logcat -s
bpesp` shows discovery progress: the resolved entity class name, its size, the
offsets it settled on, and which static field the list came from.

## Notes

- arm64-v8a only. The APK ships no other ABI, so there is nothing else to build.
- The status line (top-left, toggleable) reports `waiting for il2cpp` →
  `scanning` → `live`. Sitting on "no entity list yet" means the container scan
  found nothing — the list is usually only allocated once you are actually in a
  match, so join one and it resolves itself.
- Offsets in `offsets.h` are 1.00f3. On a game update, run the fingerprint first
  (it is name-independent) and only fall back to re-dumping if the class layout
  itself moved.
