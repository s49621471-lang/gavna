package com.unique.core.vam

import android.app.job.JobInfo
import android.content.Context
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import com.unique.core.hook.SystemServiceHook
import java.lang.reflect.Method

/**
 * Schedules a guest's jobs onto a host stub, and hands its own jobs back unchanged.
 *
 * `JobInfo.service` names a component of a package the system has never installed, so a
 * guest's `schedule()` is rejected outright — and the failure is a bare `RESULT_FAILURE`
 * with nothing in the log to say why. The component is rewritten onto `JobStub_p<slot>`,
 * which the host declares with `BIND_JOB_SERVICE`, and the job id is namespaced so two
 * instances of one app do not cancel each other's work.
 *
 * Everything the guest reads back — `getPendingJob`, `getAllPendingJobs` — is rewritten in
 * the other direction, because an app that schedules id 7 and is told it has a pending job
 * id 1048583 for a class it has never heard of will conclude its own scheduling is broken.
 */
object VirtualJobSchedulerHook {

    @Volatile private var installedFor: String? = null
    @Volatile private var store: VirtualJobStore? = null
    @Volatile private var slot: Int = 0
    @Volatile private var vuid: Int = 0

    val boundPackage: String? get() = installedFor

    /** Records for this process, so the inbound side can resolve a fired job. */
    fun storeFor(context: Context): VirtualJobStore =
        store ?: VirtualJobStore(VirtualPathModel(HostPaths.filesRoot(context))).also { store = it }

    /**
     * Methods that carry a whole `JobInfo`: `schedule`, `enqueue`, `scheduleAsPackage`.
     *
     * Matched on the argument type rather than the name, because the family has grown a
     * member in most releases and the `JobInfo` is what actually needs rewriting.
     */
    internal fun carriesJobInfo(method: Method): Boolean =
        method.parameterTypes.any { it == JobInfo::class.java }

    /**
     * Methods that name a job by id and nothing else.
     *
     * `IJobScheduler` is a per-user binder, so unlike the notification interface these
     * carry no trailing `userId` — a single int is unambiguously the job id, and a method
     * with none (`cancelAll`) needs no rewrite. The single-int requirement is what keeps
     * that true if a release adds one.
     */
    internal fun namesJobById(method: Method): Boolean {
        val name = method.name
        if (!name.startsWith("cancel") && !name.startsWith("getPendingJob")) return false
        return method.parameterTypes.count { it == Int::class.javaPrimitiveType } == 1
    }

    @Synchronized
    fun install(ready: AppBootstrap.Result.Ready, hostContext: Context): Boolean {
        if (installedFor == ready.params.packageName) return true
        val target = SystemServiceHook.TARGETS.firstOrNull { it.serviceName == "jobscheduler" }
            ?: return false

        slot = ready.params.slot
        vuid = ready.params.vuid
        store = VirtualJobStore(VirtualPathModel(HostPaths.filesRoot(hostContext)))

        val report = SystemServiceHook.install(
            target,
            shims(ready.params.packageName, hostContext.packageName),
        )
        if (!report.installed) {
            Diagnostics.warn(
                DiagChannel.HOOK, "JOB_HOOK_FAILED",
                mapOf("package" to ready.params.packageName, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        installedFor = ready.params.packageName
        Diagnostics.info(
            DiagChannel.HOOK, "JOB_HOOKED",
            mapOf(
                "package" to ready.params.packageName,
                "slot" to slot.toString(),
                "matched" to (report.bind?.describeMatches()?.take(300) ?: "-"),
            ),
        )
        return true
    }

    private fun shims(virtualPackage: String, hostPackage: String): List<MethodShim> = listOf(
        shim("scheduleJob") {
            matchMethods { method -> carriesJobInfo(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteAll<JobInfo> { job -> rewriteOutbound(job, hostPackage) }
        },

        shim("jobById") {
            matchMethods { method -> namesJobById(method) }
            rewriteAll<Int> { id -> StubRouter.hostJobId(vuid, id) }
            // getPendingJob hands a JobInfo back; the guest must see its own id and class.
            rewriteResult { result ->
                if (result is JobInfo) rewriteInbound(result) ?: result else result
            }
        },

        shim("pendingJobs") {
            matchMethods { it.name.startsWith("getAllPendingJobs") }
            rewriteResult { result -> rewriteJobList(result) }
        },
    )

    private fun rewriteOutbound(job: JobInfo, hostPackage: String): JobInfo? {
        val ready = AppBootstrap.current ?: return null
        if (job.service.packageName != ready.params.packageName) return null

        val declared = ready.manifest.components.firstOrNull {
            it.className == job.service.className
        }
        if (declared == null) {
            // A job pointing at a class the guest's own manifest does not declare would be
            // rejected by the platform anyway; saying so here names the class.
            Diagnostics.warn(
                DiagChannel.PROCESS, "JOB_SERVICE_NOT_DECLARED",
                mapOf("service" to job.service.className, "package" to ready.params.packageName),
            )
            return null
        }

        val (hostJob, record) = VirtualJobRewriter.toHost(
            job, hostPackage, ready.params.vuid, ready.params.slot,
        ) ?: return null

        store?.put(record)
        Diagnostics.info(
            DiagChannel.PROCESS, "JOB_SCHEDULED",
            mapOf(
                "service" to record.serviceClass,
                "virtualJobId" to record.virtualJobId.toString(),
                "hostJobId" to record.hostJobId.toString(),
                "stub" to StubRouter.stubJobService(record.slot),
            ),
        )
        return hostJob
    }

    private fun rewriteInbound(job: JobInfo): JobInfo? {
        val record = store?.get(job.id) ?: return null
        return VirtualJobRewriter.toGuest(job, record)
    }

    /**
     * `getAllPendingJobs` answers with a `ParceledListSlice`, so a rule declared on
     * `List<*>` would never fire — the same container problem as notification channels
     * (§6.7.1). Unwrapped through `getList()` and rebuilt.
     */
    private fun rewriteJobList(result: Any?): Any? {
        if (result == null) return null
        val sliceClass = Reflect.findClass("android.content.pm.ParceledListSlice")
        if (sliceClass == null || !sliceClass.isInstance(result)) return result
        return runCatching {
            val items = result.javaClass.getMethod("getList").invoke(result) as? List<*>
                ?: return result
            var changed = false
            val mapped = items.map { item ->
                if (item is JobInfo) rewriteInbound(item)?.also { changed = true } ?: item else item
            }
            // Jobs that belong to UNIQUE itself are dropped: a guest listing "its" pending
            // jobs must not be shown the host's.
            val mine = mapped.filter { it !is JobInfo || store?.get(StubRouter.hostJobId(vuid, it.id)) != null }
            if (!changed && mine.size == items.size) result
            else sliceClass.getConstructor(List::class.java).newInstance(mine)
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.PROCESS, "JOB_LIST_REWRITE_FAILED",
                mapOf("error" to it.toString()),
            )
            result
        }
    }
}
