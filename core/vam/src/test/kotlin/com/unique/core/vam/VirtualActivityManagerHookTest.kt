package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The predicate that decides which arguments get the host's package name.
 *
 * Worth testing on its own because the failure mode is asymmetric: missing a method
 * produces a `SecurityException` the developer can read, while matching one too many
 * silently rewrites a package name that was *data*. `forceStopPackage` is the example
 * that matters — a guest calling it with UNIQUE's name would stop UNIQUE.
 */
class VirtualActivityManagerHookTest {

    /** Stand-in for the shape of the real interface. */
    @Suppress("unused")
    interface FakeActivityManager {
        // Calls an app makes on its own behalf: identified by IApplicationThread.
        fun startActivity(caller: android.app.IApplicationThread?, callingPackage: String, intent: Any?): Int
        fun getContentProvider(caller: android.app.IApplicationThread?, callingPackage: String, name: String, userId: Int): Any?
        fun bindService(caller: android.app.IApplicationThread?, token: Any?, callingPackage: String): Int

        // Identity-bearing but without an IApplicationThread.
        fun getIntentSenderWithFeature(type: Int, packageName: String, token: Any?): Any?
        fun setServiceForeground(className: Any?, token: Any?, id: Int, notification: Any?, flags: Int): Unit

        // Takes a package name as DATA. Must never be rewritten.
        fun forceStopPackage(packageName: String, userId: Int)
        fun killBackgroundProcesses(packageName: String, userId: Int)
        fun getPackageProcessState(packageName: String, callingPackage: String): Int
    }

    
    private fun method(name: String) =
        FakeActivityManager::class.java.methods.first { it.name == name }

    @Test fun `calls carrying an application thread are caller-identity calls`() {
        for (name in listOf("startActivity", "getContentProvider", "bindService")) {
            assertThat(VirtualActivityManagerHook.carriesCallerIdentity(method(name)))
                .isTrue()
        }
    }

    @Test fun `identity methods without an application thread are allowlisted`() {
        for (name in listOf("getIntentSenderWithFeature", "setServiceForeground")) {
            assertThat(VirtualActivityManagerHook.carriesCallerIdentity(method(name)))
                .isTrue()
        }
    }

    @Test fun `methods that take a package as data are never rewritten`() {
        // The dangerous direction. forceStopPackage with a rewritten argument would make
        // a guest's call stop UNIQUE itself.
        for (name in listOf("forceStopPackage", "killBackgroundProcesses", "getPackageProcessState")) {
            assertThat(VirtualActivityManagerHook.carriesCallerIdentity(method(name)))
                .isFalse()
        }
    }
}
