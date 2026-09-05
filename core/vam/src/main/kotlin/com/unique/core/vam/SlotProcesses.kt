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

    /** Pids of the processes serving [slot], newest information first. */
    private fun pidsOf(slot: Int): List<Int> {
        val self = Process.myPid()
        val fromRouter = VirtualProviderRouter.pidOf(slot)?.takeIf { it != self && isAlive(it) }
        val name = processName(slot)
        val fromPlatform = runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.runningAppProcesses.orEmpty()
                .filter { it.processName == name && it.pid != self }
                .map { it.pid }
        }.getOrDefault(emptyList())
        return (listOfNotNull(fromRouter) + fromPlatform).distinct()
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

    private fun isAlive(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (e: ErrnoException) {
        // EPERM means a process is there and not ours to signal; only ESRCH means gone.
        e.errno != OsConstants.ESRCH
    }
}
