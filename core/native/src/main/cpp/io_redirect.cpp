#include <string>
#include <vector>
#include <mutex>
#include <unistd.h>

#include "unique_native.h"
#include "redirect_table.h"

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

// TODO(phase-4): install the libc interception.
//
// This is deliberately NOT implemented yet, and reports itself as not implemented rather
// than pretending to succeed. The redirection *table* above is complete and tested
// (tools/native-test); what is missing is the interception of the ~30 libc entry points
// listed in ARCHITECTURE.md §4.3, which must be done with ShadowHook and, critically,
// verified on a real ARM64 device across Android 12-16. Shipping untested inline-hook
// code that claims to work would be worse than shipping none: a partially hooked libc
// corrupts data silently.
//
// Until this lands, Java-level path correctness (core/vstorage) covers apps that resolve
// paths through Context accessors, which is the large majority. Apps that hard-code
// absolute paths in native code are recorded as PARTIAL in the compatibility database.
//
// Scope when implemented:
//   - shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) at process start
//   - hook open/openat/openat2, stat/lstat/fstatat, access/faccessat, mkdir/mkdirat,
//     unlink/unlinkat, rename/renameat/renameat2, chmod/chown, link/symlink/readlink,
//     opendir, statfs/statvfs, execve, dlopen/android_dlopen_ext
//   - every trampoline uses sysconf(_SC_PAGESIZE), never a hard-coded 4096, so it is
//     correct on 16 KB-page devices
InstallStatus install() {
    ULOGW("io_redirect::install() is not implemented yet (see TODO(phase-4)); "
          "page size = %ld, rules loaded = %d",
          sysconf(_SC_PAGESIZE), rule_count());
    g_installed = false;
    return InstallStatus::kNotImplemented;
}

}  // namespace unique::io_redirect
