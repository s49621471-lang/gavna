# gavna

An in-game cheat menu for **Snake.io** (`com.amelosinteractive.snake` 2.2.160, Unity 2022.3 / IL2CPP, arm64-v8a),
patched into the game and shipped as **one universal APK** — not an APKS, not a split bundle.

Tap the white bar at the bottom of the screen to open the menu.

## Download

The signed APK is published as a release asset:
**[dist/SnakeIO-2.2.160-gavna.apk → Releases](../../releases/latest)**

`sha256 7274ee3dbc61954da929d473f0665f08eccd86c71a1bae726b9ecdf597d52973`

Uninstall the Play Store copy first — this build carries a different signing key.

## Features

| Menu | What it does |
|---|---|
| **coins → currency** | `LocalUser::GetBalance`, `get_CoinBalance`, `get_GemBalance`, `get_TicketBalance`, `CoreCurrencyController::GetCurrencyBalance` and `CurrencyController::GetBalance` return the configured amount. The consume paths (`ConsumeBalance`, `ConsumeCoin`, `ConsumeGems`, `ConsumeTickets`, `CurrencyController::Consume`) and the four `HasEnoughCurrencyForType` validation steps are short-circuited, so shop purchases clear and the balance never drops. Amount is a slider (1–999 million) plus presets. |
| **unlocks → skins** | `LocalUser::IsSkinUnlocked` and every `SkinHelper::IsSkinUnlocked` overload return true. |
| **unlocks → accessories** | `LocalUser::IsSkinAccessoryOwned`, `SkinAccessoryController`/`SkinAccessoryCore`/`EmptySkinAccessoryController::IsAccessoryUnlocked`, `SkinAccessory::IsUnlocked` and `SkinAccessoryExtensions::IsAccessoryUnlocked` return true. |
| **player → god** | `PlayerSnakeController::IsInvincible` returns true, `Die` and `StartDeathProcess` return immediately. Only the player snake is affected — the overrides live on `PlayerSnakeController`, so bots keep dying normally. |
| **player → snake** | Holds the snake at a chosen length (2–5000, presets included) by calling `SnakeController::SetLength` from a frame hook, ramping at most 250 body parts per frame so the pool never spikes. |
| **misc → engine** | Live engine status: how many patch sites resolved, whether the frame hook installed, where the log file is. |

## How it works

**Injection.** The `<application android:name>` attribute in the binary manifest is swapped from
`com.kooapps.unity.UnityApplication` to `com.gavna.snakeio.GavnaApplication`. Both names are exactly 34
characters, so the swap is a byte-for-byte substitution in the AXML string pool — no resource table is
re-encoded and every other entry in the APK is copied verbatim. `GavnaApplication` extends the game's own
Application class, so the original start-up path is untouched.

**Engine.** `libgavna.so` resolves classes and methods **by name** through the exported il2cpp API
(`il2cpp_class_from_name`, `il2cpp_class_get_methods`, …). The game is not obfuscated, so no hard-coded
offsets are involved and the mod survives a rebuild of the same version.

**Waiting for the runtime.** `libil2cpp.so` is mapped long before `il2cpp_init()` runs, and in that window
`il2cpp_domain_get()` is *not* a safe read: when the domain pointer is still null it falls into a
lazy-construct path that walks metadata globals which do not exist yet. So the readiness gate touches no
il2cpp API at all. The address of the runtime's domain pointer is decoded straight out of
`il2cpp_domain_get`'s own prologue — follow the one-instruction `B` thunk, then read the `adrp xN, page` /
`ldr x?, [xN, #off]` pair — and that slot is polled as plain memory until the runtime fills it in. Only then
does anything call into il2cpp. `tools/test_decode_domain_slot.cpp` checks the decoder against the exact
instruction words from the shipped binary. If the prologue ever stops being decodable, the engine falls back
to waiting for the game window plus a fixed delay rather than guessing.

**Patching.** Each switchable patch is a payload stub (`movz w0,#1; ret`, `ldr w0,[pc,#8]; ret; .word value`,
`str xzr,[x4]; movz w0,#1; ret`) allocated within ±128 MB of `libil2cpp.so`, plus **one** `B stub`
instruction written over the method entry. A 4-byte aligned store is single-copy atomic on AArch64, so a
thread executing that method while the toggle flips sees either the original instruction or the branch —
never a half-written sequence with a broken stack frame. Turning a feature off restores the saved word, also
in one store. Changing the coin amount writes only the literal the stub loads, so no live instruction is
ever rewritten.

**Frame hook.** Snake length has to run on Unity's main thread, so `PlayerSnakeController::OnUpdate` is
inline-hooked. The first four instructions are copied into a trampoline; before that happens each one is
decoded and rejected if it is PC-relative (B, BL, B.cond, CBZ/CBNZ, TBZ/TBNZ, ADR/ADRP, literal loads). If
any of them were, the hook is skipped and logged rather than installed — a missing feature instead of a
crash.

**Menu.** Pure framework views built in code — no resource ids are added, so `resources.arsc` is shipped
untouched. Two panel windows are attached to the activity's own token (`TYPE_APPLICATION_PANEL`), which
needs no overlay permission; if the window manager refuses them, the overlay falls back to the decor view.

## Logging

Everything lands in the game's own folder:

```
/sdcard/Android/data/com.amelosinteractive.snake/files/gavna/gavna.log
```

Native init, every resolved method and its address, every toggle, and Java exceptions all go there. Fatal
signals (SIGSEGV/SIGBUS/SIGILL/SIGFPE/SIGABRT/SIGTRAP) are caught, written with the faulting address and
thread id, then chained back to the handler that owned them so the game's own crash reporting still runs.
If the external directory is unavailable the log falls back to the app's internal `files/` directory.

## Building it yourself

```bash
./build.sh                 # downloads the bundle, or:
./build.sh path/to/snakeio.apks
```

Produces `dist/SnakeIO-2.2.160-gavna.apk`. The pipeline is:

1. `APKEditor m` merges `base.apk` + `split_config.arm64_v8a.apk` + the two `gpdeku` splits into one
   universal APK and strips the split metadata from the manifest.
2. `ndk-build` compiles `libgavna.so` for arm64-v8a.
3. `javac` + `d8` produce the menu dex, installed as the next free `classesN.dex`.
4. `tools/patch_manifest.py` swaps the application class in the binary manifest.
5. `tools/repack.py` rebuilds the zip, preserving each entry's original compression method.
6. `zipalign -P 16` then `apksigner` with v1 + v2 + v3 schemes.

Requirements: JDK 17+, Android SDK build-tools **35.0.0 or newer** (the d8 in 34.0.0 crashes on anonymous
classes emitted by a JDK 21 javac), NDK r27, `APKEditor.jar`, and `gdown` if you want the download step.

## Layout

```
native/     libgavna.so  - il2cpp binding, patcher, inline hook, logger
java/       the menu, the overlay windows, the JNI bridge, the injected Application
tools/      manifest patcher and repacker
build.sh    the whole pipeline
dist/parts/ the built APK, split to fit under GitHub's 100 MB blob limit
```
