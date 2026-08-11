#include "gavna_hook.h"

#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "gavna_log.h"

namespace gavna {
namespace {

constexpr size_t kJumpWords = 4;      // ldr x17,#8 / br x17 / 8-byte literal
constexpr int64_t kBranchRange = 1 << 27;  // ±128 MB for an unconditional B

size_t PageSize() {
    static const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    return page;
}

uintptr_t PageStart(uintptr_t addr) { return addr & ~(PageSize() - 1); }

void FlushCache(void* addr, size_t size) {
    char* begin = static_cast<char*>(addr);
    __builtin___clear_cache(begin, begin + size);
}

// ldr x17, #8 ; br x17 ; .quad dest
void BuildAbsoluteJump(uint32_t* out, uintptr_t dest) {
    out[0] = 0x58000051u;
    out[1] = 0xD61F0220u;
    out[2] = static_cast<uint32_t>(dest & 0xFFFFFFFFu);
    out[3] = static_cast<uint32_t>((dest >> 32) & 0xFFFFFFFFu);
}

// Temporarily lifts the write protection of the pages covering [addr, addr+size)
// and puts back PROT_READ|PROT_EXEC afterwards. Leaving a text page without
// PROT_EXEC would turn the very next call into a segfault, so the restore is not
// optional.
class WritableText {
  public:
    WritableText(void* addr, size_t size) {
        start_ = PageStart(reinterpret_cast<uintptr_t>(addr));
        length_ = reinterpret_cast<uintptr_t>(addr) + size - start_;
        if (mprotect(reinterpret_cast<void*>(start_), length_,
                     PROT_READ | PROT_WRITE | PROT_EXEC) == 0) {
            ok_ = true;
            return;
        }
        // Some kernels refuse write+execute on file-backed text.
        if (mprotect(reinterpret_cast<void*>(start_), length_, PROT_READ | PROT_WRITE) == 0) {
            ok_ = true;
            needs_restore_ = true;
            return;
        }
        LOGE("mprotect failed for %p (%zu bytes)", addr, size);
    }

    ~WritableText() {
        if (ok_ && needs_restore_) {
            if (mprotect(reinterpret_cast<void*>(start_), length_, PROT_READ | PROT_EXEC) != 0) {
                LOGE("could not restore PROT_EXEC on 0x%zx", start_);
            }
        }
    }

    bool ok() const { return ok_; }

  private:
    uintptr_t start_ = 0;
    size_t length_ = 0;
    bool ok_ = false;
    bool needs_restore_ = false;
};

// Bump arena of executable memory kept close to libil2cpp so stubs stay in
// branch range.
uint8_t* g_arena = nullptr;
size_t g_arena_used = 0;
size_t g_arena_size = 0;

bool InRange(uintptr_t from, uintptr_t to) {
    int64_t delta = static_cast<int64_t>(to) - static_cast<int64_t>(from);
    return delta >= -kBranchRange && delta < kBranchRange;
}

// mmap does not honour a hint address reliably, so try a spread of hints and
// keep the first mapping that lands close enough.
uint8_t* MapNear(uintptr_t anchor, size_t size) {
    const size_t step = 0x200000;  // 2 MB
    for (int i = 1; i <= 48; ++i) {
        for (int direction = 0; direction < 2; ++direction) {
            uintptr_t hint = direction == 0 ? anchor + static_cast<uintptr_t>(i) * step
                                            : anchor - static_cast<uintptr_t>(i) * step;
            if (direction == 1 && anchor < static_cast<uintptr_t>(i) * step) continue;
            void* p = mmap(reinterpret_cast<void*>(PageStart(hint)), size,
                           PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (p == MAP_FAILED) continue;
            uintptr_t got = reinterpret_cast<uintptr_t>(p);
            if (InRange(anchor, got) && InRange(anchor, got + size)) {
                return static_cast<uint8_t*>(p);
            }
            munmap(p, size);
        }
    }
    return nullptr;
}

}  // namespace

bool WriteWord(void* dst, uint32_t word) {
    if (dst == nullptr || (reinterpret_cast<uintptr_t>(dst) & 3u) != 0) {
        LOGE("WriteWord: bad destination %p", dst);
        return false;
    }
    WritableText writable(dst, sizeof(uint32_t));
    if (!writable.ok()) return false;
    *static_cast<volatile uint32_t*>(dst) = word;
    FlushCache(dst, sizeof(uint32_t));
    return true;
}

bool WriteCode(void* dst, const uint32_t* words, size_t count) {
    if (dst == nullptr || words == nullptr || count == 0) return false;
    if ((reinterpret_cast<uintptr_t>(dst) & 3u) != 0) {
        LOGE("refusing unaligned code write at %p", dst);
        return false;
    }
    WritableText writable(dst, count * sizeof(uint32_t));
    if (!writable.ok()) return false;

    volatile uint32_t* p = static_cast<volatile uint32_t*>(dst);
    for (size_t i = count; i-- > 0;) {
        p[i] = words[i];
    }
    FlushCache(dst, count * sizeof(uint32_t));
    return true;
}

bool IsPcRelative(uint32_t insn) {
    if ((insn & 0x7C000000u) == 0x14000000u) return true;  // B / BL
    if ((insn & 0xFF000010u) == 0x54000000u) return true;  // B.cond
    if ((insn & 0x7E000000u) == 0x34000000u) return true;  // CBZ / CBNZ
    if ((insn & 0x7E000000u) == 0x36000000u) return true;  // TBZ / TBNZ
    if ((insn & 0x1F000000u) == 0x10000000u) return true;  // ADR / ADRP
    if ((insn & 0x3B000000u) == 0x18000000u) return true;  // LDR/LDRSW/PRFM literal
    return false;
}

uint32_t* AllocStub(const uint32_t* words, size_t count, uintptr_t anchor) {
    if (words == nullptr || count == 0) return nullptr;
    const size_t bytes = count * sizeof(uint32_t);

    if (g_arena == nullptr || g_arena_used + bytes > g_arena_size) {
        size_t size = PageSize();
        uint8_t* mapped = MapNear(anchor, size);
        if (mapped == nullptr) {
            LOGE("no stub mapping within branch range of 0x%zx", anchor);
            return nullptr;
        }
        if (mprotect(mapped, size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            LOGE("stub page cannot be made executable");
            munmap(mapped, size);
            return nullptr;
        }
        g_arena = mapped;
        g_arena_size = size;
        g_arena_used = 0;
    }

    uint32_t* stub = reinterpret_cast<uint32_t*>(g_arena + g_arena_used);
    g_arena_used += (bytes + 15u) & ~15u;  // keep stubs 16-byte aligned
    memcpy(stub, words, bytes);
    FlushCache(stub, bytes);
    return stub;
}

bool InstallHook(void* target, void* replacement, void** out_original) {
    if (target == nullptr || replacement == nullptr) {
        LOGE("InstallHook: null argument");
        return false;
    }
    if ((reinterpret_cast<uintptr_t>(target) & 3u) != 0) {
        LOGE("InstallHook: target %p is not 4-byte aligned", target);
        return false;
    }

    uint32_t* code = static_cast<uint32_t*>(target);
    uint32_t stolen[kJumpWords];
    memcpy(stolen, code, sizeof(stolen));

    for (size_t i = 0; i < kJumpWords; ++i) {
        if (IsPcRelative(stolen[i])) {
            LOGE("InstallHook: %p+%zu is PC-relative (0x%08X), refusing to hook", target, i * 4,
                 stolen[i]);
            return false;
        }
    }

    const size_t page = PageSize();
    void* trampoline = mmap(nullptr, page, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (trampoline == MAP_FAILED) {
        LOGE("InstallHook: trampoline mmap failed");
        return false;
    }

    uint32_t* tramp = static_cast<uint32_t*>(trampoline);
    memcpy(tramp, stolen, sizeof(stolen));
    BuildAbsoluteJump(tramp + kJumpWords, reinterpret_cast<uintptr_t>(target) + sizeof(stolen));

    if (mprotect(trampoline, page, PROT_READ | PROT_EXEC) != 0) {
        LOGE("InstallHook: cannot make trampoline executable");
        munmap(trampoline, page);
        return false;
    }
    FlushCache(trampoline, kJumpWords * 2 * sizeof(uint32_t));

    // Publish the trampoline before redirecting: the replacement can be entered
    // by the game thread the instant the jump lands.
    if (out_original != nullptr) *out_original = trampoline;

    uint32_t jump[kJumpWords];
    BuildAbsoluteJump(jump, reinterpret_cast<uintptr_t>(replacement));
    if (!WriteCode(target, jump, kJumpWords)) {
        if (out_original != nullptr) *out_original = nullptr;
        munmap(trampoline, page);
        return false;
    }

    LOGI("hook installed: %p -> %p (trampoline %p)", target, replacement, trampoline);
    return true;
}

uint32_t EncBranch(uintptr_t from, uintptr_t to) {
    int64_t delta = static_cast<int64_t>(to) - static_cast<int64_t>(from);
    if (delta < -kBranchRange || delta >= kBranchRange || (delta & 3) != 0) return 0;
    uint32_t imm26 = static_cast<uint32_t>((delta >> 2) & 0x03FFFFFF);
    return 0x14000000u | imm26;
}

uint32_t EncMovzW(int reg, uint16_t imm) {
    return 0x52800000u | (static_cast<uint32_t>(imm) << 5) | (static_cast<uint32_t>(reg) & 31u);
}

uint32_t EncLdrWLiteral8() {
    // ldr w0, [pc, #8] - reads the word stored two instructions further on.
    return 0x18000040u;
}

uint32_t EncStrXzr(int base_reg) {
    return 0xF9000000u | ((static_cast<uint32_t>(base_reg) & 31u) << 5) | 31u;
}

uint32_t EncRet() { return 0xD65F03C0u; }

}  // namespace gavna
