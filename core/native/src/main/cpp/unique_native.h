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

    // Installed correctly, and no library in scope was loaded to hook *yet*.
    //
    // Distinct from kNotImplemented, which it used to be reported as, and the difference
    // matters to whoever reads the log. A Unity app loads its `.so` files from
    // `UnityPlayer`'s own initialiser, long after `Application.onCreate` where the
    // initial scan runs, so a healthy run of one reports zero patched slots here and is
    // covered a moment later by the dlopen watcher:
    //
    //   IO_REDIRECT_INSTALLED status=NOT_IMPLEMENTED watch=OK rules=8 slots=0
    //
    // Reading that as "the subsystem is not implemented" is wrong twice over: it is, and
    // the guest's libraries do get hooked. Only `watch` failing alongside this means the
    // guest's paths are genuinely unredirected.
    kNothingToHook = 5,
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
// Keeps the interception *out* of libraries whose path contains one of these substrings,
// even when set_scope() puts them in scope.
//
// A PLT hook is safe for ordinary code and is not safe for a library that inspects its
// own GOT. On a Redmi running Android 15, UNIQUE patched 22 slots in a Unity game's
// `libgrave.so` — a code-virtualization protector — and the process died six seconds
// later executing an unaligned address inside an anonymous page it had generated itself:
//
//   E CRASH: signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x7dd33219f7
//   E CRASH:   #00 pc 00000000000009f7  <anonymous:0000007dd3321000>
//
// The cost of an exclusion is bounded and known: that library's hard-coded
// `/data/data/<pkg>` paths are not rewritten. The cost of hooking one of these is the
// whole app.
void set_exclusions(const char** paths, int count);
int exclusion_count();
// Publishes the *outward* table: real path prefix -> the prefix an installed app shows.
//
// The inverse of set_rules(). Redirection answers "where does this path really live";
// this answers "what would this path look like if the app were installed", which is the
// question `/proc/self/maps` asks on the guest's behalf whether it wants it or not. See
// proc_view.h. An empty table leaves /proc exactly as the kernel wrote it.
void set_proc_view(const char** from, const char** to, int count);
void clear_proc_view();
int proc_view_rule_count();
// Rewrites arbitrary text through the outward table. Exposed for the diagnostic that
// reports what a guest would see, so the answer comes from the same code that serves it.
std::string proc_view_rewrite(const char* text);
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
