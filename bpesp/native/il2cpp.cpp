#include "il2cpp.h"
#include <cstring>

Il2CppApi g_il2{};

#define BIND(field, sym)                                                       \
    do {                                                                       \
        *(void **)&g_il2.field = dlsym(handle, sym);                           \
        if (!g_il2.field) missing = sym;                                       \
    } while (0)

bool il2cpp_bind(void *handle) {
    const char *missing = nullptr;

    BIND(domain_get,               "il2cpp_domain_get");
    BIND(domain_assembly_open,     "il2cpp_domain_assembly_open");
    BIND(assembly_get_image,       "il2cpp_assembly_get_image");
    BIND(image_get_class_count,    "il2cpp_image_get_class_count");
    BIND(image_get_class,          "il2cpp_image_get_class");
    BIND(image_get_name,           "il2cpp_image_get_name");
    BIND(class_from_name,          "il2cpp_class_from_name");
    BIND(class_get_name,           "il2cpp_class_get_name");
    BIND(class_get_namespace,      "il2cpp_class_get_namespace");
    BIND(class_get_fields,         "il2cpp_class_get_fields");
    BIND(class_get_parent,         "il2cpp_class_get_parent");
    BIND(class_get_element_class,  "il2cpp_class_get_element_class");
    BIND(class_instance_size,      "il2cpp_class_instance_size");
    BIND(class_get_method_from_name,"il2cpp_class_get_method_from_name");
    BIND(class_get_static_field_data,"il2cpp_class_get_static_field_data");
    BIND(runtime_class_init,       "il2cpp_runtime_class_init");
    BIND(class_is_valuetype,       "il2cpp_class_is_valuetype");
    BIND(field_get_name,           "il2cpp_field_get_name");
    BIND(field_get_offset,         "il2cpp_field_get_offset");
    BIND(field_get_type,           "il2cpp_field_get_type");
    BIND(field_get_flags,          "il2cpp_field_get_flags");
    BIND(field_static_get_value,   "il2cpp_field_static_get_value");
    BIND(type_get_name,            "il2cpp_type_get_name");
    BIND(class_from_type,          "il2cpp_class_from_type");
    BIND(type_get_type,            "il2cpp_type_get_type");
    BIND(runtime_invoke,           "il2cpp_runtime_invoke");
    BIND(thread_attach,            "il2cpp_thread_attach");
    BIND(thread_detach,            "il2cpp_thread_detach");
    BIND(free,                     "il2cpp_free");

    g_il2.handle = handle;
    g_il2.ok = (missing == nullptr);
    return g_il2.ok;
}

void il2cpp_string_to_utf8(void *str, char *buf, size_t cap) {
    if (cap == 0) return;
    buf[0] = '\0';
    if (!str) return;

    int32_t len = rd<int32_t>(str, 0x10);
    if (len <= 0 || len > 256) return;

    const uint16_t *src = (const uint16_t *)((uint8_t *)str + 0x14);
    size_t o = 0;
    for (int32_t i = 0; i < len && o + 4 < cap; i++) {
        uint16_t c = src[i];
        if (c < 0x80) {
            buf[o++] = (char)c;
        } else if (c < 0x800) {
            buf[o++] = (char)(0xC0 | (c >> 6));
            buf[o++] = (char)(0x80 | (c & 0x3F));
        } else {
            buf[o++] = (char)(0xE0 | (c >> 12));
            buf[o++] = (char)(0x80 | ((c >> 6) & 0x3F));
            buf[o++] = (char)(0x80 | (c & 0x3F));
        }
    }
    buf[o] = '\0';
}
