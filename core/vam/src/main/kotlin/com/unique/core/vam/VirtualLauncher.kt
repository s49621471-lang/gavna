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
 */
class VirtualLauncher(
    private val hostPackage: String,
    slotCount: Int,
) {
    private val pool = ProcessPool(slotCount)

    fun snapshot() = pool.snapshot()

    fun release(vuid: Int, reason: String) = pool.releaseAll(vuid, reason)

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
