#pragma once
#include <jni.h>
#include <string>
#include <android/log.h>

#define UNIQUE_LOG_TAG "UniqueNative"
#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO,  UNIQUE_LOG_TAG, __VA_ARGS__)
#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN,  UNIQUE_LOG_TAG, __VA_ARGS__)
#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, UNIQUE_LOG_TAG, __VA_ARGS__)

#define UNIQUE_EXPORT extern "C" __attribute__((visibility("default")))

namespace unique {

// Status of a subsystem that must be installed before virtual app code runs.
// Reported to Kotlin and surfaced in Diagnostics, so a subsystem that is not yet
// implemented is visible as such rather than appearing to work.
enum class InstallStatus : int {
    kOk = 0,
    kNotImplemented = 1,   // built, but the mechanism is not wired up yet
    kUnsupportedDevice = 2,
    kFailed = 3,
    kAlreadyInstalled = 4, // installed earlier in this process; nothing to do
};

namespace io_redirect {
// Replaces the process-wide rule table. Safe to call before any hooks exist.
void set_rules(const char** from, const char** to, int count);
void clear_rules();
int rule_count();
// Rewrites `path` per the current table. Returns an empty string when no rule matched.
std::string redirect(const char* path);
// Limits the interception to libraries whose path contains one of these substrings.
// Must be set before install(), which refuses an empty scope: patching every library in
// the process would redirect UNIQUE's own file operations too.
void set_scope(const char** paths, int count);
// Installs the libc interception into the scoped libraries. Idempotent, and meant to be
// repeated after the guest loads more libraries.
InstallStatus install();
bool installed();
// GOT entries actually patched by the last install().
int slots_patched();
// Re-hooks automatically when a library is loaded after install(). Idempotent.
InstallStatus watch_library_loads();
bool watching();
}  // namespace io_redirect

namespace prop_virtual {
void set_property(const char* key, const char* value);
void clear_properties();
int property_count();
// Returns nullptr when the key is not overridden and the host value should be used.
const char* lookup(const char* key);
InstallStatus install();
bool installed();
}  // namespace prop_virtual

namespace crash {
// Records a native crash to `path` before letting the process die.
//
// The file is opened here rather than in the handler, and the previous handler is chained
// to rather than replaced, so the platform still produces its tombstone. Idempotent per
// process.
InstallStatus install(const char* path);
}  // namespace crash

}  // namespace unique
