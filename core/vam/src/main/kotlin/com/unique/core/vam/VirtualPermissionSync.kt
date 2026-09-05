package com.unique.core.vam

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vpermission.PermissionState

/**
 * Tells a running virtual process that the user changed one of its permissions.
 *
 * The record on disk is the source of truth and is read at bootstrap, so a change made
 * while the app is closed needs nothing else. A change made while it is *running* does:
 * the process holds the record in memory — deliberately, because a permission check is on
 * the hot path and reading a file for each one would be absurd — so writing only the file
 * would leave a running app seeing the old answer until it was restarted. "I turned Camera
 * off and it still took a photo" is not a state to ship.
 *
 * It travels through the slot's stub provider, the same channel §6.4.0 built for
 * cross-process provider access. A second mechanism for a second small message is how a
 * codebase ends up with four ways to talk to a virtual process.
 */
object VirtualPermissionSync {

    const val METHOD_SET_PERMISSION = "unique.setPermission"
    const val KEY_PERMISSIONS = "unique.permissions"
    const val KEY_STATE = "unique.state"

    /** Called in UNIQUE's own process. Best-effort: the file has already been written. */
    fun notify(context: Context, slot: Int, permissions: List<String>, state: String) {
        val stub = Uri.parse(
            "content://" + VirtualProviderRouter.stubAuthority(context.packageName, slot)
        )
        // Unstable: UNIQUE must not become killable because a virtual process it happens
        // to be telling something to has died (§6.4.0).
        runCatching {
            context.contentResolver.acquireUnstableContentProviderClient(stub)?.use { client ->
                client.call(
                    METHOD_SET_PERMISSION, null,
                    Bundle().apply {
                        putStringArray(KEY_PERMISSIONS, permissions.toTypedArray())
                        putString(KEY_STATE, state)
                    },
                )
            }
        }.onFailure {
            // Not an error the user should see: the change is on disk and takes effect at
            // the next start. Recorded because "took effect late" is otherwise invisible.
            Diagnostics.warn(
                DiagChannel.PROCESS, "PERMISSION_SYNC_FAILED",
                mapOf("slot" to slot.toString(), "error" to it.toString()),
            )
        }
    }

    /** Called in the virtual process, from its stub provider. */
    fun apply(extras: Bundle?): Bundle? {
        val permissions = extras?.getStringArray(KEY_PERMISSIONS) ?: return null
        val state = extras.getString(KEY_STATE)
            ?.let { runCatching { PermissionState.valueOf(it) }.getOrNull() }
            ?: return null
        val applied = VirtualPermissions.applyExternalChange(permissions.toList(), state)
        Diagnostics.info(
            DiagChannel.PROCESS, "PERMISSION_SYNC_APPLIED",
            mapOf(
                "permissions" to permissions.joinToString(",").take(200),
                "state" to state.name,
                "applied" to applied.toString(),
            ),
        )
        return Bundle.EMPTY
    }
}
