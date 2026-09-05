package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The predicate that decides which calls start an activity.
 *
 * Matched structurally for the same reason as the service family: `Activity.startActivity`
 * has gone to `IActivityTaskManager` since Android 10, and `IActivityManager` still
 * declares a `startActivity` that binds cleanly and is never called.
 */
class VirtualActivityTaskManagerHookTest {

    @Suppress("unused")
    interface FakeActivityTaskManager {
        fun startActivity(caller: android.app.IApplicationThread?, callingPackage: String, intent: android.content.Intent, resolvedType: String?, userId: Int): Int
        fun startActivityAsUser(caller: android.app.IApplicationThread?, callingPackage: String, intent: android.content.Intent, userId: Int): Int
        fun startActivityAsCaller(caller: android.app.IApplicationThread?, callingPackage: String, intent: android.content.Intent): Int
        fun startActivityAndWait(caller: android.app.IApplicationThread?, callingPackage: String, intent: android.content.Intent): Any?
        fun startActivityIntentSender(caller: android.app.IApplicationThread?, target: Any?, fillInIntent: android.content.Intent): Int
        fun startActivities(caller: android.app.IApplicationThread?, callingPackage: String, intents: Array<android.content.Intent>, userId: Int): Int

        // No Intent: nothing to route.
        fun startActivityFromRecents(taskId: Int, options: Any?): Int
        // Not an activity start at all.
        fun startNextMatchingActivity(callingActivity: Any?, intent: android.content.Intent): Boolean
        fun finishActivity(token: Any?, code: Int, data: android.content.Intent?): Boolean
        fun moveTaskToFront(caller: android.app.IApplicationThread?, callingPackage: String, taskId: Int): Unit
    }

    private fun method(name: String) =
        FakeActivityTaskManager::class.java.methods.first { it.name == name }

    @Test fun `every activity start that carries an intent is matched`() {
        for (name in listOf(
            "startActivity", "startActivityAsUser", "startActivityAsCaller",
            "startActivityAndWait", "startActivityIntentSender", "startActivities",
        )) {
            assertThat(VirtualActivityTaskManagerHook.startsActivity(method(name))).isTrue()
        }
    }

    @Test fun `starts without an intent, and non-starts, are left alone`() {
        // startActivityFromRecents names a task, not a component.
        // finishActivity and moveTaskToFront carry a package or an Intent as data.
        for (name in listOf(
            "startActivityFromRecents", "finishActivity", "moveTaskToFront",
            "startNextMatchingActivity",
        )) {
            assertThat(VirtualActivityTaskManagerHook.startsActivity(method(name))).isFalse()
        }
    }

    // -----------------------------------------------------------------------------------
    // Settings screens an app opens about itself
    // -----------------------------------------------------------------------------------

    private val guest = "com.example.guest"

    private fun retarget(
        action: String?,
        scheme: String? = null,
        dataPackage: String? = null,
        extraPackage: String? = null,
    ) = VirtualActivityTaskManagerHook.settingsRetarget(
        action, scheme, dataPackage, extraPackage, guest,
    )

    @Test fun `a settings screen the guest opens about itself is retargeted`() {
        // What an app sends for all-files access, overlay, usage access, exact alarms and
        // its own app-info page: the action, and itself named in a package URI.
        val decision = retarget(
            action = "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
            scheme = "package",
            dataPackage = guest,
        )
        assertThat(decision.rewriteData).isTrue()
        assertThat(decision.rewriteExtra).isFalse()
        assertThat(decision.needed).isTrue()
    }

    @Test fun `the notification screens name the app in an extra instead`() {
        val decision = retarget(
            action = "android.settings.APP_NOTIFICATION_SETTINGS",
            extraPackage = guest,
        )
        assertThat(decision.rewriteExtra).isTrue()
        assertThat(decision.needed).isTrue()
    }

    @Test fun `both halves are rewritten when both name the guest`() {
        val decision = retarget(
            action = "android.settings.APP_NOTIFICATION_SETTINGS",
            scheme = "package",
            dataPackage = guest,
            extraPackage = guest,
        )
        assertThat(decision.rewriteData).isTrue()
        assertThat(decision.rewriteExtra).isTrue()
    }

    @Test fun `another app's settings page is left alone`() {
        // A guest may legitimately open somebody else's page, and pointing that at UNIQUE
        // would be a lie about which app the user is looking at.
        val decision = retarget(
            action = "android.settings.APPLICATION_DETAILS_SETTINGS",
            scheme = "package",
            dataPackage = "com.android.chrome",
        )
        assertThat(decision.needed).isFalse()
    }

    @Test fun `a settings screen that is not about a package is left alone`() {
        assertThat(retarget("android.settings.WIFI_SETTINGS").needed).isFalse()
        assertThat(retarget("android.settings.SETTINGS").needed).isFalse()
    }

    @Test fun `an intent that is not a settings intent is left alone`() {
        // The same `package:` URI shape is used by the package installer, and a VIEW of it
        // is not a screen UNIQUE has any business redirecting.
        assertThat(
            retarget("android.intent.action.VIEW", scheme = "package", dataPackage = guest).needed
        ).isFalse()
        assertThat(retarget(null, scheme = "package", dataPackage = guest).needed).isFalse()
    }
}
