package com.unique.core.vam

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics

/**
 * Runs a guest's `JobService` when the system starts the stub that stands in for it.
 *
 * A job fires long after the process that scheduled it has gone, so this runs in a
 * `:vappN` that may have just started with nothing grafted into it. The routing record
 * written at schedule time carries everything needed to bootstrap: which instance, which
 * package, which class.
 *
 * ## Why the guest's service borrows the stub's engine
 *
 * A `JobService` reports completion through `jobFinished`, which goes to a private
 * `JobServiceEngine` the platform installs when it binds the service. The guest's
 * instance is never bound — the system bound the stub — so its engine is null and
 * `jobFinished` would throw inside the app's own code. Handing it the stub's engine makes
 * the guest's completion call reach the real system callback, which is the only way a
 * long-running job can ever finish.
 */
object VirtualJobDispatcher {

    private val running = HashMap<Int, JobService>()

    /**
     * What happened to a fired job.
     *
     * "The guest returned false" and "UNIQUE could not reach the guest" are the same value
     * to the platform and must not be the same event in the log: a job that completed
     * synchronously - the common case, and what `jobFinished` before returning means -
     * would otherwise be reported exactly like a routing failure.
     */
    sealed interface Dispatch {
        /** The guest's `onStartJob` ran; [stillRunning] is what it returned. */
        data class Ran(val stillRunning: Boolean) : Dispatch
        /** The guest was never reached. The reason is already reported. */
        data class NotReached(val reason: String) : Dispatch
    }

    @Synchronized
    fun start(stub: JobService, params: JobParameters?): Dispatch {
        val hostJobId = params?.jobId ?: return Dispatch.NotReached("no JobParameters")
        val record = VirtualJobStore(VirtualPathModel(HostPaths.filesRoot(stub))).get(hostJobId)
        if (record == null) {
            // The routing record is gone: UNIQUE's data was cleared, or the job outlived
            // the instance. Nothing can run, and saying which job is what makes it
            // diagnosable rather than a job that silently does nothing forever.
            Diagnostics.warn(
                DiagChannel.PROCESS, "JOB_RECORD_MISSING",
                mapOf("hostJobId" to hostJobId.toString()),
            )
            return Dispatch.NotReached("no routing record for $hostJobId")
        }

        val ready = ensureBootstrapped(stub, record)
        if (ready == null) {
            Diagnostics.error(
                DiagChannel.PROCESS, "JOB_BOOTSTRAP_FAILED",
                mapOf(
                    "hostJobId" to hostJobId.toString(),
                    "package" to record.packageName,
                    "service" to record.serviceClass,
                ),
            )
            return Dispatch.NotReached("bootstrap failed")
        }

        val guest = instantiate(ready, stub, record)
            ?: return Dispatch.NotReached("could not instantiate ${record.serviceClass}")
        val guestParams = VirtualJobRewriter.toGuest(params, record.virtualJobId) ?: params

        return runCatching {
            val stillRunning = guest.onStartJob(guestParams)
            if (stillRunning) running[hostJobId] = guest
            Diagnostics.info(
                DiagChannel.PROCESS, "JOB_STARTED",
                mapOf(
                    "service" to record.serviceClass,
                    "virtualJobId" to record.virtualJobId.toString(),
                    "hostJobId" to hostJobId.toString(),
                    "stillRunning" to stillRunning.toString(),
                ),
            )
            Dispatch.Ran(stillRunning)
        }.getOrElse {
            Diagnostics.error(
                DiagChannel.PROCESS, "JOB_START_THREW",
                mapOf("service" to record.serviceClass, "error" to it.toString()),
            )
            Dispatch.NotReached("onStartJob threw: $it")
        }
    }

    @Synchronized
    fun stop(params: JobParameters?): Boolean {
        val hostJobId = params?.jobId ?: return false
        val guest = running.remove(hostJobId) ?: return false
        // The routing store is UNIQUE's, not the instance's, and `start` reads it from
        // UNIQUE's root. This read used `guest.filesDir` — the guest service, deliberately
        // attached to the guest's own Context a few lines below — which resolves into the
        // *instance's* directory, where no store has ever been written. It found no record
        // and stopped the job with the host's own parameters, which is the wrong job id
        // seen from inside the guest. The root is the same one `start` used.
        val record = VirtualJobStore(
            VirtualPathModel(HostPaths.known ?: guest.filesDir?.absolutePath ?: return false)
        ).get(hostJobId)
        val guestParams = record?.let { VirtualJobRewriter.toGuest(params, it.virtualJobId) } ?: params
        return runCatching { guest.onStopJob(guestParams) }.getOrElse {
            Diagnostics.error(
                DiagChannel.PROCESS, "JOB_STOP_THREW",
                mapOf("hostJobId" to hostJobId.toString(), "error" to it.toString()),
            )
            false
        }
    }

    private fun ensureBootstrapped(
        stub: JobService,
        record: VirtualJob,
    ): AppBootstrap.Result.Ready? {
        AppBootstrap.current?.let { existing ->
            // A slot serves one instance for its lifetime; a job for another one landing
            // here means the routing is wrong, and running it would use the wrong data
            // directory.
            if (existing.params.vuid != record.vuid) {
                Diagnostics.error(
                    DiagChannel.PROCESS, "JOB_SLOT_MISMATCH",
                    mapOf(
                        "slotServes" to existing.params.vuid.toString(),
                        "jobWants" to record.vuid.toString(),
                    ),
                )
                return null
            }
            return existing
        }

        val context: Context = stub.applicationContext ?: stub
        val params = VirtualLaunchParams(
            vuid = record.vuid,
            packageName = record.packageName,
            versionCode = versionCodeOf(context, record) ?: return null,
            targetComponent = record.serviceClass,
            kind = VirtualComponentKind.SERVICE,
            processName = record.processName,
            slot = record.slot,
        )
        return when (val result = AppBootstrap.bootstrap(context, params)) {
            is AppBootstrap.Result.Ready -> result
            is AppBootstrap.Result.Failed -> {
                Diagnostics.error(
                    DiagChannel.PROCESS, "JOB_BOOTSTRAP_REFUSED",
                    mapOf("code" to result.code, "message" to result.message),
                )
                null
            }
        }
    }

    /**
     * The version whose APK is on disk for this package.
     *
     * The record does not carry it: a job can outlive an update, and the right answer is
     * whatever is installed now rather than whatever was current when it was scheduled.
     */
    private fun versionCodeOf(context: Context, record: VirtualJob): Long? {
        val model = VirtualPathModel(HostPaths.filesRoot(context))
        val dir = java.io.File(model.apkDir(record.packageName, 0L)).parentFile
        val versions = dir?.listFiles()?.mapNotNull { it.name.toLongOrNull() }.orEmpty()
        if (versions.isEmpty()) {
            Diagnostics.error(
                DiagChannel.PROCESS, "JOB_PACKAGE_GONE",
                mapOf("package" to record.packageName),
            )
            return null
        }
        return versions.max()
    }

    private fun instantiate(
        ready: AppBootstrap.Result.Ready,
        stub: JobService,
        record: VirtualJob,
    ): JobService? = runCatching {
        val clazz = Class.forName(record.serviceClass, true, ready.application.classLoader)
        val guest = clazz.getDeclaredConstructor().newInstance() as JobService

        // A Service is a ContextWrapper: give it the guest's own Context so getFilesDir
        // and friends resolve into the instance's directory rather than UNIQUE's.
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(guest, ready.application)

        // jobFinished() goes through a private engine the platform installs at bind time.
        // The guest's instance was never bound, so it has none; the stub's is the one the
        // system is listening to.
        copyEngine(from = stub, to = guest)
        guest
    }.getOrElse {
        Diagnostics.error(
            DiagChannel.PROCESS, "JOB_SERVICE_INSTANTIATION_FAILED",
            mapOf("service" to record.serviceClass, "error" to it.toString()),
        )
        null
    }

    private fun copyEngine(from: JobService, to: JobService) {
        val field = generateSequence(JobService::class.java as Class<*>) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField("mEngine") }.getOrNull() }
            .firstOrNull()
        if (field == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "JOB_ENGINE_FIELD_MISSING",
                mapOf("detail" to "jobFinished() will fail inside the guest"),
            )
            return
        }
        field.isAccessible = true
        runCatching { field.set(to, field.get(from)) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "JOB_ENGINE_COPY_FAILED",
                mapOf("error" to it.toString()),
            )
        }
    }
}
