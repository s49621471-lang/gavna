package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Namespacing rules.
 *
 * Virtual apps choose their own job ids and notification channel ids, and two instances
 * of the same app choose exactly the same ones. Without namespacing, instance 2
 * scheduling a job cancels instance 1's, and their notification settings merge. Both
 * failures are silent and would be blamed on the app, so the arithmetic is pinned here.
 */
class StubRouterTest {

    @Test fun `job ids from different instances never collide`() {
        val virtualJobId = 42
        val hostIds = (0 until 16).map { StubRouter.hostJobId(it, virtualJobId) }
        assertThat(hostIds.toSet()).hasSize(16)
    }

    @Test fun `job id namespacing round-trips`() {
        for (vuid in listOf(0, 1, 15, 2047)) {
            for (jobId in listOf(0, 1, 42, 0xFFFFF)) {
                val host = StubRouter.hostJobId(vuid, jobId)
                assertThat(StubRouter.virtualJobId(host)).isEqualTo(jobId)
                assertThat(StubRouter.jobOwner(host)).isEqualTo(vuid)
            }
        }
    }

    @Test fun `namespaced job ids stay positive`() {
        // A negative job id is rejected by JobScheduler, so an overflow here would show up
        // as an app that simply cannot schedule anything.
        for (vuid in listOf(0, 1, 100, 2047)) {
            assertThat(StubRouter.hostJobId(vuid, 0xFFFFF)).isAtLeast(0)
        }
    }

    @Test fun `notification channels are namespaced per instance`() {
        val a = StubRouter.hostChannelId(0, "org.example.chat", "messages")
        val b = StubRouter.hostChannelId(1, "org.example.chat", "messages")
        assertThat(a).isNotEqualTo(b)
        assertThat(a).isEqualTo("vu0:org.example.chat:messages")
    }

    @Test fun `channel ids round-trip, including ids containing separators`() {
        for (channel in listOf("messages", "calls:incoming", "a:b:c")) {
            val host = StubRouter.hostChannelId(3, "org.example.chat", channel)
            val parsed = StubRouter.parseChannelId(host)
            assertThat(parsed).isEqualTo(Triple(3, "org.example.chat", channel))
        }
    }

    @Test fun `a channel id that is not ours parses to null`() {
        assertThat(StubRouter.parseChannelId("messages")).isNull()
        assertThat(StubRouter.parseChannelId("vuX:pkg:chan")).isNull()
        assertThat(StubRouter.parseChannelId("vu0:pkg")).isNull()
    }

    @Test fun `stub activity names are unique per slot, launch mode and affinity`() {
        val names = buildSet {
            for (slot in 0 until 16) {
                for (mode in 0 until 4) {
                    for (affinity in 0 until 2) {
                        add(StubRouter.stubActivity(slot, mode, affinity))
                    }
                }
            }
        }
        assertThat(names).hasSize(16 * 4 * 2)
        assertThat(StubRouter.stubActivity(3, 2, 1))
            .isEqualTo("com.unique.stub.ActivityStub_p3_m2_a1")
    }

    @Test fun `stub service, provider and job names follow the generated ones`() {
        assertThat(StubRouter.stubService(7, 3)).isEqualTo("com.unique.stub.ServiceStub_p7_s3")
        assertThat(StubRouter.stubProvider(7)).isEqualTo("com.unique.stub.ProviderStub_p7")
        assertThat(StubRouter.stubJobService(7)).isEqualTo("com.unique.stub.JobStub_p7")
    }

    @Test fun `a stub service name round-trips back to its slot and index`() {
        assertThat(StubRouter.parseStubService(StubRouter.stubService(2, 5))).isEqualTo(2 to 5)
        assertThat(StubRouter.parseStubService("com.unique.stub.ActivityStub_p2_m0_a0")).isNull()
        assertThat(StubRouter.parseStubService("com.example.NotOurs")).isNull()
    }
}
