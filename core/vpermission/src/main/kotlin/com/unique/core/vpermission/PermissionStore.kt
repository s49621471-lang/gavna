package com.unique.core.vpermission

import android.content.pm.PackageManager
import com.unique.core.common.permission.PlatformPermissions

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
 *
 * ## Undecided does not mean denied
 *
 * Only a *runtime* permission is the user's to decide. Everything else a manifest asks
 * for is granted at install time, has no dialog and is never requested — and treating it
 * as undecided-so-denied left every guest without `INTERNET` or `ACCESS_NETWORK_STATE`,
 * which is not a permission model but a phone with the network switched off. See
 * [PlatformPermissions] for the split and the evidence.
 *
 * @param hostGrants what UNIQUE itself holds, from the platform.
 * @param isRuntimePermission whether a permission is the user's decision. The default is
 *   the static AOSP list; the caller replaces it with one that asks this device first,
 *   which is more accurate on an OEM build and for permissions newer than this code.
 * @param isSelfDefined whether the guest's own manifest defines the permission with a
 *   non-dangerous protection level. The platform always grants an app the permissions it
 *   defines itself, and UNIQUE must too — otherwise an app that guards its own provider
 *   with its own permission cannot reach it, and the host obviously does not hold a
 *   permission that only the guest defines.
 */
class PermissionStore(
    private val hostGrants: (String) -> Int,
    private val isRuntimePermission: (String) -> Boolean = PlatformPermissions::isRuntime,
    private val isSelfDefined: (String) -> Boolean = { false },
) {

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

    /**
     * What the virtual app actually observes, after the host's own grants are applied.
     *
     * The order of the checks is the whole model:
     *
     *  1. A permission the guest itself defines is granted, as at install. It is checked
     *     first because the host cannot hold a permission only the guest declares, so any
     *     later check would deny it.
     *  2. A permission UNIQUE does not hold is denied, and reported as the host's fault.
     *     UNIQUE narrows; it cannot widen.
     *  3. A decision the user has already made stands.
     *  4. Otherwise: a runtime permission is still the user's to make, and an install-time
     *     permission is granted because the manifest asked for it.
     */
    fun effectiveState(vuid: Int, packageName: String, permission: String): EffectivePermission {
        if (isSelfDefined(permission)) {
            return EffectivePermission(PermissionState.GRANTED, blockedByHost = false)
        }
        val hostHolds = hostGrants(permission) == PackageManager.PERMISSION_GRANTED
        val stored = stored(vuid, packageName, permission)
        return when {
            !hostHolds -> EffectivePermission(PermissionState.DENIED, blockedByHost = true)
            stored == PermissionState.GRANTED -> EffectivePermission(PermissionState.GRANTED, false)
            stored == PermissionState.DENIED -> EffectivePermission(PermissionState.DENIED, false)
            isRuntimePermission(permission) -> EffectivePermission(PermissionState.ASK, false)
            else -> EffectivePermission(PermissionState.GRANTED, false)
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
