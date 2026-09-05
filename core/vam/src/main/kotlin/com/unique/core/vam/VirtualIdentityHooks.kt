package com.unique.core.vam

import android.app.AlarmManager
import android.content.Context
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.SystemServiceHook

/**
 * System services a guest reaches with its own package name and nothing else.
 *
 * Each takes a calling package that `system_server` checks against the uid, and none
 * carries a component to route: an alarm's `PendingIntent` was already pointed at a stub
 * when the guest built it (§6.4.1), a clip is data, a network query is a question. So the
 * whole interception is the identity rewrite, and grouping them is honest rather than
 * lazy — a file per service would be fifteen files that say the same thing.
 *
 * [CALLER_PACKAGE_SERVICES] is the list, with the reason for each entry. What a service
 * still gets wrong after the rewrite is documented at [guards], because "the package name
 * is right" is not the same as "the feature behaves as the app expects".
 */
object VirtualIdentityHooks {

    private val installed = HashSet<String>()

    /**
     * Every service whose interception is exactly the caller-package rewrite, and why.
     *
     * ## The rule these all share
     *
     * `system_server` validates a caller-supplied package name against the calling uid —
     * `AppOpsService.checkPackage`, or an explicit "only the owner may ask" branch. UNIQUE's
     * uid is the host's, so a guest calling one of these with its own name is refused. The
     * refusal is a `SecurityException` that names the *guest's* package, which is why it
     * reads as the app being broken rather than as UNIQUE missing a hook.
     *
     * ## Why the blanket rewrite is safe on these interfaces
     *
     * The virtual package is not installed, so the platform has never heard of it. Wherever
     * that exact string appears in an outbound call it can only mean "me" — there is no
     * other app it could be referring to. What makes the rewrite *unsafe* elsewhere is a
     * method that takes another app's package as data and acts on it: `forceStopPackage` on
     * `IActivityManager` would stop UNIQUE itself. None of the interfaces below has one,
     * which is why `activity`, `package` and `appops` get their own targeted hooks instead.
     *
     * ## How this list was arrived at
     *
     * The first three are from a physical-device run and are not speculative:
     *
     * ```
     * FATAL EXCEPTION: main   Process: com.openai.chatgpt
     *   java.lang.SecurityException: Only system may: get application restrictions
     *       for other user/app com.openai.chatgpt
     *     at android.content.RestrictionsManager.getApplicationRestrictions
     *     at com.openai.chatgpt.MainActivity.onCreate
     *
     * FATAL EXCEPTION: nfz Dispatcher
     *   java.lang.SecurityException: getApplicationLocales: Neither user 10300 nor
     *       current process has android.permission.READ_APP_SPECIFIC_LOCALES.
     *     at android.app.LocaleManager.getApplicationLocales
     *
     * java.lang.SecurityException: Package com.openai.chatgpt does not belong to 10300
     *     at android.net.ConnectivityManager.getNetworkCapabilities
     * ```
     *
     * Two of those killed the app outright on its first screen. The rest of the list is the
     * set of services an ordinary app touches while starting that validate the same way —
     * each named with what a guest loses without it, so an entry can be argued with rather
     * than trusted. A service missing on a device is skipped with a diagnostic, so listing
     * one that a build does not have costs a log line.
     */
    private val CALLER_PACKAGE_SERVICES: List<Pair<String, String>> = listOf(
        "alarm" to "an alarm's PendingIntent was pointed at a stub when the guest built it",
        "clipboard" to "a clip is data; only the caller's identity is checked",
        "restrictions" to "getApplicationRestrictions crashed a guest in Activity.onCreate",
        "locale" to "getApplicationLocales crashed a guest on a background thread",
        "connectivity" to "getNetworkCapabilities is refused, so the guest sees no network",
        "power" to "acquireWakeLock carries the caller's package; without it, no wake locks",
        "wifi" to "connection and scan queries carry the caller's package",
        "location" to "every location request carries the caller's package",
        "audio" to "focus, mode and volume changes carry the caller's package",
        "vibrator_manager" to "vibrate carries the caller's package for the appop",
        "usagestats" to "app-standby and usage queries carry the caller's package",
        "netstats" to "data-usage queries carry the caller's package",
        "content" to "notifyChange and sync registration carry the caller's package",
        "shortcut" to "dynamic shortcuts are keyed by the caller's package",

        // A second device run and a survey of 63 real apps (tools/apk-survey) added these.
        // `mount` is the one with a body on the floor:
        //
        //   SecurityException: callingPackage does not match UID
        //     at IStorageManager$Stub$Proxy.getVolumeList
        //     at Environment.isExternalStorageManager    -> clear.una died on its first frame
        "mount" to "getVolumeList is refused, and Environment.isExternalStorageManager " +
            "goes through it, so a guest asking about storage at all is killed",
        "account" to "getAccountsAsUser carries the caller's package; without it a guest " +
            "asking about accounts is refused, which killed the Play Store on launch",
        "phone" to "34 of 63 surveyed apps call TelephonyManager, and its queries carry " +
            "the caller's package",
        "download" to "enqueue and query carry the caller's package",
        "device_policy" to "policy queries carry the caller's package",
        "media.camera" to "connectDevice carries the client package and is checked against the uid",
        "telecom" to "every call-capability query carries the caller's package",
        "media_router" to "registerClientAsUser carries the caller's package",
        "media_session" to "createSession carries the caller's package, so a guest that " +
            "publishes playback controls is refused without it",
    )

    /**
     * Services deliberately left alone, and why — so the absence is a decision, not a gap.
     *
     * `search`: `ISearchManager` on API 35 declares `getSearchableInfo`,
     * `getGlobalSearchActivity`, `launchAssist` and three siblings, and **not one of them
     * takes a String**. The caller-package shim therefore bound to nothing, and said so
     * nine times in one device run:
     *
     * ```
     * HOOK_MATCHED_NOTHING service=search interface=android.app.ISearchManager
     *   methods=asBinder,getGlobalSearchActivities,getGlobalSearchActivity,
     *           getSearchableInfo,getSearchablesInGlobalSearch,getWebSearchActivity,launchAssist
     * ```
     *
     * It carries no caller identity, so there is nothing here to correct. Removed rather
     * than left binding to nothing, because a hook that matches nothing looks exactly like
     * one that works — which is this project's own rule 8.
     *
     * `window`: 60 of 63 surveyed apps reference `WindowManager`, and almost none of that
     * reaches `IWindowManager` with a package name — `addView` and `getDefaultDisplay` go
     * through `IWindowSession` and the display manager instead. Rewriting strings on the
     * interface that builds windows, to fix calls the survey cannot show are being made,
     * is risk without evidence. It stays in `TARGETS` and uninstalled until a log shows a
     * refusal that names it.
     */
    private val NOT_PROXIED_ON_PURPOSE = setOf("search", "window")

    /**
     * Installs the caller-package rewrite on every service in [CALLER_PACKAGE_SERVICES].
     *
     * Reports the outcome once rather than per service: sixteen lines saying "installed"
     * bury the one that says a service was missing. The names that did not bind are listed
     * explicitly, because a hook that binds to nothing looks exactly like one that works.
     */
    fun installAll(virtualPackage: String, hostPackage: String) {
        // A name in both lists is a contradiction: one of the two was edited without the
        // other, and the deliberate-omission note has quietly become false. Cheap to check
        // and it keeps NOT_PROXIED_ON_PURPOSE a statement rather than a comment.
        val contradictions = CALLER_PACKAGE_SERVICES.map { it.first }
            .filter { it in NOT_PROXIED_ON_PURPOSE }
        if (contradictions.isNotEmpty()) {
            Diagnostics.warn(
                DiagChannel.HOOK, "IDENTITY_HOOK_LIST_CONTRADICTS",
                mapOf(
                    "services" to contradictions.joinToString(","),
                    "detail" to "listed both as proxied and as deliberately not proxied",
                ),
            )
        }

        val ok = ArrayList<String>()
        val skipped = ArrayList<String>()
        for ((service, cost) in CALLER_PACKAGE_SERVICES) {
            val bound = runCatching { install(service, virtualPackage, hostPackage) }
                .getOrDefault(false)
            if (bound) {
                ok += service
            } else {
                skipped += service
                // The reason is carried in the table precisely so it can be said here.
                // "skipped=wifi" leaves the reader to work out what a guest has lost;
                // this says it.
                Diagnostics.warn(
                    DiagChannel.HOOK, "IDENTITY_HOOK_UNAVAILABLE",
                    mapOf("service" to service, "consequence" to cost),
                )
            }
        }
        Diagnostics.info(
            DiagChannel.HOOK, "IDENTITY_HOOKS_INSTALLED",
            mapOf(
                "package" to virtualPackage,
                "installed" to ok.joinToString(","),
                "skipped" to skipped.joinToString(","),
            ),
        )
    }

    /**
     * Installs the plain package rewrite on [serviceName].
     *
     * Every argument equal to the virtual package becomes the host's. Safe on these
     * interfaces for the reason set out in §6.6.6: the virtual package name is not a name
     * the platform knows, so wherever it appears in an outbound call it can only mean
     * "me". Neither interface has a `forceStopPackage`-shaped method that takes another
     * app's package as data.
     */
    @Synchronized
    fun install(serviceName: String, virtualPackage: String, hostPackage: String): Boolean {
        val key = "$serviceName/$virtualPackage"
        if (key in installed) return true
        val target = SystemServiceHook.TARGETS.firstOrNull { it.serviceName == serviceName }
            ?: return false
        val report = SystemServiceHook.install(
            target,
            guards(serviceName) + shims(virtualPackage, hostPackage),
        )
        if (!report.installed) {
            Diagnostics.warn(
                DiagChannel.HOOK, "IDENTITY_HOOK_FAILED",
                mapOf("service" to serviceName, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        installed += key
        Diagnostics.info(
            DiagChannel.HOOK, "IDENTITY_HOOK_INSTALLED",
            mapOf(
                "service" to serviceName,
                "package" to virtualPackage,
                "matched" to (report.bind?.describeMatches()?.take(300) ?: "-"),
            ),
        )
        return true
    }

    private fun shims(virtualPackage: String, hostPackage: String): List<MethodShim> = listOf(
        shim("callerPackage") {
            matchMethods { method -> method.parameterTypes.any { it == String::class.java } }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
        },
    )

    /**
     * Calls that must not go through even with the caller's name corrected.
     *
     * The rewrite turns "the guest is asking about itself" into "UNIQUE is asking about
     * itself", which is right for a read and wrong for a write: a guest calling
     * `setApplicationLocales` would then be setting *UNIQUE's* app language, and the user
     * would find UNIQUE's own interface in a language they never chose. There is no
     * correct target for that call — a per-instance app locale is a platform record keyed
     * by an installed package, and the guest is not one — so it is refused and reported
     * rather than aimed at the nearest package that would accept it.
     *
     * Registered before the rewrite so it binds first; the guard replaces the call
     * entirely, so nothing reaches the platform.
     *
     * Reads are untouched: `getApplicationLocales` returns UNIQUE's, which is the device
     * default, which is what the guest would have seen before it ever set one.
     */
    private fun guards(serviceName: String): List<MethodShim> = when (serviceName) {
        "locale" -> listOf(
            shim("setApplicationLocales") {
                replaceWith {
                    Diagnostics.warn(
                        DiagChannel.HOOK, "APP_LOCALE_SET_UNSUPPORTED",
                        mapOf(
                            "detail" to "a guest asked to set its own app locale; UNIQUE " +
                                "cannot store one for a package the platform has not " +
                                "installed, and applying it would change UNIQUE's own",
                        ),
                    )
                    null
                }
            },
        )
        else -> emptyList()
    }

    /**
     * Reports what a guest's alarms can and cannot do on this device.
     *
     * Called once at bootstrap rather than per alarm: the answer is a property of the
     * host's permission state, and an app that schedules fifty alarms should not produce
     * fifty identical lines.
     *
     * No downgrade happens. When the user has revoked "Alarms & reminders" the guest gets
     * the same `SecurityException` it would get on a device where its own permission was
     * revoked, which is the faithful answer — an alarm silently downgraded to inexact
     * fires up to an hour late and looks like the app being broken.
     */
    fun reportAlarmCapability(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val exact = runCatching { manager?.canScheduleExactAlarms() ?: false }.getOrDefault(false)
        if (exact) {
            Diagnostics.info(DiagChannel.PROCESS, "ALARM_EXACT_AVAILABLE", emptyMap())
        } else {
            Diagnostics.warn(
                DiagChannel.PROCESS, "ALARM_EXACT_UNAVAILABLE",
                mapOf(
                    "detail" to "UNIQUE does not hold SCHEDULE_EXACT_ALARM; a guest asking " +
                        "for an exact alarm will get the platform's SecurityException",
                ),
            )
        }
    }
}
