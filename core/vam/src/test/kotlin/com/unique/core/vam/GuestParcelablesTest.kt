package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The loader a guest's `Intent` extras carry.
 *
 * `intent.setExtrasClassLoader(guestLoader)` was the first attempt at this and it shipped,
 * and the next phone log had the identical crash:
 *
 * ```
 * BadParcelableException: ClassNotFoundException when unmarshalling:
 *     com.google.android.gms.auth.api.signin.internal.SignInConfiguration
 *   at android.os.Bundle.getParcelable
 *   at …signin.internal.SignInHubActivity.onCreate
 * ```
 *
 * The reason it could not have worked: a `Bundle` resolves its class loader **once**, at
 * the first read of any key, and every value becomes a `LazyValue` holding the loader as
 * it was at that moment. UNIQUE always reads first — the routing extras are read on the
 * line above — so the loader was always UNIQUE's by the time the correct one was named.
 *
 * The fix is a loader that can be installed before the answer is known. `Intent` and
 * `Bundle` are Android classes and cannot be exercised here, but the property that made
 * the fix possible is plain Java and is pinned below: what it resolves is decided when it
 * is *asked*, not when it is set.
 */
class GuestParcelablesTest {

    /** A loader that can find exactly one class, standing in for a guest's APK. */
    private class OnlyLoader(private val name: String, private val answer: Class<*>) :
        ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            if (name == this.name) answer else throw ClassNotFoundException(name)
    }

    @Test fun `it resolves through whoever the target is when it is asked`() {
        var target: ClassLoader = OnlyLoader("nothing.at.all", String::class.java)
        val forwarding = GuestParcelables.ForwardingClassLoader { target }

        // Installed here, when the guest is not known — which is the moment UNIQUE has.
        val name = "com.google.android.gms.auth.api.signin.internal.SignInConfiguration"
        assertThat(runCatching { forwarding.loadClass(name) }.isFailure).isTrue()

        // The graft completes, and the same loader object now answers.
        target = OnlyLoader(name, java.util.ArrayList::class.java)
        assertThat(forwarding.loadClass(name)).isEqualTo(java.util.ArrayList::class.java)
    }

    @Test fun `a name the target cannot find is a ClassNotFoundException, not something else`() {
        // Whatever this does, it must not be to throw something a caller's catch block
        // does not expect: `Parcel` turns a ClassNotFoundException into the
        // BadParcelableException an app can at least recognise.
        val forwarding = GuestParcelables.ForwardingClassLoader {
            OnlyLoader("only.this", String::class.java)
        }
        val thrown = runCatching { forwarding.loadClass("some.other.Class") }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ClassNotFoundException::class.java)
    }

    @Test fun `framework classes resolve, because every guest loader chains to the boot loader`() {
        val forwarding = GuestParcelables.ForwardingClassLoader {
            GuestParcelablesTest::class.java.classLoader!!
        }
        assertThat(forwarding.loadClass("java.util.HashMap")).isEqualTo(java.util.HashMap::class.java)
    }

    @Test fun `one instance, so the creator and class caches keyed on it keep working`() {
        // `Parcel` caches Parcelable.Creators per ClassLoader and ART caches resolved
        // classes per loader. A loader per intent would defeat both, on the hottest path
        // in the engine.
        assertThat(GuestParcelables.loader).isSameInstanceAs(GuestParcelables.loader)
    }

    @Test fun `it says what it is forwarding to, for a log that has to explain itself`() {
        val forwarding = GuestParcelables.ForwardingClassLoader {
            GuestParcelablesTest::class.java.classLoader!!
        }
        assertThat(forwarding.toString()).startsWith("UNIQUE-forwarding->")
    }

    @Test fun `a target that throws does not take the toString with it`() {
        val forwarding = GuestParcelables.ForwardingClassLoader { error("no guest yet") }
        assertThat(forwarding.toString()).isEqualTo("UNIQUE-forwarding->?")
    }
}
