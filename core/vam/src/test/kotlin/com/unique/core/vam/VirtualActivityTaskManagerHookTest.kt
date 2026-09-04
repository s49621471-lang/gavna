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
}
