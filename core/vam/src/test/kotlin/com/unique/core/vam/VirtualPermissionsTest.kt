package com.unique.core.vam

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The predicate that decides which calls are "does this app hold this permission".
 *
 * Worth pinning because the two interfaces that answer it disagree about argument order —
 * `IPackageManager.checkPermission(permission, package, userId)` against
 * `IActivityManager.checkPermission(permission, pid, uid)` — and because
 * `checkUriPermission` asks a different question entirely.
 */
class VirtualPermissionsTest {

    @Suppress("unused")
    interface FakePermissionSurface {
        fun checkPermission(permission: String, packageName: String, userId: Int): Int
        fun checkUidPermission(permission: String, uid: Int): Int

        // A URI grant is not a permission grant. Answering it from the instance store
        // would break FileProvider sharing in a way that looks like a storage bug.
        fun checkUriPermission(uri: Any?, pid: Int, uid: Int, modeFlags: Int): Int
        fun checkGrantUriPermission(callingUid: Int, targetPkg: String, uri: Any?): Int

        // Not a check.
        fun grantRuntimePermission(packageName: String, permission: String, userId: Int)
        fun shouldShowRequestPermissionRationale(permission: String, packageName: String): Boolean
        // A check that answers something else, and does not return a result code.
        fun checkPermissionExists(permission: String): Boolean
    }

    private fun method(name: String) =
        FakePermissionSurface::class.java.methods.first { it.name == name }

    @Test fun `permission checks on either interface are matched`() {
        assertThat(VirtualPermissions.isPermissionCheck(method("checkPermission"))).isTrue()
        assertThat(VirtualPermissions.isPermissionCheck(method("checkUidPermission"))).isTrue()
    }

    @Test fun `uri permission checks are a different question`() {
        assertThat(VirtualPermissions.isPermissionCheck(method("checkUriPermission"))).isFalse()
        assertThat(VirtualPermissions.isPermissionCheck(method("checkGrantUriPermission")))
            .isFalse()
    }

    @Test fun `grants, rationale and non-int checks are left alone`() {
        for (name in listOf(
            "grantRuntimePermission", "shouldShowRequestPermissionRationale",
            "checkPermissionExists",
        )) {
            assertThat(VirtualPermissions.isPermissionCheck(method(name))).isFalse()
        }
    }

    @Test fun `nothing is answered locally before a guest is bound`() {
        // A process that has not grafted must not start answering permission questions:
        // it would be answering for UNIQUE's own components.
        VirtualPermissions.reset()
        assertThat(VirtualPermissions.permissionArgOf(arrayOf("android.permission.CAMERA")))
            .isNull()
        assertThat(VirtualPermissions.shouldShowRationale("android.permission.CAMERA")).isFalse()
    }
}
