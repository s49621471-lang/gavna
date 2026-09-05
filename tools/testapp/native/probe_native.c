// A minimal JNI library for the UNIQUE probe.
//
// Deliberately tiny and dependency-free: the question this answers is not "does complex
// native code work" but "can a virtual process load a library out of an APK the system
// has never installed, and does that library see the guest's identity". Anything larger
// would make a failure ambiguous.
#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <stdio.h>

#if defined(__aarch64__)
#define PROBE_ARCH "arm64-v8a"
#elif defined(__x86_64__)
#define PROBE_ARCH "x86_64"
#elif defined(__arm__)
#define PROBE_ARCH "armeabi-v7a"
#elif defined(__i386__)
#define PROBE_ARCH "x86"
#else
#define PROBE_ARCH "unknown"
#endif

JNIEXPORT jstring JNICALL
Java_com_unique_probe_ProbeNative_arch(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, PROBE_ARCH);
}

/* The page size the loader is actually running with. 16384 on an Android 15 device
   configured for large pages, which is the case no emulator here can produce. */
JNIEXPORT jlong JNICALL
Java_com_unique_probe_ProbeNative_pageSize(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jlong) sysconf(_SC_PAGESIZE);
}

JNIEXPORT jint JNICALL
Java_com_unique_probe_ProbeNative_pid(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jint) getpid();
}

/* Reads back a Java string, so the JNI bridge is exercised in both directions rather
   than only outward. */
JNIEXPORT jstring JNICALL
Java_com_unique_probe_ProbeNative_echo(JNIEnv *env, jclass clazz, jstring input) {
    (void) clazz;
    const char *chars = (*env)->GetStringUTFChars(env, input, NULL);
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "native:%s", chars ? chars : "");
    (*env)->ReleaseStringUTFChars(env, input, chars);
    return (*env)->NewStringUTF(env, buffer);
}

/* Opens a file through libc, so the storage redirection layer has something to redirect
   once phase 4's libc interception exists. Returns the path libc actually resolved. */
JNIEXPORT jstring JNICALL
Java_com_unique_probe_ProbeNative_writeThroughLibc(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    char result[512];
    FILE *f = chars ? fopen(chars, "w") : NULL;
    if (f) {
        fputs("written by libc\n", f);
        fclose(f);
        snprintf(result, sizeof(result), "ok:%s", chars);
    } else {
        snprintf(result, sizeof(result), "failed:%s", chars ? chars : "(null)");
    }
    if (chars) (*env)->ReleaseStringUTFChars(env, path, chars);
    return (*env)->NewStringUTF(env, result);
}

/* Crashes on purpose, in native code.
 *
 * There is no way to produce a *native* crash from Java, and a Java exception exercises an
 * entirely different path: the JVM's uncaught-exception handler, not a POSIX signal. This
 * dereferences a null pointer, which arrives as SIGSEGV with si_addr 0 — the most common
 * shape of native crash there is, and the one worth being sure UNIQUE records.
 *
 * `volatile` so the compiler cannot decide the store is undefined behaviour and delete it,
 * which at -O2 it is entitled to do. */
JNIEXPORT void JNICALL
Java_com_unique_probe_ProbeNative_crash(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    volatile int *p = (volatile int *) 0;
    *p = 42;
}
