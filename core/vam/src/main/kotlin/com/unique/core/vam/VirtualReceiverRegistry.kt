package com.unique.core.vam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.apk.IntentFilterEntry
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Delivers broadcasts to a guest's manifest-declared receivers.
 *
 * Manifest receivers cannot work the way activities and services do. The system will not
 * deliver to a component of a package it never installed, and unlike a service there is
 * no long-lived object to stand in for: a receiver is instantiated, run and discarded.
 *
 * So UNIQUE registers a *dynamic* receiver of its own for each action the guest declares,
 * and when one fires it instantiates the guest's `BroadcastReceiver` from the guest's
 * class loader and calls `onReceive` with the guest's `Context`. That is what the
 * platform would do; the only difference is who holds the registration.
 *
 * ## What this cannot do
 *
 * A dynamic registration lives only as long as the process. A manifest receiver's whole
 * point is often to *wake* a dead process, and that is not possible here: it needs
 * `:server` to hold the registration and start the virtual process on delivery. Until
 * then, a guest receives broadcasts only while it is already running, and
 * [registeredActions] reports exactly what is live so the gap is visible rather than
 * mysterious.
 *
 * Implicit-broadcast restrictions from Android 8 apply to UNIQUE's registration just as
 * they would to the guest's, so the set of system actions that can be delivered is
 * genuinely smaller than the manifest asks for. Actions that cannot be registered are
 * reported individually.
 *
 * Android 14's `IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS` (compat change
 * 229362273, on from `targetSdk` 34) is the second limit, and it is easy to mistake for a
 * bug in this class: an *implicit* broadcast - one with neither a component nor a package
 * - is matched only against *exported* filters. So a sender inside UNIQUE that means a
 * particular guest must scope the intent with `setPackage`, and a guest receiver declared
 * `android:exported="false"` will not see a system broadcast that arrives implicitly.
 * Closing that needs `:server` to hold the registration and re-dispatch, the same
 * mechanism a dead process needs; it is not something this class can do from inside the
 * guest.
 */
object VirtualReceiverRegistry {

    private val registered = LinkedHashMap<String, BroadcastReceiver>()

    /** Actions currently live in this process. Shown in diagnostics. */
    val registeredActions: Set<String> get() = synchronized(this) { registered.keys.toSet() }

    @Synchronized
    fun install(ready: AppBootstrap.Result.Ready) {
        val context = ready.application
        val receivers = ready.manifest.components.filter {
            it.kind == ComponentKind.RECEIVER && it.enabled && it.intentFilters.isNotEmpty()
        }

        var installed = 0
        val failed = ArrayList<String>()
        for (entry in receivers) {
            for (filterEntry in entry.intentFilters) {
                val filter = buildFilter(filterEntry) ?: continue
                val key = "${entry.className}#${filterEntry.actions.joinToString(",")}"
                if (key in registered) continue

                val bridge = GuestReceiverBridge(ready, entry)
                val ok = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // The guest's own declaration decides. Registering everything
                        // NOT_EXPORTED would be safer in isolation but is not what the
                        // app asked for, and registering everything EXPORTED would let
                        // any app on the device poke a receiver its author marked
                        // private. `exported` is already resolved by ManifestReader to
                        // Android's own default - true when the component has a filter
                        // and says nothing.
                        val flag = if (entry.exported) Context.RECEIVER_EXPORTED
                        else Context.RECEIVER_NOT_EXPORTED
                        context.registerReceiver(bridge, filter, flag)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        context.registerReceiver(bridge, filter)
                    }
                    true
                }.getOrElse {
                    failed += "${filterEntry.actions.joinToString(",")}: $it"
                    false
                }
                if (ok) {
                    registered[key] = bridge
                    installed++
                }
            }
        }

        Diagnostics.info(
            DiagChannel.PROCESS, "RECEIVERS_REGISTERED",
            mapOf(
                "package" to ready.params.packageName,
                "declared" to receivers.size.toString(),
                "registered" to installed.toString(),
                "actions" to registered.keys.joinToString(";").take(400),
                "exported" to receivers.count { it.exported }.toString(),
            ),
        )
        if (failed.isNotEmpty()) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "RECEIVER_REGISTRATION_REFUSED",
                mapOf("package" to ready.params.packageName, "reasons" to failed.joinToString("; ").take(400)),
            )
        }
    }

    private fun buildFilter(entry: IntentFilterEntry): IntentFilter? {
        if (entry.actions.isEmpty()) return null
        val filter = IntentFilter()
        entry.actions.forEach(filter::addAction)
        entry.categories.forEach(filter::addCategory)
        entry.schemes.forEach(filter::addDataScheme)
        entry.mimeTypes.forEach { runCatching { filter.addDataType(it) } }
        filter.priority = entry.priority
        return filter
    }

    /**
     * Instantiates the guest's receiver and runs it.
     *
     * A new instance per delivery, which is what the platform does for manifest
     * receivers: they are explicitly not allowed to hold state between broadcasts.
     */
    private class GuestReceiverBridge(
        private val ready: AppBootstrap.Result.Ready,
        private val entry: ComponentEntry,
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val loader = ready.application.classLoader
            val result = runCatching {
                val clazz = Class.forName(entry.className, true, loader)
                val receiver = clazz.getDeclaredConstructor().newInstance() as BroadcastReceiver
                receiver.onReceive(ready.application, intent)
            }
            result.onSuccess {
                Diagnostics.info(
                    DiagChannel.PROCESS, "BROADCAST_DELIVERED",
                    mapOf("receiver" to entry.className, "action" to (intent.action ?: "-")),
                )
            }.onFailure {
                Diagnostics.error(
                    DiagChannel.PROCESS, "BROADCAST_DELIVERY_FAILED",
                    mapOf(
                        "receiver" to entry.className,
                        "action" to (intent.action ?: "-"),
                        "error" to it.toString(),
                    ),
                )
            }
        }
    }
}
