package com.unique.core.common.google

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which guests are told the device has Google Play services.
 *
 * The first version of this test asserted a rule read off
 * `com.google.android.gms.version`, and the rule was wrong: that number is the *minimum
 * GmsCore version the client requires*, frozen at 12451000 for years, and three
 * unrelated apps in one device log all declared it. Nothing built on it could work, so
 * the answer is measured now — visible until an instance dies of the one refusal that
 * kills an old client — and what is tested is that measurement, not a guess.
 */
class GoogleStackVisibilityTest {

    @Test fun `a guest that has proved nothing sees Play services`() {
        // The default, and the whole point of the change: a phone with Play services
        // installed should not have every app told it is missing.
        val d = GoogleStackVisibility.decide(null)
        assertThat(d.hidden).isFalse()
        assertThat(d.reason).isEqualTo(GoogleStackVisibility.Reason.VISIBLE_BY_DEFAULT)
    }

    @Test fun `an instance that died of the refusal is hidden from it afterwards`() {
        val d = GoogleStackVisibility.decide(GoogleStackVisibility.Override.AUTO_HIDE)
        assertThat(d.hidden).isTrue()
        assertThat(d.reason).isEqualTo(GoogleStackVisibility.Reason.AUTO_HIDDEN_AFTER_CRASH)
        assertThat(d.detail).contains(GoogleStackVisibility.REFUSED_CALLING_PACKAGE)
    }

    @Test fun `an override wins, both ways`() {
        assertThat(GoogleStackVisibility.decide(GoogleStackVisibility.Override.HIDE).hidden)
            .isTrue()
        // SHOW has to beat a recorded crash, or an instance could never be tried again.
        assertThat(GoogleStackVisibility.decide(GoogleStackVisibility.Override.SHOW).hidden)
            .isFalse()
    }

    @Test fun `the marker the crash writes is the one a launch reads back`() {
        // These two are the ends of a round trip through a file, in two different
        // processes, and a typo in either would silently switch the recovery off.
        assertThat(GoogleStackVisibility.parseOverride(GoogleStackVisibility.AUTO_HIDE_MARKER))
            .isEqualTo(GoogleStackVisibility.Override.AUTO_HIDE)
    }

    @Test fun `a hand-edited file is read the way a person would write it`() {
        for (word in listOf("hide", "HIDE", " hidden ", "Hidden\n")) {
            assertThat(GoogleStackVisibility.parseOverride(word))
                .isEqualTo(GoogleStackVisibility.Override.HIDE)
        }
        for (word in listOf("show", "visible", " SHOW\n")) {
            assertThat(GoogleStackVisibility.parseOverride(word))
                .isEqualTo(GoogleStackVisibility.Override.SHOW)
        }
    }

    @Test fun `a mark from an older build is discarded, not obeyed`() {
        // Standoff 2, in the tenth run. It had crashed under a build with no answer for
        // the refusal, the mark was written, and the next build — which rewrites the
        // calling package so the refusal cannot happen — read the mark and told the app
        // there was no Play services. The user saw "install Google Play services" from a
        // build in which Google worked.
        val stale = GoogleStackVisibility.parseOverride("auto-hide")
        assertThat(stale).isEqualTo(GoogleStackVisibility.Override.STALE_AUTO_HIDE)
        val decision = GoogleStackVisibility.decide(stale)
        assertThat(decision.hidden).isFalse()
        assertThat(decision.reason)
            .isEqualTo(GoogleStackVisibility.Reason.STALE_MARK_DISCARDED)
    }

    @Test fun `a mark from a build one generation older is also discarded`() {
        assertThat(GoogleStackVisibility.parseOverride("auto-hide@1"))
            .isEqualTo(GoogleStackVisibility.Override.STALE_AUTO_HIDE)
    }

    @Test fun `the marker this build writes carries this build's generation`() {
        // The two are derived from one constant, and this is what says so: a marker and a
        // reader that disagree switch the recovery off silently in one direction and make
        // it permanent in the other.
        assertThat(GoogleStackVisibility.AUTO_HIDE_MARKER)
            .endsWith("@${GoogleStackVisibility.IDENTITY_GENERATION}")
    }

    @Test fun `a hand-written hide is obeyed whatever generation wrote it`() {
        // A person's decision is not evidence about a build, so it does not go stale.
        assertThat(GoogleStackVisibility.decide(
            GoogleStackVisibility.parseOverride("hide"),
        ).hidden).isTrue()
    }

    @Test fun `a typo leaves the default alone rather than deciding anything`() {
        assertThat(GoogleStackVisibility.parseOverride("hied")).isNull()
        assertThat(GoogleStackVisibility.parseOverride("")).isNull()
        assertThat(GoogleStackVisibility.parseOverride(null)).isNull()
    }

    @Test fun `the refusal is recognised however deep it is wrapped`() {
        // The platform wraps it in whatever was on the stack above, so the message that
        // identifies it is often several `Caused by` down.
        val refusal = SecurityException("Unknown calling package name 'com.example.app'.")
        assertThat(GoogleStackVisibility.isRefusedCallingPackage(refusal)).isTrue()
        assertThat(
            GoogleStackVisibility.isRefusedCallingPackage(
                RuntimeException("Unable to start activity", IllegalStateException(refusal)),
            )
        ).isTrue()
    }

    @Test fun `an ordinary crash does not hide Play services`() {
        // Writing the marker for every crash would switch Play services off for an app
        // that died of its own bug, and nothing would ever say why.
        assertThat(GoogleStackVisibility.isRefusedCallingPackage(NullPointerException()))
            .isFalse()
        assertThat(GoogleStackVisibility.isRefusedCallingPackage(null)).isFalse()
    }

    @Test fun `a cyclic cause chain terminates`() {
        // `initCause` refuses self-causation, so the cycle is built through two — which
        // is the shape a chain can actually take. Without the bound in the walk this
        // hangs the crash handler of a process that is already dying.
        val outer = RuntimeException("outer")
        val inner = RuntimeException("inner", outer)
        outer.initCause(inner)
        assertThat(GoogleStackVisibility.isRefusedCallingPackage(outer)).isFalse()
    }
}
