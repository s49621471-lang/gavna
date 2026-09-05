package com.unique.core.vam

import android.content.Context
import android.content.Intent
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vprocess.Occupant
import com.unique.core.vprocess.ProcessPool

sealed interface LaunchResult {
    data class Started(val params: VirtualLaunchParams, val activity: String) : LaunchResult
    data class Failed(val code: String, val message: String) : LaunchResult
}

/**
 * Starts a virtual app.
 *
 * Runs in the UI process: it picks a process slot, builds the stub intent and hands it to
 * the platform. Everything after that happens inside the target `:vappN` process, where
 * [LaunchInterceptor] rewrites the transaction.
 *
 * @param slotCount must match `vappProcessCount` in the root build script.
 * @param context UNIQUE's own application context. Required, and not defaulted to null:
 *   without it the pool cannot end a `:vappN` process, and a pool that cannot do that
 *   hands the next app a slot someone else is still grafted into — which is how a device
 *   run ended with three apps in a row refusing to launch.
 */
class VirtualLauncher(
    private val hostPackage: String,
    slotCount: Int,
    context: Context,
) {
    private val processes = SlotProcesses(context.applicationContext ?: context, hostPackage)

    private val pool = ProcessPool(
        capacity = slotCount,
        alive = processes::alive,
        stop = processes::stop,
    )

    fun snapshot() = pool.snapshot()

    /**
     * Gives up every slot held by an instance, ending the processes serving them.
     *
     * Called when an instance is removed or updated. Before the pool ended the process
     * itself this returned with the guest still running, and the slot it had been using
     * was unusable by anything else for as long as UNIQUE stayed up.
     */
    fun release(vuid: Int, reason: String) = pool.releaseAll(vuid, reason)

    /**
     * Leases a slot for a virtual process started by something other than [launch].
     *
     * Cold broadcast delivery needs a process without an activity to put on screen, so it
     * cannot go through [launch]. The lease is the same one: [ProcessPool.acquire] is
     * idempotent per occupant, so a guest that is already running keeps the slot it has
     * and the broadcast is delivered into the live process rather than a second one.
     *
     * Returns null when the pool is full, which the caller must treat as "not delivered"
     * rather than evicting a running app.
     */
    fun acquireSlot(vuid: Int, packageName: String, processName: String): Int? =
        pool.acquire(Occupant(vuid, packageName, processName))?.index

    fun launch(
        context: Context,
        vuid: Int,
        packageName: String,
        versionCode: Long,
        manifest: ApkManifest,
        targetActivity: String? = null,
    ): LaunchResult {
        val entry = AppBootstrap.resolveActivity(manifest, targetActivity)
            ?: return LaunchResult.Failed(
                "NO_LAUNCHABLE_ACTIVITY",
                targetActivity?.let { "$it is not an activity of $packageName" }
                    ?: "$packageName declares no launcher activity.",
            )

        val occupant = Occupant(vuid, packageName, entry.processName)
        val slot = pool.acquire(occupant)
            ?: return LaunchResult.Failed(
                "PROCESS_POOL_EXHAUSTED",
                "Every virtual process slot is in use. Stop an app and try again.",
            )

        val params = VirtualLaunchParams(
            vuid = vuid,
            packageName = packageName,
            versionCode = versionCode,
            targetComponent = entry.className,
            processName = entry.processName,
            slot = slot.index,
        )
        val intent = VirtualLaunchIntent.build(hostPackage, params, entry.launchMode)

        return try {
            context.startActivity(intent)
            Diagnostics.info(
                DiagChannel.LAUNCH, "LAUNCH_REQUESTED",
                mapOf(
                    "package" to packageName,
                    "vuid" to vuid.toString(),
                    "activity" to entry.className,
                    "slot" to slot.processSuffix,
                    "launchMode" to entry.launchMode.toString(),
                ),
            )
            LaunchResult.Started(params, entry.className)
        } catch (t: Throwable) {
            pool.release(slot.index, "launch failed")
            LaunchResult.Failed("START_ACTIVITY_FAILED", t.toString())
        }
    }
}
