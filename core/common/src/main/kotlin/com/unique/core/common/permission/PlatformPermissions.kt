package com.unique.core.common.permission

/**
 * Which Android permissions are *runtime* permissions, and which are granted at install.
 *
 * ## Why this exists
 *
 * A virtual app's permissions are decided per instance, which is the point of instances:
 * one copy of an app may hold the camera and another may not. Implementing that as "every
 * permission starts undecided and undecided means denied" is the obvious reading and it is
 * wrong, because it applies a runtime-permission rule to permissions that have no runtime
 * flow at all. `INTERNET` is never asked for. No dialog exists for it, no app requests it,
 * and an app that declares it simply has it from the moment it is installed.
 *
 * Denying those is not a narrower permission model, it is a broken device. From a physical
 * run, before this existed:
 *
 * ```
 * PERMISSION_CHECK permission=android.permission.ACCESS_NETWORK_STATE result=DENIED   (x36)
 * PERMISSION_CHECK permission=android.permission.INTERNET              result=DENIED   (x10)
 * PERMISSION_CHECK permission=android.permission.WAKE_LOCK             result=DENIED   (x4)
 * ```
 *
 * — three of the most common install-time permissions in Android, denied to every guest,
 * forever, with no user action that could have granted them. Every networked app was
 * unusable for that reason alone.
 *
 * So the split has to be made, and it has to be made the way the platform makes it: a
 * *dangerous* permission is the user's decision; everything else is granted because the
 * manifest asked for it.
 *
 * ## Why a table, when the device can be asked
 *
 * The device is asked first — `PackageManager.getPermissionInfo` knows this build's own
 * answer, including OEM additions and permissions added after this code was written. This
 * table is the fallback for the two cases where that lookup returns nothing: a permission
 * from a newer API level than the phone, and a permission no installed package defines.
 *
 * Misreading a dangerous permission as install-time would widen what a guest gets, so the
 * list is the AOSP set in full rather than the common ones. The second guard is
 * structural and matters more: UNIQUE intersects every answer with its own grant, so a
 * permission it does not hold itself stays denied whatever this says.
 *
 * Pure JVM, in `core/common`, so it can be tested against the platform's own list without
 * a device.
 */
object PlatformPermissions {

    /**
     * Health Connect permissions share a prefix and are all dangerous.
     *
     * Matched by prefix rather than enumerated: the set grows every release, and a new
     * member arriving as "install-time" is the failure direction that matters.
     */
    private const val HEALTH_PREFIX = "android.permission.health."

    /**
     * The AOSP runtime (dangerous) permissions, API 23 through 36.
     *
     * Grouped as the platform groups them, because that is how they are documented and how
     * a missing one is spotted.
     */
    val RUNTIME: Set<String> = setOf(
        // CALENDAR
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        // CALL_LOG
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.PROCESS_OUTGOING_CALLS",
        // CAMERA
        "android.permission.CAMERA",
        // CONTACTS
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        // LOCATION
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        // MICROPHONE
        "android.permission.RECORD_AUDIO",
        // NEARBY_DEVICES
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.UWB_RANGING",
        "android.permission.NEARBY_WIFI_DEVICES",
        // PHONE
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.ADD_VOICEMAIL",
        "android.permission.USE_SIP",
        "android.permission.ACCEPT_HANDOVER",
        // SENSORS
        "android.permission.BODY_SENSORS",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.ACTIVITY_RECOGNITION",
        // SMS
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.RECEIVE_MMS",
        // STORAGE and media
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        // NOTIFICATIONS
        "android.permission.POST_NOTIFICATIONS",
    )

    /**
     * Whether [permission] is one the user decides at runtime.
     *
     * The answer for a name this does not recognise is `false` — install-time — and that
     * is safe only because of the host intersection described above: an unrecognised
     * permission is one no installed package defines, so UNIQUE cannot hold it either and
     * the guest is denied regardless.
     */
    fun isRuntime(permission: String): Boolean =
        permission in RUNTIME || permission.startsWith(HEALTH_PREFIX)

    /**
     * The base protection level of a `<permission>` element, as the manifest encodes it.
     *
     * `android:protectionLevel` packs flags into the high bits — `signature|privileged`
     * is `0x12` — so the base level is the low nibble. Only `dangerous` (1) makes a
     * self-defined permission the user's decision.
     */
    fun isDangerousProtectionLevel(protectionLevel: Int): Boolean =
        (protectionLevel and 0xf) == PROTECTION_DANGEROUS

    /** `PermissionInfo.PROTECTION_DANGEROUS`, repeated here so `core/common` stays pure JVM. */
    const val PROTECTION_DANGEROUS = 1
}
