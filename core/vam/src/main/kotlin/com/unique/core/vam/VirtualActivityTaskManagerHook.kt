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
    internal fun routeActivity(hostPackage: String, rawIntent: Intent): Intent {
        val ready = AppBootstrap.current ?: return rawIntent
        // Before anything else: a `content://` URI belonging to this guest is unusable to
        // anyone outside UNIQUE, and this is the last point at which it can be made usable.
        // See VirtualUriGrants.
        val intent = VirtualUriGrants.rewriteOutgoing(hostPackage, rawIntent, ready)
        val component = intent.component
        if (component == null) return routeImplicit(hostPackage, intent, ready)
        if (component.packageName != ready.params.packageName) return intent

        val entry = resolveTarget(ready, component.className) ?: run {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "ACTIVITY_NOT_DECLARED",
                mapOf("activity" to component.className, "package" to ready.params.packageName),
            )
            return intent
        }

        val stubIntent = routeExplicit(hostPackage, intent, ready, entry)
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

    /** Builds the stub intent for a guest activity that has already been chosen. */
    private fun routeExplicit(
        hostPackage: String,
        intent: Intent,
        ready: AppBootstrap.Result.Ready,
        entry: ComponentEntry,
    ): Intent {
        val stubParams = ready.params.copy(
            targetComponent = entry.className,
            kind = VirtualComponentKind.ACTIVITY,
        )
        return Intent(intent).apply {
            setPackage(null)
            stubParams.writeTo(this)
            VirtualLaunchIntent.stampIdentity(this, guest = intent, params = stubParams)
            component = android.content.ComponentName(
                hostPackage,
                StubRouter.stubActivity(ready.params.slot, entry.launchMode.coerceIn(0, 3), 0),
            )
        }
    }

    /**
     * Resolves an implicit start against the guest's own filters, or lets it go.
     *
     * See [VirtualIntentResolver] for which side wins and why. Every outcome is recorded
     * with the rule that produced it, because "it opened the wrong thing" is a question
     * that needs an answer and not a shrug.
     */
    private fun routeImplicit(
        hostPackage: String,
        intent: Intent,
        ready: AppBootstrap.Result.Ready,
    ): Intent {
        val guestPackage = ready.params.packageName
        val matches = VirtualIntentResolver.matchingActivities(ready.manifest, intent)
        if (matches.isEmpty()) {
            // Left to the platform, which is right — an app opening a browser or a share
            // sheet does exactly this on a real device, and trapping it inside the guest
            // would break behaviour that works.
            //
            // But it is the one path by which a guest's intent reaches an *installed* app,
            // with that app's data, so it is reported at INFO with the packages that could
            // answer it. Gemini's shell activity fires an implicit ACTION_VIEW within fifty
            // milliseconds of starting; the host's Google app answers it, and the user sees
            // their real account in what they launched as a fresh instance. That is faithful
            // to the app and confusing to the person, and the only thing that makes it
            // legible afterwards is this line naming where the intent went.
            val handlers = AppBootstrap.hostContext?.let {
                VirtualIntentResolver.hostHandlersFor(it, intent, hostPackage)
            }.orEmpty()
            Diagnostics.info(
                DiagChannel.LAUNCH, "ACTIVITY_IMPLICIT_LEFT_GUEST",
                mapOf(
                    "action" to (intent.action ?: "-"),
                    "data" to (intent.data?.scheme ?: "-"),
                    "package" to guestPackage,
                    "handledByHost" to handlers.joinToString(",").ifEmpty { "nothing" },
                    "detail" to "no activity of the guest matches; the host's own apps " +
                        "answer this intent, with the host's data",
                ),
            )
            return intent
        }

        val scopedToGuest = intent.`package` == guestPackage ||
            intent.selector?.`package` == guestPackage
        if (!scopedToGuest) {
            val context = AppBootstrap.hostContext
            if (context != null &&
                VirtualIntentResolver.hostCanHandle(context, intent, hostPackage)
            ) {
                // An https VIEW belongs in a browser and a SEND belongs in the chooser.
                // Pulling either into the guest would break behaviour that works today.
                Diagnostics.info(
                    DiagChannel.LAUNCH, "ACTIVITY_IMPLICIT_HOST_PREFERRED",
                    mapOf(
                        "action" to (intent.action ?: "-"),
                        "package" to guestPackage,
                        "guestMatches" to matches.size.toString(),
                        "guestBest" to matches.first().entry.className,
                    ),
                )
                return intent
            }
        }

        val best = matches.first()
        val routed = routeExplicit(hostPackage, intent, ready, best.entry)
        Diagnostics.info(
            DiagChannel.LAUNCH, "ACTIVITY_IMPLICIT_ROUTED",
            mapOf(
                "action" to (intent.action ?: "-"),
                "data" to (intent.data?.scheme ?: "-"),
                "activity" to best.entry.className,
                "package" to guestPackage,
                "reason" to if (scopedToGuest) "scoped" else "onlyHandler",
                "candidates" to matches.size.toString(),
            ),
        )
        return routed
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
