#include "gavna_il2cpp.h"

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

#include <atomic>
#include <time.h>
#include <unistd.h>

#include "gavna_log.h"

namespace gavna {
namespace il2cpp {
namespace {

struct Api {
    void* (*domain_get)() = nullptr;
    void** (*domain_get_assemblies)(void* domain, size_t* count) = nullptr;
    void* (*assembly_get_image)(void* assembly) = nullptr;
    const char* (*image_get_name)(void* image) = nullptr;
    void* (*class_from_name)(void* image, const char* ns, const char* name) = nullptr;
    void* (*class_get_methods)(void* klass, void** iter) = nullptr;
    void* (*class_get_field_from_name)(void* klass, const char* name) = nullptr;
    const char* (*class_get_name)(void* klass) = nullptr;
    size_t (*image_get_class_count)(void* image) = nullptr;
    void* (*image_get_class)(void* image, size_t index) = nullptr;
    const char* (*method_get_name)(void* method) = nullptr;
    uint32_t (*method_get_param_count)(void* method) = nullptr;
    size_t (*field_get_offset)(void* field) = nullptr;
    void* (*thread_attach)(void* domain) = nullptr;
};

Api g_api;
void* g_handle = nullptr;
void* g_domain = nullptr;
bool g_ready = false;
uintptr_t g_base = 0;
size_t g_size = 0;

template <typename T>
bool Bind(T& fn, const char* name) {
    fn = reinterpret_cast<T>(dlsym(g_handle, name));
    if (fn == nullptr) {
        LOGE("il2cpp export missing: %s", name);
        return false;
    }
    return true;
}

bool BindAll() {
    bool ok = true;
    ok &= Bind(g_api.domain_get, "il2cpp_domain_get");
    ok &= Bind(g_api.domain_get_assemblies, "il2cpp_domain_get_assemblies");
    ok &= Bind(g_api.assembly_get_image, "il2cpp_assembly_get_image");
    ok &= Bind(g_api.image_get_name, "il2cpp_image_get_name");
    ok &= Bind(g_api.class_from_name, "il2cpp_class_from_name");
    ok &= Bind(g_api.class_get_methods, "il2cpp_class_get_methods");
    ok &= Bind(g_api.class_get_field_from_name, "il2cpp_class_get_field_from_name");
    ok &= Bind(g_api.class_get_name, "il2cpp_class_get_name");
    ok &= Bind(g_api.image_get_class_count, "il2cpp_image_get_class_count");
    ok &= Bind(g_api.image_get_class, "il2cpp_image_get_class");
    ok &= Bind(g_api.method_get_name, "il2cpp_method_get_name");
    ok &= Bind(g_api.method_get_param_count, "il2cpp_method_get_param_count");
    ok &= Bind(g_api.field_get_offset, "il2cpp_field_get_offset");
    ok &= Bind(g_api.thread_attach, "il2cpp_thread_attach");
    return ok;
}

// Reads /proc/self/maps to find where libil2cpp.so is mapped so that every
// method pointer can be range-checked before we write to it.
void ResolveModuleRange() {
    FILE* f = fopen("/proc/self/maps", "re");
    if (f == nullptr) return;
    char line[512];
    uintptr_t low = 0, high = 0;
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strstr(line, "libil2cpp.so") == nullptr) continue;
        unsigned long start = 0, end = 0;
        if (sscanf(line, "%lx-%lx", &start, &end) != 2) continue;
        if (low == 0 || static_cast<uintptr_t>(start) < low) low = static_cast<uintptr_t>(start);
        if (static_cast<uintptr_t>(end) > high) high = static_cast<uintptr_t>(end);
    }
    fclose(f);
    if (low != 0 && high > low) {
        g_base = low;
        g_size = high - low;
    }
}

void SleepMs(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = static_cast<long>(ms % 1000) * 1000000L;
    nanosleep(&ts, nullptr);
}

int64_t NowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

// The sentinel class proves the game assembly (not just mscorlib) is loaded.
constexpr const char* kSentinelNamespace = "SnakeIO";
constexpr const char* kSentinelClass = "LocalUser";

// Once the domain pointer appears it is still being filled in by il2cpp_init on
// the main thread, so give the runtime a moment before touching anything.
constexpr int kSettleMs = 3000;
// Fallback path: how long to wait after the game activity resumes before
// assuming the runtime must be up.
constexpr int kResumeFallbackMs = 15000;

void** g_domain_slot = nullptr;
std::atomic<bool> g_player_resumed{false};
std::atomic<int64_t> g_resumed_at{0};

uint32_t* FollowThunk(uint32_t* code) {
    // The exported il2cpp_* entries are one-instruction tail jumps into the
    // real implementation.
    if (code == nullptr) return nullptr;
    uint32_t insn = *code;
    if ((insn & 0xFC000000u) != 0x14000000u) return code;  // not a B
    int32_t imm26 = static_cast<int32_t>(insn & 0x03FFFFFFu);
    if (imm26 & 0x02000000) imm26 |= static_cast<int32_t>(0xFC000000u);  // sign extend
    return code + imm26;
}

// Reads `adrp xN, page` followed by `ldr x?, [xN, #off]` out of the prologue and
// returns page + off - the address of the runtime's domain pointer.
void** DecodeDomainSlot(uint32_t* code) {
    if (code == nullptr) return nullptr;

    int page_reg = -1;
    uintptr_t page = 0;
    for (int i = 0; i < 12; ++i) {
        uint32_t insn = code[i];

        if ((insn & 0x9F000000u) == 0x90000000u) {  // ADRP
            uint32_t immlo = (insn >> 29) & 0x3u;
            uint32_t immhi = (insn >> 5) & 0x7FFFFu;
            int64_t imm = static_cast<int64_t>((immhi << 2) | immlo);
            if (imm & (1LL << 20)) imm -= (1LL << 21);  // sign extend 21 bits
            page_reg = static_cast<int>(insn & 31u);
            page = (reinterpret_cast<uintptr_t>(code + i) & ~static_cast<uintptr_t>(0xFFF))
                   + static_cast<uintptr_t>(imm << 12);
            continue;
        }

        // LDR Xt, [Xn, #imm12] - 64-bit unsigned offset form
        if (page_reg >= 0 && (insn & 0xFFC00000u) == 0xF9400000u) {
            int base = static_cast<int>((insn >> 5) & 31u);
            if (base != page_reg) continue;
            uintptr_t offset = static_cast<uintptr_t>((insn >> 10) & 0xFFFu) * 8u;
            return reinterpret_cast<void**>(page + offset);
        }

        if (insn == 0xD65F03C0u) break;  // ret: prologue is over
    }
    return nullptr;
}

}  // namespace

bool ready() { return g_ready; }
uintptr_t module_base() { return g_base; }
size_t module_size() { return g_size; }

bool PointerLooksLikeCode(const void* p) {
    if (p == nullptr) return false;
    uintptr_t v = reinterpret_cast<uintptr_t>(p);
    if ((v & 3u) != 0) return false;
    if (g_base == 0 || g_size == 0) return true;  // range unknown: do not block
    return v >= g_base && v < g_base + g_size;
}

void NotifyPlayerResumed() {
    if (!g_player_resumed.exchange(true)) {
        g_resumed_at.store(NowMs());
    }
}

void** domain_slot() { return g_domain_slot; }

namespace {

// Blocks until the runtime has built its domain, without calling into it.
bool WaitForRuntimeInit(int timeout_ms, int& waited) {
    g_domain_slot = DecodeDomainSlot(
        FollowThunk(reinterpret_cast<uint32_t*>(dlsym(g_handle, "il2cpp_domain_get"))));

    if (g_domain_slot != nullptr) {
        uintptr_t slot = reinterpret_cast<uintptr_t>(g_domain_slot);
        if (g_base != 0 && (slot < g_base || slot >= g_base + g_size)) {
            LOGW("decoded domain slot %p is outside libil2cpp, ignoring", g_domain_slot);
            g_domain_slot = nullptr;
        }
    }

    if (g_domain_slot != nullptr) {
        LOGI("domain slot at %p, polling for runtime init", g_domain_slot);
        while (waited < timeout_ms) {
            if (*g_domain_slot != nullptr) {
                // The slot is stored part way through construction, so let
                // il2cpp_init finish before anything else touches the runtime.
                LOGI("domain appeared at %p, settling for %d ms", *g_domain_slot, kSettleMs);
                SleepMs(kSettleMs);
                return true;
            }
            SleepMs(100);
            waited += 100;
        }
        LOGE("domain never appeared after %d ms", waited);
        return false;
    }

    // Could not decode the prologue: fall back to waiting for the game window
    // to come up and then giving Unity a generous head start.
    LOGW("domain slot not decodable, falling back to the resume signal");
    while (waited < timeout_ms) {
        if (g_player_resumed.load() && NowMs() - g_resumed_at.load() >= kResumeFallbackMs) {
            return true;
        }
        SleepMs(250);
        waited += 250;
    }
    LOGE("resume fallback never satisfied after %d ms", waited);
    return false;
}

}  // namespace

bool WaitUntilReady(int timeout_ms) {
    if (g_ready) return true;

    int waited = 0;
    while (g_handle == nullptr && waited < timeout_ms) {
        // RTLD_NOLOAD only reports a library the process already mapped, so this
        // never pulls in a second copy of the runtime.
        g_handle = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
        if (g_handle == nullptr) {
            SleepMs(200);
            waited += 200;
        }
    }
    if (g_handle == nullptr) {
        LOGE("libil2cpp.so never appeared after %d ms", waited);
        return false;
    }
    if (!BindAll()) return false;

    ResolveModuleRange();
    LOGI("libil2cpp mapped at 0x%zx size 0x%zx", g_base, g_size);

    if (!WaitForRuntimeInit(timeout_ms, waited)) return false;

    while (waited < timeout_ms) {
        // Prefer reading the slot over calling the export: the export's null
        // branch is the lazy-construct path that crashed before, and there is no
        // reason to ever go near it again.
        g_domain = (g_domain_slot != nullptr) ? *g_domain_slot : g_api.domain_get();
        if (g_domain != nullptr) {
            size_t count = 0;
            void** assemblies = g_api.domain_get_assemblies(g_domain, &count);
            if (assemblies != nullptr && count > 0) {
                AttachCurrentThread();
                if (FindClass(kSentinelNamespace, kSentinelClass) != nullptr) {
                    g_ready = true;
                    LOGI("il2cpp ready: domain=%p assemblies=%zu base=0x%zx size=0x%zx", g_domain,
                         count, g_base, g_size);
                    return true;
                }
            }
        }
        SleepMs(250);
        waited += 250;
    }
    LOGE("il2cpp domain/assemblies not ready after %d ms", waited);
    return false;
}

void* AttachCurrentThread() {
    // Attaching the same thread twice registers a second thread object with the
    // runtime, so remember what we already did.
    static thread_local void* attached = nullptr;
    if (attached != nullptr) return attached;
    if (g_api.thread_attach == nullptr || g_domain == nullptr) return nullptr;
    attached = g_api.thread_attach(g_domain);
    return attached;
}

void* FindClass(const char* name_space, const char* name) {
    if (g_domain == nullptr || name == nullptr) return nullptr;
    size_t count = 0;
    void** assemblies = g_api.domain_get_assemblies(g_domain, &count);
    if (assemblies == nullptr) return nullptr;

    if (name_space != nullptr) {
        for (size_t i = 0; i < count; ++i) {
            void* image = g_api.assembly_get_image(assemblies[i]);
            if (image == nullptr) continue;
            void* klass = g_api.class_from_name(image, name_space, name);
            if (klass != nullptr) return klass;
        }
        return nullptr;
    }

    // Namespace unknown: walk every type table once and match on the name.
    for (size_t i = 0; i < count; ++i) {
        void* image = g_api.assembly_get_image(assemblies[i]);
        if (image == nullptr) continue;
        size_t classes = g_api.image_get_class_count(image);
        for (size_t c = 0; c < classes; ++c) {
            void* klass = g_api.image_get_class(image, c);
            if (klass == nullptr) continue;
            const char* n = g_api.class_get_name(klass);
            if (n != nullptr && strcmp(n, name) == 0) return klass;
        }
    }
    return nullptr;
}

std::vector<Method> FindMethods(void* klass, const char* name, int param_count) {
    std::vector<Method> out;
    if (klass == nullptr || name == nullptr) return out;

    // il2cpp_class_get_methods performs the structural Class::Init itself. We
    // deliberately do not call il2cpp_runtime_class_init here: that would run the
    // managed static constructor from a background thread.
    void* iter = nullptr;
    while (void* method = g_api.class_get_methods(klass, &iter)) {
        const char* mname = g_api.method_get_name(method);
        if (mname == nullptr || strcmp(mname, name) != 0) continue;
        uint32_t params = g_api.method_get_param_count(method);
        if (param_count >= 0 && params != static_cast<uint32_t>(param_count)) continue;

        Method m;
        m.info = method;
        m.name = mname;
        m.param_count = params;
        m.code = *reinterpret_cast<void**>(method);  // MethodInfo::methodPointer
        if (!PointerLooksLikeCode(m.code)) {
            LOGW("method %s has implausible code pointer %p - skipped", mname, m.code);
            continue;
        }
        out.push_back(m);
    }
    return out;
}

Method FindMethod(void* klass, const char* name, int param_count) {
    std::vector<Method> all = FindMethods(klass, name, param_count);
    if (all.empty()) return Method();
    return all.front();
}

int32_t FieldOffset(void* klass, const char* name) {
    if (klass == nullptr || name == nullptr) return -1;
    void* field = g_api.class_get_field_from_name(klass, name);
    if (field == nullptr) return -1;
    return static_cast<int32_t>(g_api.field_get_offset(field));
}

const char* ClassName(void* klass) {
    if (klass == nullptr || g_api.class_get_name == nullptr) return "<null>";
    const char* n = g_api.class_get_name(klass);
    return n != nullptr ? n : "<null>";
}

}  // namespace il2cpp
}  // namespace gavna
