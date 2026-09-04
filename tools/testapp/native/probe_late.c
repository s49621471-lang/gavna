// A second library, loaded *after* UNIQUE's initial hook pass.
//
// Its whole purpose is timing: the first library is loaded in a static initializer, which
// is before the interception is installed, and this one is loaded from an Activity, which
// is after. A library that arrives late has its own untouched GOT, so without a watch on
// library loading none of its file operations are redirected.
#include <jni.h>
#include <stdio.h>

JNIEXPORT jstring JNICALL
Java_com_unique_probe_ProbeLateNative_writeThroughLibc(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    char result[512];
    FILE *f = chars ? fopen(chars, "w") : NULL;
    if (f) {
        fputs("written by a late-loaded library\n", f);
        fclose(f);
        snprintf(result, sizeof(result), "ok:%s", chars);
    } else {
        snprintf(result, sizeof(result), "failed:%s", chars ? chars : "(null)");
    }
    if (chars) (*env)->ReleaseStringUTFChars(env, path, chars);
    return (*env)->NewStringUTF(env, result);
}
