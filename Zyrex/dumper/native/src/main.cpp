// Zyrex — dumper entry point
//
// Injected into com.skullcapstudios.bps via UnityPlayerActivity.onCreate.
// Everything heavy happens on a detached worker thread so the Unity boot path
// is never blocked; JNI_OnLoad itself does nothing but register.

#include "il2cpp_api.h"
#include "dumper.h"
#include "probe.h"
#include "guard.h"
#include "log.h"

#include <jni.h>
#include <pthread.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <sys/stat.h>

namespace {

std::atomic<bool> g_started{false};

// Delay before touching the runtime. libil2cpp.so is mapped early but the
// metadata registration it needs is not complete until well into the first
// scene load, and walking it too early is the surest way to fault.
constexpr int kInitialDelayMs   = 20000;
constexpr int kIl2cppTimeoutMs  = 120000;

// Long enough to get through the menu and play a couple of rounds. Samples
// where no local player exists (menu, dead, loading) cost nothing but a skip.
constexpr int kProbeDurationSec = 600;
constexpr int kProbeIntervalMs  = 500;

struct Args {
    char out_dir[768];
};

void sleep_ms(int ms) {
    struct timespec ts { ms / 1000, (long)(ms % 1000) * 1000000L };
    nanosleep(&ts, nullptr);
}

void write_marker(const char* dir, const char* name, const char* text) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    if (FILE* f = fopen(path, "w")) {
        fputs(text, f);
        fclose(f);
    }
}

void* worker(void* raw) {
    Args* args = static_cast<Args*>(raw);
    char out_dir[768];
    snprintf(out_dir, sizeof(out_dir), "%s", args->out_dir);
    free(args);

    mkdir(out_dir, 0770);   // harmless if it already exists

    LOGI("worker up, output -> %s", out_dir);
    write_marker(out_dir, "_status.txt", "started\n");

    sleep_ms(kInitialDelayMs);

    if (!zyrex::resolve_api(kIl2cppTimeoutMs)) {
        LOGE("api resolution failed — standing down");
        write_marker(out_dir, "_status.txt", "failed: il2cpp api unavailable\n");
        return nullptr;
    }

    // The domain has to exist before a thread can attach to it.
    zyrex::Il2CppDomain* domain = nullptr;
    for (int waited = 0; waited < 60000; waited += 200) {
        domain = zyrex::api.domain_get();
        if (domain) break;
        sleep_ms(200);
    }
    if (!domain) {
        LOGE("domain never came up");
        write_marker(out_dir, "_status.txt", "failed: no il2cpp domain\n");
        return nullptr;
    }

    zyrex::Il2CppThread* attached = zyrex::api.thread_attach(domain);
    if (!attached) {
        LOGE("thread_attach failed");
        write_marker(out_dir, "_status.txt", "failed: thread_attach\n");
        return nullptr;
    }
    LOGI("attached to il2cpp domain %p", (void*)domain);

    const bool guarded = zyrex::guard_install();
    if (!guarded) LOGW("running without fault guard");

    // The SDK dump only needs doing once — the class table is identical on
    // every launch. Drop a file named dump_again.txt in the output folder to
    // force it; otherwise go straight to probing, which is what this build is
    // actually for.
    char marker[1024];
    snprintf(marker, sizeof(marker), "%s/dump_again.txt", out_dir);
    if (FILE* m = fopen(marker, "r")) {
        fclose(m);
        LOGI("dump_again.txt present — running full SDK dump first");
        write_marker(out_dir, "_status.txt", "dumping\n");
        zyrex::run_dump(out_dir);
    }

    write_marker(out_dir, "_status.txt", "probing\n");
    zyrex::run_probe(out_dir, kProbeDurationSec, kProbeIntervalMs);

    if (guarded) zyrex::guard_remove();
    if (zyrex::api.thread_detach) zyrex::api.thread_detach(attached);

    write_marker(out_dir, "_status.txt", "done\n");
    LOGI("worker finished cleanly");
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_zyrex_dumper_Zyrex_nativeStart(JNIEnv* env, jclass, jstring jOutDir) {
    if (!env || !jOutDir) return -1;

    bool expected = false;
    if (!g_started.compare_exchange_strong(expected, true)) {
        LOGW("nativeStart called twice — ignoring");
        return 1;
    }

    const char* utf = env->GetStringUTFChars(jOutDir, nullptr);
    if (!utf) {
        g_started = false;
        return -1;
    }

    auto* args = static_cast<Args*>(calloc(1, sizeof(Args)));
    if (!args) {
        env->ReleaseStringUTFChars(jOutDir, utf);
        g_started = false;
        return -1;
    }
    snprintf(args->out_dir, sizeof(args->out_dir), "%s", utf);
    env->ReleaseStringUTFChars(jOutDir, utf);

    pthread_t t;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    const int rc = pthread_create(&t, &attr, worker, args);
    pthread_attr_destroy(&attr);

    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        free(args);
        g_started = false;
        return -1;
    }
    LOGI("dumper thread spawned");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    LOGI("libzyrexdump loaded");
    return JNI_VERSION_1_6;
}
