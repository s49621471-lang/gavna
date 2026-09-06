package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which instance a `:vappN` process belongs to, and when it may be handed to another.
 *
 * This is the decision behind one of the worst faults a device log has produced. On the
 * eleventh phone run bin.mt.plus failed to construct its `Application` — a packed app
 * whose own static initialiser throws — and the graft stopped there, after the process had
 * already been renamed, its device profile bound and its identity hooks installed. The
 * engine recorded nothing, because what it recorded was the *result* of a graft and there
 * was none. Sixteen seconds later a `JobStub_p0` for a different app fired into the same
 * process:
 *
 * ```
 * BOOTSTRAP_FAILED  package=bin.mt.plus code=NO_APPLICATION
 * CREATE_SERVICE_UNMAPPED stub=com.unique.stub.JobStub_p0
 * PROCESS_RENAMED   argv0=com.axlebolt.standoff2
 * PROFILE_REBIND_IGNORED current=028dca45-… requested=6c904a63-…
 * ```
 *
 * Standoff 2 then ran in bin.mt.plus's slot under **bin.mt.plus's `ANDROID_ID`**, because
 * a device profile binds once per process. Separate identity per instance is the single
 * promise this engine makes, and it was broken silently, by an app that was not even
 * running.
 *
 * So the claim is written before the graft, and every case is pinned here.
 */
class AppBootstrapClaimTest {

    private fun claim(vuid: Int, packageName: String, slot: Int = 0) =
        AppBootstrap.Claim(vuid, packageName, slot)

    private val game = claim(0, "com.axlebolt.standoff2")
    private val fileManager = claim(1, "bin.mt.plus")

    @Test fun `an unclaimed process grafts`() {
        assertThat(AppBootstrap.verdict(bound = null, claimed = null, wanted = game))
            .isEqualTo(AppBootstrap.Verdict.GRAFT)
    }

    @Test fun `a process already serving this instance hands itself back`() {
        assertThat(AppBootstrap.verdict(bound = game, claimed = game, wanted = game))
            .isEqualTo(AppBootstrap.Verdict.REUSE)
    }

    @Test fun `a process serving another instance is refused`() {
        assertThat(AppBootstrap.verdict(bound = game, claimed = game, wanted = fileManager))
            .isEqualTo(AppBootstrap.Verdict.REFUSE_BOUND)
    }

    /** The fault above, in one line. */
    @Test fun `a claim whose graft failed still owns the process`() {
        assertThat(AppBootstrap.verdict(bound = null, claimed = fileManager, wanted = game))
            .isEqualTo(AppBootstrap.Verdict.REFUSE_CLAIMED)
    }

    @Test fun `the app whose graft failed may try again in its own process`() {
        // Three launches of bin.mt.plus landed in the same process in that run and each
        // one failed identically. Retrying is not what went wrong and stays allowed.
        assertThat(AppBootstrap.verdict(bound = null, claimed = fileManager, wanted = fileManager))
            .isEqualTo(AppBootstrap.Verdict.GRAFT)
    }

    @Test fun `two instances of one package are different instances`() {
        val first = claim(0, "org.example.chat")
        val second = claim(1, "org.example.chat")
        assertThat(AppBootstrap.verdict(bound = first, claimed = first, wanted = second))
            .isEqualTo(AppBootstrap.Verdict.REFUSE_BOUND)
        assertThat(AppBootstrap.verdict(bound = null, claimed = first, wanted = second))
            .isEqualTo(AppBootstrap.Verdict.REFUSE_CLAIMED)
    }

    /**
     * A slot number can be stale and is not part of identity.
     *
     * `VirtualJob` records the slot its guest occupied when the job was scheduled, and the
     * guest can be given a different one before the job runs. Comparing slots would refuse
     * a guest its own process for a number that says nothing about whose process it is.
     */
    @Test fun `the same instance arriving with a stale slot number is still itself`() {
        val scheduled = claim(0, "com.axlebolt.standoff2", slot = 0)
        val nowIn = claim(0, "com.axlebolt.standoff2", slot = 3)
        assertThat(AppBootstrap.verdict(bound = scheduled, claimed = scheduled, wanted = nowIn))
            .isEqualTo(AppBootstrap.Verdict.REUSE)
    }

    @Test fun `a binding always outranks a claim`() {
        // The two disagree only if a graft succeeded for something the claim did not name,
        // which cannot happen — but the verdict has to be defined, and the binding is the
        // one backed by a running Application.
        assertThat(AppBootstrap.verdict(bound = game, claimed = fileManager, wanted = game))
            .isEqualTo(AppBootstrap.Verdict.REUSE)
    }
}
