#include "guard.h"
#include "log.h"

#include <csetjmp>
#include <csignal>
#include <cstring>
#include <pthread.h>

namespace zyrex {
namespace {

struct sigaction g_old_segv{};
struct sigaction g_old_bus{};
bool             g_installed = false;

pthread_t        g_owner{};
bool             g_owner_valid = false;

// Only touched by the owning thread, and by the handler running on that same
// thread, so plain globals are sufficient here.
volatile sig_atomic_t g_active = 0;
sigjmp_buf            g_jmp;
volatile sig_atomic_t g_faults = 0;

void forward(int sig, siginfo_t* info, void* ctx, struct sigaction& old) {
    if (old.sa_flags & SA_SIGINFO) {
        if (old.sa_sigaction) {
            old.sa_sigaction(sig, info, ctx);
            return;
        }
    } else if (old.sa_handler == SIG_IGN) {
        return;
    } else if (old.sa_handler != SIG_DFL && old.sa_handler != nullptr) {
        old.sa_handler(sig);
        return;
    }
    // No usable previous handler: restore the default and let it fault again so
    // the platform crash path behaves exactly as it would have without us.
    struct sigaction dfl{};
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);
}

void handler(int sig, siginfo_t* info, void* ctx) {
    const bool mine = g_owner_valid && pthread_equal(pthread_self(), g_owner);
    if (mine && g_active) {
        g_faults++;
        g_active = 0;
        // Unwinding out of a signal handler via siglongjmp is the standard
        // technique for this; the saved mask is restored because sigsetjmp was
        // called with savemask=1.
        siglongjmp(g_jmp, 1);
    }
    forward(sig, info, ctx, sig == SIGBUS ? g_old_bus : g_old_segv);
}

} // namespace

bool guard_install() {
    if (g_installed) return true;

    // SA_ONSTACK does nothing without an alternate stack, and the one fault we
    // cannot otherwise survive is one raised while the normal stack is already
    // unusable. 64 KiB is plenty for the handler's single siglongjmp.
    static char alt_stack[65536];
    stack_t ss{};
    ss.ss_sp = alt_stack;
    ss.ss_size = sizeof(alt_stack);
    ss.ss_flags = 0;
    if (sigaltstack(&ss, nullptr) != 0) {
        LOGW("guard: sigaltstack failed, continuing on the normal stack");
    }

    struct sigaction sa{};
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    if (sigaction(SIGSEGV, &sa, &g_old_segv) != 0) {
        LOGE("guard: sigaction(SIGSEGV) failed");
        return false;
    }
    if (sigaction(SIGBUS, &sa, &g_old_bus) != 0) {
        LOGE("guard: sigaction(SIGBUS) failed");
        sigaction(SIGSEGV, &g_old_segv, nullptr);
        return false;
    }

    g_owner = pthread_self();
    g_owner_valid = true;
    g_installed = true;
    return true;
}

void guard_remove() {
    if (!g_installed) return;
    sigaction(SIGSEGV, &g_old_segv, nullptr);
    sigaction(SIGBUS,  &g_old_bus,  nullptr);
    g_installed = false;
    g_owner_valid = false;
    g_active = 0;
}

bool guard_run(void (*fn)(void*), void* arg) {
    if (!fn) return false;
    if (!g_installed) {          // no guard available — run bare
        fn(arg);
        return true;
    }
    if (sigsetjmp(g_jmp, 1) != 0) {
        return false;            // unwound from a fault
    }
    g_active = 1;
    fn(arg);
    g_active = 0;
    return true;
}

int guard_fault_count() { return (int)g_faults; }

} // namespace zyrex
