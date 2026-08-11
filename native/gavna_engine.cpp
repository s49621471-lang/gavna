// gavna - Snake.io (com.amelosinteractive.snake, Unity 2022.3 / IL2CPP arm64)
//
// Three kinds of change are applied to the game:
//
//   * constant-return patches over a resolved il2cpp method entry, switched by
//     the menu (unlocks, immortality);
//   * native replacements, where the method entry branches into a function in
//     this library instead (the ad entry points that have to run a callback);
//   * the snake-length writer, driven from an inline hook on
//     PlayerSnakeController::OnUpdate so it executes on Unity's main thread.
//
// Ads are not a menu option. Every ad entry point is neutralised as soon as the
// runtime comes up and stays that way.

#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include <atomic>
#include <vector>

#include "gavna_hook.h"
#include "gavna_il2cpp.h"
#include "gavna_log.h"

namespace gavna {
namespace {

// ---------------------------------------------------------------------------
// Feature / value identifiers - kept in sync with com.gavna.Native
// ---------------------------------------------------------------------------
enum Feature {
    kFeatureUnlockSkins = 0,
    kFeatureUnlockAccessories = 1,
    kFeatureImmortal = 2,
    kFeatureLength = 3,
    kFeatureCount = 4,

    // Not exposed to the menu: applied at startup and never switched off.
    kFeatureNoAds = 4,
    kFeatureSlots = 5,
};

enum Value {
    kValueLength = 0,
};

constexpr int kDefaultLength = 500;
constexpr int kMinLength = 2;
constexpr int kMaxLength = 50000;
constexpr int kMaxLengthStepPerFrame = 250;
constexpr int kMaxPatchWords = 4;

// ---------------------------------------------------------------------------
// Patch table
// ---------------------------------------------------------------------------
enum PatchKind {
    kRetVoid,     // ret
    kRetTrue,     // movz w0, #1 ; ret
    kRetFalse,    // movz w0, #0 ; ret
    kNativeShowInterstitial,       // -> HkShowInterstitialAd
    kNativeShowInterstitialClean,  // -> HkShowInterstitialAdCleanFlow
    kNativeShowDailyFreeCoinRv,    // -> HkShowDailyFreeCoinRewardedVideo
};

// One patched method entry. `patch_word` is a single instruction - either a
// bare `ret` or a branch into `stub` - so switching a feature is one atomic
// store in each direction and never leaves the function half rewritten.
struct Site {
    void* code = nullptr;
    uint32_t original = 0;
    uint32_t patch_word = 0;
    uint32_t* stub = nullptr;
    bool usable = false;
    bool applied = false;
};

struct Target {
    Feature feature;
    const char* name_space;
    const char* klass;
    const char* method;
    int param_count;  // -1 matches every overload
    PatchKind kind;

    std::vector<Site> sites;
    bool resolved = false;
};

// Forward declarations for the native replacements.
void HkShowInterstitialAd(void* thiz, int source, void* settings, void* completion, void* method);
void HkShowInterstitialAdCleanFlow(void* thiz, int source, void* completion, void* method);
void HkShowDailyFreeCoinRewardedVideo(void* thiz, void* completed, void* failed, void* method);

// Class names come straight from the shipped global-metadata.dat; the game is
// not obfuscated so every lookup below is a literal name match.
Target g_targets[] = {
    // ---- every snake skin unlocked --------------------------------------
    {kFeatureUnlockSkins, "SnakeIO", "LocalUser", "IsSkinUnlocked", 1, kRetTrue},
    {kFeatureUnlockSkins, "SnakeIO", "SkinHelper", "IsSkinUnlocked", -1, kRetTrue},

    // ---- every accessory unlocked ---------------------------------------
    {kFeatureUnlockAccessories, "SnakeIO", "LocalUser", "IsSkinAccessoryOwned", 1, kRetTrue},
    {kFeatureUnlockAccessories, "SnakeIO.Accessory", "SkinAccessoryController",
     "IsAccessoryUnlocked", -1, kRetTrue},
    {kFeatureUnlockAccessories, "SnakeIO.Accessory", "SkinAccessoryCore", "IsAccessoryUnlocked", -1,
     kRetTrue},
    {kFeatureUnlockAccessories, "SnakeIO.Accessory", "EmptySkinAccessoryController",
     "IsAccessoryUnlocked", -1, kRetTrue},
    {kFeatureUnlockAccessories, "SnakeIO.Accessory", "SkinAccessory", "IsUnlocked", 0, kRetTrue},
    {kFeatureUnlockAccessories, "SnakeIO.Accessory", "SkinAccessoryExtensions",
     "IsAccessoryUnlocked", 2, kRetTrue},

    // ---- immortality (player snake only, AI snakes keep dying) ----------
    {kFeatureImmortal, "SnakeIO", "PlayerSnakeController", "IsInvincible", 0, kRetTrue},
    {kFeatureImmortal, "SnakeIO", "PlayerSnakeController", "Die", 2, kRetVoid},
    {kFeatureImmortal, "SnakeIO", "PlayerSnakeController", "StartDeathProcess", 2, kRetVoid},

    // ---- no ads, ever ---------------------------------------------------
    // The mediation adapter is never built and no ad SDK is ever initialised,
    // so nothing is ever requested, cached or shown.
    {kFeatureNoAds, "", "AdsManager", "InitializeAdsAdapter", 0, kRetVoid},
    {kFeatureNoAds, "", "AdsManager", "InitializeSDK", 0, kRetVoid},
    {kFeatureNoAds, "", "AdsManager", "InitializeAdsSdk", 0, kRetVoid},
    {kFeatureNoAds, "", "AdsManager", "LoadAds", 0, kRetVoid},
    // Every "is there an ad to show" answer becomes no, which is a state the
    // game already handles - it is what happens when a fill fails.
    {kFeatureNoAds, "", "AdsManager", "IsInterstitialAdReady", 0, kRetFalse},
    {kFeatureNoAds, "", "AdsManager", "ShouldShowInterstitialAd", 0, kRetFalse},
    {kFeatureNoAds, "", "AdsManager", "ShouldShowInterstitialAdOnDeath", 1, kRetFalse},
    {kFeatureNoAds, "", "AdsManager", "IsRewardedAdAvailable", -1, kRetFalse},
    {kFeatureNoAds, "", "AdsManager", "IsAdShown", 0, kRetFalse},
    // Rewarded video entry points that carry no callback simply do nothing.
    {kFeatureNoAds, "", "AdsManager", "ShowRewardedAd", 0, kRetVoid},
    {kFeatureNoAds, "", "AdsManager", "ShowRewardedVideo", 0, kRetVoid},
    {kFeatureNoAds, "", "AdsManager", "ShowIncentivizedButtonRewardedVideo", 2, kRetVoid},
    {kFeatureNoAds, "", "AdsManager", "ShowLiveEventRewardedVideo", 1, kRetVoid},
    // These three hand a continuation to the ad; dropping it would leave the
    // caller waiting forever, so they are replaced rather than stubbed and the
    // continuation is run straight away.
    {kFeatureNoAds, "", "AdsManager", "ShowInterstitialAd", 3, kNativeShowInterstitial},
    {kFeatureNoAds, "", "AdsManager", "ShowInterstitialAdCleanFlow", 2,
     kNativeShowInterstitialClean},
    {kFeatureNoAds, "", "AdsManager", "ShowDailyFreeCoinRewardedVideo", 2,
     kNativeShowDailyFreeCoinRv},
};

constexpr size_t kTargetCount = sizeof(g_targets) / sizeof(g_targets[0]);

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------
pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
std::atomic<bool> g_engine_ready{false};
std::atomic<bool> g_init_started{false};
std::atomic<bool> g_feature_on[kFeatureSlots];  // static storage: zero-initialised
std::atomic<int> g_length_target{kDefaultLength};

il2cpp::Method g_set_length;
il2cpp::Method g_get_decrypted;
int32_t g_length_field_offset = -1;

using OnUpdateFn = void (*)(void*, float, void*);
using SetLengthFn = void (*)(void*, int, void*);
using GetDecryptedFn = int (*)(void*, void*);
using InvokeFn = void (*)(void*, void*);

OnUpdateFn g_orig_on_update = nullptr;

// ---------------------------------------------------------------------------
// Calling a managed delegate back
// ---------------------------------------------------------------------------

// Runs a System.Action. The delegate's own class is read off the object, so
// this works for any Action-shaped delegate the game hands us.
void InvokeAction(void* action) {
    if (action == nullptr || !il2cpp::ready()) return;
    void* klass = *reinterpret_cast<void**>(action);  // Il2CppObject::klass
    if (klass == nullptr) return;
    il2cpp::Method invoke = il2cpp::FindMethod(klass, "Invoke", 0);
    if (!invoke.valid()) {
        LOGW("delegate %p has no Invoke()", action);
        return;
    }
    reinterpret_cast<InvokeFn>(invoke.code)(action, invoke.info);
}

void HkShowInterstitialAd(void* thiz, int source, void* settings, void* completion, void* method) {
    (void)thiz;
    (void)source;
    (void)settings;
    (void)method;
    InvokeAction(completion);
}

void HkShowInterstitialAdCleanFlow(void* thiz, int source, void* completion, void* method) {
    (void)thiz;
    (void)source;
    (void)method;
    InvokeAction(completion);
}

void HkShowDailyFreeCoinRewardedVideo(void* thiz, void* completed, void* failed, void* method) {
    (void)thiz;
    (void)failed;
    (void)method;
    // No ad plays, but the reward the player asked for still lands.
    InvokeAction(completed);
}

// ---------------------------------------------------------------------------
// Patching
// ---------------------------------------------------------------------------

void* NativeReplacementFor(PatchKind kind) {
    switch (kind) {
        case kNativeShowInterstitial:
            return reinterpret_cast<void*>(&HkShowInterstitialAd);
        case kNativeShowInterstitialClean:
            return reinterpret_cast<void*>(&HkShowInterstitialAdCleanFlow);
        case kNativeShowDailyFreeCoinRv:
            return reinterpret_cast<void*>(&HkShowDailyFreeCoinRewardedVideo);
        default:
            return nullptr;
    }
}

size_t BuildStubWords(PatchKind kind, uint32_t* out) {
    switch (kind) {
        case kRetVoid:
            return 0;  // no stub needed, the patch word is the ret itself
        case kRetTrue:
            out[0] = EncMovzW(0, 1);
            out[1] = EncRet();
            return 2;
        case kRetFalse:
            out[0] = EncMovzW(0, 0);
            out[1] = EncRet();
            return 2;
        default: {
            // Absolute jump into this library: the arguments stay untouched in
            // x0-x7 and x30 still points at the game's caller, so the
            // replacement returns straight back to it.
            void* fn = NativeReplacementFor(kind);
            if (fn == nullptr) return 0;
            uintptr_t dest = reinterpret_cast<uintptr_t>(fn);
            out[0] = 0x58000051u;  // ldr x17, [pc, #8]
            out[1] = 0xD61F0220u;  // br  x17
            out[2] = static_cast<uint32_t>(dest & 0xFFFFFFFFu);
            out[3] = static_cast<uint32_t>((dest >> 32) & 0xFFFFFFFFu);
            return 4;
        }
    }
}

// Builds the stub and the single instruction that jumps to it. Done once, at
// resolve time, so toggling later cannot fail half way.
bool PrepareSite(Site& site, PatchKind kind) {
    if (site.code == nullptr) return false;
    site.original = *static_cast<uint32_t*>(site.code);

    uint32_t words[kMaxPatchWords] = {0, 0, 0, 0};
    size_t count = BuildStubWords(kind, words);
    if (count == 0) {
        if (kind != kRetVoid) return false;  // a native replacement went missing
        site.patch_word = EncRet();
        site.usable = true;
        return true;
    }

    site.stub = AllocStub(words, count, reinterpret_cast<uintptr_t>(site.code));
    if (site.stub == nullptr) return false;

    site.patch_word = EncBranch(reinterpret_cast<uintptr_t>(site.code),
                                reinterpret_cast<uintptr_t>(site.stub));
    if (site.patch_word == 0) {
        LOGE("stub %p is out of branch range of %p", site.stub, site.code);
        return false;
    }
    site.usable = true;
    return true;
}

bool ApplySite(Site& site) {
    if (!site.usable) return false;
    if (site.applied) return true;
    if (!WriteWord(site.code, site.patch_word)) return false;
    site.applied = true;
    return true;
}

bool RestoreSite(Site& site) {
    if (!site.usable || !site.applied) return true;
    if (!WriteWord(site.code, site.original)) return false;
    site.applied = false;
    return true;
}

void ResolveTargets() {
    int resolved = 0;
    int missing = 0;
    // Identical bodies can be folded onto one address by the linker; patching
    // the same entry twice would make the two toggles fight over it.
    std::vector<void*> seen;
    for (size_t i = 0; i < kTargetCount; ++i) {
        Target& t = g_targets[i];
        void* klass = il2cpp::FindClass(t.name_space, t.klass);
        if (klass == nullptr && t.name_space != nullptr) {
            // Second chance without a namespace in case the type moved.
            klass = il2cpp::FindClass(nullptr, t.klass);
        }
        if (klass == nullptr) {
            LOGW("class not found: %s", t.klass);
            ++missing;
            continue;
        }
        std::vector<il2cpp::Method> methods = il2cpp::FindMethods(klass, t.method, t.param_count);
        if (methods.empty()) {
            LOGW("method not found: %s::%s/%d", t.klass, t.method, t.param_count);
            ++missing;
            continue;
        }
        for (const il2cpp::Method& m : methods) {
            bool duplicate = false;
            for (void* other : seen) {
                if (other == m.code) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                LOGW("skipping %s::%s, entry %p is already patched", t.klass, t.method, m.code);
                continue;
            }

            Site s;
            s.code = m.code;
            if (!PrepareSite(s, t.kind)) {
                LOGE("cannot prepare patch for %s::%s", t.klass, t.method);
                ++missing;
                continue;
            }
            seen.push_back(s.code);
            t.sites.push_back(s);
            ++resolved;
            LOGI("resolved %s::%s(%u args) -> %p stub %p", t.klass, t.method, m.param_count, m.code,
                 s.stub);
        }
        t.resolved = !t.sites.empty();
    }
    LOGI("resolved %d patch sites, %d targets missing", resolved, missing);
}

// ---------------------------------------------------------------------------
// Snake length
// ---------------------------------------------------------------------------

// The field offset comes from the runtime, but a nonsense value would turn into
// an out-of-bounds read on the snake object, so it is range checked once.
constexpr int32_t kMaxFieldOffset = 0x10000;

int ReadCurrentLength(void* snake) {
    if (snake == nullptr) return -1;
    if (g_length_field_offset <= 0 || g_length_field_offset >= kMaxFieldOffset) return -1;
    if (!g_get_decrypted.valid()) return -1;
    void* encrypted = static_cast<uint8_t*>(snake) + g_length_field_offset;
    return reinterpret_cast<GetDecryptedFn>(g_get_decrypted.code)(encrypted, g_get_decrypted.info);
}

void ApplyLengthTick(void* snake) {
    if (!g_feature_on[kFeatureLength].load()) return;
    if (snake == nullptr || !g_set_length.valid()) return;

    int target = g_length_target.load();
    if (target < kMinLength) target = kMinLength;
    if (target > kMaxLength) target = kMaxLength;

    int next = target;
    int current = ReadCurrentLength(snake);
    if (current > 0) {
        int delta = target - current;
        if (delta == 0) return;
        // Grow/shrink in bounded steps: one huge jump in a single frame stalls
        // the body-part pool hard enough to look like a freeze.
        if (delta > kMaxLengthStepPerFrame) delta = kMaxLengthStepPerFrame;
        if (delta < -kMaxLengthStepPerFrame) delta = -kMaxLengthStepPerFrame;
        next = current + delta;
    }
    if (next < kMinLength) next = kMinLength;

    reinterpret_cast<SetLengthFn>(g_set_length.code)(snake, next, g_set_length.info);
}

// Runs on Unity's main thread, once per frame, for the local player's snake.
void HookedOnUpdate(void* thiz, float delta_time, void* method) {
    OnUpdateFn original = g_orig_on_update;
    if (original != nullptr) original(thiz, delta_time, method);
    ApplyLengthTick(thiz);
}

void SetupLengthFeature() {
    void* snake_controller = il2cpp::FindClass("SnakeIO", "SnakeController");
    void* player_controller = il2cpp::FindClass("SnakeIO", "PlayerSnakeController");
    void* encrypted_int = il2cpp::FindClass("Kooapps.Security", "EncryptedInt");

    if (snake_controller == nullptr || player_controller == nullptr) {
        LOGE("length: snake controller classes missing");
        return;
    }

    g_set_length = il2cpp::FindMethod(snake_controller, "SetLength", 1);
    if (!g_set_length.valid()) {
        LOGE("length: SnakeController::SetLength not found");
        return;
    }

    g_length_field_offset = il2cpp::FieldOffset(snake_controller, "_Length");
    if (g_length_field_offset <= 0 || g_length_field_offset >= kMaxFieldOffset) {
        LOGW("length: implausible _Length offset %d", g_length_field_offset);
        g_length_field_offset = -1;
    }
    if (encrypted_int != nullptr) {
        g_get_decrypted = il2cpp::FindMethod(encrypted_int, "GetDecrypted", 0);
    }
    if (g_length_field_offset < 0 || !g_get_decrypted.valid()) {
        // Without the current value we cannot ramp; SetLength still works, it
        // just applies the whole delta at once.
        LOGW("length: cannot read current value, stepping disabled");
    }

    il2cpp::Method on_update = il2cpp::FindMethod(player_controller, "OnUpdate", 1);
    if (!on_update.valid()) {
        LOGE("length: PlayerSnakeController::OnUpdate not found");
        return;
    }

    // The trampoline is published straight into g_orig_on_update so the hook has
    // it the moment the redirect goes live.
    if (!InstallHook(on_update.code, reinterpret_cast<void*>(&HookedOnUpdate),
                     reinterpret_cast<void**>(&g_orig_on_update))) {
        LOGE("length: OnUpdate hook refused");
        return;
    }
    LOGI("length: ready (field offset 0x%X)", g_length_field_offset);
}

// ---------------------------------------------------------------------------
// Engine lifecycle
// ---------------------------------------------------------------------------

bool SetFeatureLocked(int feature, bool on) {
    if (feature < 0 || feature >= kFeatureSlots) return false;
    g_feature_on[feature].store(on);

    if (feature == kFeatureLength) return true;  // driven by the frame hook
    if (!g_engine_ready.load()) return true;     // will be applied on init

    bool ok = true;
    for (size_t i = 0; i < kTargetCount; ++i) {
        Target& t = g_targets[i];
        if (static_cast<int>(t.feature) != feature) continue;
        for (Site& s : t.sites) {
            ok &= on ? ApplySite(s) : RestoreSite(s);
        }
    }
    return ok;
}

// Re-applies whatever the user had toggled before the runtime came up, and
// switches the ad blocking on unconditionally.
void ApplyFeatures() {
    g_feature_on[kFeatureNoAds].store(true);
    for (size_t i = 0; i < kTargetCount; ++i) {
        Target& t = g_targets[i];
        if (!g_feature_on[t.feature].load()) continue;
        for (Site& s : t.sites) ApplySite(s);
    }
}

void* EngineThread(void*) {
    LOGI("engine thread started");
    if (!il2cpp::WaitUntilReady(240000)) {
        LOGE("il2cpp runtime never became usable");
        return nullptr;
    }
    il2cpp::AttachCurrentThread();

    pthread_mutex_lock(&g_mutex);
    ResolveTargets();
    SetupLengthFeature();
    ApplyFeatures();
    g_engine_ready.store(true);
    pthread_mutex_unlock(&g_mutex);

    LOGI("engine ready");
    return nullptr;
}

}  // namespace
}  // namespace gavna

// ---------------------------------------------------------------------------
// JNI surface consumed by com.gavna.Native
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT void JNICALL Java_com_gavna_Native_nativeInit(JNIEnv*, jclass) {
    using namespace gavna;

    bool expected = false;
    if (!g_init_started.compare_exchange_strong(expected, true)) {
        LOGI("nativeInit called twice - ignoring");
        return;
    }

    LOGI("gavna native starting (build %s %s)", __DATE__, __TIME__);

    pthread_t thread;
    if (pthread_create(&thread, nullptr, EngineThread, nullptr) != 0) {
        LOGE("cannot spawn engine thread");
        return;
    }
    pthread_detach(thread);
}

JNIEXPORT jboolean JNICALL Java_com_gavna_Native_nativeSetFeature(JNIEnv*, jclass, jint feature,
                                                                 jboolean on) {
    using namespace gavna;
    if (feature < 0 || feature >= kFeatureCount) return JNI_FALSE;  // menu features only
    pthread_mutex_lock(&g_mutex);
    bool ok = SetFeatureLocked(static_cast<int>(feature), on == JNI_TRUE);
    pthread_mutex_unlock(&g_mutex);
    LOGI("feature %d -> %s (%s)", feature, on ? "on" : "off", ok ? "ok" : "partial");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_gavna_Native_nativeSetValue(JNIEnv*, jclass, jint id,
                                                               jint value) {
    using namespace gavna;
    if (id != kValueLength) return JNI_FALSE;
    int v = value;
    if (v < kMinLength) v = kMinLength;
    if (v > kMaxLength) v = kMaxLength;
    g_length_target.store(v);
    LOGI("length target -> %d", v);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_gavna_Native_nativeOnPlayerResumed(JNIEnv*, jclass) {
    using namespace gavna;
    il2cpp::NotifyPlayerResumed();
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

}  // extern "C"
