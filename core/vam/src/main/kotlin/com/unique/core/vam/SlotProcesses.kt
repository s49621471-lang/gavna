package com.unique.core.vam

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Finds and ends the `:vappN` process behind a pool slot.
 *
 * [ProcessPool][com.unique.core.vprocess.ProcessPool] needs two facts it cannot get for
 * itself without an Android dependency: whether a slot's process exists, and how to end
 * it. This supplies both, and is the only place that knows a slot index maps to the
 * process name `<host>:vappN`.
 *
 * ## Two sources, because neither one is enough
 *
 * `ActivityManager.getRunningAppProcesses()` is the only way to turn a process *name*
 * into a pid, and it is documented in [VirtualProviderRouter] as unreliable in both
 * directions — it has omitted a process that was serving Binder calls and listed one the
 * system had already killed. The router's own record is exact but only exists for a slot
 * that got far enough to announce itself.
 *
 * So liveness is the union: a slot is alive if either source says so. That bias is
 * deliberate. A false "alive" costs one kill of a process that was already gone, which is
 * a no-op; a false "dead" hands a new app a slot with someone else's graft in it, which
 * is the failure this class exists to prevent and which took every launch after Gemini's
 * with it.
 */
internal class SlotProcesses(private val context: Context, private val hostPackage: String) {

    private fun processName(slot: Int): String = "$hostPackage:vapp$slot"

    @Volatile private var cachedAt = 0L
    @Volatile private var cached: Map<String, List<Int>> = emptyMap()

    /**
     * `:vappN` process names to pids, from one call to `getRunningAppProcesses`.
     *
     * Cached for [PROCESS_LIST_TTL_MILLIS] because the pool asks about every occupied slot
     * and then about the one it is allocating, and an uncached lookup makes that N+1
     * identical Binder calls for one launch.
     *
     * The window is deliberately much shorter than anything that could act on the answer.
     * Its whole job is to make one sweep consistent with itself, not to remember: a
     * process that dies inside the window is caught by the next sweep, and one that starts
     * inside it was started by this same allocation.
     */
    private fun processes(): Map<String, List<Int>> {
        val now = System.currentTimeMillis()
        val snapshot = cached
        if (snapshot.isNotEmpty() && now - cachedAt < PROCESS_LIST_TTL_MILLIS) return snapshot
        val self = Process.myPid()
        val fresh = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.runningAppProcesses.orEmpty()
                .filter { it.processName.contains(":vapp") && it.pid != self }
                .groupBy({ it.processName }, { it.pid })
        }.getOrDefault(emptyMap())
        cached = fresh
        cachedAt = now
        return fresh
    }

    /** Pids of the processes serving [slot], the exact record first. */
    private fun pidsOf(slot: Int): List<Int> {
        val self = Process.myPid()
        val fromRouter = VirtualProviderRouter.pidOf(slot)?.takeIf { it != self && isAlive(it) }
        val fromPlatform = processes()[processName(slot)].orEmpty()
        return (listOfNotNull(fromRouter) + fromPlatform).distinct()
    }

    /** Forces the next lookup to ask the platform again. */
    private fun invalidate() {
        cachedAt = 0L
        cached = emptyMap()
    }

    fun alive(slot: Int): Boolean = pidsOf(slot).isNotEmpty()

    /**
     * Ends the process serving [slot], if any.
     *
     * `killProcess` rather than `ActivityManager.killBackgroundProcesses`: the target is
     * another process of UNIQUE's own app, so the signal is permitted, and it takes
     * effect immediately rather than when the platform next considers the app for
     * trimming. A guest killed this way leaves the same trace a crashed one does.
     *
     * The router's record for the slot is dropped in the same breath. Leaving it would
     * let a later caller read a dead slot as ready and skip the warm-up, which is the
     * stale-mark failure `PROVIDER_SLOT_READY_STALE` exists to report.
     */
    fun stop(slot: Int, reason: String) {
        val pids = pidsOf(slot)
        VirtualProviderRouter.forgetSlot(slot)
        // Whatever the cached sweep said about this slot is wrong from here on.
        invalidate()
        if (pids.isEmpty()) return
        for (pid in pids) {
            runCatching { Process.killProcess(pid) }
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "SLOT_PROCESS_STOPPED",
            mapOf(
                "slot" to ":vapp$slot",
                "pids" to pids.joinToString("+"),
                "reason" to reason,
            ),
        )
    }

    private companion object {
        /**
         * How long one sweep of the process list stays good for.
         *
         * Short enough that no decision outlives the fact it was based on, long enough to
         * cover the several questions one allocation asks.
         */
        const val PROCESS_LIST_TTL_MILLIS = 250L
    }

    private fun isAlive(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (e: ErrnoException) {
        // EPERM means a process is there and not ours to signal; only ESRCH means gone.
        e.errno != OsConstants.ESRCH
    }
}
