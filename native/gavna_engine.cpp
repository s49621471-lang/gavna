// gavna - Snake.io (com.amelosinteractive.snake, Unity 2022.3 / IL2CPP arm64)
//
// Everything the menu can switch on is either a constant-return patch written
// over a resolved il2cpp method entry, or the snake-length writer that runs from
// an inline hook on PlayerSnakeController::OnUpdate so it executes on Unity's
// main thread.

#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include <atomic>
#include <string>
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
    kFeatureCoins = 0,
    kFeatureUnlockSkins = 1,
    kFeatureUnlockAccessories = 2,
    kFeatureImmortal = 3,
    kFeatureLength = 4,
    kFeatureCount = 5,
};

enum Value {
    kValueCoinAmount = 0,
    kValueLength = 1,
};

constexpr int kDefaultCoinAmount = 999000000;
constexpr int kDefaultLength = 500;
constexpr int kMinLength = 2;
constexpr int kMaxLength = 20000;
constexpr int kMaxLengthStepPerFrame = 250;
constexpr int kMaxPatchWords = 4;

// ---------------------------------------------------------------------------
// Patch table
// ---------------------------------------------------------------------------
enum PatchKind {
    kRetVoid,         // ret
    kRetTrue,         // movz w0, #1 ; ret
    kRetFalse,        // movz w0, #0 ; ret
    kRetInt,          // ldr w0, [pc, #8] ; ret ; .word value
    kRetTrueOutNull,  // str xzr, [x4] ; movz w0, #1 ; ret
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

// Class names come straight from the shipped global-metadata.dat; the game is
// not obfuscated so every lookup below is a literal name match.
Target g_targets[] = {
    // ---- unlimited coins / gems / tickets -------------------------------
    {kFeatureCoins, "SnakeIO", "LocalUser", "GetBalance", 1, kRetInt},
    {kFeatureCoins, "SnakeIO", "LocalUser", "get_CoinBalance", 0, kRetInt},
    {kFeatureCoins, "SnakeIO", "LocalUser", "get_GemBalance", 0, kRetInt},
    {kFeatureCoins, "SnakeIO", "LocalUser", "get_TicketBalance", 0, kRetInt},
    {kFeatureCoins, "SnakeIO", "LocalUser", "HasNoCurrency", 0, kRetFalse},
    {kFeatureCoins, "SnakeIO", "LocalUser", "ConsumeBalance", 2, kRetVoid},
    {kFeatureCoins, "SnakeIO", "LocalUser", "ConsumeCoin", 1, kRetVoid},
    {kFeatureCoins, "SnakeIO", "LocalUser", "ConsumeGems", 1, kRetVoid},
    {kFeatureCoins, "SnakeIO", "LocalUser", "ConsumeTickets", 1, kRetVoid},
    {kFeatureCoins, "SnakeIO", "CoreCurrencyController", "GetCurrencyBalance", 1, kRetInt},
    {kFeatureCoins, "SnakeIO", "CoreCurrencyController", "CanConsumeCurrency", 2, kRetTrue},
    {kFeatureCoins, "SnakeIO", "CoreCurrencyController", "ConsumeCurrency", 2, kRetTrue},
    {kFeatureCoins, "SnakeIO", "CurrencyController", "GetBalance", 0, kRetInt},
    {kFeatureCoins, "SnakeIO", "CurrencyController", "Consume", 1, kRetVoid},
    // Balance validators run before a purchase clears. Each step writes its
    // error message through an out-parameter in x4, so the patch nulls that out
    // before returning success - leaving it untouched would hand the caller an
    // uninitialised stack slot.
    {kFeatureCoins, "SnakeIO", "LocalUserCurrencyBalanceValidationStep", "HasEnoughCurrencyForType",
     4, kRetTrueOutNull},
    {kFeatureCoins, "SnakeIO", "LocalUserCurrencyChecksumValidationStep", "HasEnoughCurrencyForType",
     4, kRetTrueOutNull},
    {kFeatureCoins, "SnakeIO", "TransactionDataValidationStep", "HasEnoughCurrencyForType", 4,
     kRetTrueOutNull},
    {kFeatureCoins, "SnakeIO", "TransactionIncrementalHashValidationStep",
     "HasEnoughCurrencyForType", 4, kRetTrueOutNull},

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
};

constexpr size_t kTargetCount = sizeof(g_targets) / sizeof(g_targets[0]);

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------
pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
std::atomic<bool> g_engine_ready{false};
std::atomic<bool> g_engine_failed{false};
std::atomic<bool> g_init_started{false};
std::atomic<bool> g_feature_on[kFeatureCount];  // static storage: zero-initialised
std::atomic<int> g_coin_amount{kDefaultCoinAmount};
std::atomic<int> g_length_target{kDefaultLength};
std::atomic<int> g_resolved_sites{0};
std::atomic<int> g_missing_targets{0};
std::atomic<bool> g_hook_installed{false};
char g_status_detail[256] = "starting";

il2cpp::Method g_set_length;
il2cpp::Method g_get_decrypted;
int32_t g_length_field_offset = -1;

using OnUpdateFn = void (*)(void*, float, void*);
using SetLengthFn = void (*)(void*, int, void*);
using GetDecryptedFn = int (*)(void*, void*);

OnUpdateFn g_orig_on_update = nullptr;

void SetStatus(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_status_detail, sizeof(g_status_detail), fmt, ap);
    va_end(ap);
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
        case kRetInt:
            // The value is loaded from the word right after the stub, so the
            // menu can change it later with one data store instead of rewriting
            // live instructions.
            out[0] = EncLdrWLiteral8();
            out[1] = EncRet();
            out[2] = static_cast<uint32_t>(g_coin_amount.load());
            return 3;
        case kRetTrueOutNull:
            out[0] = EncStrXzr(4);  // out string = null before returning success
            out[1] = EncMovzW(0, 1);
            out[2] = EncRet();
            return 3;
    }
    return 0;
}

// Builds the stub and the single instruction that jumps to it. Done once, at
// resolve time, so toggling later cannot fail half way.
bool PrepareSite(Site& site, PatchKind kind) {
    if (site.code == nullptr) return false;
    site.original = *static_cast<uint32_t*>(site.code);

    uint32_t words[kMaxPatchWords] = {0, 0, 0, 0};
    size_t count = BuildStubWords(kind, words);
    if (count == 0) {
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
        if (klass == nullptr) {
            // Second chance without a namespace in case the type moved.
            klass = il2cpp::FindClass(nullptr, t.klass);
        }
        if (klass == nullptr) {
            LOGW("class not found: %s.%s", t.name_space, t.klass);
            ++missing;
            continue;
        }
        std::vector<il2cpp::Method> methods = il2cpp::FindMethods(klass, t.method, t.param_count);
        if (methods.empty()) {
            LOGW("method not found: %s.%s::%s/%d", t.name_space, t.klass, t.method, t.param_count);
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
                LOGE("cannot prepare patch for %s.%s::%s", t.name_space, t.klass, t.method);
                ++missing;
                continue;
            }
            seen.push_back(s.code);
            t.sites.push_back(s);
            ++resolved;
            LOGI("resolved %s.%s::%s(%u args) -> %p stub %p", t.name_space, t.klass, t.method,
                 m.param_count, m.code, s.stub);
        }
        t.resolved = !t.sites.empty();
    }
    g_resolved_sites.store(resolved);
    g_missing_targets.store(missing);
}

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
        // Grow/shrink in bounded steps: one 5000-part jump in a single frame
        // stalls the body-part pool hard enough to look like a freeze.
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
        LOGW("length: cannot read current value (offset=%d decrypt=%d), stepping disabled",
             g_length_field_offset, g_get_decrypted.valid() ? 1 : 0);
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
    g_hook_installed.store(true);
    LOGI("length: ready (field offset 0x%X)", g_length_field_offset);
}

// Re-applies whatever the user had toggled before the runtime came up.
void ApplyPendingFeatures() {
    for (size_t i = 0; i < kTargetCount; ++i) {
        Target& t = g_targets[i];
        if (!g_feature_on[t.feature].load()) continue;
        for (Site& s : t.sites) ApplySite(s);
    }
}

void* EngineThread(void*) {
    LOGI("engine thread started");
    if (!il2cpp::WaitUntilReady(240000)) {
        g_engine_failed.store(true);
        SetStatus("il2cpp runtime unavailable");
        return nullptr;
    }
    il2cpp::AttachCurrentThread();

    pthread_mutex_lock(&g_mutex);
    ResolveTargets();
    SetupLengthFeature();
    ApplyPendingFeatures();
    g_engine_ready.store(true);
    SetStatus("%d patch sites, %d missing, hook %s", g_resolved_sites.load(),
              g_missing_targets.load(), g_hook_installed.load() ? "ok" : "off");
    pthread_mutex_unlock(&g_mutex);

    LOGI("engine ready: %s", g_status_detail);
    return nullptr;
}

bool SetFeatureLocked(int feature, bool on) {
    if (feature < 0 || feature >= kFeatureCount) return false;
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

// Updates the literal each balance stub reads. This is plain data, so no
// instruction is ever rewritten while the game might be running it.
void RefreshCoinPatches() {
    if (!g_engine_ready.load()) return;
    uint32_t value = static_cast<uint32_t>(g_coin_amount.load());
    for (size_t i = 0; i < kTargetCount; ++i) {
        Target& t = g_targets[i];
        if (t.kind != kRetInt) continue;
        for (Site& s : t.sites) {
            if (s.stub != nullptr) s.stub[2] = value;
        }
    }
}

}  // namespace
}  // namespace gavna

// ---------------------------------------------------------------------------
// JNI surface consumed by com.gavna.Native
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT void JNICALL Java_com_gavna_Native_nativeInit(JNIEnv* env, jclass, jstring log_dir) {
    using namespace gavna;

    const char* dir = (log_dir != nullptr) ? env->GetStringUTFChars(log_dir, nullptr) : nullptr;
    LogInit(dir);
    if (dir != nullptr) env->ReleaseStringUTFChars(log_dir, dir);

    bool expected = false;
    if (!g_init_started.compare_exchange_strong(expected, true)) {
        LOGI("nativeInit called twice - ignoring");
        return;
    }

    InstallCrashHandler();
    LOGI("gavna native starting (build %s %s)", __DATE__, __TIME__);

    pthread_t thread;
    if (pthread_create(&thread, nullptr, EngineThread, nullptr) != 0) {
        LOGE("cannot spawn engine thread");
        g_engine_failed.store(true);
        SetStatus("engine thread failed to start");
        return;
    }
    pthread_detach(thread);
}

JNIEXPORT jboolean JNICALL Java_com_gavna_Native_nativeSetFeature(JNIEnv*, jclass, jint feature,
                                                                 jboolean on) {
    using namespace gavna;
    pthread_mutex_lock(&g_mutex);
    bool ok = SetFeatureLocked(static_cast<int>(feature), on == JNI_TRUE);
    pthread_mutex_unlock(&g_mutex);
    LOGI("feature %d -> %s (%s)", feature, on ? "on" : "off", ok ? "ok" : "partial");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_gavna_Native_nativeSetValue(JNIEnv*, jclass, jint id,
                                                               jint value) {
    using namespace gavna;
    switch (id) {
        case kValueCoinAmount: {
            int v = value;
            if (v < 0) v = 0;
            g_coin_amount.store(v);
            pthread_mutex_lock(&g_mutex);
            RefreshCoinPatches();
            pthread_mutex_unlock(&g_mutex);
            LOGI("coin amount -> %d", v);
            return JNI_TRUE;
        }
        case kValueLength: {
            int v = value;
            if (v < kMinLength) v = kMinLength;
            if (v > kMaxLength) v = kMaxLength;
            g_length_target.store(v);
            LOGI("length target -> %d", v);
            return JNI_TRUE;
        }
        default:
            return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL Java_com_gavna_Native_nativeStatus(JNIEnv* env, jclass) {
    using namespace gavna;
    char buf[512];
    const char* state = g_engine_failed.load()  ? "failed"
                        : g_engine_ready.load() ? "ready"
                                                : "waiting for il2cpp";
    snprintf(buf, sizeof(buf), "engine: %s\n%s\nlog: %s", state, g_status_detail, LogPath());
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL Java_com_gavna_Native_nativeLog(JNIEnv* env, jclass, jstring message) {
    using namespace gavna;
    if (message == nullptr) return;
    const char* text = env->GetStringUTFChars(message, nullptr);
    if (text != nullptr) {
        LogWrite("J", "%s", text);
        env->ReleaseStringUTFChars(message, text);
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

}  // extern "C"
