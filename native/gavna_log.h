// gavna - logcat only. Nothing is written to disk.
#pragma once

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "gavna", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "gavna", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "gavna", __VA_ARGS__)
