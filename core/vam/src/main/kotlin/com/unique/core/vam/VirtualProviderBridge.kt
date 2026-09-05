package com.unique.core.vam

import android.content.ComponentName
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * The caller's half of cross-process content-provider access.
 *
 * Any process — UNIQUE's own, or another guest's — that wants a guest authority it does
 * not itself publish comes through here. Three steps, and each exists because the one
 * before it cannot be skipped:
 *
 *  1. **Resolve.** Ask UNIQUE's router provider which `:vappN` slot will serve
 *     `(vuid, authority)`. Only the main process holds the process pool, so only it can
 *     answer, and only it can lease.
 *  2. **Bind.** `call()` the stub provider of that slot. Acquiring the stub is what starts
 *     the process — a `ContentProvider` in the host manifest is something the platform
 *     will happily bring up — and the call itself makes the process *be* that instance:
 *     it bootstraps the guest and publishes its providers. This is synchronous, which is
 *     the point. Starting a service and polling for readiness would be a race with a
 *     timeout in place of an answer.
 *  3. **Acquire.** Take a client on the stub authority. From then on the caller passes
 *     ordinary `content://<guest authority>/...` URIs: the stub accepts them because
 *     step 2 widened its authority set, and routes each call to the guest's own provider.
 *
 * What the caller ends up holding is a real Binder to a real `ContentProvider` running in
 * the guest's process, so cursors, `openFile` and `call` all work by the same mechanism
 * they would for an installed app. Nothing is re-marshalled by UNIQUE.
 */
object VirtualProviderBridge {

    const val METHOD_BIND = "unique.bindInstance"
    const val KEY_AUTHORITIES = "unique.authorities"
    const val KEY_ERROR = "unique.error"

    /** What [resolve] found: which slot serves an authority, and for which instance. */
    data class Route(
        val slot: Int,
        val vuid: Int,
        val packageName: String,
        val versionCode: Long,
        val providerClass: String,
        val processName: String,
        val authority: String,
    ) {
        fun launchParams(): VirtualLaunchParams = VirtualLaunchParams(
            vuid = vuid,
            packageName = packageName,
            versionCode = versionCode,
            targetComponent = providerClass,
            kind = VirtualComponentKind.PROVIDER,
            processName = processName,
            slot = slot,
        )
    }

    fun resolve(context: Context, vuid: Int, authority: String): Route? {
        val hostPackage = hostPackageOf(context)
        val reply = runCatching {
            callUnstably(
                context,
                VirtualProviderRouter.routerUri(hostPackage),
                VirtualProviderRouter.ROUTER_METHOD_RESOLVE,
                Bundle().apply {
                    putInt(VirtualProviderRouter.KEY_VUID, vuid)
                    putString(VirtualProviderRouter.KEY_AUTHORITY, authority)
                },
            )
        }.getOrElse {
            Diagnostics.error(
                DiagChannel.PROCESS, "PROVIDER_RESOLVE_FAILED",
                mapOf("authority" to authority, "error" to it.toString()),
            )
            return null
        } ?: return null

        val slot = reply.getInt(VirtualProviderRouter.KEY_SLOT, -1)
        if (slot < 0) return null
        return Route(
            slot = slot,
            vuid = reply.getInt(VirtualProviderRouter.KEY_VUID, vuid),
            packageName = reply.getString(VirtualProviderRouter.KEY_PACKAGE).orEmpty(),
            versionCode = reply.getLong(VirtualProviderRouter.KEY_VERSION_CODE, 0L),
            providerClass = reply.getString(VirtualProviderRouter.KEY_PROVIDER).orEmpty(),
            processName = reply.getString(VirtualProviderRouter.KEY_PROCESS).orEmpty(),
            authority = reply.getString(VirtualProviderRouter.KEY_AUTHORITY) ?: authority,
        )
    }

    /**
     * Makes the slot's process exist, be the right instance, and accept [route.authority].
     *
     * Returns the authorities the target process reports it now publishes, or null when
     * the bind failed — which is reported there rather than guessed at here.
     */
    fun bind(context: Context, route: Route): Set<String>? {
        val hostPackage = hostPackageOf(context)
        val stub = Uri.parse(
            "content://" + VirtualProviderRouter.stubAuthority(hostPackage, route.slot)
        )
        // Warm the target process before asking it for anything, and *wait until it says
        // it has grafted*. Starting the service and calling immediately still loses the
        // race: AMS cold-starts the process for the provider instead, and the ten-second
        // publish timeout applies after all. See warmProcess and awaitReady.
        warmProcess(context, hostPackage, route)
        awaitReady(context, hostPackage, route)

        val extras = Bundle().apply { putAll(route.launchParams().toBundle()) }
        var lastError: String? = null
        for (attempt in 1..BIND_ATTEMPTS) {
            val reply = runCatching {
                callUnstably(context, stub, METHOD_BIND, extras)
            }.getOrElse {
                lastError = it.toString()
                null
            }
            if (reply != null) return finishBind(route, reply)
            if (attempt < BIND_ATTEMPTS) {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "PROVIDER_BIND_RETRY",
                    mapOf(
                        "authority" to route.authority,
                        "slot" to route.slot.toString(),
                        "attempt" to attempt.toString(),
                        "error" to (lastError ?: "no reply"),
                    ),
                )
                runCatching { Thread.sleep(BIND_RETRY_MILLIS * attempt) }
            }
        }
        Diagnostics.error(
            DiagChannel.PROCESS, "PROVIDER_BIND_FAILED",
            mapOf(
                "authority" to route.authority,
                "slot" to route.slot.toString(),
                "attempts" to BIND_ATTEMPTS.toString(),
                "error" to (lastError ?: "no reply"),
            ),
        )
        return null
    }

    /**
     * Starts the target slot's process before anything acquires a provider from it.
     *
     * `ActivityManagerService` gives a process it cold-starts *for a provider* ten seconds
     * to publish, and kills it otherwise:
     *
     * ```
     * Killing 4786:com.unique:vapp2 (adj 0): timeout publishing content providers
     * ```
     *
     * A cold `:vappN` does not reliably make that. It has to load UNIQUE's own code,
     * install the interception layer and graft the guest before `handleBindApplication`
     * returns — work an ordinary app does not do, and work that happens *before* providers
     * are published. On a slow device the process is killed and restarted in a loop, and
     * every acquisition fails.
     *
     * Starting the reserved stub service first takes that budget off the critical path
     * entirely: the process comes up, grafts, and is already running when the provider
     * acquisition arrives, so publishing is immediate. It is the same reserved stub the
     * cold broadcast path uses, for the same reason — bringing a process into existence
     * without putting a window on screen.
     *
     * Fire-and-forget. A service start is asynchronous and there is nothing useful to
     * block on here; [awaitReady] is what waits, on a signal the slot sends rather than on
     * a guess about it.
     */
    private fun warmProcess(context: Context, hostPackage: String, route: Route) {
        // A slot that reports itself ready has already grafted; there is nothing to warm.
        //
        // That is the *only* question asked. An earlier version also skipped the start
        // when `getRunningAppProcesses` listed the process, and that turned out to be how
        // this failed after a guest's provider process was killed:
        //
        // ```
        // PROVIDER_SLOT_READY_STALE slot=2 pid=6236        (the router noticed the death)
        // PROVIDER_READY_NOT_SEEN slot=2 vuid=0 waitedMillis=45078
        // Killing 6989:com.unique:vapp2 (adj 0): timeout publishing content providers
        // ```
        //
        // The list still named a process that was gone, so nothing was started, and the
        // wait below timed out against a process that was never coming — leaving
        // ActivityManager to cold-start it for the provider and kill it three times over.
        // Starting it again when it is already running is harmless: `VirtualProviderHost`
        // returns `PROVIDER_WARM_ALREADY_BOUND` for a bound process and joins the graft
        // already in flight for one that is still starting.
        if (isSlotReady(context, hostPackage, route)) return
        startWarmService(context, hostPackage, route)
    }

    /** The service start itself, with no opinion about whether it is needed. */
    private fun startWarmService(context: Context, hostPackage: String, route: Route) {
        runCatching {
            val stubService = StubRouter.stubService(
                route.slot, VirtualServiceRouter.COLD_BROADCAST_STUB_INDEX,
            )
            val intent = Intent().apply {
                component = ComponentName(hostPackage, stubService)
                route.launchParams().writeTo(this)
            }
            context.startService(intent)
        }.onFailure {
            // Not fatal: the acquisition may still succeed on a fast device. Recorded
            // because it explains the retries that follow.
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_WARM_FAILED",
                mapOf("slot" to route.slot.toString(), "error" to it.toString()),
            )
        }
    }

    /**
     * Waits until the slot's process reports it has grafted, or gives up quietly.
     *
     * The signal is the slot's own `slotReady` announcement, relayed by the router
     * (§6.4.0). That is a genuine statement about the thing the caller depends on — the
     * guest is bootstrapped, so its providers publish immediately — where the process
     * merely appearing in `getRunningAppProcesses` was a guess about it, and a lagging one:
     * the run that produced this code failed with
     *
     * ```
     * PROVIDER_WARM_NOT_SEEN slot=2 waitedMillis=8000
     * Killing 5216:com.unique:vapp2 (adj 0): timeout publishing content providers
     * ```
     *
     * — a warm-up that had in fact started the process, a caller that stopped believing
     * it, and ActivityManager cold-starting the same process for the provider into its own
     * ten-second budget.
     *
     * Giving up quietly is still right: the retry loop is what reports failure, and it
     * will have a better error than "the slot never said it was ready".
     */
    private fun awaitReady(context: Context, hostPackage: String, route: Route) {
        val started = SystemClock.uptimeMillis()
        val deadline = started + READY_WAIT_MILLIS
        var nextWarm = started + READY_REWARM_MILLIS
        var warms = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val state = slotState(context, hostPackage, route)
            if (state.ready) return
            val now = SystemClock.uptimeMillis()
            if (now >= nextWarm) {
                // Ask again only when nothing is happening. The process may have died —
                // a device slow enough to need this wait is slow enough to lose it, and
                // the platform kills it before any of UNIQUE's code has run:
                //
                //   Process ProcessRecord{… com.unique:vapp2} failed to attach
                //   Killing 11888:com.unique:vapp2 (adj -10000): start timeout
                //
                // But if a graft is *running*, asking again is the harmful thing to do:
                // it queues another `onStartCommand` behind a main thread that is busy for
                // tens of seconds, and ActivityManager kills the process for `bg anr` —
                // which is exactly how this cost a slot sixteen seconds before it was
                // ready. `starting` is what tells the two apart.
                if (!state.starting) {
                    startWarmService(context, hostPackage, route)
                    warms++
                }
                nextWarm = now + READY_REWARM_MILLIS
            }
            runCatching { Thread.sleep(READY_POLL_MILLIS) }
        }
        // One last look. The poll leaves up to a quarter-second blind before the deadline,
        // and a run was lost inside it: the slot announced 116 ms before the budget
        // expired, and the caller gave up on a process that was already serving.
        if (isSlotReady(context, hostPackage, route)) return
        Diagnostics.warn(
            DiagChannel.PROCESS, "PROVIDER_READY_NOT_SEEN",
            mapOf(
                "slot" to route.slot.toString(),
                "vuid" to route.vuid.toString(),
                "waitedMillis" to (SystemClock.uptimeMillis() - started).toString(),
                "rewarms" to warms.toString(),
            ),
        )
    }

    /** What the router knows about a slot: grafted, grafting, or neither. */
    private data class SlotState(val ready: Boolean, val starting: Boolean)

    private fun slotState(context: Context, hostPackage: String, route: Route): SlotState =
        runCatching {
            val reply = callUnstably(
                context,
                VirtualProviderRouter.routerUri(hostPackage),
                VirtualProviderRouter.ROUTER_METHOD_SLOT_STATUS,
                Bundle().apply {
                    putInt(VirtualProviderRouter.KEY_SLOT, route.slot)
                    putInt(VirtualProviderRouter.KEY_VUID, route.vuid)
                },
            )
            SlotState(
                ready = reply?.getBoolean(VirtualProviderRouter.KEY_READY, false) ?: false,
                starting = reply?.getBoolean(VirtualProviderRouter.KEY_STARTING, false) ?: false,
            )
        }.getOrDefault(SlotState(ready = false, starting = false))

    private fun isSlotReady(context: Context, hostPackage: String, route: Route): Boolean =
        slotState(context, hostPackage, route).ready

    /**
     * `call()` on a provider without taking a *stable* reference to its process.
     *
     * `ContentResolver.call` acquires stably, and a stable reference tells
     * `ActivityManagerService` that the caller cannot survive the provider's process
     * dying — so it kills the caller when it does:
     *
     * ```
     * Killing 4798:com.unique (adj 0): depends on provider
     *     com.unique/.stub.ProviderStub_p0 in dying proc com.unique:vapp0
     * ```
     *
     * That is UNIQUE's own process being taken down by a virtual app closing, which is the
     * exact thing §3 exists to prevent. It appeared with cross-process providers and hid
     * behind an earlier, wrong conclusion that only same-instance processes were affected.
     *
     * The fix belongs here, on UNIQUE's own side of the acquisition — not in rewriting the
     * `stable` flag of somebody else's call in flight, which was tried and desynchronised
     * `ActivityThread`'s reference counts from ActivityManager's (§6.4.0).
     */
    private fun callUnstably(
        context: Context,
        uri: Uri,
        method: String,
        extras: Bundle?,
    ): Bundle? =
        context.contentResolver.acquireUnstableContentProviderClient(uri)?.use { client ->
            client.call(method, null, extras)
        }

    private fun finishBind(route: Route, reply: Bundle): Set<String>? {

        reply.getString(KEY_ERROR)?.let { error ->
            Diagnostics.error(
                DiagChannel.PROCESS, "PROVIDER_BIND_REFUSED",
                mapOf("authority" to route.authority, "reason" to error),
            )
            return null
        }
        return reply.getStringArray(KEY_AUTHORITIES)?.toSet().orEmpty()
    }

    /**
     * How many times to ask the slot to bind.
     *
     * Three, with a growing pause, because the failure being retried is a *cold start*
     * losing a race with the platform's publish timeout — the second attempt meets a
     * process that is already most of the way up, and the third one that is fully up.
     * Retrying an acquisition that failed for any other reason costs a few seconds and
     * reports the same error it would have anyway.
     */
    private const val BIND_ATTEMPTS = 3
    private const val BIND_RETRY_MILLIS = 2500L

    /**
     * How long to wait for a warmed slot to report itself ready.
     *
     * Sized to the work, not to a guess: a cold graft is measured at forty seconds on the
     * verification emulator (§17.1), which is why `VirtualProviderHost` allows sixty for
     * the same work. Forty-five is longer than the graft and shorter than that timeout, so
     * a caller that gives up here still leaves the bind's own retries something to do.
     *
     * The eight seconds this replaced were sized to a *fork*, because the signal being
     * waited on was only the process existing. Waiting for the right thing costs more
     * wall-clock and is the difference between a bind that works on a slow device and one
     * that is killed publishing.
     */
    private const val READY_WAIT_MILLIS = 45_000L
    private const val READY_POLL_MILLIS = 250L

    /**
     * How often to ask again for a process that has not reported in.
     *
     * Fifteen seconds is longer than the platform's own ten-second process-start timeout,
     * so a start that is going to fail has already failed by the time the next one is
     * issued — and shorter than a third of the budget, so a wait that needs three tries
     * gets them.
     */
    private const val READY_REWARM_MILLIS = 15_000L

    /**
     * A client for one instance's provider, or null when it cannot be reached.
     *
     * Unstable on purpose: the guest's process can be killed at any time — by UNIQUE, by
     * the user, by low memory — and a stable client would take the caller down with it.
     * A virtual app dying must never kill UNIQUE or another instance (§3).
     *
     * The caller closes it.
     */
    fun open(context: Context, vuid: Int, authority: String): ContentProviderClient? {
        val route = resolve(context, vuid, authority) ?: return null
        val published = bind(context, route) ?: return null
        if (authority !in published) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_NOT_PUBLISHED",
                mapOf(
                    "authority" to authority,
                    "published" to published.joinToString(",").take(300),
                ),
            )
            return null
        }
        val stubAuthority =
            VirtualProviderRouter.stubAuthority(hostPackageOf(context), route.slot)
        return context.contentResolver.acquireUnstableContentProviderClient(stubAuthority)
    }

    /**
     * UNIQUE's own package name, even when called from a grafted process.
     *
     * `context.packageName` inside a virtual process answers with the *guest's* name,
     * which is the whole point of the graft and exactly wrong here: the router and the
     * stubs are components of UNIQUE. `ApplicationInfo` for the host is not reachable
     * from a grafted `Context` either, so the bootstrap records it.
     */
    private fun hostPackageOf(context: Context): String =
        AppBootstrap.hostPackageName ?: context.packageName
}
