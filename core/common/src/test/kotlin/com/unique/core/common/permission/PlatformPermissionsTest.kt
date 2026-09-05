package com.unique.core.common.permission

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The split between "the user decides" and "the manifest asked for it".
 *
 * This table decides whether a guest gets `INTERNET`, so getting it wrong is not a
 * degraded permission model but a phone with the network switched off — which is what a
 * physical run produced: `ACCESS_NETWORK_STATE` denied 36 times, `INTERNET` ten, and
 * `WAKE_LOCK` four, to apps that had declared all three and for which no dialog exists.
 *
 * The direction of a mistake matters and the two directions are tested separately. A
 * dangerous permission wrongly called install-time would be granted without the user
 * being asked; an install-time one wrongly called dangerous is the failure above.
 */
class PlatformPermissionsTest {

    @Test fun `the permissions a real run found denied are install-time`() {
        for (name in listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
        )) {
            assertThat(PlatformPermissions.isRuntime(name)).isFalse()
        }
    }

    @Test fun `the other permissions every app declares are install-time too`() {
        for (name in listOf(
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.VIBRATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.REORDER_TASKS",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.CHANGE_NETWORK_STATE",
        )) {
            assertThat(PlatformPermissions.isRuntime(name)).isFalse()
        }
    }

    @Test fun `every dangerous permission group is covered`() {
        // Enumerated by group as the platform documents them, so a group that is missing
        // entirely is visible here rather than as a guest quietly holding the microphone.
        val byGroup = mapOf(
            "calendar" to listOf("READ_CALENDAR", "WRITE_CALENDAR"),
            "call log" to listOf("READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS"),
            "camera" to listOf("CAMERA"),
            "contacts" to listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS"),
            "location" to listOf(
                "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            ),
            "microphone" to listOf("RECORD_AUDIO"),
            "nearby devices" to listOf(
                "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
                "UWB_RANGING", "NEARBY_WIFI_DEVICES",
            ),
            "phone" to listOf(
                "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS",
                "ADD_VOICEMAIL", "USE_SIP", "ACCEPT_HANDOVER",
            ),
            "sensors" to listOf("BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "ACTIVITY_RECOGNITION"),
            "sms" to listOf(
                "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS",
            ),
            "storage" to listOf(
                "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION",
            ),
            "media" to listOf(
                "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
                "READ_MEDIA_VISUAL_USER_SELECTED",
            ),
            "notifications" to listOf("POST_NOTIFICATIONS"),
        )
        for ((group, names) in byGroup) {
            for (name in names) {
                assertWithMessage("$group: $name")
                    .that(PlatformPermissions.isRuntime("android.permission.$name"))
                    .isTrue()
            }
        }
    }

    @Test fun `health permissions are matched by prefix because the set keeps growing`() {
        assertThat(PlatformPermissions.isRuntime("android.permission.health.READ_HEART_RATE"))
            .isTrue()
        assertThat(PlatformPermissions.isRuntime("android.permission.health.WRITE_STEPS")).isTrue()
        // Whatever the next release adds under that prefix is covered before it exists.
        assertThat(PlatformPermissions.isRuntime("android.permission.health.READ_SOMETHING_NEW"))
            .isTrue()
    }

    @Test fun `an unrecognised permission is install-time, which the host grant makes safe`() {
        // Not a guess about the permission: a name no installed package defines is one
        // UNIQUE cannot hold either, so PermissionStore denies it on the host check
        // regardless of what this says.
        assertThat(PlatformPermissions.isRuntime("com.example.custom.PERMISSION")).isFalse()
        assertThat(PlatformPermissions.isRuntime("")).isFalse()
    }

    @Test fun `the protection level's flags are ignored and only the base level counts`() {
        // android:protectionLevel packs flags into the high bits: signature|privileged is
        // 0x12, and comparing the whole value against PROTECTION_DANGEROUS would classify
        // a signature permission as dangerous or miss dangerous|instant entirely.
        assertThat(PlatformPermissions.isDangerousProtectionLevel(0)).isFalse()   // normal
        assertThat(PlatformPermissions.isDangerousProtectionLevel(1)).isTrue()    // dangerous
        assertThat(PlatformPermissions.isDangerousProtectionLevel(2)).isFalse()   // signature
        assertThat(PlatformPermissions.isDangerousProtectionLevel(0x12)).isFalse() // sig|privileged
        assertThat(PlatformPermissions.isDangerousProtectionLevel(0x1001)).isTrue() // dangerous|instant
    }

    @Test fun `no name in the runtime set is malformed`() {
        // A typo here is invisible at runtime: the misspelling simply never matches, and
        // the permission it was meant to name is treated as install-time from then on.
        for (name in PlatformPermissions.RUNTIME) {
            assertThat(name).startsWith("android.permission.")
            assertThat(name.substringAfterLast('.')).matches("[A-Z][A-Z0-9_]+")
        }
    }
}
