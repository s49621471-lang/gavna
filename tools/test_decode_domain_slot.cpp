// Host-side check of the two decoders in gavna_il2cpp.cpp, run against the
// exact instruction words taken out of the shipped libil2cpp.so.
//
//   g++ -std=c++17 -o test_decode tools/test_decode_domain_slot.cpp && ./test_decode

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t* FollowThunk(uint32_t* code) {
    if (code == nullptr) return nullptr;
    uint32_t insn = *code;
    if ((insn & 0xFC000000u) != 0x14000000u) return code;
    int32_t imm26 = static_cast<int32_t>(insn & 0x03FFFFFFu);
    if (imm26 & 0x02000000) imm26 |= static_cast<int32_t>(0xFC000000u);
    return code + imm26;
}

static void** DecodeDomainSlot(uint32_t* code) {
    if (code == nullptr) return nullptr;
    int page_reg = -1;
    uintptr_t page = 0;
    for (int i = 0; i < 12; ++i) {
        uint32_t insn = code[i];
        if ((insn & 0x9F000000u) == 0x90000000u) {
            uint32_t immlo = (insn >> 29) & 0x3u;
            uint32_t immhi = (insn >> 5) & 0x7FFFFu;
            int64_t imm = static_cast<int64_t>((immhi << 2) | immlo);
            if (imm & (1LL << 20)) imm -= (1LL << 21);
            page_reg = static_cast<int>(insn & 31u);
            page = (reinterpret_cast<uintptr_t>(code + i) & ~static_cast<uintptr_t>(0xFFF)) +
                   static_cast<uintptr_t>(imm << 12);
            continue;
        }
        if (page_reg >= 0 && (insn & 0xFFC00000u) == 0xF9400000u) {
            int base = static_cast<int>((insn >> 5) & 31u);
            if (base != page_reg) continue;
            uintptr_t off = static_cast<uintptr_t>((insn >> 10) & 0xFFFu) * 8u;
            return reinterpret_cast<void**>(page + off);
        }
        if (insn == 0xD65F03C0u) break;
    }
    return nullptr;
}

// Virtual addresses as they appear in the game's libil2cpp.so.
static const uintptr_t kThunkVa = 0x230A788;   // il2cpp_domain_get
static const uintptr_t kImplVa = 0x23534D8;    // Domain::GetCurrent
static const uintptr_t kExpectedSlotVa = 0x5933308;

int main() {
    // ADRP maths only works out when the image sits on a page boundary, which is
    // exactly how the loader maps a shared object - so the fake image has to be
    // page aligned too.
    const size_t kSize = 0x6000000;
    uint8_t* raw = static_cast<uint8_t*>(calloc(1, kSize + 0x1000));
    if (raw == nullptr) return 1;
    uint8_t* mem = reinterpret_cast<uint8_t*>(
        (reinterpret_cast<uintptr_t>(raw) + 0xFFF) & ~static_cast<uintptr_t>(0xFFF));
    const uintptr_t base = reinterpret_cast<uintptr_t>(mem);
    int failures = 0;

    uint32_t* thunk = reinterpret_cast<uint32_t*>(mem + kThunkVa);
    int64_t delta = static_cast<int64_t>(kImplVa) - static_cast<int64_t>(kThunkVa);
    *thunk = 0x14000000u | static_cast<uint32_t>((delta >> 2) & 0x03FFFFFF);

    // Verbatim prologue of Domain::GetCurrent from the shipped binary.
    const uint32_t impl[] = {
        0xF81E0FFE,  // str  x30, [sp, #-0x20]!
        0xA9014FF4,  // stp  x20, x19, [sp, #0x10]
        0x9001AF14,  // adrp x20, #0x5933000
        0xF9418680,  // ldr  x0, [x20, #0x308]
        0xB50001E0,  // cbnz x0, ...
        0x52800700,  // mov  w0, #0x38
    };
    memcpy(mem + kImplVa, impl, sizeof(impl));

    uint32_t* followed = FollowThunk(thunk);
    uintptr_t followed_va = reinterpret_cast<uintptr_t>(followed) - base;
    printf("follow thunk        -> 0x%zX (expect 0x%zX) %s\n", followed_va, kImplVa,
           followed_va == kImplVa ? "ok" : (++failures, "FAIL"));

    void** slot = DecodeDomainSlot(followed);
    uintptr_t slot_va = reinterpret_cast<uintptr_t>(slot) - base;
    printf("decode domain slot  -> 0x%zX (expect 0x%zX) %s\n", slot_va, kExpectedSlotVa,
           slot != nullptr && slot_va == kExpectedSlotVa ? "ok" : (++failures, "FAIL"));

    // A prologue with no adrp/ldr pair must decode to null rather than garbage.
    const uint32_t ret_only[] = {0xD65F03C0, 0xD65F03C0, 0xD65F03C0};
    memcpy(mem + 0x1000, ret_only, sizeof(ret_only));
    void** none = DecodeDomainSlot(reinterpret_cast<uint32_t*>(mem + 0x1000));
    printf("undecodable prologue-> %p (expect nil)       %s\n", static_cast<void*>(none),
           none == nullptr ? "ok" : (++failures, "FAIL"));

    // A non-branch first instruction must be treated as the implementation.
    uint32_t* direct = reinterpret_cast<uint32_t*>(mem + kImplVa);
    printf("follow non-thunk    -> %s\n", FollowThunk(direct) == direct ? "ok" : (++failures, "FAIL"));

    free(raw);
    printf("%s\n", failures == 0 ? "all checks passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
