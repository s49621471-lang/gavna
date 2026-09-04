package com.unique.core.vam

import android.content.Context
import android.content.Intent
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.SystemServiceHook
import java.lang.reflect.Method

/**
 * Routes a guest's *own* activity starts onto stubs.
 *
 * Launching the first activity of an instance is UNIQUE's job and goes through
 * [VirtualLaunchIntent]. Every activity the guest starts after that is the guest's own
 * call, and it names a component of a package the system has never installed:
 *
 * ```
 * ActivityTaskManager: Unable to find app for caller … / ActivityNotFoundException
 * ```
 *
 * So the same wrapping applied on the way in is applied here on the way out, and the
 * inbound rewrite in [LaunchInterceptor] unwraps it exactly as it does for the first
 * launch. No second mechanism, no second contract.
 *
 * ## Why a second hook and not the `activity` one
 *
 * `Activity.startActivity` has gone to **`IActivityTaskManager`**, not
 * `IActivityManager`, since Android 10 — `Instrumentation.execStartActivity` calls
 * `ActivityTaskManager.getService().startActivity(…)`. `IActivityManager` still declares
 * a `startActivity`, so a shim placed there binds cleanly and is never called: the same
 * shape of failure as the `bindServiceInstance` rename (§6.2.1). Both interfaces are
 * hooked, and both report the concrete methods they matched.
 */
object VirtualActivityTaskManagerHook {

    @Volatile private var installedFor: String? = null

    val boundPackage: String? get() = installedFor

    /**
     * Every method that starts an activity from an `Intent`.
     *
     * Matched structurally for the reason above. `startActivities` takes an `Intent[]`
     * rather than an `Intent`, so both element and array rules are declared and whichever
     * resolves against the real signature is the one that applies.
     */
    internal fun startsActivity(method: Method): Boolean {
        if (!method.name.startsWith("startActivit")) return false
        return method.parameterTypes.any {
            it == Intent::class.java || it == Array<Intent>::class.java
        }
    }

    @Synchronized
    fun install(virtualPackage: String, hostContext: Context): Boolean {
        if (installedFor == virtualPackage) return true

        val hostPackage = hostContext.packageName
        val target = SystemServiceHook.TARGETS.firstOrNull { it.serviceName == "activity_task" }
            ?: run {
                Diagnostics.error(
                    DiagChannel.LAUNCH, "ATM_HOOK_FAILED",
                    mapOf("package" to virtualPackage, "reason" to "no activity_task target"),
                )
                return false
            }
        val report = SystemServiceHook.install(target, shims(virtualPackage, hostPackage))
        if (!report.installed) {
            Diagnostics.error(
                DiagChannel.LAUNCH, "ATM_HOOK_FAILED",
                mapOf("package" to virtualPackage, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        installedFor = virtualPackage
        Diagnostics.info(
            DiagChannel.LAUNCH, "ATM_HOOK_INSTALLED",
            mapOf(
                "package" to virtualPackage,
                "host" to hostPackage,
                "matched" to (report.bind?.describeMatches()?.take(400) ?: "-"),
            ),
        )
        return true
    }

    private fun shims(virtualPackage: String, hostPackage: String): List<MethodShim> = listOf(
        shim("activityStart") {
            matchMethods { method -> startsActivity(method) }
            // The caller's own package travels outward on these calls and is checked
            // against the real uid, exactly as on IActivityManager.
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteAll<Intent> { intent -> routeActivity(hostPackage, intent) }
            rewriteAll<Array<Intent>> { intents ->
                Array(intents.size) { routeActivity(hostPackage, intents[it]) }
            }
        },
    )

    /**
     * Rewrites an activity intent onto a stub, or returns it unchanged.
     *
     * Unchanged is right for anything that is not a virtual activity: UNIQUE's own
     * components share this process, and a guest may legitimately start a *host* activity
     * — a share sheet, a browser — which must reach the real one.
     */
    internal fun routeActivity(hostPackage: String, intent: Intent): Intent {
        val ready = AppBootstrap.current ?: return intent
        val component = intent.component
        if (component == null) {
            // An implicit start. Resolving it against the guest's own intent filters is
            // real work (§6.5) and starting the wrong activity would be far worse than a
            // start that visibly does nothing, so it goes to the platform untouched and
            // is reported.
            Diagnostics.event(
                DiagChannel.LAUNCH, DiagLevel.DEBUG, "ACTIVITY_INTENT_IMPLICIT",
                mapOf(
                    "action" to (intent.action ?: "-"),
                    "package" to ready.params.packageName,
                ),
            )
            return intent
        }
        if (component.packageName != ready.params.packageName) return intent

        val entry = resolveTarget(ready, component.className) ?: run {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "ACTIVITY_NOT_DECLARED",
                mapOf("activity" to component.className, "package" to ready.params.packageName),
            )
            return intent
        }

        val stubParams = ready.params.copy(
            targetComponent = entry.className,
            kind = VirtualComponentKind.ACTIVITY,
        )
        val stubIntent = Intent(intent).apply {
            setPackage(null)
            stubParams.writeTo(this)
            VirtualLaunchIntent.stampIdentity(this, guest = intent, params = stubParams)
        }
        stubIntent.component = android.content.ComponentName(
            hostPackage,
            StubRouter.stubActivity(ready.params.slot, entry.launchMode.coerceIn(0, 3), 0),
        )
        Diagnostics.event(
            DiagChannel.LAUNCH, DiagLevel.DEBUG, "ACTIVITY_INTENT_ROUTED",
            mapOf(
                "activity" to entry.className,
                "stub" to (stubIntent.component?.className ?: "-"),
                "launchMode" to entry.launchMode.toString(),
            ),
        )
        return stubIntent
    }

    /** An activity or the alias's target activity, as the platform resolves it. */
    private fun resolveTarget(ready: AppBootstrap.Result.Ready, className: String): ComponentEntry? {
        val direct = ready.manifest.components.firstOrNull {
            it.kind == ComponentKind.ACTIVITY && it.className == className
        }
        if (direct != null) return direct
        // <activity-alias name="X" targetActivity="Y"> is started as X and instantiated
        // as Y; the stub has to carry the class the platform will actually construct.
        val alias = ready.manifest.components.firstOrNull {
            it.kind == ComponentKind.ACTIVITY_ALIAS && it.className == className
        } ?: return null
        val targetName = alias.targetActivity ?: return null
        return ready.manifest.components.firstOrNull {
            it.kind == ComponentKind.ACTIVITY && it.className == targetName
        }
    }
}
