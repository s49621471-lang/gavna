// A native crash record that outlives the process.
//
// Engineering rule 10 says every crash leaves a diagnostic trace, and until now that was
// true only of Java crashes: a SIGSEGV inside a guest's own `.so` left the platform's
// tombstone and UNIQUE's events up to the moment of the crash, but nothing written *by*
// UNIQUE — so a user's diagnostics export said "the app stopped" and no more.
//
// ## What a signal handler may do
//
// Almost nothing. It runs on a thread whose state is undefined, possibly with the heap
// lock held by the code that just crashed, so `malloc`, `snprintf`, `dladdr` and anything
// that takes a lock are all unavailable — not as a matter of style, but because using one
// turns a crash that would have produced a tombstone into a hang that produces nothing.
//
// So: the file is opened *before* the handler is installed, the record is formatted into a
// stack buffer with hand-rolled integer conversion, and it is written with a single
// `write(2)`. Nothing here allocates and nothing here locks.
//
// Symbolisation is deliberately absent. Resolving an address to a library and offset means
// `dladdr`, which takes the linker's lock — and a crash *inside* the linker is not rare.
// The addresses are written raw; the platform's own tombstone, which is still produced
// because the previous handler is chained to, is where they get names.

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#include "unique_native.h"

namespace unique {
namespace crash {

namespace {

constexpr int kSignals[] = {SIGSEGV, SIGBUS, SIGFPE, SIGILL, SIGABRT, SIGTRAP};
constexpr size_t kSignalCount = sizeof(kSignals) / sizeof(kSignals[0]);

int g_fd = -1;
bool g_installed = false;
struct sigaction g_previous[kSignalCount];

/// The alternate stack. A SIGSEGV from a stack overflow arrives with no usable stack left,
/// and a handler that needs one is the handler that never runs.
char* g_alt_stack = nullptr;
constexpr size_t kAltStackSize = 64 * 1024;

/// Appends a NUL-terminated string. Bounded, and async-signal-safe.
size_t append(char* buf, size_t cap, size_t at, const char* text) {
    while (*text != '\0' && at < cap - 1) buf[at++] = *text++;
    return at;
}

/// Appends an unsigned value in hex. No snprintf: it is not async-signal-safe.
size_t append_hex(char* buf, size_t cap, size_t at, unsigned long value) {
    static const char kDigits[] = "0123456789abcdef";
    char tmp[sizeof(unsigned long) * 2 + 1];
    int n = 0;
    if (value == 0) {
        tmp[n++] = '0';
    } else {
        while (value != 0 && n < static_cast<int>(sizeof(tmp))) {
            tmp[n++] = kDigits[value & 0xf];
            value >>= 4;
        }
    }
    at = append(buf, cap, at, "0x");
    while (n > 0 && at < cap - 1) buf[at++] = tmp[--n];
    return at;
}

size_t append_dec(char* buf, size_t cap, size_t at, long value) {
    char tmp[24];
    int n = 0;
    bool negative = value < 0;
    unsigned long v = negative ? static_cast<unsigned long>(-value) : static_cast<unsigned long>(value);
    if (v == 0) {
        tmp[n++] = '0';
    } else {
        while (v != 0 && n < static_cast<int>(sizeof(tmp))) {
            tmp[n++] = static_cast<char>('0' + (v % 10));
            v /= 10;
        }
    }
    if (negative && at < cap - 1) buf[at++] = '-';
    while (n > 0 && at < cap - 1) buf[at++] = tmp[--n];
    return at;
}

const char* signal_name(int signo) {
    switch (signo) {
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGABRT: return "SIGABRT";
        case SIGTRAP: return "SIGTRAP";
        default:      return "SIGNAL";
    }
}

int index_of(int signo) {
    for (size_t i = 0; i < kSignalCount; i++) {
        if (kSignals[i] == signo) return static_cast<int>(i);
    }
    return -1;
}

void handler(int signo, siginfo_t* info, void* context) {
    if (g_fd >= 0) {
        char buf[512];
        size_t at = 0;
        at = append(buf, sizeof(buf), at, "signal=");
        at = append(buf, sizeof(buf), at, signal_name(signo));
        at = append(buf, sizeof(buf), at, "\nsignalNumber=");
        at = append_dec(buf, sizeof(buf), at, signo);
        at = append(buf, sizeof(buf), at, "\ncode=");
        at = append_dec(buf, sizeof(buf), at, info != nullptr ? info->si_code : 0);
        at = append(buf, sizeof(buf), at, "\nfaultAddress=");
        at = append_hex(buf, sizeof(buf), at,
                        reinterpret_cast<unsigned long>(info != nullptr ? info->si_addr : nullptr));
        at = append(buf, sizeof(buf), at, "\npid=");
        at = append_dec(buf, sizeof(buf), at, getpid());
        at = append(buf, sizeof(buf), at, "\ntid=");
        at = append_dec(buf, sizeof(buf), at, gettid());
        at = append(buf, sizeof(buf), at, "\n");
        buf[at] = '\0';

        // One write, and its result deliberately ignored: there is nothing useful to do
        // about a failed write from inside a signal handler, and checking it would only
        // add a branch on the path to the tombstone.
        ssize_t ignored = write(g_fd, buf, at);
        (void) ignored;
        fsync(g_fd);
    }

    // Chain to whatever was there before — normally the platform's own handler, which is
    // what produces the tombstone. UNIQUE adds a record; it does not take one away, and a
    // handler that swallowed the signal would leave the process in an undefined state
    // (§14.3).
    const int idx = index_of(signo);
    if (idx >= 0 && g_previous[idx].sa_sigaction != nullptr &&
        (g_previous[idx].sa_flags & SA_SIGINFO) != 0) {
        g_previous[idx].sa_sigaction(signo, info, context);
        return;
    }
    if (idx >= 0 && g_previous[idx].sa_handler != SIG_DFL &&
        g_previous[idx].sa_handler != SIG_IGN) {
        g_previous[idx].sa_handler(signo);
        return;
    }
    // No previous handler: restore the default and re-raise, so the process dies the way
    // it would have.
    signal(signo, SIG_DFL);
    raise(signo);
}

}  // namespace

InstallStatus install(const char* path) {
    if (g_installed) return InstallStatus::kAlreadyInstalled;
    if (path == nullptr || *path == '\0') return InstallStatus::kFailed;

    // Opened here, not in the handler: open() is not async-signal-safe, and a handler that
    // cannot open its own output file is a handler that reports nothing.
    g_fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (g_fd < 0) {
        ULOGE("crash: cannot open %s (%s)", path, strerror(errno));
        return InstallStatus::kFailed;
    }

    if (g_alt_stack == nullptr) {
        g_alt_stack = new char[kAltStackSize];
        stack_t ss;
        memset(&ss, 0, sizeof(ss));
        ss.ss_sp = g_alt_stack;
        ss.ss_size = kAltStackSize;
        ss.ss_flags = 0;
        if (sigaltstack(&ss, nullptr) != 0) {
            ULOGW("crash: sigaltstack failed (%s); a stack overflow will not be recorded",
                  strerror(errno));
        }
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&sa.sa_mask);

    int installed = 0;
    for (size_t i = 0; i < kSignalCount; i++) {
        if (sigaction(kSignals[i], &sa, &g_previous[i]) == 0) {
            installed++;
        } else {
            ULOGW("crash: cannot hook %s (%s)", signal_name(kSignals[i]), strerror(errno));
        }
    }
    if (installed == 0) {
        close(g_fd);
        g_fd = -1;
        return InstallStatus::kFailed;
    }
    g_installed = true;
    ULOGI("crash: recording %d signal(s) to %s", installed, path);
    return InstallStatus::kOk;
}

}  // namespace crash
}  // namespace unique

UNIQUE_EXPORT JNIEXPORT jint JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeInstallCrashHandler(
        JNIEnv* env, jclass, jstring path) {
    const char* chars = path == nullptr ? nullptr : env->GetStringUTFChars(path, nullptr);
    const auto status = unique::crash::install(chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    return static_cast<jint>(status);
}
