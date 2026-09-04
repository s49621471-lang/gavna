package com.unique.app.runtime

import android.app.Activity
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.LaunchInterceptor
import com.unique.core.vam.VirtualLaunchParams

/**
 * Base classes for the generated stub components.
 *
 * A stub is a component declared in UNIQUE's manifest that stands in for a virtual app's
 * component: the system will only ever launch something it knows about, so every virtual
 * Activity, Service, Provider and JobService is routed onto one of these.
 *
 * The behaviour common to all of them lives here rather than in the generated source, so
 * the generator emits one line per component and the logic stays reviewable.
 */

abstract class StubActivityBase(private val slot: Int) : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val routed = VirtualLaunchParams.from(intent)
        if (routed == null) {
            // Reached without routing information. This happens if the system restores a
            // stub from a saved task after UNIQUE was killed. Finishing immediately is
            // correct: there is no virtual activity to restore into.
            Diagnostics.warn(
                DiagChannel.LAUNCH, "STUB_ACTIVITY_UNROUTED",
                mapOf("slot" to slot.toString()),
            )
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        // Reaching here means the hand-off did not happen.
        //
        // On the normal path LaunchInterceptor rewrites the ClientTransaction before
        // ActivityThread instantiates anything, so the app's own Activity class is
        // created and this stub class is never constructed at all. If this code runs, the
        // interceptor was not installed or the transaction shape was not recognised -
        // both of which are reported separately on the LAUNCH channel.
        //
        // Finishing is the right response: an empty stub window would look to the user
        // like the app itself failed to draw.
        Diagnostics.error(
            DiagChannel.LAUNCH, "ACTIVITY_HANDOFF_DID_NOT_HAPPEN",
            mapOf(
                "slot" to slot.toString(),
                "vuid" to routed.vuid.toString(),
                "component" to (routed.targetComponent ?: "<launcher>"),
                "interceptorInstalled" to LaunchInterceptor.isInstalled.toString(),
            ),
        )
        super.onCreate(savedInstanceState)
        finish()
    }
}

abstract class StubServiceBase(private val slot: Int) : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        Diagnostics.warn(
            DiagChannel.PROCESS, "STUB_SERVICE_BIND_NOT_IMPLEMENTED",
            mapOf("slot" to slot.toString()),
        )
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diagnostics.warn(
            DiagChannel.PROCESS, "STUB_SERVICE_START_NOT_IMPLEMENTED",
            mapOf("slot" to slot.toString()),
        )
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

abstract class StubJobServiceBase(private val slot: Int) : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Diagnostics.warn(
            DiagChannel.PROCESS, "STUB_JOB_NOT_IMPLEMENTED",
            mapOf(
                "slot" to slot.toString(),
                "jobId" to (params?.jobId?.toString() ?: "?"),
                "vuid" to (params?.jobId?.let { com.unique.core.vam.StubRouter.jobOwner(it).toString() } ?: "?"),
            ),
        )
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

/**
 * Stub content provider.
 *
 * Providers are special: the system instantiates them during process start, before any
 * other component. That makes them the earliest point at which UNIQUE can run code in a
 * virtual app process, which is where the engine will eventually install its hooks.
 */
abstract class StubProviderBase(private val slot: Int) : ContentProvider() {

    override fun onCreate(): Boolean {
        Diagnostics.info(
            DiagChannel.PROCESS, "STUB_PROVIDER_CREATED",
            mapOf("slot" to slot.toString()),
        )
        return true
    }

    // TODO(phase-3): forward these to the virtual app's provider through :server.
    // Until then they return empty rather than throwing, so a caller probing an authority
    // gets a well-formed "nothing here" instead of a crash.
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
