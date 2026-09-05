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
        fun forceStopPackageEvenWhenStopping(packageName: String, userId: Int)
        fun killBackgroundProcesses(packageName: String, userId: Int)
        fun clearApplicationUserData(packageName: String, observer: Any?, userId: Int): Boolean
        fun crashApplicationWithType(uid: Int, initialPid: Int, packageName: String, type: Int)

        // Asks a question about a package. Safe to rewrite, and the reason the structural
        // rule exists: a real device refused to launch an app at all because
        // getHistoricalProcessExitReasons was going out with the guest's own name and
        // needs android.permission.DUMP for any package but the caller's.
        fun getHistoricalProcessExitReasons(packageName: String, pid: Int, maxNum: Int, userId: Int): Any?
        fun getPackageProcessState(packageName: String, callingPackage: String): Int
        fun isAppFreezerEnabled(packageName: String): Boolean
        fun checkPermissionForPackage(permission: String, packageName: String): Int
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

    @Test fun `methods that act on a package are never rewritten`() {
        // The dangerous direction, and the only one that matters: the rewrite fires when
        // an argument *equals the virtual package*, so what it would turn "stop me" into
        // is "stop UNIQUE".
        for (name in listOf(
            "forceStopPackage",
            "forceStopPackageEvenWhenStopping",
            "killBackgroundProcesses",
            "clearApplicationUserData",
            "crashApplicationWithType",
        )) {
            assertThat(VirtualActivityManagerHook.carriesCallerIdentity(method(name)))
                .isFalse()
        }
    }

    @Test fun `methods that ask about a package are rewritten`() {
        // The other direction, and the one a physical device found first. These take a
        // package name and only read: the virtual name is not one this device knows, so
        // wherever it appears it can only mean "me", and the host's name is the one the
        // platform will accept for that.
        //
        // `getPackageProcessState` used to be grouped with the destructive methods above.
        // That was over-cautious rather than safe: the guest runs *in* UNIQUE's process,
        // so UNIQUE's process state is the true answer, and refusing to rewrite it gave
        // the guest a SecurityException instead.
        for (name in listOf(
            "getHistoricalProcessExitReasons",
            "getPackageProcessState",
            "isAppFreezerEnabled",
            "checkPermissionForPackage",
        )) {
            assertThat(VirtualActivityManagerHook.carriesCallerIdentity(method(name)))
                .isTrue()
        }
    }

    @Test fun `a question word is not enough on its own`() {
        // Both halves of the rule have to hold. A method with no String has nothing to
        // rewrite, and one whose name contains an acting verb is excluded however it
        // starts — which is what stops a future `getAndClearFoo(String)` from slipping in.
        assertThat(VirtualActivityManagerHook.carriesCallerIdentity(method("unbindService")))
            .isFalse()
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

    // ---------------------------------------------------------------------------------
    // Foreground services. Three ints, none of which says what it is.
    // ---------------------------------------------------------------------------------

    @Suppress("unused")
    interface FakeForegroundSurface {
        fun setServiceForeground(
            className: android.content.ComponentName, token: Any?, id: Int,
            notification: android.app.Notification, flags: Int, foregroundServiceType: Int,
        )
        // A hypothetical release that drops the type argument: the shape no longer says
        // which int is which, so the shim must decline rather than guess.
        fun setServiceForeground(
            className: android.content.ComponentName, token: Any?, id: Int,
            notification: android.app.Notification,
        )
        fun setServiceForegroundWithoutNotification(className: android.content.ComponentName, id: Int, flags: Int, type: Int)
        fun stopServiceToken(className: android.content.ComponentName, token: Any?, startId: Int): Boolean
    }

    private fun foregroundMethod(paramCount: Int) =
        FakeForegroundSurface::class.java.methods.first {
            it.name == "setServiceForeground" && it.parameterTypes.size == paramCount
        }

    @Test fun `the full setServiceForeground shape is matched`() {
        assertThat(VirtualActivityManagerHook.startsForegroundService(foregroundMethod(6)))
            .isTrue()
    }

    @Test fun `a shape that cannot be read is declined, not guessed`() {
        // Fewer than three ints means first-is-id and last-is-type no longer holds. The
        // platform's own behaviour is a visible failure; a mangled type is not.
        assertThat(VirtualActivityManagerHook.startsForegroundService(foregroundMethod(4)))
            .isFalse()
        assertThat(
            VirtualActivityManagerHook.startsForegroundService(
                FakeForegroundSurface::class.java.methods
                    .first { it.name == "setServiceForegroundWithoutNotification" }
            )
        ).isFalse()
        assertThat(
            VirtualActivityManagerHook.startsForegroundService(
                FakeForegroundSurface::class.java.methods.first { it.name == "stopServiceToken" }
            )
        ).isFalse()
    }

    @Test fun `non-service intent dispatch is left to its own shim`() {
        // startActivity carries an Intent but is not a service call: routing it onto a
        // service stub would launch the wrong component.
        assertThat(VirtualActivityManagerHook.dispatchesServiceIntent(method("startActivity")))
            .isFalse()
        assertThat(VirtualActivityManagerHook.dispatchesServiceIntent(method("setServiceForeground")))
            .isFalse()
    }

    // -----------------------------------------------------------------------------------
    // Where the export flags sit in registerReceiver
    // -----------------------------------------------------------------------------------

    @Suppress("unused")
    interface FakeReceiverRegistry {
        // API 33-35, and API 30-32 before the feature id: `flags` is last in both.
        fun registerReceiverWithFeature(
            caller: android.app.IApplicationThread?, callerPackage: String,
            callingFeatureId: String?, receiverId: String?, receiver: Any?,
            filter: android.content.IntentFilter, requiredPermission: String?,
            userId: Int, flags: Int,
        ): android.content.Intent?

        // The pre-Q spelling, still on the interface and never called.
        fun registerReceiver(
            caller: android.app.IApplicationThread?, callerPackage: String,
            receiver: Any?, filter: android.content.IntentFilter,
            requiredPermission: String?, userId: Int, flags: Int,
        ): android.content.Intent?

        // Nothing to find. Answering with an index anyway would rewrite a token.
        fun unregisterReceiver(receiver: Any?)
    }

    private fun receiverMethod(name: String) =
        FakeReceiverRegistry::class.java.methods.first { it.name == name }

    @Test fun `the export flags are the last int, whichever spelling is called`() {
        for (name in listOf("registerReceiverWithFeature", "registerReceiver")) {
            val types = receiverMethod(name).parameterTypes
            val index = VirtualActivityManagerHook.receiverFlagsIndex(types)
            assertThat(index).isEqualTo(types.size - 1)
            // And it is `userId` that sits before it, which is the pair the assumption
            // rests on: an int found anywhere else would be the wrong one to rewrite.
            assertThat(types[index - 1]).isEqualTo(Int::class.javaPrimitiveType)
        }
    }

    @Test fun `a method with no int argument yields no index`() {
        val types = receiverMethod("unregisterReceiver").parameterTypes
        assertThat(VirtualActivityManagerHook.receiverFlagsIndex(types)).isEqualTo(-1)
    }
}
