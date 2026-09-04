package com.unique.core.vam

import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.SystemServiceHook
import java.lang.reflect.Method

/**
 * Gives app-op calls the host's package name.
 *
 * `AppOpsManager.checkPackage(uid, packageName)` throws when the name does not belong to
 * the uid, and the framework calls it on the way into a great many APIs — camera,
 * microphone, location, clipboard, notifications. A guest reaches it with its *virtual*
 * package name, which no uid on the device owns:
 *
 * ```
 * SecurityException: Specified package com.unique.probe under uid 10109 but it is really <host>
 * ```
 *
 * ## The rule, and why it is broader here than on ActivityManager
 *
 * On `IActivityManager` the rewrite is limited to calls that carry an
 * `IApplicationThread`, because `forceStopPackage` takes a package name as *data* and
 * rewriting it would make a guest stop UNIQUE. `IAppOpsService` has no such method
 * reachable by an ordinary app: everything that acts on a *different* package —
 * `setMode`, `setUidMode`, `resetAllModes`, `setAudioRestriction` — is gated behind
 * `MANAGE_APP_OPS_MODES`, which UNIQUE does not hold and must never hold.
 *
 * So the rule is the general one: **the virtual package name is not a name the platform
 * knows, so wherever it appears in an outbound call it can only mean "me".** The
 * privileged setters are excluded anyway, as defence in depth — if UNIQUE ever did hold
 * that permission, a guest must not reach through it.
 *
 * ## What this does not do
 *
 * Ops are attributed to UNIQUE, because the uid is UNIQUE's. That is correct and
 * permanent: the op record is a kernel-level fact about which process touched the camera.
 * Per-instance *denial* is handled a layer up, at the permission check (§6.6), which is
 * what apps actually consult.
 */
object VirtualAppOpsHook {

    /**
     * Methods that change another package's op state. Privileged, and never rewritten.
     *
     * Listed by name because that is what they are: a short, stable set of setters, not a
     * family that renames itself. A name that disappears costs nothing; a name that
     * appears and is missed is caught by UNIQUE not holding `MANAGE_APP_OPS_MODES`.
     */
    private val PRIVILEGED_SETTERS = setOf(
        "setMode",
        "setUidMode",
        "resetAllModes",
        "setAudioRestriction",
        "setCameraAudioRestriction",
        "setUserRestriction",
        "setUserRestrictions",
        "setUserRestrictionForUser",
        "addHistoricalOps",
        "resetHistoryParameters",
        "clearHistory",
    )

    @Volatile private var installedFor: String? = null

    val boundPackage: String? get() = installedFor

    internal fun carriesOwnPackage(method: Method): Boolean {
        if (method.name in PRIVILEGED_SETTERS) return false
        return method.parameterTypes.any { it == String::class.java }
    }

    @Synchronized
    fun install(virtualPackage: String, hostPackage: String): Boolean {
        if (installedFor == virtualPackage) return true
        val target = SystemServiceHook.TARGETS.firstOrNull { it.serviceName == "appops" }
            ?: return false
        val report = SystemServiceHook.install(target, shims(virtualPackage, hostPackage))
        if (!report.installed) {
            Diagnostics.warn(
                DiagChannel.HOOK, "APPOPS_HOOK_FAILED",
                mapOf("package" to virtualPackage, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        installedFor = virtualPackage
        Diagnostics.info(
            DiagChannel.HOOK, "APPOPS_HOOKED",
            mapOf(
                "package" to virtualPackage,
                "host" to hostPackage,
                "matched" to (report.bind?.describeMatches()?.take(400) ?: "-"),
            ),
        )
        return true
    }

    private fun shims(virtualPackage: String, hostPackage: String): List<MethodShim> = listOf(
        shim("appOpIdentity") {
            matchMethods { method -> carriesOwnPackage(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
        },
    )
}
