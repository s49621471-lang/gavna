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
        fun startActivity(caller: android.app.IApplicationThread?, callingPackage: String, intent: android.content.Intent): Int
        fun getContentProvider(caller: android.app.IApplicationThread?, callingPackage: String, name: String, userId: Int): Any?
        fun bindService(caller: android.app.IApplicationThread?, token: Any?, callingPackage: String): Int

        // The service family, spelled as each Android release spells it. Only the last is
        // reachable from ContextImpl on API 31+; the earlier two are dead but still
        // present, which is exactly what made the name-based shim look healthy.
        fun startService(caller: android.app.IApplicationThread?, service: android.content.Intent, callingPackage: String): Any?
        fun bindService(caller: android.app.IApplicationThread?, token: Any?, service: android.content.Intent, callingPackage: String): Int
        fun bindIsolatedService(caller: android.app.IApplicationThread?, service: android.content.Intent, instanceName: String): Int
        fun bindServiceInstance(caller: android.app.IApplicationThread?, service: android.content.Intent, instanceName: String, callingPackage: String): Int
        fun stopService(caller: android.app.IApplicationThread?, service: android.content.Intent, resolvedType: String): Int
        fun peekService(service: android.content.Intent, resolvedType: String, callingPackage: String): Any?

        // Sent back to AMS from ActivityThread; must carry the stub intent again.
        fun publishService(token: Any?, intent: android.content.Intent, service: Any?)
        fun unbindFinished(token: Any?, service: android.content.Intent, doRebind: Boolean)

        // Service methods that are keyed on a connection or a token, not an Intent.
        fun unbindService(connection: Any?): Boolean
        fun stopServiceToken(className: Any?, token: Any?, startId: Int): Boolean
        fun serviceDoneExecuting(token: Any?, type: Int, startId: Int, res: Int)

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

    private fun methodTaking(name: String, vararg types: Class<*>) =
        FakeActivityManager::class.java.methods.first {
            it.name == name && it.parameterTypes.toList() == types.toList()
        }

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

    // ---------------------------------------------------------------------------------
    // The service family. This is the predicate that decides whether a bind reaches a
    // stub at all, and it is matched structurally because the platform renames these.
    // ---------------------------------------------------------------------------------

    @Test fun `every spelling of the service dispatch family is matched`() {
        // bindServiceInstance is the one ContextImpl actually calls from Android 12 on.
        // Shimming only bindService and bindIsolatedService by name left it unrewritten
        // and every bind reached AMS naming a package it has never installed.
        for (name in listOf(
            "startService", "bindIsolatedService", "bindServiceInstance",
            "stopService", "peekService", "publishService", "unbindFinished",
        )) {
            assertThat(VirtualActivityManagerHook.dispatchesServiceIntent(method(name)))
                .isTrue()
        }
        assertThat(
            VirtualActivityManagerHook.dispatchesServiceIntent(
                methodTaking(
                    "bindService", android.app.IApplicationThread::class.java, Any::class.java,
                    android.content.Intent::class.java, String::class.java,
                )
            )
        ).isTrue()
    }

    @Test fun `service methods keyed on a connection or token are not intent rewrites`() {
        for (name in listOf("unbindService", "stopServiceToken", "serviceDoneExecuting")) {
            assertThat(VirtualActivityManagerHook.dispatchesServiceIntent(method(name)))
                .isFalse()
        }
    }

    @Test fun `non-service intent dispatch is left to its own shim`() {
        // startActivity carries an Intent but is not a service call: routing it onto a
        // service stub would launch the wrong component.
        assertThat(VirtualActivityManagerHook.dispatchesServiceIntent(method("startActivity")))
            .isFalse()
        assertThat(VirtualActivityManagerHook.dispatchesServiceIntent(method("setServiceForeground")))
            .isFalse()
    }
}
