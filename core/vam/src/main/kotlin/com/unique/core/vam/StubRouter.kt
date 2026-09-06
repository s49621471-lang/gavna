package com.unique.core.vam

import android.os.Bundle

/**
 * Translates between a virtual app's real components and the host's stub components.
 *
 * This is the core of activity virtualization: the system only ever launches components
 * declared in UNIQUE's own manifest, so every virtual `Intent` is wrapped into a stub
 * `Intent` on the way out and unwrapped on the way in.
 *
 * Kept free of platform-service calls so the wrapping rules are unit-testable: an
 * asymmetry between [wrap] and [unwrap] means an app launches the wrong screen, or the
 * wrong instance's screen, which is exactly the class of bug that is miserable to debug
 * on a device.
 */
object StubRouter {

    // Launch parameters are carried by VirtualLaunchParams, which is the single
    // marshalling contract for them. These two remain because notification routing is a
    // different problem with a different lifetime - a tap arrives long after the launch.
    const val EXTRA_VUID = "_unique_vuid"
    const val EXTRA_COMPONENT = "_unique_component"

    /** Stub activity naming, generated to match; see tools/stubgen. */
    fun stubActivity(processIndex: Int, launchMode: Int, affinityIndex: Int): String =
        "com.unique.stub.ActivityStub_p${processIndex}_m${launchMode}_a$affinityIndex"

    /**
     * Stub services are indexed as well as slotted.
     *
     * `CreateServiceData` carries only the stub's `ServiceInfo`, with nothing that says
     * which virtual service it stands for, so the stub's identity has to be the mapping
     * key - which means one stub per concurrently-running virtual service.
     */
    fun stubService(processIndex: Int, serviceIndex: Int): String =
        "com.unique.stub.ServiceStub_p${processIndex}_s$serviceIndex"

    /** Recovers (slot, index) from a stub service class name, or null if it is not ours. */
    fun parseStubService(className: String): Pair<Int, Int>? {
        val name = className.substringAfterLast('.')
        val match = STUB_SERVICE.matchEntire(name) ?: return null
        val (slot, index) = match.destructured
        return slot.toInt() to index.toInt()
    }

    private val STUB_SERVICE = Regex("""ServiceStub_p(\d+)_s(\d+)""")

    fun stubProvider(processIndex: Int): String = "com.unique.stub.ProviderStub_p$processIndex"

    fun stubJobService(processIndex: Int): String = "com.unique.stub.JobStub_p$processIndex"

    /**
     * The host receiver a guest's broadcast `PendingIntent` is pointed at.
     *
     * One, not one per slot, and it lives in UNIQUE's *main* process on purpose. A
     * `PendingIntent` outlives the process that created it — that is what it is for — so
     * the thing that fires minutes or days later must be a component that exists whether
     * or not any `:vappN` is running. UNIQUE's main process is where the broadcast router
     * already lives and where a dead guest is woken from.
     *
     * Before this, an explicit broadcast `PendingIntent` naming a guest receiver was left
     * pointing at a component the platform has never installed, and reported as
     * unsupported. It fired 26 times in one device run, all of them Play services'
     * `AppMeasurementReceiver` re-arming itself, and every one of them reached nothing.
     */
    fun stubBroadcastReceiver(): String = "com.unique.app.runtime.BroadcastStub"

    /** The guest's real receiver class, carried on the stub intent. */
    const val EXTRA_RECEIVER = "_unique_receiver"

    /** The guest's package, carried on the stub intent so the router need not guess. */
    const val EXTRA_PACKAGE = "_unique_package"

    /**
     * Namespaces a virtual job id into the host's id space.
     *
     * Virtual apps choose their own job ids and two instances of the same app will choose
     * the same one; without namespacing, instance 2 scheduling a job would cancel
     * instance 1's. 20 bits leaves room for the ids apps actually use while keeping the
     * result positive.
     */
    fun hostJobId(vuid: Int, virtualJobId: Int): Int =
        ((vuid and 0x7FF) shl 20) or (virtualJobId and 0xFFFFF)

    fun virtualJobId(hostJobId: Int): Int = hostJobId and 0xFFFFF
    fun jobOwner(hostJobId: Int): Int = (hostJobId ushr 20) and 0x7FF

    /**
     * Extras used to route a notification tap back to the instance that posted it.
     *
     * Without this, two instances of the same app produce notifications whose content
     * intents are indistinguishable and taps open the wrong one.
     */
    fun notificationRouting(vuid: Int, packageName: String): Bundle = Bundle().apply {
        putInt(EXTRA_VUID, vuid)
        putString(EXTRA_COMPONENT, packageName)
    }

    /**
     * Namespaces a notification channel so two instances do not share settings.
     * The user sees one channel per instance, which is what "separate installs" means.
     */
    fun hostChannelId(vuid: Int, packageName: String, channelId: String): String =
        "vu$vuid:$packageName:$channelId"

    /**
     * Namespaces a notification id, for the same reason job ids are namespaced.
     *
     * Apps pick small constants — `1`, `100`, `R.id.something` — and two instances of one
     * app pick the same ones, so instance 2 posting would replace instance 1's
     * notification. Same 11/20 bit split as [hostJobId]; the result stays positive.
     */
    fun hostNotificationId(vuid: Int, virtualId: Int): Int =
        ((vuid and 0x7FF) shl 20) or (virtualId and 0xFFFFF)

    fun virtualNotificationId(hostId: Int): Int = hostId and 0xFFFFF
    fun notificationOwner(hostId: Int): Int = (hostId ushr 20) and 0x7FF

    fun parseChannelId(hostChannelId: String): Triple<Int, String, String>? {
        if (!hostChannelId.startsWith("vu")) return null
        val parts = hostChannelId.split(':', limit = 3)
        if (parts.size != 3) return null
        val vuid = parts[0].removePrefix("vu").toIntOrNull() ?: return null
        return Triple(vuid, parts[1], parts[2])
    }
}
