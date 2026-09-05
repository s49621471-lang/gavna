package com.unique.core.vam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        // An outstanding delivery to an instance that no longer exists has nowhere to go,
        // and re-trying it would start a process for a guest that has been removed.
        pending.keys.removeAll { it.startsWith("$vuid|") }
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
        pending.clear()
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
            start(
                context,
                ColdBroadcastTarget(
                    vuid = route.vuid,
                    packageName = route.packageName,
                    versionCode = route.versionCode,
                    receiverClass = route.receiverClass,
                    processName = route.processName,
                ),
                intent,
            )
        }
    }

    /** One attempt at waking one instance, recorded so a lost one can be noticed. */
    private fun start(context: Context, target: ColdBroadcastTarget, intent: Intent) {
        val action = intent.action ?: return
        val started = runCatching {
            starter?.invoke(context, target, intent) ?: false
        }.getOrElse {
            Diagnostics.error(
                DiagChannel.PROCESS, "BROADCAST_COLD_START_FAILED",
                mapOf(
                    "action" to action,
                    "package" to target.packageName,
                    "error" to it.toString(),
                ),
            )
            false
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "BROADCAST_ROUTED_COLD",
            mapOf(
                "action" to action,
                "package" to target.packageName,
                "vuid" to target.vuid.toString(),
                "receiver" to target.receiverClass,
                "started" to started.toString(),
            ),
        )
        if (started) track(context, target, intent)
    }

    // ---------------------------------------------------------------------------------
    // Making the delivery survive the process it was sent to
    // ---------------------------------------------------------------------------------

    /** A `:vappN` reporting that a guest's receiver actually ran. */
    const val ROUTER_METHOD_COLD_DELIVERED = "unique.coldDelivered"
    const val KEY_VUID = "unique.vuid"
    const val KEY_RECEIVER = "unique.receiver"
    const val KEY_ACTION = "unique.action"

    /** A cold delivery that has been started and not yet acknowledged. */
    private class Pending(
        val context: Context,
        val target: ColdBroadcastTarget,
        val intent: Intent,
        var attempts: Int,
    )

    private val pending = LinkedHashMap<String, Pending>()

    private fun key(vuid: Int, receiverClass: String, action: String) =
        "$vuid|$receiverClass|$action"

    /**
     * Starting a process is not the same as delivering to one, so the delivery is tracked.
     *
     * A cold wake is the longest thing UNIQUE asks a device to do — fork, load UNIQUE's
     * own code, install the interception layer, graft the guest, then run the receiver —
     * and it happens in a process ActivityManager considers a background service start.
     * On a loaded device that budget is exceeded and the process is killed outright:
     *
     * ```
     * ANR in com.unique:vapp0
     * Killing 12252:com.unique:vapp0 (adj 500): bg anr
     * ```
     *
     * The stub is started `START_NOT_STICKY`, so nothing brought it back and the broadcast
     * was simply lost — silently, which is the worst part. A guest that misses the
     * broadcast it exists to receive has no way to know it happened, and neither did
     * UNIQUE.
     *
     * So each cold start is recorded and re-tried until the guest's receiver acknowledges
     * it. Bounded at [COLD_ATTEMPTS], because a delivery that fails three times is failing
     * for a reason another attempt will not fix, and an unbounded retry against a process
     * that cannot start is a battery drain that looks like a hang.
     */
    @Synchronized
    private fun track(context: Context, target: ColdBroadcastTarget, intent: Intent) {
        val action = intent.action ?: return
        val k = key(target.vuid, target.receiverClass, action)
        val existing = pending[k]
        if (existing != null) {
            existing.attempts++
        } else {
            pending[k] = Pending(context.applicationContext ?: context, target, intent, 1)
        }
        Handler(Looper.getMainLooper()).postDelayed({ retryIfUnacknowledged(k) }, COLD_TIMEOUT_MILLIS)
    }

    @Synchronized
    private fun retryIfUnacknowledged(k: String) {
        val row = pending[k] ?: return   // acknowledged, and removed
        if (row.attempts >= COLD_ATTEMPTS) {
            pending.remove(k)
            Diagnostics.error(
                DiagChannel.PROCESS, "COLD_BROADCAST_GIVEN_UP",
                mapOf(
                    "package" to row.target.packageName,
                    "vuid" to row.target.vuid.toString(),
                    "receiver" to row.target.receiverClass,
                    "action" to (row.intent.action ?: "-"),
                    "attempts" to row.attempts.toString(),
                ),
            )
            return
        }
        Diagnostics.warn(
            DiagChannel.PROCESS, "COLD_BROADCAST_RETRY",
            mapOf(
                "package" to row.target.packageName,
                "vuid" to row.target.vuid.toString(),
                "receiver" to row.target.receiverClass,
                "action" to (row.intent.action ?: "-"),
                "attempt" to (row.attempts + 1).toString(),
            ),
        )
        start(row.context, row.target, row.intent)
    }

    /** Called by a `:vappN` once the guest's own receiver has run. */
    @Synchronized
    fun coldDelivered(extras: Bundle?): Bundle? {
        val vuid = extras?.getInt(KEY_VUID, -1) ?: -1
        val receiver = extras?.getString(KEY_RECEIVER)
        val action = extras?.getString(KEY_ACTION)
        if (vuid < 0 || receiver == null || action == null) return null
        val removed = pending.remove(key(vuid, receiver, action)) != null
        Diagnostics.info(
            DiagChannel.PROCESS, "COLD_BROADCAST_ACKNOWLEDGED",
            mapOf(
                "vuid" to vuid.toString(),
                "receiver" to receiver,
                "action" to action,
                // False is not an error: a live guest receives through the ordinary path
                // and acknowledges anyway, and an acknowledgement racing a retry arrives
                // twice. It is worth seeing which happened.
                "wasPending" to removed.toString(),
            ),
        )
        return Bundle.EMPTY
    }

    /**
     * Long enough for a cold wake on a slow device, short enough to still be a wake.
     *
     * Ninety seconds against a cold graft measured at forty (§17.1). A retry that fires
     * while the first attempt is still working would double the load on the device least
     * able to carry it, which is precisely how the failure this fixes was produced.
     */
    private const val COLD_TIMEOUT_MILLIS = 90_000L
    private const val COLD_ATTEMPTS = 3

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
