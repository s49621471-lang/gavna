package com.unique.core.vprofile

import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.profile.DeviceProfile
import com.unique.core.common.profile.ProfileField
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics

/**
 * The single source of every identity value a virtual app can observe.
 *
 * ARCHITECTURE.md section 11 makes two promises that this class exists to keep:
 *
 *  - **Consistency.** The same logical value is identical through every API that can
 *    expose it. Adapters never compute a value; they ask here.
 *  - **Stability.** Values do not change while a process is alive. A *Regenerate*
 *    swaps the profile in the store, and the virtual app picks it up at its next cold
 *    start — never mid-process, which would produce exactly the incoherence apps notice.
 */
object DeviceProfileProvider {

    @Volatile private var profile: DeviceProfile? = null

    /** Bound once per virtual app process, before any app code runs. */
    @Synchronized
    fun bind(p: DeviceProfile) {
        val existing = profile
        if (existing != null && existing.profileId == p.profileId && existing.generation == p.generation) return
        if (existing != null) {
            // Rebinding a live process would break the stability promise above.
            Diagnostics.warn(
                DiagChannel.LAUNCH, "PROFILE_REBIND_IGNORED",
                mapOf("current" to existing.profileId, "requested" to p.profileId),
            )
            return
        }
        profile = p
        Diagnostics.info(
            DiagChannel.LAUNCH, "PROFILE_BOUND",
            mapOf("profileId" to p.profileId, "generation" to p.generation.toString()),
        )
    }

    val current: DeviceProfile? get() = profile

    val isBound: Boolean get() = profile != null

    /** Returns the profile value, or null when nothing overrides the host's own. */
    fun value(field: ProfileField): String? = profile?.value(field)

    fun require(field: ProfileField): String =
        value(field) ?: error("DeviceProfileProvider has no value for $field; is a profile bound?")

    /** Test seam. Never called from production code. */
    @Synchronized
    internal fun resetForTest() { profile = null }

    // -----------------------------------------------------------------------------
    // Settings interception
    // -----------------------------------------------------------------------------

    /**
     * Names in `Settings.Secure`/`Global`/`System` that this profile answers, mapped to
     * the field they come from. Everything not listed passes through to the host,
     * because inventing values for settings an app legitimately needs breaks it.
     */
    private val SETTINGS_FIELDS: Map<String, ProfileField> = mapOf(
        "android_id" to ProfileField.ANDROID_ID,
        "bluetooth_address" to ProfileField.BLUETOOTH_MAC,
    )

    fun settingsValue(name: String): String? =
        SETTINGS_FIELDS[name]?.let { value(it) }

    /**
     * Shims for the settings `ContentProvider`.
     *
     * `Settings.Secure.getString` does not touch a Binder interface UNIQUE proxies
     * directly — it goes through `IContentProvider.call("GET_secure", name, …)`. So this
     * is the point of interception, which is also why LSPlant is not needed for it.
     *
     * The shim is written against the *method name and argument shape*, not an index:
     * the settings provider's `call` signature gained a `AttributionSource` parameter in
     * Android 12 and changed again since, and this definition is unaffected by that.
     *
     * Wiring: applied by `:core:vam` when it wraps the acquired settings provider.
     * TODO(phase-3): that wrapping is not in place yet, so these shims are defined and
     * unit-testable but not yet installed. Until then a virtual app reads the host's real
     * ANDROID_ID, and `DeviceProfileStatus.settingsInterceptionActive` reports false.
     */
    fun settingsProviderShims(): List<MethodShim> = listOf(
        shim("call") {
            rewriteResult { result ->
                val bundle = result as? Bundle ?: return@rewriteResult result
                // The settings provider returns the value under "value".
                val requested = bundle.getString(KEY_REQUESTED_NAME)
                val override = requested?.let(::settingsValue)
                if (override != null) Bundle(bundle).apply { putString("value", override) } else bundle
            }
        },
    )

    /**
     * Not a real settings-provider key: the outbound half of the shim records which
     * setting was asked for so the result rewriter knows what it is looking at.
     * Populated by the argument rule installed alongside the result rewriter in
     * `:core:vam`.
     */
    internal const val KEY_REQUESTED_NAME = "_unique_requested_name"
}

/** What Diagnostics shows for the Device Profile section. Honest about what is live. */
data class DeviceProfileStatus(
    val bound: Boolean,
    val profileId: String?,
    val generation: Int?,
    /** True once `:core:vam` wraps the settings provider. Currently always false. */
    val settingsInterceptionActive: Boolean,
    /** True once native property virtualization is installed. Currently always false. */
    val nativePropertiesActive: Boolean,
) {
    companion object {
        fun current(): DeviceProfileStatus = DeviceProfileStatus(
            bound = DeviceProfileProvider.isBound,
            profileId = DeviceProfileProvider.current?.profileId,
            generation = DeviceProfileProvider.current?.generation,
            settingsInterceptionActive = false,
            nativePropertiesActive = false,
        )
    }
}
