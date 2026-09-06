package com.unique.core.vpermission

import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a virtual app is told when it asks whether it holds a permission.
 *
 * The rule has two halves and both have to hold at once. UNIQUE **narrows** — it can never
 * hand a guest something the host does not hold — and *undecided* means different things
 * for different permissions. Getting the second half wrong is what left every guest on a
 * real phone without `INTERNET`: there is no dialog for it, no app requests it, and no
 * action the user could have taken would have changed the answer.
 */
class PermissionStoreTest {

    private companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val INTERNET = "android.permission.INTERNET"
        const val OWN = "com.example.app.permission.C2D_MESSAGE"
        const val READ_EXTERNAL = "android.permission.READ_EXTERNAL_STORAGE"
        const val WRITE_EXTERNAL = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val MANAGE_EXTERNAL = "android.permission.MANAGE_EXTERNAL_STORAGE"
        const val PKG = "com.example.app"
    }

    private fun store(
        hostHolds: Set<String> = setOf(CAMERA, INTERNET),
        selfDefined: Set<String> = emptySet(),
    ) = PermissionStore(
        hostGrants = {
            if (it in hostHolds) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
        },
        isSelfDefined = { it in selfDefined },
    )

    private fun PermissionStore.state(permission: String) =
        effectiveState(0, PKG, permission).state

    private fun PermissionStore.granted(permission: String) =
        checkPermission(0, PKG, permission) == PackageManager.PERMISSION_GRANTED

    // -----------------------------------------------------------------------------
    // Install-time permissions
    // -----------------------------------------------------------------------------

    @Test fun `an undecided install-time permission is granted, not denied`() {
        val store = store()
        assertThat(store.stored(0, PKG, INTERNET)).isEqualTo(PermissionState.ASK)
        assertThat(store.state(INTERNET)).isEqualTo(PermissionState.GRANTED)
        assertThat(store.granted(INTERNET)).isTrue()
    }

    @Test fun `an install-time permission the host lacks is still denied`() {
        // The narrowing rule is not suspended for install-time permissions: a grant UNIQUE
        // does not hold would be a lie the platform refuses at the first real call.
        val store = store(hostHolds = emptySet())
        val effective = store.effectiveState(0, PKG, INTERNET)
        assertThat(effective.state).isEqualTo(PermissionState.DENIED)
        assertThat(effective.blockedByHost).isTrue()
    }

    // -----------------------------------------------------------------------------
    // Runtime permissions
    // -----------------------------------------------------------------------------

    @Test fun `an undecided runtime permission is the user's to decide`() {
        val store = store()
        assertThat(store.state(CAMERA)).isEqualTo(PermissionState.ASK)
        assertThat(store.granted(CAMERA)).isFalse()
    }

    @Test fun `a runtime permission the user granted is granted`() {
        val store = store()
        store.set(0, PKG, CAMERA, PermissionState.GRANTED)
        assertThat(store.granted(CAMERA)).isTrue()
    }

    @Test fun `a runtime permission the user denied stays denied`() {
        val store = store()
        store.set(0, PKG, CAMERA, PermissionState.DENIED)
        val effective = store.effectiveState(0, PKG, CAMERA)
        assertThat(effective.state).isEqualTo(PermissionState.DENIED)
        // Not the host's fault, and the UI shows the two cases differently.
        assertThat(effective.blockedByHost).isFalse()
    }

    @Test fun `a stored grant cannot outlive the host's own`() {
        val store = store(hostHolds = emptySet())
        store.set(0, PKG, CAMERA, PermissionState.GRANTED)
        assertThat(store.granted(CAMERA)).isFalse()
        assertThat(store.effectiveState(0, PKG, CAMERA).blockedByHost).isTrue()
    }

    // -----------------------------------------------------------------------------
    // Permissions the guest defines itself
    // -----------------------------------------------------------------------------

    @Test fun `a permission the guest defines itself is granted`() {
        // The platform grants an app the permissions it declares, and nothing else on the
        // device holds them — so the host check, applied to one of these, denies every
        // time. An app guarding its own provider this way could not reach it.
        val store = store(hostHolds = emptySet(), selfDefined = setOf(OWN))
        assertThat(store.granted(OWN)).isTrue()
        assertThat(store.effectiveState(0, PKG, OWN).blockedByHost).isFalse()
    }

    @Test fun `a permission another app defines is denied and blamed on the host`() {
        // com.google.android.providers.gsf.permission.READ_GSERVICES, from a real run.
        // UNIQUE genuinely does not hold it, and saying so is the honest answer.
        val store = store(hostHolds = emptySet())
        val effective = store.effectiveState(0, PKG, "com.other.app.PERMISSION")
        assertThat(effective.state).isEqualTo(PermissionState.DENIED)
        assertThat(effective.blockedByHost).isTrue()
    }

    // -----------------------------------------------------------------------------
    // Storage: the permissions UNIQUE serves itself
    // -----------------------------------------------------------------------------

    @Test fun `external storage is granted even though the host can never hold it`() {
        // The whole point. UNIQUE targets SDK 36, and since 33 the platform auto-denies
        // READ_EXTERNAL_STORAGE to any such app: no dialog, no settings toggle, nothing
        // the user could do. Intersecting with the host therefore denied it forever, and
        // a Unity game read that as "storage is unavailable" and skipped its own OBB.
        val store = store(hostHolds = emptySet())
        val effective = store.effectiveState(0, PKG, READ_EXTERNAL)
        assertThat(effective.state).isEqualTo(PermissionState.GRANTED)
        assertThat(effective.blockedByHost).isFalse()
        assertThat(store.granted(WRITE_EXTERNAL)).isTrue()
        assertThat(store.granted(MANAGE_EXTERNAL)).isTrue()
    }

    @Test fun `external storage is granted before anyone is asked`() {
        // GRANTED, not ASK: there is no host dialog that could ever answer it, so ASK
        // would mean "denied until a question nobody can put to the user is answered".
        val store = store(hostHolds = emptySet())
        assertThat(store.stored(0, PKG, READ_EXTERNAL)).isEqualTo(PermissionState.ASK)
        assertThat(store.state(READ_EXTERNAL)).isEqualTo(PermissionState.GRANTED)
    }

    @Test fun `the user can still deny an instance its files`() {
        // Self-served widens what the *host* can block, not what the user decides. An
        // instance the user switched Files off for still sees denied.
        val store = store(hostHolds = emptySet())
        store.set(0, PKG, READ_EXTERNAL, PermissionState.DENIED)
        val effective = store.effectiveState(0, PKG, READ_EXTERNAL)
        assertThat(effective.state).isEqualTo(PermissionState.DENIED)
        assertThat(effective.blockedByHost).isFalse()
    }

    @Test fun `media permissions are not self-served`() {
        // READ_MEDIA_IMAGES reaches the device's real MediaStore, which is the user's
        // photo library and not the instance's directory. UNIQUE cannot show a guest a
        // photo the user has not let UNIQUE see, and saying otherwise would be the lie
        // the narrowing rule exists to prevent.
        val store = store(hostHolds = emptySet())
        val effective = store.effectiveState(0, PKG, "android.permission.READ_MEDIA_IMAGES")
        assertThat(effective.state).isEqualTo(PermissionState.DENIED)
        assertThat(effective.blockedByHost).isTrue()
    }

    // -----------------------------------------------------------------------------
    // Per-instance isolation, which is the point of instances
    // -----------------------------------------------------------------------------

    @Test fun `two instances of one app answer separately`() {
        val store = store()
        store.set(0, PKG, CAMERA, PermissionState.GRANTED)
        store.set(1, PKG, CAMERA, PermissionState.DENIED)
        assertThat(store.checkPermission(0, PKG, CAMERA))
            .isEqualTo(PackageManager.PERMISSION_GRANTED)
        assertThat(store.checkPermission(1, PKG, CAMERA))
            .isEqualTo(PackageManager.PERMISSION_DENIED)
    }

    @Test fun `clearing one instance leaves the other alone`() {
        val store = store()
        store.set(0, PKG, CAMERA, PermissionState.GRANTED)
        store.set(1, PKG, CAMERA, PermissionState.GRANTED)
        store.clear(0, PKG)
        assertThat(store.stored(0, PKG, CAMERA)).isEqualTo(PermissionState.ASK)
        assertThat(store.stored(1, PKG, CAMERA)).isEqualTo(PermissionState.GRANTED)
    }

    @Test fun `a group is set as one and snapshots only what was set`() {
        val store = store()
        store.setGroup(0, PKG, PermissionGroup.LOCATION, PermissionState.GRANTED)
        val snapshot = store.snapshot(0, PKG)
        assertThat(snapshot.keys).containsExactlyElementsIn(PermissionGroup.LOCATION.permissions)
        assertThat(snapshot.values.toSet()).containsExactly(PermissionState.GRANTED)
    }

    @Test fun `every group the UI offers is made of runtime permissions`() {
        // An install-time permission cannot be revoked on a real device, so a switch for
        // one would be a control that does nothing the platform recognises.
        for (group in PermissionGroup.entries) {
            for (permission in group.permissions) {
                assertThat(
                    com.unique.core.common.permission.PlatformPermissions.isRuntime(permission)
                ).isTrue()
            }
        }
    }
}
