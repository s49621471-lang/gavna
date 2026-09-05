package com.unique.core.common.apk

/**
 * Android framework attribute resource ids used by the manifest reader.
 *
 * These are needed because AAPT2 may emit an empty attribute *name* in the string pool
 * (and obfuscators reliably do), leaving the resource id in `RES_XML_RESOURCE_MAP_TYPE`
 * as the only reliable identity. Only ids whose value is stable across every platform
 * release are listed; the reader always falls back to the textual name, so an id that is
 * absent here degrades to "works unless the name was stripped" rather than to a failure.
 */
internal object AndroidAttrs {
    const val THEME = 0x01010000
    const val LABEL = 0x01010001
    const val ICON = 0x01010002
    const val NAME = 0x01010003
    const val PERMISSION = 0x01010006
    const val READ_PERMISSION = 0x01010007
    const val WRITE_PERMISSION = 0x01010008
    const val PROTECTION_LEVEL = 0x01010009
    const val SHARED_USER_ID = 0x0101000b
    const val HAS_CODE = 0x0101000c
    const val PERSISTENT = 0x0101000d
    const val ENABLED = 0x0101000e
    const val DEBUGGABLE = 0x0101000f
    const val EXPORTED = 0x01010010
    const val PROCESS = 0x01010011
    const val TASK_AFFINITY = 0x01010012
    const val MULTIPROCESS = 0x01010013
    const val EXCLUDE_FROM_RECENTS = 0x01010017
    const val AUTHORITIES = 0x01010018
    const val GRANT_URI_PERMISSIONS = 0x0101001b
    const val PRIORITY = 0x0101001c
    const val LAUNCH_MODE = 0x0101001d
    const val SCREEN_ORIENTATION = 0x0101001e
    const val CONFIG_CHANGES = 0x0101001f

    /** `android:foregroundServiceType`, added in API 29. */
    const val FOREGROUND_SERVICE_TYPE = 0x01010596
    const val VALUE = 0x01010024
    const val RESOURCE = 0x01010025
    const val MIME_TYPE = 0x01010026
    const val SCHEME = 0x01010027
    const val HOST = 0x01010028
    const val PORT = 0x01010029
    const val PATH = 0x0101002a
    const val PATH_PREFIX = 0x0101002b
    const val PATH_PATTERN = 0x0101002c
    const val MIN_SDK_VERSION = 0x0101020c
    const val VERSION_CODE = 0x0101021b
    const val VERSION_NAME = 0x0101021c
    const val TARGET_SDK_VERSION = 0x01010270
    // -----------------------------------------------------------------------------
    // Window and task attributes.
    //
    // These decide how the platform builds a guest's window, and their absence is not
    // cosmetic: with `hardwareAccelerated` unread every virtual activity was launched
    // with `ActivityInfo.flags == 0`, which is what `Activity.attach` passes to
    // `Window.setWindowManager` - so every guest rendered in software. That is both the
    // "the screen lags" report and this crash, which is a hard stop for anything drawing
    // through a RenderNode (Compose, any hardware layer, most modern view code):
    //
    //     java.lang.IllegalArgumentException: Software rendering doesn't support
    //         drawRenderNode
    //
    // Ids read out of `android.R$attr` on the compile SDK rather than transcribed.
    // -----------------------------------------------------------------------------
    const val HARDWARE_ACCELERATED = 0x010102d3
    const val WINDOW_SOFT_INPUT_MODE = 0x0101022b
    const val UI_OPTIONS = 0x01010398
    const val RESIZEABLE_ACTIVITY = 0x010104f6
    const val SUPPORTS_PICTURE_IN_PICTURE = 0x010104f7
    const val MAX_ASPECT_RATIO = 0x01010560
    const val MIN_ASPECT_RATIO = 0x0101059b
    const val NO_HISTORY = 0x0101022d
    const val FINISH_ON_TASK_LAUNCH = 0x01010014
    const val CLEAR_TASK_ON_LAUNCH = 0x01010015
    const val STATE_NOT_NEEDED = 0x01010016
    const val ALLOW_TASK_REPARENTING = 0x01010204
    const val ALWAYS_RETAIN_TASK_STATE = 0x01010203
    const val IMMERSIVE = 0x010102c0
    const val SHOW_FOR_ALL_USERS = 0x010104ef
    const val DOCUMENT_LAUNCH_MODE = 0x01010445
    const val MAX_RECENTS = 0x01010446
    const val RELINQUISH_TASK_IDENTITY = 0x01010476
    const val AUTO_REMOVE_FROM_RECENTS = 0x01010447
    const val RESUME_WHILE_PAUSING = 0x010104b2
    const val COLOR_MODE = 0x0101054a
    const val ROTATION_ANIMATION = 0x0101053a
    const val SHOW_WHEN_LOCKED = 0x01010569
    const val TURN_SCREEN_ON = 0x0101056a
    const val LOCK_TASK_MODE = 0x010104ed
    const val PERSISTABLE_MODE = 0x0101042d
    const val DIRECT_BOOT_AWARE = 0x01010505

    /** `<application>` attributes that change how the guest's process is built. */
    const val LARGE_HEAP = 0x0101035a
    const val SUPPORTS_RTL = 0x010103af
    const val REQUEST_LEGACY_EXTERNAL_STORAGE = 0x01010603
    const val EXTRACT_NATIVE_LIBS = 0x010104ea
    const val USES_CLEARTEXT_TRAFFIC = 0x010104ec
    const val NETWORK_SECURITY_CONFIG = 0x01010527
    const val APP_COMPONENT_FACTORY = 0x0101057a
    const val TARGET_ACTIVITY = 0x01010202
    const val IS_FEATURE_SPLIT = 0x0101055b
    const val VERSION_CODE_MAJOR = 0x01010576
    const val ROUND_ICON = 0x0101052c
    const val BANNER = 0x010103f2
    const val LOGO = 0x010102be
    const val REQUIRED = 0x0101028e
}
