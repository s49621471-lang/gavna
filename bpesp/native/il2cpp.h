// Minimal libil2cpp.so runtime binding. Everything used here is an exported
// symbol of the shipped libil2cpp.so — no metadata parsing, so the encrypted
// global-metadata.dat header is irrelevant to us.
#pragma once

#include <cstdint>
#include <cstddef>
#include <dlfcn.h>

// ---- managed object layouts (arm64, 8-byte pointers) ------------------------
struct Il2CppObject { void *klass; void *monitor; };

struct Il2CppString {
    void *klass; void *monitor;
    int32_t  length;      // 0x10
    uint16_t chars[1];    // 0x14, UTF-16, NOT nul-terminated
};

struct Il2CppArray {
    void  *klass; void *monitor;
    void  *bounds;        // 0x10
    size_t max_length;    // 0x18
    // element data begins at 0x20
};
#define IL2CPP_ARRAY_DATA(a) ((void *)((uint8_t *)(a) + 0x20))

// System.Collections.Generic.List`1
#define LIST_ITEMS_OFF 0x10
#define LIST_SIZE_OFF  0x18

#define FIELD_ATTRIBUTE_STATIC 0x0010

struct Vec3 { float x, y, z; };
struct Mat4 { float m[16]; };   // Unity Matrix4x4: column-major, index = col*4+row

// ---- api pointers -----------------------------------------------------------
struct Il2CppApi {
    void *(*domain_get)();
    void *(*domain_assembly_open)(void *domain, const char *name);
    void *(*assembly_get_image)(void *assembly);
    size_t (*image_get_class_count)(void *image);
    const void *(*image_get_class)(void *image, size_t index);
    const char *(*image_get_name)(void *image);
    void *(*class_from_name)(void *image, const char *ns, const char *name);
    const char *(*class_get_name)(void *klass);
    const char *(*class_get_namespace)(void *klass);
    void *(*class_get_fields)(void *klass, void **iter);
    void *(*class_get_parent)(void *klass);
    void *(*class_get_element_class)(void *klass);
    int32_t (*class_instance_size)(void *klass);
    void *(*class_get_method_from_name)(void *klass, const char *name, int argc);
    void *(*class_get_static_field_data)(void *klass);
    const void *(*class_get_image)(void *klass);
    void *(*class_get_type)(void *klass);
    void *(*type_get_object)(void *type);
    void (*runtime_class_init)(void *klass);
    bool (*class_is_valuetype)(void *klass);
    const char *(*field_get_name)(void *field);
    size_t (*field_get_offset)(void *field);
    void *(*field_get_type)(void *field);
    int (*field_get_flags)(void *field);
    void (*field_static_get_value)(void *field, void *value);
    char *(*type_get_name)(void *type);
    void *(*class_from_type)(void *type);
    int (*type_get_type)(void *type);
    void *(*runtime_invoke)(void *method, void *obj, void **params, void **exc);
    void *(*thread_attach)(void *domain);
    void (*thread_detach)(void *thread);
    void (*free)(void *p);

    void *handle;       // dlopen handle of libil2cpp.so
    bool  ok;
};

extern Il2CppApi g_il2;

bool il2cpp_bind(void *handle);

// ---- helpers ----------------------------------------------------------------
template <typename T>
static inline T rd(const void *base, size_t off) {
    T v{};
    if (!base) return v;
    __builtin_memcpy(&v, (const uint8_t *)base + off, sizeof(T));
    return v;
}

// Copies a managed string into buf as UTF-8-ish (chars >0x7F become '?').
void il2cpp_string_to_utf8(void *str, char *buf, size_t cap);
