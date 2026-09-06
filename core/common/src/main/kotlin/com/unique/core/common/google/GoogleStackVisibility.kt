package com.unique.core.common.google

/**
 * Whether a guest is told the device has Google Play services.
 *
 * ## Visible, until this instance proves it cannot be
 *
 * Hiding it was unconditional once, and that is why a user with a fully Googled phone
 * was told, by every app, to install Google Play services:
 *
 * ```
 * GOOGLE_ENVIRONMENT gmsPresent=true gmsEnabled=true gmsVersionCode=263234035
 * W GooglePlayServicesUtil: com.axlebolt.standoff2 requires Google Play services,
 *     but they are missing.
 * ```
 *
 * It costs more than a message. `DynamiteModule` loads Google's own code from the Play
 * services APK through a provider; `AdvertisingIdClient` binds a service that never
 * checks the caller's package; `emoji2` looks the package up and switches itself off when
 * it is absent. None of those need a virtual app's identity to be accepted, and all of
 * them were off.
 *
 * ## What hiding was protecting against, and why it cannot be predicted
 *
 * One call: `GmsClient.getRemoteService`. It sends `context.getPackageName()` to
 * `com.google.android.gms`, which resolves the *calling uid* to UNIQUE's packages, does
 * not find the guest among them, and refuses. Where that lands decides everything:
 *
 * ```
 * (fatal)     at …c.getRemoteService (play-services-basement@@17.4.0:25)
 *             at …c$g.handleMessage · Looper.loop        <- the app's MAIN looper
 *             FATAL EXCEPTION: main
 *
 * (survived)  at …e.getRemoteService
 *             at …api.internal.zabm.run
 *             E GoogleApiManager: Failed to get service from broker.
 * ```
 *
 * An earlier version of this file tried to predict which of the two an app would do,
 * from the `com.google.android.gms.version` meta-data every Play services client
 * declares. **That was wrong, and the log said so immediately:**
 *
 * ```
 * GOOGLE_STACK_HIDDEN reason=SDK_TOO_OLD gmsVersion=12451000   (x3)
 * ```
 *
 * — the same number for three unrelated apps, one of them ChatGPT, which certainly does
 * not ship a 2018 client library. `google_play_services_version` is the *minimum GmsCore
 * version the client requires*, not the client's own version, and Google stopped moving
 * it years ago. It cannot tell two client libraries apart, so nothing built on it could
 * have worked. The mistake is written down here because the number looks exactly like a
 * version, and the next person to reach for it deserves to be stopped.
 *
 * ## So the answer is measured instead of guessed
 *
 * Play services is visible to every guest. If a guest dies of that specific refusal, the
 * crash handler writes `hide` into this instance's own file before the process goes, and
 * the next launch hides the stack — which is where that app stays, taking the same
 * no-Google-services path it took before, while every other app keeps the truth.
 *
 * The cost is one crash, once, for an app whose client library is old enough to die of
 * it. The alternative was every app being lied to forever, and the log above is what that
 * looked like from the user's side.
 */
object GoogleStackVisibility {

    /**
     * The refusal that kills an old Play services client, as it appears in the message.
     *
     * Matched on text because that is all a `SecurityException` from another process
     * carries — the class is `java.lang.SecurityException` and the sentence is what
     * identifies it. Play services has phrased it this way for as long as the logs in
     * this repository go back.
     */
    const val REFUSED_CALLING_PACKAGE = "Unknown calling package name"

    enum class Reason {
        /** Nothing says this guest cannot see it, so it does. */
        VISIBLE_BY_DEFAULT,

        /** This instance died of the refusal once, and the crash recorded it. */
        AUTO_HIDDEN_AFTER_CRASH,

        /** The user, or a support session, wrote the answer into the instance's file. */
        OVERRIDDEN,

        /** A hide recorded by a build whose mechanism this one has replaced. */
        STALE_MARK_DISCARDED,
    }

    data class Decision(
        val hidden: Boolean,
        val reason: Reason,
        val detail: String,
    ) {
        fun toMap(): Map<String, String> = mapOf(
            "hidden" to hidden.toString(),
            "reason" to reason.name,
            "detail" to detail,
        )
    }

    /** What the instance's own file says, when it says anything. */
    enum class Override {
        HIDE,
        SHOW,
        AUTO_HIDE,

        /**
         * An automatic hide recorded by a build whose mechanism is gone.
         *
         * Distinguished from "no override" rather than folded into it, because the file
         * should be deleted as well as ignored — leaving it would mean re-reading and
         * re-discarding it on every launch for the life of the instance.
         */
        STALE_AUTO_HIDE,
    }

    /**
     * Which mechanism was in place when an automatic hide was recorded.
     *
     * A recorded hide is a statement about what happened *under a particular build*, and
     * it went stale the moment the build changed. Standoff 2 is the case that made this
     * necessary: it died of the refusal under a build that had no answer for it, the mark
     * was written, and the next build — which rewrites the calling package so the refusal
     * never happens — still read the mark and told the app there was no Play services.
     * The user saw "install Google Play services" from a build in which Google worked.
     *
     * So a mark carries the generation that wrote it, and a mark from an older one is not
     * evidence about this build. Bump this whenever the way a guest's identity reaches
     * Play services changes.
     *
     * 1. Play services hidden from a guest that had crashed once.
     * 2. `GmsBrokerBinder` rewrites the calling package, so the crash should not recur.
     */
    const val IDENTITY_GENERATION = 2

    /**
     * Reads the one word an instance's visibility file may contain.
     *
     * Unrecognised text is `null` rather than an error: this file is meant to be edited
     * by hand, and a typo should leave the default in place rather than decide anything.
     */
    fun parseOverride(text: String?): Override? = when (val word = text?.trim()?.lowercase()) {
        null -> null
        "hide", "hidden" -> Override.HIDE
        "show", "visible" -> Override.SHOW
        // An automatic hide counts only if this build's mechanism is the one that
        // recorded it. Anything older is a fact about a build that no longer exists.
        AUTO_HIDE_MARKER -> Override.AUTO_HIDE
        else -> if (word.startsWith("auto-hide")) Override.STALE_AUTO_HIDE else null
    }

    /** The word [decide] will read back for an automatic hide made by this build. */
    const val AUTO_HIDE_MARKER = "auto-hide@$IDENTITY_GENERATION"

    fun decide(override: Override?): Decision = when (override) {
        Override.HIDE -> Decision(
            true, Reason.OVERRIDDEN,
            "hidden for this instance by an override",
        )
        Override.SHOW -> Decision(
            false, Reason.OVERRIDDEN,
            "shown for this instance by an override, even if it crashed before",
        )
        Override.AUTO_HIDE -> Decision(
            true, Reason.AUTO_HIDDEN_AFTER_CRASH,
            "this instance died of \"$REFUSED_CALLING_PACKAGE\" once; Play services is " +
                "hidden from it so its SDK takes the path it has for a phone without one",
        )
        Override.STALE_AUTO_HIDE -> Decision(
            false, Reason.STALE_MARK_DISCARDED,
            "this instance was hidden from Play services by an older build; the calling " +
                "package is rewritten now, so the refusal that caused it should not recur",
        )
        null -> Decision(
            false, Reason.VISIBLE_BY_DEFAULT,
            "the device has Play services and this instance has not shown it cannot use it",
        )
    }

    /**
     * Whether this crash is the one that should hide the stack from this instance.
     *
     * The whole chain is searched, not just the outermost throwable: the platform wraps
     * the refusal in whatever was on the stack above it, and the sentence that identifies
     * it can be several `Caused by` deep.
     */
    fun isRefusedCallingPackage(error: Throwable?): Boolean {
        var current = error
        var guard = 0
        while (current != null && guard++ < 16) {
            if (current.message?.contains(REFUSED_CALLING_PACKAGE) == true) return true
            if (current.cause === current) return false
            current = current.cause
        }
        return false
    }
}
