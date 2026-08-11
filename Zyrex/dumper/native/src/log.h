#pragma once

#include <android/log.h>

#define ZX_TAG "Zyrex"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  ZX_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  ZX_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ZX_TAG, __VA_ARGS__)
