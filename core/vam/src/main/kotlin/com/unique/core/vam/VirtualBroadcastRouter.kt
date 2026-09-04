package com.unique.core.vam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Delivers broadcasts to guests whose processes are not running.
 *
 * A guest's manifest receiver exists only as a dynamic registration inside its own
 * process (§6.3), so a dead guest received nothing at all — and waking on a broadcast is
 * most of what a manifest receiver is *for*. This closes that, and it does so without a
 * separate `:server`: the registration lives in UNIQUE's own long-lived process, which is
 * the thing `:server` was shorthand for.
 *
 * The delivery path for a cold guest:
 *
 *  1. This router holds a dynamic registration for every action any imported package
 *     declares, in UNIQUE's main process.
 *  2. A broadcast arrives. The router finds which instances declared that action.
 *  3. For each, it starts that instance's stub *service* carrying the intent — which is
 *     what brings the virtual process up, because a process cannot be started by wishing.
 *  4. In the virtual process the graft happens as usual and the guest's own receiver runs
 *     with the guest's own `Context`.
 *
 * ## What this still cannot do
 *
 * UNIQUE's main process must be alive to hold the registration. It is whenever the user
 * has UNIQUE open, and a `BOOT_COMPLETED` receiver in the host manifest brings it back
 * after a restart — but a broadcast arriving while the whole app is dead and unstarted is
 * missed. Closing that needs the registrations to be *static* in the host manifest, which
 * means knowing the actions at build time rather than at import time.
 */
object VirtualBroadcastRouter {

    /** One instance's interest in one action. */
    private data class Route(
        val vuid: Int,
        val packageName: String,
        val receiverClass: String,
        val processName: String,
        val versionCode: Long,
    )

    private val routes = LinkedHashMap<String, MutableList<Route>>()
    private val registered = HashMap<String, BroadcastReceiver>()

    @Volatile private var hostContext: Context? = null

    val registeredActions: Set<String> get() = synchronized(this) { registered.keys.toSet() }
    val routeCount: Int get() = synchronized(this) { routes.values.sumOf { it.size } }

    /**
     * Registers the actions one instance's manifest declares.
     *
     * Idempotent per (action, instance): calling this again for the same instance replaces
     * its routes rather than adding duplicates, so a re-import does not deliver twice.
     */
    @Synchronized
    fun register(
        context: Context,
        vuid: Int,
        manifest: ApkManifest,
    ) {
        hostContext = context.applicationContext ?: context
        val packageName = manifest.packageName
        routes.values.forEach { list -> list.removeAll { it.vuid == vuid } }

        var added = 0
        for (entry in manifest.components) {
            if (entry.kind != ComponentKind.RECEIVER || !entry.enabled) continue
            for (filter in entry.intentFilters) {
                for (action in filter.actions) {
                    routes.getOrPut(action) { ArrayList() } += Route(
                        vuid, packageName, entry.className, entry.processName,
                        manifest.versionCode,
                    )
                    added++
                    ensureRegistered(action)
                }
            }
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "BROADCAST_ROUTES_REGISTERED",
            mapOf(
                "package" to packageName,
                "vuid" to vuid.toString(),
                "routes" to added.toString(),
                "actions" to registered.keys.joinToString(",").take(300),
            ),
        )
    }

    @Synchronized
    fun unregister(context: Context, vuid: Int) {
        routes.values.forEach { list -> list.removeAll { it.vuid == vuid } }
        val empty = routes.filterValues { it.isEmpty() }.keys
        for (action in empty) {
            routes.remove(action)
            registered.remove(action)?.let { runCatching { context.unregisterReceiver(it) } }
        }
    }

    @Synchronized
    fun reset(context: Context) {
        registered.values.forEach { runCatching { context.unregisterReceiver(it) } }
        registered.clear()
        routes.clear()
    }

    private fun ensureRegistered(action: String) {
        if (action in registered) return
        val context = hostContext ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = dispatch(ctx, intent)
        }
        val filter = IntentFilter(action)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // NOT_EXPORTED, and the caller must scope its intent. UNIQUE holds this
                // registration on behalf of guests; letting any app on the device wake
                // every virtual app would be a worse trade than the one §6.3 already
                // documents.
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (ok) {
            registered[action] = receiver
        } else {
            Diagnostics.warn(
                DiagChannel.PROCESS, "BROADCAST_ROUTE_REFUSED",
                mapOf("action" to action),
            )
        }
    }

    /**
     * Hands one broadcast to every instance that declared its action.
     *
     * The starter is injected rather than called directly so this object stays free of the
     * launcher, which lives a layer up and would make the dependency circular.
     */
    @Synchronized
    private fun dispatch(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val targets = routes[action].orEmpty()
        if (targets.isEmpty()) return

        for (route in targets) {
            val target = ColdBroadcastTarget(
                vuid = route.vuid,
                packageName = route.packageName,
                versionCode = route.versionCode,
                receiverClass = route.receiverClass,
                processName = route.processName,
            )
            val started = runCatching {
                starter?.invoke(context, target, intent) ?: false
            }.getOrElse {
                Diagnostics.error(
                    DiagChannel.PROCESS, "BROADCAST_COLD_START_FAILED",
                    mapOf(
                        "action" to action,
                        "package" to route.packageName,
                        "error" to it.toString(),
                    ),
                )
                false
            }
            Diagnostics.info(
                DiagChannel.PROCESS, "BROADCAST_ROUTED_COLD",
                mapOf(
                    "action" to action,
                    "package" to route.packageName,
                    "vuid" to route.vuid.toString(),
                    "receiver" to route.receiverClass,
                    "started" to started.toString(),
                ),
            )
        }
    }

    /** Set by the engine: starts a virtual process and hands it the intent. */
    @Volatile
    var starter: ((Context, ColdBroadcastTarget, Intent) -> Boolean)? = null
}

/**
 * Who a cold broadcast is for.
 *
 * Carries no slot. A slot is not a property of an instance - it is a lease on one of the
 * host's `:vappN` processes, handed out by [VirtualLauncher] at the moment something
 * actually starts. Baking one into a registration made at import time would name a
 * process that, by the time a broadcast arrives, may well belong to a different app.
 */
data class ColdBroadcastTarget(
    val vuid: Int,
    val packageName: String,
    val versionCode: Long,
    val receiverClass: String,
    val processName: String,
)
