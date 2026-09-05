package com.unique.core.vam

import android.net.Uri
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.diagnostics.Diagnostics

/**
 * Where every imported package's content-provider authorities are known.
 *
 * `ActivityManagerService` resolves an authority against *installed* packages, so a guest
 * authority resolves to nothing no matter who asks. Inside the guest's own process
 * [VirtualProviderRegistry] answers the acquisition directly (§6.4). This is the other
 * half: the table that lets a process which is *not* the guest's find out which of the
 * host's `:vappN` slots to talk to.
 *
 * It lives in UNIQUE's main process, next to [VirtualBroadcastRouter] and for the same
 * reason — that is the process that outlives any individual virtual app. Other processes
 * reach it through [UNIQUE's router provider][ROUTER_METHOD_RESOLVE], because a
 * `ContentProvider` declared in the host manifest is the one interface the platform will
 * carry between two processes of the same app without UNIQUE inventing an AIDL for it.
 *
 * The key is `(vuid, authority)`, never the authority alone. Two clones of one app
 * declare the *same* authority, and answering "which instance did you mean" by picking
 * the first would hand a caller another instance's database.
 */
object VirtualProviderRouter {

    /** One instance's provider, as the host knows it. */
    data class Target(
        val vuid: Int,
        val packageName: String,
        val versionCode: Long,
        val providerClass: String,
        val processName: String,
        val authority: String,
    )

    const val ROUTER_METHOD_RESOLVE = "unique.resolveProvider"

    /** A `:vappN` reporting that it has grafted and is ready to serve. */
    const val ROUTER_METHOD_SLOT_READY = "unique.slotReady"

    /** A `:vappN` reporting that a graft has begun, so a caller stops asking again. */
    const val ROUTER_METHOD_SLOT_STARTING = "unique.slotStarting"

    /** A caller asking whether a slot has got that far yet. */
    const val ROUTER_METHOD_SLOT_STATUS = "unique.slotStatus"

    const val KEY_READY = "unique.ready"
    const val KEY_STARTING = "unique.starting"
    const val KEY_PID = "unique.pid"

    const val KEY_VUID = "unique.vuid"
    const val KEY_AUTHORITY = "unique.authority"
    const val KEY_PACKAGE = "unique.package"
    const val KEY_VERSION_CODE = "unique.versionCode"
    const val KEY_PROVIDER = "unique.provider"
    const val KEY_PROCESS = "unique.process"
    const val KEY_SLOT = "unique.slot"

    /** The host provider every other process asks. `<applicationId>.router`. */
    fun routerUri(hostPackage: String): Uri = Uri.parse("content://$hostPackage.router")

    /** The stub provider serving one slot, as generated into the host manifest. */
    fun stubAuthority(hostPackage: String, slot: Int): String = "$hostPackage.vprovider.$slot"

    private val targets = LinkedHashMap<Key, Target>()

    private data class Key(val vuid: Int, val authority: String)

    /** Set by the engine: leases the `:vappN` slot that will serve an instance. */
    @Volatile
    var slotLeaser: ((Target) -> Int?)? = null

    val size: Int get() = synchronized(this) { targets.size }

    @Synchronized
    fun register(vuid: Int, manifest: ApkManifest) {
        targets.keys.removeAll { it.vuid == vuid }
        var added = 0
        for (entry in manifest.components) {
            if (entry.kind != ComponentKind.PROVIDER || !entry.enabled) continue
            for (authority in entry.authorities) {
                targets[Key(vuid, authority)] = Target(
                    vuid = vuid,
                    packageName = manifest.packageName,
                    versionCode = manifest.versionCode,
                    providerClass = entry.className,
                    processName = entry.processName,
                    authority = authority,
                )
                added++
            }
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDER_ROUTES_REGISTERED",
            mapOf(
                "package" to manifest.packageName,
                "vuid" to vuid.toString(),
                "authorities" to added.toString(),
            ),
        )
    }

    @Synchronized
    fun unregister(vuid: Int) {
        targets.keys.removeAll { it.vuid == vuid }
    }

    @Synchronized
    fun reset() {
        targets.clear()
        ready.clear()
        starting.clear()
    }

    /**
     * Which instance each `:vappN` has finished grafting, as the slot itself reported.
     *
     * The alternative — polling `ActivityManager.getRunningAppProcesses` — answers a
     * different and much weaker question, and answers it unreliably: it has omitted a
     * process that was already serving Binder calls, and listed one `system_server` had
     * buried minutes earlier. A caller that gives up on that signal proceeds to acquire a
     * provider from a process that has not started, ActivityManager cold-starts it *for
     * the provider*, and the platform's ten-second publish timeout kills it in a loop.
     *
     * A slot saying "I am ready" is the only signal that means what the caller needs.
     */
    private data class Ready(val vuid: Int, val pid: Int)

    private val ready = HashMap<Int, Ready>()

    /**
     * Slots whose graft has begun and not yet finished.
     *
     * The difference between this and [ready] is the difference between "ask again" and
     * "wait": a caller that re-issues a warm-up while a graft is running queues another
     * `onStartCommand` behind a main thread that is busy for tens of seconds, and
     * ActivityManager answers that with
     *
     * ```
     * ANR in com.unique:vapp2
     * Killing 3515:com.unique:vapp2 (adj 0): bg anr
     * ```
     *
     * — killing the very process the caller was waiting for, sixteen seconds before it
     * would have been ready. The re-warm exists for a process that died; without this
     * record it could not tell that from one that is merely slow.
     */
    private val starting = HashMap<Int, Ready>()

    @Synchronized
    fun markStarting(slot: Int, vuid: Int, pid: Int) {
        starting[slot] = Ready(vuid, pid)
        Diagnostics.event(
            DiagChannel.PROCESS, DiagLevel.DEBUG, "PROVIDER_SLOT_STARTING",
            mapOf("slot" to slot.toString(), "vuid" to vuid.toString(), "pid" to pid.toString()),
        )
    }

    /** Whether a graft is in flight in a process that is still alive. */
    @Synchronized
    fun isStarting(slot: Int, vuid: Int): Boolean {
        val mark = starting[slot] ?: return false
        if (mark.vuid != vuid) return false
        if (!processAlive(mark.pid)) {
            starting.remove(slot)
            return false
        }
        return true
    }

    @Synchronized
    fun markReady(slot: Int, vuid: Int, pid: Int) {
        starting.remove(slot)
        ready[slot] = Ready(vuid, pid)
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDER_SLOT_READY",
            mapOf(
                "slot" to slot.toString(),
                "vuid" to vuid.toString(),
                "pid" to pid.toString(),
            ),
        )
    }

    /**
     * Whether [slot] is currently serving [vuid] and has finished grafting.
     *
     * The recorded pid is checked, not just the mark, because a slot that grafted and then
     * died must not read as ready: the caller would skip the warm-up and let
     * ActivityManager cold-start the process *for the provider* again, which is the
     * failure this whole handshake exists to prevent.
     *
     * `Os.kill(pid, 0)` is the liveness check rather than `getRunningAppProcesses`, for
     * the reason in this class's doc: the list has been seen to lag reality in both
     * directions. A signal of 0 delivers nothing and only asks whether the process is
     * there; `ESRCH` is the one errno that means it is not.
     *
     * The residual hole is pid reuse: a recycled pid belonging to some other process would
     * read as alive. It stays narrow — the mark must also name the same vuid, and the
     * bind's own retry loop is what finally establishes whether the slot can answer — and
     * it is worth naming rather than papering over.
     */
    @Synchronized
    fun isReady(slot: Int, vuid: Int): Boolean {
        val mark = ready[slot] ?: return false
        if (mark.vuid != vuid) return false
        if (!processAlive(mark.pid)) {
            ready.remove(slot)
            Diagnostics.info(
                DiagChannel.PROCESS, "PROVIDER_SLOT_READY_STALE",
                mapOf("slot" to slot.toString(), "pid" to mark.pid.toString()),
            )
            return false
        }
        return true
    }

    /**
     * The pid last reported by [slot], whether or not it is still running.
     *
     * Exposed for [SlotProcesses], which needs a slot's pid to end the process in it and
     * cannot get one from the process list alone — that list has been seen to omit a
     * process that was very much alive. Liveness is the caller's question, not this one's:
     * the record is returned as recorded.
     */
    @Synchronized
    fun pidOf(slot: Int): Int? = (ready[slot] ?: starting[slot])?.pid

    private fun processAlive(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (e: ErrnoException) {
        // EPERM means a process is there and not ours to signal; only ESRCH means gone.
        e.errno != OsConstants.ESRCH
    }

    @Synchronized
    fun forgetSlot(slot: Int) {
        ready.remove(slot)
        starting.remove(slot)
    }

    /** Answers [ROUTER_METHOD_SLOT_READY] and [ROUTER_METHOD_SLOT_STATUS]. */
    fun slotReady(extras: Bundle?): Bundle? {
        val slot = extras?.getInt(KEY_SLOT, -1) ?: -1
        val vuid = extras?.getInt(KEY_VUID, -1) ?: -1
        val pid = extras?.getInt(KEY_PID, -1) ?: -1
        if (slot < 0 || vuid < 0 || pid < 0) return null
        markReady(slot, vuid, pid)
        return Bundle.EMPTY
    }

    fun slotStarting(extras: Bundle?): Bundle? {
        val slot = extras?.getInt(KEY_SLOT, -1) ?: -1
        val vuid = extras?.getInt(KEY_VUID, -1) ?: -1
        val pid = extras?.getInt(KEY_PID, -1) ?: -1
        if (slot < 0 || vuid < 0 || pid < 0) return null
        markStarting(slot, vuid, pid)
        return Bundle.EMPTY
    }

    fun slotStatus(extras: Bundle?): Bundle? {
        val slot = extras?.getInt(KEY_SLOT, -1) ?: -1
        val vuid = extras?.getInt(KEY_VUID, -1) ?: -1
        if (slot < 0 || vuid < 0) return null
        return Bundle().apply {
            putBoolean(KEY_READY, isReady(slot, vuid))
            putBoolean(KEY_STARTING, isStarting(slot, vuid))
        }
    }

    @Synchronized
    fun target(vuid: Int, authority: String): Target? = targets[Key(vuid, authority)]

    /** Every authority any imported instance declares, whichever instance owns it. */
    @Synchronized
    fun allAuthorities(): Set<String> = targets.keys.mapTo(LinkedHashSet()) { it.authority }

    /**
     * Answers one `resolveProvider` call, leasing the slot that will serve the instance.
     *
     * Runs in UNIQUE's main process; the reply crosses back to whoever asked.
     */
    fun resolve(extras: Bundle?): Bundle? {
        val vuid = extras?.getInt(KEY_VUID, -1) ?: -1
        val authority = extras?.getString(KEY_AUTHORITY)
        if (vuid < 0 || authority.isNullOrEmpty()) return null

        val target = target(vuid, authority) ?: run {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_ROUTE_UNKNOWN",
                mapOf("vuid" to vuid.toString(), "authority" to authority),
            )
            return null
        }
        val slot = slotLeaser?.invoke(target) ?: run {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_ROUTE_NO_SLOT",
                mapOf("vuid" to vuid.toString(), "authority" to authority),
            )
            return null
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDER_ROUTE_RESOLVED",
            mapOf(
                "vuid" to vuid.toString(),
                "authority" to authority,
                "slot" to slot.toString(),
                "process" to target.processName,
            ),
        )
        return Bundle().apply {
            putInt(KEY_SLOT, slot)
            putInt(KEY_VUID, target.vuid)
            putString(KEY_PACKAGE, target.packageName)
            putLong(KEY_VERSION_CODE, target.versionCode)
            putString(KEY_PROVIDER, target.providerClass)
            putString(KEY_PROCESS, target.processName)
            putString(KEY_AUTHORITY, target.authority)
        }
    }
}
