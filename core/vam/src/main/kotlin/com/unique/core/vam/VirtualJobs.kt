package com.unique.core.vam

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.content.ComponentName
import android.os.Parcel
import android.os.Parcelable
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import java.io.File

/**
 * The record that lets a job fired minutes later find its way back to a guest.
 *
 * A scheduled job outlives the process that scheduled it — that is the entire point of
 * `JobScheduler` — so the mapping from a host job id to the guest's `JobService` cannot
 * live in memory. It is written under `runtime/`, in UNIQUE's app-private storage, and
 * read back by whichever `:vappN` process the system starts to run the job.
 *
 * Kept as one small file per job rather than a database: the reader is a stub service
 * starting cold in a process that may not have bootstrapped yet, and a file read is the
 * one thing guaranteed to work there without IPC.
 */
data class VirtualJob(
    val hostJobId: Int,
    val virtualJobId: Int,
    val vuid: Int,
    val packageName: String,
    val serviceClass: String,
    val processName: String,
    val slot: Int,
) {
    fun asProperties(): String = buildString {
        appendLine("hostJobId=$hostJobId")
        appendLine("virtualJobId=$virtualJobId")
        appendLine("vuid=$vuid")
        appendLine("packageName=$packageName")
        appendLine("serviceClass=$serviceClass")
        appendLine("processName=$processName")
        appendLine("slot=$slot")
    }

    companion object {
        fun parse(text: String): VirtualJob? {
            val map = text.lineSequence()
                .filter { it.contains('=') }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            return VirtualJob(
                hostJobId = map["hostJobId"]?.toIntOrNull() ?: return null,
                virtualJobId = map["virtualJobId"]?.toIntOrNull() ?: return null,
                vuid = map["vuid"]?.toIntOrNull() ?: return null,
                packageName = map["packageName"] ?: return null,
                serviceClass = map["serviceClass"] ?: return null,
                processName = map["processName"] ?: map["packageName"] ?: return null,
                slot = map["slot"]?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** Reads and writes the job records. Free of platform services so it is testable. */
class VirtualJobStore(private val model: VirtualPathModel) {

    private fun fileFor(hostJobId: Int) = File(model.jobRecord(hostJobId))

    fun put(job: VirtualJob) {
        runCatching {
            val f = fileFor(job.hostJobId)
            f.parentFile?.mkdirs()
            f.writeText(job.asProperties())
        }.onFailure {
            Diagnostics.error(
                DiagChannel.PROCESS, "JOB_RECORD_WRITE_FAILED",
                mapOf("jobId" to job.hostJobId.toString(), "error" to it.toString()),
            )
        }
    }

    fun get(hostJobId: Int): VirtualJob? {
        val f = fileFor(hostJobId)
        if (!f.isFile) return null
        return runCatching { VirtualJob.parse(f.readText()) }.getOrNull()
    }

    fun remove(hostJobId: Int) {
        runCatching { fileFor(hostJobId).delete() }
    }
}

/**
 * Copies a `Parcelable` through the platform's own marshalling.
 *
 * `JobInfo` and `JobParameters` have final fields and no public copy constructor, and
 * hand-rebuilding one through its `Builder` means enumerating every constraint the
 * platform has ever had — a list that goes stale exactly as fast as the AIDL signatures
 * `MethodShim` exists to avoid pinning. Round-tripping through a `Parcel` copies whatever
 * the release actually carries, including fields UNIQUE has never heard of.
 *
 * The copy exists so the *guest's* object is never mutated: it scheduled a job with its
 * own id and must keep seeing that id.
 */
internal object ParcelCopy {

    inline fun <reified T : Parcelable> of(source: T): T? = runCatching {
        val creator = T::class.java.getField("CREATOR").get(null) as Parcelable.Creator<*>
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            creator.createFromParcel(parcel) as T
        } finally {
            parcel.recycle()
        }
    }.getOrElse {
        Diagnostics.warn(
            DiagChannel.PROCESS, "PARCEL_COPY_FAILED",
            mapOf("type" to (source::class.java.name), "error" to it.toString()),
        )
        null
    }
}

/**
 * Rewrites a guest's `JobInfo` onto a host stub, and a fired job's `JobParameters` back.
 *
 * Two things have to change on the way out and both have to change back on the way in:
 *
 *  - **The service.** `JobInfo.service` names a component of a package the system has
 *    never installed, so the schedule is rejected outright.
 *  - **The id.** Apps choose small constants and two instances of one app choose the same
 *    one, so the second instance scheduling would cancel the first's job.
 */
object VirtualJobRewriter {

    /** Sets a final field on a *copy*, never on the object the guest holds. */
    private fun setField(target: Any, name: String, value: Any?): Boolean = runCatching {
        val field = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }
            .firstOrNull() ?: error("no field $name on ${target.javaClass.name}")
        field.isAccessible = true
        field.set(target, value)
        true
    }.getOrElse {
        Diagnostics.error(
            DiagChannel.PROCESS, "JOB_FIELD_REWRITE_FAILED",
            mapOf("field" to name, "error" to it.toString()),
        )
        false
    }

    /** The outbound copy: stub component, namespaced id. Null when it cannot be built. */
    fun toHost(job: JobInfo, hostPackage: String, vuid: Int, slot: Int): Pair<JobInfo, VirtualJob>? {
        val copy = ParcelCopy.of(job) ?: return null
        val hostId = StubRouter.hostJobId(vuid, job.id)
        val stub = ComponentName(hostPackage, StubRouter.stubJobService(slot))
        if (!setField(copy, "jobId", hostId)) return null
        if (!setField(copy, "service", stub)) return null
        val record = VirtualJob(
            hostJobId = hostId,
            virtualJobId = job.id,
            vuid = vuid,
            packageName = job.service.packageName,
            serviceClass = job.service.className,
            processName = job.service.packageName,
            slot = slot,
        )
        return copy to record
    }

    /** The inbound copy: the guest sees the id it chose, not the namespaced one. */
    fun toGuest(params: JobParameters, virtualJobId: Int): JobParameters? {
        val copy = ParcelCopy.of(params) ?: return null
        return if (setField(copy, "jobId", virtualJobId)) copy else null
    }

    /** The reverse of [toHost] for a `JobInfo` handed *back* to the guest. */
    fun toGuest(job: JobInfo, record: VirtualJob): JobInfo? {
        val copy = ParcelCopy.of(job) ?: return null
        if (!setField(copy, "jobId", record.virtualJobId)) return null
        if (!setField(copy, "service", ComponentName(record.packageName, record.serviceClass))) return null
        return copy
    }
}
