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
                capabilities.virtualGmsInstalled ->
                    RoutingDecision(flow, GoogleMode.VIRTUAL_GMS,
                        "Legacy Google Sign-In checks the app's package and signing certificate, which only in-space Google Play services can satisfy.")
                else ->
                    RoutingDecision(flow, GoogleMode.UNSUPPORTED,
                        "Legacy Google Sign-In requires in-space Google Play services. Enable them for this space, or use an app that supports Sign in with Google.")
            }

            // Reasoning, not a measurement: the audience of a Sign in with Google token is
            // a web/server client id, so a token obtained through the host's GMS should be
            // valid for the app's own backend. This is the layer's central hypothesis and
            // it stays labelled as one until a Google Sign-In sample confirms it on a
            // device (phase 5).
            GoogleFlow.CREDENTIAL_MANAGER -> if (capabilities.hostGmsAvailable) {
                RoutingDecision(flow, GoogleMode.HOST_BRIDGE,
                    "Unverified: Sign in with Google issues a token for the app's server client, so the device's own Google Play services should be able to answer. Not yet confirmed against a real sample.")
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
                capabilities.hostGmsAvailable ->
                    RoutingDecision(flow, GoogleMode.HOST_BRIDGE,
                        "Firebase Auth consumes a token from Sign in with Google, which the host bridge can provide.")
                else ->
                    RoutingDecision(flow, GoogleMode.UNSUPPORTED, "No usable token source.")
            }

            // The browser round trip: correct about the outbound half, wrong about the
            // return, and it took a physical run to see which half was being claimed.
            //
            // This used to answer PASSTHROUGH whenever the app declared a redirect scheme
            // and the device had Custom Tabs — "the most reliable path", in the decision
            // the UI showed the user. Sending the user to the browser works. Getting them
            // back does not: the redirect is an `ACTION_VIEW` for `myapp://callback`,
            // Chrome hands it to `PackageManagerService`, and the activity that declares
            // that scheme belongs to a package the platform has never installed. Nothing
            // resolves, and the user is left on a browser page with a sign-in that
            // completed on Google's side and arrived nowhere.
            //
            // UNIQUE cannot close this. An intent filter is fixed in a manifest at build
            // time, the scheme is the *guest's* and is only known at import, and the one
            // runtime lever the platform offers — enabling and disabling a pre-declared
            // `<activity-alias>` — still needs the scheme to have been declared in
            // advance. The interception UNIQUE does have runs inside the guest's own
            // process; Chrome's process is not UNIQUE's to hook.
            //
            // So it is reported as unsupported, with the reason, which is worth more than
            // a mode that reads as working. In-space Google Play services remains the
            // path that can answer this, because there the whole exchange stays inside
            // the space.
            GoogleFlow.OAUTH_WEB -> if (manifest.deepLinkSchemes.isEmpty()) {
                // No declared scheme means the app is not expecting a redirect back into
                // itself at all — it reads the result some other way, and the browser is
                // just a browser. Nothing here needs Google Play services.
                RoutingDecision(flow, GoogleMode.PASSTHROUGH,
                    "The app declares no redirect scheme, so nothing has to come back into this instance.")
            } else {
                RoutingDecision(flow, GoogleMode.UNSUPPORTED,
                    "The browser can be opened, but the redirect back to " +
                        "${manifest.deepLinkSchemes.first()}:// cannot arrive: that scheme " +
                        "is declared by a package the device has not installed, so nothing " +
                        "resolves it. Use in-space Google Play services, or a sign-in the " +
                        "app can complete without leaving itself.")
            }

            GoogleFlow.FCM -> if (capabilities.hostGmsAvailable) {
                RoutingDecision(flow, GoogleMode.HOST_BRIDGE,
                    "Experimental: push registration is bridged through the host. Whether Google Play services accepts it varies by version; the result is recorded per device.")
            } else {
                RoutingDecision(flow, GoogleMode.UNSUPPORTED, "Google Play services are not available on this device.")
            }

            // Attestation-bound, and not investigated yet. The expectation is that this
            // does not work, but no sample has been run on a device, so the decision says
            // "not yet" rather than "never" - the difference matters when someone later
            // decides whether to spend a week on it.
            GoogleFlow.PLAY_GAMES -> RoutingDecision(flow, GoogleMode.UNSUPPORTED,
                "Not supported yet: Play Games checks an attested app identity, and UNIQUE has not been tested against it.")
        }
    }
}
