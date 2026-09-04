package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Job identity across instances, and the record that survives the process.
 *
 * The device test can only show one instance's job running to completion — a job that
 * finishes synchronously is gone before a second could be observed alongside it — so the
 * property that two instances do not collide is pinned here instead.
 */
class VirtualJobsTest {

    @Test fun `two instances scheduling the same id do not collide`() {
        val a = StubRouter.hostJobId(0, 31)
        val b = StubRouter.hostJobId(1, 31)
        assertThat(a).isNotEqualTo(b)
        assertThat(StubRouter.virtualJobId(a)).isEqualTo(31)
        assertThat(StubRouter.virtualJobId(b)).isEqualTo(31)
        assertThat(StubRouter.jobOwner(a)).isEqualTo(0)
        assertThat(StubRouter.jobOwner(b)).isEqualTo(1)
    }

    @Test fun `job ids stay positive across the whole range`() {
        // A negative id would be rejected by JobScheduler, and the failure would look
        // like the app's own id being invalid.
        assertThat(StubRouter.hostJobId(2047, 0xFFFFF)).isAtLeast(0)
        assertThat(StubRouter.hostJobId(0, 0)).isEqualTo(0)
    }

    @Test fun `a routing record round-trips through its file form`() {
        val job = VirtualJob(
            hostJobId = 1048607,
            virtualJobId = 31,
            vuid = 1,
            packageName = "com.unique.probe",
            serviceClass = "com.unique.probe.ProbeJobService",
            processName = "com.unique.probe",
            slot = 1,
        )
        assertThat(VirtualJob.parse(job.asProperties())).isEqualTo(job)
    }

    @Test fun `a truncated record is rejected rather than half-read`() {
        // Half a record would route a job to the wrong class, which is worse than not
        // running it at all.
        assertThat(VirtualJob.parse("hostJobId=1\nvuid=0\n")).isNull()
        assertThat(VirtualJob.parse("")).isNull()
        assertThat(VirtualJob.parse("garbage")).isNull()
    }

    @Suppress("unused")
    interface FakeJobScheduler {
        fun schedule(job: android.app.job.JobInfo): Int
        fun enqueue(job: android.app.job.JobInfo, work: Any?): Int
        fun scheduleAsPackage(job: android.app.job.JobInfo, packageName: String, userId: Int, tag: String): Int
        fun cancel(jobId: Int)
        fun cancelAll()
        fun getPendingJob(jobId: Int): android.app.job.JobInfo?
        fun getAllPendingJobs(): Any?
    }

    private fun method(name: String) =
        FakeJobScheduler::class.java.methods.first { it.name == name }

    @Test fun `every call carrying a JobInfo is rewritten`() {
        for (name in listOf("schedule", "enqueue", "scheduleAsPackage")) {
            assertThat(VirtualJobSchedulerHook.carriesJobInfo(method(name))).isTrue()
        }
        assertThat(VirtualJobSchedulerHook.carriesJobInfo(method("cancel"))).isFalse()
    }

    @Test fun `calls naming a job by id are matched, and cancelAll is not`() {
        assertThat(VirtualJobSchedulerHook.namesJobById(method("cancel"))).isTrue()
        assertThat(VirtualJobSchedulerHook.namesJobById(method("getPendingJob"))).isTrue()
        // No int: nothing to namespace, and cancelling everything is the guest's right.
        assertThat(VirtualJobSchedulerHook.namesJobById(method("cancelAll"))).isFalse()
        // Two ints would mean one of them is not a job id; the shim declines rather than
        // guessing, the same rule as the notification id.
        assertThat(VirtualJobSchedulerHook.namesJobById(method("scheduleAsPackage"))).isFalse()
    }
}
