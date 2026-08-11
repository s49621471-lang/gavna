# gavna

An in-game cheat menu for **Snake.io** (`com.amelosinteractive.snake` 2.2.160, Unity 2022.3 / IL2CPP, arm64-v8a),
patched into the game and shipped as **one universal APK** — not an APKS, not a split bundle.

Tap the white bar at the bottom of the screen to open the menu.

## Download

The signed APK is published as a release asset:
**[SnakeIO-2.2.160-gavna.apk → Releases](../../releases/latest)**

`sha256 589f0573e1b181f3bbb7b99983f71bfac83a552826eba455c248a9d523dbcd26`

Uninstall the Play Store copy first — this build carries a different signing key.

## Ads

Removed outright, not as a switch. The mediation adapter is never constructed and no ad SDK is ever
initialised, so nothing is requested, cached or shown:

| Method | Becomes |
|---|---|
| `AdsManager::InitializeAdsAdapter`, `InitializeSDK`, `InitializeAdsSdk`, `LoadAds` | no-op |
| `IsInterstitialAdReady`, `ShouldShowInterstitialAd`, `ShouldShowInterstitialAdOnDeath`, `IsRewardedAdAvailable`, `IsAdShown` | `false` |
| `ShowRewardedAd`, `ShowRewardedVideo`, `ShowIncentivizedButtonRewardedVideo`, `ShowLiveEventRewardedVideo` | no-op |
| `ShowInterstitialAd`, `ShowInterstitialAdCleanFlow`, `ShowDailyFreeCoinRewardedVideo` | replaced natively — the continuation the game handed the ad is invoked immediately |

Those last three are the reason the whole thing is not just a pile of `ret`s. They pass a `System.Action`
that the ad system is supposed to call when the ad closes; dropping it would leave the game waiting on an
ad that never runs. Their entries branch into `libgavna.so` instead, which reads the delegate's own class
off the object, resolves its `Invoke`, and calls it. `ShowDailyFreeCoinRewardedVideo` runs the *completed*
handler, so the daily reward still lands with no video.

Everything reporting "no ad available" is a state the game already handles — it is what happens when a fill
fails.

## Menu

| Tab | What it does |
|---|---|
| **unlocks → skins** | `LocalUser::IsSkinUnlocked` and every `SkinHelper::IsSkinUnlocked` overload return true. |
| **unlocks → accessories** | `LocalUser::IsSkinAccessoryOwned`, `SkinAccessoryController`/`SkinAccessoryCore`/`EmptySkinAccessoryController::IsAccessoryUnlocked`, `SkinAccessory::IsUnlocked` and `SkinAccessoryExtensions::IsAccessoryUnlocked` return true. |
| **player → god** | `PlayerSnakeController::IsInvincible` returns true, `Die` and `StartDeathProcess` return immediately. Only the player snake is affected — the overrides live on `PlayerSnakeController`, so bots keep dying normally. |
| **player → snake** | Holds the snake at a chosen length, **2 – 50 000**, slider plus presets, by calling `SnakeController::SetLength` from a frame hook, ramping at most 250 body parts per frame so the pool never spikes. |

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
il2cpp API. The address of the runtime's domain pointer is decoded straight out of `il2cpp_domain_get`'s own
prologue — follow the one-instruction `B` thunk, then read the `adrp xN, page` / `ldr x?, [xN, #off]` pair —
and that slot is polled as plain memory until the runtime fills it in. Only then does anything call into
il2cpp. `tools/test_decode_domain_slot.cpp` checks the decoder against the exact instruction words from the
shipped binary. If the prologue ever stops being decodable, the engine falls back to waiting for the game
window plus a fixed delay rather than guessing.

**Patching.** Each patch is a payload stub — `movz w0,#1; ret`, or an absolute jump into `libgavna.so` for
the native replacements — allocated within ±128 MB of `libil2cpp.so`, plus **one** `B stub` instruction
written over the method entry. A 4-byte aligned store is single-copy atomic on AArch64, so a thread
executing that method while a toggle flips sees either the original instruction or the branch — never a
half-written sequence with a broken stack frame. Turning a feature off restores the saved word, also in one
store. A native replacement receives the arguments untouched in `x0`–`x7` with `x30` still pointing at the
game's caller, so it returns straight back.

**Frame hook.** Snake length has to run on Unity's main thread, so `PlayerSnakeController::OnUpdate` is
inline-hooked. The first four instructions are copied into a trampoline; before that happens each one is
decoded and rejected if it is PC-relative (B, BL, B.cond, CBZ/CBNZ, TBZ/TBNZ, ADR/ADRP, literal loads). If
any of them were, the hook is skipped and the feature disabled rather than installed — a missing toggle
instead of a crash.

**Menu.** Pure framework views built in code — no resource ids are added, so `resources.arsc` is shipped
untouched. Two panel windows are attached to the activity's own token (`TYPE_APPLICATION_PANEL`), which
needs no overlay permission; if the window manager refuses them, the overlay falls back to the decor view.

Nothing is written to disk. Diagnostics go to logcat under the `gavna` tag and nowhere else.

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
native/     libgavna.so  - il2cpp binding, patcher, inline hook
java/       the menu, the overlay windows, the JNI bridge, the injected Application
tools/      manifest patcher, repacker, decoder test
build.sh    the whole pipeline
dist/parts/ the built APK, split to fit under GitHub's 100 MB blob limit
```
