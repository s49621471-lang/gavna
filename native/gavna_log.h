// gavna - logging + crash reporting to a file inside the game folder
#pragma once

#include <stdarg.h>

namespace gavna {

// Opens <dir>/gavna.log for append. Safe to call more than once.
void LogInit(const char* dir);

// Absolute path of the active log file (never null, may be "<none>").
const char* LogPath();

void LogWrite(const char* tag, const char* fmt, ...) __attribute__((format(printf, 2, 3)));

// Installs signal handlers that dump the faulting address / backtrace hint into
// the log and then chain to whatever handler was installed before us.
void InstallCrashHandler();

}  // namespace gavna

#define LOGI(...) ::gavna::LogWrite("I", __VA_ARGS__)
#define LOGW(...) ::gavna::LogWrite("W", __VA_ARGS__)
#define LOGE(...) ::gavna::LogWrite("E", __VA_ARGS__)
