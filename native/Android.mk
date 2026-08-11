LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := gavna
LOCAL_SRC_FILES := gavna_log.cpp gavna_hook.cpp gavna_il2cpp.cpp gavna_engine.cpp
LOCAL_CPPFLAGS  := -std=c++17 -fno-exceptions -fno-rtti -O2 -Wall -Wextra -Werror \
                   -Wno-missing-field-initializers \
                   -fvisibility=hidden -ffunction-sections -fdata-sections
LOCAL_LDFLAGS   := -Wl,--gc-sections -Wl,-z,max-page-size=16384
LOCAL_LDLIBS    := -llog

include $(BUILD_SHARED_LIBRARY)
