// Minimal AArch64 inline hook + code patcher.
//
// Every switchable patch is a *single* instruction store: the payload lives in a
// stub allocated within branch range of the target, and the target's first
// instruction becomes one `B stub`. A 4-byte aligned store is single-copy atomic
// on AArch64, so a thread that is executing the function while the menu toggles
// sees either the original instruction or the branch - never half of a
// multi-instruction sequence with a broken stack frame.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace gavna {

// Makes [addr, addr+size) writable, runs `words` into it and restores the
// original protection. Writes tail first.
bool WriteCode(void* dst, const uint32_t* words, size_t count);

// Single aligned instruction store plus icache flush.
bool WriteWord(void* dst, uint32_t word);

// True when the instruction is PC-relative and therefore cannot be relocated
// verbatim into a trampoline (B, BL, B.cond, CBZ/CBNZ, TBZ/TBNZ, ADR/ADRP and
// the literal-pool loads).
bool IsPcRelative(uint32_t insn);

// Copies `count` instructions into executable memory placed within ±128 MB of
// `anchor`, so a plain B can reach it. Returns nullptr when no nearby mapping
// could be obtained.
uint32_t* AllocStub(const uint32_t* words, size_t count, uintptr_t anchor);

// Installs an absolute jump at `target` to `replacement`, copying the first four
// instructions into an allocated trampoline that is returned through
// `out_original`. Fails (leaving the target untouched) when any of those four
// instructions is PC-relative.
bool InstallHook(void* target, void* replacement, void** out_original);

// Instruction encoders.
uint32_t EncBranch(uintptr_t from, uintptr_t to);  // b <to>, 0 when out of range
uint32_t EncMovzW(int reg, uint16_t imm);          // movz wN, #imm
uint32_t EncLdrWLiteral8();                        // ldr w0, [pc, #8]
uint32_t EncStrXzr(int base_reg);                  // str xzr, [xN]
uint32_t EncRet();                                 // ret

}  // namespace gavna
