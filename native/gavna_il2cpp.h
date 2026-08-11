// Thin binding over the il2cpp runtime exports shipped inside libil2cpp.so.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include <vector>

namespace gavna {
namespace il2cpp {

// MethodInfo::methodPointer sits at offset 0 for this runtime (Unity 2022.3,
// metadata v31) - verified against the dumped struct layout of the shipped
// binary, and re-checked at runtime before any pointer is used.
struct Method {
    void* info = nullptr;
    void* code = nullptr;
    const char* name = nullptr;
    uint32_t param_count = 0;

    bool valid() const { return info != nullptr && code != nullptr; }
};

// Waits (up to timeout_ms) for libil2cpp.so to be loaded and its domain to have
// assemblies attached, then resolves every runtime export we use.
bool WaitUntilReady(int timeout_ms);

bool ready();

// Base address and size of the loaded libil2cpp.so text mapping. Used to sanity
// check every method pointer before it is patched.
uintptr_t module_base();
size_t module_size();
bool PointerLooksLikeCode(const void* p);

void* AttachCurrentThread();

// Looks the class up in every loaded assembly. `name_space` may be nullptr, in
// which case only the class name is matched.
void* FindClass(const char* name_space, const char* name);

// All methods of `klass` with the given name. `param_count` of -1 matches any
// arity. Only methods declared on `klass` itself or inherited are considered,
// mirroring il2cpp_class_get_method_from_name lookup order.
std::vector<Method> FindMethods(void* klass, const char* name, int param_count);

// Single best match; invalid Method when nothing matched.
Method FindMethod(void* klass, const char* name, int param_count);

// Byte offset of an instance field inside the object, or -1.
int32_t FieldOffset(void* klass, const char* name);

const char* ClassName(void* klass);

}  // namespace il2cpp
}  // namespace gavna
