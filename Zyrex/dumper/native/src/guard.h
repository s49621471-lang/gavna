// Zyrex — fault guard
//
// The dumper walks runtime metadata that a protector has already tampered with,
// so a malformed class can hand us a bad pointer. Without a guard that is an
// instant SIGSEGV and the game dies on launch, which is the one outcome worth
// engineering against. With it, the offending class is skipped and the dump
// continues.
//
// Scope is deliberately narrow:
//   * handlers are installed only for the duration of the dump
//   * they only act on faults raised by the dumper's own thread; anything else
//     is forwarded to whatever handler was installed before us (the game ships
//     a crash reporter, and breaking it would be worse than the bug it reports)
//   * the previous disposition is restored when the dump finishes

#pragma once

namespace zyrex {

// Installs SIGSEGV/SIGBUS handlers for the calling thread's guarded regions.
// Safe to call once; returns false if sigaction fails.
bool guard_install();

// Restores the handlers that were in place before guard_install().
void guard_remove();

// Runs fn(arg) under the guard. Returns true on normal completion, false if a
// fault was caught and unwound. Nestable calls are not supported — one level.
bool guard_run(void (*fn)(void*), void* arg);

// Number of faults absorbed so far, for the dump report.
int guard_fault_count();

} // namespace zyrex
