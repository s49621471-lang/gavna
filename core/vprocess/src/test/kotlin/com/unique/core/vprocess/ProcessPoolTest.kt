package com.unique.core.vprocess

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pool that decides which `:vappN` process an app gets.
 *
 * Every test here is about the one thing the pool got wrong on a real phone: a slot's
 * record and the process behind it are two facts that can disagree, and the pool used to
 * believe the record. The consequence was not subtle — after Gemini ran in slot 0, the
 * next three apps were each assigned slot 0, each found the surviving process still
 * grafted to Gemini, and each refused to start. On the device that reads as "UNIQUE
 * stopped launching anything".
 *
 * The fake below is the whole point of the injected [ProcessPool.alive] and
 * [ProcessPool.stop]: process lifetime is exactly the part that cannot be exercised on a
 * JVM, so it is the part that is substituted.
 */
class ProcessPoolTest {

    /** A stand-in for the `:vappN` processes, with the same lifecycle and none of Android. */
    private class Processes {
        val running = HashSet<Int>()
        val stopped = ArrayList<Pair<Int, String>>()

        fun start(slot: Int) = running.add(slot)

        fun pool(capacity: Int = 3) = ProcessPool(
            capacity = capacity,
            alive = { it in running },
            stop = { slot, reason ->
                stopped += slot to reason
                running.remove(slot)
            },
        )
    }

    private fun occupant(name: String, vuid: Int = 0) = Occupant(vuid, name, name)

    @Test fun `a slot is assigned and found again by the same occupant`() {
        val processes = Processes()
        val pool = processes.pool()
        val slot = pool.acquire(occupant("com.a"))!!
        processes.start(slot.index)

        assertThat(slot.index).isEqualTo(0)
        assertThat(slot.processSuffix).isEqualTo(":vapp0")
        assertThat(pool.acquire(occupant("com.a"))!!.index).isEqualTo(0)
        assertThat(pool.freeCount()).isEqualTo(2)
    }

    @Test fun `two occupants get two slots`() {
        val processes = Processes()
        val pool = processes.pool()
        val first = pool.acquire(occupant("com.a"))!!
        processes.start(first.index)
        val second = pool.acquire(occupant("com.b"))!!

        assertThat(second.index).isNotEqualTo(first.index)
    }

    @Test fun `two instances of one app are two occupants`() {
        // The whole point of instances: u0 and u1 have different data directories, so
        // collapsing them into one process would give one instance the other's storage.
        val processes = Processes()
        val pool = processes.pool()
        val first = pool.acquire(occupant("com.a", vuid = 0))!!
        processes.start(first.index)
        val second = pool.acquire(occupant("com.a", vuid = 1))!!

        assertThat(second.index).isNotEqualTo(first.index)
    }

    @Test fun `releasing a slot ends the process serving it`() {
        // The bug, in one assertion. Before this, release() cleared the record and left
        // the process running, and the next app to be given the slot could not start.
        val processes = Processes()
        val pool = processes.pool()
        val slot = pool.acquire(occupant("com.a"))!!
        processes.start(slot.index)

        pool.release(slot.index, "removed")

        assertThat(processes.running).isEmpty()
        assertThat(processes.stopped).containsExactly(0 to "removed")
        assertThat(pool.freeCount()).isEqualTo(3)
    }

    @Test fun `a reassigned slot never carries the previous app's process`() {
        val processes = Processes()
        val pool = processes.pool()
        val first = pool.acquire(occupant("com.bard"))!!
        processes.start(first.index)
        pool.releaseAll(vuid = 0, reason = "removed")

        val second = pool.acquire(occupant("clear.una"))!!

        assertThat(second.index).isEqualTo(first.index)
        assertThat(processes.running).isEmpty()
    }

    @Test fun `a process still alive in a free slot is ended before the slot is handed over`() {
        // The residual case: the record says free and the process is there anyway, which
        // is what the platform's own process list lagging looks like. The slot must still
        // be handed over clean.
        val processes = Processes()
        val pool = processes.pool()
        processes.start(0)

        val slot = pool.acquire(occupant("com.a"))!!

        assertThat(slot.index).isEqualTo(0)
        assertThat(processes.stopped).hasSize(1)
        assertThat(processes.running).isEmpty()
    }

    @Test fun `a slot whose process died is reclaimed rather than leaked`() {
        // Nothing ever called release() on a crash or a low-memory kill, so a crashed
        // guest kept its slot until UNIQUE was restarted and the pool ran out with every
        // process dead.
        val processes = Processes()
        val pool = processes.pool(capacity = 1)
        val slot = pool.acquire(occupant("com.a"))!!
        processes.start(slot.index)
        processes.running.remove(slot.index)  // the guest crashed

        val next = pool.acquire(occupant("com.b"))

        assertThat(next).isNotNull()
        assertThat(next!!.index).isEqualTo(0)
    }

    @Test fun `an occupant whose process died is given a slot again`() {
        val processes = Processes()
        val pool = processes.pool()
        val first = pool.acquire(occupant("com.a"))!!
        processes.start(first.index)
        processes.running.remove(first.index)

        val again = pool.acquire(occupant("com.a"))

        assertThat(again).isNotNull()
        assertThat(pool.find(occupant("com.a"))).isNotNull()
    }

    @Test fun `a full pool of live processes refuses rather than evicting`() {
        // Evicting would stop an app the user is looking at, to start one they asked for.
        // Refusing is the honest answer and the caller reports it.
        val processes = Processes()
        val pool = processes.pool(capacity = 2)
        for (name in listOf("com.a", "com.b")) {
            processes.start(pool.acquire(occupant(name))!!.index)
        }

        assertThat(pool.acquire(occupant("com.c"))).isNull()
        assertThat(processes.stopped).isEmpty()
        assertThat(processes.running).hasSize(2)
    }

    @Test fun `releaseAll frees only the instance it names`() {
        val processes = Processes()
        val pool = processes.pool()
        processes.start(pool.acquire(occupant("com.a", vuid = 0))!!.index)
        val other = pool.acquire(occupant("com.a", vuid = 1))!!
        processes.start(other.index)

        pool.releaseAll(vuid = 0, reason = "removed")

        assertThat(pool.find(occupant("com.a", vuid = 0))).isNull()
        assertThat(pool.find(occupant("com.a", vuid = 1))).isNotNull()
        assertThat(processes.running).containsExactly(other.index)
    }

    @Test fun `a stop that throws does not leave the slot occupied`() {
        // Killing another process can fail for reasons UNIQUE does not control. Losing
        // the slot to that would be the same leak in a new place.
        val pool = ProcessPool(
            capacity = 1,
            alive = { false },
            stop = { _, _ -> error("kill refused") },
        )
        val slot = pool.acquire(occupant("com.a"))!!

        pool.release(slot.index, "removed")

        assertThat(pool.freeCount()).isEqualTo(1)
    }

    @Test fun `an occupied slot is not free`() {
        val processes = Processes()
        val pool = processes.pool(capacity = 2)
        processes.start(pool.acquire(occupant("com.a"))!!.index)
        assertThat(pool.freeCount()).isEqualTo(1)
    }
}
