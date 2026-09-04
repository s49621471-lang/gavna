package com.unique.core.vpermission

import android.content.pm.PackageManager

/** A permission group as the UI presents it. Maps to one or more Android permissions. */
enum class PermissionGroup(val label: String, val permissions: List<String>) {
    CAMERA("Camera", listOf("android.permission.CAMERA")),
    MICROPHONE("Microphone", listOf("android.permission.RECORD_AUDIO")),
    LOCATION("Location", listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
    )),
    FILES("Files", listOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE",
    )),
    NOTIFICATIONS("Notifications", listOf("android.permission.POST_NOTIFICATIONS")),
    CONTACTS("Contacts", listOf(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
    )),
    PHONE("Phone", listOf("android.permission.READ_PHONE_STATE"));

    companion object {
        fun of(permission: String): PermissionGroup? =
            entries.firstOrNull { permission in it.permissions }
    }
}

enum class PermissionState { GRANTED, DENIED, ASK }

/**
 * Per-instance runtime permission state.
 *
 * Each virtual instance has its own answers, so Telegram #1 can hold Camera while
 * Telegram #2 does not — which is the behaviour users expect from separate installs.
 *
 * Important limit, stated rather than hidden: UNIQUE can only ever *narrow* what the
 * host holds. Denying a permission here genuinely denies it to the virtual app, but
 * granting one the host itself lacks does nothing until the user grants it to UNIQUE.
 * [effectiveState] encodes exactly that, so the UI can show "needs UNIQUE permission"
 * instead of a switch that appears on but does not work.
 */
class PermissionStore(private val hostGrants: (String) -> Int) {

    private val states = HashMap<Key, PermissionState>()

    private data class Key(val vuid: Int, val packageName: String, val permission: String)

    @Synchronized
    fun set(vuid: Int, packageName: String, permission: String, state: PermissionState) {
        states[Key(vuid, packageName, permission)] = state
    }

    @Synchronized
    fun setGroup(vuid: Int, packageName: String, group: PermissionGroup, state: PermissionState) {
        group.permissions.forEach { set(vuid, packageName, it, state) }
    }

    @Synchronized
    fun stored(vuid: Int, packageName: String, permission: String): PermissionState =
        states[Key(vuid, packageName, permission)] ?: PermissionState.ASK

    /** What the virtual app actually observes, after the host's own grants are applied. */
    fun effectiveState(vuid: Int, packageName: String, permission: String): EffectivePermission {
        val hostHolds = hostGrants(permission) == PackageManager.PERMISSION_GRANTED
        val stored = stored(vuid, packageName, permission)
        return when {
            !hostHolds -> EffectivePermission(PermissionState.DENIED, blockedByHost = true)
            stored == PermissionState.GRANTED -> EffectivePermission(PermissionState.GRANTED, false)
            stored == PermissionState.DENIED -> EffectivePermission(PermissionState.DENIED, false)
            else -> EffectivePermission(PermissionState.ASK, false)
        }
    }

    fun checkPermission(vuid: Int, packageName: String, permission: String): Int =
        if (effectiveState(vuid, packageName, permission).state == PermissionState.GRANTED) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }

    @Synchronized
    fun clear(vuid: Int, packageName: String) {
        states.keys.filter { it.vuid == vuid && it.packageName == packageName }
            .forEach { states.remove(it) }
    }

    @Synchronized
    fun snapshot(vuid: Int, packageName: String): Map<String, PermissionState> =
        states.filterKeys { it.vuid == vuid && it.packageName == packageName }
            .mapKeys { it.key.permission }
}

/**
 * @param blockedByHost true when the answer is DENIED only because UNIQUE itself does not
 *   hold the permission. The UI shows this differently: the user must grant it to UNIQUE
 *   first, and no amount of toggling the virtual app's switch will help.
 */
data class EffectivePermission(val state: PermissionState, val blockedByHost: Boolean)
