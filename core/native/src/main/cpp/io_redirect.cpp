#include <cstdarg>
#include <cstdio>
#include <cerrno>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>
#include <vector>

#include "plt_hook.h"
#include "proc_view.h"
#include "redirect_table.h"
#include "unique_native.h"

namespace unique::io_redirect {
namespace {

RedirectTable g_table;
ProcView g_proc_view;
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

void set_proc_view(const char** from, const char** to, int count) {
    std::vector<RedirectRule> rules;
    rules.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        if (from[i] == nullptr || to[i] == nullptr) continue;
        rules.push_back(RedirectRule{from[i], to[i]});
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    g_proc_view.set(std::move(rules));
    ULOGI("proc view set: %zu rule(s)", g_proc_view.size());
}

void clear_proc_view() {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_proc_view.clear();
}

int proc_view_rule_count() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<int>(g_proc_view.size());
}

std::string proc_view_rewrite(const char* text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_proc_view.rewrite(text == nullptr ? std::string() : std::string(text));
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

/// Reads a `/proc` pseudo-file whole, without going back through the hooks.
///
/// `st_size` is zero for everything under `/proc`, so the only way to know how much there
/// is, is to read until there is not any more. A Unity game's maps runs to a few hundred
/// kilobytes; the growth below reaches that in five reads.
std::string read_all(const char* path) {
    const int fd = o_open != nullptr ? o_open(path, O_RDONLY | O_CLOEXEC, 0)
                                     : ::open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string out;
    char buffer[16384];
    for (;;) {
        const ssize_t n = ::read(fd, buffer, sizeof(buffer));
        if (n > 0) {
            out.append(buffer, static_cast<size_t>(n));
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        break;
    }
    ::close(fd);
    return out;
}

/// A read-only file descriptor holding [text], or -1.
///
/// `memfd_create` rather than a file on disk: a temporary file would be one more thing in
/// the guest's own directory for the guest to find, would need cleaning up after a crash,
/// and would appear in the very list this is rewriting.
int fd_holding(const std::string& text, bool cloexec) {
    const int fd = ::memfd_create("maps", cloexec ? MFD_CLOEXEC : 0u);
    if (fd < 0) return -1;
    size_t written = 0;
    while (written < text.size()) {
        const ssize_t n = ::write(fd, text.data() + written, text.size() - written);
        if (n > 0) {
            written += static_cast<size_t>(n);
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        ::close(fd);
        return -1;
    }
    if (::lseek(fd, 0, SEEK_SET) < 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

/// Serves [path] from the guest's view of it, or -1 to let the real open proceed.
///
/// Every failure falls through to the real file. A guest that can read its own maps and
/// sees UNIQUE in them is a guest that may refuse to run; a guest that cannot read them
/// at all is one that crashes in its own crash handler.
int serve_proc(const char* path, bool cloexec) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_proc_view.empty()) return -1;
        if (!ProcView::covers(path, ::getpid())) return -1;
    }
    const std::string real = read_all(path);
    if (real.empty()) return -1;
    std::string shown;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        shown = g_proc_view.rewrite(real);
    }
    return fd_holding(shown, cloexec);
}

int h_open(const char* path, int flags, ...) {
    const int served = serve_proc(path, (flags & O_CLOEXEC) != 0);
    if (served >= 0) return served;
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
    const int served = serve_proc(path, (flags & O_CLOEXEC) != 0);
    if (served >= 0) return served;
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
    // `fopen("/proc/self/maps", "r")` is the commonest spelling of the check by a long
    // way, and it does not reach h_open: libc calls its own open internally, without
    // crossing a PLT. Serving it here is what makes the view actually cover anything.
    const int served = serve_proc(path, mode != nullptr && std::strchr(mode, 'e') != nullptr);
    if (served >= 0) {
        FILE* stream = ::fdopen(served, "r");
        if (stream != nullptr) return stream;
        ::close(served);
    }
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_fopen != nullptr ? o_fopen(target, mode) : ::fopen(target, mode);
}

ssize_t h_readlink(const char* path, char* buf, size_t size) {
    std::string holder;
    const char* target = rewrite(path, holder);
    const ssize_t n = o_readlink != nullptr ? o_readlink(target, buf, size)
                                            : ::readlink(target, buf, size);
    if (n <= 0 || buf == nullptr) return n;

    // The answer is a path, and under `/proc/self/fd` it is a path into UNIQUE. An app
    // that walks its own open descriptors — which a protector does, looking for exactly
    // this — reads the APK it was loaded from by name.
    //
    // `readlink` does not terminate the buffer and the caller is not entitled to a byte
    // past what is returned, so a longer answer is truncated rather than written past the
    // end. Truncation is what the real call does when the buffer is short.
    std::string shown;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_proc_view.empty()) return n;
        if (!g_proc_view.rewrite_path(buf, static_cast<size_t>(n), shown)) return n;
    }
    const size_t copied = shown.size() < size ? shown.size() : size;
    std::memcpy(buf, shown.data(), copied);
    return static_cast<ssize_t>(copied);
}

int h_statfs(const char* path, struct statfs* out) {
    std::string holder;
    const char* target = rewrite(path, holder);
    return o_statfs != nullptr ? o_statfs(target, out) : ::statfs(target, out);
}

std::vector<std::string> g_filters;
std::vector<std::string> g_excludes;

/// Every PLT slot this process has patched, since the process started.
///
/// Cumulative, not "what the last scan found". A re-scan after a late dlopen legitimately
/// finds zero *new* slots — the ones already patched no longer point at the original libc
/// symbols, so they do not match again — and overwriting the total with that zero produced
/// a diagnostic that read as though the hooks had been lost:
///
///   io_redirect: rehooked after loading libprobevulkan.so (1 -> 0 slots)
///
/// They had not. But a number that only looks like a regression is worse than no number,
/// because it is the number someone will believe while looking for a bug that is not there.
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
    const int added = g_slots_patched - before;
    if (added > 0) {
        ULOGI("io_redirect: hooked %d new slot(s) after loading %s (%d total)",
              added, what == nullptr ? "?" : what, g_slots_patched);
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

void set_exclusions(const char** paths, int count) {
    std::vector<std::string> excludes;
    for (int i = 0; i < count; ++i) {
        if (paths[i] != nullptr) excludes.emplace_back(paths[i]);
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    g_excludes = std::move(excludes);
    ULOGI("io_redirect: %zu exclusion(s) set", g_excludes.size());
}

int exclusion_count() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<int>(g_excludes.size());
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
    std::vector<std::string> excludes;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        filters = g_filters;
        excludes = g_excludes;
    }
    if (filters.empty()) {
        // Refused rather than applied everywhere. An unscoped hook would patch UNIQUE's
        // own libraries and the platform's, and "redirect every file operation in the
        // process" is not a thing to do by accident.
        ULOGW("io_redirect::install() refused: no scope set");
        g_installed = false;
        return InstallStatus::kFailed;
    }

    auto report = plt::hook_all(filters, excludes, requests,
                                sizeof(requests) / sizeof(requests[0]));
    g_slots_patched += report.slots_patched;
    for (const auto& failure : report.failures) {
        ULOGE("io_redirect: %s", failure.c_str());
    }
    for (const auto& name : report.excluded) {
        ULOGI("io_redirect: excluded (not hooked, on purpose): %s", name.c_str());
    }
    ULOGI("io_redirect installed: %d slot(s) in %d/%d libraries (%d excluded, %d total), "
          "%d rule(s), page size %ld",
          report.slots_patched, report.libraries_matched, report.libraries_scanned,
          report.libraries_excluded, g_slots_patched, rule_count(), sysconf(_SC_PAGESIZE));
    if (report.libraries_matched == 0) {
        for (const auto& filter : filters) {
            ULOGW("io_redirect: filter did not match: %s", filter.c_str());
        }
        for (const auto& name : report.sample) {
            ULOGW("io_redirect: app-private library seen: %s", name.c_str());
        }
    }

    // Zero patched slots is not a failure on its own - a guest with no native code has
    // nothing to hook, and one that loads its libraries later has nothing to hook *yet* -
    // but it must not be reported as a working interception either. kNothingToHook says
    // both, where the not-implemented status used to say neither.
    g_installed = report.slots_patched > 0;
    return report.slots_patched > 0 ? InstallStatus::kOk : InstallStatus::kNothingToHook;
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

    // No exclusions here, deliberately: this hooks `dlopen` so that install_locked can
    // run again afterwards, and install_locked is where the exclusions apply. Excluding a
    // protector from the *watch* would mean a library it loads is never redirected at
    // all, which is a different and larger loss than not redirecting the protector.
    auto report = plt::hook_all(scope, {}, requests, sizeof(requests) / sizeof(requests[0]));
    g_watching = report.slots_patched > 0;
    ULOGI("io_redirect: library-load watch %s (%d slot(s) in %d libraries)",
          g_watching ? "installed" : "found nothing to hook",
          report.slots_patched, report.libraries_matched);
    for (const auto& name : report.sample) {
        ULOGW("io_redirect: watch saw but did not match: %s", name.c_str());
    }
    // Not kNothingToHook: the scope here is libnativeloader.so and libart.so, which are
    // loaded in every process there has ever been. Matching nothing means the hook did
    // not work, and the consequence is specific - a library the guest loads after
    // bootstrap is never redirected - so it is reported as a failure and not as an empty
    // scan.
    return g_watching ? InstallStatus::kOk : InstallStatus::kFailed;
}

bool watching() { return g_watching; }

}  // namespace unique::io_redirect
