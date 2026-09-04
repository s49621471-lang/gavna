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
 * `IAlarmManager` and `IClipboard` both take a calling package that `system_server`
 * checks against the uid, and neither carries a component to route: an alarm's
 * `PendingIntent` was already pointed at a stub when the guest built it (§6.4.1), and a
 * clip is data. So the whole interception is the identity rewrite, and grouping them is
 * honest rather than lazy — a separate object per service would be three files that say
 * the same thing.
 *
 * What each service still gets wrong is documented on its installer below, because
 * "the package name is right" is not the same as "the feature behaves as the app expects".
 */
object VirtualIdentityHooks {

    private val installed = HashSet<String>()

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
        val report = SystemServiceHook.install(target, shims(virtualPackage, hostPackage))
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
