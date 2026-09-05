package com.unique.app.engine

import android.content.Context
import android.content.pm.PackageManager
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.VirtualPermissionSync
import com.unique.core.vpermission.PermissionGroup
import com.unique.core.vpermission.PermissionState
import java.io.File

/**
 * The per-instance permission state, from UNIQUE's own process.
 *
 * The record lives in a properties file under `runtime/permissions/<vuid>/`, written by the
 * virtual process. UNIQUE's UI reads and writes the same file, which is what lets a user
 * change a permission for an app that is not running — the common case, since the reason
 * to change one is usually that the app misbehaved and was closed.
 *
 * Two things this deliberately does not do.
 *
 * It does not invent a state for a permission the app never declared: a row exists only
 * for a group the manifest asks for. A settings screen offering Camera to an app that
 * cannot use it is a lie about the app.
 *
 * And it never reports GRANTED for something the *host* does not hold. UNIQUE can only
 * narrow what it has (§6.6.5); a switch that shows on and does nothing is worse than one
 * that explains itself, so `blockedByHost` is carried through to the UI.
 */
object InstancePermissions {

    /** One group as the UI shows it, for one instance. */
    data class Row(
        val group: PermissionGroup,
        val permissions: List<String>,
        val state: PermissionState,
        val blockedByHost: Boolean,
        /**
         * The permissions of this group UNIQUE itself does not hold.
         *
         * Carried to the UI because "blocked" is not the end of the story: the user can
         * unblock it, and the switch is what should ask. See [UniqueBridge].
         */
        val missingHostPermissions: List<String> = emptyList(),
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "group" to group.name,
            "label" to group.label,
            "permissions" to permissions,
            "state" to state.name,
            "blockedByHost" to blockedByHost,
            "granted" to (state == PermissionState.GRANTED),
            "missingHostPermissions" to missingHostPermissions,
        )
    }

    suspend fun rows(context: Context, vuid: Int): List<Row> {
        val instance = UniqueEngine.instances.instance(vuid) ?: return emptyList()
        val declared = declaredPermissions(instance.packageName, instance.versionCode)
        if (declared.isEmpty()) return emptyList()
        val stored = read(vuid, instance.packageName)

        return PermissionGroup.entries.mapNotNull { group ->
            val mine = group.permissions.filter { it in declared }
            if (mine.isEmpty()) return@mapNotNull null

            // A group is held by the host if *any* of its permissions is: Location asks
            // for fine and coarse, and holding coarse alone is a real state that must not
            // read as "UNIQUE has nothing".
            val hostHolds = mine.any {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            val state = when {
                mine.any { stored[it] == PermissionState.GRANTED } -> PermissionState.GRANTED
                mine.all { stored[it] == PermissionState.DENIED } -> PermissionState.DENIED
                else -> PermissionState.ASK
            }
            Row(
                group = group,
                permissions = mine,
                state = if (hostHolds) state else PermissionState.DENIED,
                blockedByHost = !hostHolds,
                missingHostPermissions = mine.filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                },
            )
        }
    }

    /**
     * Sets one group's state and tells the instance's process, if it has one.
     *
     * The file is the record and the running process holds a copy of it in memory, so
     * writing only the file would leave a running app seeing the old answer until it was
     * restarted — and "toggle it and nothing happens" is indistinguishable from a bug.
     */
    suspend fun set(
        context: Context,
        vuid: Int,
        group: PermissionGroup,
        state: PermissionState,
    ): Boolean {
        val instance = UniqueEngine.instances.instance(vuid) ?: return false
        val declared = declaredPermissions(instance.packageName, instance.versionCode)
        val mine = group.permissions.filter { it in declared }
        if (mine.isEmpty()) return false

        val current = read(vuid, instance.packageName).toMutableMap()
        mine.forEach { current[it] = state }
        write(vuid, instance.packageName, current)

        val slot = UniqueEngine.launcher.snapshot()
            .firstOrNull { it.occupant?.vuid == vuid }?.index
        if (slot != null) {
            VirtualPermissionSync.notify(context, slot, mine, state.name)
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "PERMISSION_SET_BY_USER",
            mapOf(
                "vuid" to vuid.toString(),
                "group" to group.name,
                "state" to state.name,
                "liveSlot" to (slot?.toString() ?: "-"),
            ),
        )
        return true
    }

    private fun declaredPermissions(packageName: String, versionCode: Long): Set<String> =
        runCatching {
            ManifestReader
                .fromApk(File(UniqueEngine.storage.model.baseApk(packageName, versionCode)))
                .usesPermissions
                .toSet()
        }.getOrElse { emptySet() }

    private fun stateFile(vuid: Int, packageName: String): File =
        File(UniqueEngine.storage.model.permissionsFile(vuid, packageName))

    private fun read(vuid: Int, packageName: String): Map<String, PermissionState> {
        val file = stateFile(vuid, packageName)
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val name = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
                val state = runCatching { PermissionState.valueOf(value) }.getOrNull()
                    ?: return@mapNotNull null
                name to state
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun write(vuid: Int, packageName: String, states: Map<String, PermissionState>) {
        val file = stateFile(vuid, packageName)
        runCatching {
            file.parentFile?.mkdirs()
            // The same whole-file rewrite the virtual process does. Small enough that a
            // rewrite beats a merge, and a merge between two writers is where the states
            // would start disagreeing.
            file.writeText(states.entries.joinToString("\n") { "${it.key}=${it.value.name}" } + "\n")
        }.onFailure {
            Diagnostics.error(
                DiagChannel.STORAGE, "PERMISSION_WRITE_FAILED",
                mapOf("vuid" to vuid.toString(), "error" to it.toString()),
            )
        }
    }
}
