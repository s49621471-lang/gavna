package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The app-op rewrite is deliberately broader than the ActivityManager one, so what it
 * refuses to touch is the part worth pinning.
 */
class VirtualAppOpsHookTest {

    @Suppress("unused")
    interface FakeAppOpsService {
        fun checkPackage(uid: Int, packageName: String): Int
        fun checkOperation(code: Int, uid: Int, packageName: String): Int
        fun noteOperation(code: Int, uid: Int, packageName: String, attributionTag: String?): Int
        fun startOperation(token: Any?, code: Int, uid: Int, packageName: String): Int

        // Privileged: these act on a *different* package and are gated behind
        // MANAGE_APP_OPS_MODES. Never rewritten, even though UNIQUE does not hold it.
        fun setMode(code: Int, uid: Int, packageName: String, mode: Int)
        fun setUidMode(code: Int, uid: Int, mode: Int)
        fun resetAllModes(reqUserId: Int, reqPackageName: String)
        fun setAudioRestriction(code: Int, usage: Int, uid: Int, mode: Int, exceptionPackages: Array<String>)

        // No package name to rewrite.
        fun getPackagesForOps(ops: IntArray): List<*>
    }

    private fun method(name: String) =
        FakeAppOpsService::class.java.methods.first { it.name == name }

    @Test fun `calls naming the app itself are rewritten`() {
        for (name in listOf("checkPackage", "checkOperation", "noteOperation", "startOperation")) {
            assertThat(VirtualAppOpsHook.carriesOwnPackage(method(name))).isTrue()
        }
    }

    @Test fun `privileged setters are never rewritten`() {
        // The dangerous direction: a rewritten package here would let a guest reach
        // through UNIQUE to change another app's op state, if UNIQUE ever held the
        // permission. It must not depend on UNIQUE continuing not to hold it.
        for (name in listOf("setMode", "resetAllModes", "setAudioRestriction")) {
            assertThat(VirtualAppOpsHook.carriesOwnPackage(method(name))).isFalse()
        }
    }

    @Test fun `calls with no package name are left alone`() {
        assertThat(VirtualAppOpsHook.carriesOwnPackage(method("setUidMode"))).isFalse()
        assertThat(VirtualAppOpsHook.carriesOwnPackage(method("getPackagesForOps"))).isFalse()
    }
}
