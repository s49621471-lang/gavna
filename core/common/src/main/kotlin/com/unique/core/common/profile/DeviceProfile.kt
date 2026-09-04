package com.unique.core.common.profile

import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * Every identity value a virtual app can observe.
 *
 * Declaring them as an enum rather than as scattered strings is what makes the
 * consistency requirement testable: the consistency suite enumerates this enum, asks
 * every registered adapter for the value, and asserts they all agree. Adding a new
 * identity surface means adding a row here, not adding a hook somewhere.
 */
enum class ProfileField {
    ANDROID_ID,
    INSTANCE_ID,
    INSTALL_ID,
    GSF_ID,
    MEDIA_DRM_ID,
    WIFI_MAC,
    BLUETOOTH_MAC,
    SERIAL,
    BUILD_FINGERPRINT,
    BUILD_MODEL,
    BUILD_MANUFACTURER,
    BUILD_BRAND,
    BUILD_DEVICE,
    BUILD_PRODUCT,
    LOCALE,
    TIME_ZONE,
}

/** Optional `Build.*` overrides. Null fields mean "report the host's real value". */
data class BuildOverrides(
    val fingerprint: String? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    val brand: String? = null,
    val device: String? = null,
    val product: String? = null,
) {
    val isEmpty: Boolean
        get() = fingerprint == null && model == null && manufacturer == null &&
            brand == null && device == null && product == null

    companion object {
        val NONE = BuildOverrides()
    }
}

data class ScreenProfile(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
)

/**
 * A stable, self-consistent identity for one virtual instance.
 *
 * Values are generated once, persisted, and returned identically for the life of the
 * profile. [generation] increments on an explicit *Regenerate*; the new values take
 * effect at the virtual app's next cold start, never mid-process — changing an identifier
 * underneath a running app is exactly the incoherence this design exists to prevent.
 */
data class DeviceProfile(
    val profileId: String,
    val displayName: String,
    val androidId: String,
    val instanceId: UUID,
    val installId: UUID,
    val gsfId: String,
    val mediaDrmId: String,
    val wifiMac: String,
    val bluetoothMac: String,
    val serial: String,
    val locale: String? = null,
    val timeZone: String? = null,
    val screen: ScreenProfile? = null,
    val build: BuildOverrides = BuildOverrides.NONE,
    val generation: Int = 1,
) {
    init {
        require(androidId.length == 16 && androidId.all { it in "0123456789abcdef" }) {
            "androidId must be 16 lowercase hex characters, got '$androidId'"
        }
    }

    /**
     * Reads a field. This is the only accessor the platform adapters may use; nothing
     * else in the codebase is allowed to synthesise an identity value.
     */
    fun value(field: ProfileField): String? = when (field) {
        ProfileField.ANDROID_ID -> androidId
        ProfileField.INSTANCE_ID -> instanceId.toString()
        ProfileField.INSTALL_ID -> installId.toString()
        ProfileField.GSF_ID -> gsfId
        ProfileField.MEDIA_DRM_ID -> mediaDrmId
        ProfileField.WIFI_MAC -> wifiMac
        ProfileField.BLUETOOTH_MAC -> bluetoothMac
        ProfileField.SERIAL -> serial
        ProfileField.BUILD_FINGERPRINT -> build.fingerprint
        ProfileField.BUILD_MODEL -> build.model
        ProfileField.BUILD_MANUFACTURER -> build.manufacturer
        ProfileField.BUILD_BRAND -> build.brand
        ProfileField.BUILD_DEVICE -> build.device
        ProfileField.BUILD_PRODUCT -> build.product
        ProfileField.LOCALE -> locale
        ProfileField.TIME_ZONE -> timeZone
    }

    /** Short form for the UI — the full ANDROID_ID is shown, everything else abbreviated. */
    fun summary(): Map<String, String> = mapOf(
        "Android ID" to androidId,
        "Instance ID" to instanceId.toString(),
        "Install ID" to installId.toString().take(8) + "…",
        "Generation" to generation.toString(),
    )
}

/**
 * Creates and regenerates device profiles.
 *
 * This is the *only* place in the codebase permitted to call [SecureRandom] or
 * [UUID.randomUUID] — an architecture test fails the build if either appears elsewhere.
 * Centralising generation is what makes "the same value from every API" enforceable
 * rather than aspirational.
 */
class DeviceProfileFactory(private val random: java.util.Random = SecureRandom()) {

    fun create(displayName: String, profileId: String = UUID.randomUUID().toString()): DeviceProfile =
        DeviceProfile(
            profileId = profileId,
            displayName = displayName,
            androidId = randomAndroidId(),
            instanceId = randomUuid(),
            installId = randomUuid(),
            gsfId = randomGsfId(),
            mediaDrmId = randomHex(32),
            wifiMac = randomMac(),
            bluetoothMac = randomMac(),
            serial = randomHex(16).uppercase(Locale.ROOT),
            generation = 1,
        )

    /**
     * Produces the next generation of [previous]: new identity values, same profile id,
     * same display name, same user-chosen overrides (locale, timezone, build, screen).
     */
    fun regenerate(previous: DeviceProfile): DeviceProfile = previous.copy(
        androidId = randomAndroidId(),
        instanceId = randomUuid(),
        installId = randomUuid(),
        gsfId = randomGsfId(),
        mediaDrmId = randomHex(32),
        wifiMac = randomMac(),
        bluetoothMac = randomMac(),
        serial = randomHex(16).uppercase(Locale.ROOT),
        generation = previous.generation + 1,
    )

    /** 64 bits, rendered exactly as `Settings.Secure.ANDROID_ID` is: 16 lowercase hex chars. */
    fun randomAndroidId(): String = randomHex(16)

    /** Google Services Framework ids are decimal-rendered 64-bit values. */
    private fun randomGsfId(): String {
        val v = java.lang.Long.toUnsignedString(random.nextLong().let { if (it == 0L) 1L else it })
        return v
    }

    /**
     * Locally-administered unicast MAC. Modern Android returns `02:00:00:00:00:00` to
     * apps anyway; a plausible value is generated so a profile stays self-consistent if a
     * compatibility profile ever enables reporting one.
     */
    private fun randomMac(): String {
        val b = ByteArray(6)
        random.nextBytes(b)
        b[0] = ((b[0].toInt() and 0xFE) or 0x02).toByte()
        return b.joinToString(":") { "%02x".format(it) }
    }

    private fun randomUuid(): UUID {
        val b = ByteArray(16)
        random.nextBytes(b)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte() // version 4
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte() // variant 1
        var msb = 0L; var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (b[i].toLong() and 0xFF)
        for (i in 8..15) lsb = (lsb shl 8) or (b[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }

    private fun randomHex(chars: Int): String {
        val b = ByteArray((chars + 1) / 2)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }.take(chars)
    }
}
