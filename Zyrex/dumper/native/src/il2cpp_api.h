// Zyrex — IL2CPP runtime API binding
//
// Everything is resolved by dlsym against the already-loaded libil2cpp.so.
// No struct layouts are assumed except MethodInfo::methodPointer (offset 0,
// stable across Unity 2018-2022) and that read is range-validated before use.
//
// Target: BLOCKPOST (com.skullcapstudios.bps), Unity 2021.3.45f2, arm64-v8a.

#pragma once

#include <cstddef>
#include <cstdint>

namespace zyrex {

// Opaque runtime handles. We never dereference these ourselves.
struct Il2CppDomain;
struct Il2CppAssembly;
struct Il2CppImage;
struct Il2CppClass;
struct Il2CppType;
struct Il2CppThread;
struct FieldInfo;
struct MethodInfo;
struct PropertyInfo;

// ---------------------------------------------------------------------------
// Function pointer table
// ---------------------------------------------------------------------------
struct Api {
    // domain / assembly / image
    Il2CppDomain*         (*domain_get)();
    const Il2CppAssembly* (*domain_assembly_open)(Il2CppDomain*, const char*);
    const Il2CppImage*    (*assembly_get_image)(const Il2CppAssembly*);
    const char*           (*image_get_name)(const Il2CppImage*);
    const char*           (*image_get_filename)(const Il2CppImage*);
    size_t                (*image_get_class_count)(const Il2CppImage*);
    const Il2CppClass*    (*image_get_class)(const Il2CppImage*, size_t);

    // mono compatibility shim — the only surviving way to *enumerate* assemblies
    // on this build (il2cpp_domain_get_assemblies has been stripped by the
    // protector). Iterates until it returns null.
    Il2CppAssembly*       (*mono_domain_get_assemblies_iter)(Il2CppDomain*, void**);

    // class
    const char*   (*class_get_name)(Il2CppClass*);
    const char*   (*class_get_namespace)(Il2CppClass*);
    Il2CppClass*  (*class_get_parent)(Il2CppClass*);
    int           (*class_get_flags)(const Il2CppClass*);
    FieldInfo*    (*class_get_fields)(Il2CppClass*, void**);
    const MethodInfo* (*class_get_methods)(Il2CppClass*, void**);
    PropertyInfo* (*class_get_properties)(Il2CppClass*, void**);
    Il2CppClass*  (*class_get_interfaces)(Il2CppClass*, void**);
    Il2CppClass*  (*class_get_nested_types)(Il2CppClass*, void**);
    int32_t       (*class_instance_size)(Il2CppClass*);
    bool          (*class_is_valuetype)(const Il2CppClass*);
    bool          (*class_is_enum)(const Il2CppClass*);
    bool          (*class_is_inited)(const Il2CppClass*);
    const Il2CppType* (*class_get_type)(Il2CppClass*);
    uint32_t      (*class_get_type_token)(Il2CppClass*);
    Il2CppClass*  (*class_get_declaring_type)(Il2CppClass*);
    Il2CppClass*  (*class_get_element_class)(Il2CppClass*);
    void*         (*class_get_static_field_data)(const Il2CppClass*);
    const Il2CppImage* (*class_get_image)(Il2CppClass*);
    Il2CppClass*  (*class_from_name)(const Il2CppImage*, const char*, const char*);

    // field
    const char*       (*field_get_name)(FieldInfo*);
    const Il2CppType* (*field_get_type)(FieldInfo*);
    size_t            (*field_get_offset)(FieldInfo*);
    int               (*field_get_flags)(FieldInfo*);
    Il2CppClass*      (*field_get_parent)(FieldInfo*);

    // method
    const char*       (*method_get_name)(const MethodInfo*);
    const Il2CppType* (*method_get_return_type)(const MethodInfo*);
    uint32_t          (*method_get_param_count)(const MethodInfo*);
    const Il2CppType* (*method_get_param)(const MethodInfo*, uint32_t);
    const char*       (*method_get_param_name)(const MethodInfo*, uint32_t);
    uint32_t          (*method_get_flags)(const MethodInfo*, uint32_t*);
    Il2CppClass*      (*method_get_class)(const MethodInfo*);

    // type
    char*        (*type_get_name)(const Il2CppType*);   // malloc'd -> free via api.free
    int          (*type_get_type)(const Il2CppType*);
    Il2CppClass* (*type_get_class_or_element_class)(const Il2CppType*);

    // property
    const char*       (*property_get_name)(PropertyInfo*);
    const MethodInfo* (*property_get_get_method)(PropertyInfo*);
    const MethodInfo* (*property_get_set_method)(PropertyInfo*);

    // object / static access — used by the live probe
    Il2CppClass* (*object_get_class)(void*);
    void         (*field_static_get_value)(FieldInfo*, void*);
    uint16_t*    (*string_chars)(void*);

    // thread / misc
    Il2CppThread* (*thread_attach)(Il2CppDomain*);
    void          (*thread_detach)(Il2CppThread*);
    bool          (*is_vm_thread)(Il2CppThread*);
    void          (*free)(void*);
};

extern Api api;

// Base address and size of the loaded libil2cpp.so, from /proc/self/maps.
// Used to turn absolute method pointers into file-relative RVAs and to reject
// anything that does not actually live inside the module.
extern uintptr_t il2cpp_base;
extern size_t    il2cpp_size;

// Blocks (with timeout) until libil2cpp.so is mapped, then resolves every
// symbol above. Returns false if the module never appears or a symbol the
// dumper cannot work without is missing.
bool resolve_api(int timeout_ms);

// True when p points inside the mapped libil2cpp.so image.
bool in_il2cpp_module(const void* p);

// Safe wrapper around type_get_name: never returns null, never leaks.
// Caller must not free; the value is copied into 'out'.
void type_name(const Il2CppType* type, char* out, size_t out_len);

// MethodInfo::methodPointer lives at offset 0 in every Unity version this
// game could have been built with. Returns 0 when the value is not a pointer
// into libil2cpp.so.
uintptr_t method_pointer(const MethodInfo* method);

} // namespace zyrex
