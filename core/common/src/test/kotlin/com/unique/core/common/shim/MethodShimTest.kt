package com.unique.core.common.shim

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These tests exist to prove the claim ARCHITECTURE.md makes about signature-agnostic
 * interception, because that claim is the reason UNIQUE is a clean-room engine rather
 * than a fork.
 *
 * [ActivityManagerV33] and [ActivityManagerV36] model the same framework method as it
 * appears in two platform releases: V36 inserts an extra `int` argument in the middle and
 * appends a `String`, exactly the kind of change that breaks index-based patching. The
 * *same* shim definition must work against both.
 */
class MethodShimTest {

    /** Stand-in for a framework interface as of one release. */
    interface ActivityManagerV33 {
        fun startActivity(caller: Any?, callingPackage: String, intent: FakeIntent, userId: Int): Int
        fun getPackageForToken(token: Any?): String
        fun unrelatedMethod(x: Int): Int
    }

    /** The same interface after a platform release inserted and appended arguments. */
    interface ActivityManagerV36 {
        fun startActivity(
            caller: Any?,
            callingPackage: String,
            callingFeatureId: String,   // inserted
            intent: FakeIntent,
            resolvedType: String?,      // inserted
            userId: Int,
            deviceId: Int,              // appended
        ): Int
        fun getPackageForToken(token: Any?): String
        fun unrelatedMethod(x: Int): Int
    }

    data class FakeIntent(val target: String)

    private class RecordingV33 : ActivityManagerV33 {
        var seenPackage: String? = null
        var seenIntent: FakeIntent? = null
        var seenUserId: Int? = null
        var unrelatedCalls = 0
        override fun startActivity(caller: Any?, callingPackage: String, intent: FakeIntent, userId: Int): Int {
            seenPackage = callingPackage; seenIntent = intent; seenUserId = userId; return 0
        }
        override fun getPackageForToken(token: Any?) = "com.unique"
        override fun unrelatedMethod(x: Int): Int { unrelatedCalls++; return x }
    }

    private class RecordingV36 : ActivityManagerV36 {
        var seenPackages = mutableListOf<String>()
        var seenIntent: FakeIntent? = null
        var seenUserId: Int? = null
        var seenDeviceId: Int? = null
        override fun startActivity(
            caller: Any?, callingPackage: String, callingFeatureId: String,
            intent: FakeIntent, resolvedType: String?, userId: Int, deviceId: Int,
        ): Int {
            seenPackages += callingPackage; seenIntent = intent
            seenUserId = userId; seenDeviceId = deviceId; return 0
        }
        override fun getPackageForToken(token: Any?) = "com.unique"
        override fun unrelatedMethod(x: Int) = x
    }

    /**
     * One shim definition, written once, with no knowledge of argument positions.
     * This is the exact text a real UNIQUE shim would use.
     */
    private fun startActivityShim() = shim("startActivity") {
        rewriteAll<String>(matching = { it == "com.example.virtual" }) { "com.unique" }
        rewriteFirst<FakeIntent> { FakeIntent("stub:" + it.target) }
    }

    @Test fun `binds against the old signature`() {
        val real = RecordingV33()
        val (proxy, result) = ShimRegistry(33).register(startActivityShim())
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        assertThat(result.allBound).isTrue()
        proxy.startActivity(null, "com.example.virtual", FakeIntent("MainActivity"), 0)

        assertThat(real.seenPackage).isEqualTo("com.unique")
        assertThat(real.seenIntent).isEqualTo(FakeIntent("stub:MainActivity"))
    }

    @Test fun `the same shim binds against a signature with inserted and appended arguments`() {
        val real = RecordingV36()
        val (proxy, result) = ShimRegistry(36).register(startActivityShim())
            .wrap<ActivityManagerV36>(real, ActivityManagerV36::class.java)

        assertThat(result.allBound).isTrue()
        proxy.startActivity(
            null, "com.example.virtual", "featureId",
            FakeIntent("MainActivity"), "text/plain", 0, 7,
        )

        // The callingPackage was rewritten wherever it appeared…
        assertThat(real.seenPackages).contains("com.unique")
        // …the unrelated String arguments were left alone…
        assertThat(real.seenPackages).doesNotContain("featureId")
        // …the Intent was rewritten at its new position…
        assertThat(real.seenIntent).isEqualTo(FakeIntent("stub:MainActivity"))
        // …and the newly appended argument passed through untouched.
        assertThat(real.seenDeviceId).isEqualTo(7)
    }

    @Test fun `a predicate keeps unrelated arguments of the same type untouched`() {
        val real = RecordingV36()
        val (proxy, _) = ShimRegistry(36).register(startActivityShim())
            .wrap<ActivityManagerV36>(real, ActivityManagerV36::class.java)

        proxy.startActivity(null, "com.other.app", "featureId", FakeIntent("A"), null, 0, 1)
        assertThat(real.seenPackages).containsExactly("com.other.app")
    }

    @Test fun `methods with no shim are invoked untouched`() {
        val real = RecordingV33()
        val (proxy, _) = ShimRegistry(33).register(startActivityShim())
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        assertThat(proxy.unrelatedMethod(41)).isEqualTo(41)
        assertThat(real.unrelatedCalls).isEqualTo(1)
    }

    @Test fun `a shim that cannot bind is reported instead of failing silently`() {
        val real = RecordingV33()
        val absent = shim("methodRemovedInThisRelease") { rewriteAll<String> { "x" } }
        val (_, result) = ShimRegistry(33).register(startActivityShim(), absent)
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        assertThat(result.bound).contains("startActivity")
        assertThat(result.unbound).containsExactly("methodRemovedInThisRelease")
        assertThat(result.allBound).isFalse()
    }

    @Test fun `api range gates a shim off`() {
        val real = RecordingV33()
        val legacyOnly = shim("startActivity") {
            maxApi = 30
            rewriteAll<String> { "should.not.apply" }
        }
        val (proxy, _) = ShimRegistry(33).register(legacyOnly)
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        proxy.startActivity(null, "com.example.virtual", FakeIntent("A"), 0)
        assertThat(real.seenPackage).isEqualTo("com.example.virtual")
    }

    @Test fun `result rewriting works`() {
        val real = RecordingV33()
        val s = shim("getPackageForToken") { rewriteResult { "com.example.virtual" } }
        val (proxy, _) = ShimRegistry(33).register(s)
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        assertThat(proxy.getPackageForToken(null)).isEqualTo("com.example.virtual")
    }

    @Test fun `full replacement never reaches the real implementation`() {
        val real = RecordingV33()
        val s = shim("startActivity") { replaceWith { -1 } }
        val (proxy, _) = ShimRegistry(33).register(s)
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        assertThat(proxy.startActivity(null, "com.example.virtual", FakeIntent("A"), 0)).isEqualTo(-1)
        assertThat(real.seenPackage).isNull()
    }

    @Test fun `primitive int arguments are matched through boxing`() {
        val real = RecordingV36()
        val s = shim("startActivity") {
            rewriteLast<Int>(matching = { it == 7 }) { 99 }
        }
        val (proxy, _) = ShimRegistry(36).register(s)
            .wrap<ActivityManagerV36>(real, ActivityManagerV36::class.java)

        proxy.startActivity(null, "p", "f", FakeIntent("A"), null, 0, 7)
        assertThat(real.seenDeviceId).isEqualTo(99)
        assertThat(real.seenUserId).isEqualTo(0)
    }

    @Test fun `a replacement can answer some calls and delegate the rest`() {
        // This is the shape every virtual system service needs: answer for the virtual
        // package, hand everything else to the real implementation.
        val real = RecordingV33()
        val s = shim("getPackageForToken") {
            replaceWith { call ->
                if (call.firstArgOf<String>() == "virtual") "com.example.virtual" else call.proceed()
            }
        }
        val (proxy, _) = ShimRegistry(33).register(s)
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        assertThat(proxy.getPackageForToken("virtual")).isEqualTo("com.example.virtual")
        assertThat(proxy.getPackageForToken("other")).isEqualTo("com.unique")
    }

    @Test fun `proceed sees arguments already rewritten by the rules`() {
        val real = RecordingV33()
        val s = shim("startActivity") {
            rewriteAll<String>(matching = { it == "com.example.virtual" }) { "com.unique" }
            replaceWith { call -> call.proceed() }
        }
        val (proxy, _) = ShimRegistry(33).register(s)
            .wrap<ActivityManagerV33>(real, ActivityManagerV33::class.java)

        proxy.startActivity(null, "com.example.virtual", FakeIntent("A"), 0)
        assertThat(real.seenPackage).isEqualTo("com.unique")
    }

    @Test fun `exceptions from the real implementation propagate unwrapped`() {
        val throwing = object : ActivityManagerV33 {
            override fun startActivity(c: Any?, p: String, i: FakeIntent, u: Int): Int =
                throw IllegalStateException("boom")
            override fun getPackageForToken(token: Any?) = ""
            override fun unrelatedMethod(x: Int) = x
        }
        val (proxy, _) = ShimRegistry(33).register(startActivityShim())
            .wrap<ActivityManagerV33>(throwing, ActivityManagerV33::class.java)

        val e = runCatching {
            proxy.startActivity(null, "com.example.virtual", FakeIntent("A"), 0)
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalStateException::class.java)
        assertThat(e).hasMessageThat().isEqualTo("boom")
    }
}
