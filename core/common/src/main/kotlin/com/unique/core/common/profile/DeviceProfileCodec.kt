package com.unique.core.common.profile

import java.util.UUID

/**
 * A flat text form of [DeviceProfile], for the one reader that cannot use the database.
 *
 * A `:vappN` process has no IPC to `:server` on the launch path — deliberately, because
 * that removes an ordering hazard from the most timing-sensitive code in the product — so
 * it reads the profile from a file, exactly as it reads the manifest from the APK and its
 * permission decisions from `runtime/`.
 *
 * Text rather than a serialised object: this file is read by a different build of the same
 * app after an update, and a `data class` whose shape changed would fail to deserialise
 * where a missing line merely falls back to a default.
 */
object DeviceProfileCodec {

    fun encode(profile: DeviceProfile): String = buildString {
        appendLine("profileId=${profile.profileId}")
        appendLine("displayName=${profile.displayName}")
        appendLine("androidId=${profile.androidId}")
        appendLine("instanceId=${profile.instanceId}")
        appendLine("installId=${profile.installId}")
        appendLine("gsfId=${profile.gsfId}")
        appendLine("mediaDrmId=${profile.mediaDrmId}")
        appendLine("wifiMac=${profile.wifiMac}")
        appendLine("bluetoothMac=${profile.bluetoothMac}")
        appendLine("serial=${profile.serial}")
        appendLine("generation=${profile.generation}")
        profile.locale?.let { appendLine("locale=$it") }
        profile.timeZone?.let { appendLine("timeZone=$it") }
        profile.build.fingerprint?.let { appendLine("build.fingerprint=$it") }
        profile.build.model?.let { appendLine("build.model=$it") }
        profile.build.manufacturer?.let { appendLine("build.manufacturer=$it") }
        profile.build.brand?.let { appendLine("build.brand=$it") }
        profile.build.device?.let { appendLine("build.device=$it") }
        profile.build.product?.let { appendLine("build.product=$it") }
        profile.screen?.let {
            appendLine("screen.widthPx=${it.widthPx}")
            appendLine("screen.heightPx=${it.heightPx}")
            appendLine("screen.densityDpi=${it.densityDpi}")
        }
    }

    /**
     * Returns null when the text does not carry a usable profile.
     *
     * Null rather than a partially-populated profile with invented values: an instance
     * whose identity is half-read is worse than one that falls back to the host's, because
     * the halves would be inconsistent with each other.
     */
    fun decode(text: String): DeviceProfile? {
        val map = text.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

        val androidId = map["androidId"] ?: return null
        if (androidId.length != 16 || androidId.any { it !in "0123456789abcdef" }) return null

        return runCatching {
            DeviceProfile(
                profileId = map["profileId"] ?: return@runCatching null,
                displayName = map["displayName"].orEmpty(),
                androidId = androidId,
                instanceId = UUID.fromString(map["instanceId"] ?: return@runCatching null),
                installId = UUID.fromString(map["installId"] ?: return@runCatching null),
                gsfId = map["gsfId"].orEmpty(),
                mediaDrmId = map["mediaDrmId"].orEmpty(),
                wifiMac = map["wifiMac"].orEmpty(),
                bluetoothMac = map["bluetoothMac"].orEmpty(),
                serial = map["serial"].orEmpty(),
                locale = map["locale"],
                timeZone = map["timeZone"],
                screen = screenOf(map),
                build = BuildOverrides(
                    fingerprint = map["build.fingerprint"],
                    model = map["build.model"],
                    manufacturer = map["build.manufacturer"],
                    brand = map["build.brand"],
                    device = map["build.device"],
                    product = map["build.product"],
                ),
                generation = map["generation"]?.toIntOrNull() ?: 1,
            )
        }.getOrNull()
    }

    private fun screenOf(map: Map<String, String>): ScreenProfile? {
        val width = map["screen.widthPx"]?.toIntOrNull() ?: return null
        val height = map["screen.heightPx"]?.toIntOrNull() ?: return null
        val density = map["screen.densityDpi"]?.toIntOrNull() ?: return null
        return ScreenProfile(width, height, density)
    }
}
