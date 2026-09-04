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
    const val REQUIRED = 0x0101028e
}
