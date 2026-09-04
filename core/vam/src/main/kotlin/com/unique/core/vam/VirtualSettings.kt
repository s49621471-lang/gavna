package com.unique.core.vam

import android.os.Build
import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.common.profile.DeviceProfile
import com.unique.core.common.profile.ProfileField
import com.unique.core.diagnostics.Diagnostics

/**
 * Gives a guest the identity of its *instance* rather than of the device.
 *
 * Without this, every instance of every app reports the same `ANDROID_ID`, the same
 * serial and the same `Build` fields — so an app that fingerprints the device, which is
 * most of the interesting ones, sees two clones as one installation. Separate identity is
 * the whole point of a second instance; a second data directory alone does not provide it.
 *
 * Two mechanisms, because the platform uses two:
 *
 *  - **Settings** are read through the `settings` content provider, so
 *    [answerSettingsCall] is consulted from the provider wrapper UNIQUE already installs
 *    for `AttributionSource` (§6.4).
 *  - **`Build` fields** are `static final String`s read at class initialisation, so they
 *    are written once, before any guest code runs.
 *
 * Values come from [DeviceProfile] and nowhere else. Nothing in the codebase is allowed to
 * synthesise an identity value locally — that is the rule that keeps two subsystems from
 * disagreeing about who this instance is.
 */
object VirtualSettings {

    /** The settings a guest may read that name the device rather than a preference. */
    private val SECURE_FIELDS: Map<String, ProfileField> = mapOf(
        "android_id" to ProfileField.ANDROID_ID,
        "bluetooth_address" to ProfileField.BLUETOOTH_MAC,
    )

    /** `Settings.NameValueTable.VALUE`, the key the platform's own cache reads. */
    private const val VALUE_KEY = "value"

    @Volatile private var profile: DeviceProfile? = null
    @Volatile private var answered = 0

    val boundProfile: DeviceProfile? get() = profile
    val answeredCount: Int get() = answered

    @Synchronized
    fun bind(profile: DeviceProfile) {
        this.profile = profile
        answered = 0
        Diagnostics.info(
            DiagChannel.PROCESS, "PROFILE_BOUND",
            mapOf(
                "profile" to profile.profileId,
                "androidId" to profile.androidId,
                "generation" to profile.generation.toString(),
            ),
        )
    }

    @Synchronized
    fun reset() {
        profile = null
        answered = 0
    }

    /**
     * Answers a `settings` provider `call`, or returns null to let the platform answer.
     *
     * The arguments are identified by *value* rather than position: the platform's own
     * method names (`GET_secure` and friends) and the setting name are both strings among
     * several, and the shape has changed across releases. A call UNIQUE does not
     * recognise is passed through, which is the safe direction.
     */
    fun answerSettingsCall(args: Array<Any?>): Bundle? {
        val current = profile ?: return null
        val strings = args.filterIsInstance<String>()
        val getter = strings.firstOrNull { it.startsWith("GET_") } ?: return null
        if (getter != "GET_secure") return null

        val field = strings.firstNotNullOfOrNull { SECURE_FIELDS[it] } ?: return null
        val value = current.value(field) ?: return null
        answered++
        Diagnostics.event(
            DiagChannel.PROCESS, DiagLevel.DEBUG, "SETTING_ANSWERED",
            mapOf("setting" to field.name, "value" to value),
        )
        return Bundle().apply { putString(VALUE_KEY, value) }
    }

    /**
     * Writes the profile's `Build` overrides into the process.
     *
     * `Build.MODEL` and friends are `static final`, but they are ordinary fields at
     * runtime and the class has already been initialised by the time a guest reads them,
     * so setting them reflectively is both possible and stable. It affects UNIQUE's own
     * code in this process too, which is correct: the process serves exactly one instance.
     *
     * A field the profile does not override is left alone rather than blanked — a guest
     * that sees an empty `Build.MODEL` behaves worse than one that sees the real device.
     */
    fun applyBuildOverrides(profile: DeviceProfile): Int {
        val overrides = buildMap {
            profile.build.fingerprint?.let { put("FINGERPRINT" to Build::class.java, it) }
            profile.build.model?.let { put("MODEL" to Build::class.java, it) }
            profile.build.manufacturer?.let { put("MANUFACTURER" to Build::class.java, it) }
            profile.build.brand?.let { put("BRAND" to Build::class.java, it) }
            profile.build.device?.let { put("DEVICE" to Build::class.java, it) }
            profile.build.product?.let { put("PRODUCT" to Build::class.java, it) }
            put("SERIAL" to Build::class.java, profile.serial)
        }
        var applied = 0
        for ((target, value) in overrides) {
            val (name, owner) = target
            val ok = runCatching {
                val field = owner.getDeclaredField(name)
                field.isAccessible = true
                field.set(null, value)
                true
            }.getOrElse {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "BUILD_OVERRIDE_FAILED",
                    mapOf("field" to name, "error" to it.toString()),
                )
                false
            }
            if (ok) applied++
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "BUILD_OVERRIDES_APPLIED",
            mapOf("applied" to applied.toString(), "requested" to overrides.size.toString()),
        )
        return applied
    }
}
