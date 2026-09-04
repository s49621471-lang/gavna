package com.unique.core.vam

import android.content.ComponentName
import android.content.Intent
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Maps a guest's services onto the host's stub services.
 *
 * The mapping has to exist because `CreateServiceData` — the message that tells
 * `ActivityThread` to instantiate a service — carries only the stub's `ServiceInfo`.
 * There is nothing in it that says which virtual service it stands for, unlike the
 * activity path where the `Intent` travels alongside. So the *stub's identity* is the
 * key, and each concurrently-running virtual service needs a stub of its own.
 *
 * The table lives in the virtual process. That is sufficient for the case that matters —
 * a guest starting its own service — because the outbound call and the inbound
 * `CREATE_SERVICE` happen in the same process. Starting a guest's service from another
 * process needs `:server` to own the table, and is not implemented; such a call is
 * refused with a diagnostic rather than silently starting the wrong service.
 */
object VirtualServiceRouter {

    /** Must match `STUB_SERVICES_PER_PROCESS` in app/build.gradle.kts. */
    const val STUBS_PER_PROCESS = 7

    /**
     * The stub each slot keeps back for starting a process out of band.
     *
     * Two callers, one reason: a process cannot be brought into existence by wishing for
     * one, and neither of them may put a window on screen. A cold broadcast starts it to
     * wake a dead guest (§6.3.1); a cross-process provider acquisition starts it to graft
     * the target *before* the platform's ten-second provider-publish timeout applies
     * (§6.4.0).
     *
     * Reserved structurally rather than by convention. [reserve] never hands it out, so a
     * guest service can never land on it — which is what lets [resolve] returning null be
     * a reliable signal that a `CREATE_SERVICE` is one of those starts rather than a lost
     * reservation. An agreement of the "index 5 is probably free" kind would hold only
     * until a guest ran six services.
     */
    const val COLD_BROADCAST_STUB_INDEX = STUBS_PER_PROCESS - 1

    /** Stubs available to a guest's own services. */
    private const val GUEST_STUBS = STUBS_PER_PROCESS - 1

    private data class Binding(val stubIndex: Int, val entry: ComponentEntry, val vuid: Int)

    private val byRealClass = LinkedHashMap<String, Binding>()
    private val byStubIndex = HashMap<Int, Binding>()

    @Volatile private var slot: Int = -1

    @Synchronized
    fun bindSlot(slotIndex: Int) {
        if (slot != slotIndex) {
            slot = slotIndex
            byRealClass.clear()
            byStubIndex.clear()
        }
    }

    /**
     * Reserves a stub for [entry], or returns the one already reserved for it.
     *
     * Returns null when every stub in this slot is in use. The caller refuses the start
     * and says so: quietly reusing a stub would deliver one service's lifecycle callbacks
     * to another, which is worse than a visible failure.
     */
    @Synchronized
    fun reserve(entry: ComponentEntry, vuid: Int): String? {
        byRealClass[entry.className]?.let {
            return StubRouter.stubService(slot, it.stubIndex)
        }
        val free = (0 until GUEST_STUBS).firstOrNull { it !in byStubIndex }
        if (free == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "SERVICE_STUB_POOL_EXHAUSTED",
                mapOf(
                    "slot" to slot.toString(),
                    "service" to entry.className,
                    "capacity" to GUEST_STUBS.toString(),
                ),
            )
            return null
        }
        val binding = Binding(free, entry, vuid)
        byRealClass[entry.className] = binding
        byStubIndex[free] = binding
        Diagnostics.info(
            DiagChannel.PROCESS, "SERVICE_STUB_RESERVED",
            mapOf(
                "slot" to slot.toString(),
                "stub" to free.toString(),
                "service" to entry.className,
                "vuid" to vuid.toString(),
            ),
        )
        return StubRouter.stubService(slot, free)
    }

    /** The virtual service a stub class stands for, or null when the stub is unassigned. */
    @Synchronized
    fun resolve(stubClassName: String): ComponentEntry? {
        val (_, index) = StubRouter.parseStubService(stubClassName) ?: return null
        return byStubIndex[index]?.entry
    }

    @Synchronized
    fun release(stubClassName: String) {
        val (_, index) = StubRouter.parseStubService(stubClassName) ?: return
        val binding = byStubIndex.remove(index) ?: return
        byRealClass.remove(binding.entry.className)
    }

    @Synchronized
    fun reset() {
        byRealClass.clear()
        byStubIndex.clear()
    }

    /**
     * Rewrites an outbound service intent onto a stub, or returns null when it is not for
     * a virtual service and should be left alone.
     */
    @Synchronized
    fun outbound(
        hostPackage: String,
        intent: Intent,
        params: VirtualLaunchParams,
        entry: ComponentEntry,
    ): Intent? {
        require(entry.kind == ComponentKind.SERVICE) { "${entry.className} is not a service" }
        val stub = reserve(entry, params.vuid) ?: return null
        return Intent(intent).apply {
            component = ComponentName(hostPackage, stub)
            params.copy(
                targetComponent = entry.className,
                kind = VirtualComponentKind.SERVICE,
            ).writeTo(this)
        }
    }
}
