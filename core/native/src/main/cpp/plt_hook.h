#pragma once
// PLT/GOT hooking.
//
// This is deliberately *not* inline hooking. An inline hook rewrites the first
// instructions of a function, which on ARM64 has to reason about BTI landing pads, PAC
// signing, literal pools and the instruction cache, and gets a partially-patched libc
// wrong in ways that corrupt data silently. A PLT hook writes one pointer into a
// library's Global Offset Table: nothing executable is modified, the failure mode is
// "this library was not hooked" rather than "this library is now broken", and the scope
// is explicit — exactly the libraries asked for, and no others.
//
// What that buys and what it costs:
//
//   - It catches calls *out of* a hooked library, which is what UNIQUE needs: a guest's
//     own code calling fopen("/data/data/<pkg>/…").
//   - It does not catch calls libc makes to itself. fopen() reaching open() internally is
//     invisible here, and correctly so: the path was already redirected on the way in.
//   - It does not catch a library that is loaded later. Rehooking is the caller's job,
//     which is why install() is idempotent and cheap to repeat.

#include <cstddef>
#include <string>
#include <vector>

namespace unique::plt {

struct HookRequest {
    const char* symbol;
    void* replacement;
    void** original;   // filled with the previous GOT value, may be null
};

struct HookReport {
    int libraries_scanned = 0;
    int libraries_matched = 0;
    int slots_patched = 0;
    std::vector<std::string> failures;
    /// Library names seen, filled only when nothing matched. "No library matched" and
    /// "the filter is not the shape dl_iterate_phdr reports" are indistinguishable
    /// without it, and the second is the one that actually happens.
    std::vector<std::string> sample;
};

// Patches every GOT slot in the loaded libraries whose path contains one of
// `path_filters` (empty means every library) for each requested symbol.
//
// Idempotent: a slot already pointing at the replacement is left alone and not counted.
HookReport hook_all(const std::vector<std::string>& path_filters,
                    HookRequest* requests, size_t request_count);

}  // namespace unique::plt
