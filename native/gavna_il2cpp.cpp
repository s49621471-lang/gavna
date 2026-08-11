#include "gavna_il2cpp.h"

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
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

// The sentinel class proves the game assembly (not just mscorlib) is loaded.
constexpr const char* kSentinelNamespace = "SnakeIO";
constexpr const char* kSentinelClass = "LocalUser";

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

bool WaitUntilReady(int timeout_ms) {
    if (g_ready) return true;

    int waited = 0;
    while (g_handle == nullptr && waited < timeout_ms) {
        g_handle = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
        if (g_handle == nullptr) {
            SleepMs(200);
            waited += 200;
        }
    }
    if (g_handle == nullptr) {
        // Fall back to a normal dlopen: on some devices RTLD_NOLOAD misses a
        // library the app loaded through System.loadLibrary.
        g_handle = dlopen("libil2cpp.so", RTLD_NOW);
    }
    if (g_handle == nullptr) {
        LOGE("libil2cpp.so never appeared after %d ms", waited);
        return false;
    }
    if (!BindAll()) return false;

    while (waited < timeout_ms) {
        g_domain = g_api.domain_get();
        if (g_domain != nullptr) {
            size_t count = 0;
            void** assemblies = g_api.domain_get_assemblies(g_domain, &count);
            if (assemblies != nullptr && count > 0) {
                AttachCurrentThread();
                if (FindClass(kSentinelNamespace, kSentinelClass) != nullptr) {
                    ResolveModuleRange();
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
