package com.unique.app.runtime

import android.app.Activity
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.LaunchInterceptor
import com.unique.core.vam.VirtualColdBroadcast
import com.unique.core.vam.VirtualComponentKind
import com.unique.core.vam.VirtualDiagnostics
import com.unique.core.vam.VirtualJobDispatcher
import com.unique.core.vam.VirtualLaunchParams
import com.unique.core.vam.VirtualPermissionSync
import com.unique.core.vam.VirtualProviderBridge
import com.unique.core.vam.VirtualProviderHost

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

/**
 * Base for the generated stub services.
 *
 * For a guest's own service this class is never constructed: [LaunchInterceptor] replaces
 * the stub's `ServiceInfo` before `ActivityThread` instantiates anything, so the guest's
 * `Service` runs instead. Reaching here that way means the hand-off did not happen, and
 * the reasons are reported separately on the PROCESS channel.
 *
 * The one deliberate exception is cold broadcast delivery, which starts the stub reserved
 * at `VirtualServiceRouter.COLD_BROADCAST_STUB_INDEX` precisely so that this class runs:
 * there is no guest service to swap in, only a process that needs to exist.
 */
abstract class StubServiceBase(private val slot: Int) : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        Diagnostics.error(
            DiagChannel.PROCESS, "STUB_SERVICE_BIND_NOT_ROUTED",
            mapOf(
                "slot" to slot.toString(),
                "stub" to javaClass.name,
                "requested" to (VirtualLaunchParams.from(intent)?.targetComponent ?: "-"),
            ),
        )
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val params = VirtualLaunchParams.from(intent)

        // A cold broadcast delivery. Unlike every other start that reaches this class,
        // this one is *meant* to: the stub exists to bring the process up, and the guest
        // has no service to swap in. See VirtualBroadcastRouter.
        if (params != null && params.kind == VirtualComponentKind.RECEIVER) {
            VirtualColdBroadcast.deliver(this, params, intent)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // A process being warmed for a provider acquisition. Same reason as above — the
        // stub exists so the process does — but here nothing is delivered: the graft *is*
        // the work, and it has to happen outside ActivityManager's ten-second budget for
        // publishing a cold-started process's providers.
        if (params != null && params.kind == VirtualComponentKind.PROVIDER) {
            VirtualProviderHost.warm(this, params)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Diagnostics.error(
            DiagChannel.PROCESS, "STUB_SERVICE_START_NOT_ROUTED",
            mapOf(
                "slot" to slot.toString(),
                "stub" to javaClass.name,
                "requested" to (params?.targetComponent ?: "-"),
            ),
        )
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/**
 * Stub job service.
 *
 * Unlike the activity and service stubs this class *is* on the success path: the system
 * binds it, and it hands the job to the guest's own `JobService`. A job fires long after
 * the process that scheduled it has gone, so there is nothing to intercept ahead of time —
 * the routing record written at schedule time is what says whose job this is.
 */
abstract class StubJobServiceBase(private val slot: Int) : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean =
        when (val dispatch = VirtualJobDispatcher.start(this, params)) {
            // The guest ran. `stillRunning` false is the ordinary case - work finished
            // synchronously - and is not a failure, which is why it is not reported here.
            is VirtualJobDispatcher.Dispatch.Ran -> dispatch.stillRunning
            is VirtualJobDispatcher.Dispatch.NotReached -> {
                Diagnostics.error(
                    DiagChannel.PROCESS, "STUB_JOB_NOT_ROUTED",
                    mapOf(
                        "slot" to slot.toString(),
                        "hostJobId" to (params?.jobId?.toString() ?: "?"),
                        "vuid" to (params?.jobId
                            ?.let { com.unique.core.vam.StubRouter.jobOwner(it).toString() } ?: "?"),
                        "reason" to dispatch.reason,
                    ),
                )
                false
            }
        }

    override fun onStopJob(params: JobParameters?): Boolean = VirtualJobDispatcher.stop(params)
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

    /**
     * The one method a caller may invoke before this process is anything in particular.
     *
     * Acquiring this provider is what started the process; `bind` is what makes it *be* a
     * given instance and tells the caller which authorities it may then use. See
     * [VirtualProviderHost].
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == VirtualProviderBridge.METHOD_BIND) {
            return VirtualProviderHost.bind(this.context ?: return null, this, extras)
        }
        // Answered whether or not this process is serving anything yet: the buffers of a
        // slot that failed to bind are exactly the ones worth reading.
        if (method == VirtualDiagnostics.METHOD_SNAPSHOT) {
            return VirtualDiagnostics.snapshotBundle(":vapp$slot")
        }
        if (method == VirtualPermissionSync.METHOD_SET_PERMISSION) {
            return VirtualPermissionSync.apply(extras)
        }
        return VirtualProviderHost.route(arg?.let { Uri.parse(it).authority }, "call")
            ?.call(method, arg, extras)
    }

    // Everything below is pure routing. The stub owns no data of its own; it exists
    // because the platform will only publish a provider that a manifest declares, and a
    // guest's manifest is not one the platform has read.

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = VirtualProviderHost.route(uri.authority, "query")
        ?.query(uri, projection, selection, selectionArgs, sortOrder)

    override fun getType(uri: Uri): String? =
        VirtualProviderHost.route(uri.authority, "getType")?.getType(uri)

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        VirtualProviderHost.route(uri.authority, "insert")?.insert(uri, values)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        VirtualProviderHost.route(uri.authority, "delete")
            ?.delete(uri, selection, selectionArgs) ?: 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = VirtualProviderHost.route(uri.authority, "update")
        ?.update(uri, values, selection, selectionArgs) ?: 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        VirtualProviderHost.route(uri.authority, "openFile")?.openFile(uri, mode)

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
        VirtualProviderHost.route(uri.authority, "getStreamTypes")
            ?.getStreamTypes(uri, mimeTypeFilter)

    override fun openTypedAssetFile(
        uri: Uri, mimeTypeFilter: String, opts: Bundle?,
    ): AssetFileDescriptor? = VirtualProviderHost.route(uri.authority, "openTypedAssetFile")
        ?.openTypedAssetFile(uri, mimeTypeFilter, opts)
}
