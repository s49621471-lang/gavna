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

    fun stubService(processIndex: Int): String = "com.unique.stub.ServiceStub_p$processIndex"

    fun stubProvider(processIndex: Int): String = "com.unique.stub.ProviderStub_p$processIndex"

    fun stubJobService(processIndex: Int): String = "com.unique.stub.JobStub_p$processIndex"

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

    fun parseChannelId(hostChannelId: String): Triple<Int, String, String>? {
        if (!hostChannelId.startsWith("vu")) return null
        val parts = hostChannelId.split(':', limit = 3)
        if (parts.size != 3) return null
        val vuid = parts[0].removePrefix("vu").toIntOrNull() ?: return null
        return Triple(vuid, parts[1], parts[2])
    }
}
