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
 *
 * ## Why the pool has to know about processes at all
 *
 * The obvious design — a pool that is pure bookkeeping, with process lifetime someone
 * else's problem — is the one that shipped, and it is wrong in both directions. A slot
 * record and the `:vappN` process behind it are two facts that can disagree, and every
 * way they disagree breaks a launch:
 *
 *  - **The record is freed and the process lives on.** Removing an instance called
 *    `releaseAll`, which cleared the occupant and killed nothing. The next app was
 *    assigned the same slot, the platform routed its stub activity into the surviving
 *    process, and [the graft refused to rebind][ProcessPool]:
 *
 *    ```
 *    BOOTSTRAP_FAILED package=clear.una code=SLOT_ALREADY_BOUND
 *        message=Slot 0 already serves com.google.android.apps.bard (u0)
 *    ACTIVITY_HANDOFF_DID_NOT_HAPPEN slot=0 component=clear.una.MainActivity
 *    ```
 *
 *    Every app launched after that one failed identically, because slot 0 was picked
 *    first every time. On the device this reads as "UNIQUE stopped launching anything
 *    after Gemini", which is exactly how it was reported.
 *
 *  - **The process dies and the record lives on.** Nothing called [release] on a crash
 *    or a low-memory kill, despite the doc saying so, so a crashed guest kept its slot
 *    forever and the pool ran out of capacity with sixteen dead processes.
 *
 * Both are closed by making the pool ask, rather than assume: [alive] answers whether a
 * slot's process actually exists, [stop] ends it. Liveness is re-checked at every
 * allocation instead of relying on a death callback, because a callback that does not
 * fire is indistinguishable from a process that is still running, and that is precisely
 * the failure above.
 *
 * Both are required rather than defaulted. A default would have to guess, and the two
 * questions the pool asks want opposite guesses — "is my occupant still running" wants
 * yes, "is this free slot really empty" wants no — so any single default is wrong for one
 * of them. Requiring them makes every construction site say which processes it means,
 * which is the one thing the version that shipped never had to do.
 *
 * @param alive whether a `:vappN` process is currently running for this slot index.
 * @param stop ends the process serving a slot index. Called before a slot with a live
 *   process is handed to a new occupant, and when a slot is released.
 */
class ProcessPool(
    private val capacity: Int,
    private val alive: (Int) -> Boolean,
    private val stop: (Int, String) -> Unit,
) {

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
     *
     * A slot is only ever handed out clean. Slots whose process has died are reclaimed
     * first, and a free slot that still has a process in it — the state that broke every
     * launch after the first — has that process stopped before the caller gets it.
     */
    @Synchronized
    fun acquire(occupant: Occupant, now: Long = System.currentTimeMillis()): ProcessSlot? {
        byOccupant[occupant]?.let { idx ->
            // An occupant that is still running keeps its slot; one whose process died
            // must not, or the guest is never started again and the slot never freed.
            if (alive(idx)) {
                slots[idx] = slots[idx].copy(lastUsedMillis = now)
                return slots[idx]
            }
            release(idx, "process gone")
        }
        reclaimDead()
        val free = slots.firstOrNull { it.isFree }
            ?: run {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "PROCESS_POOL_EXHAUSTED",
                    mapOf("capacity" to capacity.toString(), "requested" to occupant.toString()),
                )
                return null
            }
        // Free by the pool's own record, but the process may still be there: the record
        // is dropped synchronously and a process dies whenever the platform gets round to
        // it. Handing this slot over as-is is what produced SLOT_ALREADY_BOUND.
        if (alive(free.index)) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "SLOT_PROCESS_STALE",
                mapOf(
                    "slot" to free.processSuffix,
                    "requested" to occupant.packageName,
                    "detail" to "a process was still running in a slot the pool had freed",
                ),
            )
            runCatching { stop(free.index, "stale process in a free slot") }
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

    /**
     * Frees slots whose process is gone.
     *
     * This is the death observation the pool never had. Doing it at allocation time
     * rather than from a callback is deliberate: `ActivityManager` offers an app no
     * reliable notification of its own subprocess dying, and the one signal that is
     * always available — asking whether the pid is still there — is only needed at the
     * moment the answer matters.
     */
    private fun reclaimDead() {
        for (slot in slots.toList()) {
            if (slot.occupant == null) continue
            if (alive(slot.index)) continue
            release(slot.index, "process gone")
        }
    }

    /**
     * Gives up a slot, ending the process that serves it.
     *
     * The kill is the point. A released slot whose process survives is worse than one
     * that was never released: the pool believes it is free, hands it to the next app,
     * and that app cannot start because the surviving process is already grafted to
     * someone else. [stop] is called before the record is cleared so that a caller
     * implementing it can still see which slot it is ending.
     */
    @Synchronized
    fun release(index: Int, reason: String) {
        val slot = slots.getOrNull(index) ?: return
        runCatching { stop(index, reason) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "SLOT_STOP_FAILED",
                mapOf("slot" to slot.processSuffix, "error" to it.toString()),
            )
        }
        slot.occupant?.let { byOccupant.remove(it) }
        slots[index] = slot.copy(occupant = null)
        Diagnostics.info(
            DiagChannel.PROCESS, "PROCESS_RELEASED",
            mapOf("slot" to slot.processSuffix, "reason" to reason),
        )
    }

    /**
     * Gives up a slot **only if** it is still the one [vuid] holds.
     *
     * The check is the point. This is called from a message sent by a `:vappN` that has
     * just failed to graft, and a message describes the moment it was sent: by the time it
     * arrives the pool may have reclaimed that slot and started somebody else in it.
     * Releasing unconditionally would then kill a healthy process that had done nothing
     * except be next.
     *
     * Returns whether the slot was released, so a caller can say which happened.
     */
    @Synchronized
    fun releaseIf(index: Int, vuid: Int, reason: String): Boolean {
        val slot = slots.getOrNull(index) ?: return false
        if (slot.occupant?.vuid != vuid) {
            Diagnostics.info(
                DiagChannel.PROCESS, "SLOT_RELEASE_SKIPPED",
                mapOf(
                    "slot" to slot.processSuffix,
                    "asked" to vuid.toString(),
                    "serves" to (slot.occupant?.vuid?.toString() ?: "-"),
                    "reason" to reason,
                ),
            )
            return false
        }
        release(index, reason)
        return true
    }

    @Synchronized
    fun releaseAll(vuid: Int, reason: String) {
        slots.filter { it.occupant?.vuid == vuid }.forEach { release(it.index, reason) }
    }

    @Synchronized
    fun freeCount(): Int = slots.count { it.isFree }
}
