#include <string>
#include <vector>
#include <unistd.h>

#include "unique_native.h"

namespace {

// Small RAII helper: JNI string handling is the most common source of local-reference
// leaks in code like this, and a leak in a hot path (every redirect() call) is fatal.
class ScopedUtf {
public:
    ScopedUtf(JNIEnv* env, jstring s) : env_(env), js_(s) {
        chars_ = (s != nullptr) ? env->GetStringUTFChars(s, nullptr) : nullptr;
    }
    ~ScopedUtf() { if (chars_ != nullptr) env_->ReleaseStringUTFChars(js_, chars_); }
    ScopedUtf(const ScopedUtf&) = delete;
    ScopedUtf& operator=(const ScopedUtf&) = delete;
    [[nodiscard]] const char* get() const { return chars_; }

private:
    JNIEnv* env_;
    jstring js_;
    const char* chars_;
};

}  // namespace

UNIQUE_EXPORT JNIEXPORT jint JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativePageSize(JNIEnv*, jclass) {
    return static_cast<jint>(sysconf(_SC_PAGESIZE));
}

UNIQUE_EXPORT JNIEXPORT void JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeSetRedirectRules(
        JNIEnv* env, jclass, jobjectArray from, jobjectArray to) {
    const jsize n = env->GetArrayLength(from);
    if (n != env->GetArrayLength(to)) {
        ULOGE("redirect rule arrays differ in length (%d vs %d); ignoring",
              n, env->GetArrayLength(to));
        return;
    }
    std::vector<std::string> from_storage;
    std::vector<std::string> to_storage;
    from_storage.reserve(static_cast<size_t>(n));
    to_storage.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto fs = reinterpret_cast<jstring>(env->GetObjectArrayElement(from, i));
        auto ts = reinterpret_cast<jstring>(env->GetObjectArrayElement(to, i));
        // The inner scope is load-bearing. ScopedUtf releases in its destructor, which
        // without it runs *after* DeleteLocalRef - releasing a string through a reference
        // that no longer exists. ART's checked JNI aborts the process for that:
        //
        //   JNI ERROR (app bug): jstring is an invalid local reference
        //       in call to ReleaseStringUTFChars
        //
        // It stayed latent for as long as nothing actually published a redirect table.
        {
            ScopedUtf f(env, fs);
            ScopedUtf t(env, ts);
            from_storage.emplace_back(f.get() != nullptr ? f.get() : "");
            to_storage.emplace_back(t.get() != nullptr ? t.get() : "");
        }
        env->DeleteLocalRef(fs);
        env->DeleteLocalRef(ts);
    }
    std::vector<const char*> fp;
    std::vector<const char*> tp;
    fp.reserve(from_storage.size());
    tp.reserve(to_storage.size());
    for (size_t i = 0; i < from_storage.size(); ++i) {
        fp.push_back(from_storage[i].c_str());
        tp.push_back(to_storage[i].c_str());
    }
    unique::io_redirect::set_rules(fp.data(), tp.data(), static_cast<int>(fp.size()));
}

UNIQUE_EXPORT JNIEXPORT void JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeClearRedirectRules(JNIEnv*, jclass) {
    unique::io_redirect::clear_rules();
}

UNIQUE_EXPORT JNIEXPORT jint JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeRedirectRuleCount(JNIEnv*, jclass) {
    return unique::io_redirect::rule_count();
}

// Exposed so the Java layer and the on-device instrumentation suite can assert that the
// native table agrees with VirtualPathModel. The two must never disagree: that is how an
// app ends up reading instance 0's database while writing instance 1's.
UNIQUE_EXPORT JNIEXPORT jstring JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeRedirect(
        JNIEnv* env, jclass, jstring path) {
    ScopedUtf p(env, path);
    if (p.get() == nullptr) return nullptr;
    const std::string out = unique::io_redirect::redirect(p.get());
    if (out.empty()) return nullptr;
    return env->NewStringUTF(out.c_str());
}

UNIQUE_EXPORT JNIEXPORT void JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeSetRedirectScope(
        JNIEnv* env, jclass, jobjectArray paths) {
    const jsize count = paths == nullptr ? 0 : env->GetArrayLength(paths);
    std::vector<std::string> owned;
    std::vector<const char*> raw;
    owned.reserve(static_cast<size_t>(count));
    raw.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(paths, i));
        if (item == nullptr) continue;
        const char* chars = env->GetStringUTFChars(item, nullptr);
        if (chars != nullptr) {
            owned.emplace_back(chars);
            env->ReleaseStringUTFChars(item, chars);
        }
        env->DeleteLocalRef(item);
    }
    for (const auto& value : owned) raw.push_back(value.c_str());
    unique::io_redirect::set_scope(raw.data(), static_cast<int>(raw.size()));
}

UNIQUE_EXPORT JNIEXPORT jint JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeRedirectSlotsPatched(JNIEnv*, jclass) {
    return unique::io_redirect::slots_patched();
}

UNIQUE_EXPORT JNIEXPORT jint JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeInstallIoRedirect(JNIEnv*, jclass) {
    return static_cast<jint>(unique::io_redirect::install());
}

UNIQUE_EXPORT JNIEXPORT void JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeSetProperty(
        JNIEnv* env, jclass, jstring key, jstring value) {
    ScopedUtf k(env, key);
    ScopedUtf v(env, value);
    unique::prop_virtual::set_property(k.get(), v.get());
}

UNIQUE_EXPORT JNIEXPORT void JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeClearProperties(JNIEnv*, jclass) {
    unique::prop_virtual::clear_properties();
}

UNIQUE_EXPORT JNIEXPORT jstring JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeLookupProperty(
        JNIEnv* env, jclass, jstring key) {
    ScopedUtf k(env, key);
    const char* v = unique::prop_virtual::lookup(k.get());
    return v == nullptr ? nullptr : env->NewStringUTF(v);
}

UNIQUE_EXPORT JNIEXPORT jint JNICALL
Java_com_unique_core_nativebridge_UniqueNative_nativeInstallPropertyVirtualization(JNIEnv*, jclass) {
    return static_cast<jint>(unique::prop_virtual::install());
}

UNIQUE_EXPORT jint JNI_OnLoad(JavaVM*, void*) {
    ULOGI("libunique_native loaded (page size %ld)", sysconf(_SC_PAGESIZE));
    return JNI_VERSION_1_6;
}
