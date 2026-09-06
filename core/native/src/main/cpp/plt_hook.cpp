#include "plt_hook.h"

#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstring>
#include <mutex>

#include "unique_native.h"

namespace unique::plt {
namespace {

#if defined(__LP64__)
using Rela = ElfW(Rela);
using Sym = ElfW(Sym);
#define UNIQUE_R_SYM(info) ELF64_R_SYM(info)
#define UNIQUE_R_TYPE(info) ELF64_R_TYPE(info)
#else
#error "UNIQUE runs 64-bit guests only; a 32-bit build would need REL as well as RELA."
#endif

#if defined(__aarch64__)
constexpr unsigned kJumpSlot = R_AARCH64_JUMP_SLOT;
constexpr unsigned kGlobDat = R_AARCH64_GLOB_DAT;
#elif defined(__x86_64__)
constexpr unsigned kJumpSlot = R_X86_64_JUMP_SLOT;
constexpr unsigned kGlobDat = R_X86_64_GLOB_DAT;
#else
#error "Unsupported architecture for PLT hooking."
#endif

std::mutex g_mutex;

/// Everything one library's dynamic section tells us about its relocations.
struct DynamicInfo {
    const Rela* plt_rela = nullptr;
    size_t plt_rela_count = 0;
    const Rela* rela = nullptr;
    size_t rela_count = 0;
    const Sym* symtab = nullptr;
    const char* strtab = nullptr;
};

DynamicInfo read_dynamic(const ElfW(Dyn)* dyn, ElfW(Addr) base) {
    DynamicInfo info;
    size_t plt_bytes = 0;
    size_t rela_bytes = 0;
    size_t rela_entry = sizeof(Rela);

    for (; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_JMPREL:
                info.plt_rela = reinterpret_cast<const Rela*>(base + dyn->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                plt_bytes = dyn->d_un.d_val;
                break;
            case DT_RELA:
                info.rela = reinterpret_cast<const Rela*>(base + dyn->d_un.d_ptr);
                break;
            case DT_RELASZ:
                rela_bytes = dyn->d_un.d_val;
                break;
            case DT_RELAENT:
                rela_entry = dyn->d_un.d_val;
                break;
            case DT_SYMTAB:
                info.symtab = reinterpret_cast<const Sym*>(base + dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                info.strtab = reinterpret_cast<const char*>(base + dyn->d_un.d_ptr);
                break;
            default:
                break;
        }
    }
    if (rela_entry == 0) rela_entry = sizeof(Rela);
    info.plt_rela_count = plt_bytes / sizeof(Rela);
    info.rela_count = rela_bytes / rela_entry;
    return info;
}

/// Makes the page holding `slot` writable, writes, and restores the old protection.
///
/// The page size comes from sysconf, never a hard-coded 4096: on a 16 KB-page Android 15
/// device a 4096 mask computes the wrong page base and mprotect fails on an address that
/// looks perfectly reasonable.
bool write_slot(void** slot, void* value, void** previous) {
    const auto page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto address = reinterpret_cast<uintptr_t>(slot);
    auto page = reinterpret_cast<void*>(address & ~(page_size - 1));

    if (mprotect(page, page_size, PROT_READ | PROT_WRITE) != 0) {
        return false;
    }
    if (previous != nullptr) *previous = *slot;
    *slot = value;
    // Left readable and writable rather than restored to read-only. The GOT is writable
    // in a normal process unless the linker applied RELRO, and re-protecting a RELRO page
    // read-only would make the *next* hook of the same page fail - which is worse than a
    // page that stays writable, because it fails silently and only for some libraries.
    __builtin___clear_cache(reinterpret_cast<char*>(page),
                            reinterpret_cast<char*>(page) + page_size);
    return true;
}

struct ScanContext {
    const std::vector<std::string>* filters;
    const std::vector<std::string>* excludes;
    HookRequest* requests;
    size_t request_count;
    HookReport* report;
};

bool path_matches(const char* path, const std::vector<std::string>& filters) {
    if (filters.empty()) return true;
    if (path == nullptr) return false;
    for (const auto& filter : filters) {
        if (strstr(path, filter.c_str()) != nullptr) return true;
    }
    return false;
}

/// True when an exclusion names this library. An empty list excludes nothing.
bool path_excluded(const char* path, const std::vector<std::string>& excludes) {
    if (excludes.empty() || path == nullptr) return false;
    for (const auto& exclude : excludes) {
        if (strstr(path, exclude.c_str()) != nullptr) return true;
    }
    return false;
}

void patch_relocations(const Rela* rela, size_t count, const DynamicInfo& info,
                       ElfW(Addr) base, ScanContext* ctx) {
    if (rela == nullptr || info.symtab == nullptr || info.strtab == nullptr) return;

    for (size_t i = 0; i < count; ++i) {
        const unsigned type = UNIQUE_R_TYPE(rela[i].r_info);
        if (type != kJumpSlot && type != kGlobDat) continue;

        const auto sym_index = UNIQUE_R_SYM(rela[i].r_info);
        const char* name = info.strtab + info.symtab[sym_index].st_name;
        if (name == nullptr || name[0] == '\0') continue;

        for (size_t r = 0; r < ctx->request_count; ++r) {
            HookRequest& request = ctx->requests[r];
            if (strcmp(name, request.symbol) != 0) continue;

            auto slot = reinterpret_cast<void**>(base + rela[i].r_offset);
            if (*slot == request.replacement) break;   // already ours; idempotent

            void* previous = nullptr;
            if (write_slot(slot, request.replacement, &previous)) {
                // The original comes from the dynamic linker, never from the slot.
                //
                // Reading it out of the GOT is the obvious thing and it is only *usually*
                // right: a lazily-bound entry holds the resolver stub rather than the
                // function, and a trampoline that calls a resolver stub with the wrong
                // arguments jumps somewhere that is not a function at all. The NDK links
                // with `-z now` so the case is rare, which is exactly what makes it the
                // kind of bug that surfaces once, on one device, minutes into a game.
                //
                // `dlsym(RTLD_DEFAULT)` asks the loader the same question the relocation
                // asked and cannot answer with a stub. The GOT value is kept only as the
                // fallback for a symbol the loader will not name.
                if (request.original != nullptr && *request.original == nullptr) {
                    void* resolved = dlsym(RTLD_DEFAULT, request.symbol);
                    *request.original =
                            (resolved != nullptr && resolved != request.replacement)
                                    ? resolved
                                    : previous;
                }
                ctx->report->slots_patched++;
            } else {
                ctx->report->failures.emplace_back(std::string("mprotect failed for ") + name);
            }
            break;
        }
    }
}

int scan_one(struct dl_phdr_info* info, size_t, void* data) {
    auto* ctx = static_cast<ScanContext*>(data);
    ctx->report->libraries_scanned++;
    if (!path_matches(info->dlpi_name, *ctx->filters)) {
        // Keep a few names that look app-private, so a filter that does not match what
        // dl_iterate_phdr actually reports is visible rather than looking like "the
        // library was not loaded".
        if (ctx->report->sample.size() < 8 && info->dlpi_name != nullptr &&
            strstr(info->dlpi_name, "/data/") != nullptr) {
            ctx->report->sample.emplace_back(info->dlpi_name);
        }
        return 0;
    }
    if (path_excluded(info->dlpi_name, *ctx->excludes)) {
        // Counted and named, never silent. "This library was deliberately left alone"
        // and "the scan never saw it" produce the same zero, and only one of them is a
        // bug — so the report carries both numbers.
        ctx->report->libraries_excluded++;
        if (ctx->report->excluded.size() < 16) {
            ctx->report->excluded.emplace_back(info->dlpi_name);
        }
        return 0;
    }
    ctx->report->libraries_matched++;

    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_DYNAMIC) continue;
        const auto* dyn = reinterpret_cast<const ElfW(Dyn)*>(info->dlpi_addr + phdr.p_vaddr);
        DynamicInfo dynamic = read_dynamic(dyn, info->dlpi_addr);
        patch_relocations(dynamic.plt_rela, dynamic.plt_rela_count, dynamic,
                          info->dlpi_addr, ctx);
        patch_relocations(dynamic.rela, dynamic.rela_count, dynamic,
                          info->dlpi_addr, ctx);
    }
    return 0;
}

}  // namespace

HookReport hook_all(const std::vector<std::string>& path_filters,
                    const std::vector<std::string>& path_excludes,
                    HookRequest* requests, size_t request_count) {
    std::lock_guard<std::mutex> lock(g_mutex);
    HookReport report;
    ScanContext ctx{&path_filters, &path_excludes, requests, request_count, &report};
    dl_iterate_phdr(scan_one, &ctx);
    return report;
}

}  // namespace unique::plt
