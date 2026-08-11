#include "il2cpp_api.h"
#include "log.h"

#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <ctime>

namespace zyrex {

Api       api{};
uintptr_t il2cpp_base = 0;
size_t    il2cpp_size = 0;

namespace {

void* g_handle = nullptr;
bool  g_missing_required = false;

void sleep_ms(int ms) {
    struct timespec ts { ms / 1000, (long)(ms % 1000) * 1000000L };
    nanosleep(&ts, nullptr);
}

// Locate the executable mapping of libil2cpp.so. The lowest mapped address for
// the file is the load base; we accumulate the span so pointer checks cover the
// whole image, not just .text.
bool find_module_range(const char* soname, uintptr_t& base, size_t& size) {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return false;

    uintptr_t lo = 0, hi = 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, soname)) continue;
        unsigned long s = 0, e = 0;
        if (sscanf(line, "%lx-%lx", &s, &e) != 2) continue;
        if (lo == 0 || (uintptr_t)s < lo) lo = (uintptr_t)s;
        if ((uintptr_t)e > hi) hi = (uintptr_t)e;
    }
    fclose(f);

    if (lo == 0 || hi <= lo) return false;
    base = lo;
    size = hi - lo;
    return true;
}

template <typename T>
void bind(T& slot, const char* name, bool required) {
    slot = reinterpret_cast<T>(dlsym(g_handle, name));
    if (!slot) {
        if (required) {
            LOGE("required symbol missing: %s", name);
            g_missing_required = true;
        } else {
            LOGW("optional symbol missing: %s", name);
        }
    }
}

} // namespace

bool in_il2cpp_module(const void* p) {
    if (!p || il2cpp_base == 0 || il2cpp_size == 0) return false;
    uintptr_t v = reinterpret_cast<uintptr_t>(p);
    return v >= il2cpp_base && v < il2cpp_base + il2cpp_size;
}

bool resolve_api(int timeout_ms) {
    // libil2cpp.so is loaded by libmain.so during UnityPlayer startup, which
    // happens after our JNI_OnLoad. Poll rather than assume.
    const int step = 100;
    int waited = 0;
    for (;;) {
        g_handle = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
        if (g_handle) break;
        if (waited >= timeout_ms) {
            LOGE("libil2cpp.so never appeared after %d ms", waited);
            return false;
        }
        sleep_ms(step);
        waited += step;
    }
    LOGI("libil2cpp.so mapped after %d ms", waited);

    // Load base comes from the linker, not from parsing /proc/self/maps. The
    // maps route summed every line whose path mentioned libil2cpp.so and
    // produced an 11.8 GB span on a 62 MB library, which put a constant skew
    // on every recorded RVA. dladdr reports dli_fbase authoritatively.
    {
        void* probe = dlsym(g_handle, "il2cpp_domain_get");
        Dl_info info{};
        if (probe && dladdr(probe, &info) != 0 && info.dli_fbase) {
            il2cpp_base = reinterpret_cast<uintptr_t>(info.dli_fbase);
            // dladdr gives the base but not the extent, so the span still comes
            // from maps — bounded to mappings that actually start at or after
            // the real base.
            uintptr_t lo = 0; size_t span = 0;
            if (find_module_range("libil2cpp.so", lo, span) && lo <= il2cpp_base) {
                il2cpp_size = (lo + span) - il2cpp_base;
            }
            if (il2cpp_size == 0 || il2cpp_size > 0x20000000) {
                il2cpp_size = 0x8000000;   // 128 MB ceiling — generous for a 62 MB image
            }
            LOGI("libil2cpp.so base=%p size=0x%zx (dladdr)", (void*)il2cpp_base, il2cpp_size);
        } else if (find_module_range("libil2cpp.so", il2cpp_base, il2cpp_size)) {
            LOGW("dladdr unavailable, falling back to maps: base=%p size=0x%zx",
                 (void*)il2cpp_base, il2cpp_size);
        } else {
            LOGW("could not determine libil2cpp.so range; RVAs will read 0");
        }
    }

    bind(api.domain_get,           "il2cpp_domain_get",            true);
    bind(api.domain_assembly_open, "il2cpp_domain_assembly_open",  true);
    bind(api.assembly_get_image,   "il2cpp_assembly_get_image",    true);
    bind(api.image_get_name,       "il2cpp_image_get_name",        true);
    bind(api.image_get_filename,   "il2cpp_image_get_filename",    false);
    bind(api.image_get_class_count,"il2cpp_image_get_class_count", true);
    bind(api.image_get_class,      "il2cpp_image_get_class",       true);

    // This build has il2cpp_domain_get_assemblies stripped; the mono shim is
    // the enumeration path. Optional because we have a name-list fallback.
    bind(api.mono_domain_get_assemblies_iter, "mono_domain_get_assemblies_iter", false);

    bind(api.class_get_name,          "il2cpp_class_get_name",           true);
    bind(api.class_get_namespace,     "il2cpp_class_get_namespace",      true);
    bind(api.class_get_parent,        "il2cpp_class_get_parent",         false);
    bind(api.class_get_flags,         "il2cpp_class_get_flags",          false);
    bind(api.class_get_fields,        "il2cpp_class_get_fields",         true);
    bind(api.class_get_methods,       "il2cpp_class_get_methods",        true);
    bind(api.class_get_properties,    "il2cpp_class_get_properties",     false);
    bind(api.class_get_interfaces,    "il2cpp_class_get_interfaces",     false);
    bind(api.class_get_nested_types,  "il2cpp_class_get_nested_types",   false);
    bind(api.class_instance_size,     "il2cpp_class_instance_size",      false);
    bind(api.class_is_valuetype,      "il2cpp_class_is_valuetype",       false);
    bind(api.class_is_enum,           "il2cpp_class_is_enum",            false);
    bind(api.class_is_inited,         "il2cpp_class_is_inited",          false);
    bind(api.class_get_type,          "il2cpp_class_get_type",           false);
    bind(api.class_get_type_token,    "il2cpp_class_get_type_token",     false);
    bind(api.class_get_declaring_type,"il2cpp_class_get_declaring_type", false);
    bind(api.class_get_element_class, "il2cpp_class_get_element_class",  false);
    bind(api.class_get_static_field_data, "il2cpp_class_get_static_field_data", false);
    bind(api.class_get_image,         "il2cpp_class_get_image",          false);
    bind(api.class_from_name,         "il2cpp_class_from_name",          false);

    bind(api.field_get_name,   "il2cpp_field_get_name",   true);
    bind(api.field_get_type,   "il2cpp_field_get_type",   false);
    bind(api.field_get_offset, "il2cpp_field_get_offset", true);
    bind(api.field_get_flags,  "il2cpp_field_get_flags",  false);
    bind(api.field_get_parent, "il2cpp_field_get_parent", false);

    bind(api.method_get_name,        "il2cpp_method_get_name",        true);
    bind(api.method_get_return_type, "il2cpp_method_get_return_type", false);
    bind(api.method_get_param_count, "il2cpp_method_get_param_count", false);
    bind(api.method_get_param,       "il2cpp_method_get_param",       false);
    bind(api.method_get_param_name,  "il2cpp_method_get_param_name",  false);
    bind(api.method_get_flags,       "il2cpp_method_get_flags",       false);
    bind(api.method_get_class,       "il2cpp_method_get_class",       false);

    bind(api.type_get_name, "il2cpp_type_get_name", false);
    bind(api.type_get_type, "il2cpp_type_get_type", false);
    bind(api.type_get_class_or_element_class, "il2cpp_type_get_class_or_element_class", false);

    bind(api.property_get_name,       "il2cpp_property_get_name",       false);
    bind(api.property_get_get_method, "il2cpp_property_get_get_method", false);
    bind(api.property_get_set_method, "il2cpp_property_get_set_method", false);

    bind(api.object_get_class,       "il2cpp_object_get_class",       false);
    bind(api.field_static_get_value, "il2cpp_field_static_get_value", false);
    bind(api.string_chars,           "il2cpp_string_chars",           false);
    bind(api.array_length,           "il2cpp_array_length",           false);

    bind(api.thread_attach, "il2cpp_thread_attach", true);
    bind(api.thread_detach, "il2cpp_thread_detach", false);
    bind(api.is_vm_thread,  "il2cpp_is_vm_thread",  false);
    bind(api.free,          "il2cpp_free",          false);

    if (g_missing_required) {
        LOGE("aborting: one or more required il2cpp symbols are unavailable");
        return false;
    }
    return true;
}

void type_name(const Il2CppType* type, char* out, size_t out_len) {
    if (out_len == 0) return;
    out[0] = '\0';
    if (!type || !api.type_get_name) {
        snprintf(out, out_len, "?");
        return;
    }
    char* n = api.type_get_name(type);
    if (!n) {
        snprintf(out, out_len, "?");
        return;
    }
    snprintf(out, out_len, "%s", n);
    // il2cpp_type_get_name returns a buffer from the il2cpp allocator. Free it
    // through il2cpp_free when available; falling back to libc free() across an
    // allocator boundary would be undefined, so we accept the leak instead.
    if (api.free) api.free(n);
}

uintptr_t method_pointer(const MethodInfo* method) {
    if (!method) return 0;
    // MethodInfo begins with Il2CppMethodPointer methodPointer in every IL2CPP
    // revision from Unity 2018 through 2022, so offset 0 is safe to read. The
    // value is only trusted if it lands inside the module.
    void* p = *reinterpret_cast<void* const*>(method);
    if (!in_il2cpp_module(p)) return 0;
    return reinterpret_cast<uintptr_t>(p) - il2cpp_base;
}

} // namespace zyrex
