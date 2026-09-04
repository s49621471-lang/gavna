package com.unique.core.vam

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import com.unique.core.common.apk.ComponentEntry

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

    const val EXTRA_INTENT = "_unique_intent"
    const val EXTRA_VUID = "_unique_vuid"
    const val EXTRA_COMPONENT = "_unique_component"
    const val EXTRA_PROCESS = "_unique_process"
    const val EXTRA_THEME = "_unique_theme"

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
     * Wraps a virtual intent into one the system will accept.
     *
     * The original intent is carried as a parcelled extra rather than being mutated, so
     * that [unwrap] returns an object indistinguishable from what the app passed in —
     * including its flags, categories, clip data and selector.
     */
    fun wrap(
        hostPackage: String,
        stubClassName: String,
        vuid: Int,
        target: ComponentEntry,
        original: Intent,
    ): Intent = Intent().apply {
        component = ComponentName(hostPackage, stubClassName)
        // Task identity must come from the stub, or the system groups every virtual app
        // into one task. The affinity is chosen by the caller when it picks the stub.
        flags = original.flags
        putExtra(EXTRA_INTENT, Intent(original))
        putExtra(EXTRA_VUID, vuid)
        putExtra(EXTRA_COMPONENT, target.className)
        putExtra(EXTRA_PROCESS, target.processName)
        if (target.theme != 0) putExtra(EXTRA_THEME, target.theme)
    }

    /** Recovers what the virtual app originally asked for. Null when this is not ours. */
    fun unwrap(stubIntent: Intent?): Unwrapped? {
        val intent = stubIntent ?: return null
        if (!intent.hasExtra(EXTRA_INTENT)) return null
        @Suppress("DEPRECATION")
        val original = intent.getParcelableExtra<Intent>(EXTRA_INTENT) ?: return null
        val vuid = intent.getIntExtra(EXTRA_VUID, -1)
        val component = intent.getStringExtra(EXTRA_COMPONENT) ?: return null
        if (vuid < 0) return null
        return Unwrapped(
            vuid = vuid,
            className = component,
            processName = intent.getStringExtra(EXTRA_PROCESS),
            theme = intent.getIntExtra(EXTRA_THEME, 0),
            intent = original,
        )
    }

    data class Unwrapped(
        val vuid: Int,
        val className: String,
        val processName: String?,
        val theme: Int,
        val intent: Intent,
    )

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
