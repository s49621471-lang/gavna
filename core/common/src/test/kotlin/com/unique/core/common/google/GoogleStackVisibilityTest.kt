package com.unique.core.common.google

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which guests are told the device has Google Play services.
 *
 * The rule generalises from two physical runs and the numbers below are theirs, not
 * invented: `com.gordey.standarling` linked `play-services-basement@@17.4.0` and died on
 * its main looper when the broker refused it; an app on the 18.x line took the same
 * refusal, logged *"Failed to get service from broker"* and carried on. Everything here
 * is about not generalising further than that evidence allows.
 */
class GoogleStackVisibilityTest {

    private fun decide(version: Int?, override: Boolean? = null) =
        GoogleStackVisibility.decide(version, override)

    @Test fun `an app that does not link the client library is shown the stack`() {
        // Nothing in it can be killed by a refused broker, and hiding cost it every
        // Google package lookup it might make for another reason.
        val d = decide(null)
        assertThat(d.hidden).isFalse()
        assertThat(d.reason).isEqualTo(GoogleStackVisibility.Reason.NO_GMS_SDK)
    }

    @Test fun `a modern client library is shown the stack`() {
        // 203390000 is play-services 18.0.0, the first line whose zabm.run catches the
        // refusal. This is the case that stops apps saying "install Google services".
        val d = decide(203_390_000)
        assertThat(d.hidden).isFalse()
        assertThat(d.reason).isEqualTo(GoogleStackVisibility.Reason.SDK_HANDLES_REFUSAL)
    }

    @Test fun `the client library that died on a phone is hidden from`() {
        // 12451000 is the constant the 15.x–17.x line declares, which is what
        // play-services-basement 17.4.0 carried into the fatal run.
        val d = decide(12_451_000)
        assertThat(d.hidden).isTrue()
        assertThat(d.reason).isEqualTo(GoogleStackVisibility.Reason.SDK_TOO_OLD)
        assertThat(d.detail).contains("kills the app")
    }

    @Test fun `an unresolved resource reference is hidden from, not read as a version`() {
        // 0x7f0c000b is 2130771979 — larger than any real version, so reading it as one
        // would call every app with an unresolved reference modern. That id is from a
        // real log line: META_DATA_NOT_RESOLVED name=com.google.android.gms.version.
        val d = decide(0x7f0c000b)
        assertThat(d.hidden).isTrue()
        assertThat(d.reason).isEqualTo(GoogleStackVisibility.Reason.VERSION_UNRESOLVED)
    }

    @Test fun `the boundary itself counts as modern`() {
        assertThat(decide(GoogleStackVisibility.FIRST_VERSION_THAT_SURVIVES_A_REFUSED_BROKER).hidden)
            .isFalse()
        assertThat(decide(GoogleStackVisibility.FIRST_VERSION_THAT_SURVIVES_A_REFUSED_BROKER - 1).hidden)
            .isTrue()
    }

    @Test fun `an override wins over the rule, both ways`() {
        assertThat(decide(203_390_000, override = true).hidden).isTrue()
        assertThat(decide(12_451_000, override = false).hidden).isFalse()
        assertThat(decide(null, override = true).reason)
            .isEqualTo(GoogleStackVisibility.Reason.OVERRIDDEN)
    }

    @Test fun `every decision carries the version it was made from`() {
        // The log has to be able to say *why*, not only *what*: a wrong answer here looks
        // exactly like an app that genuinely has no Google integration.
        assertThat(decide(12_451_000).toMap()["gmsVersion"]).isEqualTo("12451000")
        assertThat(decide(null).toMap()["gmsVersion"]).isEqualTo("-")
        assertThat(decide(203_390_000).toMap()["hidden"]).isEqualTo("false")
    }
}
