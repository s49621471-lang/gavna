package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Telling a notification id from a user id.
 *
 * The reason this needs a test: unlike a package name, an `Int` argument carries no
 * evidence about what it means. Rewriting every int on `enqueueNotificationWithTag`
 * namespaced the *user id* and the platform answered "asks to run as user 1048576" —
 * which is `1 shl 20`, the namespacing applied to user 0.
 */
class VirtualNotificationHookTest {

    @Suppress("unused")
    interface FakeNotificationManager {
        fun enqueueNotificationWithTag(
            pkg: String, opPkg: String, tag: String?, id: Int, notification: Any?, userId: Int,
        )
        fun cancelNotificationWithTag(
            pkg: String, opPkg: String, tag: String?, id: Int, userId: Int,
        )

        // The trap: one int, and it is the user id.
        fun cancelAllNotifications(pkg: String, userId: Int)

        // Not about a notification id at all.
        fun createNotificationChannels(pkg: String, channels: Any?)
        fun enqueueToast(pkg: String, token: Any?, text: CharSequence?, duration: Int, displayId: Int)
        fun areNotificationsEnabled(pkg: String): Boolean
    }

    private fun method(name: String) =
        FakeNotificationManager::class.java.methods.first { it.name == name }

    @Test fun `enqueue and cancel by tag carry an id and a user id`() {
        assertThat(VirtualNotificationHook.carriesNotificationId(method("enqueueNotificationWithTag")))
            .isTrue()
        assertThat(VirtualNotificationHook.carriesNotificationId(method("cancelNotificationWithTag")))
            .isTrue()
    }

    @Test fun `a lone int is the user id and is never namespaced`() {
        assertThat(VirtualNotificationHook.carriesNotificationId(method("cancelAllNotifications")))
            .isFalse()
    }

    @Test fun `channels, toasts and queries are not notification ids`() {
        for (name in listOf(
            "createNotificationChannels", "enqueueToast", "areNotificationsEnabled",
        )) {
            assertThat(VirtualNotificationHook.carriesNotificationId(method(name))).isFalse()
        }
    }

    @Test fun `notification ids round-trip and separate two instances`() {
        val a = StubRouter.hostNotificationId(0, 4711)
        val b = StubRouter.hostNotificationId(1, 4711)
        assertThat(a).isNotEqualTo(b)
        assertThat(StubRouter.virtualNotificationId(a)).isEqualTo(4711)
        assertThat(StubRouter.virtualNotificationId(b)).isEqualTo(4711)
        assertThat(StubRouter.notificationOwner(a)).isEqualTo(0)
        assertThat(StubRouter.notificationOwner(b)).isEqualTo(1)
        assertThat(a).isAtLeast(0)
        assertThat(StubRouter.hostNotificationId(2047, 0xFFFFF)).isAtLeast(0)
    }
}
