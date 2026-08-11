#include "gavna_log.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

namespace gavna {
namespace {

constexpr int kMaxSignals = 6;
const int kSignals[kMaxSignals] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP};

int g_fd = -1;
char g_path[512] = "<none>";
pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
struct sigaction g_old[kMaxSignals];
bool g_handlers_installed = false;

void WriteAll(int fd, const char* buf, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t n = write(fd, buf + done, len - done);
        if (n <= 0) {
            if (errno == EINTR) continue;
            return;
        }
        done += static_cast<size_t>(n);
    }
}

// Signal-safe unsigned -> hex, returns characters written.
size_t HexTo(char* out, unsigned long long v) {
    static const char kDigits[] = "0123456789abcdef";
    char tmp[16];
    int n = 0;
    do {
        tmp[n++] = kDigits[v & 0xF];
        v >>= 4;
    } while (v != 0 && n < 16);
    for (int i = 0; i < n; ++i) out[i] = tmp[n - 1 - i];
    return static_cast<size_t>(n);
}

void CrashHandler(int sig, siginfo_t* info, void* ctx) {
    // Only async-signal-safe calls below: no malloc, no printf, no mutex.
    char buf[256];
    size_t n = 0;
    const char* kHead = "\n*** gavna: fatal signal ";
    memcpy(buf + n, kHead, strlen(kHead));
    n += strlen(kHead);
    n += HexTo(buf + n, static_cast<unsigned long long>(sig));
    const char* kAt = " at 0x";
    memcpy(buf + n, kAt, strlen(kAt));
    n += strlen(kAt);
    n += HexTo(buf + n, info ? reinterpret_cast<unsigned long long>(info->si_addr) : 0ULL);
    const char* kTid = " tid=";
    memcpy(buf + n, kTid, strlen(kTid));
    n += strlen(kTid);
    n += HexTo(buf + n, static_cast<unsigned long long>(gettid()));
    buf[n++] = '\n';

    if (g_fd >= 0) WriteAll(g_fd, buf, n);
    buf[n] = '\0';
    __android_log_write(ANDROID_LOG_ERROR, "gavna", buf);

    // Hand control back to whoever owned the signal before us (Crashlytics,
    // Unity, or the default kernel action) so the normal crash path still runs.
    for (int i = 0; i < kMaxSignals; ++i) {
        if (kSignals[i] == sig) {
            sigaction(sig, &g_old[i], nullptr);
            break;
        }
    }
    (void)ctx;
    raise(sig);
}

}  // namespace

void LogInit(const char* dir) {
    pthread_mutex_lock(&g_mutex);
    if (g_fd < 0 && dir != nullptr && dir[0] != '\0') {
        mkdir(dir, 0770);  // harmless if it already exists
        snprintf(g_path, sizeof(g_path), "%s/gavna.log", dir);
        g_fd = open(g_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0660);
        if (g_fd < 0) {
            __android_log_print(ANDROID_LOG_WARN, "gavna", "cannot open %s (%d)", g_path, errno);
            snprintf(g_path, sizeof(g_path), "<none>");
        }
    }
    pthread_mutex_unlock(&g_mutex);
}

const char* LogPath() { return g_path; }

void LogWrite(const char* tag, const char* fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    int written = vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    if (written < 0) return;

    __android_log_write(ANDROID_LOG_INFO, "gavna", msg);

    if (g_fd < 0) return;

    char line[1200];
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm_buf;
    time_t secs = ts.tv_sec;
    localtime_r(&secs, &tm_buf);
    int n = snprintf(line, sizeof(line), "%02d:%02d:%02d.%03ld %s %s\n", tm_buf.tm_hour,
                     tm_buf.tm_min, tm_buf.tm_sec, ts.tv_nsec / 1000000L, tag, msg);
    if (n <= 0) return;
    if (n > static_cast<int>(sizeof(line))) n = static_cast<int>(sizeof(line));

    pthread_mutex_lock(&g_mutex);
    WriteAll(g_fd, line, static_cast<size_t>(n));
    pthread_mutex_unlock(&g_mutex);
}

void InstallCrashHandler() {
    if (g_handlers_installed) return;
    g_handlers_installed = true;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = CrashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    for (int i = 0; i < kMaxSignals; ++i) {
        if (sigaction(kSignals[i], &sa, &g_old[i]) != 0) {
            memset(&g_old[i], 0, sizeof(g_old[i]));
            g_old[i].sa_handler = SIG_DFL;
        }
    }
    LOGI("crash handler installed, log=%s", g_path);
}

}  // namespace gavna
