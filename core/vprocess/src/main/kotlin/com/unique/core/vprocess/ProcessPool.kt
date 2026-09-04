package com.unique.core.vprocess

import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/** One virtual app process slot, backed by a manifest-declared `:vappN` process. */
data class ProcessSlot(
    val index: Int,
    val processSuffix: String,
    val occupant: Occupant?,
    val lastUsedMillis: Long,
) {
    val isFree: Boolean get() = occupant == null
}

/**
 * Which (instance, manifest process) pair currently owns a slot.
 *
 * The key is the pair rather than just the package, because an app that declares
 * `android:process=":push"` genuinely expects a separate process and relies on it —
 * collapsing them changes behaviour apps depend on (separate static state, separate
 * crash domains).
 */
data class Occupant(val vuid: Int, val packageName: String, val processName: String)

/**
 * Assigns virtual apps to the host's pre-declared process slots.
 *
 * Android does not let an app create processes on demand: a process exists only because
 * a manifest component declares `android:process`. So the pool is declared statically at
 * build time and assigned dynamically here.
 */
class ProcessPool(private val capacity: Int) {

    private val slots = (0 until capacity).map {
        ProcessSlot(it, ":vapp$it", null, 0L)
    }.toMutableList()

    private val byOccupant = HashMap<Occupant, Int>()

    @Synchronized
    fun snapshot(): List<ProcessSlot> = slots.toList()

    @Synchronized
    fun find(occupant: Occupant): ProcessSlot? = byOccupant[occupant]?.let { slots[it] }

    /**
     * Returns the slot serving [occupant], allocating one if needed.
     *
     * Returns null when the pool is full of live processes: the caller reports
     * `PROCESS_POOL_EXHAUSTED` and refuses the launch rather than evicting a running app
     * out from under the user.
     */
    @Synchronized
    fun acquire(occupant: Occupant, now: Long = System.currentTimeMillis()): ProcessSlot? {
        byOccupant[occupant]?.let { idx ->
            slots[idx] = slots[idx].copy(lastUsedMillis = now)
            return slots[idx]
        }
        val free = slots.firstOrNull { it.isFree }
            ?: run {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "PROCESS_POOL_EXHAUSTED",
                    mapOf("capacity" to capacity.toString(), "requested" to occupant.toString()),
                )
                return null
            }
        val taken = free.copy(occupant = occupant, lastUsedMillis = now)
        slots[free.index] = taken
        byOccupant[occupant] = free.index
        Diagnostics.info(
            DiagChannel.PROCESS, "PROCESS_ASSIGNED",
            mapOf(
                "slot" to taken.processSuffix,
                "vuid" to occupant.vuid.toString(),
                "process" to occupant.processName,
            ),
        )
        return taken
    }

    /** Called when a `:vappN` process dies, whether cleanly or by crash. */
    @Synchronized
    fun release(index: Int, reason: String) {
        val slot = slots.getOrNull(index) ?: return
        slot.occupant?.let { byOccupant.remove(it) }
        slots[index] = slot.copy(occupant = null)
        Diagnostics.info(
            DiagChannel.PROCESS, "PROCESS_RELEASED",
            mapOf("slot" to slot.processSuffix, "reason" to reason),
        )
    }

    @Synchronized
    fun releaseAll(vuid: Int, reason: String) {
        slots.filter { it.occupant?.vuid == vuid }.forEach { release(it.index, reason) }
    }

    @Synchronized
    fun freeCount(): Int = slots.count { it.isFree }
}
