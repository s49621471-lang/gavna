package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which exceptions a guest is allowed to survive.
 *
 * The guard exists to make one refusal non-fatal, and the danger in a class like this is
 * that it quietly becomes a general crash suppressor — at which point every bug in every
 * guest turns into a process in an undefined state, and `CrashGuard`'s rule that a broken
 * process must die stops holding. So what is tested is mostly what it does *not* catch.
 */
class GuestLooperGuardTest {

    private fun refusal(package_: String = "com.example.app") =
        SecurityException("Unknown calling package name '$package_'.")

    @Test fun `the Play services refusal is survived`() {
        assertThat(GuestLooperGuard.shouldSurvive(refusal(), alreadySurvived = 0)).isTrue()
    }

    @Test fun `it is survived however deep the platform wrapped it`() {
        // In the log that produced this class the refusal arrives from a binder
        // transaction, wrapped by whatever was on the stack in `handleMessage`.
        val wrapped = RuntimeException(
            "Unable to start activity",
            IllegalStateException(refusal()),
        )
        assertThat(GuestLooperGuard.shouldSurvive(wrapped, 0)).isTrue()
    }

    @Test fun `an ordinary crash is not survived`() {
        // The important half. A guest that dereferences null must still die: a process
        // that keeps running after one is in a state nothing can reason about, and the
        // UI's Restart is the honest answer.
        for (other in listOf(
            NullPointerException("boom"),
            SecurityException("Permission denial: android.permission.CAMERA"),
            OutOfMemoryError("no"),
            IllegalStateException(),
        )) {
            assertThat(GuestLooperGuard.shouldSurvive(other, 0)).isFalse()
        }
    }

    @Test fun `an app that hits the refusal in a loop is let go`() {
        // Each survived refusal nests one more `Looper.loop()` frame. An app retrying
        // forever would grow the stack without bound, so past the limit the exception
        // goes to the uncaught handler — which records the marker that hides Play
        // services from this instance and ends the retries at the source.
        val limit = GuestLooperGuard.thrashLimit()
        assertThat(GuestLooperGuard.shouldSurvive(refusal(), limit - 2)).isTrue()
        assertThat(GuestLooperGuard.shouldSurvive(refusal(), limit - 1)).isFalse()
        assertThat(GuestLooperGuard.shouldSurvive(refusal(), limit + 100)).isFalse()
    }

    @Test fun `the limit is high enough for a real app and low enough to bound the stack`() {
        // One device log had 10 refusals for a single app in one launch, all recovered
        // from by its own SDK. A limit below that would give up on an app that was fine.
        assertThat(GuestLooperGuard.thrashLimit()).isAtLeast(16)
        assertThat(GuestLooperGuard.thrashLimit()).isAtMost(64)
    }
}
