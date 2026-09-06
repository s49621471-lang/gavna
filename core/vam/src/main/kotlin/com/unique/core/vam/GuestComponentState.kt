package com.unique.core.vam

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import java.io.File
import java.util.Properties

/**
 * Whether each of the guest's own components is enabled, per instance.
 *
 * An app turning one of its own components on or off is ordinary: an `<activity-alias>`
 * enabled and its sibling disabled is *the* way to change a launcher icon, a keyboard or
 * a share target is enabled once the user opts in, and a receiver is disabled to stop
 * being woken. Under UNIQUE it was fatal:
 *
 * ```
 * SecurityException: Attempt to change component state; pid=18448, uid=10108,
 *   component=ComponentInfo{com.kunzisoft.keepass.libre/…}
 *     at ApplicationPackageManager.setComponentEnabledSetting
 *     at com.kunzisoft.keepass.activities.FileDatabaseSelectActivity.onCreate
 * ```
 *
 * `PackageManagerService` checks that the component belongs to the calling uid, and the
 * guest's package is not one it has installed at all — so there is nothing for it to
 * store and no way for it to agree. The state has to live here.
 *
 * Kept in a properties file beside the instance's permission record, because an app that
 * disables its alternative icon expects it to stay disabled: an in-memory answer would be
 * correct for one session and wrong on the next launch, which is worse than either.
 *
 * ## What consumes it
 *
 * `getComponentEnabledSetting` answers from here, and so does every intent resolution
 * against the guest's own manifest — [isEnabled] is what `VirtualIntentResolver` and
 * `GuestIntentResolution` filter on, so a disabled alias stops matching, which is the
 * behaviour the app asked for. `ComponentInfo.enabled` carries it too.
 */
internal object GuestComponentState {

    private const val APPLICATION_KEY = "<application>"

    private class Binding(
        val vuid: Int,
        val packageName: String,
        val file: File,
        val states: MutableMap<String, Int>,
    )

    @Volatile private var binding: Binding? = null

    /** Loads the instance's record. Safe to call before the guest's Application exists. */
    @Synchronized
    fun bind(vuid: Int, packageName: String, context: Context) {
        val host = context.applicationContext ?: context
        val model = VirtualPathModel(HostPaths.filesRoot(host))
        val file = File(model.componentStateFile(vuid, packageName))
        val states = HashMap<String, Int>()
        runCatching {
            if (file.isFile) {
                val props = Properties()
                file.inputStream().use(props::load)
                for (name in props.stringPropertyNames()) {
                    props.getProperty(name)?.toIntOrNull()?.let { states[name] = it }
                }
            }
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "COMPONENT_STATE_UNREADABLE",
                mapOf("file" to file.path, "error" to it.toString()),
            )
        }
        binding = Binding(vuid, packageName, file, states)
        if (states.isNotEmpty()) {
            Diagnostics.info(
                DiagChannel.PROCESS, "COMPONENT_STATE_RESTORED",
                mapOf("package" to packageName, "vuid" to vuid.toString(),
                      "entries" to states.size.toString()),
            )
        }
    }

    @Synchronized
    fun reset() {
        binding = null
    }

    /** True when [component] names the guest and this process is bound to it. */
    fun owns(component: ComponentName?): Boolean {
        val b = binding ?: return false
        return component != null && component.packageName == b.packageName
    }

    /** True when [packageName] is the guest this process is bound to. */
    fun owns(packageName: String?): Boolean = packageName != null && packageName == binding?.packageName

    /** The stored setting for one component, or `COMPONENT_ENABLED_STATE_DEFAULT`. */
    @Synchronized
    fun settingFor(className: String): Int =
        binding?.states?.get(className) ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    /** The stored setting for the application as a whole. */
    @Synchronized
    fun applicationSetting(): Int =
        binding?.states?.get(APPLICATION_KEY) ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    /**
     * Whether a manifest entry is enabled *now*.
     *
     * `COMPONENT_ENABLED_STATE_DEFAULT` means "whatever the manifest said", which is
     * exactly [ComponentEntry.enabled]; the other two override it. The application's own
     * setting is not folded in here: disabling an application is a different question from
     * disabling one of its components, and an app that disables itself has asked to be
     * uninstallable-looking rather than to have its manifest rewritten.
     */
    fun isEnabled(entry: ComponentEntry): Boolean = when (settingFor(entry.className)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
        else -> entry.enabled
    }

    /** Records a component's new state and writes the record. */
    @Synchronized
    fun set(className: String, newState: Int) = store(className, newState, "component")

    /** Records the application's own state and writes the record. */
    @Synchronized
    fun setApplication(newState: Int) = store(APPLICATION_KEY, newState, "application")

    private fun store(key: String, newState: Int, kind: String) {
        val b = binding ?: return
        if (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) b.states.remove(key)
        else b.states[key] = newState
        persist(b)
        Diagnostics.info(
            DiagChannel.PROCESS, "COMPONENT_STATE_SET",
            mapOf(
                "package" to b.packageName,
                "vuid" to b.vuid.toString(),
                kind to key,
                "state" to newState.toString(),
            ),
        )
    }

    private fun persist(b: Binding) {
        runCatching {
            b.file.parentFile?.mkdirs()
            val props = Properties()
            for ((k, v) in b.states) props.setProperty(k, v.toString())
            b.file.outputStream().use { props.store(it, "UNIQUE component state") }
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "COMPONENT_STATE_NOT_WRITTEN",
                mapOf("file" to b.file.path, "error" to it.toString()),
            )
        }
    }
}
