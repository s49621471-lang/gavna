package com.unique.core.common.compat

/** How well a package is known to work inside UNIQUE. */
enum class SupportLevel { SUPPORTED, PARTIAL, EXPERIMENTAL, UNSUPPORTED, UNKNOWN }

/**
 * Behaviour switches a compatibility profile can turn on for one package.
 *
 * Every entry exists because a real app needs it. Adding a flag is how package-specific
 * behaviour enters the codebase — an architecture test rejects `packageName == "..."`
 * comparisons anywhere outside this module, which is what keeps the engine free of the
 * `if (packageName)` sprawl that makes these projects unmaintainable.
 */
enum class CompatFlag {
    /** Run this package against an in-space GMS rather than the host bridge. */
    FORCE_VIRTUAL_GMS,
    /** Never route this package's Google calls anywhere; let them fail as they would. */
    DISABLE_GOOGLE_LAYER,
    /** Skip native path redirection for `/proc` reads (some anti-tamper code trips on it). */
    NO_PROC_REDIRECT,
    /** Report the host's real external storage instead of the virtual one. */
    REAL_EXTERNAL_STORAGE,
    /** Force every component of the package into one process regardless of the manifest. */
    SINGLE_PROCESS,
    /** Do not intercept `System.loadLibrary`; the app resolves libraries itself. */
    NO_NATIVE_LOAD_HOOK,
    /** Rebuild notifications from `extras` instead of forwarding custom RemoteViews. */
    REBUILD_NOTIFICATIONS,
    /** The package's `.so` files are not 16 KB aligned; it cannot run on 16 KB devices. */
    NATIVE_ALIGNMENT_16K,
    /** Requires Play Integrity / attestation, which app-level virtualization cannot provide. */
    REQUIRES_ATTESTATION,
}

/** Google flows that can be routed independently. */
enum class GoogleFlow { SIGN_IN, CREDENTIAL_MANAGER, ACCOUNT_MANAGER, FIREBASE_AUTH, OAUTH_WEB, FCM, PLAY_GAMES }

/** How a given flow is served. */
enum class GoogleMode {
    /** Mode A: an in-space GMS answers, so package+signature identity is the app's own. */
    VIRTUAL_GMS,
    /** Mode B: the host's real GMS answers on the app's behalf. */
    HOST_BRIDGE,
    /** Mode C: no GMS involvement — WebView/Custom Tabs OAuth with a deep-link return. */
    PASSTHROUGH,
    /** Explicitly not served; the app receives a typed error and a diagnostic is written. */
    UNSUPPORTED,
}

data class Workaround(val id: String, val description: String, val params: Map<String, String> = emptyMap())

data class CompatibilityProfile(
    val packageName: String,
    /** Applies to these versionCodes only; null means every version. */
    val minVersionCode: Long? = null,
    val maxVersionCode: Long? = null,
    val flags: Set<CompatFlag> = emptySet(),
    val googlePolicy: Map<GoogleFlow, GoogleMode> = emptyMap(),
    val workarounds: List<Workaround> = emptyList(),
    val support: SupportLevel = SupportLevel.UNKNOWN,
    /** Shown verbatim to the user when [support] is UNSUPPORTED or PARTIAL. */
    val note: String? = null,
) {
    fun appliesTo(versionCode: Long): Boolean =
        (minVersionCode == null || versionCode >= minVersionCode) &&
            (maxVersionCode == null || versionCode <= maxVersionCode)
}

/**
 * Resolves the effective profile for a package.
 *
 * Local overrides win over the shipped database so a user (or a support session) can fix
 * an app without waiting for an app update, which is the whole reason the database is
 * data rather than code.
 */
class CompatibilityResolver(
    private val shipped: List<CompatibilityProfile>,
    private val local: List<CompatibilityProfile> = emptyList(),
    private val default: CompatibilityProfile = CompatibilityProfile(packageName = "*"),
) {
    fun resolve(packageName: String, versionCode: Long): CompatibilityProfile {
        val localMatch = local.firstOrNull { it.packageName == packageName && it.appliesTo(versionCode) }
        val shippedMatch = shipped.firstOrNull { it.packageName == packageName && it.appliesTo(versionCode) }
        return when {
            localMatch != null && shippedMatch != null -> merge(shippedMatch, localMatch)
            localMatch != null -> localMatch
            shippedMatch != null -> shippedMatch
            else -> default.copy(packageName = packageName)
        }
    }

    private fun merge(base: CompatibilityProfile, override: CompatibilityProfile) = base.copy(
        flags = base.flags + override.flags,
        googlePolicy = base.googlePolicy + override.googlePolicy,
        workarounds = base.workarounds + override.workarounds,
        support = if (override.support != SupportLevel.UNKNOWN) override.support else base.support,
        note = override.note ?: base.note,
    )
}
