package com.unique.core.common.google

/**
 * Whether a guest should be told that Google Play services is on this device.
 *
 * ## Why this is a decision and not a constant
 *
 * It was a constant — hidden, always — and that constant is the reason a user reported
 * *"UNIQUE asks me to install Google services, and they are already installed."* Every
 * guest, on a phone with a full Google stack, was answered:
 *
 * ```
 * W GooglePlayServicesUtil: com.axlebolt.standoff2 requires Google Play services,
 *     but they are missing.
 * E GooglePlayServicesUtil: GooglePlayServices not available due to error 1
 * ```
 *
 * That is a lie about the device, and it costs more than a message. `DynamiteModule`
 * loads Google's own code from the Play services APK through a provider; `AdvertisingIdClient`
 * binds a service that does *not* check the caller's package; `emoji2` looks the package
 * up and disables itself when it is absent. None of those need a virtual app's identity
 * to be accepted, and all of them were switched off by the hiding.
 *
 * ## What the hiding was actually protecting against, exactly
 *
 * One call: `GmsClient.getRemoteService`. It sends `context.getPackageName()` to
 * `com.google.android.gms`, which resolves the *calling uid* to UNIQUE's packages, does
 * not find the guest among them, and refuses:
 *
 * ```
 * SecurityException: Unknown calling package name 'com.gordey.standarling'.
 * ```
 *
 * UNIQUE cannot prevent that — what Play services sees for UNIQUE's uid is decided inside
 * Play services' own process, by the real `PackageManager`. What it *can* do is tell which
 * apps survive it, and two phone logs say so precisely. The same refusal, two apps:
 *
 * ```
 * (run 5, fatal)  at com.google.android.gms.common.internal.c.getRemoteService
 *                   (play-services-basement@@17.4.0:25)
 *                 at …c$g.handleMessage · Looper.loop        <- the app's MAIN looper
 *                 FATAL EXCEPTION: main
 *
 * (run 7, survived) at com.google.android.gms.common.internal.e.getRemoteService
 *                 at com.google.android.gms.common.api.internal.zabm.run
 *                 E GoogleApiManager: Failed to get service from broker.
 * ```
 *
 * The second app kept running. Play services' own client library learned to catch this:
 * from 18.x, `zabm.run` wraps `getRemoteService`, logs *"Failed to get service from
 * broker"* and reports a `ConnectionResult` — which is the same path the SDK takes on a
 * phone with no Google stack at all. Before that, the exception went to the caller's
 * looper and killed the app.
 *
 * So the question "should this guest see Play services" has an answer that depends on the
 * *guest*, and the guest states it in its own manifest.
 *
 * ## The number the manifest states
 *
 * Every app that links the Play services client library must declare:
 *
 * ```xml
 * <meta-data android:name="com.google.android.gms.version"
 *            android:value="@integer/google_play_services_version" />
 * ```
 *
 * The library defines that integer and its value is the library's own release: 12451000
 * through the 15.x–17.x line, and a nine-digit `2xxxxxxxx` from 18.0.0 onward — which is
 * exactly the boundary at which the refusal became survivable. An app that declares no
 * such meta-data does not link the library at all and has nothing that could crash.
 *
 * Pure JVM, so the rule is unit-tested against the versions the logs actually carried
 * rather than against a device that happens to have one of them.
 */
object GoogleStackVisibility {

    /**
     * The first `google_play_services_version` whose client library catches a refused
     * broker instead of letting it reach the caller's looper.
     *
     * Play services 18.0.0 is `203390000`; everything before it is eight digits. The
     * threshold is written as the round nine-digit boundary rather than that exact
     * release because the change is a property of the whole 2xxxxxxxx line, and picking
     * a precise release would claim a sharper measurement than two logs support.
     */
    const val FIRST_VERSION_THAT_SURVIVES_A_REFUSED_BROKER = 200_000_000

    /**
     * Resource ids start at `0x7f000000`. A `com.google.android.gms.version` in that range
     * is a *reference the guest's resources have not resolved yet*, not a version — and it
     * is a large number, so reading it as one would call every such app modern.
     */
    private const val FIRST_RESOURCE_ID = 0x7f000000

    enum class Reason {
        /** The guest does not link the Play services client library. */
        NO_GMS_SDK,

        /** Its client library catches a refused broker and degrades. */
        SDK_HANDLES_REFUSAL,

        /** Its client library lets the refusal reach the caller's looper, and dies. */
        SDK_TOO_OLD,

        /** The declared version is a resource reference nothing has resolved yet. */
        VERSION_UNRESOLVED,

        /** The user or the compatibility database said so for this instance. */
        OVERRIDDEN,
    }

    data class Decision(
        val hidden: Boolean,
        val reason: Reason,
        val declaredVersion: Int?,
        val detail: String,
    ) {
        fun toMap(): Map<String, String> = mapOf(
            "hidden" to hidden.toString(),
            "reason" to reason.name,
            "gmsVersion" to (declaredVersion?.toString() ?: "-"),
            "detail" to detail,
        )
    }

    /**
     * @param declaredVersion the resolved value of the guest's own
     *   `com.google.android.gms.version`, or null when it declares none.
     * @param override `true` to force hiding, `false` to force showing, null to decide.
     *   The escape hatch for an app whose behaviour contradicts the rule; it exists so a
     *   wrong guess costs a line in a file rather than a build.
     */
    fun decide(declaredVersion: Int?, override: Boolean? = null): Decision {
        if (override != null) {
            return Decision(
                override, Reason.OVERRIDDEN, declaredVersion,
                if (override) "hidden for this instance by an override"
                else "shown for this instance by an override",
            )
        }
        if (declaredVersion == null) {
            return Decision(
                false, Reason.NO_GMS_SDK, null,
                "the guest declares no com.google.android.gms.version, so it does not " +
                    "link the client library and has nothing a refused broker could kill",
            )
        }
        if (declaredVersion >= FIRST_RESOURCE_ID) {
            return Decision(
                true, Reason.VERSION_UNRESOLVED, declaredVersion,
                "com.google.android.gms.version is still a resource reference; hidden " +
                    "until it resolves, because an unresolved id reads as a large version",
            )
        }
        if (declaredVersion >= FIRST_VERSION_THAT_SURVIVES_A_REFUSED_BROKER) {
            return Decision(
                false, Reason.SDK_HANDLES_REFUSAL, declaredVersion,
                "Play services client 18 or newer: a refused broker is caught, logged as " +
                    "\"Failed to get service from broker\" and reported as a ConnectionResult",
            )
        }
        return Decision(
            true, Reason.SDK_TOO_OLD, declaredVersion,
            "Play services client older than 18: a refused broker reaches the caller's " +
                "looper and kills the app, so the stack is hidden and the SDK takes its " +
                "own no-Google-services path",
        )
    }
}
