package com.unique.core.google

import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.compat.CompatFlag
import com.unique.core.common.compat.CompatibilityProfile
import com.unique.core.common.compat.GoogleFlow
import com.unique.core.common.compat.GoogleMode
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/** What UNIQUE can actually offer on this device right now. */
data class GoogleCapabilities(
    /** The host has Google Play services installed and usable. */
    val hostGmsAvailable: Boolean,
    /** GMS and GSF have been imported into the virtual space. */
    val virtualGmsInstalled: Boolean,
    /** A browser that supports Custom Tabs is present. */
    val customTabsAvailable: Boolean,
) {
    companion object {
        val NONE = GoogleCapabilities(false, false, false)
    }
}

/** Why the router chose what it chose — shown in App Details -> Google. */
data class RoutingDecision(
    val flow: GoogleFlow,
    val mode: GoogleMode,
    val rationale: String,
)

/**
 * Chooses how each Google flow is served for a given package.
 *
 * The order is: an explicit compatibility-database policy always wins; otherwise the
 * decision is made from *evidence in the app's own manifest* plus what the device can
 * actually do. Nothing here is a package-name special case — that is what the
 * compatibility database is for.
 */
class GoogleCompatRouter(
    private val capabilities: GoogleCapabilities,
) {

    fun route(
        manifest: ApkManifest,
        profile: CompatibilityProfile,
        flow: GoogleFlow,
    ): RoutingDecision {
        val decision = decide(manifest, profile, flow)
        Diagnostics.info(
            DiagChannel.GOOGLE, "GOOGLE_ROUTE",
            mapOf(
                "package" to manifest.packageName,
                "flow" to flow.name,
                "mode" to decision.mode.name,
                "why" to decision.rationale,
            ),
        )
        return decision
    }

    fun routeAll(manifest: ApkManifest, profile: CompatibilityProfile): List<RoutingDecision> =
        GoogleFlow.entries.map { route(manifest, profile, it) }

    private fun decide(
        manifest: ApkManifest,
        profile: CompatibilityProfile,
        flow: GoogleFlow,
    ): RoutingDecision {
        if (CompatFlag.DISABLE_GOOGLE_LAYER in profile.flags) {
            return RoutingDecision(flow, GoogleMode.UNSUPPORTED,
                "The compatibility database disables the Google layer for this app.")
        }
        profile.googlePolicy[flow]?.let {
            return RoutingDecision(flow, it, "Set explicitly in the compatibility database.")
        }
        if (CompatFlag.FORCE_VIRTUAL_GMS in profile.flags) {
            return if (capabilities.virtualGmsInstalled) {
                RoutingDecision(flow, GoogleMode.VIRTUAL_GMS,
                    "The compatibility database requires in-space Google Play services.")
            } else {
                RoutingDecision(flow, GoogleMode.UNSUPPORTED,
                    "This app needs in-space Google Play services, which are not installed in this space.")
            }
        }

        return when (flow) {
            // Package+signature bound. Only an in-space GMS can answer correctly; a host
            // bridge would return a token for the wrong OAuth client, which fails
            // server-side in a way the app developer cannot debug.
            GoogleFlow.SIGN_IN -> when {
                manifest.deepLinkSchemes.isNotEmpty() && capabilities.customTabsAvailable ->
                    RoutingDecision(flow, GoogleMode.PASSTHROUGH,
                        "The app declares an OAuth redirect scheme, so the browser flow can be used and is the most reliable path.")
                capabilities.virtualGmsInstalled ->
                    RoutingDecision(flow, GoogleMode.VIRTUAL_GMS,
                        "Legacy Google Sign-In checks the app's package and signing certificate, which only in-space Google Play services can satisfy.")
                else ->
                    RoutingDecision(flow, GoogleMode.UNSUPPORTED,
                        "Legacy Google Sign-In requires in-space Google Play services. Enable them for this space, or use an app that supports Sign in with Google.")
            }

            // Audience is a web/server client id, so the host's GMS produces a token the
            // app's backend genuinely accepts.
            GoogleFlow.CREDENTIAL_MANAGER -> if (capabilities.hostGmsAvailable) {
                RoutingDecision(flow, GoogleMode.HOST_BRIDGE,
                    "Sign in with Google issues a token for the app's server client, so the device's own Google Play services can answer.")
            } else {
                RoutingDecision(flow, GoogleMode.UNSUPPORTED, "Google Play services are not available on this device.")
            }

            GoogleFlow.ACCOUNT_MANAGER -> if (capabilities.hostGmsAvailable) {
                RoutingDecision(flow, GoogleMode.HOST_BRIDGE,
                    "Account access is bridged to the device's accounts; consent is shown as UNIQUE.")
            } else {
                RoutingDecision(flow, GoogleMode.UNSUPPORTED, "No Google accounts are available.")
            }

            // Follows whichever flow produced the token it consumes.
            GoogleFlow.FIREBASE_AUTH -> when {
                manifest.deepLinkSchemes.isNotEmpty() ->
                    RoutingDecision(flow, GoogleMode.PASSTHROUGH,
                        "Firebase Auth consumes a token from another flow; the app's browser redirect can supply it.")
                capabilities.hostGmsAvailable ->
                    RoutingDecision(flow, GoogleMode.HOST_BRIDGE,
                        "Firebase Auth consumes a token from Sign in with Google, which the host bridge can provide.")
                else ->
                    RoutingDecision(flow, GoogleMode.UNSUPPORTED, "No usable token source.")
            }

            GoogleFlow.OAUTH_WEB -> RoutingDecision(flow, GoogleMode.PASSTHROUGH,
                "Browser-based OAuth needs no Google Play services, only a working redirect back into this instance.")

            GoogleFlow.FCM -> if (capabilities.hostGmsAvailable) {
                RoutingDecision(flow, GoogleMode.HOST_BRIDGE,
                    "Experimental: push registration is bridged through the host. Whether Google Play services accepts it varies by version; the result is recorded per device.")
            } else {
                RoutingDecision(flow, GoogleMode.UNSUPPORTED, "Google Play services are not available on this device.")
            }

            // Attestation-bound. Not achievable in app-level virtualization, and UNIQUE
            // does not attempt to defeat it.
            GoogleFlow.PLAY_GAMES -> RoutingDecision(flow, GoogleMode.UNSUPPORTED,
                "Play Games requires an attested, installed app identity, which app-level virtualization cannot provide.")
        }
    }
}
