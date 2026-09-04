#include <cstdarg>
#include <cstdio>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>
#include <vector>

#include "plt_hook.h"
#include "redirect_table.h"
#include "unique_native.h"

namespace unique::io_redirect {
namespace {

RedirectTable g_table;
std::mutex g_mutex;
bool g_installed = false;

}  // namespace

void set_rules(const char** from, const char** to, int count) {
    std::vector<RedirectRule> rules;
    rules.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        if (from[i] == nullptr || to[i] == nullptr) continue;
        rules.push_back(RedirectRule{from[i], to[i]});
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    g_table.set(std::move(rules));
    ULOGI("redirect table set: %zu rule(s)", g_table.size());
}

void clear_rules() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_table.clear();
}

int rule_count() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<int>(g_table.size());
}

std::string redirect(const char* path) {
    std::string out;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_table.redirect(path, out)) return out;
    return {};
}

bool installed() { return g_installed; }

// ---------------------------------------------------------------------------------
// The interception.
//
// Every trampoline has the same three-line shape: rewrite the path if a rule matches,
// call through to the original, done. The uniformity is the point - a trampoline that
// does anything else is a place for a bug to hide in code that runs on every file
// operation a virtual app performs.
//
// Only the *outermost* call matters. A guest calling fopen() reaches open() inside libc
// without crossing a PLT, and that is correct: the path was already rewritten on the way
// in, and rewriting it twice would be wrong.
// ---------------------------------------------------------------------------------

// Declared at namespace scope, deliberately: inside the anonymous namespace below this
// would declare a *different* symbol from the definition further down, and the call from
// the trampolines becomes ambiguous rather than resolving to the real one.
InstallStatus install_locked();

namespace {

// Originals, filled by the PLT hook. Null until install() runs, and every trampoline
// falls back to the libc symbol if it is - so a partially installed hook degrades to
// "no redirection" rather than a null call.
int (*o_open)(const char*, int, ...) = nullptr;
int (*o_openat)(int, const char*, int, ...) = nullptr;
int (*o_stat)(const char*, struct stat*) = nullptr;
int (*o_lstat)(const char*, struct stat*) = nullptr;
int (*o_access)(const char*, int) = nullptr;
int (*o_mkdir)(const char*, mode_t) = nullptr;
int (*o_rmdir)(const char*) = nullptr;
int (*o_unlink)(const char*) = nullptr;
int (*o_rename)(const char*, const char*) = nullptr;
int (*o_chmod)(const char*, mode_t) = nullptr;
DIR* (*o_opendir)(const char*) = nullptr;
FILE* (*o_fopen)(const char*, const char*) = nullptr;
ssize_t (*o_readlink)(const char*, char*, size_t) = nullptr;
int (*o_statfs)(const char*, struct statfs*) = nullptr;

/// Rewrites `path` when a rule matches, otherwise hands back the original pointer.
///
/// The `holder` keeps the rewritten string alive for the duration of the call. Returning
/// a std::string by value and taking .c_str() of a temporary is the obvious way to write
/// this and is a dangling pointer.
const char* rewrite(const char* path, std::string& holder) {
    if (path == nullptr) return nullptr;
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_table.redirect(path, out)) return path;
    }
    holder = std::move(out);
    return holder.c_str();
}

int h_open(const char* path, int flags, ...) {
    std::string holder;
    const char* target = rewrite(path, holder);
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, unsigned));
        va_end(args);
    }
    return o_open != nullptr ? o_open(target, flags, mode) : ::open(target, flags, mode);
}

int h_openat(int dirfd, const char* path, int flags, ...) {
    std::string holder;
    const char* target = rewrite(path, holder);
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, unsigned));
        va_end(args);
    }
    return o_openat != nullptr ? o_openat(dirfd, target, flags, mode)
                               : ::openat(dirfd, target, flags, mode);
}

#define UNIQUE_TRAMPOLINE_1(name, ret, sig_type)                       \
    ret h_##name(const char* path) {                                   \
        std::string holder;                                            \
        const char* target = rewrite(path, holder);                    \
        return o_##name != nullptr ? o_##name(target) : ::name(target); \
    }

UNIQUE_TRAMPOLINE_1(rmdir, int, int)
UNIQUE_TRAMPOLINE_1(unlink, int, int)
UNIQUE_TRAMPOLINE_1(opendir, DIR*, DIR*)

int h_stat(const char* path, struct stat* out) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_stat != nullptr ? o_stat(target, out) : ::stat(target, out);
}

int h_lstat(const char* path, struct stat* out) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_lstat != nullptr ? o_lstat(target, out) : ::lstat(target, out);
}

int h_access(const char* path, int mode) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_access != nullptr ? o_access(target, mode) : ::access(target, mode);
}

int h_mkdir(const char* path, mode_t mode) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_mkdir != nullptr ? o_mkdir(target, mode) : ::mkdir(target, mode);
}

int h_chmod(const char* path, mode_t mode) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_chmod != nullptr ? o_chmod(target, mode) : ::chmod(target, mode);
}

int h_rename(const char* from, const char* to) {
    std::string from_holder, to_holder;
    const char* from_target = rewrite(from, from_holder);
    const char* to_target = rewrite(to, to_holder);
    return o_rename != nullptr ? o_rename(from_target, to_target)
                               : ::rename(from_target, to_target);
}

FILE* h_fopen(const char* path, const char* mode) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_fopen != nullptr ? o_fopen(target, mode) : ::fopen(target, mode);
}

ssize_t h_readlink(const char* path, char* buf, size_t size) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_readlink != nullptr ? o_readlink(target, buf, size)
                                 : ::readlink(target, buf, size);
}

int h_statfs(const char* path, struct statfs* out) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_statfs != nullptr ? o_statfs(target, out) : ::statfs(target, out);
}

std::vector<std::string> g_filters;
int g_slots_patched = 0;
bool g_watching = false;

// The loader entry points, captured when the watch is installed.
void* (*o_android_dlopen_ext)(const char*, int, const void*, const void*) = nullptr;
void* (*o_dlopen)(const char*, int) = nullptr;

/// True while this thread is already re-scanning, so a load triggered from inside the
/// scan cannot recurse into it.
thread_local bool t_rescanning = false;

/// Re-hooks after a library has finished loading.
///
/// Called *after* the original returns, which matters: at that point the dynamic linker
/// has released its own lock, and `dl_iterate_phdr` — which install() uses and which
/// takes the same lock — can run without deadlocking. Doing this from inside the linker
/// would hang the process on a non-recursive mutex.
void rescan_after_load(const char* what) {
    if (t_rescanning) return;
    t_rescanning = true;
    const int before = g_slots_patched;
    install_locked();
    if (g_slots_patched != before) {
        ULOGI("io_redirect: rehooked after loading %s (%d -> %d slots)",
              what == nullptr ? "?" : what, before, g_slots_patched);
    }
    t_rescanning = false;
}

void* h_android_dlopen_ext(const char* path, int flags, const void* extinfo,
                           const void* caller) {
    void* handle = o_android_dlopen_ext != nullptr
            ? o_android_dlopen_ext(path, flags, extinfo, caller)
            : nullptr;
    if (handle != nullptr) rescan_after_load(path);
    return handle;
}

void* h_dlopen(const char* path, int flags) {
    void* handle = o_dlopen != nullptr ? o_dlopen(path, flags) : ::dlopen(path, flags);
    if (handle != nullptr) rescan_after_load(path);
    return handle;
}

}  // namespace

void set_scope(const char** paths, int count) {
    std::vector<std::string> filters;
    for (int i = 0; i < count; ++i) {
        if (paths[i] != nullptr) filters.emplace_back(paths[i]);
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    g_filters = std::move(filters);
}

int slots_patched() { return g_slots_patched; }

/// Installs the interception into the libraries named by set_scope().
///
/// Idempotent, and meant to be repeated: a library the guest loads later has its own GOT
/// and is not covered by an earlier pass. Callers re-run this after System.loadLibrary.
/// The scan itself. Split out so the loader trampolines can re-run it without recursing
/// through the public entry point's scope checks.
InstallStatus install_locked() {
    plt::HookRequest requests[] = {
        {"open",     reinterpret_cast<void*>(h_open),     reinterpret_cast<void**>(&o_open)},
        {"openat",   reinterpret_cast<void*>(h_openat),   reinterpret_cast<void**>(&o_openat)},
        {"stat",     reinterpret_cast<void*>(h_stat),     reinterpret_cast<void**>(&o_stat)},
        {"lstat",    reinterpret_cast<void*>(h_lstat),    reinterpret_cast<void**>(&o_lstat)},
        {"access",   reinterpret_cast<void*>(h_access),   reinterpret_cast<void**>(&o_access)},
        {"mkdir",    reinterpret_cast<void*>(h_mkdir),    reinterpret_cast<void**>(&o_mkdir)},
        {"rmdir",    reinterpret_cast<void*>(h_rmdir),    reinterpret_cast<void**>(&o_rmdir)},
        {"unlink",   reinterpret_cast<void*>(h_unlink),   reinterpret_cast<void**>(&o_unlink)},
        {"rename",   reinterpret_cast<void*>(h_rename),   reinterpret_cast<void**>(&o_rename)},
        {"chmod",    reinterpret_cast<void*>(h_chmod),    reinterpret_cast<void**>(&o_chmod)},
        {"opendir",  reinterpret_cast<void*>(h_opendir),  reinterpret_cast<void**>(&o_opendir)},
        {"fopen",    reinterpret_cast<void*>(h_fopen),    reinterpret_cast<void**>(&o_fopen)},
        {"readlink", reinterpret_cast<void*>(h_readlink), reinterpret_cast<void**>(&o_readlink)},
        {"statfs",   reinterpret_cast<void*>(h_statfs),   reinterpret_cast<void**>(&o_statfs)},
    };

    std::vector<std::string> filters;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        filters = g_filters;
    }
    if (filters.empty()) {
        // Refused rather than applied everywhere. An unscoped hook would patch UNIQUE's
        // own libraries and the platform's, and "redirect every file operation in the
        // process" is not a thing to do by accident.
        ULOGW("io_redirect::install() refused: no scope set");
        g_installed = false;
        return InstallStatus::kFailed;
    }

    auto report = plt::hook_all(filters, requests,
                                sizeof(requests) / sizeof(requests[0]));
    g_slots_patched = report.slots_patched;
    for (const auto& failure : report.failures) {
        ULOGE("io_redirect: %s", failure.c_str());
    }
    ULOGI("io_redirect installed: %d slot(s) in %d/%d libraries, %d rule(s), page size %ld",
          report.slots_patched, report.libraries_matched, report.libraries_scanned,
          rule_count(), sysconf(_SC_PAGESIZE));
    if (report.libraries_matched == 0) {
        for (const auto& filter : filters) {
            ULOGW("io_redirect: filter did not match: %s", filter.c_str());
        }
        for (const auto& name : report.sample) {
            ULOGW("io_redirect: app-private library seen: %s", name.c_str());
        }
    }

    // Zero patched slots is not a failure on its own - a guest with no native code has
    // nothing to hook - but it must not be reported as a working interception.
    g_installed = report.slots_patched > 0;
    return report.slots_patched > 0 ? InstallStatus::kOk : InstallStatus::kNotImplemented;
}

InstallStatus install() { return install_locked(); }

/**
 * Notices libraries loaded *after* the initial scan.
 *
 * The initial hook walks what is loaded at that moment, so a library the guest loads
 * later - `System.loadLibrary` from an Activity, or one native library `dlopen`ing
 * another - has its own untouched GOT and none of its file operations are redirected.
 * That was recorded as broken rather than papered over; this closes it.
 *
 * `System.loadLibrary` reaches the linker through `libnativeloader`, so the notification
 * point is that library's call to `android_dlopen_ext` - one symbol, in one system
 * library, and the trampoline only calls the original and then re-scans. It rewrites
 * nothing and redirects nothing, which is what makes hooking outside the guest's own code
 * acceptable here when redirecting IO there would not be.
 *
 * The guest's own libraries are hooked too, so a native plugin loader is covered as well.
 */
InstallStatus watch_library_loads() {
    if (g_watching) return InstallStatus::kOk;

    plt::HookRequest requests[] = {
        {"android_dlopen_ext", reinterpret_cast<void*>(h_android_dlopen_ext),
         reinterpret_cast<void**>(&o_android_dlopen_ext)},
        {"dlopen", reinterpret_cast<void*>(h_dlopen), reinterpret_cast<void**>(&o_dlopen)},
    };

    // Narrow and explicit: the loader plumbing, plus the guest's own code.
    std::vector<std::string> scope;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        scope = g_filters;
    }
    scope.emplace_back("libnativeloader.so");
    scope.emplace_back("libart.so");

    auto report = plt::hook_all(scope, requests, sizeof(requests) / sizeof(requests[0]));
    g_watching = report.slots_patched > 0;
    ULOGI("io_redirect: library-load watch %s (%d slot(s) in %d libraries)",
          g_watching ? "installed" : "found nothing to hook",
          report.slots_patched, report.libraries_matched);
    for (const auto& name : report.sample) {
        ULOGW("io_redirect: watch saw but did not match: %s", name.c_str());
    }
    return g_watching ? InstallStatus::kOk : InstallStatus::kNotImplemented;
}

bool watching() { return g_watching; }

}  // namespace unique::io_redirect
