#pragma once
#include <jni.h>
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
};

namespace io_redirect {
// Replaces the process-wide rule table. Safe to call before any hooks exist.
void set_rules(const char** from, const char** to, int count);
void clear_rules();
int rule_count();
// Rewrites `path` per the current table. Returns an empty string when no rule matched.
std::string redirect(const char* path);
// Installs the libc interception. See the implementation for what is and is not done.
InstallStatus install();
bool installed();
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

}  // namespace unique
