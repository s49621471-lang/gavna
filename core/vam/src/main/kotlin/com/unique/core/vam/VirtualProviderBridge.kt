package com.unique.core.vam

import android.app.ActivityManager
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
            context.contentResolver.call(
                VirtualProviderRouter.routerUri(hostPackage),
                VirtualProviderRouter.ROUTER_METHOD_RESOLVE,
                null,
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
        // Warm the target process before asking it for anything, and *wait for it to
        // exist*. Starting the service and calling immediately still loses the race: AMS
        // cold-starts the process for the provider instead, and the ten-second publish
        // timeout applies after all. See warmProcess.
        warmProcess(context, hostPackage, route)
        awaitProcess(context, hostPackage, route.slot)

        val extras = Bundle().apply { putAll(route.launchParams().toBundle()) }
        var lastError: String? = null
        for (attempt in 1..BIND_ATTEMPTS) {
            val reply = runCatching {
                context.contentResolver.call(stub, METHOD_BIND, null, extras)
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
     * Fire-and-forget. The retry loop above is what actually waits, because a service
     * start is asynchronous and there is nothing useful to block on.
     */
    private fun warmProcess(context: Context, hostPackage: String, route: Route) {
        // Nothing to warm, and starting the service again would queue another
        // onStartCommand behind a graft that is already running — which is how a `:vappN`
        // earns `bg anr` for work it was told to do three times.
        if (processExists(context, hostPackage, route.slot)) return
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
     * Waits until the slot's process exists, or gives up quietly.
     *
     * Existence is the whole point: a *running* process publishes a provider promptly, so
     * ActivityManager's ten-second budget for cold-starting one never applies. Giving up
     * quietly is right too — the retry loop is what actually reports failure, and it will
     * have a better error than "the process did not appear".
     */
    private fun awaitProcess(context: Context, hostPackage: String, slot: Int) {
        val deadline = SystemClock.uptimeMillis() + PROCESS_WAIT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (processExists(context, hostPackage, slot)) return
            runCatching { Thread.sleep(250) }
        }
        Diagnostics.warn(
            DiagChannel.PROCESS, "PROVIDER_WARM_NOT_SEEN",
            mapOf("slot" to slot.toString(), "waitedMillis" to PROCESS_WAIT_MILLIS.toString()),
        )
    }

    private fun processExists(context: Context, hostPackage: String, slot: Int): Boolean {
        val name = "$hostPackage:vapp$slot"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return runCatching {
            am.runningAppProcesses.orEmpty().any { it.processName == name }
        }.getOrDefault(false)
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
     * How long to wait for a warmed process to appear.
     *
     * A fork is fast even on a slow device; what is slow is everything after it, and that
     * is what the process needing to *exist* protects against.
     *
     * Best-effort, and short, because the evidence is unreliable:
     * `getRunningAppProcesses` lags — it has been seen to omit a process that was already
     * serving Binder calls, and to list one `system_server` had buried minutes earlier.
     * Waiting longer on a bad signal buys nothing; the retry loop is what actually
     * establishes whether the slot can answer.
     */
    private const val PROCESS_WAIT_MILLIS = 8_000L

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
